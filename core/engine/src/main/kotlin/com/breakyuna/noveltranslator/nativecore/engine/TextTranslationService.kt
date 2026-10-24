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

    /** Caller already owns the durable execution; this method never reserves a second run. */
    internal suspend fun translateReservedPage(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions? = null,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        afterId, pageSize, false, progress, options)

    internal suspend fun translateReservedProject(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, pageSize: Int, progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions? = null,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        0, pageSize, true, progress, options)

    private suspend fun translateReserved(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, wholeProject: Boolean,
        progress: (TranslationProgress) -> Unit, options: RemoteTranslationOptions?,
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == null && pageSize in 1..100)
        val identity = lease.identity
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
            val enabledAssets = (projects as? ProjectAssetPort)?.assets(ref)
                ?.filter { it.enabled }?.mapTo(HashSet()) { it.id }
            var revision = expectedRevision
            var committed = 0
            var requests = 0
            var usage = unknownRequestUsage()
            var totals = RequestUsageTotals()
            var cursor = afterId
            suspend fun commitResults(results: List<Pair<TextProjectItem, String>>) {
                for (chunk in results.chunked(20)) {
                    currentCoroutineContext().ensureActive()
                    val edits = chunk.mapIndexed { offset, (item, text) -> ManualTextEdit(
                        CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()),
                        item.fingerprint, text,
                    ) }
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTING, revision, committed, requests, edits.map { it.commandId }))
                    revision = writes.commitTranslation(lease, TranslationBatch(ref, revision, edits))
                    committed += edits.size
                    withContext(NonCancellable) {
                        recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTED, revision, committed, requests))
                    }
                    progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                }
            }
            suspend fun terminateItemFailure(failure: AgentModelFailure, targets: List<TextProjectItem>): Nothing {
                val running = runtime.snapshot().activeRun
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
                        revision = failureStore.markTranslationFailures(lease, TranslationFailureBatch(ref, revision, edits))
                        withContext(NonCancellable) {
                            recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTED, revision, committed, requests))
                        }
                        progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    }
                }
                throw failure
            }
            do {
                currentCoroutineContext().ensureActive()
                val page = projects.items(ref, revision, cursor, pageSize)
                // Check the revision even for an empty/no-op page before reporting success.
                require(page.revision == revision)
                val mode = options?.startMode ?: TranslationStartMode.NEW
                val eligible = page.items.filter { mode.accepts(it.status) && it.source.isNotBlank() &&
                    (enabledAssets == null || it.assetId in enabledAssets) }
                val duplicates = if (options?.deduplicateSameFile == true) TranslationDeduplication.coordinate(
                    eligible.filter { it.metadata.isDefault }.map { item -> TranslationItemSnapshot(item.id,
                        item.assetId, item.row, item.source, TextItemStatus.NONE, item.fingerprint) }) else null
                val byId = eligible.associateBy { it.id }
                val derived = duplicates?.groups?.associate { group ->
                    requireNotNull(group.primaryId) to group.derivedIds.map { byId.getValue(it) }
                } ?: emptyMap()
                val pending = mutableListOf<TextProjectItem>()
                for (item in eligible) {
                    if (duplicates?.primaryFor(item.id)?.let { it != item.id } == true) continue
                    val existing = if (options?.deduplicateSameFile == true && item.metadata.isDefault) {
                        val reuse = projects as? TranslationReusePort ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
                        reuse.committedDuplicate(ref, revision, expectedRevision, item.id)
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
                    val transformed = processor?.prepare(item.source)
                    val source = transformed?.text ?: item.source
                    if (source.length > 65_500) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                    prepared[item.id] = transformed
                    TranslationItemSnapshot(item.id, item.assetId, item.row, source, item.status, item.fingerprint)
                }
                val plan = TranslationPlanner(TranslationPlanLimits(
                    maxItemsPerUnit = options?.batchSize ?: 20,
                    maxCharactersPerUnit = options?.batchCharacters ?: 32_768,
                    // No extra line/token setting has been exposed yet. Preserve existing
                    // character/item limits while using the shared asset-aware planner.
                    maxLinesPerUnit = 4_096,
                    maxTokensPerUnit = 1_000_000,
                    precedingLines = 0,
                )).plan(TranslationTargetSnapshot(ref, revision, snapshots),
                    TranslationModeCommand(ref, TranslationRunMode.valueOf(mode.name), revision))
                check(plan.skipped.isEmpty()) { "Prepared input was unexpectedly excluded" }
                for (unit in plan.units) {
                    var remainingAdditional = options?.additionalRequestsPerBatch ?: 0
                    val retryPlanner = TranslationRetryPlanner(TranslationRetryBudget(
                        maxAttempts = remainingAdditional.coerceAtLeast(1),
                        maxSplitDepth = remainingAdditional,
                    ))
                    val queue = java.util.ArrayDeque<TranslationRetryContext>()
                    queue.add(TranslationRetryContext(unit.items))
                    while (queue.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val block = queue.removeFirst()
                        val inputs = block.items.map { byId.getValue(it.id) }
                        val precedings = if (options != null && options.precedingLines > 0) {
                            val contextStore = projects as? TranslationContextPort
                                ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
                            val earlier = contextStore.precedingTranslationItems(ref, revision, inputs.first().id)
                            require(earlier.revision == revision && earlier.items.all { it.assetId == unit.assetId })
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
                            }, options, policySnapshot?.fingerprint, if (policy.promptEnabled) policy.prompt else "", precedings)
                        val beforeDispatch: suspend () -> Unit = {
                            currentCoroutineContext().ensureActive()
                            requests++
                            recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUESTING, revision, committed, requests,
                                translationAttempt = if (options != null) request.attempt else null))
                            progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                        }
                        val dispatchGateway = gateway as? DispatchAwareTranslationGateway
                        val response = try {
                            if (dispatchGateway != null) dispatchGateway.completePrepared(lease, request, beforeDispatch)
                            else { beforeDispatch(); gateway.complete(lease, request) }
                        } catch (failure: AgentModelFailure) {
                            if (options != null) recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUEST_FAILED,
                                revision, committed, requests, translationAttempt = request.attempt))
                            val failedTargets = inputs.flatMap { listOf(it) + derived[it.id].orEmpty() }
                            // Failed/ambiguous responses remain unknown in the runtime summary;
                            // any exact HTTP receipt is retained separately by the audit store.
                            totals = totals.add(unknownRequestUsage())
                            usage = addUsage(usage, unknownRequestUsage(), requests == 1)
                            progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                            if (remainingAdditional == 0) terminateItemFailure(failure, failedTargets)
                            val decision = retryPlanner.decide(block, failure.kind)
                            if (decision.outcome == TranslationRetryOutcome.PERMANENT ||
                                decision.retryBlocks.isEmpty() || decision.retryBlocks.size > remainingAdditional)
                                terminateItemFailure(failure, failedTargets)
                            remainingAdditional -= decision.retryBlocks.size
                            // Only remote options explicitly confirmed by the user can reach here.
                            // Every replacement request gets its own attempt/audit and shared gate.
                            val running = runtime.snapshot().activeRun
                            if (running == null || running.identity != identity || running.phase != RuntimePhase.RUNNING)
                                throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
                            if (decision.backoffMillis > 0) delay(decision.backoffMillis)
                            currentCoroutineContext().ensureActive()
                            decision.retryBlocks.asReversed().forEach {
                                queue.addFirst(TranslationRetryContext(it.items, it.attempts, it.splitDepth))
                            }
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
                    }
                }
                if (!wholeProject || page.items.isEmpty()) break
                val nextCursor = page.items.last().id.value
                check(nextCursor > cursor) { "Project cursor did not advance" }
                cursor = nextCursor
            } while (true)
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
