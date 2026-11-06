package com.breakyuna.noveltranslator.nativecore.domain

data class AgentApprovalResult(val id: String, val row: Int, val state: TextApprovalState)
class AgentChatLine(val id: String, val role: String, val text: String)
/** One explicitly opened message; large bodies stay out of the timeline projection. */
class AgentMessageDetail(val id: String, val role: String, val text: String, val reasoning: String = "", val tools: String = "")
enum class TextApprovalState { PENDING, REJECTED, CONSUMED, COMMITTED, CONFLICT, CANCELLED }
/** Bounded full difference, loaded only for one pending approval; never log this object. */
class TextApproval(
    val id: ApprovalRequestId, val ref: LocalProjectRef, val chatId: ChatId, val runId: RunId,
    val commandId: CommandId, val digest: String, val itemId: ItemId, val revision: Long,
    val base: ObjectFingerprint, val row: Int, val beforeStatus: TextItemStatus, val before: String, val after: String, val state: TextApprovalState,
)
interface NativeAgentWorkspacePort {
    suspend fun chats(ref: LocalProjectRef): List<ChatId>
    suspend fun selectChat(ref: LocalProjectRef, chat: ChatId)
    suspend fun currentChat(ref: LocalProjectRef): ChatId
    suspend fun newChat(ref: LocalProjectRef): ChatId
    suspend fun results(ref: LocalProjectRef, chatId: ChatId): List<AgentApprovalResult>
    suspend fun messages(ref: LocalProjectRef, chatId: ChatId): List<AgentChatLine>
    suspend fun append(ref: LocalProjectRef, chatId: ChatId, role: String, text: String)
    suspend fun pending(ref: LocalProjectRef, chatId: ChatId): TextApproval?
    suspend fun hasPending(ref: LocalProjectRef, chatId: ChatId): Boolean = pending(ref, chatId) != null
    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, itemId: ItemId, translation: String): TextApproval
    suspend fun cancelPending(ref: LocalProjectRef, runId: RunId)
    suspend fun reject(ref: LocalProjectRef, id: ApprovalRequestId, digest: String)
    suspend fun apply(lease: RunLease, ref: LocalProjectRef, id: ApprovalRequestId, digest: String): TextApprovalState
    /** Read receipts only; never resume an uncommitted operation automatically. */
    suspend fun reconcile(ref: LocalProjectRef, chatId: ChatId)
}

class AgentApprovalUnavailable : IllegalStateException("Approval could not be verified")

/** Body-free query cursor, bound to the exact local instance and Chat. */
data class AgentMessageCursor(val ref: LocalProjectRef, val chat: ChatId, val beforeTime: Long, val beforeId: MessageId) {
    init { require(beforeTime >= 0) }
}
class AgentMessagePage(lines: List<AgentChatLine>, val next: AgentMessageCursor?) {
    val lines = frozenList(lines)
}
