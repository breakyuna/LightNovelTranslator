package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Interceptor-only fixtures: no DNS, sockets or provider requests. */
class ModelProtocolStreamingTransportTest {
    private val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.invalid", "fixture", false)
    private val request = ModelProtocolRequest("fixture", null, listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")))

    private fun transport(payload: String, type: String, inspect: (Request) -> Unit) = ModelProtocolTransport(
        CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(payload.toResponseBody(type.toMediaType())).build()
        }.build()),
    )

    @Test fun geminiUsesItsStreamingOperationAndDoesNotAddChatFields() { runBlocking<Unit> {
        var calls = 0
        val payload = """data: {"candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}]}

"""
        val adapter = transport(payload, "text/event-stream") { http ->
            calls++
            assertEquals("/v1beta/models/fixture:streamGenerateContent", http.url.encodedPath)
            assertEquals("sse", http.url.queryParameter("alt"))
            assertEquals("text/event-stream", http.header("Accept"))
            val buffer = Buffer(); http.body!!.writeTo(buffer)
            assertFalse(buffer.readUtf8().contains("\"stream\""))
        }
        val events = mutableListOf<AgentModelEvent>()
        val result = adapter.complete(ModelProtocol.GOOGLE_GEMINI, settings, "", request, emit = { events.add(it) })
        assertEquals("hello", result.text)
        assertEquals("hello", (events.single() as AgentModelEvent.TextDelta).text)
        assertEquals(1, calls)
    } }

    @Test fun jsonFallbackKeepsACompleteReceiptEvenWhenPreviewIsCancelled() { runBlocking<Unit> {
        var calls = 0
        val payload = """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"complete"}]}]}"""
        val adapter = transport(payload, "application/json") { http ->
            calls++
            val buffer = Buffer(); http.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("\"stream\":true"))
        }
        val result = adapter.complete(ModelProtocol.OPENAI_RESPONSES, settings, "", request,
            emit = { throw CancellationException("Stopped preview") })
        assertEquals("complete", result.text)
        assertEquals(1, calls)
    } }
}
