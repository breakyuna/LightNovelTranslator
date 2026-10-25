package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Bounded automatic-context policy. The values are safety guards, not measured device budgets.
 * They stay below the storage/model hard limits (256 messages, 2 MiB window, 16,384-character
 * summary) so automatic compaction runs before a provider request is rejected.
 */
data class AgentCompactionPolicy(
    val maxContextChars: Int = 400_000,
    val maxContextBytes: Int = 800_000,
    val minUserTurns: Int = 3,
    val retainUserTurns: Int = 2,
    val maxSummaryChars: Int = 8_000,
) {
    init {
        require(maxContextChars in MIN_CONTEXT..1_000_000)
        require(maxContextBytes in MIN_CONTEXT..2_000_000)
        require(maxContextChars <= maxContextBytes)
        require(minUserTurns in 2..64)
        require(retainUserTurns in 1 until minUserTurns)
        require(maxSummaryChars in MIN_SUMMARY..16_384)
    }

    private companion object {
        const val MIN_CONTEXT = 4_096
        const val MIN_SUMMARY = 256
    }
}

/** A proven user-turn boundary. [boundary] is the last archived message, [firstRetained] the first kept one. */
data class AgentCompactionCut(
    val index: Int,
    val boundary: MessageId,
    val firstRetained: MessageId,
    val archivedMessages: Int,
    val retainedMessages: Int,
    val archivedUserTurns: Int,
    val retainedUserTurns: Int,
) {
    init {
        require(index > 0)
        require(archivedMessages > 0 && retainedMessages > 0)
        require(archivedUserTurns > 0 && retainedUserTurns > 0)
    }

    override fun toString(): String =
        "AgentCompactionCut(index=$index, archived=$archivedMessages, retained=$retainedMessages)"
}

data class AgentCompactionPlan(val cut: AgentCompactionCut, val archivedChars: Int, val archivedBytes: Int) {
    init {
        require(archivedChars > 0)
        require(archivedBytes > 0)
    }

    override fun toString(): String =
        "AgentCompactionPlan(cut=$cut, archivedChars=$archivedChars, archivedBytes=$archivedBytes)"
}

sealed interface AgentCompactionDecision {
    /** The model window is inside both policy bounds; no request should be made. */
    data object NotNeeded : AgentCompactionDecision
    data class Ready(val plan: AgentCompactionPlan) : AgentCompactionDecision
    /** The history cannot be reduced without losing pairing or a user-turn boundary. */
    data class Refused(val reason: AgentCompactionRefusal) : AgentCompactionDecision
}

enum class AgentCompactionRefusal { INSUFFICIENT_USER_TURNS, NO_SAFE_CUT, PENDING_TOOL_CALL, INVALID_HISTORY }

/**
 * Pure planner for automatic model-context compaction. It never edits the visible history,
 * never sums provider continuations separately and never falls back to truncating a turn.
 */
object AgentCompactionPlanner {
    fun plan(history: List<AgentMessage>, policy: AgentCompactionPolicy): AgentCompactionDecision {
        try {
            AgentHistory.validate(history)
        } catch (invalid: InvalidAgentHistory) {
            return AgentCompactionDecision.Refused(
                if (invalid.reason == InvalidAgentHistory.Reason.MISSING_RESULT) AgentCompactionRefusal.PENDING_TOOL_CALL
                else AgentCompactionRefusal.INVALID_HISTORY,
            )
        }
        val measured = try {
            measure(history)
        } catch (_: Exception) {
            return AgentCompactionDecision.Refused(AgentCompactionRefusal.INVALID_HISTORY)
        }
        if (measured.chars <= policy.maxContextChars.toLong() && measured.bytes <= policy.maxContextBytes.toLong())
            return AgentCompactionDecision.NotNeeded
        val userTurns = history.count { it is AgentMessage.User }
        if (userTurns < policy.minUserTurns) return AgentCompactionDecision.Refused(AgentCompactionRefusal.INSUFFICIENT_USER_TURNS)
        val cut = safeCut(history, policy.retainUserTurns)
            ?: return AgentCompactionDecision.Refused(AgentCompactionRefusal.NO_SAFE_CUT)
        val archived = history.subList(0, cut.index).filterNot { it is AgentMessage.System }
        val archivedSize = try {
            measure(archived)
        } catch (_: Exception) {
            return AgentCompactionDecision.Refused(AgentCompactionRefusal.INVALID_HISTORY)
        }
        if (archivedSize.chars > Int.MAX_VALUE.toLong() || archivedSize.bytes > Int.MAX_VALUE.toLong())
            return AgentCompactionDecision.Refused(AgentCompactionRefusal.INVALID_HISTORY)
        return AgentCompactionDecision.Ready(AgentCompactionPlan(cut, archivedSize.chars.toInt(), archivedSize.bytes.toInt()))
    }

    /**
     * Returns null unless the history is complete and a user-turn boundary leaves at least one
     * archived and one retained turn. Tool calls and their results are never separated.
     */
    fun safeCut(history: List<AgentMessage>, retainUserTurns: Int): AgentCompactionCut? {
        require(retainUserTurns in 1..64)
        try {
            AgentHistory.validate(history)
        } catch (_: InvalidAgentHistory) {
            return null
        }
        val users = history.indices.filter { history[it] is AgentMessage.User }
        if (users.size <= retainUserTurns) return null
        val index = users[users.size - retainUserTurns]
        if (index <= 0 || index >= history.size) return null
        val archived = history.subList(0, index)
        val retained = history.subList(index, history.size)
        if (retained.firstOrNull() !is AgentMessage.User || archived.lastOrNull() == null) return null
        try {
            AgentHistory.validate(archived)
            AgentHistory.validate(retained)
        } catch (_: InvalidAgentHistory) {
            return null
        }
        val archivedSystemMessages = archived.count { it is AgentMessage.System }
        val archivedMessages = archived.size - archivedSystemMessages
        if (archivedMessages <= 0) return null
        return AgentCompactionCut(index, archived.last().id, retained.first().id, archivedMessages, retained.size,
            users.size - retainUserTurns, retainUserTurns)
    }

    private class Measured(val chars: Long, val bytes: Long)

    private fun measure(messages: List<AgentMessage>): Measured {
        var chars = 0L
        var bytes = 0L
        for (message in messages) {
            val encoded = AgentMessageCodec.encode(message)
            chars = Math.addExact(chars, encoded.length.toLong())
            bytes = Math.addExact(bytes, TextProjectFingerprint.utf8(encoded).size.toLong())
        }
        return Measured(chars, bytes)
    }
}

/**
 * One explicit summary request. [archivedMessages] contains only the archived non-system prefix
 * and is immutable; implementations must not append it to the conversation or retry on failure.
 */
class AgentSummaryRequest(
    val ref: LocalProjectRef,
    val chat: ChatId,
    val priorSummary: String,
    archivedMessages: List<AgentMessage>,
    val maxSummaryChars: Int,
) {
    val archivedMessages: List<AgentMessage> = frozenList(archivedMessages)
    init {
        require(this.archivedMessages.isNotEmpty())
        require(this.archivedMessages.none { it is AgentMessage.System })
        require(priorSummary.length <= 16_384)
        require(maxSummaryChars in 256..16_384)
        AgentHistory.validate(this.archivedMessages)
    }
    override fun toString(): String =
        "AgentSummaryRequest(archived=${this.archivedMessages.size}, maxChars=$maxSummaryChars, summary=redacted)"
}

/** The app supplies this with the shared model gateway later; one call is one deliberate request. */
interface AgentSummaryPort {
    suspend fun summarize(request: AgentSummaryRequest): String
}

/**
 * A prepared automatic-compaction target. [context] already binds the current fingerprint,
 * summary and cut counts; [revision] is the exact context-row revision used for CAS.
 */
data class AgentAutoContextPreview(
    val context: AgentContextPreview,
    val revision: Long,
    val boundary: MessageId,
    val retainUserTurns: Int,
) {
    init {
        require(revision >= 0)
        require(retainUserTurns in 1..64)
        require(context.archivedMessages > 0)
        require(context.retainedMessages > 0)
    }
    override fun toString(): String =
        "AgentAutoContextPreview(archived=${context.archivedMessages}, revision=$revision, summary=redacted)"
}

interface AgentAutoContextPort {
    suspend fun prepareAutoContext(ref: LocalProjectRef, chat: ChatId, retainUserTurns: Int): AgentAutoContextPreview
    /** Must re-check fingerprint, revision, Project/Chat and pending approvals in one transaction. */
    suspend fun saveAutoContext(preview: AgentAutoContextPreview, summary: String)
}

/** Host facts that forbid a context rewrite before any billable summary request is prepared. */
enum class AgentCompactionBlock { PENDING_APPROVAL, ACTIVE_TOOL_LOOP, ACTIVE_RUN, USER_DISABLED }

interface AgentCompactionGatePort {
    suspend fun blockedReason(ref: LocalProjectRef, chat: ChatId): AgentCompactionBlock?
}

enum class AgentCompactionSkip {
    BELOW_THRESHOLD, INSUFFICIENT_USER_TURNS, NO_SAFE_CUT, PENDING_TOOL_CALL, INVALID_HISTORY,
    STALE_CONTEXT, PENDING_APPROVAL, ACTIVE_TOOL_LOOP, ACTIVE_RUN, USER_DISABLED,
}

enum class AgentCompactionFailure {
    GATE_UNAVAILABLE, HISTORY_UNAVAILABLE, PREPARE_FAILED, SUMMARY_FAILED,
    INVALID_SUMMARY, SUMMARY_TOO_LARGE, PERSIST_FAILED,
}

sealed interface AgentCompactionResult {
    data class Compacted(val plan: AgentCompactionPlan, val summaryChars: Int) : AgentCompactionResult
    data class Skipped(val reason: AgentCompactionSkip) : AgentCompactionResult
    data class Failed(val reason: AgentCompactionFailure,
        val modelFailure: ModelFailureKind? = null) : AgentCompactionResult
}
