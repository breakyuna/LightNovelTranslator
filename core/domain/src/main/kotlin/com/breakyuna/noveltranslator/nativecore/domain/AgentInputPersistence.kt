package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Explicit proof that one pending history append is no longer pending.
 *
 * [Confirmed] is the only way a persisted pending append may be consumed and must carry the exact
 * receipt the queue accepted. The receipt must also reference a persisted USER message in the same
 * Project/Chat; [AgentInputStatePort] verifies that identity. [Released] mirrors
 * [AgentInputQueue.releaseAppend]: the append failed before it entered history, so the draft
 * remains queued and can be retried under a new attempt. Both variants are explicit; a replacement
 * snapshot that simply omits [AgentInputQueue.pendingAppend] is rejected.
 */
sealed interface AgentInputAppendSettlement {
    data class Confirmed(val receipt: AgentInputAppendReceipt) : AgentInputAppendSettlement
    data class Released(val draftId: CommandId, val attemptId: RequestAttemptId) : AgentInputAppendSettlement
}

/**
 * Durable snapshot of exactly one Project/Chat input queue. [revision] is the storage-owned
 * compare-and-set token; it is not a queue decision and the store never advances the state machine.
 * [queue] remains the authority for ordering, pause state, Run binding and pending append.
 *
 * [pendingMessageId] is optional only while the host has reserved an append but has not yet written
 * the history message. [lastAppendReceipt] is store-owned audit data and is never supplied by a
 * caller that wants to clear a pending append; only [AgentInputAppendSettlement.Confirmed] changes it.
 *
 * Restoration returns facts only. Neither this snapshot nor the store sends a request, appends
 * history, injects a steer or replays an old receipt.
 */
class StoredAgentInputQueue(
    val ref: LocalProjectRef,
    val revision: Long,
    val queue: AgentInputQueue,
    val pendingMessageId: MessageId? = null,
    val lastAppendReceipt: AgentInputAppendReceipt? = null,
) {
    init {
        require(revision >= 0) { "Queue revision is negative" }
        val chat = queue.chat
        require(chat.projectId == ref.projectId) { "Queue Chat belongs to a different Project" }
        require(queue.run == null || (queue.run.projectId == ref.projectId && queue.run.chatId == chat.chatId)) {
            "Queue Run belongs to a different Project/Chat"
        }
        require(pendingMessageId == null || queue.pendingAppend != null) {
            "A pending message identity requires a pending append"
        }
        val receipt = lastAppendReceipt
        require(receipt == null || receipt.chat == chat) {
            "Append receipt belongs to a different Chat"
        }
        require(receipt == null || receipt.run.projectId == ref.projectId) {
            "Append receipt belongs to a different Project"
        }
    }

    /** Reuses this durable identity/revision with a queue advanced by the domain object itself. */
    fun withQueue(queue: AgentInputQueue): StoredAgentInputQueue {
        require(queue.chat == this.queue.chat) { "Queue Chat cannot change" }
        val nextPending = queue.pendingAppend
        val currentPending = this.queue.pendingAppend
        val samePending = nextPending != null && currentPending != null &&
            nextPending.draft.id == currentPending.draft.id &&
            nextPending.attemptId == currentPending.attemptId
        return StoredAgentInputQueue(
            ref,
            revision,
            queue,
            pendingMessageId.takeIf { samePending },
            lastAppendReceipt,
        )
    }

    /** Records the message identity once the host has actually appended the pending draft. */
    fun withPendingMessage(messageId: MessageId?): StoredAgentInputQueue =
        StoredAgentInputQueue(ref, revision, queue, messageId, lastAppendReceipt)

    override fun toString(): String =
        "StoredAgentInputQueue(chat=${queue.chat.chatId.value}, revision=$revision, " +
            "drafts=${queue.drafts.size}, pending=${queue.pendingAppend != null})"

    companion object {
        /** A queue that has not been persisted in this instance/generation yet. */
        fun initial(ref: LocalProjectRef, queue: AgentInputQueue): StoredAgentInputQueue =
            StoredAgentInputQueue(ref, 0, queue)
    }
}

/**
 * Durable, revisioned snapshot of one Run's steer ledger. Terminal attempts (injected/cancelled)
 * are retained as facts; accepted attempts are retained only as the last known state and are never
 * injected automatically after restoration. The store only persists this state, it never changes
 * [AgentSteerState.stage] or turns an attempt into an injection.
 */
class StoredAgentSteerLedger(
    val ref: LocalProjectRef,
    val revision: Long,
    val state: AgentSteerState,
) {
    init {
        require(revision >= 0) { "Steer revision is negative" }
        require(state.chat.projectId == ref.projectId) { "Steer Chat belongs to a different Project" }
        require(state.run.projectId == ref.projectId) { "Steer Run belongs to a different Project" }
        require(state.run.chatId == state.chat.chatId) { "Steer Run and Chat differ" }
        require(state.attempts.size <= AgentSteerState.MAX_ATTEMPTS) { "Steer ledger exceeds its budget" }
        require(state.attempts.sumOf { it.text.length } <= MAX_TOTAL_TEXT_CHARS) {
            "Steer ledger text exceeds its budget"
        }
    }

    /** Reuses this durable identity/revision with a ledger advanced by the domain object itself. */
    fun withState(state: AgentSteerState): StoredAgentSteerLedger {
        require(state.run == this.state.run && state.chat == this.state.chat) {
            "Steer Run/Chat cannot change"
        }
        return StoredAgentSteerLedger(ref, revision, state)
    }

    override fun toString(): String =
        "StoredAgentSteerLedger(run=${state.run.runId.value}, revision=$revision, " +
            "attempts=${state.attempts.size}, stage=${state.stage})"

    companion object {
        /** Worst-case text retained by one fully populated steer ledger. */
        const val MAX_TOTAL_TEXT_CHARS: Int =
            AgentSteerState.MAX_ATTEMPTS * AgentSteerCommand.MAX_TEXT_CHARS

        /** A ledger that has not been persisted in this instance/generation yet. */
        fun initial(ref: LocalProjectRef, state: AgentSteerState): StoredAgentSteerLedger =
            StoredAgentSteerLedger(ref, 0, state)
    }
}

/**
 * Durable input/steer facts for the native Agent. Reads are explicitly read-only and only return
 * what was committed by a previous explicit snapshot write. Writes require the caller's real
 * [ProjectWriteLease]; this port has no fallback that mutates state from a read or a model callback.
 */
interface AgentInputStatePort {
    /** Returns the persisted queue or null; it never creates a queue or sends pending drafts. */
    suspend fun loadInputQueue(ref: LocalProjectRef, chat: ChatId): StoredAgentInputQueue?

    /**
     * Atomically replaces one Chat's ordered queue. [snapshot.revision] must match the persisted
     * revision (0 for a new row). Removing a persisted pending append requires [settlement] that
     * matches that exact draft/Run/attempt/revision (and message identity when already recorded).
     * A settlement snapshot must represent the state immediately after that append is cleared and
     * must not simultaneously begin a different pending append; persist the settlement first.
     */
    suspend fun replaceInputQueue(
        write: ProjectWriteLease,
        snapshot: StoredAgentInputQueue,
        settlement: AgentInputAppendSettlement? = null,
    ): StoredAgentInputQueue

    /** Returns one Run's persisted steer ledger or null; it never injects or resends anything. */
    suspend fun loadSteerLedger(
        ref: LocalProjectRef,
        chat: ChatId,
        run: RunIdentity,
    ): StoredAgentSteerLedger?

    /**
     * Atomically replaces one Run's steer ledger. Existing attempts must keep their identity,
     * text, submission phase and terminal outcome; only new attempts may be appended.
     */
    suspend fun replaceSteerLedger(
        write: ProjectWriteLease,
        snapshot: StoredAgentSteerLedger,
    ): StoredAgentSteerLedger
}

/** Stable keyset cursor, scoped by the caller to one exact Project/Chat. */
data class AgentSteerRunCursor(val run: RunId, val command: CommandId)
data class AgentSteerRunSummary(val run: RunIdentity, val stage: AgentRunStage, val revision: Long)
class AgentSteerRunPage(runs: List<AgentSteerRunSummary>, val next: AgentSteerRunCursor?) {
    val runs = frozenList(runs)
}
