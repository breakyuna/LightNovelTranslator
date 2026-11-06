package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/** EPUB 与 TXT 共用的正文归一策略必须一致，否则同书换格式会出现段落粒度差异。 */
class ImportTextNormalizerTest {

    @Test
    fun foldsRunsOfBlankLinesIntoSingleBlankLine() {
        assertEquals("a\n\nb", ImportTextNormalizer.foldBlankLines("a\n\n\n\n\nb"))
        assertEquals("a\n\nb", ImportTextNormalizer.foldBlankLines("a\n\nb"))
        assertEquals("a\nb", ImportTextNormalizer.foldBlankLines("a\nb"))
        assertEquals("a", ImportTextNormalizer.foldBlankLines("a"))
    }

    @Test
    fun mapsNonBreakingSpaceButKeepsIdeographicSpaceAndTabs() {
        assertEquals("a b", ImportTextNormalizer.normalizeNonBreakingSpaces("a\u00A0b"))
        assertEquals("a\u3000b", ImportTextNormalizer.normalizeNonBreakingSpaces("a\u3000b"))
        assertEquals("a\tb", ImportTextNormalizer.normalizeNonBreakingSpaces("a\tb"))
    }

    @Test
    fun blockTextTrimsLineEdgesAndKeepsInnerWhitespace() {
        // 行首尾空白（含全角空格与制表符）是 HTML 排版产物，行内空白必须保留。
        assertEquals(
            "你好\u3000世界\t制表",
            ImportTextNormalizer.normalizeBlockText("   你好\u3000世界\t制表  ")
        )
    }

    @Test
    fun blockTextKeepsSoftBreaksAndFoldsBlankLines() {
        assertEquals("第一行\n第二行", ImportTextNormalizer.normalizeBlockText("第一行\n第二行"))
        assertEquals("第一段\n\n第二段", ImportTextNormalizer.normalizeBlockText("第一段\n\n\n\n第二段"))
    }

    @Test
    fun blockTextDropsLeadingAndTrailingBlankLines() {
        assertEquals("正文", ImportTextNormalizer.normalizeBlockText("\n\n  正文  \n\n"))
    }

    @Test
    fun blockTextMapsNonBreakingSpaceDecodedFromEntities() {
        assertEquals("a b", ImportTextNormalizer.normalizeBlockText("a\u00A0b"))
    }
}
