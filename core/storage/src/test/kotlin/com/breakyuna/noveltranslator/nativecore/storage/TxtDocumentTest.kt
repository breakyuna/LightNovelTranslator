package com.breakyuna.noveltranslator.nativecore.storage

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class TxtDocumentTest {
    @Test fun splitlinesMatchesLgIncludingBlankLinesAndUnicodeSeparators() {
        assertEquals(listOf("a", "", "b", "c", "d"), parse("a\r\n\r\nb\u0085c\u2028d\n"))
        assertEquals(emptyList<String>(), parse(""))
        assertEquals(listOf(""), parse("\n"))
    }

    @Test fun utf16BomSelectsEncodingAndOriginalBytesAreNotChanged() {
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "中文\r\nnext".toByteArray(Charsets.UTF_16LE)
        val copy = bytes.copyOf()
        assertEquals(listOf("中文", "next"), TxtDocument.lines(bytes, TxtEncoding.UTF8, TextProjectLimits()))
        assertArrayEquals(copy, bytes)
    }

    @Test fun malformedInputAndSourceItemAndRowBudgetsFailExplicitly() {
        expect(TextProjectException.Reason.INVALID_TEXT) {
            TxtDocument.lines(byteArrayOf(0xc3.toByte()), TxtEncoding.UTF8, TextProjectLimits())
        }
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            TxtDocument.lines("long".toByteArray(), TxtEncoding.UTF8, TextProjectLimits(maxSourceBytes = 2))
        }
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            TxtDocument.lines("a\nb".toByteArray(), TxtEncoding.UTF8, TextProjectLimits(maxItems = 1))
        }
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            TxtDocument.lines("long".toByteArray(), TxtEncoding.UTF8, TextProjectLimits(maxItemChars = 2))
        }
    }

    private fun parse(text: String): List<String> = TxtDocument.lines(text.toByteArray(), TxtEncoding.UTF8, TextProjectLimits())
    private fun expect(reason: TextProjectException.Reason, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: TextProjectException) { assertEquals(reason, error.reason) }
    }
}
