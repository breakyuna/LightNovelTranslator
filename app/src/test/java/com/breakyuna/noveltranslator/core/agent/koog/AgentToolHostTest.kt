package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.agent.AgentToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentToolHost.executeTool 的调度、参数校验与错误归一测试。
 *
 * executeTool 是接口默认方法：参数解析/校验/异常归一都在这里完成，
 * 所有实现（含 AppViewModel 中的 AgentToolHostImpl）自动获得一致行为。
 * 依赖 org.json，故使用 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolHostTest {

    private class RecordingToolHost : AgentToolHost {
        val invocations = mutableListOf<String>()
        var listBooksOutput = "共 2 本书"
        var listBooksError: Throwable? = null

        override suspend fun listBooks(): String {
            invocations += "list_books"
            listBooksError?.let { throw it }
            return listBooksOutput
        }

        override suspend fun getBookStatus(bookId: Long): String {
            invocations += "get_book_status:$bookId"
            return "Book#$bookId 状态正常"
        }

        override suspend fun startTranslation(
            projectId: Long,
            rangeStart: Int?,
            rangeEnd: Int?
        ): String {
            invocations += "start_translation:$projectId:$rangeStart:$rangeEnd"
            return "已启动工程 #$projectId"
        }

        override suspend fun pauseTranslation(projectId: Long): String {
            invocations += "pause_translation:$projectId"
            return "已请求暂停工程 #$projectId"
        }

        override suspend fun scanGlossary(bookId: Long): String {
            invocations += "scan_glossary:$bookId"
            return "术语扫描完成"
        }

        override suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String {
            invocations += "resplit_chapters:$bookId:$previewOnly"
            return "识别完成"
        }

        override suspend fun getRecentLog(projectId: Long?, limit: Int): String {
            invocations += "get_recent_log:$projectId:$limit"
            return "日志 $limit 条"
        }

        override suspend fun askUser(prompt: String, options: List<String>): String {
            invocations += "ask_user:$prompt:$options"
            return "用户回答"
        }

        override suspend fun updateTranslationConfig(
            projectId: Long,
            parallelChapters: Int?,
            continuityMode: String?,
            metadataEnabled: Boolean?,
            promptCacheEnabled: Boolean?
        ): String {
            invocations += "update_translation_config:$projectId:$parallelChapters:$continuityMode:$metadataEnabled:$promptCacheEnabled"
            return "配置已更新"
        }
    }

    private fun execute(host: AgentToolHost, name: String, argumentsJson: String): AgentToolOutcome =
        runBlocking { host.executeTool(name, argumentsJson) }

    @Test
    fun dispatchesListBooksWithoutArguments() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_LIST_BOOKS, "{}")
        assertTrue(outcome.ok)
        assertEquals("共 2 本书", outcome.output)
        assertEquals(listOf("list_books"), host.invocations)
    }

    @Test
    fun blankArgumentsAreTreatedAsEmptyObject() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_LIST_BOOKS, "   ")
        assertTrue(outcome.ok)
        assertEquals(listOf("list_books"), host.invocations)
    }

    @Test
    fun getBookStatusAcceptsStringAndNumberIds() {
        val host = RecordingToolHost()
        assertTrue(execute(host, AgentToolHost.TOOL_GET_BOOK_STATUS, """{"book_id":12}""").ok)
        assertTrue(execute(host, AgentToolHost.TOOL_GET_BOOK_STATUS, """{"book_id":"13"}""").ok)
        assertEquals(listOf("get_book_status:12", "get_book_status:13"), host.invocations)
    }

    @Test
    fun getBookStatusRejectsMissingOrNonPositiveId() {
        val host = RecordingToolHost()
        listOf("{}", """{"book_id":0}""", """{"book_id":-3}""", """{"book_id":"abc"}""", """{"book_id":null}""").forEach { args ->
            val outcome = execute(host, AgentToolHost.TOOL_GET_BOOK_STATUS, args)
            // 现状记录：参数校验失败走 success 信封（ok=true）返回中文说明。
            // 这与协议提示词中“ok=false 表示调用失败”的约定不一致，见审查报告。
            assertTrue("参数非法时应给出说明: $args", outcome.ok)
            assertEquals("缺少或非法的 book_id 参数", outcome.output)
        }
        assertTrue(host.invocations.isEmpty())
    }

    @Test
    fun startTranslationPassesPositiveRangeAndFiltersNonPositive() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":2,"range_end":9}""")
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":0,"range_end":-1}""")
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":"5","range_start":"3","range_end":"4"}""")
        assertEquals(
            listOf(
                "start_translation:5:2:9",
                "start_translation:5:null:null",
                "start_translation:5:3:4"
            ),
            host.invocations
        )
    }

    @Test
    fun startTranslationRejectsMissingProjectId() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"range_start":1}""")
        assertTrue(outcome.ok)
        assertEquals("缺少或非法的 project_id 参数", outcome.output)
        assertTrue(host.invocations.isEmpty())
    }

    @Test
    fun pauseTranslationDelegates() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_PAUSE_TRANSLATION, """{"project_id":6}""")
        assertTrue(outcome.ok)
        assertEquals(listOf("pause_translation:6"), host.invocations)
    }

    @Test
    fun scanGlossaryDelegates() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8}""")
        assertTrue(outcome.ok)
        assertEquals(listOf("scan_glossary:8"), host.invocations)
    }

    @Test
    fun resplitChaptersDefaultsPreviewOnlyToTrue() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_RESPLIT_CHAPTERS, """{"book_id":4}""")
        execute(host, AgentToolHost.TOOL_RESPLIT_CHAPTERS, """{"book_id":4,"preview_only":false}""")
        execute(host, AgentToolHost.TOOL_RESPLIT_CHAPTERS, """{"book_id":4,"preview_only":"false"}""")
        execute(host, AgentToolHost.TOOL_RESPLIT_CHAPTERS, """{"book_id":4,"preview_only":"true"}""")
        execute(host, AgentToolHost.TOOL_RESPLIT_CHAPTERS, """{"book_id":4,"preview_only":"其他"}""")
        assertEquals(
            listOf(
                "resplit_chapters:4:true",
                "resplit_chapters:4:false",
                // org.json 的 optBoolean 会把 "true"/"false" 字符串强转为布尔，
                // 其他字符串保持默认值 true。
                "resplit_chapters:4:false",
                "resplit_chapters:4:true",
                "resplit_chapters:4:true"
            ),
            host.invocations
        )
    }

    @Test
    fun getRecentLogOptionalProjectAndDefaultLimit() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_GET_RECENT_LOG, "{}")
        execute(host, AgentToolHost.TOOL_GET_RECENT_LOG, """{"project_id":9,"limit":200}""")
        execute(host, AgentToolHost.TOOL_GET_RECENT_LOG, """{"project_id":0,"limit":5}""")
        assertEquals(
            listOf("get_recent_log:null:50", "get_recent_log:9:200", "get_recent_log:null:5"),
            host.invocations
        )
    }

    @Test
    fun askUserRequiresPromptAndFiltersBlankOptions() {
        val host = RecordingToolHost()
        val missing = execute(host, AgentToolHost.TOOL_ASK_USER, """{"options":["A"]}""")
        assertTrue(missing.ok)
        assertEquals("缺少 prompt 参数：ask_user 必须给出明确的问题", missing.output)

        val blank = execute(host, AgentToolHost.TOOL_ASK_USER, """{"prompt":"  "}""")
        assertEquals("缺少 prompt 参数：ask_user 必须给出明确的问题", blank.output)

        execute(host, AgentToolHost.TOOL_ASK_USER, """{"prompt":"选哪个方案","options":["A","","B"]}""")
        execute(host, AgentToolHost.TOOL_ASK_USER, """{"prompt":"直接问"}""")
        assertEquals(
            listOf("ask_user:选哪个方案:[A, B]", "ask_user:直接问:[]"),
            host.invocations
        )
    }

    @Test
    fun updateTranslationConfigNormalizesFields() {
        val host = RecordingToolHost()
        execute(
            host,
            AgentToolHost.TOOL_UPDATE_TRANSLATION_CONFIG,
            """{"project_id":3,"parallel_chapters":0,"continuity_mode":"  ","metadata_enabled":"true","prompt_cache_enabled":"yes"}"""
        )
        execute(
            host,
            AgentToolHost.TOOL_UPDATE_TRANSLATION_CONFIG,
            """{"project_id":3,"parallel_chapters":"4","continuity_mode":"TRANSLATION_TAIL","metadata_enabled":false,"prompt_cache_enabled":true}"""
        )
        assertEquals(
            listOf(
                "update_translation_config:3:null:null:true:null",
                "update_translation_config:3:4:TRANSLATION_TAIL:false:true"
            ),
            host.invocations
        )
    }

    @Test
    fun unknownToolReturnsFailureEnvelope() {
        val host = RecordingToolHost()
        val outcome = execute(host, "delete_everything", "{}")
        assertFalse(outcome.ok)
        val payload = JSONObject(outcome.output)
        assertEquals("unknown_tool", payload.getString("code"))
        assertTrue(payload.getString("message").contains("delete_everything"))
        assertTrue(host.invocations.isEmpty())
    }

    @Test
    fun invalidArgumentsJsonReturnsFailureEnvelope() {
        val host = RecordingToolHost()
        val outcome = execute(host, AgentToolHost.TOOL_LIST_BOOKS, "{不是 JSON")
        assertFalse(outcome.ok)
        assertEquals("invalid_arguments", JSONObject(outcome.output).getString("code"))
    }

    @Test
    fun toolCrashBecomesFailureEnvelope() {
        val host = RecordingToolHost().apply { listBooksError = RuntimeException("boom") }
        val outcome = execute(host, AgentToolHost.TOOL_LIST_BOOKS, "{}")
        assertFalse(outcome.ok)
        val payload = JSONObject(outcome.output)
        assertEquals("tool_crashed", payload.getString("code"))
        assertTrue(payload.getString("message").contains("boom"))
    }

    @Test
    fun blankSuccessOutputGetsPlaceholder() {
        val host = RecordingToolHost().apply { listBooksOutput = "   " }
        val outcome = execute(host, AgentToolHost.TOOL_LIST_BOOKS, "{}")
        assertTrue(outcome.ok)
        assertEquals("（无返回内容）", outcome.output)
    }

    @Test
    fun cancellationFromHostIsRethrownNotWrapped() {
        val host = RecordingToolHost().apply { listBooksError = CancellationException("stop") }
        try {
            execute(host, AgentToolHost.TOOL_LIST_BOOKS, "{}")
            throw AssertionError("取消异常应原样上抛，不得包装成失败回执")
        } catch (expected: CancellationException) {
            // 预期路径：executeTool 只捕获非取消异常
        }
    }

    @Test
    fun failureEnvelopeCarriesCodeAndMessage() {
        val outcome = AgentToolOutcome.failure("some_code", "some message")
        assertFalse(outcome.ok)
        val payload = JSONObject(outcome.output)
        assertFalse(payload.getBoolean("ok"))
        assertEquals("some_code", payload.getString("code"))
        assertEquals("some message", payload.getString("message"))
    }

    @Test
    fun toolSpecsCoverAllNineToolsWithValidSchemas() {
        val specs = RecordingToolHost().toolSpecs()
        val expected = listOf(
            AgentToolHost.TOOL_LIST_BOOKS,
            AgentToolHost.TOOL_GET_BOOK_STATUS,
            AgentToolHost.TOOL_START_TRANSLATION,
            AgentToolHost.TOOL_PAUSE_TRANSLATION,
            AgentToolHost.TOOL_SCAN_GLOSSARY,
            AgentToolHost.TOOL_RESPLIT_CHAPTERS,
            AgentToolHost.TOOL_GET_RECENT_LOG,
            AgentToolHost.TOOL_ASK_USER,
            AgentToolHost.TOOL_UPDATE_TRANSLATION_CONFIG
        )
        assertEquals(expected, specs.map { it.name })
        assertEquals(expected.size, specs.map { it.name }.toSet().size)
        specs.forEach { spec ->
            assertTrue(spec.description.isNotBlank())
            val schema = JSONObject(spec.parametersSchemaJson)
            assertEquals("object", schema.getString("type"))
        }
    }
}
