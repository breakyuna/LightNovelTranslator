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

        override suspend fun scanGlossary(
            bookId: Long,
            startChapter: Int?,
            endChapter: Int?,
            projectId: Long?
        ): String {
            invocations += "scan_glossary:$bookId:$startChapter:$endChapter:$projectId"
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
            promptCacheEnabled: Boolean?
        ): String {
            invocations += "update_translation_config:$projectId:$parallelChapters:$continuityMode:$promptCacheEnabled"
            return "配置已更新"
        }

        override suspend fun summarizeChapter(bookId: Long, chapterIndex: Int, projectId: Long?): String {
            invocations += "summarize_chapter:$bookId:$chapterIndex:$projectId"
            return "摘要"
        }
        override suspend fun getStoryMemory(projectId: Long): String = "记忆"
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
    fun startTranslationPassesRangeAndAcceptsSingleEndedRange() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":2,"range_end":9}""")
        // 只给一端也照传：另一端由宿主补成首页 / 末页，不能被静默丢弃后整本开跑。
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":20}""")
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_end":"7"}""")
        execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":"5","range_start":"3","range_end":"4"}""")
        assertEquals(
            listOf(
                "start_translation:5:2:9",
                "start_translation:5:20:null",
                "start_translation:5:null:7",
                "start_translation:5:3:4"
            ),
            host.invocations
        )
    }

    @Test
    fun startTranslationRejectsInvalidOrReversedRange() {
        val host = RecordingToolHost()
        assertEquals(
            "非法的 range_start 参数：章号是从 1 开始的整数，省略表示从第一章开始",
            execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":0}""").output
        )
        assertEquals(
            "非法的 range_end 参数：章号是从 1 开始的整数，省略表示翻到最后一章",
            execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_end":-1}""").output
        )
        assertEquals(
            "章节范围不合法：range_start(9) 大于 range_end(2)",
            execute(host, AgentToolHost.TOOL_START_TRANSLATION, """{"project_id":5,"range_start":9,"range_end":2}""").output
        )
        assertTrue(host.invocations.isEmpty())
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
        assertEquals(listOf("scan_glossary:8:null:null:null"), host.invocations)
    }

    @Test
    fun scanGlossaryPassesChapterRange() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"start_chapter":3,"end_chapter":12}""")
        // 只给一端时另一端保持 null，由宿主补成首页/末页。
        execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"start_chapter":20}""")
        execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"end_chapter":"40"}""")
        assertEquals(
            listOf(
                "scan_glossary:8:3:12:null",
                "scan_glossary:8:20:null:null",
                "scan_glossary:8:null:40:null"
            ),
            host.invocations
        )
    }

    @Test
    fun scanGlossaryPassesProjectIdAndRejectsInvalidOne() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"project_id":13}""")
        assertEquals(listOf("scan_glossary:8:null:null:13"), host.invocations)
        // 非法 project_id 不能被当成「没给」而静默落到别的工程。
        assertEquals(
            "非法的 project_id 参数：应为该书下的翻译工程 id",
            execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"project_id":"abc"}""").output
        )
        assertEquals(1, host.invocations.size)
    }

    @Test
    fun summarizeChapterPassesProjectId() {
        val host = RecordingToolHost()
        execute(host, AgentToolHost.TOOL_SUMMARIZE_CHAPTER, """{"book_id":3,"chapter_index":4}""")
        execute(host, AgentToolHost.TOOL_SUMMARIZE_CHAPTER, """{"book_id":3,"chapter_index":4,"project_id":9}""")
        assertEquals(
            "非法的 project_id 参数：应为该书下的翻译工程 id",
            execute(host, AgentToolHost.TOOL_SUMMARIZE_CHAPTER, """{"book_id":3,"chapter_index":4,"project_id":0}""").output
        )
        assertEquals(
            listOf(
                "summarize_chapter:3:4:null",
                "summarize_chapter:3:4:9"
            ),
            host.invocations
        )
    }

    @Test
    fun scanGlossaryRejectsInvalidRangeInsteadOfScanningWholeBook() {
        val host = RecordingToolHost()
        // 非法章节号不能被当成「没给」，否则会静默变成整本付费扫描。
        assertEquals(
            "非法的 start_chapter 参数：章号是从 1 开始的整数，省略表示从第一章开始",
            execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"start_chapter":0}""").output
        )
        assertEquals(
            "非法的 end_chapter 参数：章号是从 1 开始的整数，省略表示扫到最后一章",
            execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"end_chapter":"abc"}""").output
        )
        assertEquals(
            "章节范围不合法：start_chapter(30) 大于 end_chapter(10)",
            execute(host, AgentToolHost.TOOL_SCAN_GLOSSARY, """{"book_id":8,"start_chapter":30,"end_chapter":10}""").output
        )
        assertTrue(host.invocations.isEmpty())
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
            """{"project_id":3,"parallel_chapters":0,"continuity_mode":"  ","prompt_cache_enabled":"yes"}"""
        )
        execute(
            host,
            AgentToolHost.TOOL_UPDATE_TRANSLATION_CONFIG,
            """{"project_id":3,"parallel_chapters":"4","continuity_mode":"TRANSLATION_TAIL","prompt_cache_enabled":true}"""
        )
        assertEquals(
            listOf(
                "update_translation_config:3:null:null:null",
                "update_translation_config:3:4:TRANSLATION_TAIL:true"
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
    fun toolSpecsCoverAllToolsWithValidSchemas() {
        val specs = RecordingToolHost().toolSpecs()
        // 2026-10-19 起工具集从 9 个扩到 19 个，覆盖完整翻译流程；
        // 顺序即目录顺序，executeTool 的 when 分支必须一一对应。
        val expected = listOf(
            AgentToolHost.TOOL_LIST_BOOKS,
            AgentToolHost.TOOL_GET_BOOK_STATUS,
            AgentToolHost.TOOL_START_TRANSLATION,
            AgentToolHost.TOOL_PAUSE_TRANSLATION,
            AgentToolHost.TOOL_SCAN_GLOSSARY,
            AgentToolHost.TOOL_RESPLIT_CHAPTERS,
            AgentToolHost.TOOL_GET_RECENT_LOG,
            AgentToolHost.TOOL_ASK_USER,
            AgentToolHost.TOOL_UPDATE_TRANSLATION_CONFIG,
            AgentToolHost.TOOL_CREATE_TRANSLATION_PROJECT,
            AgentToolHost.TOOL_CANCEL_TRANSLATION,
            AgentToolHost.TOOL_RESUME_TRANSLATION,
            AgentToolHost.TOOL_EXPORT_EDITION,
            AgentToolHost.TOOL_SUMMARIZE_CHAPTER,
            AgentToolHost.TOOL_GET_STORY_MEMORY,
            AgentToolHost.TOOL_LIST_GLOSSARY,
            AgentToolHost.TOOL_ADD_GLOSSARY_ENTRY,
            AgentToolHost.TOOL_REMOVE_GLOSSARY_ENTRY,
            AgentToolHost.TOOL_GET_QUALITY_RULES,
            AgentToolHost.TOOL_SET_QUALITY_RULES,
            AgentToolHost.TOOL_GET_TRANSLATION_COST
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
