package com.breakyuna.noveltranslator.nativecore.domain

/** A branch copies a validated closed history prefix; it never copies execution permissions. */
data class AgentBranchPreview(val ref: LocalProjectRef, val chat: ChatId, val message: MessageId,
    val fingerprint: ObjectFingerprint, val messages: Int, val editable: Boolean)

object AgentBranchHistory {
    fun copy(message: AgentMessage, id: MessageId, replacement: String? = null): AgentMessage {
        if (replacement != null) {
            require(replacement.isNotBlank() && replacement.length <= 8000)
            TextProjectFingerprint.utf8(replacement)
            return when (message) {
                is AgentMessage.User -> {
                    require(message.parts.all { it is UserPart.Text })
                    AgentMessage.User(id, listOf(UserPart.Text(replacement)))
                }
                is AgentMessage.Assistant -> {
                    require(message.parts.all { it is AssistantPart.Text })
                    AgentMessage.Assistant(id, listOf(AssistantPart.Text(replacement)))
                }
                else -> error("Only plain user or assistant text can be edited")
            }
        }
        return when (message) {
            is AgentMessage.User -> AgentMessage.User(id, message.parts)
            is AgentMessage.Assistant -> AgentMessage.Assistant(id, message.parts, message.continuation)
            is AgentMessage.ToolResult -> AgentMessage.ToolResult(id, message.callId, message.toolName, message.result, message.isError)
            is AgentMessage.System -> error("System context cannot become a branch message")
        }
    }
    fun editable(message: AgentMessage): Boolean = when (message) {
        is AgentMessage.User -> message.parts.all { it is UserPart.Text }
        is AgentMessage.Assistant -> message.parts.all { it is AssistantPart.Text }
        else -> false
    }
}
