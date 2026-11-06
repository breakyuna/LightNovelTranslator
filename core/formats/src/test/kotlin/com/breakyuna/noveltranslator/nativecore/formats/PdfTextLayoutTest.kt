package com.breakyuna.noveltranslator.nativecore.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfTextLayoutTest {
    private fun measure(codePoint: Int): Float = when {
        codePoint == ' '.code -> 0.25f
        codePoint in 0x2E80..0x9FFF -> 1f
        else -> 0.5f
    }

    @Test fun wrapsLatinTextAtWordBoundaries() {
        val lines = PdfTextLayout.wrap("alpha beta gamma", 3f, ::measure)
        assertEquals(listOf("alpha", "beta", "gamma"), lines)
    }

    @Test fun splitsASingleWordThatCannotFit() {
        val lines = PdfTextLayout.wrap("abcdefgh", 2f, ::measure)
        assertEquals(listOf("abcd", "efgh"), lines)
        assertTrue(lines.all { widthOf(it) <= 2f })
    }

    @Test fun breaksBetweenCjkCharacters() {
        val lines = PdfTextLayout.wrap("中文测试", 2f, ::measure)
        assertEquals(listOf("中文", "测试"), lines)
    }

    @Test fun keepsExplicitEmptyParagraphs() {
        val lines = PdfTextLayout.wrap("alpha\n\nbeta", 10f, ::measure)
        assertEquals(listOf("alpha", "", "beta"), lines)
    }

    @Test fun normalizesTabsToSingleSpaces() {
        val lines = PdfTextLayout.wrap("a\tb", 10f, ::measure)
        assertEquals(listOf("a b"), lines)
    }

    @Test fun everyLineStaysWithinTheWidthForBreakableText() {
        val text = "one two three four five six seven 中文测试内容 alpha beta"
        val lines = PdfTextLayout.wrap(text, 4f, ::measure)
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertTrue("line too wide: $line", widthOf(line) <= 4f)
        }
    }

    @Test fun sanitizeNormalizesLineEndingsAndDropsControls() {
        val text = "a\r\nb\rc\u0000d\u0007e\u009Ff"
        assertEquals("a\nb\ncdef", PdfTextLayout.sanitize(text))
    }

    @Test fun sanitizeDropsUnpairedSurrogatesAndKeepsValidPairs() {
        val paired = "\uD83D\uDE00"
        assertEquals("a" + paired + "b", PdfTextLayout.sanitize("a\uD800" + paired + "\uDC00b"))
    }

    @Test fun sanitizeDropsByteOrderMarks() {
        assertEquals("ab", PdfTextLayout.sanitize("\uFEFFa\uFEFFb"))
    }

    @Test fun zeroWidthIsRejected() {
        try {
            PdfTextLayout.wrap("text", 0f, ::measure)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: callers must pass a positive content width.
        }
    }

    private fun widthOf(line: String): Float {
        var width = 0f
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            width += measure(codePoint)
            index += Character.charCount(codePoint)
        }
        return width
    }
}
