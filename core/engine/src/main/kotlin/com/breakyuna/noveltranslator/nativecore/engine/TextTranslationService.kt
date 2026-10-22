package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-local, sequential current-page slice. Does not claim durable run recovery. */
class TextTranslationService(
    private val runtime: RuntimePort, private val projects: TextProjectPort,
    private val writes: TranslationCommitPort, private val gateway: TranslationModelGateway,
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
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        active.set(lease)
        try {
            val page = projects.items(ref, expectedRevision, afterId, pageSize)
            val pending = page.items.filter { it.status == TextItemStatus.NONE && it.source.isNotBlank() }
            var revision = page.revision
            var committed = 0
            var requests = 0
            var usage = unknownRequestUsage()
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
                progress(TranslationProgress(committed, requests, revision, usage))
                val response = gateway.complete(lease, TranslationModelRequest(ref, identity,
                    RequestAttemptId(UUID.randomUUID().toString()), configuration,
                    inputs.map { TranslationInput(it.id, it.source, it.fingerprint) }))
                usage = addUsage(usage, response.usage, requests == 1)
                progress(TranslationProgress(committed, requests, revision, usage))
                val outputs = response.outputs.associateBy { it.id }
                val edits = inputs.mapIndexed { offset, item -> ManualTextEdit(
                    CommandId(UUID.randomUUID().toString()), ref, item.id, Math.addExact(revision, offset.toLong()),
                    item.fingerprint, outputs.getValue(item.id).text,
                ) }
                currentCoroutineContext().ensureActive()
                revision = writes.commitTranslation(lease, TranslationBatch(ref, revision, edits))
                committed += edits.size
                progress(TranslationProgress(committed, requests, revision, usage))
            }
            return@withContext TranslationProgress(committed, requests, revision, usage)
        } finally { runtime.finishRun(lease); active.compareAndSet(lease, null) }
    }

    private fun addUsage(left: RequestUsage, right: RequestUsage, first: Boolean): RequestUsage {
        if (first) return right
        fun sum(a: TokenMeasurement, b: TokenMeasurement): TokenMeasurement {
            val leftTokens = a.tokens
            val rightTokens = b.tokens
            if (leftTokens == null || rightTokens == null) return TokenMeasurement(null, UsageSource.UNKNOWN)
            return TokenMeasurement(Math.addExact(leftTokens, rightTokens),
                if (a.source == UsageSource.ACTUAL && b.source == UsageSource.ACTUAL) UsageSource.ACTUAL else UsageSource.ESTIMATED)
        }
        return RequestUsage(sum(left.input, right.input), sum(left.cacheRead, right.cacheRead),
            sum(left.cacheWrite, right.cacheWrite), sum(left.thinking, right.thinking), sum(left.output, right.output))
    }
}
