package com.breakyuna.noveltranslator.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseNumberParserTest {

    @Test
    fun parseChineseNumberToLong_basicDigits() {
        assertEquals(0L, ChineseNumberParser.parseChineseNumberToLong("零"))
        assertEquals(0L, ChineseNumberParser.parseChineseNumberToLong("〇"))
        assertEquals(1L, ChineseNumberParser.parseChineseNumberToLong("一"))
        assertEquals(1L, ChineseNumberParser.parseChineseNumberToLong("壹"))
        assertEquals(2L, ChineseNumberParser.parseChineseNumberToLong("二"))
        assertEquals(2L, ChineseNumberParser.parseChineseNumberToLong("两"))
        assertEquals(2L, ChineseNumberParser.parseChineseNumberToLong("俩"))
        assertEquals(3L, ChineseNumberParser.parseChineseNumberToLong("三"))
        assertEquals(4L, ChineseNumberParser.parseChineseNumberToLong("四"))
        assertEquals(5L, ChineseNumberParser.parseChineseNumberToLong("五"))
        assertEquals(6L, ChineseNumberParser.parseChineseNumberToLong("六"))
        assertEquals(7L, ChineseNumberParser.parseChineseNumberToLong("七"))
        assertEquals(8L, ChineseNumberParser.parseChineseNumberToLong("八"))
        assertEquals(9L, ChineseNumberParser.parseChineseNumberToLong("九"))
    }

    @Test
    fun parseChineseNumberToLong_tensAndTeens() {
        assertEquals(10L, ChineseNumberParser.parseChineseNumberToLong("十"))
        assertEquals(11L, ChineseNumberParser.parseChineseNumberToLong("十一"))
        assertEquals(12L, ChineseNumberParser.parseChineseNumberToLong("十二"))
        assertEquals(15L, ChineseNumberParser.parseChineseNumberToLong("十五"))
        assertEquals(20L, ChineseNumberParser.parseChineseNumberToLong("二十"))
        assertEquals(25L, ChineseNumberParser.parseChineseNumberToLong("二十五"))
        assertEquals(28L, ChineseNumberParser.parseChineseNumberToLong("二十八"))
        assertEquals(28L, ChineseNumberParser.parseChineseNumberToLong("廿八"))
        assertEquals(30L, ChineseNumberParser.parseChineseNumberToLong("三十"))
        assertEquals(35L, ChineseNumberParser.parseChineseNumberToLong("卅五"))
        assertEquals(99L, ChineseNumberParser.parseChineseNumberToLong("九十九"))
    }

    @Test
    fun parseChineseNumberToLong_hundredsThousandsAndLarger() {
        assertEquals(100L, ChineseNumberParser.parseChineseNumberToLong("一百"))
        assertEquals(105L, ChineseNumberParser.parseChineseNumberToLong("一百零五"))
        assertEquals(128L, ChineseNumberParser.parseChineseNumberToLong("一百二十八"))
        assertEquals(1000L, ChineseNumberParser.parseChineseNumberToLong("一千"))
        assertEquals(2000L, ChineseNumberParser.parseChineseNumberToLong("两千"))
        assertEquals(2500L, ChineseNumberParser.parseChineseNumberToLong("两千五百"))
        assertEquals(10000L, ChineseNumberParser.parseChineseNumberToLong("一万"))
        assertEquals(20000L, ChineseNumberParser.parseChineseNumberToLong("两万"))
        assertEquals(35000L, ChineseNumberParser.parseChineseNumberToLong("三万五千"))
        assertEquals(120000L, ChineseNumberParser.parseChineseNumberToLong("十二万"))
        assertEquals(100000000L, ChineseNumberParser.parseChineseNumberToLong("一亿"))
        assertEquals(120000000L, ChineseNumberParser.parseChineseNumberToLong("一亿两千万"))
    }

    @Test
    fun parseChineseNumberToLong_digitSequences() {
        assertEquals(2024L, ChineseNumberParser.parseChineseNumberToLong("二零二四"))
        assertEquals(2026L, ChineseNumberParser.parseChineseNumberToLong("二〇二六"))
    }

    @Test
    fun extractNormalizedNumbers_naturalNovelTranslations() {
        // Natural numbers in Chinese translated text
        assertEquals(
            listOf("2", "3"),
            ChineseNumberParser.extractNormalizedNumbers("两只狼和三名冒险者")
        )
        assertEquals(
            listOf("28", "10"),
            ChineseNumberParser.extractNormalizedNumbers("二十八天之后，十人幸存。")
        )
        assertEquals(
            listOf("12"),
            ChineseNumberParser.extractNormalizedNumbers("他等了十二天。")
        )
        assertEquals(
            listOf("50"),
            ChineseNumberParser.extractNormalizedNumbers("等级达到了五十级。")
        )
    }

    @Test
    fun extractNormalizedNumbers_mixedArabicAndChinese() {
        assertEquals(
            listOf("3", "2000"),
            ChineseNumberParser.extractNormalizedNumbers("第3军团共有两千人。")
        )
        assertEquals(
            listOf("1", "500"),
            ChineseNumberParser.extractNormalizedNumbers("第一阶段消耗500点法力。")
        )
    }

    @Test
    fun extractNormalizedNumbers_hybridArabicMultiplier() {
        assertEquals(
            listOf("30000"),
            ChineseNumberParser.extractNormalizedNumbers("敌军共有3万人。")
        )
        assertEquals(
            listOf("5000000"),
            ChineseNumberParser.extractNormalizedNumbers("总计500万赏金。")
        )
    }

    @Test
    fun extractNormalizedNumbers_ignoresIdiomsAndStopWords() {
        // "一切" should not extract 1
        assertEquals(
            listOf("5"),
            ChineseNumberParser.extractNormalizedNumbers("准备了五个包袱，一切顺利。")
        )
        // "一直" should not extract 1
        assertEquals(
            listOf("2"),
            ChineseNumberParser.extractNormalizedNumbers("两个人一直向前走。")
        )
        // "万一" should not extract 10001
        assertEquals(
            emptyList<String>(),
            ChineseNumberParser.extractNormalizedNumbers("万一遇到危险就逃跑。")
        )
        // "千方百计" should not extract numbers
        assertEquals(
            listOf("3"),
            ChineseNumberParser.extractNormalizedNumbers("千方百计找到了三件圣物。")
        )
    }
}
