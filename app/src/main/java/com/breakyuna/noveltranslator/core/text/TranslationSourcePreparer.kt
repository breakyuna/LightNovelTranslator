package com.breakyuna.noveltranslator.core.text

import java.util.Locale

/** 单行准备结果。[preparedText] 是实际请求和校对共用的完整源文。 */
sealed interface PreparedSourceLine {
    val preparedText: String
    val preserveAnalysis: TextPreserveAnalysis
}

/**
 * 完全保护的行：仍提供非空保护样例，但不建立译后恢复状态。
 *
 * 译后直接把 [preparedText]（即原始行）写回，因此纯保护行不需要译文。
 */
data class PreservedSourceLine(
    override val preparedText: String,
    override val preserveAnalysis: TextPreserveAnalysis
) : PreparedSourceLine

/**
 * 可翻译行。
 *
 * [restorationText] 是译前替换之前的正文，用于恢复源文形式；
 * [leadingWhitespace] / [trailingWhitespace] 在译后重新包回译文两侧。
 */
data class TranslatableSourceLine(
    override val preparedText: String,
    override val preserveAnalysis: TextPreserveAnalysis,
    val restorationText: String,
    val leadingWhitespace: String,
    val trailingWhitespace: String
) : PreparedSourceLine

/**
 * 一次完整的源文准备结果。
 *
 * 移植自 LinguaGacha `translation-source.ts` 的 PreparedTranslationSource：
 * [body] 是原始正文的资源映射，覆盖每行准备及译后恢复；[name] 按整段投影，
 * 不执行正文清理与替换；[samples] 是汇总去重后的请求样例。
 */
data class PreparedTranslationSource(
    val sourceText: String,
    val body: ResourceReferenceProjection,
    val name: ResourceReferenceProjection?,
    val preparedLines: List<PreparedSourceLine>,
    val samples: List<String>
)

/**
 * 翻译与校对共用完整源文准备；资源在清理、分行和替换前隔离。
 *
 * 固定顺序（与来源实现一致）：资源投影 → ruby 清理 → 保护段分析 → 译前替换。
 * 资源必须先隔离，否则 ruby / 替换规则会把 URL 或 data URI 改坏。
 */
fun prepareTranslationSource(
    src: String,
    nameSrc: String? = null,
    textType: String = DEFAULT_TEXT_TYPE,
    cleanRuby: Boolean = false,
    preserveRule: TextProtectionRule,
    preReplacements: TextReplacements? = null,
    startOrdinal: Int = 0
): PreparedTranslationSource {
    val name = if (nameSrc == null) {
        null
    } else {
        ResourceReferenceSlots.project(nameSrc, startOrdinal)
    }
    val body = ResourceReferenceSlots.project(
        normalizeLineBreaks(src),
        name?.nextOrdinal ?: startOrdinal
    )
    val preparedLines = splitTextLines(body.text).map { rawText ->
        prepareTranslationSourceLine(
            rawText = rawText,
            textType = textType,
            cleanRuby = cleanRuby,
            preserveRule = preserveRule,
            preReplacements = preReplacements,
            referenceMappings = body.mappings
        )
    }

    // 按源行顺序提供保护样例，Markdown 正文需要附带格式说明。
    val samples = LinkedHashSet<String>()
    if (name != null) samples += preserveRule.collect(name.text)
    for (line in preparedLines) {
        samples += line.preserveAnalysis.segments
        if (textType.trim().uppercase(Locale.ROOT) == "MD" && line is TranslatableSourceLine) {
            samples += "Markdown Code"
        }
    }
    return PreparedTranslationSource(
        sourceText = src,
        body = body,
        name = name,
        preparedLines = preparedLines,
        samples = samples.filter { it.isNotBlank() }
    )
}

/** 按翻译入口的既定顺序准备单行，并同时生成校对使用的源文投影。 */
private fun prepareTranslationSourceLine(
    rawText: String,
    textType: String,
    cleanRuby: Boolean,
    preserveRule: TextProtectionRule,
    preReplacements: TextReplacements?,
    referenceMappings: List<ResourceReferenceMapping>
): PreparedSourceLine {
    // 完全保护的行仍提供非空保护样例，但不会建立译后恢复状态。
    // 注意保护分析绑定的是原始行，而不是 ruby 清理后的行。
    val preserved: (TextPreserveAnalysis) -> PreparedSourceLine = { analysis ->
        PreservedSourceLine(preparedText = rawText, preserveAnalysis = analysis)
    }

    val normalizedTextType = textType.trim().uppercase(Locale.ROOT)
    var text = if (cleanRuby) {
        ResourceReferenceSlots.transformProjected(rawText, referenceMappings) { value ->
            RubyCleaner.clean(value, normalizedTextType)
        }
    } else {
        rawText
    }
    if (text.isBlank()) return preserved(preserveRule.analyze(rawText))

    val leadingWhitespace = text.takeWhile { it.isWhitespace() }
    val trailingWhitespace = text.takeLastWhile { it.isWhitespace() }
    text = text.substring(leadingWhitespace.length, text.length - trailingWhitespace.length)

    val trimmedAnalysis = preserveRule.analyze(text)
    if (trimmedAnalysis.unpreservedText.isBlank()) return preserved(trimmedAnalysis)

    val replacedText = if (preReplacements == null) {
        text
    } else {
        ResourceReferenceSlots.transformProjected(text, referenceMappings) { value ->
            preReplacements.apply(value)
        }
    }
    val preparedText = leadingWhitespace + replacedText + trailingWhitespace
    val preserveAnalysis = if (preparedText == trimmedAnalysis.text) {
        trimmedAnalysis
    } else {
        preserveRule.analyze(preparedText)
    }
    return TranslatableSourceLine(
        preparedText = preparedText,
        preserveAnalysis = preserveAnalysis,
        restorationText = text,
        leadingWhitespace = leadingWhitespace,
        trailingWhitespace = trailingWhitespace
    )
}

/** 默认文本类型：只启用基础保护规则，不套用任何引擎预设。 */
const val DEFAULT_TEXT_TYPE: String = "NONE"

/** 归一化所有受支持的行尾为 LF。 */
internal fun normalizeLineBreaks(text: String): String =
    text.replace("\r\n", "\n").replace("\r", "\n")

/** 按 LF 切分并保留空行与末尾空行。 */
internal fun splitTextLines(text: String): List<String> = normalizeLineBreaks(text).split("\n")

/**
 * 常见注音标记清理器。
 *
 * 移植自 LinguaGacha `text-ruby-cleaner.ts`：先应用保守规则，
 * 非脚本格式再应用括号类激进规则（WOLF / RPGMAKER / RENPY 的括号和竖线
 * 很可能是控制语法，不能套用）。
 */
internal object RubyCleaner {

    private data class Rule(val pattern: Regex, val replacement: (MatchResult) -> String)

    // 保守模式规则：移除所有常见 ruby 标记，尽量只留下正文。
    private val CONSERVATIVE_RULES = listOf(
        Rule(unicode("""\r\[(.+?),.+?\]""")) { it.groupValues[1] }, // \r[漢字,かんじ]
        Rule(unicode("""\rb\[(.+?),.+?\]""")) { it.groupValues[1] }, // \rb[漢字,かんじ]
        Rule(unicode("""\[r_.+?\]\[ch_(.+?)\]""")) { it.groupValues[1] }, // [r_かんじ][ch_漢字]
        Rule(unicode("""\[ch_(.+?)\]""")) { it.groupValues[1] }, // [ch_漢字]
        Rule(unicode("""<ruby\s*=\s*.*?>(.*?)</ruby>""")) { it.groupValues[1] }, // <ruby = かんじ>漢字</ruby>
        Rule(unicode("""<ruby>.*?<rb>(.*?)</rb>.*?</ruby>""")) { it.groupValues[1] },
        Rule(unicode("""\[ruby text\s*=\s*.*?\]""")) { "" } // [ruby text=かんじ]
    )

    // 激进模式额外规则：移除括号、方括号和竖线格式的 ruby 标记。
    private val AGGRESSIVE_RULES = listOf(
        Rule(unicode("""\((.+)/.+\)""")) { it.groupValues[1] }, // (漢字/かんじ)
        Rule(unicode("""\[(.+)/.+\]""")) { it.groupValues[1] }, // [漢字/かんじ]
        Rule(unicode("""\|(.+?)\[.+?\]""")) { it.groupValues[1] } // |漢字[かんじ]
    )

    private val AGGRESSIVE_EXCLUDED_TYPES = setOf("WOLF", "RPGMAKER", "RENPY")

    fun clean(text: String, textType: String): String {
        var result = text
        for (rule in CONSERVATIVE_RULES) {
            result = rule.pattern.replace(result, rule.replacement)
        }
        if (textType !in AGGRESSIVE_EXCLUDED_TYPES) {
            for (rule in AGGRESSIVE_RULES) {
                result = rule.pattern.replace(result, rule.replacement)
            }
        }
        return result
    }

    private fun unicode(pattern: String): Regex =
        Regex("(?U)$pattern", RegexOption.IGNORE_CASE)
}

/**
 * 一条译前/译后替换规则。
 *
 * 移植自 LinguaGacha `text-replacement-rules.ts` 的条目语义：
 * 字面量按原文替换，正则允许 `$1` / `${name}` 捕获组语法；
 * 所有规则按列表顺序执行。
 *
 * 与来源实现的偏差：空 `src` 的条目直接跳过而不是抛错。规则来自用户配置，
 * 一条空白规则不应让整本书的翻译失败；配置层应负责提示。
 */
data class TextReplacementEntry(
    val src: String,
    val dst: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false
)

/** 编译后的替换规则集，可复用。 */
class TextReplacements(entries: List<TextReplacementEntry>) {

    private sealed interface Rule {
        fun apply(text: String): String
    }

    /** 正则规则：`(?U)` 让 `\s`/`\d` 对齐 JS 的 `u` flag。 */
    private class RegexRule(val pattern: Regex, val template: String) : Rule {
        // 命名分组预解析为组序号：MatchGroupCollection.get(String) 需要 API 26（minSdk 24）。
        private val namedGroups: Map<String, Int> = namedGroupIndexes(pattern.pattern)

        override fun apply(text: String): String =
            pattern.replace(text) { match -> expandReplacement(template, match, namedGroups) }
    }

    /** 字面量规则：走字面匹配器，复用 NFKC + casefold 语义。 */
    private class LiteralRule(val matcher: LiteralMatcher, val replacement: String) : Rule {
        override fun apply(text: String): String {
            val ranges = matcher.match(text)
                .flatMap { it.ranges }
                .sortedWith(compareBy({ it.start }, { it.end }))
            val selected = ArrayList<TextRange>()
            for (range in ranges) {
                val previous = selected.lastOrNull()
                if (previous != null && range.start < previous.end) continue
                selected += range
            }
            if (selected.isEmpty()) return text
            val result = StringBuilder(text.length)
            var offset = 0
            for (range in selected) {
                result.append(text, offset, range.start)
                result.append(replacement)
                offset = range.end
            }
            result.append(text, offset, text.length)
            return result.toString()
        }
    }

    // 字面量规则的 key 用输入序号，保证同一批规则内身份唯一。
    private val rules: List<Rule> = entries.mapIndexedNotNull { index, entry ->
        if (entry.src.isEmpty()) {
            null
        } else if (entry.regex) {
            RegexRule(
                pattern = Regex(
                    "(?U)${entry.src}",
                    if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                ),
                template = entry.dst
            )
        } else {
            LiteralRule(
                matcher = compileLiteralPatterns(
                    listOf(
                        LiteralPattern(
                            key = index.toString(),
                            text = entry.src,
                            caseSensitive = entry.caseSensitive
                        )
                    )
                ),
                replacement = entry.dst
            )
        }
    }

    /** 按顺序应用全部规则。 */
    fun apply(text: String): String {
        var result = text
        for (rule in rules) result = rule.apply(result)
        return result
    }
}

/**
 * 展开 JS 替换串语义的 `$1` / `${name}`。
 *
 * 反斜杠转义与缺失分组归空串都按 JS `String.prototype.replace` 的行为处理，
 * 避免规则里的普通 `$` 被 Kotlin 的替换串解析器抛异常。
 *
 * 命名分组一律按序号取值（[namedGroupIndexes] 预解析）：
 * `MatchGroupCollection.get(String)` 底层调用 `Matcher.start(String)`，需要 API 26，
 * 而本应用 minSdk 24，禁止在代码里出现按名查询（lint NewApi 会直接报错）。
 */
private fun expandReplacement(
    template: String,
    match: MatchResult,
    namedGroups: Map<String, Int> = emptyMap()
): String {
    val sb = StringBuilder(template.length)
    var index = 0
    while (index < template.length) {
        val ch = template[index]
        if (ch == '\\' && index + 1 < template.length) {
            sb.append(template[index + 1])
            index += 2
            continue
        }
        if (ch == '$' && index + 1 < template.length) {
            val next = template[index + 1]
            if (next in '1'..'9') {
                sb.append(match.groupValues.getOrNull(next - '0') ?: "")
                index += 2
                continue
            }
            if (next == '{') {
                val close = template.indexOf('}', index + 2)
                if (close > 0) {
                    val name = template.substring(index + 2, close)
                    val groupIndex = namedGroups[name]
                    if (groupIndex != null) {
                        sb.append(match.groups[groupIndex]?.value ?: "")
                    }
                    index = close + 1
                    continue
                }
            }
        }
        sb.append(ch)
        index += 1
    }
    return sb.toString()
}

/** 命名组引用形式：`(?<name>` / `(?P<name>`（不含 `(?<=` / `(?<!` 环视）。 */
private val NAMED_GROUP_REFERENCE =
    Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>|\(\?P<([A-Za-z][A-Za-z0-9]*)>""")

/**
 * 扫描正则源，把命名分组映射到组序号（左到右按捕获括号计数）。
 *
 * 与 core/quality 的 [com.breakyuna.noveltranslator.core.quality.namedGroupIndexes]
 * 语义一致；分组判定规则：`(?:` `(?=` `(?!` `(?<=` `(?<!` 与 `(?i)` 等标志组非捕获，
 * 其余 `(` 与 `(?<name>` / `(?P<name>` 为捕获。
 */
internal fun namedGroupIndexes(pattern: String): Map<String, Int> {
    val names = LinkedHashMap<String, Int>()
    var groupIndex = 0
    var inCharClass = false
    var index = 0
    while (index < pattern.length) {
        when (val ch = pattern[index]) {
            '\\' -> index++ // 跳过被转义的下一个字符
            '[' -> if (!inCharClass) inCharClass = true
            ']' -> if (inCharClass) inCharClass = false
            '(' -> if (!inCharClass) {
                val next = pattern.getOrNull(index + 1)
                val capturing = when {
                    next == null -> true
                    next != '?' -> true
                    else -> when (val after = pattern.getOrNull(index + 2)) {
                        ':', '=', '!' -> false
                        '<' -> pattern.getOrNull(index + 3)?.let { it == '=' || it == '!' } != true
                        'P' -> true
                        else -> false // (?i) 等标志组不是组
                    }
                }
                if (capturing) {
                    groupIndex++
                    val reference = NAMED_GROUP_REFERENCE.matchAt(pattern, index)
                    if (reference != null) {
                        val name = reference.groupValues[1].ifEmpty { reference.groupValues[2] }
                        names[name] = groupIndex
                    }
                }
            }
        }
        index++
    }
    return names
}
