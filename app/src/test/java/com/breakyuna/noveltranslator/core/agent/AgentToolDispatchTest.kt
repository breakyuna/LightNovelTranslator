package com.breakyuna.noveltranslator.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentToolHost.executeTool 的参数校验与工具目录测试。
 *
 * 覆盖 2026-10-19 新增的翻译全流程工具（建工程 / 取消 / 恢复 / 导出 /
 * 术语 CRUD / 质量规则 / 费用汇总）：接口默认实现返回"未接线"占位，
 * 真实实现由 AppViewModel 覆写（不在本单测范围）。
 *
 * 需要 Robolectric：executeTool 与目录渲染走 android 的 org.json。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolDispatchTest {

    /** 全部使用默认实现（未接线）的宿主，用于验证调度与参数校验。 */
    private val host = object : AgentToolHost {
        override suspend fun listBooks(): String = "书架"
        override suspend fun getBookStatus(bookId: Long): String = "状态"
        override suspend fun startTranslation(projectId: Long, rangeStart: Int?, rangeEnd: Int?): String = "启动"
        override suspend fun pauseTranslation(projectId: Long): String = "暂停"
        override suspend fun scanGlossary(bookId: Long): String = "扫描"
        override suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String = "重分"
        override suspend fun getRecentLog(projectId: Long?, limit: Int): String = "日志"
        override suspend fun askUser(prompt: String, options: List<String>): String = "回答"
        override suspend fun updateTranslationConfig(
            projectId: Long,
            parallelChapters: Int?,
            continuityMode: String?,
            metadataEnabled: Boolean?,
            promptCacheEnabled: Boolean?
        ): String = "配置"
    }

    private suspend fun call(name: String, args: String): AgentToolOutcome =
        host.executeTool(name, args)

    // ---------- 未知工具与参数缺失 ----------

    @Test
    fun unknownToolReturnsFailureEnvelope() = runTest {
        val outcome = call("delete_everything", "{}")

        assertFalse(outcome.ok)
        assertTrue(outcome.output.contains("unknown_tool"))
        assertTrue(outcome.output.contains("delete_everything"))
    }

    @Test
    fun invalidArgumentsJsonReturnsFailureEnvelope() = runTest {
        val outcome = call(AgentToolHost.TOOL_LIST_BOOKS, "{not json")

        assertFalse(outcome.ok)
        assertTrue(outcome.output.contains("invalid_arguments"))
    }

    @Test
    fun newToolsValidateRequiredIds() = runTest {
        val toolsToIds = listOf(
            AgentToolHost.TOOL_CANCEL_TRANSLATION to "project_id",
            AgentToolHost.TOOL_RESUME_TRANSLATION to "project_id",
            AgentToolHost.TOOL_EXPORT_EDITION to "project_id",
            AgentToolHost.TOOL_LIST_GLOSSARY to "project_id",
            AgentToolHost.TOOL_ADD_GLOSSARY_ENTRY to "project_id",
            AgentToolHost.TOOL_REMOVE_GLOSSARY_ENTRY to "project_id",
            AgentToolHost.TOOL_GET_QUALITY_RULES to "project_id",
            AgentToolHost.TOOL_SET_QUALITY_RULES to "project_id",
            AgentToolHost.TOOL_GET_TRANSLATION_COST to "project_id"
        )
        for ((tool, idKey) in toolsToIds) {
            val missing = call(tool, "{}")
            assertTrue("$tool 缺少 $idKey 时应拒绝", !missing.ok || missing.output.contains(idKey))

            val illegal = call(tool, """{"$idKey":"abc"}""")
            assertTrue("$tool 的 $idKey 非法时应拒绝", !illegal.ok || illegal.output.contains(idKey))
        }
        val createMissing = call(AgentToolHost.TOOL_CREATE_TRANSLATION_PROJECT, "{}")
        assertTrue(createMissing.output.contains("book_id"))
    }

    @Test
    fun addGlossaryEntryRequiresBothTerms() = runTest {
        val outcome = call(
            AgentToolHost.TOOL_ADD_GLOSSARY_ENTRY,
            """{"project_id":1,"source_term":"韩立"}"""
        )

        // 参数校验失败按既有契约走 success 信封（消息即回执），只有 JSON 本身
        // 解析不了才是 failure 信封（invalid_arguments）。
        assertTrue(outcome.ok)
        assertTrue(outcome.output.contains("target_term"))
        assertTrue(outcome.output.contains("source_term"))
    }

    @Test
    fun removeGlossaryEntryRequiresEntryId() = runTest {
        val outcome = call(
            AgentToolHost.TOOL_REMOVE_GLOSSARY_ENTRY,
            """{"project_id":1}"""
        )

        assertTrue(outcome.output.contains("entry_id"))
    }

    @Test
    fun setQualityRulesValidatesSlotAndEntries() = runTest {
        val noSlot = call(
            AgentToolHost.TOOL_SET_QUALITY_RULES,
            """{"project_id":1,"entries":[{"src":"x"}]}"""
        )
        assertTrue(noSlot.output.contains("slot"))

        val noEntries = call(
            AgentToolHost.TOOL_SET_QUALITY_RULES,
            """{"project_id":1,"slot":"text_preserve"}"""
        )
        assertTrue(noEntries.output.contains("entries"))
    }

    // ---------- 默认实现占位 ----------

    @Test
    fun unwiredExtensionToolsReturnPlaceholderInsteadOfSilentSuccess() = runTest {
        val outcomes = listOf(
            call(AgentToolHost.TOOL_CREATE_TRANSLATION_PROJECT, """{"book_id":1}"""),
            call(AgentToolHost.TOOL_CANCEL_TRANSLATION, """{"project_id":1}"""),
            call(AgentToolHost.TOOL_RESUME_TRANSLATION, """{"project_id":1}"""),
            call(AgentToolHost.TOOL_EXPORT_EDITION, """{"project_id":1}"""),
            call(AgentToolHost.TOOL_LIST_GLOSSARY, """{"project_id":1}"""),
            call(AgentToolHost.TOOL_ADD_GLOSSARY_ENTRY, """{"project_id":1,"source_term":"a","target_term":"b"}"""),
            call(AgentToolHost.TOOL_REMOVE_GLOSSARY_ENTRY, """{"project_id":1,"entry_id":2}"""),
            call(AgentToolHost.TOOL_GET_QUALITY_RULES, """{"project_id":1}"""),
            call(AgentToolHost.TOOL_SET_QUALITY_RULES, """{"project_id":1,"slot":"text_preserve","entries":[{"src":"x"}]}"""),
            call(AgentToolHost.TOOL_GET_TRANSLATION_COST, """{"project_id":1}""")
        )

        outcomes.forEach { outcome ->
            assertTrue(outcome.ok)
            assertTrue(outcome.output.contains("尚未接线"))
        }
    }

    // ---------- 工具目录 ----------

    @Test
    fun toolCatalogIsWellFormed() {
        val specs = host.toolSpecs()

        assertTrue("工具目录应覆盖全流程工具", specs.size >= 19)
        assertEquals("工具名必须唯一", specs.map { it.name }.distinct().size, specs.size)
        specs.forEach { spec ->
            assertTrue("${spec.name} 缺少描述", spec.description.isNotBlank())
            val schema = JSONObject(spec.parametersSchemaJson)
            assertEquals("${spec.name} 的 schema 必须是 object", "object", schema.getString("type"))
        }
    }

    @Test
    fun everyCatalogToolIsDispatched() {
        val dispatched = setOf(
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
            AgentToolHost.TOOL_LIST_GLOSSARY,
            AgentToolHost.TOOL_ADD_GLOSSARY_ENTRY,
            AgentToolHost.TOOL_REMOVE_GLOSSARY_ENTRY,
            AgentToolHost.TOOL_GET_QUALITY_RULES,
            AgentToolHost.TOOL_SET_QUALITY_RULES,
            AgentToolHost.TOOL_GET_TRANSLATION_COST
        )
        val catalogNames = host.toolSpecs().map { it.name }.toSet()

        assertEquals(dispatched, catalogNames)
    }
}
