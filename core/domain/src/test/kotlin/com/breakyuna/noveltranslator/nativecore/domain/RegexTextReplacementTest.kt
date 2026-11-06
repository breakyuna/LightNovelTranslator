package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class RegexTextReplacementTest {
    private fun replace(text: String, pattern: String, target: String) =
        LiteralTextReplacement.preview(text, TextReplacementOptions(pattern, target, regex = true))

    @Test fun dollarCapturesUseJavascriptFallbackRulesAndBackslashesRemainLiteral() {
        assertEquals("b-a-a0-a-\$0-\$9-\$-ab-\\1", replace("ab", "(a)(b)",
            "\$2-\$1-\$10-\$01-\$0-\$9-\$\$-\$&-\\1").text)
        assertEquals("x", replace("b", "(a)?b", "\$1x").text)
        assertEquals("\$1", replace("b", "b", "\$1").text)
        assertEquals("a9", replace("a", "(a)", "\$19").text)
    }

    @Test fun prefixSuffixAndNamedCapturesComeFromTheOriginalSource() {
        assertEquals("xx[b]y[y]y", replace("xby", "b", "\$`[\$&]\$'[\$']").text)
        assertEquals("A: ", replace("a", "(?P<letter>a)", "A: \$<missing>").text)
        assertEquals("a", replace("a", "(?P<letter>a)", "\$<letter>").text)
        assertEquals("\$<letter>", replace("a", "a", "\$<letter>").text)
    }

    @Test fun literalModeDoesNotExpandTokensAndCasePolicyIsExplicit() {
        assertEquals("\$1\\1", LiteralTextReplacement.preview("(a)", TextReplacementOptions("(a)", "\$1\\1")).text)
        val options = TextReplacementOptions("(a)", "\$1x", caseSensitive = false, regex = true)
        assertEquals("Ax ax", LiteralTextReplacement.preview("A a", options).text)
    }

    @Test fun zeroWidthMatchesAreFiniteAndExpansionCancellationAndUnsupportedPatternsReject() {
        val result = replace("ab", "^|$", "_")
        assertEquals("_ab_", result.text); assertEquals(2, result.occurrences)
        for (pattern in listOf("[", "(?=a)", "(a)\\1")) {
            try { TextReplacementOptions(pattern, "x", regex = true); fail("Accepted unsupported pattern") }
            catch (_: InvalidTextRule) { }
        }
        try { replace("a".repeat(65_536), "a", "\$`\$'"); fail("Accepted expansion overflow") }
        catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.LIMIT_EXCEEDED, failure.reason) }
        try {
            LiteralTextReplacement.preview("text", TextReplacementOptions(".*", "\$&", regex = true)) {
                throw java.util.concurrent.CancellationException()
            }; fail("Ignored cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
}
