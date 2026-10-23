package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompatibleChatStreamTest {
    private fun chunk(delta: String = "{}", finish: String? = null, index: String = "0"): String {
        val reason = finish?.let { JsonPrimitive(it).toString() } ?: "null"
        return "{\"choices\":[{\"index\":$index,\"delta\":$delta,\"finish_reason\":$reason}]}"
    }
    private fun event(data: String) = "data: $data\n\n"
    private fun message(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
    private suspend fun parse(wire: String, events: MutableList<AgentModelEvent> = mutableListOf()): String =
        CompatibleChatStream().read(ByteArrayInputStream(wire.toByteArray())) { events.add(it) }
    private suspend fun failure(wire: String, kind: ModelFailureKind) {
        try { parse(wire); fail("Expected classified failure") } catch (error: AgentModelFailure) { assertEquals(kind, error.kind) }
    }

    @Test fun utf8CanSplitAtEveryByteAndSseSupportsBomCrLfCommentsAndMultilineData() = runBlocking {
        val content = chunk("{\"role\":\"assistant\",\"content\":\"中文😀\",\"reasoning_content\":\"思考\"}")
        val wire = "\uFEFF: heartbeat\r\nid: ignored\r\nretry: 1\r\ndata: " + content.replace(",\"delta\"", ",\r\ndata: \"delta\"") + "\r\n\r\n" +
            event(chunk(finish = "stop")) + event("[DONE]")
        val source = object : ByteArrayInputStream(wire.toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(1, length))
        }
        val events = mutableListOf<AgentModelEvent>()
        val body = CompatibleChatStream().read(source) { events.add(it) }
        assertEquals("中文😀", message(body).getValue("content").jsonPrimitive.content)
        assertEquals("思考", message(body).getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("中文😀", events.filterIsInstance<AgentModelEvent.TextDelta>().single().text)
        assertEquals("思考", events.filterIsInstance<AgentModelEvent.ThinkingDelta>().single().text)
    }

    @Test fun nullLegacyFunctionCallIsAbsentButNonNullValuesRemainRejected() = runBlocking {
        val events = mutableListOf<AgentModelEvent>()
        val wire = event(chunk("""{"content":"hello","function_call":null}""")) +
            event(chunk("""{"content":" world","function_call":null}""", finish = "stop")) + event("[DONE]")
        assertEquals("hello world", message(parse(wire, events)).getValue("content").jsonPrimitive.content)
        assertEquals("hello world", events.filterIsInstance<AgentModelEvent.TextDelta>().joinToString("") { it.text })
        for (value in listOf("{}", """{"name":"legacy","arguments":"{}"}""", "false")) {
            failure(event(chunk("""{"content":"hello","function_call":$value}""")), ModelFailureKind.CONTENT)
        }
    }

    @Test fun interleavedToolFragmentsAreAssembledByExactIndexBeforeArgumentsAreParsed() = runBlocking {
        val events = mutableListOf<AgentModelEvent>()
        val wire = event(chunk("{\"tool_calls\":[{\"index\":1,\"id\":\"call-b\",\"type\":\"function\",\"function\":{\"name\":\"project_summary\",\"arguments\":\"{}\"}}]}")) +
            event(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call-\",\"type\":\"function\",\"function\":{\"name\":\"read_\",\"arguments\":\"{\\\"item_id\\\":\"}}]}")) +
            event(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"item\",\"arguments\":\"1}\"}}]}")) +
            event(chunk(finish = "tool_calls")) + event("[DONE]")
        val calls = message(parse(wire, events)).getValue("tool_calls").jsonArray
        assertEquals(listOf("call-a", "call-b"), calls.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val first = calls.first().jsonObject.getValue("function").jsonObject
        assertEquals("read_item", first.getValue("name").jsonPrimitive.content)
        assertEquals("{\"item_id\":1}", first.getValue("arguments").jsonPrimitive.content)
        assertEquals(3, events.filterIsInstance<AgentModelEvent.ToolCallDelta>().size)
    }

    @Test fun repeatedCompleteNamesDoNotDuplicateWhileArgumentsKeepTheirFragments() = runBlocking {
        // Covers repeated names after either a full first name or a genuinely fragmented name.
        for (names in listOf(listOf("read_item", "read_item", "read_item"), listOf("read_", "item", "read_item"))) {
            val arguments = listOf("{\"item_id\":", "1", "}")
            val wire = names.mapIndexed { index, name -> event(chunk(buildJsonObject {
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("index", 0); put("id", "call-a")
                    put("function", buildJsonObject { put("name", name); put("arguments", arguments[index]) })
                }) })
            }.toString())) }.joinToString("") + event(chunk(finish = "tool_calls")) + event("[DONE]")
            val call = message(parse(wire)).getValue("tool_calls").jsonArray.single().jsonObject
            val function = call.getValue("function").jsonObject
            assertEquals("call-a", call.getValue("id").jsonPrimitive.content)
            assertEquals("read_item", function.getValue("name").jsonPrimitive.content)
            assertEquals("{\"item_id\":1}", function.getValue("arguments").jsonPrimitive.content)
        }
    }

    @Test fun usageOnlyFinalFrameIsRetainedAndCrOnlyFramingWorks() = runBlocking {
        val wire = (event(chunk("{\"content\":\"done\"}")) + event(chunk(finish = "stop")) +
            event("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}") + event("[DONE]")).replace('\n', '\r')
        val body = Json.parseToJsonElement(parse(wire)).jsonObject
        assertEquals(12, body.getValue("usage").jsonObject.getValue("prompt_tokens").jsonPrimitive.int)
    }

    @Test fun eofAndDoneCannotTurnAnUnfinishedReplyIntoSuccess() = runBlocking {
        val partial = event(chunk("{\"content\":\"partial\"}"))
        failure(partial, ModelFailureKind.TRUNCATED)
        failure(partial + event("[DONE]"), ModelFailureKind.TRUNCATED)
        failure(partial + "data: " + chunk(finish = "stop"), ModelFailureKind.TRUNCATED)
        // A clean EOF after a dispatched, explicit finish frame is accepted for compatible local servers.
        assertEquals("partial", message(parse(partial + event(chunk(finish = "stop")))).getValue("content").jsonPrimitive.content)
    }

    @Test fun truncatedToolsNeverBecomeExecutableCallsButKnownUsageIsRetained() = runBlocking {
        val wire = event(chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"prepare_text_edit\",\"arguments\":\"{\"}}]}")) +
            event(chunk(finish = "length")) + event("{\"choices\":[],\"usage\":{\"completion_tokens\":10}}") + event("[DONE]")
        val body = parse(wire)
        assertNull(message(body)["tool_calls"])
        assertEquals("length", Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
    }

    @Test fun errorAfterPreviewDoesNotProduceACompleteReply() = runBlocking {
        val events = mutableListOf<AgentModelEvent>()
        try {
            parse(event(chunk("{\"content\":\"visible partial\"}")) + "event: error\ndata: {\"error\":{\"message\":\"fixture-private-error\"}}\n\n", events)
            fail()
        } catch (error: AgentModelFailure) {
            assertEquals(ModelFailureKind.SERVER, error.kind); assertFalse(error.toString().contains("fixture-private-error"))
        }
        assertEquals("visible partial", events.filterIsInstance<AgentModelEvent.TextDelta>().single().text)
    }

    @Test fun fractionalIndicesMissingToolsAndPostFinishContentFailClosed() = runBlocking {
        failure(event(chunk(index = "0.5")) + event("[DONE]"), ModelFailureKind.PARSING)
        failure(event(chunk("{\"tool_calls\":[{\"index\":1.5}]}")), ModelFailureKind.PARSING)
        failure(event(chunk(finish = "tool_calls")) + event("[DONE]"), ModelFailureKind.PARSING)
        failure(event(chunk(finish = "stop")) + event(chunk("{\"content\":\"late\"}")), ModelFailureKind.PARSING)
    }

    @Test fun invalidUtf8AndExcessiveEventSizeAreRejected() = runBlocking {
        try { CompatibleChatStream().read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) { }; fail() }
        catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, error.kind) }
        failure("data: " + "x".repeat(512 * 1024 + 1) + "\n\n", ModelFailureKind.CONTENT)
    }
}
