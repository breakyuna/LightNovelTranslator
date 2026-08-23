package com.example

import com.example.core.llm.TokenCalculator
import com.example.core.llm.TranslationPrompts
import com.example.core.llm.LlmResult
import com.example.core.parser.TxtParser
import com.example.core.translator.TranslationQualityValidator
import com.example.core.agent.TermExtractionAgent
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.getAppStrings
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun translationQualityValidatorDetectsOmissionsAndMarkerChanges() {
        val source = """
            First source paragraph with enough detail to establish the scene and its characters.

            [IMG:illustration01.jpg]

            Second source paragraph continues the action with several complete sentences and dialogue.
        """.trimIndent().repeat(4)
        val missing = TranslationQualityValidator.validate(source, "A very short summary.")
        assertFalse(missing.isAcceptable)
        assertTrue(missing.problems.any { it.contains("short") || it.contains("paragraph") })

        val alteredMarker = TranslationQualityValidator.validate(source, source.replace("illustration01.jpg", "other.jpg"))
        assertFalse(alteredMarker.isAcceptable)
        assertTrue(alteredMarker.problems.any { it.contains("image markers") })
    }

    @Test
    fun termExtractionPromptRequiresRequestedTargetLanguage() {
        val prompt = TranslationPrompts.buildTermExtractionPrompt(
            textSample = "Alice entered the Silver Keep.",
            sourceLanguage = "English",
            targetLanguage = "Japanese",
            existingTerms = listOf("Alice")
        )
        assertTrue(prompt.contains("Required target language for every suggested translation: Japanese"))
        assertTrue(prompt.contains("Alice"))
    }

    @Test
    fun termExtractionPromptSamplesTheMiddleAndTailOfLongChapters() {
        val text = "HEAD_TERM " + "x".repeat(7000) + " MIDDLE_TERM " + "y".repeat(7000) + " TAIL_TERM"
        val prompt = TranslationPrompts.buildTermExtractionPrompt(text, "English", "Chinese")
        assertTrue(prompt.contains("HEAD_TERM"))
        assertTrue(prompt.contains("MIDDLE_TERM"))
        assertTrue(prompt.contains("TAIL_TERM"))
    }

    @Test
    fun malformedTerminologyJsonIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TermExtractionAgent.parseTermsJson(1L, "not-json")
        }
    }

    @Test
    fun malformedTerminologyResponseKeepsUsageForAccounting() {
        val result = TermExtractionAgent.parseExtractionResult(
            projectId = 1L,
            result = LlmResult(
                text = "not-json",
                promptTokens = 12,
                completionTokens = 3,
                durationMs = 10,
                isSuccess = true
            )
        )

        assertTrue(result.terms.isEmpty())
        assertEquals(12, result.usage.promptTokens)
        assertNotNull(result.parseError)
    }

    @Test
    fun costCalculationDoesNotRoundEachRequest() {
        val cost = TokenCalculator.calculateCost(1, 1, 0.1, 0.2)
        assertEquals(0.0000003, cost, 0.0000000001)
    }

    @Test
    fun testI18nStrings() {
        val zhStrings = getAppStrings(AppLanguage.CHINESE)
        val enStrings = getAppStrings(AppLanguage.ENGLISH)

        assertEquals("小说翻译工作室", zhStrings.appTitle)
        assertEquals("Novel Translator", enStrings.appTitle)
        assertNotNull(zhStrings.openSettings)
        assertNotNull(enStrings.openSettings)
        assertNotNull(zhStrings.themeSettingsTitle)
        assertNotNull(enStrings.themeSettingsTitle)
    }

    @Test
    fun testWordCountWithVariousLanguages() {
        val mixedText = "Hello world 这是测试章节"
        val count = TxtParser.countWords(mixedText)
        assertEquals(8, count) // 2 english words + 6 chinese characters

        // Test empty & blank
        assertEquals(0, TxtParser.countWords(""))
        assertEquals(0, TxtParser.countWords("   \n\t  "))

        // Test Japanese Hiragana & Katakana
        val jaText = "こんにちは世界"
        assertEquals(7, TxtParser.countWords(jaText))

        // Test Korean Hangul
        val koText = "안녕하세요 세계"
        assertEquals(7, TxtParser.countWords(koText))
    }

    @Test
    fun testChineseChapterRegexSplit() {
        val novelText = """
            第一章 开始旅程
            这是第一章的内容。
            
            第二章 遇到挑战
            这是第二章的内容。
        """.trimIndent()

        val chapters = TxtParser.splitIntoChapters(novelText)
        assertEquals(2, chapters.size)
        assertEquals("第一章 开始旅程", chapters[0].title)
        assertEquals("第二章 遇到挑战", chapters[1].title)
        assertTrue(chapters[0].content.contains("第一章的内容"))
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
    fun testMarkdownHeaderChapterSplit() {
        val mdText = """
            # Introduction
            Welcome to the story.
            
            ## Chapter 1
            This is the first part.
            
            ## Chapter 2
            This is the second part.
        """.trimIndent()

        val chapters = TxtParser.splitIntoChapters(mdText, regexPattern = TxtParser.REGEX_MARKDOWN)
        assertEquals(3, chapters.size)
        assertEquals("# Introduction", chapters[0].title)
        assertEquals("## Chapter 1", chapters[1].title)
        assertEquals("## Chapter 2", chapters[2].title)
    }

    @Test
    fun testSingleMonolithicTextFallbackChunking() {
        val paragraphs = List(20) { idx -> "这是第 ${idx + 1} 个段落的内容，包含足够的字数用来测试段落切割逻辑。" }
        val fullText = paragraphs.joinToString("\n\n")
        val chapters = TxtParser.splitIntoChapters(fullText, fallbackChunkWords = 50)
        assertTrue("Should fallback split into multiple chunks", chapters.size >= 2)
        assertEquals("第 1 章", chapters[0].title)
    }

    @Test
    fun testCharsetDetection() {
        // UTF-8 with BOM
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "测试内容".toByteArray(Charsets.UTF_8)
        val (text1, charset1) = TxtParser.detectCharsetAndRead(utf8BomBytes)
        assertEquals("测试内容", text1)
        assertEquals(Charsets.UTF_8, charset1)

        // Plain UTF-8
        val utf8Bytes = "标准UTF-8测试".toByteArray(Charsets.UTF_8)
        val (text2, charset2) = TxtParser.detectCharsetAndRead(utf8Bytes)
        assertEquals("标准UTF-8测试", text2)
        assertEquals(Charsets.UTF_8, charset2)
    }

    @Test
    fun testTokenCalculationAndBudget() {
        val promptTokens = TokenCalculator.estimateTokens("Translate the following text into English.")
        assertTrue(promptTokens > 0)

        val cjkTokens = TokenCalculator.estimateTokens("这是一段测试中文语句")
        assertTrue(cjkTokens >= 10)

        val budget = TokenCalculator.calculateChunkBudget(
            maxContextTokens = 8192,
            overheadEstimate = 800L
        )
        assertTrue(budget in 600L..3000L)

        val smallCtxBudget = TokenCalculator.calculateChunkBudget(
            maxContextTokens = 2048,
            overheadEstimate = 500L
        )
        assertTrue(smallCtxBudget >= 600L)
    }

    @Test
    fun testCurrencyFormatting() {
        val usd = TokenCalculator.formatCost(0.00456, "USD")
        assertTrue(usd.startsWith("$"))

        val cny = TokenCalculator.formatCost(0.00456, "CNY")
        assertTrue(cny.startsWith("¥"))

        val cost = TokenCalculator.calculateCost(
            promptTokens = 10000,
            completionTokens = 5000,
            inputPricePerMillion = 0.5,
            outputPricePerMillion = 1.5
        )
        // 10000/1M * 0.5 = 0.005, 5000/1M * 1.5 = 0.0075 -> total 0.0125
        assertEquals(0.0125, cost, 0.0001)
    }

    @Test
    fun testFormatTokenCount() {
        assertEquals("500", TokenCalculator.formatTokenCount(500))
        assertEquals("1.5k", TokenCalculator.formatTokenCount(1500))
        assertEquals("2.50M", TokenCalculator.formatTokenCount(2500000))
    }
}
