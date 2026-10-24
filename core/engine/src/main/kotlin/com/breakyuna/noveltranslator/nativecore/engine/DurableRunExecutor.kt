package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reserves before any suspension, journals before side effects, releases only after durable cleanup. */
class DurableRunExecutor(private val runtime: RuntimePort, private val journal: RunJournalPort) {
    suspend fun <T> execute(
        ref: LocalProjectRef, identity: RunIdentity, owner: RuntimeOwner,
        block: suspend (RunLease, RunProgressRecorder) -> T,
    ): T {
        require(ref.projectId == identity.projectId)
        currentCoroutineContext().ensureActive()
        val lease = runtime.beginRun(identity, owner)
        return executeReserved(ref, lease, owner, block = block)
    }

    /** Only engine-owned launchers transfer a synchronously reserved lease to this lifetime. */
    internal suspend fun <T> executeReserved(
        ref: LocalProjectRef, lease: RunLease, owner: RuntimeOwner,
        clearSelectedChat: ChatIdentity? = null,
        block: suspend (RunLease, RunProgressRecorder) -> T,
    ): T {
        var handle: RunJournalHandle? = null
        var terminal = StoredRunState.FAILED
        var failure: Throwable? = null
        val recorder = object : RunProgressRecorder {
            var waiting = false
            var approvalTerminal: StoredRunState? = null
            override suspend fun checkpoint(value: RunCheckpoint) {
                journal.checkpoint(requireNotNull(handle), value)
            }
            override fun waitingForApproval() { waiting = true }
            override fun approvalResult(state: TextApprovalState) {
                approvalTerminal = when (state) {
                    TextApprovalState.COMMITTED -> StoredRunState.SUCCEEDED
                    TextApprovalState.CONFLICT -> StoredRunState.FAILED
                    TextApprovalState.CANCELLED, TextApprovalState.REJECTED -> StoredRunState.CANCELLED
                    TextApprovalState.PENDING, TextApprovalState.CONSUMED -> StoredRunState.WAITING_APPROVAL
                }
            }
        }
        try {
            // Once reservation succeeds, cancellation must not skip recording/settling this execution.
            handle = withContext(NonCancellable) { journal.begin(ref, lease.identity, owner) }
            currentCoroutineContext().ensureActive()
            val result = coroutineScope { block(lease, recorder) }
            currentCoroutineContext().ensureActive()
            if (runtime.snapshot().activeRun?.phase != RuntimePhase.RUNNING) throw CancellationException("Run stopped")
            terminal = recorder.approvalTerminal ?: if (recorder.waiting) StoredRunState.WAITING_APPROVAL else StoredRunState.SUCCEEDED
            return result
        } catch (error: Throwable) {
            failure = error
            val phase = runtime.snapshot().activeRun?.phase
            terminal = when {
                phase == RuntimePhase.PAUSING && (error is CancellationException ||
                    error is RuntimeUnavailable && error.reason == RuntimeUnavailable.Reason.STOPPING) -> StoredRunState.PAUSED
                error is CancellationException || phase == RuntimePhase.STOPPING -> StoredRunState.CANCELLED
                else -> StoredRunState.FAILED
            }
            throw error
        } finally {
            try {
                handle?.let { value ->
                    try { withContext(NonCancellable) { journal.finish(value, terminal) } }
                    catch (cleanup: Exception) {
                        // Preserve cancellation/failure. A failed journal finalization remains RUNNING
                        // and is conservatively interrupted on the next process start.
                        if (failure == null || terminal == StoredRunState.PAUSED) throw cleanup
                    }
                }
            } finally { runtime.finishRun(lease, clearSelectedChat) }
        }
    }
}
