package com.breakyuna.noveltranslator.core.agent

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

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

    suspend fun scanGlossary(bookId: Long): String

    suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String

    suspend fun getRecentLog(projectId: Long?, limit: Int): String

    /** Blocks until the user answers (or the wait times out) and returns the answer text. */
    suspend fun askUser(prompt: String, options: List<String>): String

    suspend fun updateTranslationConfig(
        projectId: Long,
        parallelChapters: Int?,
        continuityMode: String?,
        metadataEnabled: Boolean?,
        promptCacheEnabled: Boolean?
    ): String

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
                    if (projectId == null || projectId <= 0) {
                        "缺少或非法的 project_id 参数"
                    } else {
                        startTranslation(
                            projectId = projectId,
                            rangeStart = requiredLong("range_start")?.takeIf { it > 0 }?.toInt(),
                            rangeEnd = requiredLong("range_end")?.takeIf { it > 0 }?.toInt()
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
                    if (bookId == null || bookId <= 0) {
                        "缺少或非法的 book_id 参数"
                    } else {
                        scanGlossary(bookId)
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
                            metadataEnabled = args.opt("metadata_enabled")?.let { value ->
                                value as? Boolean ?: value.toString().toBooleanStrictOrNull()
                            },
                            promptCacheEnabled = args.opt("prompt_cache_enabled")?.let { value ->
                                value as? Boolean ?: value.toString().toBooleanStrictOrNull()
                            }
                        )
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
        const val TOOL_LIST_BOOKS = "list_books"
        const val TOOL_GET_BOOK_STATUS = "get_book_status"
        const val TOOL_START_TRANSLATION = "start_translation"
        const val TOOL_PAUSE_TRANSLATION = "pause_translation"
        const val TOOL_SCAN_GLOSSARY = "scan_glossary"
        const val TOOL_RESPLIT_CHAPTERS = "resplit_chapters"
        const val TOOL_GET_RECENT_LOG = "get_recent_log"
        const val TOOL_ASK_USER = "ask_user"
        const val TOOL_UPDATE_TRANSLATION_CONFIG = "update_translation_config"

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
                description = "对整本书执行专有术语扫描，产出待审核候选。会产生付费 API 请求，调用前必须向用户确认。",
                parametersSchemaJson = """{"type":"object","properties":{"book_id":{"type":"integer","description":"目标书籍 id"}},"required":["book_id"],"additionalProperties":false}"""
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
                description = "修改翻译工程开关：parallel_chapters（同书并发章数 1-5）、continuity_mode（SOURCE_TAIL 或 TRANSLATION_TAIL）、metadata_enabled、prompt_cache_enabled。只能传需要修改的字段。",
                parametersSchemaJson = """{"type":"object","properties":{"project_id":{"type":"integer","description":"翻译工程 id"},"parallel_chapters":{"type":"integer","description":"并发章数，1-5"},"continuity_mode":{"type":"string","enum":["SOURCE_TAIL","TRANSLATION_TAIL"]},"metadata_enabled":{"type":"boolean"},"prompt_cache_enabled":{"type":"boolean"}},"required":["project_id"],"additionalProperties":false}"""
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
