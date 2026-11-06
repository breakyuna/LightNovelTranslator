package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class TextQualityIndexTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val fp = ObjectFingerprint("a".repeat(64))
    private val options = TextProofreadingOptions()
    private fun fact(id: Long, evaluated: Boolean = true, codes: List<TextWarningCode> = emptyList(),
        terms: List<String> = emptyList(), hits: Map<String, Int> = emptyMap()) =
        TextQualityFact(ItemId(id), fp, TextProofreadingResult(codes, terms, evaluated, fp, options, hits))
    private fun index(facts: List<TextQualityFact>) = TextQualityIndex(ref, 2, TextItemQuery(), options, fp, facts, facts.size)

    @Test fun skippedRowsAreDistinctFromCleanRowsAndMultipleWarningsCountOncePerType() {
        val index = index(listOf(fact(1, false), fact(2), fact(3, codes = listOf(TextWarningCode.GLOSSARY,
            TextWarningCode.GLOSSARY, TextWarningCode.TEXT_PRESERVE), terms = listOf("t"))))
        assertEquals(2, index.evaluatedCount); assertEquals(1, index.cleanCount)
        assertEquals(1, index.warningCounts[TextWarningCode.GLOSSARY]); assertEquals(1, index.missingTermCounts["t"])
        assertEquals(listOf(ItemId(1)), index.select(TextQualityFilter(TextQualityOutcome.NOT_EVALUATED)).ids)
        assertEquals(listOf(ItemId(2)), index.select(TextQualityFilter(TextQualityOutcome.NO_WARNING)).ids)
        assertEquals(listOf(ItemId(3)), index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY, "t")).ids)
    }
    @Test fun positiveTermHitsSelectMatchingItemsAndAggregateOccurrences() {
        val index = index(listOf(fact(1, hits = mapOf("t" to 2)), fact(2, hits = mapOf("t" to 1)),
            fact(3, codes = listOf(TextWarningCode.GLOSSARY), terms = listOf("t"))))
        assertEquals(3, index.termHitCounts["t"])
        assertEquals(2, index.termHitItemCounts["t"])
        assertEquals(listOf(ItemId(1), ItemId(2)),
            index.select(TextQualityFilter(TextQualityOutcome.ALL, termRuleId = "t", termMatched = true)).ids)
        assertEquals(listOf(ItemId(3)),
            index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, termRuleId = "t")).ids)
    }

    @Test fun sourceListsAreCopiedAndCursorWindowsRemainStrictlyOrderedAndBounded() {
        val index = index(listOf(fact(1), fact(4), fact(9)))
        val selection = index.select(TextQualityFilter())
        assertEquals(listOf(ItemId(1), ItemId(4)), selection.window(0, 2))
        assertEquals(listOf(ItemId(9)), selection.window(4, 2))
        assertEquals(listOf(ItemId(4), ItemId(9)), selection.window(2, 2))
        assertTrue(selection.window(9, 100).isEmpty()); assertNotNull(index.fact(ItemId(4))); assertNull(index.fact(ItemId(5)))
        try { selection.window(0, 101); fail("Accepted oversized window") } catch (_: IllegalArgumentException) { }
    }
    @Test fun selectionChecksCancellationAndInvalidFilterShapesAreRejected() {
        val index = index(listOf(fact(1)))
        try { index.select(TextQualityFilter()) { throw java.util.concurrent.CancellationException() }; fail("Ignored cancellation") }
        catch (_: java.util.concurrent.CancellationException) { }
        try { TextQualityFilter(TextQualityOutcome.NO_WARNING, TextWarningCode.GLOSSARY); fail("Accepted conflicting filter") }
        catch (_: IllegalArgumentException) { }
    }
}
