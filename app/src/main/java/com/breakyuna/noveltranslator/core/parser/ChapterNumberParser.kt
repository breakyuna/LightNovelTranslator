package com.breakyuna.noveltranslator.core.parser

import java.util.Locale

/**
 * 章节编号解析器
 * 支持将阿拉伯数字、全角数字、汉字数字及罗马数字解析为整数。
 */
object ChapterNumberParser {

    /**
     * 将任意数字字符串解析为 Int，如果无法解析则返回 null
     */
    fun parseNumber(rawStr: String?): Int? {
        if (rawStr.isNullOrBlank()) return null
        val trimmed = normalizeFullWidth(rawStr.trim())

        // 1. 尝试纯阿拉伯数字解析
        val arabic = trimmed.toIntOrNull()
        if (arabic != null && arabic >= 0) return arabic

        // 2. 尝试从混合字符串中提取前缀/主体阿拉伯数字
        val digitsOnly = trimmed.takeWhile { it.isDigit() }
        if (digitsOnly.isNotEmpty()) {
            val num = digitsOnly.toIntOrNull()
            if (num != null && num >= 0) return num
        }

        // 3. 尝试中文数字解析
        val chineseNum = parseChineseNumber(trimmed)
        if (chineseNum != null && chineseNum >= 0) return chineseNum

        // 4. 尝试罗马数字解析
        val romanNum = parseRomanNumber(trimmed)
        if (romanNum != null && romanNum > 0) return romanNum

        return null
    }

    /**
     * 将全角数字字符规范化为半角数字
     */
    fun normalizeFullWidth(str: String): String {
        val sb = java.lang.StringBuilder(str.length)
        for (ch in str) {
            when (ch) {
                in '０'..'９' -> sb.append((ch - '０' + '0'.code).toChar())
                '，', ',' -> sb.append("")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 解析中文数字（支持 0 ~ 999,999）
     * 例如："一", "十二", "二十三", "一百零五", "两千零三", "万"
     */
    private fun parseChineseNumber(str: String): Int? {
        val clean = str.replace(" ", "").replace("第", "").replace("章", "").replace("回", "").replace("節", "").replace("节", "").replace("話", "").replace("话", "")
        if (clean.isEmpty()) return null

        var total = 0
        var currentSection = 0
        var currentNumber = 0
        var hasValidDigit = false

        for (ch in clean) {
            when (ch) {
                '零', '〇' -> {
                    currentNumber = 0
                    hasValidDigit = true
                }
                '一', '壹' -> {
                    currentNumber = 1
                    hasValidDigit = true
                }
                '二', '贰', '两', '兩' -> {
                    currentNumber = 2
                    hasValidDigit = true
                }
                '三', '叁', '仨' -> {
                    currentNumber = 3
                    hasValidDigit = true
                }
                '四', '肆' -> {
                    currentNumber = 4
                    hasValidDigit = true
                }
                '五', '伍' -> {
                    currentNumber = 5
                    hasValidDigit = true
                }
                '六', '陆', '陸' -> {
                    currentNumber = 6
                    hasValidDigit = true
                }
                '七', '柒' -> {
                    currentNumber = 7
                    hasValidDigit = true
                }
                '八', '捌' -> {
                    currentNumber = 8
                    hasValidDigit = true
                }
                '九', '玖' -> {
                    currentNumber = 9
                    hasValidDigit = true
                }
                '十', '拾' -> {
                    hasValidDigit = true
                    if (currentNumber == 0) currentNumber = 1
                    currentSection += currentNumber * 10
                    currentNumber = 0
                }
                '百', '佰' -> {
                    hasValidDigit = true
                    if (currentNumber == 0) currentNumber = 1
                    currentSection += currentNumber * 100
                    currentNumber = 0
                }
                '千', '仟' -> {
                    hasValidDigit = true
                    if (currentNumber == 0) currentNumber = 1
                    currentSection += currentNumber * 1000
                    currentNumber = 0
                }
                '万', '萬' -> {
                    hasValidDigit = true
                    currentSection += currentNumber
                    total += if (currentSection == 0) 10000 else currentSection * 10000
                    currentSection = 0
                    currentNumber = 0
                }
                else -> return null // 出现非数字字符，判定不是纯中文数字
            }
        }

        if (!hasValidDigit) return null
        currentSection += currentNumber
        return total + currentSection
    }

    /**
     * 解析罗马数字（I, V, X, L, C, D, M）
     */
    private fun parseRomanNumber(str: String): Int? {
        val upper = str.trim().uppercase(Locale.ROOT)
        if (upper.isEmpty()) return null

        val romanMap = mapOf(
            'I' to 1,
            'V' to 5,
            'X' to 10,
            'L' to 50,
            'C' to 100,
            'D' to 500,
            'M' to 1000
        )

        for (c in upper) {
            if (!romanMap.containsKey(c)) return null
        }

        var result = 0
        var prevValue = 0

        for (i in upper.length - 1 downTo 0) {
            val current = romanMap[upper[i]] ?: return null
            if (current < prevValue) {
                result -= current
            } else {
                result += current
            }
            prevValue = current
        }

        return if (result in 1..4999) result else null
    }
}
