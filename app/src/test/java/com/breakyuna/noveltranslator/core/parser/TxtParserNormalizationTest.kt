package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TXT 与 EPUB 的空白归一必须一致：连续空行折叠、行内全角空格/制表符保留。
 * 既有字符集探测与章节推断能力见 TxtParserTest，这里只覆盖新增的归一行为。
 */
class TxtParserNormalizationTest {

    @Test
    fun inMemorySplitFoldsConsecutiveBlankLines() {
        val text = "第一章 开始\n第一段\n\n\n\n第二段\n第二章 继续\n第三段"

        val chapters = TxtParser.splitIntoChapters(text)

        assertEquals(2, chapters.size)
        assertEquals("第一段\n\n第二段", chapters.first().content)
        assertEquals("第三段", chapters.last().content)
    }

    @Test
    fun streamingSplitFoldsConsecutiveBlankLines() {
        val text = "第一章 开始\n第一段\n\n\n\n第二段"

        val chapters = TxtParser.chapterSequence(text.reader().buffered()).toList()

        assertEquals("第一段\n\n第二段", chapters.single().content)
    }

    @Test
    fun paragraphFallbackChunkingNeverEmitsRunsOfBlankLines() {
        val paragraphs = List(6) { index ->
            "第 ${index + 1} 段内容，足够长度用于触发回退分块逻辑的中文文本。"
        }
        val fullText = paragraphs.joinToString("\n\n\n\n")

        val chapters = TxtParser.splitIntoChapters(fullText, fallbackChunkWords = 50)

        assertTrue("Should fallback split into multiple chunks", chapters.size >= 2)
        chapters.forEach { chapter -> assertFalse(chapter.content.contains("\n\n\n")) }
    }

    @Test
    fun fullWidthSpacesAndTabsArePreservedInsideTxtChapters() {
        val text = "第一章 开始\n你好\u3000世界\n制表\t分隔"

        val chapters = TxtParser.splitIntoChapters(text)

        assertEquals("你好\u3000世界\n制表\t分隔", chapters.single().content)
    }

    @Test
    fun singleBlankLineSeparatesParagraphsWithoutFolding() {
        val text = "第一章 开始\n第一段\n\n第二段"

        val chapters = TxtParser.splitIntoChapters(text)

        assertEquals("第一段\n\n第二段", chapters.single().content)
    }
}
