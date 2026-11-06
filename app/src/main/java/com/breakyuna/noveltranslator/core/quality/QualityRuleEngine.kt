package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.LiteralMatcher
import com.breakyuna.noveltranslator.core.text.LiteralPattern
import com.breakyuna.noveltranslator.core.text.TextRange
import com.breakyuna.noveltranslator.core.text.compileLiteralPatterns

/**
 * 平台安全的规则编译与执行。
 *
 * ## 为什么不用 `core/text` 的 `TextReplacements` / `TextProtectionRule`
 *
 * 这两个类会给每条模式前置 `(?U)`（UNICODE_CHARACTER_CLASS）。桌面 JVM 支持它，
 * 但 **Android 14（API 34）起 `java.util.regex` 改由 ICU4C 实现**，而 ICU 的
 * 内联标志只接受 `i d m s u w x -`（见 ICU `regexcst.txt` 的 `open-paren-extended`
 * 状态表，`U` 落入 `doBadOpenParenType` → `U_REGEX_RULE_SYNTAX`）。也就是说
 * `Regex("(?U)...")` 在新版 Android 上会抛 `PatternSyntaxException`。
 *
 * `core/text` 的那两个类目前还没有任何生产调用点，所以这个地雷还没被踩到；
 * 但质量规则是每次翻译、每一段都会执行的路径，**不能把平台的未验证行为放进热路径**。
 * 因此这里：
 * - 字面量规则完整委托 `core/text` 的 [LiteralMatcher]（纯 Kotlin 实现，
 *   NFKC + casefold 语义与 `TextReplacements` 一致，且不涉及正则）；
 * - 正则规则在本类内编译，**不注入 `(?U)`**，替换串展开语义照搬
 *   `core/text` 的 `expandReplacement`（JS `$1` / `${name}` 行为）；
 * - 保护掩蔽同样改为"按定义序合并成一条 alternation"后本地编译
 *   （见 `TranslationTextProtection`），语义与逐规则裁决等价。
 *
 * 代价：用户正则里的 `\d` / `\w` / `\b` 走 ASCII 语义（与旧硬编码保护体系一致，
 * 因为那条 legacy alternation 本来就没有 `(?U)`），`\s` 不含全角空格。
 * 这是有意为之：掩蔽本来就会跳过纯空白段，而默认行为必须与升级前逐字节一致。
 */

/**
 * 逐条编译正则，**不注入 `(?U)`**。
 *
 * 校验、解析、执行三处共用同一个编译函数，保证"保存时能编译"与"运行时能编译"
 * 永远是同一件事。
 */
internal fun compileRulePattern(pattern: String, caseSensitive: Boolean = false): Regex? =
    runCatching {
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }.getOrNull()

/** 把若干模式按定义序合并成一条 alternation；单条非法时整组返回 null。 */
internal fun compileAlternation(patterns: List<String>, caseSensitive: Boolean = false): Regex? {
    if (patterns.isEmpty()) return null
    // 每条模式包一层 (?:...)：隔离用户模式里的顶层 alternation 与量化作用域歧义；
    // 前缀内联标志另行改写为标志作用域组（见 isolateLeadingInlineFlags），
    // 否则 Java/ICU 正则里 (?i) 会从其位置作用到所在组剩余部分，泄漏到后续分支。
    val source = patterns.joinToString("|") { "(?:${isolateLeadingInlineFlags(it)})" }
    return runCatching {
        Regex(source, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }.getOrNull()
}

/** 匹配“前缀内联标志”形式：`(?flags)rest`；不含 `<` `:` `=` `!` 所以不会误伤命名组与环视。 */
private val LEADING_INLINE_FLAGS = Regex("""^\(\?([a-zA-Z]+)\)(.*)$""", RegexOption.DOT_MATCHES_ALL)

/**
 * 把模式前缀的内联标志改写为标志作用域组：`(?i)foo` → `(?i:foo)`。
 *
 * Java/ICU 正则中 `(?i)` 作用于**所在组的剩余部分**，直接拼接 alternation 会让
 * 标志泄漏到后续分支（测试 `compileAlternationIsolatesInlineFlags` 守着这一点）。
 * 非前缀位置的标志不重写（保持原样，调用方应避免依赖该模式）。
 */
private fun isolateLeadingInlineFlags(pattern: String): String {
    val match = LEADING_INLINE_FLAGS.matchEntire(pattern) ?: return pattern
    val flags = match.groupValues[1]
    val rest = match.groupValues[2]
    if (rest.isEmpty()) return pattern
    return "(?$flags:$rest)"
}

/**
 * 编译后的替换规则集，按列表顺序执行。
 *
 * 与 `core/text` 的 `TextReplacements` 的差异只在正则编译 flag（见类 KDoc）；
 * 执行语义一致：字面量走 [LiteralMatcher]，正则允许 `$1` / `${name}`。
 */
class QualityReplacements(entries: List<ReplacementRuleEntry>) {

    private sealed interface Rule {
        fun apply(text: String): String
    }

    /** 正则规则：`(?U)` 由调用方决定，这里按 ASCII 字符类语义编译。 */
    private class RegexRule(val pattern: Regex, val template: String) : Rule {
        // 命名分组预解析为组序号：MatchGroupCollection.get(String) 需要 API 26（minSdk 24）。
        private val namedGroups: Map<String, Int> = namedGroupIndexes(pattern.pattern)

        override fun apply(text: String): String =
            pattern.replace(text) { match -> expandReplacement(template, match, namedGroups) }
    }

    /** 字面量规则：复用 core/text 的 Aho–Corasick + NFKC + casefold 匹配器。 */
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

    private val rules: List<Rule> = entries.mapIndexedNotNull { index, entry ->
        if (entry.pattern.isEmpty()) {
            null
        } else if (entry.isRegex) {
            compileRulePattern(entry.pattern, entry.caseSensitive)
                ?.let { RegexRule(it, entry.replacement) }
        } else {
            LiteralRule(
                matcher = compileLiteralPatterns(
                    listOf(
                        LiteralPattern(
                            key = index.toString(),
                            text = entry.pattern,
                            caseSensitive = entry.caseSensitive
                        )
                    )
                ),
                replacement = entry.replacement
            )
        }
    }

    /** 实际可执行的规则数（编译失败的条目已被剔除）。 */
    val ruleCount: Int get() = rules.size

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
 * 与 `core/text` 的私有 `expandReplacement` 行为一致（反斜杠转义、缺失分组归空串），
 * 这样同一条规则在两个实现下的替换结果相同。
 *
 * 命名分组一律经 [namedGroupIndexes] 预解析成组序号后按序号取值：
 * `MatchGroupCollection.get(String)` 底层调用 `Matcher.start(String)`，需要 API 26，
 * 而本应用 minSdk 24，因此禁止在代码里出现按名查询（lint NewApi 会直接报错）。
 */
internal fun expandReplacement(
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
 * 与 [countCapturingGroups] 共用同一套分组判定，保证序号一致；
 * `(?P=name)` 回引按捕获计数但不进命名表（极罕见，用户规则应避免）。
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
