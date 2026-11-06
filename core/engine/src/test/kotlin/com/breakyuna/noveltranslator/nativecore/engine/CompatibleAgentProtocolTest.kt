package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Interceptor-only fixture for a non-chat Agent protocol. */
class CompatibleAgentProtocolTest {
    private val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("send"))
    private val configuration = ModelConfigurationRef("fixture", 1)
    private val settings = object : NativeModelSettingsPort {
        override suspend fun current() = NativeModelSettings(configuration, "https://example.com", "fixture-model",
            hasCredential = true, protocol = NativeModelProtocol.ANTHROPIC_MESSAGES)
        override suspend fun resolve(reference: ModelConfigurationRef) = current()
        override suspend fun credential(reference: ModelConfigurationRef) = "secret"
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("Not used")
    }
    private val request get() = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"), configuration,
        listOf(
            AgentMessage.System(MessageId("system"), "fixture system"),
            AgentMessage.User(MessageId("user"), listOf(UserPart.Text("fixture"))),
        ), emptyList())

    @Test fun unknownCapabilitiesNeverAuthorizeToolsOnANonChatProtocol() = runBlocking {
        val runtime = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val model = CompatibleAgentModel(settings, runtime, transport = transport)
        val withTools = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"), configuration,
            listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("fixture"))),
                AgentMessage.Assistant(MessageId("assistant"), listOf(AssistantPart.Text("ok"))),
                AgentMessage.User(MessageId("second"), listOf(UserPart.Text("again")))),
            listOf(AgentToolDefinition("lookup", "synthetic",
                JsonObjectValue.parse("{}", JsonInputLimits(4096, 4)))))
        try {
            try { model.complete(withTools) { }; fail("Authorized unknown tool capability") }
            catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.CONTENT, error.kind) }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun anthropicAgentRequestUsesProtocolTransportAndEmitsCompletePreview() = runBlocking {
        val runtime = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val payload = buildJsonObject {
            put("type", "message"); put("role", "assistant"); put("stop_reason", "end_turn")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) })
            put("usage", buildJsonObject { put("input_tokens", 20); put("output_tokens", 4) })
        }.toString()
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("https://example.com/v1/messages", chain.request().url.toString())
            assertEquals("secret", chain.request().header("x-api-key"))
            val body = chain.request().body!!.let { requestBody ->
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                buffer.readUtf8()
            }
            assertTrue(body.contains("\"system\""))
            assertTrue(body.contains("\"messages\""))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val model = CompatibleAgentModel(settings, runtime, transport = transport)
        val events = mutableListOf<AgentModelEvent>()
        try {
            val response = model.complete(request) { events.add(it) }
            assertEquals("hello", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            assertEquals(20L, response.usage.input.tokens); assertEquals(4L, response.usage.output.tokens)
            assertEquals("hello", events.filterIsInstance<AgentModelEvent.TextDelta>().single().text)
            assertEquals(1, calls)
        } finally { runtime.finishRun(lease) }
    }
}
