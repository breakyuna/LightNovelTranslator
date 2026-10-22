package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections

internal fun <T> frozenList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

/** Immutable artifact identity; a URI alone must never stand for a sent image. */
data class FrozenImageRef(val attachmentId: AttachmentId, val mimeType: String, val sha256: String) {
    init {
        require(mimeType.startsWith("image/") && mimeType.none { it.isWhitespace() || it.isISOControl() })
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

sealed interface UserPart {
    data class Text(val text: String) : UserPart {
        override fun toString(): String = "Text(redacted)"
    }
    data class Image(val image: FrozenImageRef) : UserPart
}

/** Provider continuation fields stay opaque and separate from visible text. */
data class ProviderContinuation(val protocol: String, val data: JsonObjectValue) {
    init { require(protocol.isNotBlank()) }
}

/** Internal identity is stable across attempts; provider IDs may be request-local. */
data class AgentToolCall(
    val id: ToolCallId,
    val name: String,
    val arguments: JsonObjectValue,
    val providerCallId: String? = null,
) {
    init {
        require(name.isNotBlank() && name.none { it.isWhitespace() || it.isISOControl() })
        require(providerCallId == null || providerCallId.isNotBlank())
    }
}

sealed interface AssistantPart {
    data class Text(val text: String) : AssistantPart {
        override fun toString(): String = "Text(redacted)"
    }
    data class Thinking(val text: String?, val continuation: ProviderContinuation?) : AssistantPart {
        override fun toString(): String = "Thinking(redacted)"
    }
    data class ToolCall(val call: AgentToolCall) : AssistantPart
}

sealed class AgentMessage(val id: MessageId) {
    class System(id: MessageId, val text: String) : AgentMessage(id)
    class User(id: MessageId, parts: List<UserPart>) : AgentMessage(id) {
        val parts: List<UserPart> = frozenList(parts)
        init { require(parts.isNotEmpty()) }
    }
    class Assistant(
        id: MessageId,
        parts: List<AssistantPart>,
        val continuation: ProviderContinuation? = null,
    ) : AgentMessage(id) {
        // A filtered/truncated provider receipt can legitimately have no parts.
        // Request history validation still rejects empty assistant entries.
        val parts: List<AssistantPart> = frozenList(parts)
    }
    class ToolResult(
        id: MessageId,
        val callId: ToolCallId,
        val toolName: String,
        val result: JsonObjectValue,
        val isError: Boolean,
    ) : AgentMessage(id) {
        init { require(toolName.isNotBlank()) }
    }
}

class InvalidAgentHistory(val reason: Reason) : IllegalArgumentException("Invalid Agent history: $reason") {
    enum class Reason { DUPLICATE_MESSAGE, SYSTEM_POSITION, MISSING_RESULT, DUPLICATE_CALL, UNMATCHED_RESULT, EMPTY_ASSISTANT }
}

/**
 * Validates native role/call pairing without rewriting history into user text.
 * A complete request cannot end with pending tools. Checkpoint storage may retain
 * pending calls explicitly; recovery must reconcile their receipts before retry.
 */
object AgentHistory {
    fun validate(messages: List<AgentMessage>, allowPendingAtTail: Boolean = false) {
        val messageIds = mutableSetOf<MessageId>()
        val callIds = mutableSetOf<ToolCallId>()
        val pending = mutableMapOf<ToolCallId, String>()
        var conversationStarted = false
        for (message in messages) {
            if (!messageIds.add(message.id)) invalid(InvalidAgentHistory.Reason.DUPLICATE_MESSAGE)
            if (pending.isNotEmpty() && message !is AgentMessage.ToolResult) {
                invalid(InvalidAgentHistory.Reason.MISSING_RESULT)
            }
            when (message) {
                is AgentMessage.System -> if (conversationStarted) invalid(InvalidAgentHistory.Reason.SYSTEM_POSITION)
                is AgentMessage.User -> conversationStarted = true
                is AgentMessage.Assistant -> {
                    if (message.parts.isEmpty()) invalid(InvalidAgentHistory.Reason.EMPTY_ASSISTANT)
                    conversationStarted = true
                    message.parts.filterIsInstance<AssistantPart.ToolCall>().forEach { part ->
                        if (!callIds.add(part.call.id)) invalid(InvalidAgentHistory.Reason.DUPLICATE_CALL)
                        pending[part.call.id] = part.call.name
                    }
                }
                is AgentMessage.ToolResult -> {
                    conversationStarted = true
                    if (pending[message.callId] != message.toolName) invalid(InvalidAgentHistory.Reason.UNMATCHED_RESULT)
                    pending.remove(message.callId)
                }
            }
        }
        if (!allowPendingAtTail && pending.isNotEmpty()) invalid(InvalidAgentHistory.Reason.MISSING_RESULT)
    }

    private fun invalid(reason: InvalidAgentHistory.Reason): Nothing = throw InvalidAgentHistory(reason)
}
