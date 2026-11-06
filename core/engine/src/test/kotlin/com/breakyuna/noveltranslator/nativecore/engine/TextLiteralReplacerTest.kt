package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class TextLiteralReplacerTest {
    @Test fun metacharactersAndReplacementCaptureLikeTextRemainLiteral() {
        val result = TextLiteralReplacer.preview(".* a .*", TextReplacementOptions(".*", "\$1\\1"))
        assertEquals("\$1\\1 a \$1\\1", result.text); assertEquals(2, result.occurrences); assertTrue(result.changed)
        assertEquals(ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(".* a .*"))), result.originalFingerprint)
    }
    @Test fun matchingIsNonoverlappingAndSupportsWhitespaceAndExplicitCasePolicy() {
        assertEquals("ba", TextLiteralReplacer.preview("aaa", TextReplacementOptions("aa", "b")).text)
        assertEquals("A b", TextLiteralReplacer.preview("A a", TextReplacementOptions("a", "b")).text)
        assertEquals("b b", TextLiteralReplacer.preview("A a", TextReplacementOptions("a", "b", false)).text)
        assertEquals("ab", TextLiteralReplacer.preview("a\r\nb", TextReplacementOptions("\r\n", "")).text)
        assertEquals("a_b", TextLiteralReplacer.preview("a b", TextReplacementOptions(" ", "_")).text)
    }
    @Test fun unchangedDraftsAndMissingMatchesDoNotBecomeWriteCandidates() {
        val same = TextLiteralReplacer.preview("aaa", TextReplacementOptions("a", "a"))
        assertEquals(3, same.occurrences); assertFalse(same.changed)
        val absent = TextLiteralReplacer.preview("aaa", TextReplacementOptions("z", "x"))
        assertEquals(0, absent.occurrences); assertFalse(absent.changed)
        try { TextReplacementOptions("", "x"); fail("Accepted empty needle") } catch (_: IllegalArgumentException) { }
    }
    @Test fun cancellationAndExpansionLimitsRejectThePreviewWithoutTruncatingIt() {
        try {
            TextLiteralReplacer.preview("text", TextReplacementOptions("t", "x")) { throw java.util.concurrent.CancellationException() }
            fail("Ignored cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        try {
            TextLiteralReplacer.preview("x".repeat(20), TextReplacementOptions("x", "z".repeat(4096)))
            fail("Accepted oversized draft")
        } catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.LIMIT_EXCEEDED, failure.reason) }
    }
}
