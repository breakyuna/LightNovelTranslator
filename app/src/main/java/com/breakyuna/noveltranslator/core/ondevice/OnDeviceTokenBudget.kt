package com.breakyuna.noveltranslator.core.ondevice

/**
 * 端侧 GenAI 的输入 Token 预算与分段工具（纯 JVM 逻辑，可单测）。
 *
 * 依据官方文档：
 * - Prompt / Summarization：输入必须 < 4000 tokens（约 3000 英文单词）。
 *   https://developers.google.com/ml-kit/genai/summarization/android
 *   https://developers.google.com/ml-kit/genai/prompt/android/get-started
 * - Proofreading：输入应 < 256 tokens（远比 4000 严格，实现按 256 分段）。
 *   https://developers.google.com/ml-kit/genai/proofreading/android
 * - Summarization InputType.ARTICLE 还要求输入 > 400 字符；不足时仍尝试调用，
 *   若模型返回 REQUEST_TOO_SMALL 由调用方按 FAILED 处理并回退云端。
 *
 * Token 估算为保守近似：CJK/日文假名/韩文音节按 1 字符 ≈ 1 token，其余按 4 字符 ≈ 1 token。
 * 真实计数仅 Prompt API 的 countTokens 提供，摘要/校对无计数 API，故用估算值分段。
 */
object OnDeviceTokenBudget {

    /** Prompt / Summarization 官方硬上限。 */
    const val MAX_INPUT_TOKENS = 4000

    /** Proofreading 官方输入上限（tokens）。 */
    const val PROOFREAD_MAX_INPUT_TOKENS = 256

    /** 摘要/提示词实际使用的安全上限，留余量给请求结构与输出。 */
    const val SAFE_INPUT_TOKENS = 3500

    /** 校对实际使用的安全上限。 */
    const val PROOFREAD_SAFE_TOKENS = 200

    /** 估算文本 token 数：CJK 类字符 1 字 1 token，其余 4 字符 1 token（向上取整）。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjkChar(ch)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** CJK 统一表意文字、假名、韩文音节、全角标点等按 1 字符 1 token 计。 */
    fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2E80..0x2EFF || // CJK 部首补充
            code in 0x3000..0x303F || // CJK 符号与标点
            code in 0x3040..0x30FF || // 平假名 + 片假名
            code in 0x3130..0x318F || // 韩文兼容字母
            code in 0x3400..0x4DBF || // CJK 扩展 A
            code in 0x4E00..0x9FFF || // CJK 统一表意
            code in 0xAC00..0xD7AF || // 韩文音节
            code in 0xF900..0xFAFF || // CJK 兼容表意
            code in 0xFF00..0xFFEF || // 全角/半角形式
            code in 0x20000..0x2FA1F // CJK 扩展 B-F 与兼容补充
    }

    /**
     * 将文本分段，使每段 [estimateTokens] 不超过 [maxTokens]。
     *
     * 分段策略（保内容、不丢字）：
     * 1. 整体不超限 → 单段；
     * 2. 超限 → 先按行贪心合并（行尾的 '\n' 作为单元的一部分保留）；
     * 3. 单行超限 → 按句读（。！？!?．）切分后贪心合并；
     * 4. 单句超限 → 按 [maxTokens] 个字符硬切（估算器保证每字符 ≤ 1 token）。
     *
     * 所有段按序直接拼接（段间不加任何分隔符）即还原原文。
     */
    fun splitForTokenLimit(text: String, maxTokens: Int): List<String> {
        require(maxTokens > 0) { "maxTokens must be > 0" }
        if (text.isBlank()) return emptyList()
        if (estimateTokens(text) <= maxTokens) return listOf(text)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current = StringBuilder()
            }
        }

        fun appendUnit(unit: String) {
            if (current.isNotEmpty() && estimateTokens(current.toString() + unit) > maxTokens) {
                flush()
            }
            current.append(unit)
        }

        // 行单元：保留行尾 '\n'，保证拼接后与原文一致。
        val lines = text.split('\n')
        for ((index, line) in lines.withIndex()) {
            val unit = if (index == lines.lastIndex) line else "$line\n"
            if (estimateTokens(unit) <= maxTokens) {
                appendUnit(unit)
            } else {
                flush()
                for (sentence in splitBySentence(unit)) {
                    if (estimateTokens(sentence) <= maxTokens) {
                        appendUnit(sentence)
                    } else {
                        flush()
                        var start = 0
                        while (start < sentence.length) {
                            val end = minOf(start + maxTokens, sentence.length)
                            chunks.add(sentence.substring(start, end))
                            start = end
                        }
                    }
                }
            }
        }
        flush()
        return chunks.ifEmpty { listOf(text) }
    }

    /** 按句末标点切分并保留标点；无标点的长句整体返回。 */
    internal fun splitBySentence(line: String): List<String> {
        val sentences = mutableListOf<String>()
        val builder = StringBuilder()
        for (ch in line) {
            builder.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '.' || ch == '．') {
                sentences.add(builder.toString())
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) sentences.add(builder.toString())
        return sentences
    }
}
