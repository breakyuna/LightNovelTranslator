package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.data.model.TermCategory
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

/**
 * One agent-callable tool as advertised to the model.
 *
 * [parametersSchemaJson] must be a JSON-Schema object; it is rendered into the system prompt and
 * validated by the agent layer, which falls back to an empty object schema when it is malformed.
 */
data class AgentToolSpec(
    val name: String,
    val description: String,
    val parametersSchemaJson: String
)

/**
 * Result of one tool call, serialized as the tool-result payload the model reads back.
 *
 * [ok] mirrors success; [output] is the payload text the model reads back. Both are produced
 * by the factories so a host implementation never has to build the envelope itself.
 */
data class AgentToolOutcome(
    val ok: Boolean,
    val output: String
) {
    companion object {
        fun success(output: String): AgentToolOutcome = AgentToolOutcome(
            ok = true,
            output = output.ifBlank { "（无返回内容）" }
        )

        fun failure(code: String, message: String): AgentToolOutcome = AgentToolOutcome(
            ok = false,
            output = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString()
        )
    }
}

/**
 * The only bridge between the agent runtime and the app's translation/book features.
 *
 * Every method is a tool the agent may call. Implementations live in the ViewModel and must
 * delegate to the existing application methods instead of re-implementing business logic, so the
 * agent can never bypass the scheduler gates, the provider boundary or the audit trail.
 *
 * All methods return a human-readable string (the tool result the model sees) rather than throwing:
 * a failed tool call is information for the agent, not a crash.
 */
interface AgentToolHost {
    suspend fun listBooks(): String

    suspend fun getBookStatus(bookId: Long): String

    suspend fun startTranslation(
        projectId: Long,
        rangeStart: Int? = null,
        rangeEnd: Int? = null
    ): String

    suspend fun pauseTranslation(projectId: Long): String

    /**
     * 扫描一本书（或其中一个章节范围）的专有术语，产出待审核候选。
     *
     * [startChapter] / [endChapter] 是 1 起的章号，闭区间。两者都省略表示整本；
     * 只给一端时实现把另一端补成该书的首页 / 末页（"从第 20 章扫到结尾"）。
     * 实现必须按真实章号校验请求范围，越界时明确报错而不是静默改成别的范围——
     * 这个工具会产生付费请求，扫错范围比报错更糟。回执必须写明实际扫描的章号区间。
     *
     * [projectId] 指定候选落到哪个翻译工程；省略时实现只能在"该书只有一个工程"时
     * 自动选择，多工程必须回执候选列表让模型明确指定，不能静默取第一个。
     */
    suspend fun scanGlossary(
        bookId: Long,
        startChapter: Int? = null,
        endChapter: Int? = null,
        projectId: Long? = null
    ): String

    suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String

    suspend fun getRecentLog(projectId: Long?, limit: Int): String

    /** Blocks until the user answers (or the wait times out) and returns the answer text. */
    suspend fun askUser(prompt: String, options: List<String>): String

    suspend fun updateTranslationConfig(
        projectId: Long,
        parallelChapters: Int?,
        continuityMode: String?,
        promptCacheEnabled: Boolean?
    ): String

    // ------------------------------------------------------------------
    // 翻译全流程扩展工具
    //
    // 默认实现返回"未接线"，由集成层（AppViewModel）按需覆写；
    // 这样新增工具不会破坏既有实现方（测试替身等），而未接线状态
    // 在运行时对模型可见，不会被静默当成空结果。
    // ------------------------------------------------------------------

    /** 为一本书创建译文 Edition 与翻译工程（默认全文、串行、默认 Provider）。 */
    suspend fun createTranslationProject(
        bookId: Long,
        targetLanguage: String? = null,
        editionName: String? = null
    ): String = NOT_WIRED_MESSAGE

    /** 取消一个翻译工程；已完成并通过 QA 的章节保留，不重复计费。 */
    suspend fun cancelTranslation(projectId: Long): String = NOT_WIRED_MESSAGE

    /** 恢复一个已暂停的翻译工程，从已持久化的边界继续。 */
    suspend fun resumeTranslation(projectId: Long): String = NOT_WIRED_MESSAGE

    /** 导出工程的译文 Edition 为 TXT / EPUB（可双语对照）。 */
    suspend fun exportEdition(
        projectId: Long,
        format: String? = null,
        bilingual: Boolean? = null
    ): String = NOT_WIRED_MESSAGE

    /** 列出工程术语表条目，可按关键词过滤。 */
    suspend fun listGlossary(projectId: Long, keyword: String? = null): String = NOT_WIRED_MESSAGE

    /**
     * 新增一条术语（源文 → 译文）。
     *
     * [category] 取 TermCategory 的名称（CHARACTER / LOCATION / LORE / SKILL / ITEM /
     * HONORIFIC / CUSTOM，缺省 CUSTOM）。kind（PROPER_NOUN / TERMINOLOGY）由 category
     * 推导，不再作为独立参数暴露：此前模型按文档传"人名/地名"这类词，落库校验
     * 直接抛 Unknown glossary category，工具必然失败。
     */
    suspend fun addGlossaryEntry(
        projectId: Long,
        sourceTerm: String,
        targetTerm: String,
        category: String? = null,
        note: String? = null
    ): String = NOT_WIRED_MESSAGE

    /** 删除一条术语（entry_id 来自 list_glossary）。 */
    suspend fun removeGlossaryEntry(projectId: Long, entryId: Long): String = NOT_WIRED_MESSAGE

    /** 查看工程的质量规则三槽位（文本保护 / 译前替换 / 译后替换）。 */
    suspend fun getQualityRules(projectId: Long): String = NOT_WIRED_MESSAGE

    /** 保存一个质量规则槽位；[entriesJson] 为条目数组 JSON。 */
    suspend fun setQualityRules(
        projectId: Long,
        slot: String,
        entriesJson: String,
        enabled: Boolean? = null
    ): String = NOT_WIRED_MESSAGE

    /** 汇总一个工程的 Token 用量与费用（历次运行累计）。 */
    suspend fun getTranslationCost(projectId: Long): String = NOT_WIRED_MESSAGE

    /**
     * 对单章执行一次**独立**的摘要/记忆请求并落库。
     *
     * 元数据已与正文翻译解耦：这个工具是章节摘要与故事记忆的常规产出路径，
     * 对标 LinguaGacha 由 Agent 技能与工具负责元数据的分工。
     *
     * [projectId] 指定记忆写进哪个翻译工程；省略时只在"该书只有一个工程"时自动选择。
     */
    suspend fun summarizeChapter(bookId: Long, chapterIndex: Int, projectId: Long? = null): String

    /** 读取一个工程当前的故事记忆与最近章节摘要，供 Agent 判断连续性后处理。 */
    suspend fun getStoryMemory(projectId: Long): String

    /** Catalog rendered into the agent system prompt. Stable, so it needs no host state. */
    fun toolSpecs(): List<AgentToolSpec> = AGENT_TOOL_SPECS

    /**
     * Executes one text-protocol tool call. Argument parsing and validation live here so every
     * implementation gets identical error envelopes for free.
     */
    suspend fun executeTool(name: String, argumentsJson: String): AgentToolOutcome {
        val args = try {
            JSONObject(if (argumentsJson.isBlank()) "{}" else argumentsJson)
        } catch (error: Throwable) {
            return AgentToolOutcome.failure("invalid_arguments", "参数不是合法 JSON: ${error.localizedMessage ?: "parse error"}")
        }
        val requiredLong: (String) -> Long? = { key ->
            if (!args.has(key)) null
            else args.opt(key)?.let { value ->
                (value as? Number)?.toLong() ?: value.toString().trim().toLongOrNull()
            }
        }
        // Pair.first = 参数是否合法（缺省 / null 视为合法），Pair.second = 解析出的正整数值。
        // 与 requiredLong 不同，这里区分「没给」和「给了但非法」，避免把非法章节号静默当成整本扫描。
        val optionalPositiveInt: (String) -> Pair<Boolean, Int?> = { key ->
            if (!args.has(key) || args.isNull(key)) {
                true to null
            } else {
                val raw = args.opt(key)
                val parsed = (raw as? Number)?.toInt() ?: raw?.toString()?.trim()?.toIntOrNull()
                (parsed != null && parsed > 0) to parsed
            }
        }
        /** 可选的正整数 id：区分「没给」（合法、null）与「给了但非法」，避免静默回落到别的工程。 */
        val optionalPositiveLong: (String) -> Pair<Boolean, Long?> = { key ->
            if (!args.has(key) || args.isNull(key)) {
                true to null
            } else {
                val raw = args.opt(key)
                val parsed = (raw as? Number)?.toLong() ?: raw?.toString()?.trim()?.toLongOrNull()
                (parsed != null && parsed > 0) to parsed
            }
        }
        val output = try {
            when (name) {
                TOOL_LIST_BOOKS -> listBooks()

                TOOL_GET_BOOK_STATUS -> {
                    val bookId = requiredLong("book_id")
                    if (bookId == null || bookId <= 0) {
                        "缺少或非法的 book_id 参数"
                    } else {
                        getBookStatus(bookId)
                    }
                }

                TOOL_START_TRANSLATION -> {
                    val projectId = requiredLong("project_id")
                    val (startValid, rangeStart) = optionalPositiveInt("range_start")
                    val (endValid, rangeEnd) = optionalPositiveInt("range_end")
                    when {
                        projectId == null || projectId <= 0 -> "缺少或非法的 project_id 参数"
                        !startValid -> "非法的 range_start 参数：章号是从 1 开始的整数，省略表示从第一章开始"
                        !endValid -> "非法的 range_end 参数：章号是从 1 开始的整数，省略表示翻到最后一章"
                        rangeStart != null && rangeEnd != null && rangeStart > rangeEnd ->
                            "章节范围不合法：range_start($rangeStart) 大于 range_end($rangeEnd)"
                        else -> startTranslation(
                            projectId = projectId,
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd
                        )
                    }
                }

                TOOL_PAUSE_TRANSLATION -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        pauseTranslation(projectId)
                    }
                }

                TOOL_SCAN_GLOSSARY -> {
                    val bookId = requiredLong("book_id")
                    val (startValid, startChapter) = optionalPositiveInt("start_chapter")
                    val (endValid, endChapter) = optionalPositiveInt("end_chapter")
                    val (projectValid, projectId) = optionalPositiveLong("project_id")
                    when {
                        bookId == null || bookId <= 0 -> "缺少或非法的 book_id 参数"
                        !projectValid -> "非法的 project_id 参数：应为该书下的翻译工程 id"
                        !startValid -> "非法的 start_chapter 参数：章号是从 1 开始的整数，省略表示从第一章开始"
                        !endValid -> "非法的 end_chapter 参数：章号是从 1 开始的整数，省略表示扫到最后一章"
                        startChapter != null && endChapter != null && startChapter > endChapter ->
                            "章节范围不合法：start_chapter($startChapter) 大于 end_chapter($endChapter)"
                        else -> scanGlossary(bookId, startChapter, endChapter, projectId)
                    }
                }

                TOOL_RESPLIT_CHAPTERS -> {
                    val bookId = requiredLong("book_id")
                    if (bookId == null || bookId <= 0) {
                        "缺少或非法的 book_id 参数"
                    } else {
                        resplitChapters(bookId, previewOnly = args.optBoolean("preview_only", true))
                    }
                }

                TOOL_GET_RECENT_LOG -> {
                    val projectId = requiredLong("project_id")?.takeIf { it > 0 }
                    getRecentLog(projectId, args.optInt("limit", 50))
                }

                TOOL_ASK_USER -> {
                    val prompt = args.optString("prompt").trim()
                    if (prompt.isBlank()) {
                        "缺少 prompt 参数：ask_user 必须给出明确的问题"
                    } else {
                        val options = args.optJSONArray("options")?.let { array ->
                            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                        }.orEmpty()
                        askUser(prompt, options)
                    }
                }

                TOOL_UPDATE_TRANSLATION_CONFIG -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        updateTranslationConfig(
                            projectId = projectId,
                            parallelChapters = requiredLong("parallel_chapters")?.takeIf { it > 0 }?.toInt(),
                            continuityMode = args.optString("continuity_mode").trim().takeIf(String::isNotBlank),
                            promptCacheEnabled = args.opt("prompt_cache_enabled")?.let { value ->
                                value as? Boolean ?: value.toString().toBooleanStrictOrNull()
                            }
                        )
                    }
                }

                TOOL_CREATE_TRANSLATION_PROJECT -> {
                    val bookId = requiredLong("book_id")
                    if (bookId == null || bookId <= 0) {
                        "缺少或非法的 book_id 参数"
                    } else {
                        createTranslationProject(
                            bookId = bookId,
                            targetLanguage = args.optString("target_language").trim().takeIf(String::isNotBlank),
                            editionName = args.optString("edition_name").trim().takeIf(String::isNotBlank)
                        )
                    }
                }

                TOOL_CANCEL_TRANSLATION -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        cancelTranslation(projectId)
                    }
                }

                TOOL_RESUME_TRANSLATION -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        resumeTranslation(projectId)
                    }
                }

                TOOL_EXPORT_EDITION -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        exportEdition(
                            projectId = projectId,
                            format = args.optString("format").trim().takeIf(String::isNotBlank),
                            bilingual = args.opt("bilingual")?.let { value ->
                                value as? Boolean ?: value.toString().toBooleanStrictOrNull()
                            }
                        )
                    }
                }

                TOOL_LIST_GLOSSARY -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        listGlossary(
                            projectId = projectId,
                            keyword = args.optString("keyword").trim().takeIf(String::isNotBlank)
                        )
                    }
                }

                TOOL_ADD_GLOSSARY_ENTRY -> {
                    val projectId = requiredLong("project_id")
                    val sourceTerm = args.optString("source_term").trim()
                    val targetTerm = args.optString("target_term").trim()
                    // category 是 TermCategory 名称；kind 只作旧名兼容（同一套取值，旧的
                    // "人名/地名"式取值一律回绝并回传合法取值，让模型一次改对）。
                    val rawCategory = args.optString("category").trim()
                        .ifBlank { args.optString("kind").trim() }
                    val category = rawCategory.uppercase(Locale.ROOT).takeIf(String::isNotBlank)
                    when {
                        projectId == null || projectId <= 0 -> "缺少或非法的 project_id 参数"
                        sourceTerm.isEmpty() || targetTerm.isEmpty() ->
                            "缺少 source_term 或 target_term 参数：术语必须同时给出源文与译文"
                        category != null && category !in GLOSSARY_CATEGORIES ->
                            "非法的 category 参数：应为 " + GLOSSARY_CATEGORIES.joinToString(" / ")
                        else -> addGlossaryEntry(
                            projectId = projectId,
                            sourceTerm = sourceTerm,
                            targetTerm = targetTerm,
                            category = category,
                            note = args.optString("note").trim().takeIf(String::isNotBlank)
                        )
                    }
                }

                TOOL_REMOVE_GLOSSARY_ENTRY -> {
                    val projectId = requiredLong("project_id")
                    val entryId = requiredLong("entry_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else if (entryId == null || entryId <= 0) {
                        "缺少或非法的 entry_id 参数（可从 list_glossary 获取）"
                    } else {
                        removeGlossaryEntry(projectId, entryId)
                    }
                }

                TOOL_GET_QUALITY_RULES -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        getQualityRules(projectId)
                    }
                }

                TOOL_SET_QUALITY_RULES -> {
                    val projectId = requiredLong("project_id")
                    val slot = args.optString("slot").trim()
                    val entriesJson = entriesJsonOf(args)
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else if (slot.isEmpty()) {
                        "缺少 slot 参数：应为 text_preserve / pre_replacement / post_replacement"
                    } else if (entriesJson.isBlank()) {
                        "缺少 entries 参数：应为条目数组 JSON"
                    } else {
                        setQualityRules(
                            projectId = projectId,
                            slot = slot,
                            entriesJson = entriesJson,
                            enabled = args.opt("enabled")?.let { value ->
                                value as? Boolean ?: value.toString().toBooleanStrictOrNull()
                            }
                        )
                    }
                }

                TOOL_GET_TRANSLATION_COST -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        getTranslationCost(projectId)
                    }
                }

                TOOL_SUMMARIZE_CHAPTER -> {
                    val bookId = requiredLong("book_id")
                    val chapterIndex = requiredLong("chapter_index")?.takeIf { it > 0 }?.toInt()
                    val (projectValid, projectId) = optionalPositiveLong("project_id")
                    when {
                        bookId == null || bookId <= 0 -> "缺少或非法的 book_id 参数"
                        chapterIndex == null -> "缺少或非法的 chapter_index 参数：应为正整数章号"
                        !projectValid -> "非法的 project_id 参数：应为该书下的翻译工程 id"
                        else -> summarizeChapter(bookId, chapterIndex, projectId)
                    }
                }

                TOOL_GET_STORY_MEMORY -> {
                    val projectId = requiredLong("project_id")
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        getStoryMemory(projectId)
                    }
                }

                else -> return AgentToolOutcome.failure("unknown_tool", "未知工具: $name")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return AgentToolOutcome.failure("tool_crashed", error.localizedMessage ?: error.javaClass.simpleName)
        }
        return AgentToolOutcome.success(output)
    }

    companion object {
        /** 扩展工具未接线时对模型可见的占位回执。 */
        const val NOT_WIRED_MESSAGE = "（该工具尚未接线：集成层未实现）"

        const val TOOL_LIST_BOOKS = "list_books"
        const val TOOL_GET_BOOK_STATUS = "get_book_status"
        const val TOOL_START_TRANSLATION = "start_translation"
        const val TOOL_PAUSE_TRANSLATION = "pause_translation"
        const val TOOL_SCAN_GLOSSARY = "scan_glossary"
        const val TOOL_RESPLIT_CHAPTERS = "resplit_chapters"
        const val TOOL_GET_RECENT_LOG = "get_recent_log"
        const val TOOL_ASK_USER = "ask_user"
        const val TOOL_UPDATE_TRANSLATION_CONFIG = "update_translation_config"
        const val TOOL_CREATE_TRANSLATION_PROJECT = "create_translation_project"
        const val TOOL_CANCEL_TRANSLATION = "cancel_translation"
        const val TOOL_RESUME_TRANSLATION = "resume_translation"
        const val TOOL_EXPORT_EDITION = "export_edition"
        const val TOOL_LIST_GLOSSARY = "list_glossary"
        const val TOOL_ADD_GLOSSARY_ENTRY = "add_glossary_entry"
        const val TOOL_REMOVE_GLOSSARY_ENTRY = "remove_glossary_entry"
        const val TOOL_GET_QUALITY_RULES = "get_quality_rules"
        const val TOOL_SET_QUALITY_RULES = "set_quality_rules"
        const val TOOL_GET_TRANSLATION_COST = "get_translation_cost"
        const val TOOL_SUMMARIZE_CHAPTER = "summarize_chapter"
        const val TOOL_GET_STORY_MEMORY = "get_story_memory"

        /**
         * 术语类别取值，与 TermCategory 枚举同名。
         *
         * schema 的 enum、参数校验与集成层落库共用这一份定义，避免再次出现
         * "文档写的取值 vs 校验认的取值"两套词表。
         */
        val GLOSSARY_CATEGORIES: List<String> = TermCategory.entries.map { it.name }

        private val AGENT_TOOL_SPECS: List<AgentToolSpec> = listOf(
            AgentToolSpec(
                name = TOOL_LIST_BOOKS,
                description = "列出书架上的全部书籍及其翻译工程状态。查询任何书籍信息前先调用它获取 book_id。",
                parametersSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_GET_BOOK_STATUS,
                description = "查看一本书的章节数、阅读进度与每个翻译工程的状态、并发章数、连续性模式、元数据与提示词缓存开关、最近一次运行的完成/失败章节数。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"}},"required":["book_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_START_TRANSLATION,
                description = "启动一个翻译工程。会产生付费 API 请求，调用前必须向用户说明并得到确认。可选地只翻译指定章节范围。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"range_start":{"type":"integer","description":"起始章号（含）"},"range_end":{"type":"integer","description":"结束章号（含）"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_PAUSE_TRANSLATION,
                description = "暂停一个正在运行的翻译工程；已完成的章节保留，恢复后从持久化边界继续。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_SCAN_GLOSSARY,
                description = "对一本书的指定章节范围执行专有术语扫描，产出待审核候选。会产生付费 API 请求：" +
                    "先用 ask_user 与用户确认扫描范围（例如“先扫第 1~30 章”），不要默认整本扫描。" +
                    "省略 start_chapter / end_chapter 会扫全书，只适合用户明确要求整本时使用。" +
                    "候选按翻译工程落库：该书有多个工程时必须用 project_id 指定，否则会回执工程列表要求你选择。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"},"start_chapter":{"type":"integer","description":"起始章号（含），从 1 开始；省略表示第一章"},"end_chapter":{"type":"integer","description":"结束章号（含）；省略表示最后一章"},"project_id":{"type":"integer","description":"候选落库的翻译工程 id；该书只有一个工程时可省略"}},"required":["book_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_RESPLIT_CHAPTERS,
                description = "用 AI 重新识别书籍章节结构。preview_only 为 true 时只返回预览；为 false 时会覆盖现有章节划分，必须先向用户确认。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"},"preview_only":{"type":"boolean","description":"是否只预览不应用，默认 true"}},"required":["book_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_GET_RECENT_LOG,
                description = "读取最近的系统日志，用于回答任务进度、失败原因与费用相关问题。project_id 可省略以读取全局日志。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"可选，按翻译工程过滤"},"limit":{"type":"integer","description":"返回条数，默认 50，最大 200"}},"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_ASK_USER,
                description = "向用户提出一个问题并等待回答。参数不足或需要用户在多个方案中做选择时使用。",
                parametersSchemaJson = """{"type":"object","properties":{"prompt":{"type":"string","description":"要问用户的问题"},"options":{"type":"array","items":{"type":"string"},"description":"可选，供用户选择项"}},"required":["prompt"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_UPDATE_TRANSLATION_CONFIG,
                description = "修改翻译工程开关：parallel_chapters（同书并发章数 1-5）、continuity_mode（SOURCE_TAIL 或 TRANSLATION_TAIL）、prompt_cache_enabled。只能传需要修改的字段。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"parallel_chapters":{"type":"integer","description":"并发章数，1-5"},"continuity_mode":{"type":"string","enum":["SOURCE_TAIL","TRANSLATION_TAIL"]},"prompt_cache_enabled":{"type":"boolean"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_CREATE_TRANSLATION_PROJECT,
                description = "为一本书创建译文 Edition 和翻译工程（默认全文、串行、默认 Provider）。创建后可调整配置并启动。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"},"target_language":{"type":"string","description":"译文语言，缺省按书本语言推断"},"edition_name":{"type":"string","description":"译文版本名，缺省自动生成"}},"required":["book_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_CANCEL_TRANSLATION,
                description = "取消一个翻译工程。已完成并通过 QA 的章节保留，不会重复计费。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_RESUME_TRANSLATION,
                description = "恢复一个已暂停的翻译工程，从已持久化的边界继续。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_EXPORT_EDITION,
                description = "把一个翻译工程的译文导出为 TXT 或 EPUB 文件，可双语对照。导出前先告知用户格式与文件名。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"format":{"type":"string","enum":["txt","epub"],"description":"导出格式，默认 txt"},"bilingual":{"type":"boolean","description":"是否双语对照导出，默认否"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_SUMMARIZE_CHAPTER,
                description = "对某一章执行一次独立的摘要与故事记忆更新请求并落库。会产生付费 API 请求，调用前必须向用户确认。元数据与正文翻译已解耦，摘要是章节记忆的唯一产出路径。该书有多个翻译工程时用 project_id 指定记忆归属，否则会回执工程列表要求你选择。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"},"chapter_index":{"type":"integer","description":"章号，从 1 开始"},"project_id":{"type":"integer","description":"记忆落库的翻译工程 id；该书只有一个工程时可省略"}},"required":["book_id","chapter_index"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_GET_STORY_MEMORY,
                description = "读取一个翻译工程当前的故事记忆与最近章节摘要。翻译前建立上下文、或判断某条记忆是否过期时使用。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_LIST_GLOSSARY,
                description = "查看翻译工程的术语表条目，可按关键词过滤。翻译或审校术语问题前先调用它。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"keyword":{"type":"string","description":"可选，按源文或译文过滤"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_ADD_GLOSSARY_ENTRY,
                description = "向翻译工程添加一条术语（源文→译文）。category 取 CHARACTER/LOCATION/LORE/SKILL/ITEM/HONORIFIC/CUSTOM，缺省 CUSTOM。会影响后续章节翻译，调用前必须向用户确认。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"source_term":{"type":"string","description":"源文术语"},"target_term":{"type":"string","description":"译文"},"category":{"type":"string","enum":["CHARACTER","LOCATION","LORE","SKILL","ITEM","HONORIFIC","CUSTOM"],"description":"术语类别，缺省 CUSTOM"},"note":{"type":"string","description":"备注"}},"required":["project_id","source_term","target_term"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_REMOVE_GLOSSARY_ENTRY,
                description = "删除翻译工程中的一条术语。entry_id 来自 list_glossary 回执。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"entry_id":{"type":"integer","description":"术语条目 id"}},"required":["project_id","entry_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_GET_QUALITY_RULES,
                description = "查看翻译工程的质量规则：文本保护、译前替换、译后替换三个槽位的启用状态、模式与条目。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_SET_QUALITY_RULES,
                description = "保存一个质量规则槽位的条目。text_preserve 条目为 {\"src\":\"正则\",\"note\":\"说明\"}；pre/post_replacement 条目为 {\"pattern\":\"源文\",\"replacement\":\"译文\",\"isRegex\":false,\"caseSensitive\":false}。会改变后续翻译行为，调用前必须向用户确认。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"slot":{"type":"string","enum":["text_preserve","pre_replacement","post_replacement"],"description":"规则槽位"},"entries":{"type":"array","description":"条目数组"},"enabled":{"type":"boolean","description":"是否启用该槽位，缺省保持原值"}},"required":["project_id","slot","entries"],"additionalProperties":false}"""
            ),
            AgentToolSpec(
                name = TOOL_GET_TRANSLATION_COST,
                description = "汇总一个翻译工程的 Token 用量与费用（历次运行累计），用于回答成本相关问题。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"}},"required":["project_id"],"additionalProperties":false}"""
            )
        )
    }
}

/** A question the agent asked and the UI must present to the user. */
data class AgentUserQuestion(
    val id: String,
    val prompt: String,
    val options: List<String> = emptyList()
)

/**
 * 从工具参数中取出规则条目数组 JSON：优先嵌套数组（模型按 schema 传对象），
 * 其次接受整段字符串化的 JSON（部分模型只会输出字符串）。
 */
private fun entriesJsonOf(args: JSONObject): String {
    args.optJSONArray("entries")?.let { array -> return array.toString() }
    return args.optString("entries").trim()
}
