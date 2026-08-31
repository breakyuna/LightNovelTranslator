package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.*
import org.junit.Test

class ChapterStructureDetectorTest {

    @Test
    fun testStandardChineseChapters() {
        val sample = buildString {
            for (i in 1..10) {
                append("第 $i 章 标题 $i\n")
                append("这是第 $i 章的正文内容，包含丰富的故事情节与文字描述。".repeat(20))
                append("\n\n")
            }
        }
        val result = ChapterStructureDetector.detect(sample)
        assertEquals(10, result.headings.size)
        assertEquals(ChapterPatternType.CHINESE_NUMBERED, result.headings.first().patternType)
        assertTrue("Confidence should be high (>= 85)", result.confidence >= 85f)
        assertEquals(1, result.headings.first().chapterNumber)
        assertEquals(10, result.headings.last().chapterNumber)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testChineseCharactersNumbered() {
        val titles = listOf("第一章 开端", "第二章 发展", "第十章 转折", "第二十三章 激战", "第一百章 终章")
        val sample = buildString {
            titles.forEach { title ->
                append("$title\n")
                append("这是章节正文内容，描述情节发展。\n\n".repeat(15))
            }
        }
        val result = ChapterStructureDetector.detect(sample)
        assertEquals(5, result.headings.size)
        assertEquals(1, result.headings[0].chapterNumber)
        assertEquals(2, result.headings[1].chapterNumber)
        assertEquals(10, result.headings[2].chapterNumber)
        assertEquals(23, result.headings[3].chapterNumber)
        assertEquals(100, result.headings[4].chapterNumber)
        assertTrue(result.confidence >= 75f)
    }

    @Test
    fun testKoreanChaptersWithHwaAndJang() {
        val sampleHwa = buildString {
            for (i in 1..8) {
                append("${i}화 새로운 시작\n")
                append("이것은 한국어 소설 본문 내용입니다. 모험이 계속됩니다.\n\n".repeat(15))
            }
        }
        val resultHwa = ChapterStructureDetector.detect(sampleHwa)
        assertEquals(8, resultHwa.headings.size)
        assertEquals(ChapterPatternType.KOREAN_NUMBERED, resultHwa.headings.first().patternType)
        assertEquals(1, resultHwa.headings.first().chapterNumber)
        assertEquals(8, resultHwa.headings.last().chapterNumber)
        assertTrue(resultHwa.confidence >= 85f)

        val sampleJang = buildString {
            for (i in 1..5) {
                append("제 $i 장\n")
                append("본문 텍스트 내용입니다.\n\n".repeat(15))
            }
        }
        val resultJang = ChapterStructureDetector.detect(sampleJang)
        assertEquals(5, resultJang.headings.size)
        assertEquals(ChapterPatternType.KOREAN_NUMBERED, resultJang.headings.first().patternType)
        assertEquals(1, resultJang.headings.first().chapterNumber)
    }

    @Test
    fun testJapaneseChaptersWithWaAndHen() {
        val sample = buildString {
            for (i in 1..6) {
                append("第${i}話 異世界生活\n")
                append("これは日本語の小説の本文です。冒険が始まります。\n\n".repeat(15))
            }
        }
        val result = ChapterStructureDetector.detect(sample)
        assertEquals(6, result.headings.size)
        assertEquals(ChapterPatternType.JAPANESE_NUMBERED, result.headings.first().patternType)
        assertEquals(1, result.headings.first().chapterNumber)
        assertTrue(result.confidence >= 85f)
    }

    @Test
    fun testEnglishChaptersWithRomanAndArabic() {
        val sample = """
            Chapter 1: The Beginning
            It was the best of times, it was the worst of times.
            
            Chapter 2: The Journey
            They set out into the wilderness early in the morning.
            
            Chapter 3: The Climax
            The battle began at dawn and lasted until dusk.
            
            Chapter 4: The Aftermath
            Peace returned to the kingdom once again.
        """.trimIndent()

        val result = ChapterStructureDetector.detect(sample)
        assertEquals(4, result.headings.size)
        assertEquals(ChapterPatternType.ENGLISH_NUMBERED, result.headings.first().patternType)
        assertEquals(1, result.headings[0].chapterNumber)
        assertEquals(4, result.headings[3].chapterNumber)
        assertTrue(result.confidence >= 80f)
    }

    @Test
    fun testMarkdownHeadings() {
        val sample = """
            # 第一部 序章
            世界之初，一片混沌。
            
            # 第二部 征程
            勇者踏上了漫长的旅程。
            
            # 第三部 决战
            终焉之时悄然降临。
        """.trimIndent()

        val result = ChapterStructureDetector.detect(sample)
        assertEquals(3, result.headings.size)
        assertEquals(ChapterPatternType.MARKDOWN_HEADING, result.headings.first().patternType)
        assertTrue(result.confidence >= 65f)
    }

    @Test
    fun testArabicNumberedOutlines() {
        val sample = buildString {
            for (i in 1..7) {
                append("$i. 章节标题 $i\n")
                append("这是详细的段落内容，说明了该小节的要点与展开叙述。\n\n".repeat(10))
            }
        }
        val result = ChapterStructureDetector.detect(sample)
        assertEquals(7, result.headings.size)
        assertEquals(ChapterPatternType.ARABIC_NUMBERED, result.headings.first().patternType)
        assertEquals(1, result.headings.first().chapterNumber)
        assertEquals(7, result.headings.last().chapterNumber)
    }

    @Test
    fun testLeadingTocDetectionAndCropping() {
        val sample = buildString {
            append("目录\n")
            for (i in 1..8) {
                append("第 $i 章 标题 $i\n")
            }
            append("\n\n正文开始\n\n")
            for (i in 1..8) {
                append("第 $i 章 标题 $i\n")
                append("这是第 $i 章的正文详细内容，包含许多精彩的对话和故事展开。".repeat(30))
                append("\n\n")
            }
        }
        val result = ChapterStructureDetector.detect(sample)
        // 应该剔除开头的 8 条目录，只保留正文的 8 章
        assertEquals(8, result.headings.size)
        assertNotNull("Should detect TOC range", result.detectedTocRange)
        assertTrue("First heading should be after TOC", result.headings.first().charOffset > result.detectedTocRange!!.last)
    }

    @Test
    fun testSpecialUnnumberedPrologueAndEpilogue() {
        val sample = buildString {
            append("序章 世界的起源\n")
            append("在一切开始之前，古老的神明注视着大地。\n\n".repeat(15))

            for (i in 1..5) {
                append("第 $i 章 故事 $i\n")
                append("这是正文章节内容。\n\n".repeat(15))
            }

            append("尾声 终结与新生\n")
            append("英雄们的故事在这里画上了句号，但新的传说正在孕育。\n\n".repeat(15))
        }

        val result = ChapterStructureDetector.detect(sample)
        // 序章 + 5章 + 尾声 = 7
        assertEquals(7, result.headings.size)
        assertEquals("序章 世界的起源", result.headings.first().normalizedText)
        assertEquals("尾声 终结与新生", result.headings.last().normalizedText)
    }

    @Test
    fun testNumberParserCombinations() {
        assertEquals(1, ChapterNumberParser.parseNumber("1"))
        assertEquals(12, ChapterNumberParser.parseNumber("１２"))
        assertEquals(105, ChapterNumberParser.parseNumber("一百零五"))
        assertEquals(2023, ChapterNumberParser.parseNumber("两千零二十三"))
        assertEquals(14, ChapterNumberParser.parseNumber("XIV"))
        assertEquals(8, ChapterNumberParser.parseNumber("VIII"))
        assertEquals(1, ChapterNumberParser.parseNumber("01"))
        assertEquals(0, ChapterNumberParser.parseNumber("0"))
    }
}
