package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelProtocolStreamTest {
    private fun frames(vararg data: String) = ByteArrayInputStream(data.joinToString("") { "data: $it\n\n" }.toByteArray())

    @Test fun responsesEmitBeforeTerminalAndUseTheCanonicalReceipt() { runBlocking<Unit> {
        val events = mutableListOf<AgentModelEvent>()
        val result = ModelProtocolStream(ModelProtocol.OPENAI_RESPONSES).read(frames(
            """{"type":"response.output_text.delta","delta":"hello"}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}],"usage":{"input_tokens":3,"output_tokens":1}}}""",
        )) { events.add(it) }
        assertEquals("hello", (events.single() as AgentModelEvent.TextDelta).text)
        val receipt = ModelProtocols.response(ModelProtocol.OPENAI_RESPONSES, result)
        assertEquals("hello", receipt.text)
    } }

    @Test fun anthropicAccumulatesTextAndSplitToolArgumentsOnlyAfterAllBlocksClose() { runBlocking<Unit> {
        val events = mutableListOf<AgentModelEvent>()
        val result = ModelProtocolStream(ModelProtocol.ANTHROPIC_MESSAGES).read(frames(
            """{"type":"message_start","message":{"type":"message","role":"assistant","content":[],"usage":{"input_tokens":4}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call","name":"lookup","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"row\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"1}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )) { events.add(it) }
        val receipt = ModelProtocols.response(ModelProtocol.ANTHROPIC_MESSAGES, result)
        assertEquals("hello", receipt.text)
        assertEquals("{\"row\":1}", receipt.toolCalls.single().argumentsJson)
        assertTrue(events.any { it is AgentModelEvent.ToolCallDelta })
    } }

    @Test fun geminiJoinsTextFragmentsAndPreservesFinalUsage() { runBlocking<Unit> {
        val events = mutableListOf<AgentModelEvent>()
        val result = ModelProtocolStream(ModelProtocol.GOOGLE_GEMINI).read(frames(
            """{"candidates":[{"content":{"parts":[{"text":"a"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}""",
        )) { events.add(it) }
        assertEquals("ab", ModelProtocols.response(ModelProtocol.GOOGLE_GEMINI, result).text)
        assertEquals(listOf("a", "b"), events.filterIsInstance<AgentModelEvent.TextDelta>().map { it.text })
    } }

    @Test fun prematureEofCannotProduceAnAssistantReceipt() { runBlocking<Unit> {
        for ((protocol, frame) in listOf(
            ModelProtocol.OPENAI_RESPONSES to """{"type":"response.output_text.delta","delta":"partial"}""",
            ModelProtocol.GOOGLE_GEMINI to """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""",
            ModelProtocol.ANTHROPIC_MESSAGES to """{"type":"message_start","message":{"content":[],"usage":{}}}""",
        )) {
            try { ModelProtocolStream(protocol).read(frames(frame)) {}; fail("Accepted partial $protocol") }
            catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.TRUNCATED, failure.kind) }
        }
    } }

    @Test fun anthropicRejectsUnclosedToolsAndFractionalIndexes() { runBlocking<Unit> {
        for (index in listOf("0", "0.5")) {
            try {
                ModelProtocolStream(ModelProtocol.ANTHROPIC_MESSAGES).read(frames(
                    """{"type":"message_start","message":{"content":[],"usage":{}}}""",
                    """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"c","name":"lookup","input":{}}}""",
                    """{"type":"message_stop"}""",
                )) {}
                fail("Accepted incomplete or invalid tool")
            } catch (_: AgentModelFailure) { }
        }
    } }
    @Test fun malformedEventsAreParsingFailuresAndUnspecifiedGeminiFinishIsNotTerminal() { runBlocking<Unit> {
        try {
            ModelProtocolStream(ModelProtocol.OPENAI_RESPONSES).read(frames("{")) {}
            fail("Malformed event accepted")
        } catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, failure.kind) }
        try {
            ModelProtocolStream(ModelProtocol.GOOGLE_GEMINI).read(frames(
                """{"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"FINISH_REASON_UNSPECIFIED"}]}""")) {}
            fail("Non-terminal response accepted")
        } catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.TRUNCATED, failure.kind) }
    } }

}
