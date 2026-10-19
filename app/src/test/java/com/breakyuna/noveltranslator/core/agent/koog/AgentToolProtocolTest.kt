package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文本工具协议（AgentToolProtocol）的解析与渲染测试。
 *
 * 协议层使用 android 的 org.json；本模块单元测试未开启 returnDefaultValues，
 * 纯 JVM 下 android.jar 是“未模拟”桩（调用即抛），因此这里用 Robolectric，
 * 与仓库既有 agent 测试（TermCandidateLifecycleTest 等）一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolProtocolTest {

    private fun callBlock(name: String, argumentsJson: String): String =
        AgentToolProtocol.TOOL_CALL_OPEN +
            """{"name":"$name","arguments":$argumentsJson}""" +
            AgentToolProtocol.TOOL_CALL_CLOSE

    // ---------- parseAssistantResponse ----------

    @Test
    fun parsesSingleToolCallBlock() {
        val block = callBlock("list_books", "{}")
        val parsed = AgentToolProtocol.parseAssistantResponse(block)

        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls.single().name)
        assertEquals("{}", parsed.calls.single().argumentsJson)
        assertEquals(block, parsed.calls.single().rawBlock)
        assertTrue(parsed.malformedBlocks.isEmpty())
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun parsesProseAroundCallBlocks() {
        val text = "我先查一下书架。\n" + callBlock("list_books", "{}") + "\n然后告诉你。"
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals(1, parsed.calls.size)
        // 只摘掉调用块本身：块前与块后的换行都保留，仅对结果整体去首尾空白
        assertEquals("我先查一下书架。\n\n然后告诉你。", parsed.visibleText)
    }

    @Test
    fun parsesMultipleCallBlocksInOrder() {
        val text = callBlock("list_books", "{}") + "\n" + callBlock("get_recent_log", """{"limit":5}""")
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals(listOf("list_books", "get_recent_log"), parsed.calls.map { it.name })
        assertEquals(5L, JSONObject(parsed.calls[1].argumentsJson).getLong("limit"))
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun stripsCodeFencesInsideCallBlock() {
        val text = AgentToolProtocol.TOOL_CALL_OPEN +
            "\n```json\n{\"name\":\"list_books\",\"arguments\":{}}\n```\n" +
            AgentToolProtocol.TOOL_CALL_CLOSE
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls.single().name)
        assertTrue(parsed.malformedBlocks.isEmpty())
    }

    @Test
    fun malformedBlockIsReportedInsteadOfDropped() {
        val text = AgentToolProtocol.TOOL_CALL_OPEN + "这不是 JSON" + AgentToolProtocol.TOOL_CALL_CLOSE
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertTrue(parsed.calls.isEmpty())
        assertEquals(1, parsed.malformedBlocks.size)
        assertTrue(parsed.malformedBlocks.single().startsWith("工具调用块不是含 name/arguments 的严格 JSON："))
        assertTrue(parsed.malformedBlocks.single().contains("这不是 JSON"))
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun malformedAndValidBlocksCoexist() {
        val text = AgentToolProtocol.TOOL_CALL_OPEN + "{坏" + AgentToolProtocol.TOOL_CALL_CLOSE +
            "\n" + callBlock("list_books", "{}")
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals(listOf("list_books"), parsed.calls.map { it.name })
        assertEquals(1, parsed.malformedBlocks.size)
    }

    @Test
    fun rejectsInvalidToolNames() {
        listOf("List_Books", "1tool", "a b", "工具名", "a".repeat(65), "").forEach { name ->
            val block = AgentToolProtocol.TOOL_CALL_OPEN +
                """{"name":"$name","arguments":{}}""" +
                AgentToolProtocol.TOOL_CALL_CLOSE
            val parsed = AgentToolProtocol.parseAssistantResponse(block)
            assertTrue("name=[$name] 应判为格式错误", parsed.calls.isEmpty())
            assertEquals(1, parsed.malformedBlocks.size)
        }
    }

    @Test
    fun accepts64CharToolName() {
        val name = "a".repeat(64)
        val parsed = AgentToolProtocol.parseAssistantResponse(callBlock(name, "{}"))
        assertEquals(name, parsed.calls.single().name)
    }

    @Test
    fun missingArgumentsDefaultsToEmptyObject() {
        val text = AgentToolProtocol.TOOL_CALL_OPEN + """{"name":"list_books"}""" + AgentToolProtocol.TOOL_CALL_CLOSE
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals("{}", parsed.calls.single().argumentsJson)
    }

    @Test
    fun nonObjectArgumentsAreMalformed() {
        listOf("""{"name":"list_books","arguments":[1,2]}""", """{"name":"list_books","arguments":"x"}""").forEach { json ->
            val text = AgentToolProtocol.TOOL_CALL_OPEN + json + AgentToolProtocol.TOOL_CALL_CLOSE
            val parsed = AgentToolProtocol.parseAssistantResponse(text)
            assertTrue("arguments 非对象应判格式错误: $json", parsed.calls.isEmpty())
            assertEquals(1, parsed.malformedBlocks.size)
        }
    }

    @Test
    fun missingNameIsMalformed() {
        val text = AgentToolProtocol.TOOL_CALL_OPEN + """{"arguments":{}}""" + AgentToolProtocol.TOOL_CALL_CLOSE
        val parsed = AgentToolProtocol.parseAssistantResponse(text)
        assertTrue(parsed.calls.isEmpty())
        assertEquals(1, parsed.malformedBlocks.size)
    }

    @Test
    fun nestedArgumentsArePreservedAsJsonText() {
        val text = callBlock("update_translation_config", """{"project_id":9,"nested":{"a":[1,2]}}""")
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        val arguments = JSONObject(parsed.calls.single().argumentsJson)
        assertEquals(9L, arguments.getLong("project_id"))
        assertEquals(2, arguments.getJSONObject("nested").getJSONArray("a").length())
    }

    @Test
    fun recoversBareJsonCallWithoutTags() {
        val parsed = AgentToolProtocol.parseAssistantResponse("""{"name":"list_books","arguments":{}}""")

        assertEquals(1, parsed.calls.size)
        assertEquals("list_books", parsed.calls.single().name)
        assertEquals("", parsed.calls.single().rawBlock)
        assertEquals("", parsed.visibleText)
    }

    @Test
    fun bareJsonWithoutNameIsNotRecovered() {
        val text = """{"foo":1}"""
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertTrue(parsed.calls.isEmpty())
        assertEquals(text, parsed.visibleText)
    }

    @Test
    fun bareJsonWithSurroundingProseIsNotRecovered() {
        val text = "请看：{\"name\":\"list_books\",\"arguments\":{}}"
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertTrue(parsed.calls.isEmpty())
        assertEquals(text, parsed.visibleText)
    }

    @Test
    fun recoveryIsSkippedWhenTaggedBlocksExist() {
        val text = callBlock("list_books", "{}") + "\n" + """{"name":"get_recent_log","arguments":{}}"""
        val parsed = AgentToolProtocol.parseAssistantResponse(text)

        assertEquals(listOf("list_books"), parsed.calls.map { it.name })
        assertEquals("""{"name":"get_recent_log","arguments":{}}""", parsed.visibleText)
    }

    @Test
    fun blankTextYieldsEmptyResult() {
        val parsed = AgentToolProtocol.parseAssistantResponse("   \n  ")
        assertTrue(parsed.calls.isEmpty())
        assertTrue(parsed.malformedBlocks.isEmpty())
        assertEquals("", parsed.visibleText)
    }

    // ---------- renderToolCatalog ----------

    @Test
    fun rendersEmptyCatalogNoticeWhenNoSpecs() {
        val catalog = AgentToolProtocol.renderToolCatalog(emptyList())
        assertTrue(catalog.contains("当前没有可用工具。"))
        assertTrue(catalog.contains("不要猜测应用内的数据"))
    }

    @Test
    fun rendersCatalogWithNormalizedSchema() {
        val spec = AgentToolSpec(
            name = "get_book_status",
            description = "查看书籍状态",
            parametersSchemaJson = """
                {
                  "type": "object",
                  "properties": {"book_id": {"type": "integer"}},
                  "required": ["book_id"]
                }
            """.trimIndent()
        )
        val catalog = AgentToolProtocol.renderToolCatalog(listOf(spec))

        assertTrue(catalog.contains("### get_book_status"))
        assertTrue(catalog.contains("查看书籍状态"))
        assertTrue(catalog.contains("参数 JSON Schema："))
        assertTrue(catalog.contains("\"book_id\""))
        assertTrue(catalog.contains("\"type\": \"integer\"".replace(" ", "")))
        assertFalse(catalog.contains("\n  "))
    }

    @Test
    fun malformedSchemaFallsBackToEmptyObjectSchema() {
        val spec = AgentToolSpec("broken", "坏 schema", "{不是合法 JSON")
        val catalog = AgentToolProtocol.renderToolCatalog(listOf(spec))
        assertTrue(catalog.contains("{\"type\":\"object\"}"))
    }

    @Test
    fun blankSchemaFallsBackToEmptyObjectSchema() {
        val spec = AgentToolSpec("blank", "空 schema", "   ")
        val catalog = AgentToolProtocol.renderToolCatalog(listOf(spec))
        assertTrue(catalog.contains("{\"type\":\"object\"}"))
    }

    // ---------- renderProtocolInstructions ----------

    @Test
    fun rendersProtocolInstructionsWithRoundLimit() {
        val instructions = AgentToolProtocol.renderProtocolInstructions(maxToolRounds = 3)

        assertTrue(instructions.contains("## 工具调用协议（必须严格遵守）"))
        assertTrue(instructions.contains("单个回合最多进行 3 轮工具调用"))
        val template = AgentToolProtocol.TOOL_CALL_OPEN +
            """{"name":"<工具名>","arguments":{<符合该工具 JSON Schema 的参数>}}""" +
            AgentToolProtocol.TOOL_CALL_CLOSE
        assertTrue(instructions.contains(template))
        assertTrue(instructions.contains("不得凭记忆或猜测作答"))
    }

    // ---------- formatToolResults / renderToolResultBlock ----------

    @Test
    fun formatToolResultsJoinsRecordsWithNewlines() {
        val records = listOf(
            ToolExecutionRecord("list_books", "{}", ok = true, output = "共 2 本书"),
            ToolExecutionRecord("get_recent_log", "{}", ok = false, output = "读取失败")
        )
        val text = AgentToolProtocol.formatToolResults(records)

        val listBooksIndex = text.indexOf("name=\"list_books\"")
        val logIndex = text.indexOf("name=\"get_recent_log\"")
        assertTrue(listBooksIndex >= 0 && logIndex > listBooksIndex)
        assertTrue(text.contains("ok=\"true\""))
        assertTrue(text.contains("ok=\"false\""))
        assertTrue(text.contains("共 2 本书"))
        assertTrue(text.contains("读取失败"))
    }

    @Test
    fun renderToolResultBlockShape() {
        val block = AgentToolProtocol.renderToolResultBlock("list_books", ok = true, output = "共 2 本书")
        assertTrue(block.startsWith(AgentToolProtocol.TOOL_RESULT_OPEN + " name=\"list_books\" ok=\"true\">"))
        assertTrue(block.endsWith(AgentToolProtocol.TOOL_RESULT_CLOSE))
        assertTrue(block.contains("\n共 2 本书\n"))
    }

    @Test
    fun renderToolResultBlockFiltersControlCharacters() {
        val block = AgentToolProtocol.renderToolResultBlock("t", ok = true, output = "a\u0001b\u0007c")
        assertTrue(block.contains("abc"))
        assertFalse(block.contains("\u0001"))
    }

    @Test
    fun renderToolResultBlockCapsLongOutput() {
        val output = "x".repeat(7_000)
        val block = AgentToolProtocol.renderToolResultBlock("t", ok = true, output = output)

        assertTrue(block.contains("已截断，原长度 7000 字符"))
        assertTrue(block.contains("x".repeat(2_900)))
        assertTrue(block.length <= AgentToolProtocol.MAX_TOOL_RESULT_CHARS + 200)
    }

    @Test
    fun renderToolResultBlockKeepsShortOutputVerbatim() {
        val output = "y".repeat(100)
        val block = AgentToolProtocol.renderToolResultBlock("t", ok = false, output)
        assertTrue(block.contains(output))
        assertFalse(block.contains("已截断"))
    }

    // ---------- renderToolCallBlock ----------

    @Test
    fun renderToolCallBlockPrefersRawBlock() {
        val raw = AgentToolProtocol.TOOL_CALL_OPEN + "原文" + AgentToolProtocol.TOOL_CALL_CLOSE
        assertEquals(raw, AgentToolProtocol.renderToolCallBlock("list_books", "{}", raw))
    }

    @Test
    fun renderToolCallBlockRebuildsWhenRawMissing() {
        val rebuilt = AgentToolProtocol.renderToolCallBlock("list_books", """{"a":1}""", rawBlock = "  ")
        assertEquals(
            AgentToolProtocol.TOOL_CALL_OPEN + """{"name":"list_books","arguments":{"a":1}}""" + AgentToolProtocol.TOOL_CALL_CLOSE,
            rebuilt
        )
    }

    // ---------- formatProtocolError / formatRoundLimitNotice ----------

    @Test
    fun formatProtocolErrorEnvelope() {
        val text = AgentToolProtocol.formatProtocolError(listOf("错误一", "错误二"))
        assertTrue(text.contains("name=\"protocol\""))
        assertTrue(text.contains("ok=\"false\""))
        assertTrue(text.contains("\"code\":\"invalid_tool_call\""))
        assertTrue(text.contains("错误一 错误二"))
        assertTrue(text.contains(AgentToolProtocol.TOOL_CALL_OPEN))
        assertTrue(text.contains("请按 expected 只输出严格 JSON 的工具调用块，或直接给出最终答复。"))
    }

    @Test
    fun formatRoundLimitNoticeEnvelope() {
        val text = AgentToolProtocol.formatRoundLimitNotice(maxToolRounds = 6)
        assertTrue(text.contains("ok=\"false\""))
        assertTrue(text.contains("\"code\":\"tool_round_limit_reached\""))
        assertTrue(text.contains("本回合已用满 6 轮工具调用，不能再调用工具。"))
        assertTrue(text.contains("请基于已经获得的信息直接给出最终答复"))
    }
}
