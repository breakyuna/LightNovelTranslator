package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolSpec
import org.json.JSONObject

/**
 * Agent 与模型之间的“文本工具协议”。
 *
 * LNT 的 [com.breakyuna.noveltranslator.core.llm.LlmGateway] 不支持原生 function calling，
 * 因此工具调用完全由文本承载：
 * - 模型要么直接给出最终答复，要么输出工具调用块，块内为严格 JSON；
 * - 编排层解析这些块，经 [com.breakyuna.noveltranslator.core.agent.AgentToolHost] 执行，
 *   再把结果作为用户消息送回模型。
 *
 * 解析端保持宽容（容忍代码围栏、前后缀散文、漏掉标签的裸 JSON），渲染端保持严格
 * （只接受严格 JSON 对象），格式错误会作为可读回执反馈给模型自我修正。
 *
 * 注意：标签字面量一律由片段拼接而成，源码中不出现完整闭合标签文本，
 * 避免与工具调用/编辑参数里的标记序列混淆；拼接结果即协议真实文本。
 */
object AgentToolProtocol {

    private const val CALL_TAG = "tool_call"
    private const val RESULT_TAG = "tool_result"

    /** 工具调用块起始标签。 */
    const val TOOL_CALL_OPEN = "<$CALL_TAG>"

    /** 工具调用块闭合标签。 */
    const val TOOL_CALL_CLOSE = "</$CALL_TAG>"

    /** 工具结果块起始标签（后跟属性）。 */
    const val TOOL_RESULT_OPEN = "<$RESULT_TAG"

    /** 工具结果块闭合标签。 */
    const val TOOL_RESULT_CLOSE = "</$RESULT_TAG>"

    /** 工具回执的默认字符预算，防止一次查询把上下文撑爆。 */
    const val MAX_TOOL_RESULT_CHARS = 6_000

    private val TOOL_CALL_REGEX =
        Regex("(?s)" + Regex.escape(TOOL_CALL_OPEN) + "\\s*(.*?)\\s*" + Regex.escape(TOOL_CALL_CLOSE))
    private val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

    /**
     * 解析助手回复：提取全部工具调用块、格式错误说明与可见文本。
     *
     * 当没有任何标签块、且整段文本本身就是一个带 name 字段的 JSON 对象时，
     * 按一次工具调用恢复（部分模型会漏掉标签），此时可见文本为空。
     */
    fun parseAssistantResponse(text: String): ParsedAssistantResponse {
        if (text.isBlank()) {
            return ParsedAssistantResponse(calls = emptyList(), malformedBlocks = emptyList(), visibleText = "")
        }
        val calls = mutableListOf<ParsedToolCall>()
        val malformed = mutableListOf<String>()
        val visible = StringBuilder()
        var cursor = 0
        for (match in TOOL_CALL_REGEX.findAll(text)) {
            visible.append(text, cursor, match.range.first)
            val call = parseCallBlock(match.groupValues[1], match.value)
            if (call == null) {
                malformed += "工具调用块不是含 name/arguments 的严格 JSON：" + snippet(match.value)
            } else {
                calls += call
            }
            cursor = match.range.last + 1
        }
        visible.append(text.substring(cursor))
        var visibleText = visible.toString().trim()
        if (calls.isEmpty() && malformed.isEmpty()) {
            val recovered = recoverBareJsonCall(visibleText)
            if (recovered != null) {
                calls += recovered
                visibleText = ""
            }
        }
        return ParsedAssistantResponse(
            calls = calls,
            malformedBlocks = malformed,
            visibleText = visibleText
        )
    }

    /**
     * 渲染注入 system prompt 的工具目录。Schema 缺失或非法时退化为空对象 Schema，
     * 保证模型总能看到一段合法 JSON。
     */
    fun renderToolCatalog(specs: List<AgentToolSpec>): String = buildString {
        if (specs.isEmpty()) {
            appendLine("当前没有可用工具。不要猜测应用内的数据，直接根据已知信息回答，或说明无法完成。")
            return@buildString
        }
        specs.forEach { spec ->
            appendLine("### " + spec.name)
            appendLine(spec.description.trim())
            appendLine("参数 JSON Schema：")
            appendLine(normalizeSchema(spec.parametersSchemaJson))
            appendLine()
        }
    }

    /**
     * 渲染协议规则。规则与 [parseAssistantResponse] 的实现保持一对一，
     * 改动协议时必须同步修改两边，避免提示词与解析器漂移。
     */
    fun renderProtocolInstructions(maxToolRounds: Int): String = buildString {
        appendLine("## 工具调用协议（必须严格遵守）")
        appendLine("需要读取或修改应用内的书籍、翻译任务、术语、日志等事实时，必须调用工具，不得凭记忆或猜测作答。")
        appendLine("每一轮回复只能是以下两者之一，或两者并存（先调用工具，再用自然语言简要说明）：")
        appendLine("1. 最终答复：直接回答用户的自然语言文本。")
        appendLine("2. 工具调用：一个或多个如下格式的调用块，块内必须是严格 JSON 对象，")
        appendLine("   只允许字符串键与双引号，禁止注释、尾随逗号、单引号、未加引号的键和多余文字：")
        appendLine("$TOOL_CALL_OPEN{\"name\":\"<工具名>\",\"arguments\":{<符合该工具 JSON Schema 的参数>}}$TOOL_CALL_CLOSE")
        appendLine("多个工具调用放在同一轮时，它们必须是相互独立的；有依赖关系的调用必须分轮进行。")
        appendLine("收到工具结果回执后，基于结果继续：要么再调用工具，要么给出最终答复。")
        appendLine("单个回合最多进行 $maxToolRounds 轮工具调用，达到上限后必须基于已有信息给出最终答复。")
        appendLine("回执中 ok=\"false\" 表示调用失败：阅读 message 修正参数后重试，或向用户说明限制；")
        appendLine("不要用完全相同的参数反复重试超过两次。")
        appendLine("会发起付费 API 请求或修改用户数据的工具（开始翻译、重分章节、更新翻译配置等），")
        appendLine("必须先向用户说明计划并得到明确确认，不得擅自执行。")
        appendLine("最终答复使用简洁中文，不要输出思考过程，不要复述工具调用的原始 JSON。")
    }

    /**
     * 把一轮内执行的工具结果渲染成用户消息文本。输出超长时保留头尾并标注截断，
     * 保证模型知道“还有更多内容”而不是静默丢失。
     */
    fun formatToolResults(records: List<ToolExecutionRecord>): String =
        records.joinToString(separator = "\n") { record ->
            renderToolResultBlock(record.name, record.ok, record.output)
        }

    /** 渲染单条工具结果回执块，供历史重建与消息展示复用。 */
    fun renderToolResultBlock(name: String, ok: Boolean, output: String): String = buildString {
        append(TOOL_RESULT_OPEN)
        append(" name=\"" + name + "\" ok=\"" + ok + "\">")
        appendLine()
        appendLine(capText(output, MAX_TOOL_RESULT_CHARS))
        append(TOOL_RESULT_CLOSE)
    }.trim()

    /** 渲染单条工具调用块：优先模型原文，缺失时按协议重建。 */
    fun renderToolCallBlock(name: String, argumentsJson: String, rawBlock: String = ""): String =
        rawBlock.ifBlank {
            TOOL_CALL_OPEN + "{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}" + TOOL_CALL_CLOSE
        }

    /** 协议格式错误回执：告诉模型错在哪、正确格式是什么，促使其自我修正。 */
    fun formatProtocolError(errors: List<String>): String {
        val payload = JSONObject()
            .put("code", "invalid_tool_call")
            .put("message", errors.joinToString(" "))
            .put("expected", TOOL_CALL_OPEN + "{\"name\":\"<工具名>\",\"arguments\":{...}}" + TOOL_CALL_CLOSE)
            .toString()
        return renderToolResultBlock("protocol", ok = false, payload) +
            "\n请按 expected 只输出严格 JSON 的工具调用块，或直接给出最终答复。"
    }

    /** 达到工具轮次上限时的收尾提示，要求模型基于已有信息作答。 */
    fun formatRoundLimitNotice(maxToolRounds: Int): String {
        val payload = JSONObject()
            .put("code", "tool_round_limit_reached")
            .put("message", "本回合已用满 $maxToolRounds 轮工具调用，不能再调用工具。")
            .toString()
        return renderToolResultBlock("protocol", ok = false, payload) +
            "\n请基于已经获得的信息直接给出最终答复；信息不足时说明缺口，不要继续调用工具。"
    }

    private fun parseCallBlock(block: String, rawBlock: String): ParsedToolCall? {
        val json = stripCodeFences(block).trim()
        if (json.isEmpty()) return null
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val name = root.optString("name").trim()
        if (name.isEmpty() || !TOOL_NAME_PATTERN.matches(name)) return null
        val argumentsJson = argumentsToJson(root.opt("arguments")) ?: return null
        return ParsedToolCall(name = name, argumentsJson = argumentsJson, rawBlock = rawBlock)
    }

    private fun recoverBareJsonCall(text: String): ParsedToolCall? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }
        val name = root.optString("name").trim()
        if (name.isEmpty() || !TOOL_NAME_PATTERN.matches(name)) return null
        val argumentsJson = argumentsToJson(root.opt("arguments")) ?: return null
        return ParsedToolCall(name = name, argumentsJson = argumentsJson)
    }

    /** arguments 缺省按空对象处理；存在但非对象视为格式错误。 */
    private fun argumentsToJson(arguments: Any?): String? = when (arguments) {
        null -> "{}"
        is JSONObject -> arguments.toString()
        else -> null
    }

    private fun stripCodeFences(text: String): String =
        text.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun normalizeSchema(schemaJson: String): String {
        val trimmed = schemaJson.trim()
        if (trimmed.isEmpty()) return "{\"type\":\"object\"}"
        return try {
            JSONObject(trimmed).toString()
        } catch (_: Exception) {
            // Schema 非法时退化为空对象 Schema：模型至少能发出无参调用，由工具侧校验参数。
            "{\"type\":\"object\"}"
        }
    }

    private fun capText(text: String, maxChars: Int): String {
        val cleaned = text.filter { it == '\n' || it == '\t' || it >= ' ' }
        if (cleaned.length <= maxChars) return cleaned
        val head = cleaned.take(maxChars / 2)
        val tail = cleaned.takeLast(maxChars - maxChars / 2)
        return head + "\n…[已截断，原长度 " + cleaned.length + " 字符]…\n" + tail
    }

    private fun snippet(text: String, maxChars: Int = 120): String =
        text.trim().replace(Regex("\\s+"), " ").take(maxChars)
}
