package com.breakyuna.noveltranslator.core.text

import java.util.regex.PatternSyntaxException

/**
 * 单条文本保护规则。
 *
 * [pattern] 是 Java 正则源码（不含分隔符与 flag），与 LinguaGacha 的
 * `text-preserve-rules.ts` 条目一一对应；[info] 只服务于设置页展示与日志，
 * 不参与匹配。
 */
data class TextPreserveEntry(
    val pattern: String,
    val info: String = ""
)

/**
 * 同一文本的一次保护段裁决，准备、译后恢复与质量检查共用同一份结果。
 *
 * 移植自 LinguaGacha `TextPreserveAnalysis`：
 * - [text] 绑定被分析的文本，处理阶段改变后必须重新分析；
 * - [segments] 是按源文顺序排列的保护段；
 * - [unpreservedText] 把保护段替换为等长空白并保留 `\r\n`，消费方以 trim
 *   判断该行是否仍有可翻译正文。占位保持行长与词边界，Token 预算与
 *   外文残留检查可以直接消费。
 */
data class TextPreserveAnalysis(
    val text: String,
    val segments: List<String>,
    val unpreservedText: String
)

/** 收集阶段的候选命中，携带规则定义序供重叠裁决。 */
private data class PreserveCandidate(
    val value: String,
    val index: Int,
    val ruleIndex: Int
)

/**
 * 可配置正则规则集：一次编译、多文本复用。
 *
 * 设计要点（全部移植自 LinguaGacha `TextPreserveRule`）：
 * 1. 每条规则独立 `findAll`，先收集**全部**候选，再统一裁决重叠；
 * 2. 同起点按规则定义序优先，随后丢弃与已选区间重叠的候选；
 * 3. 规则遍历顺序因此不会影响文本顺序，`segments` 始终按源文顺序返回。
 *
 * 正则 flag 说明：来源实现使用 JS 的 `giu`。Kotlin 侧用 `IGNORE_CASE` 对齐 `gi`，
 * 并前置 `(?U)` 让 `\s`/`\d`/`\w` 走 Unicode 语义以对齐 `u`。
 * 已知偏差：`(?U)` 同时把 `\b`/`\w` 变成 Unicode 边界，比 JS 的 ASCII 边界更宽；
 * 内置规则因此避免依赖 `\b`。
 *
 * **Android 14+（API 34）兼容**：`java.util.regex` 在 API 34 切换到 ICU 实现后，
 * 内联 `(?U)` 标志可能抛 `PatternSyntaxException`（Robolectric 跑 JVM 正则测不出，
 * 只在 API 34+ 真机复现）。因此编译带兜底：带 `(?U)` 失败时退回 JVM 默认语义，
 * 保护能力降级但绝不让整章掩蔽路径崩溃。
 */
class TextProtectionRule(entries: List<TextPreserveEntry>) {

    private val patterns: List<Regex> = entries.map { entry ->
        // 非法正则在构造期直接抛 PatternSyntaxException，与来源实现的编译期失败一致，
        // 避免运行期静默失效。
        compileRule(entry.pattern)
    }

    private fun compileRule(pattern: String): Regex = try {
        Regex("(?U)$pattern", RegexOption.IGNORE_CASE)
    } catch (_: PatternSyntaxException) {
        Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** 一次裁决同时提供保护段与剩余正文，后续检查复用相同结果。 */
    fun analyze(text: String): TextPreserveAnalysis {
        val matches = collectMatches(text)
        return TextPreserveAnalysis(
            text = text,
            segments = matches.map { it.value },
            unpreservedText = replaceMatches(text, matches) { value, _ -> blankOut(value) }
        )
    }

    /** 收集正文中的可接受保护段（可能包含纯空白段，调用方按需过滤）。 */
    fun collect(text: String): List<String> = collectMatches(text).map { it.value }

    /** 用固定字符串替换保护段。 */
    fun replace(text: String, replacement: String): String =
        replaceMatches(text, collectMatches(text)) { value, _ -> replacement }

    /** 用回调替换保护段，回调索引只统计实际被替换的段。 */
    fun replace(text: String, replacement: (String, Int) -> String): String =
        replaceMatches(text, collectMatches(text)) { value, index -> replacement(value, index) }

    /** 仅转换保护段之间的文本，保护段按原样写回。 */
    fun transformUnpreserved(text: String, transform: (String) -> String): String {
        val matches = collectMatches(text)
        val result = StringBuilder(text.length)
        var cursor = 0
        for (match in matches) {
            result.append(transform(text.substring(cursor, match.index)))
            result.append(match.value)
            cursor = match.index + match.value.length
        }
        result.append(transform(text.substring(cursor)))
        return result.toString()
    }

    /**
     * 收集全部候选后统一裁决重叠，避免规则遍历顺序改变文本顺序。
     *
     * 空命中（零宽匹配）直接丢弃：零宽段无法构成可恢复的保护边界，
     * 且会让 `cursor` 失去推进保证。
     */
    private fun collectMatches(text: String): List<PreserveCandidate> {
        val candidates = ArrayList<PreserveCandidate>()
        patterns.forEachIndexed { ruleIndex, pattern ->
            for (match in pattern.findAll(text)) {
                val value = match.value
                if (value.isEmpty()) continue
                candidates += PreserveCandidate(value, match.range.first, ruleIndex)
            }
        }
        return removeOverlappingMatches(candidates)
    }

    /** 同起点按规则顺序优先，随后丢弃与已选范围重叠的候选。 */
    private fun removeOverlappingMatches(candidates: List<PreserveCandidate>): List<PreserveCandidate> {
        val sorted = candidates.sortedWith(compareBy({ it.index }, { it.ruleIndex }))
        val result = ArrayList<PreserveCandidate>(sorted.size)
        var cursor = 0
        for (candidate in sorted) {
            if (candidate.index < cursor) continue
            result += candidate
            cursor = candidate.index + candidate.value.length
        }
        return result
    }

    /** 按已裁决的非重叠区间重建文本。 */
    private inline fun replaceMatches(
        text: String,
        matches: List<PreserveCandidate>,
        replacement: (String, Int) -> String
    ): String {
        val result = StringBuilder(text.length)
        var cursor = 0
        matches.forEachIndexed { index, match ->
            result.append(text, cursor, match.index)
            result.append(replacement(match.value, index))
            cursor = match.index + match.value.length
        }
        result.append(text, cursor, text.length)
        return result.toString()
    }

    /**
     * 把保护段内容替换为等长空白，仅保留 `\r\n`。
     *
     * 逐 UTF-16 单元映射，与 Kotlin 字符串下标一致；不改变行长，
     * 因此 `unpreservedText` 与原文可以按同一坐标系比较。
     */
    private fun blankOut(value: String): String = buildString(value.length) {
        for (ch in value) append(if (ch == '\r' || ch == '\n') ch else ' ')
    }
}

/**
 * 统一提取非空保护段，保留原始空白供精确比较。
 *
 * 移植自 LinguaGacha `collect_non_blank_text_preserve_segments`。
 */
fun collectNonBlankTextPreserveSegments(text: String, rule: TextProtectionRule): List<String> =
    rule.collect(text).filter { it.isNotBlank() }
