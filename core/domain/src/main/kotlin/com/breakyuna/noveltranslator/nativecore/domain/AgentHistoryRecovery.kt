package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recovery helpers for a typed Agent history whose process died while a tool call was open.
 *
 * A model request must never be sent with an unresolved trailing tool call. Recovery appends a
 * deterministic, error-shaped tool result for each call that has no result; it never re-executes
 * the tool and never turns an unknown side effect into a successful result.
 */
object AgentHistoryRecovery {
    /** Open calls in first-call order, matching the history validator's pending map. */
    fun pendingToolCalls(messages: List<AgentMessage>): List<AgentToolCall> {
        val pending = LinkedHashMap<ToolCallId, AgentToolCall>()
        messages.forEach { message ->
            if (message is AgentMessage.Assistant) {
                message.parts.filterIsInstance<AssistantPart.ToolCall>().forEach { part ->
                    pending[part.call.id] = part.call
                }
            }
            if (message is AgentMessage.ToolResult) pending.remove(message.callId)
        }
        return pending.values.toList()
    }
}

/**
 * Appends one error result per open call. The message identity is derived from the call, so a
 * retried recovery is idempotent even when the caller loses the first response.
 *
 * This is a top-level extension on purpose: a member extension declared inside
 * [AgentHistoryRecovery] is only callable with that object as an implicit receiver, which made
 * every production call site fail to resolve.
 */
suspend fun AgentConversationPort.repairPendingToolTail(
    ref: LocalProjectRef,
    chat: ChatId,
    errorCode: String = "interrupted",
): Int {
    require(errorCode.isNotBlank() && errorCode.length <= 64)
    val pending = AgentHistoryRecovery.pendingToolCalls(modelHistory(ref, chat))
    if (pending.isEmpty()) return 0
    val result = JsonObjectValue.parse(
        buildJsonObject { put("error", errorCode) }.toString(),
        JsonInputLimits(4096, 4),
    )
    pending.forEach { call ->
        appendMessage(ref, chat, AgentMessage.ToolResult(
            MessageId("recovered-" + call.id.value),
            call.id,
            call.name,
            result,
            true,
        ))
    }
    return pending.size
}
