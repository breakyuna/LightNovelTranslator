package com.breakyuna.noveltranslator.nativecore.domain

/** A manual summary replaces only model context, never visible history or usage receipts. */
data class AgentContextPreview(val ref: LocalProjectRef, val chat: ChatId,
    val fingerprint: ObjectFingerprint, val previousSummary: String, val archivedMessages: Int, val retainedMessages: Int) {
    override fun toString(): String = "AgentContextPreview(archived=$archivedMessages, retained=$retainedMessages, summary=redacted)"
}

object AgentContextCut {
    /** Keep the last two complete user turns. A cut never splits tools from their results. */
    fun index(history: List<AgentMessage>): Int {
        AgentHistory.validate(history)
        val users = history.indices.filter { history[it] is AgentMessage.User }
        require(users.size >= 3) { "At least three turns are required" }
        val cut = users[users.size - 2]
        AgentHistory.validate(history.take(cut))
        AgentHistory.validate(history.drop(cut))
        require(cut > 0)
        return cut
    }
}
