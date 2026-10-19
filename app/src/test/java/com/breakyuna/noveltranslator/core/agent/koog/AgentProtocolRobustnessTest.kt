package com.breakyuna.noveltranslator.core.agent.koog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentProtocolRobustnessTest {

    private val knownTools = setOf("list_books", "get_book_status")

    private fun block(tag: String, content: String): String = "<$tag>$content</$tag>"

    @Test
    fun fullWidthTagAndBracketsParse() {
        val text = "＜tool_call＞{\"name\":\"list_books\",\"arguments\":{}}＜/tool_call＞"
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls[0].name)
        assertEquals("{}", parsed.calls[0].argumentsJson)
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun fullWidthJsonParses() {
        val text = block("tool_call", "｛＂name＂：＂list_books＂，＂arguments＂：｛＂limit＂：5｝｝")
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls[0].name)
        assertEquals(5, JSONObject(parsed.calls[0].argumentsJson).getInt("limit"))
    }

    @Test
    fun visibleTextKeepsOriginalCharactersNotFolded() {
        val text = "好的" + block("tool_call", "{\"name\":\"list_books\",\"arguments\":{}}") + "，明白了"
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertEquals(1, parsed.calls.size)
        assertEquals("好的，明白了", parsed.visibleText)
    }

    @Test
    fun tagVariantsParse() {
        val variants = listOf("tool_use", "invoke", "function_call", "TOOL_CALL", "Tool_Call")
        for (tag in variants) {
            val parsed = AgentToolProtocol.parseAssistantResponse(
                block(tag, "{\"name\":\"list_books\",\"arguments\":{}}"),
                knownTools
            )
            assertEquals("标签 $tag 应解析出调用", 1, parsed.calls.size)
            assertEquals("list_books", parsed.calls[0].name)
        }
    }

    @Test
    fun mismatchedCloseTagIsNotACallButIsSuspected() {
        // 开标签用了变体、闭标签落了单：既不是调用，也不是 malformed，但应被识别为疑似调用。
        val text = "<invoke>{\"name\":\"list_books\",\"arguments\":{}" + AgentToolProtocol.TOOL_RESULT_CLOSE
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertTrue(parsed.calls.isEmpty())
        assertTrue(parsed.malformedBlocks.isEmpty())
        assertNotNull(AgentToolProtocol.detectSuspectedToolCall(text, knownTools))
    }

    @Test
    fun proseWrappedJsonIsExtractedWithKnownToolNames() {
        val text = "我先看一下：{\"name\":\"list_books\",\"arguments\":{}}，稍等"
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls[0].name)
        assertEquals("", parsed.calls[0].rawBlock)
        assertEquals("我先看一下：，稍等", parsed.visibleText)
    }

    @Test
    fun proseJsonWithUnknownToolNameIsNotExtracted() {
        val text = "试试 {\"name\":\"unknown_tool\",\"arguments\":{}} 结束"
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertTrue(parsed.calls.isEmpty())
        assertEquals(text, parsed.visibleText)
    }

    @Test
    fun bareJsonStillRecoveredWithoutKnownNames() {
        val parsed = AgentToolProtocol.parseAssistantResponse("{\"name\":\"list_books\",\"arguments\":{}}")
        assertEquals(1, parsed.calls.size)
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun lenientLadderRepairsEachDefect() {
        val cases = listOf(
            "{\"name\":\"list_books\",\"arguments\":{\"limit\":5,}}" to 5,
            "{'name':'list_books','arguments':{}}" to 0,
            "{name:\"list_books\",arguments:{}}" to 0,
            "{\"name\":\"list_books\",\"arguments\":{}} // 查书架" to 0,
            "/* 调用 */ {\"name\":\"list_books\",\"arguments\":{}}" to 0
        )
        for ((content, expectedLimit) in cases) {
            val parsed = AgentToolProtocol.parseAssistantResponse(block("tool_call", content), knownTools)
            assertEquals("修复梯应救回：$content", 1, parsed.calls.size)
            assertEquals("list_books", parsed.calls[0].name)
            if (expectedLimit > 0) {
                assertEquals(expectedLimit, JSONObject(parsed.calls[0].argumentsJson).getInt("limit"))
            }
        }
    }

    @Test
    fun lenientLadderHandlesCombinedDefects() {
        val content = "{name:'list_books',arguments:{},/* 注释 */}"
        val parsed = AgentToolProtocol.parseAssistantResponse(block("tool_call", content), knownTools)
        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls[0].name)
    }

    @Test
    fun stillFailingBlockIsMalformed() {
        val text = block("tool_call", "{\"name\":\"list_books\",\"arguments\":{")
        val parsed = AgentToolProtocol.parseAssistantResponse(text, knownTools)
        assertTrue(parsed.calls.isEmpty())
        assertEquals(1, parsed.malformedBlocks.size)
        assertTrue(parsed.malformedBlocks[0].contains("不是含 name/arguments 的严格 JSON"))
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun detectSuspectedToolCallHeuristics() {
        val tagHint = AgentToolProtocol.TOOL_CALL_OPEN
        assertNotNull(
            AgentToolProtocol.detectSuspectedToolCall("我想用 $tagHint 但是忘了闭合", knownTools)
        )
        assertEquals(
            "list_books",
            AgentToolProtocol.detectSuspectedToolCall("参数是 name: list_books, arguments: {}", knownTools)
        )
        assertEquals(
            "list_books",
            AgentToolProtocol.detectSuspectedToolCall("我帮你调用 list_books 查一下 {}", knownTools)
        )
        assertNull(
            AgentToolProtocol.detectSuspectedToolCall("我可以调用 list_books 帮你查书架", knownTools)
        )
        assertNull(AgentToolProtocol.detectSuspectedToolCall("", knownTools))
        assertNull(AgentToolProtocol.detectSuspectedToolCall("hello world", knownTools))
    }

    @Test
    fun suspectedToolCallErrorReceiptIsActionable() {
        val receipt = AgentToolProtocol.formatSuspectedToolCallError("list_books")
        assertTrue(receipt.contains("list_books"))
        assertTrue(receipt.contains("invalid_tool_call"))
        assertTrue(receipt.contains("ok=\"false\""))
        assertTrue(receipt.contains(AgentToolProtocol.TOOL_CALL_OPEN))
    }

    @Test
    fun toolCallSignatureIgnoresKeyOrderAndWhitespace() {
        assertEquals(
            AgentToolProtocol.toolCallSignature("list_books", "{\"a\":1,\"b\":2}"),
            AgentToolProtocol.toolCallSignature("list_books", "{\"b\":2,\"a\":1}")
        )
        assertEquals(
            AgentToolProtocol.toolCallSignature("list_books", "{\"a\": 1}"),
            AgentToolProtocol.toolCallSignature("list_books", "{\"a\":1}")
        )
    }

    @Test
    fun toolCallSignatureDistinguishesNameAndValues() {
        assertFalse(
            AgentToolProtocol.toolCallSignature("list_books", "{\"a\":1}") ==
                AgentToolProtocol.toolCallSignature("list_books", "{\"a\":2}")
        )
        assertFalse(
            AgentToolProtocol.toolCallSignature("list_books", "{}") ==
                AgentToolProtocol.toolCallSignature("get_book_status", "{}")
        )
        assertEquals(
            AgentToolProtocol.toolCallSignature("list_books", "not json"),
            AgentToolProtocol.toolCallSignature("list_books", " not json ")
        )
    }

    @Test
    fun cleanFinalTextStripsThinkBlocks() {
        val thinkOpen = "<" + "think" + ">"
        val thinkClose = "</" + "think" + ">"
        val text = thinkOpen + "推理过程" + thinkClose + "\n答案"
        assertEquals("答案", AgentToolProtocol.cleanFinalText(text))
    }

    @Test
    fun cleanFinalTextStripsResidualBlocksAndStrayTags() {
        val callBlock = AgentToolProtocol.renderToolCallBlock("list_books", "{}")
        val resultBlock = AgentToolProtocol.renderToolResultBlock("list_books", true, "共 2 本书")
        assertEquals("结论\n\n以上", AgentToolProtocol.cleanFinalText("结论\n$callBlock\n以上"))
        assertEquals("结果\n请查收", AgentToolProtocol.cleanFinalText("结果\n${resultBlock}请查收"))
        assertEquals(
            "答复 残留",
            AgentToolProtocol.cleanFinalText(AgentToolProtocol.TOOL_RESULT_CLOSE + " 残留")
        )
        assertEquals(
            "结论\n以上",
            AgentToolProtocol.cleanFinalText(
                "结论\n＜tool_call＞{\"name\":\"list_books\",\"arguments\":{}}＜/tool_call＞\n以上"
            )
        )
    }

    @Test
    fun cleanFinalTextKeepsNormalTextAndCollapsesNewlines() {
        assertEquals("书架上有 2 本书。", AgentToolProtocol.cleanFinalText("书架上有 2 本书。"))
        assertEquals("a\n\nb", AgentToolProtocol.cleanFinalText("a\n\n\n\nb"))
        assertEquals("", AgentToolProtocol.cleanFinalText("   "))
    }

    @Test
    fun toolResultsCarryBoundaryHint() {
        val records = listOf(ToolExecutionRecord("list_books", "{}", true, "共 2 本书"))
        val rendered = AgentToolProtocol.formatToolResults(records)
        assertTrue(rendered.contains("tool_result"))
        assertTrue(rendered.contains("共 2 本书"))
        assertTrue(rendered.contains("不是用户的新消息"))
        assertEquals("", AgentToolProtocol.formatToolResults(emptyList()))
    }

    @Test
    fun protocolInstructionsTeachNoMixingAndFewShot() {
        val instructions = AgentToolProtocol.renderProtocolInstructions(6)
        assertTrue(instructions.contains("二者不要混排"))
        assertTrue(instructions.contains("list_books"))
        assertTrue(instructions.contains(AgentToolProtocol.TOOL_CALL_OPEN))
    }
}
