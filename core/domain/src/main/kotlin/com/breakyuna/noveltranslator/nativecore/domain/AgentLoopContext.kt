package com.breakyuna.noveltranslator.nativecore.domain

/** Advance only the model window at a complete tool-batch boundary; the original trace is immutable. */
interface AgentLoopContextPort {
    suspend fun saveLoopContext(lease: RunLease, ref: LocalProjectRef, chat: ChatId,
        expectedMessages: List<MessageId>, boundary: MessageId, summary: String)
}
