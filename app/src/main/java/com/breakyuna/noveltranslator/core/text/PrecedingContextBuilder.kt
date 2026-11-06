package com.breakyuna.noveltranslator.core.text

/**
 * 一行可作前文的源文。
 *
 * [sourceId] 标识这行属于哪个来源（书籍 / 版本 / 文件路径）；前文不允许跨来源，
 * 否则会把另一本书的结尾当成当前章节的上文。
 */
data class PrecedingSourceLine(
    val sourceId: String,
    val text: String
)

/**
 * 前文构建器：从当前章节向前取源文行作为上文。
 *
 * 移植自 LinguaGacha `translation-planner.ts` 的 generate_preceding_chunk。
 * 这是解锁并行翻译的关键：前文**只依赖源文**，不依赖任何已有译文，
 * 因此第 N 章的上文在请求发出前就能确定，章节之间不再有生产依赖。
 *
 * 三条约束：
 * 1. 只接纳以句末标点结尾的行——半句话开头会让模型的"承接"失去意义；
 * 2. 受 [threshold] 条数上限约束（0 表示禁用，与来源实现的默认值一致）；
 * 3. 不跨 [PrecedingSourceLine.sourceId]。
 *
 * 任一条不满足即停止向前搜索，而不是跳过：跳过半句话再往前取，
 * 拿到的上文与当前章节之间会出现断裂。
 */
class PrecedingContextBuilder(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val endLinePunctuation: Set<Char> = DEFAULT_END_LINE_PUNCTUATION
) {

    /**
     * 从 [items] 的 [startIndex] 之前向前收集前文，按文档顺序返回。
     *
     * [startIndex] 是当前 chunk 在 [items] 中的起始下标。
     */
    fun build(
        currentSourceId: String,
        items: List<PrecedingSourceLine>,
        startIndex: Int
    ): List<PrecedingSourceLine> {
        if (threshold <= 0) return emptyList()
        val result = ArrayList<PrecedingSourceLine>()
        for (index in startIndex - 1 downTo 0) {
            val item = items.getOrNull(index) ?: continue
            val src = item.text.trim()
            if (src.isEmpty() || result.size >= threshold) break
            if (item.sourceId != currentSourceId) break
            val lastChar = src.lastOrNull()
            if (lastChar != null && lastChar in endLinePunctuation) {
                result += item
            } else {
                break
            }
        }
        return result.reversed()
    }

    /** 便捷入口：[precedingLines] 是紧邻当前 chunk 之前的行，按文档顺序。 */
    fun buildPreceding(
        currentSourceId: String,
        precedingLines: List<PrecedingSourceLine>
    ): List<PrecedingSourceLine> = build(currentSourceId, precedingLines, precedingLines.size)

    companion object {
        /** 默认禁用：与 LinguaGacha 的 `preceding_lines_threshold` 默认值一致。 */
        const val DEFAULT_THRESHOLD: Int = 0

        /** 向前选取上文时，仅接纳以这些标点结尾的条目。 */
        val DEFAULT_END_LINE_PUNCTUATION: Set<Char> = setOf(
            '.', '。', '?', '？', '!', '！', '…', '\'', '"', '」', '』'
        )
    }
}
