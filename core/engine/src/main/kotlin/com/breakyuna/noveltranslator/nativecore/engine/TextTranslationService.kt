package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay

/** Bounded page streaming with durable execution/commit checkpoints; no automatic replay. */
class TextTranslationService(
    private val runtime: RuntimePort, private val projects: TextProjectPort,
    private val writes: TranslationCommitPort, private val gateway: TranslationModelGateway,
    private val runs: DurableRunExecutor,
    private val tasks: TranslationTaskPort? = null,
    private val ledger: UsageLedgerReadPort? = null,
    private val retryCheckpoints: TranslationRetryCheckpointPort? = null,
    private val laboratory: LaboratorySettingsProvider? = null,
) {
    private val active = java.util.concurrent.atomic.AtomicReference<RunLease?>(null)
    fun stop(): Boolean = active.get()?.let { runtime.requestStop(it) } ?: false

    suspend fun translatePage(
        ref: LocalProjectRef, identity: RunIdentity, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int = 40,
        options: RemoteTranslationOptions? = null, progress: (TranslationProgress) -> Unit = {},
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(identity.projectId == ref.projectId && identity.chatId == null && pageSize in 1..100)
        currentCoroutineContext().ensureActive()
        runs.execute(ref, identity, RuntimeOwner.TRANSLATION) { lease, recorder ->
            translateReservedPage(ref, lease, recorder, configuration, expectedRevision, afterId, pageSize, progress, options)
        }
    }

    /** A single durable run streams the entire project, retaining only one page at a time. */
    suspend fun translateProject(
        ref: LocalProjectRef, identity: RunIdentity, configuration: ModelConfigurationRef,
        expectedRevision: Long, pageSize: Int = 40, options: RemoteTranslationOptions? = null, progress: (TranslationProgress) -> Unit = {},
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(identity.projectId == ref.projectId && identity.chatId == null && pageSize in 1..100)
        currentCoroutineContext().ensureActive()
        runs.execute(ref, identity, RuntimeOwner.TRANSLATION) { lease, recorder ->
            translateReservedProject(ref, lease, recorder, configuration, expectedRevision, pageSize, progress, options)
        }
    }

    /** User-confirmed continuation of an existing fixed task, never a re-query or a new logical run. */
    suspend fun resume(spec: TranslationTaskSpec, progress: (TranslationProgress) -> Unit = {}): TranslationProgress =
        withContext(Dispatchers.Default) {
            require(tasks != null)
            runs.execute(spec.ref, spec.run, RuntimeOwner.TRANSLATION) { lease, recorder ->
                translateReserved(spec.ref, lease, recorder, spec.configuration, spec.revision, spec.afterId,
                    spec.pageSize, spec.wholeProject, progress, spec.options, resuming = true)
            }
        }

    /** Caller already owns the durable execution; this method never reserves a second run. */
    internal suspend fun translateReservedPage(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions? = null, resuming: Boolean = false,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        afterId, pageSize, false, progress, options, resuming)

    internal suspend fun translateReservedProject(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, pageSize: Int, progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions? = null, resuming: Boolean = false,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        0, pageSize, true, progress, options, resuming)

    private suspend fun translateReserved(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, wholeProject: Boolean,
        progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions?, resuming: Boolean,
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == null && pageSize in 1..100)
        val identity = lease.identity
        val lab = laboratory?.current() ?: LaboratorySettings.DEFAULT
        val deduplicate = options != null && (options.deduplicateSameFile || lab.duplicateSourceDeduplication)
        fun sourceFor(text: String): String =
            if (options != null && lab.cleanRuby) RubyCleaner.cleanConservative(text) else text
        fun promptFor(prompt: String): String =
            if (options != null && lab.promptEnhancement) LaboratoryPromptEnhancement.apply(prompt) else prompt
        active.set(lease)
        try {
            val policySnapshot = if (options != null) {
                val policies = projects as? ProjectTextPolicyPort ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
                policies.textPolicy(ref)
            } else null
            require(policySnapshot == null || (policySnapshot.ref == ref && policySnapshot.revision == expectedRevision))
            val context = currentCoroutineContext()
            val policy = policySnapshot?.policy ?: ProjectTextPolicy()
            val processor = if (options != null) TextPolicyProcessor(policy) { context.ensureActive() } else null
            // Freeze enabled membership under the run lease; disabled source files must
            // not enter a paid request simply because their rows occur in this page.
            val assetPort = projects as? ProjectAssetPort
            val assets = assetPort?.assets(ref)
            val enabledAssets = assets?.filter { it.enabled }?.mapTo(HashSet()) { it.id }
            val pagePolicy = assetPort?.let { ProjectPageTranslationPolicy(it.projectSettings(ref), requireNotNull(assets)) }
            val task = if (options != null && tasks != null) tasks.open(lease,
                TranslationTaskSpec(ref, identity, configuration, expectedRevision, afterId, pageSize, wholeProject, options), resuming) else null
            var revision = task?.revision ?: expectedRevision
            var committed = task?.committed ?: 0
            var requests = task?.requests ?: 0
            val initialRevision = task?.initialRevision ?: expectedRevision
            if (task != null) recorder.checkpoint(RunCheckpoint(StoredRunStage.ACCEPTED, revision, committed, requests))
            var usage = unknownRequestUsage()
            var totals = if (task != null) ledger?.summary(UsageLedgerQuery(ref, run = identity))?.totals ?: RequestUsageTotals()
                else RequestUsageTotals()
            var cursor = afterId
            if (task != null) progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
            fun checkPause() {
                if (runtime.snapshot().activeRun?.phase == RuntimePhase.PAUSING) throw ForegroundTranslationPaused()
            }
            suspend fun applyEdits(edits: List<ManualTextEdit>) {
                currentCoroutineContext().ensureActive()
                recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTING, revision, committed, requests, edits.map { it.commandId }))
                if (task != null) tasks?.committing(lease, revision, edits)
                revision = writes.commitTranslation(lease, TranslationBatch(ref, revision, edits))
                committed += edits.size
                withContext(NonCancellable) {
                    if (task != null) tasks?.committed(lease, revision, committed)
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTED, revision, committed, requests))
                }
                progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
            }
            suspend fun drainStaged() {
                if (task == null) return
                while (true) {
                    val edits = tasks?.staged(lease).orEmpty()
                    if (edits.isEmpty()) break
                    applyEdits(edits)
                }
            }
            suspend fun commitResults(results: List<Pair<TextProjectItem, String>>) {
                if (task != null) {
                    tasks?.stage(lease, results.map { (item, text) -> TranslationTaskOutput(item.id, item.fingerprint, text) })
                    drainStaged()
                } else for (chunk in results.chunked(20)) {
                    applyEdits(chunk.mapIndexed { offset, (item, text) -> ManualTextEdit(
                        CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()), item.fingerprint, text) })
                }
            }
            suspend fun terminateItemFailure(failure: AgentModelFailure, targets: List<TextProjectItem>): Nothing {
                val running = runtime.snapshot().activeRun
                if (running?.identity == identity && running.phase == RuntimePhase.PAUSING) throw failure
                if (running == null || running.identity != identity || running.phase != RuntimePhase.RUNNING)
                    throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
                val itemFailure = failure.kind in setOf(ModelFailureKind.PARSING, ModelFailureKind.TRUNCATED,
                    ModelFailureKind.CONTENT, ModelFailureKind.CONTENT_FILTERED, ModelFailureKind.CONTEXT_OVERFLOW,
                    ModelFailureKind.EMPTY_RESPONSE)
                val failures = targets.filter { it.status == TextItemStatus.NONE }
                if (itemFailure && options != null && failures.isNotEmpty()) {
                    val failureStore = writes as? TranslationFailurePort ?: throw failure
                    for (chunk in failures.chunked(20)) {
                        currentCoroutineContext().ensureActive()
                        val edits = chunk.mapIndexed { offset, item -> ManualTextEdit(
                            CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()),
                            item.fingerprint, item.translation, TextItemStatus.ERROR) }
                        recorder.checkpoint(RunCheckpoint(StoredRunStage.MARKING_FAILURE, revision, committed, requests,
                            edits.map { it.commandId }))
                        if (task != null) tasks?.committing(lease, revision, edits, failures = true)
                        revision = failureStore.markTranslationFailures(lease, TranslationFailureBatch(ref, revision, edits))
                        withContext(NonCancellable) {
                            if (task != null) tasks?.committed(lease, revision, committed)
                            recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTED, revision, committed, requests))
                        }
                        progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    }
                }
                if (task != null) tasks?.failed(lease)
                throw failure
            }
            if (task != null && resuming) {
                val response = tasks?.response(lease)
                if (response != null) {
                    val primary = response.outputs.associate { output ->
                        output.id to (projects.item(ref, output.id)?.item ?: throw TranslationTaskNeedsReview())
                    }
                    val outputs = response.outputs.associateBy { it.id }
                    val restored = response.targets.map { id ->
                        currentCoroutineContext().ensureActive()
                        val item = projects.item(ref, id)?.item ?: throw TranslationTaskNeedsReview()
                        val output = outputs[id] ?: if (deduplicate && item.metadata.isDefault)
                            response.outputs.singleOrNull { value ->
                                val source = primary.getValue(value.id)
                                source.metadata.isDefault && source.assetId == item.assetId && source.source == item.source
                            } else null
                        if (output == null) throw TranslationTaskNeedsReview()
                        val prepared = processor?.prepare(sourceFor(item.source))
                        val text = if (prepared != null) requireNotNull(processor).finish(prepared, output.text) else output.text
                        TranslationTaskOutput(item.id, item.fingerprint, text)
                    }
                    tasks?.stage(lease, restored)
                }
                drainStaged()
            }
            var savedRetry = if (task != null) retryCheckpoints?.read(lease, ref, configuration) else null
            // An explicit queue member-retry reopens this same task with a bounded manual
            // selection. It grants one request per selected member and no automatic replacement
            // budget, so the previous terminal checkpoint must not block or rewrite it. All
            // checkpoint writes are skipped for this manual mode; queue decisions and request
            // audits remain the durable evidence.
            val manualRetrySelection = if (task != null && resuming) {
                (tasks as? TranslationTaskRetrySelectionPort)?.retrySelection(lease).orEmpty()
            } else emptySet()
            // Explicit skips stay unfinished for completion evidence but must never be requested
            // again when a resume continues the remaining members of the same fixed task.
            val skipSelection = if (task != null && resuming) {
                (tasks as? TranslationTaskSkipSelectionPort)?.skipSelection(lease).orEmpty()
            } else emptySet()
            val manualRetry = manualRetrySelection.isNotEmpty()
            if (manualRetry) savedRetry = null
            savedRetry?.let { saved ->
                require(saved.initialAdditional == options?.additionalRequestsPerBatch)
                saved.terminalFailure?.let { throw AgentModelFailure(it) }
                val activeRetry = saved.active
                if (activeRetry != null) {
                    val items = activeRetry.ids.map { id -> projects.item(ref, id)?.item ?: throw TranslationTaskNeedsReview() }
                    val pending = tasks?.remaining(lease, ref, items) ?: throw TranslationTaskNeedsReview()
                    // A canonical task receipt must prove every active primary was committed.
                    // An unknown/failed/unsent active attempt is not permission to replay it.
                    if (pending.isNotEmpty()) throw TranslationTaskNeedsReview()
                    savedRetry = TranslationRetryCheckpoint(saved.targets, saved.initialAdditional,
                        saved.remainingAdditional, saved.pending, notBeforeMillis = saved.notBeforeMillis)
                    withContext(NonCancellable) { retryCheckpoints?.write(lease, ref, configuration, requireNotNull(savedRetry)) }
                }
            }
            do {
                currentCoroutineContext().ensureActive()
                checkPause()
                val page = projects.items(ref, revision, cursor, pageSize)
                // Check the revision even for an empty/no-op page before reporting success.
                require(page.revision == revision)
                val mode = options?.startMode ?: TranslationStartMode.NEW
                val remaining = if (task != null) tasks?.remaining(lease, ref, page.items) else null
                val eligible = page.items.filter {
                    it.id !in skipSelection &&
                        (remaining == null || it.id in remaining) &&
                        // A manual retry may include only the explicitly selected ERROR items;
                        // unselected failures are never silently swept in by a broad mode.
                        (if (it.status == TextItemStatus.ERROR && manualRetry) {
                            it.id in manualRetrySelection
                        } else {
                            mode.accepts(it.status)
                        }) &&
                        it.source.isNotBlank() &&
                        (enabledAssets == null || it.assetId in enabledAssets) && pagePolicy?.accepts(it) != false
                }
                val duplicates = if (deduplicate) TranslationDeduplication.coordinate(
                    eligible.filter { it.metadata.isDefault }.map { item -> TranslationItemSnapshot(item.id,
                        item.assetId, item.row, item.source, TextItemStatus.NONE, item.fingerprint) }) else null
                val byId = eligible.associateBy { it.id }
                val derived = duplicates?.groups?.associate { group ->
                    requireNotNull(group.primaryId) to group.derivedIds.map { byId.getValue(it) }
                } ?: emptyMap()
                val pending = mutableListOf<TextProjectItem>()
                for (item in eligible) {
                    if (duplicates?.primaryFor(item.id)?.let { it != item.id } == true) continue
                    val existing = if (deduplicate && item.metadata.isDefault) {
                        val reuse = projects as? TranslationReusePort ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
                        reuse.committedDuplicate(ref, revision, initialRevision, item.id)
                    } else null
                    if (existing == null) pending.add(item) else {
                        require(existing.revision == revision && existing.item.assetId == item.assetId &&
                            existing.item.source == item.source && existing.item.status == TextItemStatus.PROCESSED &&
                            existing.item.metadata.isDefault && existing.item.translation.isNotBlank())
                        commitResults((listOf(item) + derived[item.id].orEmpty()).map { it to existing.item.translation })
                    }
                }
                // Planning remains page-bounded; bodies never accumulate across the project.
                // Prepare before measuring so expanded protection/replacement text is budgeted.
                val prepared = LinkedHashMap<ItemId, TextPolicyProcessor.Prepared?>()
                val snapshots = pending.map { item ->
                    currentCoroutineContext().ensureActive()
                    if (item.source.length > 65_500) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                    val transformed = processor?.prepare(sourceFor(item.source))
                    val source = transformed?.text ?: item.source
                    if (source.length > 65_500) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                    prepared[item.id] = transformed
                    TranslationItemSnapshot(item.id, item.assetId, item.row, source, item.status, item.fingerprint)
                }
                val reservedRetryIds = savedRetry?.takeIf { it.pending.isNotEmpty() }?.targets?.mapTo(HashSet()) { it.id }.orEmpty()
                val plan = TranslationPlanner(TranslationPlanLimits(
                    maxItemsPerUnit = options?.batchSize ?: 20,
                    maxCharactersPerUnit = options?.batchCharacters ?: 32_768,
                    // No extra line/token setting has been exposed yet. Preserve existing
                    // character/item limits while using the shared asset-aware planner.
                    maxLinesPerUnit = 4_096,
                    maxTokensPerUnit = 1_000_000,
                    precedingLines = 0,
                )).plan(TranslationTargetSnapshot(ref, revision, snapshots.filter { it.id !in reservedRetryIds }),
                    TranslationModeCommand(ref, TranslationRunMode.valueOf(mode.name), revision))
                check(plan.skipped.isEmpty()) { "Prepared input was unexpectedly excluded" }
                val sessions = ArrayList<Pair<Long, TranslationRetrySession>>()
                val saved = savedRetry
                if (saved != null && saved.pending.isNotEmpty()) {
                    val ids = saved.pending.flatMap { it.ids }
                    val local = snapshots.filter { it.id in ids }
                    if (local.isNotEmpty()) {
                        if (local.size != ids.size || local.map { it.assetId }.distinct().size != 1) throw TranslationTaskNeedsReview()
                        sessions.add(local.first().assetId to TranslationRetrySession.restore(local, saved))
                        savedRetry = null
                    }
                }
                plan.units.forEach { unit -> sessions.add(unit.assetId to
                    TranslationRetrySession.start(unit.items,
                        if (manualRetry) 0 else (options?.additionalRequestsPerBatch ?: 0))) }
                for ((unitAssetId, retries) in sessions) {
                    if (task != null && !manualRetry) retryCheckpoints?.write(lease, ref, configuration, retries.checkpoint)
                    while (retries.pending) {
                        currentCoroutineContext().ensureActive()
                        checkPause()
                        val due = retries.checkpoint.notBeforeMillis
                        while (due > System.currentTimeMillis().coerceAtLeast(0)) {
                            checkPause()
                            delay((due - System.currentTimeMillis().coerceAtLeast(0)).coerceIn(1, 30_000))
                        }
                        currentCoroutineContext().ensureActive()
                        checkPause()
                        val block = retries.next()
                        val inputs = block.items.map { byId.getValue(it.id) }
                        val precedings = if (options != null && options.precedingLines > 0) {
                            val contextStore = projects as? TranslationContextPort
                                ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
                            val earlier = contextStore.precedingTranslationItems(ref, revision, inputs.first().id)
                            require(earlier.revision == revision && earlier.items.all { it.assetId == unitAssetId })
                            val contextual = earlier.items.map { item -> TranslationItemSnapshot(item.id, item.assetId,
                                item.row, item.source, item.status, item.fingerprint) } + block.items
                            val contextPlan = TranslationPlanner(TranslationPlanLimits(
                                maxItemsPerUnit = options.batchSize,
                                maxCharactersPerUnit = options.batchCharacters,
                                maxLinesPerUnit = 4_096, maxTokensPerUnit = 1_000_000,
                                precedingLines = options.precedingLines,
                                precedingCharacters = 2_048, precedingTokens = 2_048,
                            )).plan(TranslationTargetSnapshot(ref, revision, contextual),
                                TranslationModeCommand(ref, TranslationRunMode.valueOf(mode.name), revision,
                                    TranslationCommandScope.items(block.items.map { it.id })))
                            contextPlan.units.first().precedings.map {
                                TranslationContextLine(it.id, it.source, it.fingerprint)
                            }
                        } else emptyList()
                        val request = TranslationModelRequest(ref, identity,
                            RequestAttemptId(UUID.randomUUID().toString()), configuration,
                            inputs.map { item ->
                                val text = prepared[item.id]
                                TranslationInput(item.id, text?.text ?: item.source, item.fingerprint, text?.terms ?: emptyList())
                            }, options, policySnapshot?.fingerprint, promptFor(if (policy.promptEnabled) policy.prompt else ""), precedings)
                        val beforeDispatch: suspend () -> Unit = {
                            currentCoroutineContext().ensureActive()
                            retries.begin(request.attempt)
                            if (task != null && !manualRetry) retryCheckpoints?.write(lease, ref, configuration, retries.checkpoint)
                            requests++
                            if (task != null) tasks?.requesting(lease, revision, request.attempt,
                                inputs.flatMap { listOf(it.id) + derived[it.id].orEmpty().map { item -> item.id } }, requests)
                            recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUESTING, revision, committed, requests,
                                translationAttempt = if (options != null) request.attempt else null))
                            progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                        }
                        val dispatchGateway = gateway as? DispatchAwareTranslationGateway
                        val response = try {
                            if (dispatchGateway != null) dispatchGateway.completePrepared(lease, request, beforeDispatch)
                            else { beforeDispatch(); gateway.complete(lease, request) }
                        } catch (failure: AgentModelFailure) {
                            // A scheduling/configuration rejection did not enter the request checkpoint.
                            if (retries.checkpoint.active == null) throw failure
                            if (options != null) recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUEST_FAILED,
                                revision, committed, requests, translationAttempt = request.attempt))
                            val failedTargets = inputs.flatMap { listOf(it) + derived[it.id].orEmpty() }
                            // Failed/ambiguous responses remain unknown in the runtime summary;
                            // any exact HTTP receipt is retained separately by the audit store.
                            totals = totals.add(unknownRequestUsage())
                            usage = addUsage(usage, unknownRequestUsage(), requests == 1)
                            progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                            retries.failed(failure.kind, System.currentTimeMillis().coerceAtLeast(0))
                            if (task != null && !manualRetry) withContext(NonCancellable) {
                                retryCheckpoints?.write(lease, ref, configuration, retries.checkpoint)
                            }
                            if (retries.checkpoint.terminalFailure != null) terminateItemFailure(failure, failedTargets)
                            // Only remote options explicitly confirmed by the user can reach here.
                            // Every replacement request gets its own attempt/audit and shared gate.
                            val running = runtime.snapshot().activeRun
                            if (running?.identity == identity && running.phase == RuntimePhase.PAUSING) throw failure
                            if (running == null || running.identity != identity || running.phase != RuntimePhase.RUNNING)
                                throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
                            currentCoroutineContext().ensureActive()
                            continue
                        }
                        recorder.checkpoint(RunCheckpoint(StoredRunStage.RESPONSE_RECORDED, revision, committed, requests))
                        usage = addUsage(usage, response.usage, requests == 1)
                        totals = totals.add(response.usage)
                        progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                        val outputs = response.outputs.associateBy { it.id }
                        val results = inputs.flatMap { item ->
                            val text = prepared[item.id]?.let { requireNotNull(processor).finish(it, outputs.getValue(item.id).text) }
                                ?: outputs.getValue(item.id).text
                            (listOf(item) + derived[item.id].orEmpty()).map { it to text }
                        }
                        commitResults(results)
                        retries.succeeded()
                        if (task != null && !manualRetry) withContext(NonCancellable) {
                            retryCheckpoints?.write(lease, ref, configuration, retries.checkpoint)
                        }
                    }
                }
                if (!wholeProject || page.items.isEmpty()) break
                val nextCursor = page.items.last().id.value
                check(nextCursor > cursor) { "Project cursor did not advance" }
                cursor = nextCursor
            } while (true)
            checkPause()
            if (savedRetry?.pending?.isNotEmpty() == true) throw TranslationTaskNeedsReview()
            if (task != null) tasks?.finished(lease)
            TranslationProgress(committed, requests, revision, usage, totals, identity)
        } finally { active.compareAndSet(lease, null) }
    }

    private fun addUsage(left: RequestUsage, right: RequestUsage, first: Boolean): RequestUsage {
        if (first) return right
        fun sum(a: TokenMeasurement, b: TokenMeasurement): TokenMeasurement {
            val leftTokens = a.tokens
            val rightTokens = b.tokens
            if (leftTokens == null || rightTokens == null) return TokenMeasurement(null, UsageSource.UNKNOWN)
            val combined = try { Math.addExact(leftTokens, rightTokens) }
            catch (_: ArithmeticException) { return TokenMeasurement(null, UsageSource.UNKNOWN) }
            // The compatibility projection is Long-bounded. Exact facts remain in usageTotals.
            return TokenMeasurement(combined,
                if (a.source == UsageSource.ACTUAL && b.source == UsageSource.ACTUAL) UsageSource.ACTUAL else UsageSource.ESTIMATED)
        }
        return RequestUsage(sum(left.input, right.input), sum(left.cacheRead, right.cacheRead),
            sum(left.cacheWrite, right.cacheWrite), sum(left.thinking, right.thinking), sum(left.output, right.output))
    }
}
