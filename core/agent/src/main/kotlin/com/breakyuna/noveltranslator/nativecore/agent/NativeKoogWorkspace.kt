package com.breakyuna.noveltranslator.nativecore.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/** Actual Koog fixed-operation strategy, with a clearly local, non-LLM backend for this S2 slice. */
class NativeKoogWorkspace(private val runtime: RuntimePort, private val projects: TextProjectPort,
    private val persistence: AgentPersistencePort) {
    private fun localUsage(): RequestUsage {
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        return RequestUsage(unknown, unknown, unknown, unknown, unknown)
    }
    suspend fun query(lease: RunLease, ref: LocalProjectRef): String = execute(lease, ref, null)
    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, workspace: NativeAgentWorkspacePort,
                        item: ItemId, translation: String): String = execute(lease, ref, Proposal(workspace, item, translation))
    private class Proposal(val workspace: NativeAgentWorkspacePort, val item: ItemId, val text: String)
    private suspend fun execute(lease: RunLease, ref: LocalProjectRef, proposal: Proposal?): String = withContext(Dispatchers.Default) {
        val tools = if (proposal == null) nativeProjectTools(runtime, projects, ref)
            else nativeApprovalTools(runtime, proposal.workspace, ref, lease)
        val name = if (proposal == null) "project_summary" else "prepare_text_edit"
        val configuration = ModelConfigurationRef(if (proposal == null) "local-agent-query-v1" else "local-agent-prepare-v1", 1)
        val arguments = if (proposal == null) JsonObjectValue.parse("{}", JsonInputLimits(128, 4)) else JsonObjectValue.parse(
            buildJsonObject { put("item_id", proposal.item.value); put("translation", proposal.text) }.toString(),
            JsonInputLimits(512 * 1024, 8))
        val local = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                require(request.configuration == configuration)
                val outcome = tools.execute(lease, AgentToolCall(ToolCallId(UUID.randomUUID().toString()),
                    name, arguments))
                val text = when (outcome) {
                    is AgentToolOutcome.Success -> outcome.result.encode()
                    is AgentToolOutcome.Failure -> throw AgentModelFailure(ModelFailureKind.CONTENT)
                }
                return AgentModelResponse(AgentMessage.Assistant(MessageId(UUID.randomUUID().toString()),
                    listOf(AssistantPart.Text(text))), localUsage(), ModelStopReason.COMPLETE)
            }
        }
        val sink = object : AgentEventSink {
            override fun preview(key: AgentRequestKey, event: AgentModelEvent) = Unit
            override fun previewFinished(key: AgentRequestKey) = Unit
        }
        val boundary = AgentRequestBoundary(runtime, local, persistence, sink)
        val request = AgentModelRequest(lease.identity, BranchId("main"), RequestAttemptId(UUID.randomUUID().toString()),
            configuration, listOf(
                AgentMessage.System(MessageId("system"), "Local workflow only; instance=${ref.instanceId.value}; generation=${ref.generation.value}"),
                AgentMessage.User(MessageId("user"), listOf(UserPart.Text(name + "\n" + arguments.encode())))), tools.definitions())
        val executor = object : PromptExecutor() {
            override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
                // This strategy has exactly one fixed operation. Never reuse it for arbitrary model prompts/tools.
                val result = boundary.complete(lease, request)
                val text = result.message.parts.joinToString("") { (it as AssistantPart.Text).text }
                return Message.Assistant(content = text, metaInfo = ResponseMetaInfo(timestamp = Instant.DISTANT_PAST, modelId = model.id))
            }
            override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
                flow { throw UnsupportedOperationException("Streaming unavailable") }
            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
                throw UnsupportedOperationException("Moderation unavailable")
            override suspend fun models(): List<LLModel> = emptyList()
            override fun close() = Unit
        }
        val model = LLModel(provider = object : LLMProvider("lnt-local", "Local workflow") {},
            id = configuration.id, capabilities = listOf(LLMCapability.Temperature))
        // Koog 1.3.0 Assistant exposes typed response parts, not a `content` property.
        val strategy = functionalStrategy<String, String>("native-project-workflow") { input -> requestLLM(input).textContent() }
        val agent = AIAgent(promptExecutor = executor, llmModel = model, strategy = strategy,
            toolRegistry = ToolRegistry.EMPTY, systemPrompt = "Local workflow only; no network, paid model or automatic project writes.", maxIterations = 2,
            id = "native-" + lease.identity.runId.value)
        try { agent.run(name) } finally { withContext(NonCancellable) { agent.close() } }
    }
}
