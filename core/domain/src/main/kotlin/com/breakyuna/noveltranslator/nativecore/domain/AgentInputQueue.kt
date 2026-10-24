package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Explicit next-turn input budgets. Lengths are UTF-16 code units, matching the Agent send
 * guard in NativeKoogConversation; they are safety limits, not measured device capacities.
 */
data class AgentInputQueueLimits(
    val maxDrafts: Int = DEFAULT_MAX_DRAFTS,
    val maxDraftChars: Int = DEFAULT_MAX_DRAFT_CHARS,
    val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
) {
    init {
        require(maxDrafts in 1..HARD_MAX_DRAFTS) { "Invalid draft count budget" }
        require(maxDraftChars in 1..HARD_MAX_DRAFT_CHARS) { "Invalid draft text budget" }
        require(maxTotalChars in maxDraftChars..HARD_MAX_TOTAL_CHARS) { "Invalid total text budget" }
    }

    companion object {
        const val DEFAULT_MAX_DRAFTS = 20
        const val DEFAULT_MAX_DRAFT_CHARS = 8_000
        const val DEFAULT_MAX_TOTAL_CHARS = 32_000
        const val HARD_MAX_DRAFTS = 50
        const val HARD_MAX_DRAFT_CHARS = 8_000
        const val HARD_MAX_TOTAL_CHARS = 400_000
        val DEFAULT = AgentInputQueueLimits()
    }
}

/** Explicit reasons for rejected queue mutations; a rejection never alters the existing queue. */
enum class AgentInputQueueFailure {
    WRONG_CHAT,
    WRONG_RUN,
    DUPLICATE_DRAFT,
    DRAFT_ID_CONFLICT,
    QUEUE_FULL,
    TEXT_EMPTY,
    TEXT_TOO_LONG,
    TOTAL_CHARS_EXCEEDED,
    INVALID_TEXT,
    UNKNOWN_DRAFT,
    DRAFT_IN_FLIGHT,
    EMPTY_QUEUE,
    QUEUE_PAUSED,
    APPEND_PENDING,
    RUN_HAS_PENDING_APPEND,
    NO_PENDING_APPEND,
    STALE_ATTEMPT,
}

/** Explicit reasons for ignoring an append receipt; ignored receipts remove nothing. */
enum class AgentInputAppendFailure {
    NO_PENDING_APPEND,
    WRONG_CHAT,
    WRONG_RUN,
    WRONG_DRAFT,
    STALE_ATTEMPT,
    STALE_REVISION,
}

sealed interface AgentInputQueueUpdate {
    data class Applied(val queue: AgentInputQueue) : AgentInputQueueUpdate
    data class Rejected(val reason: AgentInputQueueFailure) : AgentInputQueueUpdate
}

sealed interface AgentInputAppendStartResult {
    data class Started(val queue: AgentInputQueue, val append: AgentInputAppend) : AgentInputAppendStartResult
    data class Rejected(val reason: AgentInputQueueFailure) : AgentInputAppendStartResult
}

sealed interface AgentInputAppendResult {
    data class Consumed(
        val queue: AgentInputQueue,
        val appended: AgentInputAppend,
        val messageId: MessageId,
    ) : AgentInputAppendResult

    data class Ignored(val reason: AgentInputAppendFailure) : AgentInputAppendResult
}

/**
 * User-editable input that is not yet part of canonical history. The caller supplies a stable
 * [CommandId] that must never be reused for different content, including after deletion; editing
 * increments [revision] so a late append receipt for an older snapshot can never consume the newer
 * text. A draft queued during a Run may carry that Run binding, but it remains a next-turn input,
 * never an in-execution steer.
 */
class AgentInputDraft private constructor(
    val id: CommandId,
    val chat: ChatIdentity,
    val run: RunIdentity?,
    val text: String,
    val revision: Long,
) {
    init {
        require(revision >= 1) { "Draft revision starts at 1" }
        require(run == null || belongsTo(run, chat)) { "Draft Run must belong to the same Project/Chat" }
    }

    /**
     * Edits are identity-preserving and revision-bumping. The queue checks its aggregate budget
     * before calling this method.
     */
    fun edit(text: String, limits: AgentInputQueueLimits = AgentInputQueueLimits.DEFAULT): AgentInputDraft {
        val failure = validateDraftText(text, limits.maxDraftChars)
        require(failure == null) { "Invalid Agent input draft: $failure" }
        if (text == this.text) return this
        return AgentInputDraft(id, chat, run, text, revision + 1)
    }

    /** Rebind or clear the optional Run provenance when a Run ends. Identity and text are unchanged. */
    internal fun boundTo(run: RunIdentity?): AgentInputDraft =
        if (this.run == run) this else AgentInputDraft(id, chat, run, text, revision)

    override fun toString(): String =
        "AgentInputDraft(id=${id.value}, revision=$revision, chars=${text.length})"

    companion object {
        fun create(
            id: CommandId,
            chat: ChatIdentity,
            text: String,
            run: RunIdentity? = null,
            limits: AgentInputQueueLimits = AgentInputQueueLimits.DEFAULT,
        ): AgentInputDraft {
            val failure = validateDraftText(text, limits.maxDraftChars)
            require(failure == null) { "Invalid Agent input draft: $failure" }
            require(run == null || belongsTo(run, chat)) { "Draft Run must belong to the same Project/Chat" }
            return AgentInputDraft(id, chat, run, text, 1)
        }

        /**
         * Explicit recovery factory for a persisted snapshot. It restores facts only and never
         * queues, appends or sends anything. Aggregate limits remain the queue's responsibility.
         */
        fun restore(
            id: CommandId,
            chat: ChatIdentity,
            run: RunIdentity?,
            text: String,
            revision: Long,
        ): AgentInputDraft {
            require(revision >= 1) { "Draft revision starts at 1" }
            require(run == null || belongsTo(run, chat)) { "Draft Run must belong to the same Project/Chat" }
            val failure = validateDraftText(text, AgentInputQueueLimits.HARD_MAX_DRAFT_CHARS)
            require(failure == null) { "Invalid persisted Agent input draft: $failure" }
            return AgentInputDraft(id, chat, run, text, revision)
        }
    }
}

/** Snapshot handed to the history writer. It remains in the queue until the append is confirmed. */
class AgentInputAppend(
    val draft: AgentInputDraft,
    val run: RunIdentity,
    val attemptId: RequestAttemptId,
) {
    init {
        require(belongsTo(run, draft.chat)) { "Append Run must belong to the draft Chat" }
        require(draft.run == null || draft.run == run) { "Append draft Run mismatch" }
    }

    val text: String get() = draft.text
    val revision: Long get() = draft.revision

    override fun toString(): String =
        "AgentInputAppend(draft=${draft.id.value}, revision=$revision, attempt=${attemptId.value})"
}

/**
 * Proof that one exact draft snapshot was appended to canonical history. The message identity is
 * required so a receipt cannot be confused with a model acknowledgement or a later send.
 */
class AgentInputAppendReceipt(
    val draftId: CommandId,
    val chat: ChatIdentity,
    val run: RunIdentity,
    val attemptId: RequestAttemptId,
    val draftRevision: Long,
    val messageId: MessageId,
) {
    init {
        require(draftRevision >= 1) { "Draft revision starts at 1" }
        require(belongsTo(run, chat)) { "Receipt Run must belong to the receipt Chat" }
    }
}

/**
 * Process-local, immutable next-turn input queue for exactly one Project/Chat. It owns ordering,
 * count/text budgets, pause/resume and the append handshake. It never writes history and never
 * starts a model request; the host must call [beginAppend], append that exact snapshot, then
 * acknowledge with the resulting [MessageId].
 *
 * Consumption is deliberately not "remove the head": only a receipt matching the pending draft,
 * Run, attempt and revision removes one item. Duplicate or late receipts therefore leave a newer
 * draft, including a newly typed one, untouched.
 *
 * This object is process-local and is not a recovery source. After a restart, reconcile persisted
 * history and append receipts before calling [beginAppend] again; clearing the UI box never proves
 * that a message entered history.
 */
class AgentInputQueue private constructor(
    val chat: ChatIdentity,
    val limits: AgentInputQueueLimits,
    val run: RunIdentity?,
    val paused: Boolean,
    drafts: List<AgentInputDraft>,
    val pendingAppend: AgentInputAppend?,
) {
    val drafts: List<AgentInputDraft> = frozenList(drafts)

    init {
        require(run == null || belongsTo(run, chat)) { "Queue Run must belong to the queue Chat" }
        require(this.drafts.size <= limits.maxDrafts) { "Draft count exceeds the queue budget" }
        require(this.drafts.sumOf { it.text.length } <= limits.maxTotalChars) {
            "Draft text exceeds the queue budget"
        }
        require(this.drafts.all { it.chat == chat && it.text.length <= limits.maxDraftChars })
        require(this.drafts.map { it.id }.toSet().size == this.drafts.size) { "Duplicate draft identity" }
        val pending = pendingAppend
        if (pending != null) {
            require(pending.run == run) { "Pending append must belong to the active Run" }
            require(this.drafts.firstOrNull()?.id == pending.draft.id) { "Only the head draft may be pending" }
            require(this.drafts.first().revision == pending.draft.revision) { "Pending draft revision changed" }
            require(this.drafts.first().text == pending.draft.text) { "Pending draft text changed" }
        }
    }

    val head: AgentInputDraft? get() = drafts.firstOrNull()
    val totalChars: Int get() = drafts.sumOf { it.text.length }

    fun enqueue(draft: AgentInputDraft): AgentInputQueueUpdate {
        if (draft.chat != chat) return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.WRONG_CHAT)
        if (draft.run != null && run != null && draft.run != run) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.WRONG_RUN)
        }
        validateDraftText(draft.text, limits.maxDraftChars)?.let {
            return AgentInputQueueUpdate.Rejected(it)
        }
        val existing = drafts.firstOrNull { it.id == draft.id }
        if (existing != null) {
            val same = existing.chat == draft.chat && existing.run == draft.run && existing.text == draft.text
            return AgentInputQueueUpdate.Rejected(
                if (same) AgentInputQueueFailure.DUPLICATE_DRAFT else AgentInputQueueFailure.DRAFT_ID_CONFLICT,
            )
        }
        if (drafts.size >= limits.maxDrafts) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.QUEUE_FULL)
        }
        if (totalChars + draft.text.length > limits.maxTotalChars) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.TOTAL_CHARS_EXCEEDED)
        }
        return AgentInputQueueUpdate.Applied(copy(drafts = drafts + draft))
    }

    fun edit(id: CommandId, text: String): AgentInputQueueUpdate {
        val index = drafts.indexOfFirst { it.id == id }
        if (index < 0) return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.UNKNOWN_DRAFT)
        if (pendingAppend?.draft?.id == id) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.DRAFT_IN_FLIGHT)
        }
        validateDraftText(text, limits.maxDraftChars)?.let {
            return AgentInputQueueUpdate.Rejected(it)
        }
        val current = drafts[index]
        if (text == current.text) return AgentInputQueueUpdate.Applied(this)
        val nextTotal = totalChars - current.text.length + text.length
        if (nextTotal > limits.maxTotalChars) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.TOTAL_CHARS_EXCEEDED)
        }
        val updated = drafts.toMutableList()
        updated[index] = current.edit(text, limits)
        return AgentInputQueueUpdate.Applied(copy(drafts = updated))
    }

    /** Deletion is queue-level; an in-flight append cannot be erased before its receipt settles. */
    fun delete(id: CommandId): AgentInputQueueUpdate {
        val index = drafts.indexOfFirst { it.id == id }
        if (index < 0) return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.UNKNOWN_DRAFT)
        if (pendingAppend?.draft?.id == id) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.DRAFT_IN_FLIGHT)
        }
        val remaining = drafts.toMutableList().also { it.removeAt(index) }
        return AgentInputQueueUpdate.Applied(copy(drafts = remaining))
    }

    fun pause(): AgentInputQueueUpdate =
        if (paused) AgentInputQueueUpdate.Applied(this) else AgentInputQueueUpdate.Applied(copy(paused = true))

    fun resume(): AgentInputQueueUpdate =
        if (paused) AgentInputQueueUpdate.Applied(copy(paused = false)) else AgentInputQueueUpdate.Applied(this)

    /**
     * Reserves the head for one history append. The draft stays queued and is not editable until
     * the append either succeeds or is explicitly released.
     */
    fun beginAppend(run: RunIdentity, attemptId: RequestAttemptId): AgentInputAppendStartResult {
        if (!belongsTo(run, chat)) return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.WRONG_CHAT)
        if (this.run != null && this.run != run) {
            return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.WRONG_RUN)
        }
        if (paused) return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.QUEUE_PAUSED)
        if (pendingAppend != null) {
            return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.APPEND_PENDING)
        }
        val draft = drafts.firstOrNull() ?: return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.EMPTY_QUEUE)
        if (draft.run != null && draft.run != run) {
            return AgentInputAppendStartResult.Rejected(AgentInputQueueFailure.WRONG_RUN)
        }
        val bound = draft.boundTo(run)
        val append = AgentInputAppend(bound, run, attemptId)
        val nextDrafts = listOf(bound) + drafts.drop(1)
        return AgentInputAppendStartResult.Started(
            copy(run = run, drafts = nextDrafts, pendingAppend = append),
            append,
        )
    }

    fun acknowledgeAppended(receipt: AgentInputAppendReceipt): AgentInputAppendResult {
        if (receipt.chat != chat) return AgentInputAppendResult.Ignored(AgentInputAppendFailure.WRONG_CHAT)
        val pending = pendingAppend ?: return AgentInputAppendResult.Ignored(AgentInputAppendFailure.NO_PENDING_APPEND)
        if (receipt.run != pending.run) return AgentInputAppendResult.Ignored(AgentInputAppendFailure.WRONG_RUN)
        if (receipt.draftId != pending.draft.id) {
            return AgentInputAppendResult.Ignored(AgentInputAppendFailure.WRONG_DRAFT)
        }
        if (receipt.attemptId != pending.attemptId) {
            return AgentInputAppendResult.Ignored(AgentInputAppendFailure.STALE_ATTEMPT)
        }
        if (receipt.draftRevision != pending.draft.revision) {
            return AgentInputAppendResult.Ignored(AgentInputAppendFailure.STALE_REVISION)
        }
        val remaining = drafts.filterNot { it.id == receipt.draftId }
        return AgentInputAppendResult.Consumed(
            copy(drafts = remaining, pendingAppend = null),
            pending,
            receipt.messageId,
        )
    }

    /** A failed/aborted history append keeps the draft for retry under a new attempt identity. */
    fun releaseAppend(attemptId: RequestAttemptId): AgentInputQueueUpdate {
        val pending = pendingAppend ?: return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.NO_PENDING_APPEND)
        if (pending.attemptId != attemptId) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.STALE_ATTEMPT)
        }
        return AgentInputQueueUpdate.Applied(copy(pendingAppend = null))
    }

    /** Ends one Run without dropping queued next-turn drafts; their optional Run provenance is cleared. */
    fun endRun(run: RunIdentity): AgentInputQueueUpdate {
        if (!belongsTo(run, chat)) return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.WRONG_CHAT)
        if (this.run == null || this.run != run) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.WRONG_RUN)
        }
        if (pendingAppend != null) {
            return AgentInputQueueUpdate.Rejected(AgentInputQueueFailure.RUN_HAS_PENDING_APPEND)
        }
        val unbound = drafts.map { if (it.run == run) it.boundTo(null) else it }
        return AgentInputQueueUpdate.Applied(copy(run = null, drafts = unbound, pendingAppend = null))
    }

    private fun copy(
        run: RunIdentity? = this.run,
        paused: Boolean = this.paused,
        drafts: List<AgentInputDraft> = this.drafts,
        pendingAppend: AgentInputAppend? = this.pendingAppend,
    ): AgentInputQueue = AgentInputQueue(chat, limits, run, paused, drafts, pendingAppend)

    override fun toString(): String =
        "AgentInputQueue(chat=${chat.chatId.value}, drafts=${drafts.size}, " +
            "chars=$totalChars, paused=$paused, pending=${pendingAppend != null})"

    companion object {
        fun empty(
            chat: ChatIdentity,
            limits: AgentInputQueueLimits = AgentInputQueueLimits.DEFAULT,
            run: RunIdentity? = null,
        ): AgentInputQueue {
            require(run == null || belongsTo(run, chat)) { "Queue Run must belong to the queue Chat" }
            return AgentInputQueue(chat, limits, run, false, emptyList(), null)
        }

        /**
         * Explicit recovery factory for a persisted snapshot. The constructor invariants still
         * apply; this method does not start an append, consume a draft or send anything.
         */
        fun restore(
            chat: ChatIdentity,
            limits: AgentInputQueueLimits,
            run: RunIdentity?,
            paused: Boolean,
            drafts: List<AgentInputDraft>,
            pendingAppend: AgentInputAppend?,
        ): AgentInputQueue = AgentInputQueue(chat, limits, run, paused, drafts, pendingAppend)
    }
}

private fun belongsTo(run: RunIdentity, chat: ChatIdentity): Boolean =
    run.projectId == chat.projectId && run.chatId == chat.chatId

/** The queue maps every content failure to an explicit reason; drafts themselves never accept invalid text. */
private fun validateDraftText(text: String, maxChars: Int): AgentInputQueueFailure? = when {
    text.isBlank() -> AgentInputQueueFailure.TEXT_EMPTY
    text.length > maxChars -> AgentInputQueueFailure.TEXT_TOO_LONG
    text.any { it.isISOControl() && it != '\n' && it != '\t' } -> AgentInputQueueFailure.INVALID_TEXT
    else -> null
}
