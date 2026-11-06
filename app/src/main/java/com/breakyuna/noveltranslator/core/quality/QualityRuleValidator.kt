package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextProtectionRule

/** 校验问题严重级。ERROR 阻止保存，WARNING 只提示。 */
enum class QualityRuleIssueSeverity { ERROR, WARNING }

/** 一条校验结果。[index] 是条目在编辑列表中的下标，便于 UI 定位。 */
data class QualityRuleIssue(
    val index: Int,
    val severity: QualityRuleIssueSeverity,
    val message: String
)

/**
 * 用户质量规则校验。
 *
 * 原则与 `core/text` 的替换语义对齐：**编译期失败要提前到保存时**，
 * 运行期不再抛异常。校验只回答"哪里有问题"，不决定是否阻断以外的策略。
 */
object QualityRuleValidator {

    /** 单个规则集的最大条目数。 */
    const val MAX_ENTRIES_PER_SET = 200

    /** 模式长度上限，防止病态正则把编译时间拖垮。 */
    const val MAX_PATTERN_LENGTH = 500

    /** 替换文本长度上限。 */
    const val MAX_REPLACEMENT_LENGTH = 2_000

    /** 备注长度上限（超出只警告，保存时截断）。 */
    const val MAX_NOTE_LENGTH = 200

    /**
     * 会匹配任意文本的模式。命中时给警告而不是拒绝：
     * 某些用户确实想"清空某类内容"，但要让他们明确知道后果。
     */
    private val DANGEROUS_PATTERNS = setOf(
        ".*", ".+", "[\\s\\S]*", "[\\s\\S]+", "(?s).*", "(?s).+",
        "^.*$", "^.+$", "[^\\n\\r]*"
    )

    /** 从模式里提取已定义的命名捕获组名（`(?<name>` / `(?P<name>`）。 */
    private val NAMED_GROUP_PATTERN =
        Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>|\(\?P<([A-Za-z][A-Za-z0-9]*)>""")

    /** 校验整个规则集（含槽位开关与模式合法性）。 */
    fun validateRuleSet(
        type: QualityRuleType,
        enabled: Boolean,
        mode: QualityRuleMode,
        entries: List<QualityRuleEntry>
    ): List<QualityRuleIssue> {
        val issues = mutableListOf<QualityRuleIssue>()
        if (mode !in QualityRuleMode.allowedModes(type)) {
            issues += QualityRuleIssue(
                index = -1,
                severity = QualityRuleIssueSeverity.ERROR,
                message = "模式 ${mode.name} 不适用于${type.label()}，可用值：${QualityRuleMode.allowedModes(type).joinToString(" / ") { it.name }}"
            )
        }
        if (entries.size > MAX_ENTRIES_PER_SET) {
            issues += QualityRuleIssue(
                index = -1,
                severity = QualityRuleIssueSeverity.ERROR,
                message = "条目数 ${entries.size} 超过上限 $MAX_ENTRIES_PER_SET"
            )
        }
        issues += entries.mapIndexed { index, entry -> validateEntry(type, index, entry) }.flatten()
        if (enabled && entries.isEmpty()) {
            val effect = when (type) {
                QualityRuleType.TEXT_PRESERVE -> "只会应用内置基础规则"
                QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT -> "不会修改任何文本"
            }
            issues += warning(-1, "槽位已启用但没有条目，$effect")
        }
        if (type == QualityRuleType.TEXT_PRESERVE &&
            mode == QualityRuleMode.CUSTOM &&
            entries.filterIsInstance<TextPreserveRuleEntry>().all { it.src.isBlank() }
        ) {
            issues += warning(-1, "自定义模式下没有用户规则，效果与 SMART 相同")
        }
        return issues
    }

    /** 校验单条条目。 */
    fun validateEntry(
        type: QualityRuleType,
        index: Int,
        entry: QualityRuleEntry
    ): List<QualityRuleIssue> {
        val issues = mutableListOf<QualityRuleIssue>()
        when (entry) {
            is TextPreserveRuleEntry -> {
                val pattern = entry.src.trim()
                if (pattern.isEmpty()) {
                    issues += error(index, "保护规则模式不能为空")
                    return issues
                }
                if (pattern.length > MAX_PATTERN_LENGTH) {
                    issues += error(index, "保护规则模式长度 ${pattern.length} 超过上限 $MAX_PATTERN_LENGTH")
                }
                val regex = compileRegex(pattern)
                if (regex == null) {
                    issues += error(index, "保护规则正则无法编译，请检查语法")
                } else {
                    issues += commonRegexWarnings(index, pattern, regex, isPreserve = true)
                }
                issues += noteWarnings(index, entry.note)
            }

            is ReplacementRuleEntry -> {
                val pattern = entry.pattern.trim()
                if (pattern.isEmpty()) {
                    issues += error(index, "替换规则模式不能为空")
                    return issues
                }
                if (pattern.length > MAX_PATTERN_LENGTH) {
                    issues += error(index, "替换规则模式长度 ${pattern.length} 超过上限 $MAX_PATTERN_LENGTH")
                }
                if (entry.replacement.length > MAX_REPLACEMENT_LENGTH) {
                    issues += error(index, "替换文本长度 ${entry.replacement.length} 超过上限 $MAX_REPLACEMENT_LENGTH")
                }
                val regex = if (entry.isRegex) compileRegex(pattern, caseSensitive = entry.caseSensitive) else null
                if (entry.isRegex && regex == null) {
                    issues += error(index, "替换规则正则无法编译，请检查语法")
                } else if (regex != null) {
                    issues += commonRegexWarnings(index, pattern, regex, isPreserve = false)
                    issues += replacementGroupWarnings(index, entry.replacement, pattern, regex)
                } else {
                    // 字面量条目：只提示可能想用正则的明显元字符，不阻断。
                    val meta = pattern.filter { it in ".*+?^\${}()|[]\\" }
                    if (meta.isNotEmpty()) {
                        issues += warning(
                            index,
                            "字面量规则包含正则元字符（${meta.take(8)}），这些字符按原样匹配；如需正则请开启正则模式"
                        )
                    }
                }
                issues += noteWarnings(index, entry.note)
            }
        }
        return issues
    }

    /** 只返回 ERROR 级问题，供保存闸门使用。 */
    fun blockingIssues(issues: List<QualityRuleIssue>): List<QualityRuleIssue> =
        issues.filter { it.severity == QualityRuleIssueSeverity.ERROR }

    /**
     * 统计正则里的捕获组数量（纯 Kotlin 扫描，不依赖 java.util.regex.Pattern）。
     *
     * 规则：`(?:` `(?=` `(?!` `(?<=` `(?<!` 为非捕获；`(?<name>` / `(?P<name>` 与普通 `(` 为捕获。
     * 跳过字符类内的括号与反斜杠转义。
     */
    internal fun countCapturingGroups(pattern: String): Int {
        var count = 0
        var inCharClass = false
        var index = 0
        while (index < pattern.length) {
            when (val ch = pattern[index]) {
                '\\' -> index++ // 跳过被转义的下一个字符
                '[' -> if (!inCharClass) inCharClass = true
                ']' -> if (inCharClass) inCharClass = false
                '(' -> if (!inCharClass) {
                    val next = pattern.getOrNull(index + 1)
                    if (next == null) {
                        count++
                    } else if (next != '?') {
                        count++
                    } else {
                        when (val after = pattern.getOrNull(index + 2)) {
                            ':', '=', '!' -> Unit // (?: (?= (?! 非捕获
                            '<' -> {
                                // (?<= (?<! 非捕获；(?<name> 捕获
                                val third = pattern.getOrNull(index + 3)
                                if (third != '=' && third != '!') count++
                            }
                            'P' -> count++ // (?P<name> 捕获
                            else -> Unit
                        }
                    }
                }
            }
            index++
        }
        return count
    }

    private fun commonRegexWarnings(
        index: Int,
        pattern: String,
        regex: Regex,
        isPreserve: Boolean
    ): List<QualityRuleIssue> {
        val issues = mutableListOf<QualityRuleIssue>()
        val normalized = pattern.trim()
        if (normalized in DANGEROUS_PATTERNS || regex.matches("")) {
            val effect = if (isPreserve) "把整段正文当作保护段" else "匹配（并可能替换）任意文本"
            issues += warning(index, "该正则会$effect，请确认这是预期行为")
        }
        if (isPreserve && (regex.matches(" ") || regex.matches("\n")) &&
            normalized !in DANGEROUS_PATTERNS
        ) {
            issues += warning(
                index,
                "该规则会命中空白：空白保护段不生成掩蔽标记（与既有引擎语义一致），只参与保护分析"
            )
        }
        return issues
    }

    /** 替换串里的 `$1` / `${name}` 必须对应真实捕获组。 */
    private fun replacementGroupWarnings(
        index: Int,
        replacement: String,
        pattern: String,
        regex: Regex
    ): List<QualityRuleIssue> {
        val issues = mutableListOf<QualityRuleIssue>()
        val groupCount = countCapturingGroups(pattern)
        val definedNames = NAMED_GROUP_PATTERN.findAll(pattern)
            .mapNotNull { it.groups[1]?.value ?: it.groups[2]?.value }
            .toSet()
        var cursor = 0
        while (cursor < replacement.length) {
            val ch = replacement[cursor]
            if (ch == '\\' && cursor + 1 < replacement.length) {
                cursor += 2
                continue
            }
            if (ch != '$' || cursor + 1 >= replacement.length) {
                cursor += 1
                continue
            }
            val next = replacement[cursor + 1]
            if (next in '1'..'9') {
                val referenced = next - '0'
                if (referenced > groupCount) {
                    issues += error(
                        index,
                        "替换串引用了不存在的捕获组 \$$referenced（该正则只有 $groupCount 个捕获组）"
                    )
                }
                cursor += 2
                continue
            }
            if (next == '{') {
                val close = replacement.indexOf('}', cursor + 2)
                if (close < 0) {
                    issues += warning(index, "替换串中的 ${'$'}{ 没有闭合，将按普通字符处理")
                    cursor += 2
                    continue
                }
                val name = replacement.substring(cursor + 2, close)
                if (name.isNotEmpty() && name !in definedNames) {
                    issues += error(index, "替换串引用了不存在的命名捕获组 ${'$'}{$name}")
                }
                cursor = close + 1
                continue
            }
            cursor += 1
        }
        return issues
    }

    private fun noteWarnings(index: Int, note: String): List<QualityRuleIssue> =
        if (note.length > MAX_NOTE_LENGTH) {
            listOf(warning(index, "备注长度 ${note.length} 超过 $MAX_NOTE_LENGTH，保存时将被截断"))
        } else {
            emptyList()
        }

    /**
     * 按执行语义编译校验用正则。
     *
     * 与 [compileRulePattern] 复用同一函数：保存期校验与运行期编译必须看到
     * 同一份 flag 组合，否则会出现"保存时能编译、运行时抛异常"的缝隙。
     * 这里刻意不注入 `(?U)`，原因见 [QualityReplacements] 的类注释
     * （Android 14+ 的 ICU 正则不接受 `U` 内联标志）。
     */
    internal fun compileRegex(pattern: String, caseSensitive: Boolean = false): Regex? =
        compileRulePattern(pattern, caseSensitive)
}

internal fun QualityRuleType.label(): String = when (this) {
    QualityRuleType.TEXT_PRESERVE -> "文本保护"
    QualityRuleType.PRE_REPLACEMENT -> "译前替换"
    QualityRuleType.POST_REPLACEMENT -> "译后替换"
}

internal fun error(index: Int, message: String) = QualityRuleIssue(
    index = index,
    severity = QualityRuleIssueSeverity.ERROR,
    message = message
)

internal fun warning(index: Int, message: String) = QualityRuleIssue(
    index = index,
    severity = QualityRuleIssueSeverity.WARNING,
    message = message
)

/**
 * 条目自带的正则能否按执行语义编译。
 *
 * 字面量条目不编译（它们走 [com.breakyuna.noveltranslator.core.text.LiteralMatcher]，
 * 不可能因语法失败）。
 */
internal fun ReplacementRuleEntry.isCompilableRegex(): Boolean =
    !isRegex || compileRulePattern(pattern, caseSensitive) != null

/** 供 resolver 在剔除坏规则前确认一条保护规则可编译。 */
internal fun TextPreserveRuleEntry.isCompilableRegex(): Boolean =
    compileRulePattern(src) != null
