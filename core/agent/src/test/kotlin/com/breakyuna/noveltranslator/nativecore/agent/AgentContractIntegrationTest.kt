package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Port-level synthetic flow only. This does not claim Koog/Android/SQLite evidence. */
class AgentContractIntegrationTest {
    @Test
    fun twoTypedToolsReturnPairedResultsIntoTheNextModelRequest() = runTest {
        val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("command"))
        val runtime = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val tools = AgentToolRegistry(runtime, listOf(
            RegisteredAgentTool("lookup", "synthetic lookup",
                ToolParameterSchema(mapOf("id" to ToolParameter.Integer(1, Long.MAX_VALUE))), ToolEffect.READ_ONLY,
                AgentToolHandler { _, args -> json("""{"identity":${args.integer("id")}}""") }),
            RegisteredAgentTool("describe", "synthetic description",
                ToolParameterSchema(mapOf("label" to ToolParameter.Text(1, 20))), ToolEffect.READ_ONLY,
                AgentToolHandler { _, args ->
                    assertEquals("synthetic", args.text("label"))
                    json("""{"description":"synthetic result"}""")
                }),
        ))
        val calls = listOf(
            AgentToolCall(ToolCallId("lookup-call"), "lookup", json("""{"id":9007199254740993}""")),
            AgentToolCall(ToolCallId("describe-call"), "describe", json("""{"label":"synthetic"}""")),
        )
        val seen = mutableListOf<AgentModelRequest>()
        val gateway = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                seen.add(request)
                emit(AgentModelEvent.TextDelta("synthetic preview"))
                if (seen.size == 1) return response(calls.map { AssistantPart.ToolCall(it) }, ModelStopReason.TOOL_CALLS)
                val results = request.history.takeLast(2).map { it as AgentMessage.ToolResult }
                assertEquals(calls.map { it.id }, results.map { it.callId })
                assertEquals("9007199254740993", results[0].result.json["identity"].toString())
                return AgentModelResponse(AgentMessage.Assistant(MessageId("final"), listOf(AssistantPart.Text("done"))),
                    response().usage, ModelStopReason.COMPLETE)
            }
        }
        val boundary = AgentRequestBoundary(runtime, gateway, store, PreviewSink())
        val first = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("first"),
            ModelConfigurationRef("synthetic-model", 1), request(identity).history, tools.definitions())
        val toolResponse = boundary.complete(lease, first)
        val results = calls.map { call ->
            val outcome = tools.execute(lease, call) as AgentToolOutcome.Success
            AgentMessage.ToolResult(MessageId("result-" + call.id.value), call.id, call.name, outcome.result, false)
        }
        val next = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("second"), first.configuration,
            first.history + toolResponse.message + results, tools.definitions())
        val final = boundary.complete(lease, next)
        assertEquals("done", (final.message.parts.single() as AssistantPart.Text).text)
        assertEquals(2, seen.size)
        assertEquals(2, store.saveCount)
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(lease)
    }
}
