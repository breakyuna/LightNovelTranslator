package com.breakyuna.noveltranslator.core.translation

/**
 * Parses and extracts numbers from natural language text, supporting:
 * - Standard ASCII Arabic digits (e.g., 2, 28, 10, 50, 1000)
 * - Full-width digits (e.g., ２, ２８)
 * - CJK/Chinese numeral words and combinations (e.g., 两, 二, 二十八, 十, 一百零五, 两千, 一万)
 * - Hybrid Arabic-CJK numeral notations (e.g., 3万, 500万, 2.5亿)
 *
 * This normalizes natural translations (such as model converting "2" to "两" or "28" to "二十八")
 * so QA validation can accurately distinguish faithful translations from factual hallucinations.
 */
object ChineseNumberParser {

    private val NON_NUMERIC_WORDS = setOf(
        "一切", "万一", "一般", "一定", "一起", "一直", "一方面", "进一步", "第一时间",
        "统一", "单一", "唯一", "专一", "同一", "合一", "不一", "归一", "万象",
        "千方百计", "九死一生", "乱七八糟", "五花八门", "三心二意", "十全十美",
        "万无一失", "千变万化", "千差万别", "一心一意", "一清二楚", "七上八下",
        "四面八方", "成千上万", "数一数二", "不管三七二十一", "三番五次", "五颜六色",
        "七拼八凑", "横七竖八", "丢三落四", "挑三拣四", "接二连三", "说一不二",
        "低三下四", "不三不四", "一干二净", "一五一十", "千篇一律", "百发百中"
    )

    private const val CJK_NUMBER_CHARACTERS = "零〇一壹二两倆俩贰貳三仨叁參四肆五伍六陆陸七柒八捌九玖十拾百佰千仟万萬亿億廿卅卌"

    private val CJK_NUMERAL_REGEX = Regex("[$CJK_NUMBER_CHARACTERS]+")
    private val ARABIC_NUMERAL_REGEX = Regex("\\p{Nd}+(?:[.,]\\p{Nd}+)?")
    private val HYBRID_NUMERAL_REGEX = Regex("(\\p{Nd}+(?:[.,]\\p{Nd}+)?)\\s*([万萬亿億千万佰拾]+)")

    private data class ExtractedToken(
        val start: Int,
        val end: Int,
        val normalizedValue: String
    )

    /**
     * Extracts all normalized numeric values (as string digits) from the input text in order of occurrence.
     */
    fun extractNormalizedNumbers(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val occupied = BooleanArray(text.length)
        val tokens = mutableListOf<ExtractedToken>()

        // 1. Mark non-numeric stop words and idioms so their inner numerals are ignored.
        for (word in NON_NUMERIC_WORDS) {
            var startIndex = 0
            while (startIndex < text.length) {
                val index = text.indexOf(word, startIndex)
                if (index == -1) break
                val endIndex = index + word.length
                for (i in index until endIndex) {
                    occupied[i] = true
                }
                startIndex = index + 1
            }
        }

        // 2. Extract hybrid numbers (e.g., 3万, 500万, 2.5亿)
        for (match in HYBRID_NUMERAL_REGEX.findAll(text)) {
            val range = match.range
            val start = range.first
            val end = range.last + 1
            if ((start until end).any { occupied[it] }) continue

            val numPartStr = match.groupValues[1].replace(",", "")
            val unitPartStr = match.groupValues[2]
            val unitMultiplier = parseChineseNumberToLong(unitPartStr)
            val numValue = numPartStr.toDoubleOrNull()

            if (unitMultiplier != null && numValue != null) {
                val totalValue = (numValue * unitMultiplier).toLong()
                tokens += ExtractedToken(start, end, totalValue.toString())
                for (i in start until end) occupied[i] = true
            }
        }

        // 3. Extract pure Arabic numbers (ASCII and full-width)
        for (match in ARABIC_NUMERAL_REGEX.findAll(text)) {
            val range = match.range
            val start = range.first
            val end = range.last + 1
            if ((start until end).any { occupied[it] }) continue

            val normalized = normalizeArabicNumberToken(match.value)
            if (normalized.isNotBlank()) {
                tokens += ExtractedToken(start, end, normalized)
                for (i in start until end) occupied[i] = true
            }
        }

        // 4. Extract CJK Chinese numbers
        for (match in CJK_NUMERAL_REGEX.findAll(text)) {
            val range = match.range
            val start = range.first
            val end = range.last + 1
            if ((start until end).any { occupied[it] }) continue

            val parsedValue = parseChineseNumberToLong(match.value)
            if (parsedValue != null) {
                tokens += ExtractedToken(start, end, parsedValue.toString())
                for (i in start until end) occupied[i] = true
            }
        }

        tokens.sortBy { it.start }
        return tokens.map { it.normalizedValue }
    }

    /**
     * Parses a CJK Chinese numeral string into a Long integer.
     */
    fun parseChineseNumberToLong(s: String): Long? {
        if (s.isBlank()) return null

        // If purely digits without place multipliers (e.g., "二〇二六", "二零二四", "九五二七", "两", "五")
        val hasMultipliers = s.any { it in "十拾百佰千仟万萬亿億廿卅卌" }
        if (!hasMultipliers) {
            val digits = s.map { charToDigit(it) ?: return null }
            if (digits.size == 1) return digits[0].toLong()
            var result = 0L
            for (d in digits) {
                result = result * 10 + d
            }
            return result
        }

        var total = 0L
        var section = 0L
        var currentNum: Long? = null

        for (ch in s) {
            val digit = charToDigit(ch)
            if (digit != null) {
                currentNum = if (digit == 0) 0L else digit.toLong()
                continue
            }

            when (ch) {
                '廿' -> {
                    section += 20L
                    currentNum = null
                }
                '卅' -> {
                    section += 30L
                    currentNum = null
                }
                '卌' -> {
                    section += 40L
                    currentNum = null
                }
                '十', '拾' -> {
                    val n = currentNum ?: 1L
                    section += n * 10L
                    currentNum = null
                }
                '百', '佰' -> {
                    val n = currentNum ?: 1L
                    section += n * 100L
                    currentNum = null
                }
                '千', '仟' -> {
                    val n = currentNum ?: 1L
                    section += n * 1000L
                    currentNum = null
                }
                '万', '萬' -> {
                    val secVal = section + (currentNum ?: 0L)
                    val n = if (secVal == 0L) 1L else secVal
                    total += n * 10000L
                    section = 0L
                    currentNum = null
                }
                '亿', '億' -> {
                    val secVal = section + (currentNum ?: 0L)
                    val n = if (secVal == 0L) 1L else secVal
                    total += n * 100000000L
                    section = 0L
                    currentNum = null
                }
                else -> return null
            }
        }

        total += section + (currentNum ?: 0L)
        return total
    }

    private fun normalizeArabicNumberToken(value: String): String = buildString(value.length) {
        value.forEach { character ->
            if (character.isDigit()) append(Character.digit(character, 10))
        }
    }

    private fun charToDigit(ch: Char): Int? = when (ch) {
        '0', '０', '零', '〇' -> 0
        '1', '１', '一', '壹' -> 1
        '2', '２', '二', '两', '倆', '俩', '贰', '貳' -> 2
        '3', '３', '三', '仨', '叁', '參' -> 3
        '4', '４', '四', '肆' -> 4
        '5', '５', '五', '伍' -> 5
        '6', '６', '六', '陆', '陸' -> 6
        '7', '７', '七', '柒' -> 7
        '8', '８', '八', '捌' -> 8
        '9', '９', '九', '玖' -> 9
        else -> null
    }
}
