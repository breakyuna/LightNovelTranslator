package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

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
            var revision = expectedRevision
            var committed = 0
            var requests = 0
            var usage = unknownRequestUsage()
            var totals = RequestUsageTotals()
            var cursor = afterId
            do {
                currentCoroutineContext().ensureActive()
                val page = projects.items(ref, revision, cursor, pageSize)
                // Check the revision even for an empty/no-op page before reporting success.
                require(page.revision == revision)
                val pending = page.items.filter { it.status == TextItemStatus.NONE && it.source.isNotBlank() }
                var index = 0
                while (index < pending.size) {
                    currentCoroutineContext().ensureActive()
                    val inputs = mutableListOf<TextProjectItem>()
                    val prepared = LinkedHashMap<ItemId, TextPolicyProcessor.Prepared?>()
                    var chars = 0
                    while (index < pending.size && inputs.size < (options?.batchSize ?: 20)) {
                        val next = pending[index]
                        // Reserve room for the simulation marker as well as a normal model response.
                        if (next.source.length > 65_500) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                        val transformed = processor?.prepare(next.source)
                        val size = transformed?.text?.length ?: next.source.length
                        if (inputs.isNotEmpty() && chars + size > (options?.batchCharacters ?: 32_768)) break
                        prepared[next.id] = transformed
                        inputs.add(next); chars += size; index++
                    }
                    val request = TranslationModelRequest(ref, identity,
                        RequestAttemptId(UUID.randomUUID().toString()), configuration,
                        inputs.map { item ->
                            val text = prepared[item.id]
                            TranslationInput(item.id, text?.text ?: item.source, item.fingerprint, text?.terms ?: emptyList())
                        }, options, policySnapshot?.fingerprint, if (policy.promptEnabled) policy.prompt else "")
                    val beforeDispatch: suspend () -> Unit = {
                        currentCoroutineContext().ensureActive()
                        requests++
                        recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUESTING, revision, committed, requests,
                            translationAttempt = if (options != null) request.attempt else null))
                        progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    }
                    val dispatchGateway = gateway as? DispatchAwareTranslationGateway
                    val response = if (dispatchGateway != null)
                        dispatchGateway.completePrepared(lease, request, beforeDispatch)
                    else { beforeDispatch(); gateway.complete(lease, request) }
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.RESPONSE_RECORDED, revision, committed, requests))
                    usage = addUsage(usage, response.usage, requests == 1)
                    totals = totals.add(response.usage)
                    progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    val outputs = response.outputs.associateBy { it.id }
                    val edits = inputs.mapIndexed { offset, item -> ManualTextEdit(
                        CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()),
                        item.fingerprint, prepared[item.id]?.let { requireNotNull(processor).finish(it, outputs.getValue(item.id).text) }
                            ?: outputs.getValue(item.id).text,
                    ) }
                    currentCoroutineContext().ensureActive()
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTING, revision, committed, requests, edits.map { it.commandId }))
                    revision = writes.commitTranslation(lease, TranslationBatch(ref, revision, edits))
                    committed += edits.size
                    withContext(NonCancellable) {
                        recorder.checkpoint(RunCheckpoint(StoredRunStage.COMMITTED, revision, committed, requests))
                    }
                    progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
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
