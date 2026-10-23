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
        progress: (TranslationProgress) -> Unit = {},
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(identity.projectId == ref.projectId && identity.chatId == null && pageSize in 1..100)
        currentCoroutineContext().ensureActive()
        runs.execute(ref, identity, RuntimeOwner.TRANSLATION) { lease, recorder ->
            translateReservedPage(ref, lease, recorder, configuration, expectedRevision, afterId, pageSize, progress)
        }
    }

    /** A single durable run streams the entire project, retaining only one page at a time. */
    suspend fun translateProject(
        ref: LocalProjectRef, identity: RunIdentity, configuration: ModelConfigurationRef,
        expectedRevision: Long, pageSize: Int = 40, progress: (TranslationProgress) -> Unit = {},
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(identity.projectId == ref.projectId && identity.chatId == null && pageSize in 1..100)
        currentCoroutineContext().ensureActive()
        runs.execute(ref, identity, RuntimeOwner.TRANSLATION) { lease, recorder ->
            translateReservedProject(ref, lease, recorder, configuration, expectedRevision, pageSize, progress)
        }
    }

    /** Caller already owns the durable execution; this method never reserves a second run. */
    internal suspend fun translateReservedPage(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, progress: (TranslationProgress) -> Unit,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        afterId, pageSize, false, progress)

    internal suspend fun translateReservedProject(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, pageSize: Int, progress: (TranslationProgress) -> Unit,
    ): TranslationProgress = translateReserved(ref, lease, recorder, configuration, expectedRevision,
        0, pageSize, true, progress)

    private suspend fun translateReserved(
        ref: LocalProjectRef, lease: RunLease, recorder: RunProgressRecorder, configuration: ModelConfigurationRef,
        expectedRevision: Long, afterId: Long, pageSize: Int, wholeProject: Boolean,
        progress: (TranslationProgress) -> Unit,
    ): TranslationProgress = withContext(Dispatchers.Default) {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == null && pageSize in 1..100)
        val identity = lease.identity
        active.set(lease)
        try {
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
                    var chars = 0
                    while (index < pending.size && inputs.size < 20) {
                        val next = pending[index]
                        // Reserve room for the simulation marker as well as a normal model response.
                        if (next.source.length > 65_500) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                        if (inputs.isNotEmpty() && chars + next.source.length > 32_768) break
                        inputs.add(next); chars += next.source.length; index++
                    }
                    requests++
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.REQUESTING, revision, committed, requests))
                    progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    val response = gateway.complete(lease, TranslationModelRequest(ref, identity,
                        RequestAttemptId(UUID.randomUUID().toString()), configuration,
                        inputs.map { TranslationInput(it.id, it.source, it.fingerprint) }))
                    recorder.checkpoint(RunCheckpoint(StoredRunStage.RESPONSE_RECORDED, revision, committed, requests))
                    usage = addUsage(usage, response.usage, requests == 1)
                    totals = totals.add(response.usage)
                    progress(TranslationProgress(committed, requests, revision, usage, totals, identity))
                    val outputs = response.outputs.associateBy { it.id }
                    val edits = inputs.mapIndexed { offset, item -> ManualTextEdit(
                        CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()),
                        item.fingerprint, outputs.getValue(item.id).text,
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
