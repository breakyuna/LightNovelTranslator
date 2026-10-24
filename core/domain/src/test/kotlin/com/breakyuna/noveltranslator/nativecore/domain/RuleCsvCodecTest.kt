package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class RuleCsvCodecTest {
    private val header = "src,dst,info,regex,case_sensitive\r\n"

    @Test fun roundTripKeepsCommasQuotesNewlinesAndMatchingIdentity() {
        val rule = ProjectTextRule("kept", TextRuleKind.POST_REPLACE, " a,\"b\" ", "line1\r\nline2", "注記", regex = true)
        val policy = ProjectTextPolicy(listOf(rule))
        val result = RuleCsvCodec.decode(RuleCsvCodec.encode(policy, rule.kind), rule.kind, policy)
        assertEquals(rule, result.rules.single())
    }

    @Test fun emptyHeaderOnlyFileAndBooleanCellsHaveExplicitMeaning() {
        assertTrue(RuleCsvCodec.decode(header, TextRuleKind.TERM).rules.isEmpty())
        val decoded = RuleCsvCodec.decode(header + "a,b,,FALSE, TRUE \n", TextRuleKind.TERM)
        assertFalse(decoded.rules.single().regex)
        assertTrue(decoded.rules.single().caseSensitive)
    }

    @Test fun malformedRecordsAndQuotesNeverProducePartialImport() {
        for (text in listOf("", "dst,src,info,regex,case_sensitive\n", header + "a,b\n",
            header + "a,b,,false,no\n", header + "\"a\"tail,b,,false,true\n",
            header + "\"a,b,,false,true\n", header + "a,b,,false,true,extra\n")) {
            try { RuleCsvCodec.decode(text, TextRuleKind.TERM); fail("Accepted malformed CSV") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun limitsAndDisabledExportCannotBeBypassed() {
        try {
            RuleCsvCodec.decode(header + (1..257).joinToString("\n") { "s$it,t,,false,true" }, TextRuleKind.TERM)
            fail("Accepted excessive rows")
        } catch (_: IllegalArgumentException) { }
        try {
            RuleCsvCodec.encode(ProjectTextPolicy(listOf(ProjectTextRule("x", TextRuleKind.TERM, "a", enabled = false))), TextRuleKind.TERM)
            fail("Export silently enabled rule")
        } catch (_: IllegalArgumentException) { }
    }
}
