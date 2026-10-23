package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** All responses come from an interceptor, with no DNS, socket or billable provider. */
class CompatibleAgentModelStreamingTest {
    private val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("send"))
    private val configuration = ModelConfigurationRef("fixture", 1)
    private var endpoint = "http://localhost:1234/v1"
    private val settings = object : NativeModelSettingsPort {
        override suspend fun current() = NativeModelSettings(configuration, endpoint, "fixture-model", false)
        override suspend fun resolve(reference: ModelConfigurationRef) = current()
        override suspend fun credential(reference: ModelConfigurationRef) = ""
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("Not used")
    }
    private val request get() = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"), configuration,
        listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("fixture")))), emptyList())
    private fun runtime() = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
    private fun frame(delta: String = "{}", finish: String? = null): String {
        val reason = finish?.let { JsonPrimitive(it).toString() } ?: "null"
        return "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$reason}]}\n\n"
    }
    private val successful get() = frame("{\"content\":\"hello\",\"reasoning_content\":\"thinking\"}") + frame(finish = "stop") +
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":7}}}\n\ndata: [DONE]\n\n"
    private fun transport(body: ResponseBody, observe: (Request) -> Unit = {}) = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
        observe(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build()
    }.build())
    private fun trackingBody(text: String, closed: AtomicBoolean, onClose: () -> Unit = {}): ResponseBody = object : ResponseBody() {
        private val input = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() { if (closed.compareAndSet(false, true)) onClose(); super.close() }
        }.buffer()
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = input
    }

    @Test fun streamFlagPreviewAndActualUsageAreReturnedInOneRequest() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0; var sent: Request? = null
        val model = CompatibleAgentModel(settings, runtime, transport(successful.toResponseBody("text/event-stream".toMediaType())) { calls++; sent = it })
        val events = mutableListOf<AgentModelEvent>()
        try {
            val response = model.complete(request) { events.add(it) }
            val body = Buffer().also { requireNotNull(sent).body!!.writeTo(it) }.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject
            assertTrue(json.getValue("stream").jsonPrimitive.boolean)
            assertTrue(json.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
            assertNull(sent!!.header("Authorization")); assertEquals(1, calls)
            assertEquals("hello", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            assertEquals("thinking", response.message.parts.filterIsInstance<AssistantPart.Thinking>().single().text)
            assertEquals("hello", events.filterIsInstance<AgentModelEvent.TextDelta>().single().text)
            assertEquals(20L, response.usage.input.tokens); assertEquals(7L, response.usage.cacheRead.tokens)
            assertEquals(UsageSource.ACTUAL, response.usage.output.source)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun storedProxyPrefixAndNullFunctionCallProduceACompleteReplyInOneRequest() = runBlocking {
        endpoint = "https://example.com/api/models"
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val wire = frame("""{"content":"hello","function_call":null}""") + frame(finish = "stop") + "data: [DONE]\n\n"
        val model = CompatibleAgentModel(settings, runtime, transport(wire.toResponseBody("text/event-stream".toMediaType())) {
            calls++
            assertEquals("https://example.com/api/models/chat/completions", it.url.toString())
        })
        try {
            val response = model.complete(request) { }
            assertEquals(ModelStopReason.COMPLETE, response.stopReason)
            assertEquals("hello", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            assertEquals(1, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun providerReturningJsonInTheSameResponseDoesNotCauseAnotherRequest() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT); var calls = 0
        val body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"same response\"}}]}"
        val model = CompatibleAgentModel(settings, runtime, transport(body.toResponseBody("application/json".toMediaType())) { calls++ })
        try {
            val response = model.complete(request) { fail("No fake token stream for a JSON response") }
            assertEquals("same response", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            assertEquals(UsageSource.UNKNOWN, response.usage.output.source); assertEquals(1, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun interruptedStreamHasNoFinalResponseAndClosesItsBody() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT); val closed = AtomicBoolean()
        val model = CompatibleAgentModel(settings, runtime, transport(trackingBody(frame("{\"content\":\"partial\"}"), closed)))
        try {
            try { model.complete(request) { }; fail() } catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.TRUNCATED, error.kind) }
            assertTrue(closed.get())
        } finally { runtime.finishRun(lease) }
    }

    @Test fun cancellationDuringPreviewClosesResourcesBeforeReturning() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT); val closed = AtomicBoolean()
        val model = CompatibleAgentModel(settings, runtime, transport(trackingBody(successful, closed)))
        try {
            try { model.complete(request) { throw CancellationException("Synthetic stop") }; fail() } catch (_: CancellationException) { }
            assertTrue(closed.get())
        } finally { runtime.finishRun(lease) }
    }

    @Test fun completeReceiptSurvivesCancellationWhileTheResponseIsClosing() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val closed = AtomicBoolean(); val owner = AtomicReference<Job>()
        val body = trackingBody(successful, closed) { owner.get()?.cancel() }
        val model = CompatibleAgentModel(settings, runtime, transport(body))
        val result = CompletableDeferred<AgentModelResponse>()
        try {
            val worker = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val response = model.complete(request) { }
                withContext(NonCancellable) { result.complete(response) }
            }
            owner.set(worker); worker.start(); worker.join()
            val response = withTimeout(5000) { result.await() }
            assertTrue(closed.get()); assertEquals(20L, response.usage.input.tokens)
            assertEquals(ModelStopReason.COMPLETE, response.stopReason)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun lengthReceiptRetainsUsageAndNeverParsesIncompleteToolArgumentsAsCalls() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val wire = frame("{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"prepare_text_edit\",\"arguments\":\"{\"}}]}") +
            frame(finish = "length") + "data: {\"choices\":[],\"usage\":{\"completion_tokens\":9}}\n\ndata: [DONE]\n\n"
        val model = CompatibleAgentModel(settings, runtime, transport(wire.toResponseBody("text/event-stream".toMediaType())))
        try {
            val response = model.complete(request) { }
            assertEquals(ModelStopReason.TRUNCATED, response.stopReason)
            assertTrue(response.message.parts.none { it is AssistantPart.ToolCall }); assertEquals(9L, response.usage.output.tokens)
        } finally { runtime.finishRun(lease) }
    }
}
