package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TxtParserTest {
    @Test
    fun infersEnglishHeadingsFromLinesBeyondTheFileStart() {
        val sample = "Preface\nintro\n\nChapter 1\nbody\n\nChapter 2\nbody"

        assertEquals(TxtParser.REGEX_ENGLISH, TxtParser.inferChapterRegex(sample))
    }

    @Test
    fun acceptsLowercaseEnglishChapterHeadings() {
        val sample = "preface\nintro\n\nchapter 1\nbody\n\nchapter 2\nbody"

        val chapters = TxtParser.splitIntoChapters(sample, TxtParser.REGEX_ENGLISH)

        assertEquals(listOf("Preface / Prologue", "chapter 1", "chapter 2"), chapters.map { it.title })
    }

    @Test
    fun fallsBackToGbkWhenBytesAreNotValidUtf8() {
        val charset = Charset.forName("GB18030")
        val (text, detected) = TxtParser.detectCharsetAndRead("中文章节".toByteArray(charset))

        assertEquals("中文章节", text)
        assertEquals(charset, detected)
    }

    @Test
    fun honorsExplicitLanguageHintBeforeAutoDetection() {
        val sample = "Chapter 1\nbody\n\nChapter 2\nbody"

        assertEquals(TxtParser.REGEX_CHINESE, TxtParser.inferChapterRegex(sample, "Chinese"))
    }

    @Test
    fun infersMarkdownHeadingsWhenNoBuiltInLanguageRuleMatches() {
        val sample = "# Part One\nbody\n\n## Part Two\nbody"

        assertEquals(TxtParser.REGEX_MARKDOWN, TxtParser.inferChapterRegex(sample))
    }

    @Test
    fun infersJapaneseAndKoreanHeadingRules() {
        assertEquals(
            TxtParser.REGEX_JAPANESE,
            TxtParser.inferChapterRegex("第１話\n本文\n\n第２話\n本文", "Japanese")
        )
        assertEquals(
            TxtParser.REGEX_KOREAN,
            TxtParser.inferChapterRegex("제 1 장\n본문\n\n제 2 장\n본문", "Korean")
        )
    }
}
