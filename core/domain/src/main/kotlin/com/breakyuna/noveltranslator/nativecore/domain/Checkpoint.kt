package com.breakyuna.noveltranslator.nativecore.domain

enum class CheckpointStage { BEFORE_REQUEST, AFTER_RESPONSE, TOOL_PENDING, COMPACTED, INTERRUPTED }

/** Committed compaction result: public history keeps the original timeline, the model sees this. */
data class CompactionSummary(
    val upToMessageId: MessageId,
    val text: String,
    val version: Int,
) {
    init {
        require(text.isNotBlank()) { "Empty compaction summary" }
        require(version > 0) { "Summary version starts at 1" }
    }
}

/**
 * Versioned recovery envelope. It carries only serializable facts: strategy version, stage,
 * branch, typed history, pending tool identities, summary and usage. Coroutines, bitmaps and
 * open handles must never be stored here, and Koog's in-memory objects are not persisted.
 *
 * A checkpoint proves what was recorded, not that a paid request did not happen; recovery must
 * reconcile side-effect receipts before retrying (see APPROVAL_CONTRACT §5.3).
 */
class CheckpointEnvelope(
    val version: Int,
    val strategyVersion: Int,
    val run: RunIdentity,
    val branchId: BranchId,
    val stage: CheckpointStage,
    messages: List<AgentMessage>,
    pendingToolCalls: List<ToolCallId>,
    val summary: CompactionSummary?,
    acceptedCommands: List<CommandId>,
    val usage: RequestUsage,
    /**
     * Private working-copy scope. Existing callers may omit it for an in-memory envelope, but a
     * durable Agent checkpoint must carry it so reads can reject a different instance/generation.
     */
    val projectRef: LocalProjectRef? = null,
) {
    val messages: List<AgentMessage> = frozenList(messages)
    val pendingToolCalls: List<ToolCallId> = frozenList(pendingToolCalls)
    val acceptedCommands: List<CommandId> = frozenList(acceptedCommands)

    init {
        require(version > 0 && strategyVersion > 0) { "Checkpoint and strategy versions start at 1" }
        require(run.chatId != null) { "Agent checkpoints belong to a Chat" }
        require(projectRef == null || projectRef.projectId == run.projectId) {
            "Checkpoint scope belongs to the same project as the run"
        }
        require((summary != null) == (stage == CheckpointStage.COMPACTED)) {
            "Only a compacted checkpoint carries a summary"
        }
        require(this.acceptedCommands.toSet().size == this.acceptedCommands.size) {
            "Accepted commands must be unique"
        }
        if (stage == CheckpointStage.TOOL_PENDING) {
            require(this.pendingToolCalls.isNotEmpty()) { "A tool-pending checkpoint names its pending calls" }
        }
        AgentHistory.validate(this.messages, allowPendingAtTail = stage == CheckpointStage.TOOL_PENDING)
        require(openToolCalls(this.messages).toSet() == this.pendingToolCalls.toSet()) {
            "Declared pending calls must match the typed history"
        }
    }

    private companion object {
        fun openToolCalls(messages: List<AgentMessage>): List<ToolCallId> {
            val open = LinkedHashMap<ToolCallId, String>()
            messages.forEach { message ->
                if (message is AgentMessage.Assistant) {
                    message.parts.filterIsInstance<AssistantPart.ToolCall>().forEach { part ->
                        open[part.call.id] = part.call.name
                    }
                }
                if (message is AgentMessage.ToolResult) open.remove(message.callId)
            }
            return open.keys.toList()
        }
    }
}
