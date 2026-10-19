package com.breakyuna.noveltranslator.core.parser

/**
 * 导入侧（EPUB / TXT）共用的正文归一策略。
 *
 * 统一口径（EPUB 与 TXT 必须一致，否则同一本书换格式会出现段落粒度差异）：
 * 1. 行内空白原样保留：全角空格 U+3000、制表符、连续半角空格都不做"空白压缩"。
 *    EPUB 走 jsoup 的 `TextNode.getWholeText()`，TXT 走原始行，二者都不重写
 *    行内空白——段首缩进与词边界会直接进翻译 token，压缩会丢信息。
 * 2. `&nbsp;` 解码产物 U+00A0 映射为普通空格（仅 EPUB：这是 HTML 实体的解码
 *    产物，不是作者输入的字符；与既有 EPUB 导入行为保持一致）。
 * 3. 连续空行折叠为单个空行：3 个及以上连续 `\n` 收敛为 2 个。
 * 4. 逐行去首尾空白只用于 EPUB 块：HTML 源码的缩进是排版产物，不属正文；
 *    TXT 保留作者原始行格式，不做行级修剪。
 */
internal object ImportTextNormalizer {

    /** 连续换行的折叠目标：一个空行（两个 `\n`）。 */
    private val CONSECUTIVE_NEWLINES = Regex("\n{3,}")

    /**
     * 折叠连续空行：3 个及以上连续 `\n` 收敛为 2 个。
     * TXT 与 EPUB 的章正文在落库前都经过这一步。
     */
    fun foldBlankLines(text: String): String =
        if (text.indexOf('\n') < 0) text else CONSECUTIVE_NEWLINES.replace(text, "\n\n")

    /**
     * 把 HTML 实体解码后的 U+00A0（`&nbsp;`）映射为普通空格。
     * 其余 Unicode 空白（U+3000 等）保持原样。
     */
    fun normalizeNonBreakingSpaces(text: String): String =
        if (text.indexOf('\u00A0') < 0) text else text.replace('\u00A0', ' ')

    /**
     * EPUB 块文本归一：实体空格映射 → 逐行去首尾空白 → 块内连续空行折叠。
     *
     * 块内单个换行（来自 `<br>`）保留为软换行；两个及以上连续换行（空行）
     * 折叠为一个空行，与 TXT 的段落分隔口径一致。块首尾的空白行直接丢弃。
     */
    fun normalizeBlockText(raw: String): String {
        val normalized = normalizeNonBreakingSpaces(raw)
        val lines = normalized.split('\n')
        val result = ArrayList<String>(lines.size)
        var pendingBlankLine = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // 块首的空白行没有语义，只有出现过正文之后的空行才保留一个。
                if (result.isNotEmpty()) pendingBlankLine = true
                continue
            }
            if (pendingBlankLine) {
                result += ""
                pendingBlankLine = false
            }
            result += trimmed
        }
        return result.joinToString("\n")
    }
}
