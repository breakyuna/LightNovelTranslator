package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.InvalidTextRule
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextRule
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import org.junit.Assert.*
import org.junit.Test

class RuleMatchExplainerTest {
    private val explainer = RuleMatchExplainer()

    private fun rule(
        id: String,
        source: String,
        kind: TextRuleKind = TextRuleKind.TERM,
        regex: Boolean = false,
        caseSensitive: Boolean = true,
        enabled: Boolean = true,
    ) = ProjectTextRule(id, kind, source, "target", "", enabled, regex, caseSensitive)

    @Test
    fun reportsNonOverlappingOffsetsForLiteralAndRegexRules() {
        val literal = explainer.explain(rule("term", "cat"), "cat cat")
        assertEquals(listOf(RuleMatchSpan(0, 3), RuleMatchSpan(4, 7)), literal.spans)
        assertEquals(2, literal.matchCount)
        assertFalse(literal.truncated)
        assertEquals(7, literal.textLength)

        val regex = explainer.explain(rule("protect", "c.t", TextRuleKind.PROTECT, regex = true), "cat cot")
        assertEquals(listOf(RuleMatchSpan(0, 3), RuleMatchSpan(4, 7)), regex.spans)

        val overlapping = explainer.explain(rule("replace", "aa", TextRuleKind.PRE_REPLACE), "aaaa")
        assertEquals(listOf(RuleMatchSpan(0, 2), RuleMatchSpan(2, 4)), overlapping.spans)
    }

    @Test
    fun casePolicyAndDisablementAreReportedWithoutHidingOffsets() {
        assertEquals(1, explainer.explain(rule("term", "cat"), "CAT cat").spans.size)
        assertEquals(2, explainer.explain(rule("term", "cat", caseSensitive = false), "CAT cat").spans.size)
        val disabled = explainer.explain(rule("term", "cat", enabled = false), "cat")
        assertFalse(disabled.enabled)
        assertEquals(1, disabled.matchCount)
    }

    @Test
    fun offsetsAreBoundedAndZeroLengthMatchesAreIgnored() {
        val bounded = explainer.explain(rule("term", "aa"), "aaaa", maxMatches = 1)
        assertEquals(1, bounded.spans.size)
        assertEquals(1, bounded.matchCount)
        assertTrue(bounded.truncated)

        val empty = explainer.explain(rule("protect", "a*", TextRuleKind.PROTECT, regex = true), "bbb")
        assertTrue(empty.spans.isEmpty())
        assertEquals(0, empty.matchCount)
        assertFalse(empty.truncated)
    }

    @Test
    fun oversizedTextAndInvalidRegexAreRejectedAndNoTextIsRetained() {
        try {
            explainer.explain(rule("term", "x"), "x".repeat(RuleMatchExplainer.MAX_TEXT_CHARS + 1))
            fail("Accepted oversized text")
        } catch (_: InvalidTextRule) {
        }
        try {
            explainer.explain(rule("protect", "(", TextRuleKind.PROTECT, regex = true), "text")
            fail("Accepted invalid regex")
        } catch (_: InvalidTextRule) {
        }
        val explanation = explainer.explain(rule("term", "secret"), "secret")
        assertEquals(1, explanation.matchCount)
        assertFalse(explanation.toString().contains("secret"))
        assertFalse(explanation.spans.toString().contains("secret"))
    }
}
