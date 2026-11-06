package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolSpec
import org.json.JSONArray
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
 * 解析端宽容、渲染端严格的不对称设计在本轮加固后包含：
 * 1. **Unicode 归一化**：全角字符先折叠为 ASCII 再做标签匹配与 JSON 解析，
 *    修掉弱模型输出 `＜tool_call＞` / 全角引号时整段被当成最终答复的静默失败；
 * 2. **模糊调用提取**：`tool_call` / `tool_use` / `invoke` / `function_call` 标签变体
 *    （大小写不敏感、开闭必须同变体），以及散文里含已知工具名的最外层 JSON 块；
 * 3. **宽容 JSON 修复梯**：严格解析失败后依次尝试全角归一化、去尾逗号、单引号、
 *    无引号键、去注释，仍失败才判 malformed；
 * 4. **静默失败可检测**：[detectSuspectedToolCall] 识别“想调用但没解析出来”的回复，
 *    由编排层走格式纠正回执而不是直接当最终答复 break；
 * 5. **答复清洗**：[cleanFinalText] 剥掉思考块与残留的调用/回执原始块。
 *
 * 折叠只服务于解析判断：[foldFullWidth] 是严格 1:1 字符映射，索引与原文本对齐，
 * 因此可见文本始终从原文切片，中文正文不受影响。
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

    private const val THINK_OPEN = "<" + "think" + ">"
    private const val THINK_CLOSE = "</" + "think" + ">"

    /** 回执块集合后的边界说明，缓解弱模型把回执当成用户的新消息。 */
    private const val TOOL_RESULT_BOUNDARY_HINT =
        "以上是本回合的工具回执（不是用户的新消息），请基于它继续：要么再输出工具调用块，要么给出最终答复。"

    /**
     * 与 tool_call 等价、弱模型可能混用的“调用包装”标签变体。
     * 提示词端仍只推荐标准 tool_call，这里是解析端的兜底。
     */
    private val CALL_TAG_VARIANTS = listOf(CALL_TAG, "tool_use", "invoke", "function_call")

    /**
     * 调用块正则：开闭标签必须是同一变体（反向引用 \1），大小写不敏感，
     * 允许标签属性与块内换行。匹配在 [foldFullWidth] 后的文本上进行。
     */
    private val CALL_BLOCK_REGEX = Regex(
        "(?s)<\\s*(" + CALL_TAG_VARIANTS.joinToString("|") { Regex.escape(it) } + ")\\b[^>]*>" +
            "\\s*(.*?)\\s*<\\s*/\\s*\\1\\s*>",
        RegexOption.IGNORE_CASE
    )

    /** 回执块正则：与调用块同规则，供最终答复清洗摘除模型回显的原始回执。 */
    private val RESULT_BLOCK_REGEX = Regex(
        "(?s)<\\s*" + RESULT_TAG + "\\b[^>]*>.*?<\\s*/\\s*" + RESULT_TAG + "\\s*>",
        RegexOption.IGNORE_CASE
    )

    /** 落单的裸标签（开标签没闭合、闭标签落单等），清洗时整枚摘除。 */
    private val STRAY_TAG_REGEX = Regex(
        "<\\s*/?\\s*(" + (CALL_TAG_VARIANTS + RESULT_TAG).joinToString("|") { Regex.escape(it) } + ")\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )

    private val THINK_BLOCK_REGEX =
        Regex("(?s)" + Regex.escape(THINK_OPEN) + ".*?" + Regex.escape(THINK_CLOSE))

    private val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

    // ---------- 疑似调用检测 ----------

    /** 协议标签痕迹：开标签没闭合、变体写法、半截标签都算。 */
    private val TAG_HINT_REGEX = Regex(
        "<\\s*/?\\s*(" + CALL_TAG_VARIANTS.joinToString("|") { Regex.escape(it) } + ")\\b",
        RegexOption.IGNORE_CASE
    )
    private val NAME_FIELD_REGEX = Regex("\"?\\s*name\\s*\"?\\s*:", RegexOption.IGNORE_CASE)
    private val ARGUMENTS_FIELD_REGEX = Regex("\"?\\s*arguments\\s*\"?\\s*:", RegexOption.IGNORE_CASE)
    private val QUOTED_NAME_VALUE = Regex("\"\\s*name\\s*\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val BARE_NAME_VALUE = Regex("\\bname\\s*[:=]\\s*([A-Za-z_][A-Za-z0-9_]*)", RegexOption.IGNORE_CASE)

    // ---------- 宽容 JSON 修复梯 ----------

    private val TRAILING_COMMA_REGEX = Regex(",\\s*([}\\]])")
    private val SINGLE_QUOTED_REGEX = Regex("'((?:[^'\\\\]|\\\\.)*)'")
    private val UNQUOTED_KEY_REGEX = Regex("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)")
    private val BLOCK_COMMENT_REGEX = Regex("/\\*[\\s\\S]*?\\*/")
    private val LINE_COMMENT_REGEX = Regex("//[^\n]*")

    private val CODE_FENCE_PREFIX = Regex("^```[a-zA-Z]*\\s*")
    private val CODE_FENCE_SUFFIX = Regex("\\s*```$")
    private val EXCESS_NEWLINES = Regex("\n{3,}")

    /**
     * 解析助手回复：提取全部工具调用块、格式错误说明与可见文本。
     *
     * - 标签匹配在归一化副本上进行，容忍全角括号、大小写与 [CALL_TAG_VARIANTS] 变体；
     * - 没有任何标签块时，整段文本本身是一个带 name 的 JSON 对象即按一次调用恢复
     *   （部分模型会漏掉标签），此时可见文本为空；
     * - [knownToolNames] 非空时，额外从散文里提取“最外层 {...} 且 name 是已知工具”的
     *   JSON 块，周围散文保留为可见文本；纯散文提到工具名不算调用。
     */
    fun parseAssistantResponse(
        text: String,
        knownToolNames: Set<String> = emptySet()
    ): ParsedAssistantResponse {
        if (text.isBlank()) {
            return ParsedAssistantResponse(calls = emptyList(), malformedBlocks = emptyList(), visibleText = "")
        }
        val folded = foldFullWidth(text)
        val calls = mutableListOf<ParsedToolCall>()
        val malformed = mutableListOf<String>()
        val consumed = mutableListOf<IntRange>()
        for (match in CALL_BLOCK_REGEX.findAll(folded)) {
            consumed += match.range
            // 折叠是 1:1 字符映射：组区间在原文与折叠副本上通用，分别取两份内容——
            // 解析时原文优先（字符串值里的弯引号不被折叠破坏），折叠文兜底。
            val contentRange = match.groups[2]?.range ?: match.range
            val foldedContent = match.groups[2]?.value.orEmpty()
            val originalContent = text.substring(contentRange)
            val rawBlock = text.substring(match.range)
            val call = parseCallBlock(foldedContent, originalContent, rawBlock)
            if (call == null) {
                malformed += "工具调用块不是含 name/arguments 的严格 JSON：" + snippet(rawBlock)
            } else {
                calls += call
            }
        }
        var visibleText = text.withoutRanges(consumed).trim()
        if (calls.isEmpty() && malformed.isEmpty()) {
            val recovered = recoverBareJsonCall(visibleText)
            if (recovered != null) {
                calls += recovered
                visibleText = ""
            } else if (knownToolNames.isNotEmpty()) {
                val (proseCalls, proseSpans) = extractProseCalls(text, folded, knownToolNames)
                if (proseCalls.isNotEmpty()) {
                    calls += proseCalls
                    visibleText = text.withoutRanges(consumed + proseSpans).trim()
                }
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
     *
     * 提示词端明确“纯调用块 / 纯自然语言二选一，不要混排”：解析端已能容忍混排与
     * 全角写法，但弱模型混排时更容易把调用块包在散文里，引导比兜底更省钱。
     */
    fun renderProtocolInstructions(maxToolRounds: Int): String = buildString {
        appendLine("## 工具调用协议（必须严格遵守）")
        appendLine("需要读取或修改应用内的书籍、翻译任务、术语、日志等事实时，必须调用工具，不得凭记忆或猜测作答。")
        appendLine("每一轮回复只能是以下两者之一，二者不要混排：")
        appendLine("1. 工具调用轮：只输出调用块，不要写解释文字；块内必须是严格 JSON 对象，")
        appendLine("   只允许字符串键与双引号，禁止注释、尾随逗号、单引号、未加引号的键和多余文字：")
        appendLine("$TOOL_CALL_OPEN{\"name\":\"<工具名>\",\"arguments\":{<符合该工具 JSON Schema 的参数>}}$TOOL_CALL_CLOSE")
        appendLine("2. 最终答复轮：只输出自然语言，不出现任何调用块、JSON 或协议标签。")
        appendLine("多个工具调用放在同一轮时，它们必须是相互独立的；有依赖关系的调用必须分轮进行。")
        appendLine("收到工具结果回执后，基于结果继续：要么再调用工具，要么给出最终答复。")
        appendLine("单个回合最多进行 $maxToolRounds 轮工具调用，达到上限后必须基于已有信息给出最终答复。")
        appendLine("回执中 ok=\"false\" 表示调用失败：阅读 message 修正参数后重试，或向用户说明限制；")
        appendLine("不要用完全相同的参数反复重试超过两次；同一调用本回合已执行过时不要重复。")
        appendLine("会发起付费 API 请求或修改用户数据的工具（开始翻译、重分章节、更新翻译配置等），")
        appendLine("必须先向用户说明计划并得到明确确认，不得擅自执行。")
        appendLine("最终答复使用简洁中文，不要输出思考过程，不要复述工具调用的原始 JSON。")
        appendLine()
        appendLine("### 单轮示例")
        appendLine("用户：书架上有哪些书？")
        appendLine("助手：$TOOL_CALL_OPEN{\"name\":\"list_books\",\"arguments\":{}}$TOOL_CALL_CLOSE")
        appendLine("（系统随后送入 tool_result 回执：共 2 本书）")
        appendLine("助手：书架上有 2 本书。需要我查看某一本的翻译进度吗？")
    }

    /**
     * 把一轮内执行的工具结果渲染成用户消息文本。输出超长时保留头尾并标注截断，
     * 保证模型知道“还有更多内容”而不是静默丢失。末尾附边界说明，避免弱模型把
     * 整段回执误解为用户的新消息。
     */
    fun formatToolResults(records: List<ToolExecutionRecord>): String {
        if (records.isEmpty()) return ""
        return records.joinToString(separator = "\n") { record ->
            renderToolResultBlock(record.name, record.ok, record.output)
        } + "\n" + TOOL_RESULT_BOUNDARY_HINT
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

    /**
     * “疑似想调工具但解析不出来”的纠正回执：复用 [formatProtocolError]，
     * 注明检测到的疑似工具名，给模型一次自我修正机会（编排层仍失败才降级为答复）。
     */
    fun formatSuspectedToolCallError(suspectedToolName: String): String =
        formatProtocolError(
            listOf(
                "检测到疑似工具调用（$suspectedToolName），但回复里没有可执行的调用块。",
                "请只输出一个标准调用块（块内是含 name 与 arguments 的严格 JSON 对象），" +
                    "或直接用自然语言给出最终答复。"
            )
        )

    /** 达到工具轮次上限时的收尾提示，要求模型基于已有信息作答。 */
    fun formatRoundLimitNotice(maxToolRounds: Int): String {
        val payload = JSONObject()
            .put("code", "tool_round_limit_reached")
            .put("message", "本回合已用满 $maxToolRounds 轮工具调用，不能再调用工具。")
            .toString()
        return renderToolResultBlock("protocol", ok = false, payload) +
            "\n请基于已经获得的信息直接给出最终答复；信息不足时说明缺口，不要继续调用工具。"
    }

    /**
     * 调用签名：工具名 + 归一化参数（键排序后重新序列化）。
     * 编排层用它做同一回合内的相同调用熔断：签名第 2 次起不再执行、不再付费。
     * 参数非法时退化为原文 trim，保证签名稳定可比较。
     */
    fun toolCallSignature(name: String, argumentsJson: String): String =
        name + "|" + canonicalArguments(argumentsJson)

    /**
     * 判断一段“零调用”的回复是否其实想调工具，返回疑似工具名；不想调用时返回 null。
     *
     * 命中条件（任一）：
     * 1. 出现协议标签痕迹（开标签没闭合、变体写法、半截标签）；
     * 2. 出现 name + arguments 字段结构（可能被截断或坏了引号）；
     * 3. 出现已知工具名且伴随调用痕迹（`{` 或 arguments 字段）。
     *
     * 纯散文提到工具名（“我可以调用 list_books 帮你查”）不触发：那通常是最终答复，
     * 误发纠正回执只会浪费一次付费请求。
     */
    fun detectSuspectedToolCall(text: String, knownToolNames: Set<String> = emptySet()): String? {
        if (text.isBlank()) return null
        val folded = foldFullWidth(text)
        val knownName = knownToolNames.firstOrNull { folded.contains(it) }
        if (TAG_HINT_REGEX.containsMatchIn(folded)) {
            return knownName ?: extractSuspectedName(folded) ?: CALL_TAG
        }
        if (NAME_FIELD_REGEX.containsMatchIn(folded) && ARGUMENTS_FIELD_REGEX.containsMatchIn(folded)) {
            return knownName ?: extractSuspectedName(folded) ?: CALL_TAG
        }
        if (knownName != null &&
            (folded.contains("{") || ARGUMENTS_FIELD_REGEX.containsMatchIn(folded))
        ) {
            return knownName
        }
        return null
    }

    /**
     * 最终答复清洗：剥掉思考块、残留的 tool_call / tool_result 原始块（含标签变体）
     * 与落单裸标签。区间在归一化副本上定位、从原文摘除，全角写法同样能清掉。
     * 清洗后为空白时由会话层走“空答复”兜底，回合不会无声结束。
     */
    fun cleanFinalText(text: String): String {
        if (text.isBlank()) return ""
        val folded = foldFullWidth(text)
        val removal = mutableListOf<IntRange>()
        THINK_BLOCK_REGEX.findAll(folded).forEach { removal += it.range }
        CALL_BLOCK_REGEX.findAll(folded).forEach { removal += it.range }
        RESULT_BLOCK_REGEX.findAll(folded).forEach { removal += it.range }
        STRAY_TAG_REGEX.findAll(folded).forEach { removal += it.range }
        return EXCESS_NEWLINES.replace(text.withoutRanges(removal).trim(), "\n\n")
    }

    // ---------- 内部实现 ----------

    /**
     * 全角 → ASCII 归一化。严格 1:1 字符映射（长度不变），因此折叠副本与原文本
     * 索引对齐：可以在副本上匹配标签、解析 JSON，再用同一索引回切原文。
     *
     * 覆盖 FF01–FF5E 全角 ASCII（含全角字母/数字/标点，如 ＜＞“”＋：，［］｛｝）、
     * 表意空格与弯引号；中文表意文字不在折叠范围内，正文不受影响。
     */
    private fun foldFullWidth(text: String): String {
        if (text.isEmpty()) return text
        val builder = StringBuilder(text.length)
        for (char in text) {
            builder.append(
                when (char) {
                    in '\uFF01'..'\uFF5E' -> char - 0xFEE0
                    '\u3000' -> ' '
                    '\u201C', '\u201D' -> '"'
                    '\u2018', '\u2019' -> '\''
                    else -> char
                }
            )
        }
        return builder.toString()
    }

    private fun parseCallBlock(foldedBlock: String, originalBlock: String, rawBlock: String): ParsedToolCall? {
        val json = stripCodeFences(foldedBlock)
        if (json.isEmpty()) return null
        // 原文优先：字符串值里含弯引号等字符时严格解析即可通过，不被折叠破坏。
        val root = parseCallObject(json, stripCodeFences(originalBlock)) ?: return null
        val name = root.optString("name").trim()
        if (name.isEmpty() || !TOOL_NAME_PATTERN.matches(name)) return null
        val argumentsJson = argumentsToJson(root.opt("arguments")) ?: return null
        return ParsedToolCall(name = name, argumentsJson = argumentsJson, rawBlock = rawBlock)
    }

    /**
     * 严格优先、失败后走宽容修复梯的 JSON 对象解析。
     * 修复梯依次为：全角归一化 → 去尾逗号 → 单引号键值 → 无引号键 → 去注释；
     * 整梯最多跑两轮——去注释可能暴露出新的尾逗号，一轮修不干净。
     */
    private fun parseCallObject(foldedJson: String, originalJson: String): JSONObject? {
        strictObject(originalJson)?.let { return it }
        strictObject(foldedJson)?.let { return it }
        var candidate = foldedJson
        repeat(2) {
            candidate = TRAILING_COMMA_REGEX.replace(candidate, "$1")
            strictObject(candidate)?.let { return it }
            candidate = SINGLE_QUOTED_REGEX.replace(candidate) { match ->
                "\"" + match.groupValues[1] + "\""
            }
            strictObject(candidate)?.let { return it }
            candidate = UNQUOTED_KEY_REGEX.replace(candidate, "$1\"$2\"$3")
            strictObject(candidate)?.let { return it }
            candidate = LINE_COMMENT_REGEX.replace(BLOCK_COMMENT_REGEX.replace(candidate, ""), "")
            strictObject(candidate)?.let { return it }
        }
        return null
    }

    private fun strictObject(json: String): JSONObject? =
        if (json.isBlank()) {
            null
        } else {
            try {
                JSONObject(json)
            } catch (_: Exception) {
                null
            }
        }

    private fun recoverBareJsonCall(text: String): ParsedToolCall? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val root = parseCallObject(trimmed, foldFullWidth(trimmed)) ?: return null
        val name = root.optString("name").trim()
        if (name.isEmpty() || !TOOL_NAME_PATTERN.matches(name)) return null
        val argumentsJson = argumentsToJson(root.opt("arguments")) ?: return null
        return ParsedToolCall(name = name, argumentsJson = argumentsJson)
    }

    /**
     * 从散文里提取“最外层 {...} 且 name 是已知工具”的 JSON 块。
     * 返回 (调用列表, 需从可见文本摘除的区间)；区间索引在原文与折叠副本上通用。
     */
    private fun extractProseCalls(
        original: String,
        folded: String,
        knownToolNames: Set<String>
    ): Pair<List<ParsedToolCall>, List<IntRange>> {
        val calls = mutableListOf<ParsedToolCall>()
        val consumed = mutableListOf<IntRange>()
        for (span in outermostBraceSpans(folded)) {
            val foldedCandidate = folded.substring(span.first, span.last + 1)
            if (knownToolNames.none { foldedCandidate.contains(it) }) continue
            val originalCandidate = original.substring(span.first, span.last + 1)
            val call = parseCallBlock(foldedCandidate, originalCandidate, rawBlock = "") ?: continue
            if (call.name in knownToolNames) {
                calls += call
                consumed += span
            }
        }
        return calls to consumed
    }

    /** 扫描文本里全部最外层 {...} 区间（感知 JSON 字符串与转义），供散文提取使用。 */
    private fun outermostBraceSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            if (escaped) {
                escaped = false
                continue
            }
            when {
                inString && text[index] == '\\' -> escaped = true
                text[index] == '"' -> inString = !inString
                inString -> Unit
                text[index] == '{' -> if (depth == 0) {
                    start = index
                    depth = 1
                } else {
                    depth++
                }
                text[index] == '}' -> when (depth) {
                    1 -> {
                        spans += IntRange(start, index)
                        depth = 0
                    }
                    else -> if (depth > 1) depth--
                }
            }
        }
        return spans
    }

    /** 从原文中摘除若干区间（可重叠），其余部分按原样拼接。 */
    private fun String.withoutRanges(ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return this
        val builder = StringBuilder()
        var cursor = 0
        ranges.sortedBy { it.first }.forEach { range ->
            if (range.first > cursor) builder.append(this, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < length) builder.append(this, cursor, length)
        return builder.toString()
    }

    /** 从疑似调用文本里抽出工具名：先找带引号的 name 值，再找裸写法。 */
    private fun extractSuspectedName(folded: String): String? {
        for (pattern in listOf(QUOTED_NAME_VALUE, BARE_NAME_VALUE)) {
            val match = pattern.find(folded) ?: continue
            val name = match.groupValues[1].trim()
            if (TOOL_NAME_PATTERN.matches(name)) return name
        }
        return null
    }

    private fun canonicalArguments(argumentsJson: String): String = try {
        canonicalizeValue(JSONObject(argumentsJson)).toString()
    } catch (_: Exception) {
        argumentsJson.trim()
    }

    /** 递归重建 JSON 值，对象键排序，使参数顺序/空白差异不影响签名。 */
    private fun canonicalizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            val sorted = JSONObject()
            keys.sorted().forEach { key -> sorted.put(key, canonicalizeValue(value.get(key))) }
            sorted
        }
        is JSONArray -> {
            val sorted = JSONArray()
            for (index in 0 until value.length()) sorted.put(canonicalizeValue(value.get(index)))
            sorted
        }
        else -> value
    }

    /** arguments 缺省按空对象处理；存在但非对象视为格式错误。 */
    private fun argumentsToJson(arguments: Any?): String? = when (arguments) {
        null -> "{}"
        is JSONObject -> arguments.toString()
        else -> null
    }

    private fun stripCodeFences(text: String): String =
        CODE_FENCE_PREFIX.replace(text.trim(), "")
            .let { CODE_FENCE_SUFFIX.replace(it, "") }
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
