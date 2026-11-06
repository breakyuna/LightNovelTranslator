package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.AgentToolCall
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolDefinition
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolOutcome
import com.breakyuna.noveltranslator.nativecore.domain.AgentToolPort
import com.breakyuna.noveltranslator.nativecore.domain.InvalidToolParameters
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.domain.ToolEffect
import com.breakyuna.noveltranslator.nativecore.domain.ToolParameterSchema
import com.breakyuna.noveltranslator.nativecore.domain.ValidatedToolArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface AgentToolHandler {
    suspend fun execute(run: RunIdentity, arguments: ValidatedToolArguments): JsonObjectValue
}

class RegisteredAgentTool(
    val name: String,
    val description: String,
    val parameters: ToolParameterSchema,
    val effect: ToolEffect,
    val handler: AgentToolHandler,
)

/**
 * Scalar-tool boundary. Read queries and durable approval preparation are allowed.
 * Project writes/paid actions remain denied until the full
 * PreparedChangeSet/approval/receipt path exists. A model's confirmation text is
 * never considered authorization. Repeating read queries is allowed.
 */
class AgentToolRegistry(private val runtime: RuntimePort, tools: List<RegisteredAgentTool>) : AgentToolPort {
    private val catalog = tools.associateBy { it.name }
    init {
        require(catalog.size == tools.size) { "Duplicate tool name" }
        catalog.values.forEach { it.parameters.definition(it.name, it.description) }
    }

    override fun definitions(): List<AgentToolDefinition> = catalog.values
        .filter { it.effect == ToolEffect.READ_ONLY || it.effect == ToolEffect.APPROVAL_PREPARATION }
        .map { it.parameters.definition(it.name, it.description) }

    override suspend fun execute(run: RunLease, call: AgentToolCall): AgentToolOutcome {
        currentCoroutineContext().ensureActive()
        val owner = runtime.snapshot().activeRun
        require(owner != null && owner.identity == run.identity && owner.owner == RuntimeOwner.AGENT) {
            "Tool requires an active Agent run"
        }
        val operation = runtime.beginOperation(run, OperationKind.TOOL)
        try {
            val tool = catalog[call.name] ?: return AgentToolOutcome.Failure("unknown_tool")
            if (tool.effect != ToolEffect.READ_ONLY && tool.effect != ToolEffect.APPROVAL_PREPARATION) return AgentToolOutcome.Failure("approval_path_unavailable")
            val arguments = try {
                tool.parameters.validate(call.arguments)
            } catch (invalid: InvalidToolParameters) {
                return AgentToolOutcome.Failure("invalid_arguments", invalid.field)
            }
            currentCoroutineContext().ensureActive()
            return try {
                AgentToolOutcome.Success(tool.handler.execute(run.identity, arguments))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AgentToolOutcome.Failure("tool_failed")
            }
        } finally {
            runtime.endOperation(operation)
        }
    }
}
