package com.example

import com.example.core.llm.TokenCalculator
import com.example.core.parser.TxtParser
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testWordCount() {
        val mixedText = "Hello world 这是测试章节"
        val count = TxtParser.countWords(mixedText)
        assertEquals(8, count) // 2 english words + 6 chinese characters
    }

    @Test
    fun testChineseChapterRegexSplit() {
        val novelText = """
            前言
            这是前言内容。
            
            第一章 开始旅程
            这是第一章的内容。
            
            第二章 遇到挑战
            这是第二章的内容。
        """.trimIndent()

        val chapters = TxtParser.splitIntoChapters(novelText)
        assertEquals(3, chapters.size)
        assertEquals("前言", chapters[0].title)
        assertEquals("第一章 开始旅程", chapters[1].title)
        assertEquals("第二章 遇到挑战", chapters[2].title)
    }

    @Test
    fun testEnglishChapterRegexSplit() {
        val novelText = """
            Chapter 1 The Beginning
            In a hole in the ground there lived a hobbit.
            
            Chapter 2 The Shadow of the Past
            The talk did not die down in nine days.
        """.trimIndent()

        val chapters = TxtParser.splitIntoChapters(novelText, regexPattern = TxtParser.REGEX_ENGLISH)
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].title.startsWith("Chapter 1"))
        assertTrue(chapters[1].title.startsWith("Chapter 2"))
    }

    @Test
    fun testTokenCalculationAndBudget() {
        val promptTokens = TokenCalculator.estimateTokens("Translate the following text into English.")
        assertTrue(promptTokens > 0)

        val budget = TokenCalculator.calculateChunkBudget(
            maxContextTokens = 8192,
            systemPromptTokens = 500,
            glossaryTokens = 300,
            summaryTokens = 400,
            maxOutputTokens = 3000
        )
        // 8192 - 3000 - 500 - 300 - 400 - 400 = 3592
        assertEquals(3592, budget)
    }

    @Test
    fun testCurrencyFormatting() {
        val usd = TokenCalculator.formatCost(0.00456, "USD")
        assertTrue(usd.startsWith("$"))

        val cny = TokenCalculator.formatCost(0.00456, "CNY")
        assertTrue(cny.startsWith("¥"))
    }
}
