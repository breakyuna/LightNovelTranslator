package com.breakyuna.noveltranslator.nativecore.domain

/** Frozen configuration identity, not a provider Entity or a credential. Resolve the exact revision, never latest. */
data class ModelConfigurationRef(val id: String, val revision: Long) {
    init { require(id.isNotBlank() && revision >= 0) }
}

data class AgentToolDefinition(val name: String, val description: String, val inputSchema: JsonObjectValue) {
    init { require(name.isNotBlank() && name.none { it.isWhitespace() || it.isISOControl() }) }
}

/**
 * Every deliberate provider request has its own attempt identity. Transport retry
 * with uncertain billing is not implicit in this interface.
 */
class AgentModelRequest(
    val run: RunIdentity,
    val branchId: BranchId,
    val attemptId: RequestAttemptId,
    val configuration: ModelConfigurationRef,
    history: List<AgentMessage>,
    tools: List<AgentToolDefinition>,
) {
    val history: List<AgentMessage> = frozenList(history)
    val tools: List<AgentToolDefinition> = frozenList(tools)
    init {
        require(run.chatId != null)
        AgentHistory.validate(this.history)
        require(this.tools.map { it.name }.toSet().size == this.tools.size) { "Duplicate tool definitions" }
    }
}

enum class UsageSource { ACTUAL, ESTIMATED, UNKNOWN }
data class TokenMeasurement(val tokens: Long?, val source: UsageSource) {
    init {
        require(tokens == null || tokens >= 0)
        require((source == UsageSource.UNKNOWN) == (tokens == null))
    }
}

data class RequestUsage(
    val input: TokenMeasurement,
    val cacheRead: TokenMeasurement,
    val cacheWrite: TokenMeasurement,
    val thinking: TokenMeasurement,
    val output: TokenMeasurement,
)

enum class ModelStopReason { COMPLETE, TOOL_CALLS, TRUNCATED, CONTENT_FILTERED }

data class AgentModelResponse(
    val message: AgentMessage.Assistant,
    val usage: RequestUsage,
    val stopReason: ModelStopReason,
)

sealed interface AgentModelEvent {
    /** Preview only; never append deltas as persisted conversation messages. */
    data class TextDelta(val text: String) : AgentModelEvent {
        override fun toString(): String = "TextDelta(redacted)"
    }
}

/** Narrow gateway consumed by Koog adaptation. No adapter may start its own HTTP client. */
interface AgentModelPort {
    suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse
}

enum class ModelFailureKind {
    NETWORK, TIMEOUT, RATE_LIMIT, SERVER, AUTHENTICATION, CONTEXT_OVERFLOW, PARSING, CONTENT,
    TRUNCATED, CONTENT_FILTERED, EMPTY_RESPONSE, UNKNOWN,
}

/** Sanitized classification only; raw provider errors may contain credentials/body. */
class AgentModelFailure(val kind: ModelFailureKind) : Exception("Agent model request failed: $kind")
