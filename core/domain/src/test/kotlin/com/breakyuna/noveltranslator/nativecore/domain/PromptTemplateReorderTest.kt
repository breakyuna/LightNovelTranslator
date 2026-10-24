package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PromptTemplateReorderTest {
    @Test fun reorderingTiedOrdersPreservesEveryTemplatePayloadAndLanguageFilter() {
        val a = PromptTemplate("a", "A", "description", false, listOf(PromptLanguageTag("ja")), 7, "body {{name}}")
        val b = PromptTemplate("b", "B", order = 7, body = "other")
        val original = PromptTemplateSet(listOf(a, b))
        val moved = original.reordered(listOf("b", "a"))
        assertEquals(listOf("b", "a"), moved.ordered().map { it.id })
        assertEquals(listOf(0, 1), moved.ordered().map { it.order })
        val restoredA = moved.templates.last()
        assertEquals(a.body, restoredA.body); assertEquals(a.name, restoredA.name)
        assertEquals(a.description, restoredA.description); assertEquals(a.languages, restoredA.languages)
        assertFalse(restoredA.enabled)
        assertEquals(listOf("b"), moved.enabledFor(PromptLanguageTag("ja")).map { it.id })
        assertEquals(listOf("a", "b"), original.ordered().map { it.id })
        assertEquals(7, a.order)
    }

    @Test fun partialDuplicateUnknownAndOversizeOrderRequestsAreRejected() {
        val original = PromptTemplateSet(listOf(PromptTemplate("a", "A", body = "one"), PromptTemplate("b", "B", body = "two")))
        for (ids in listOf(listOf("a"), listOf("a", "a"), listOf("a", "unknown"), listOf("a", "b", "extra"))) {
            try { original.reordered(ids); fail("Accepted an invalid permutation") }
            catch (_: IllegalArgumentException) { }
        }
        try { original.templates.first().withOrder(PromptTemplate.MAX_ORDER + 1); fail("Accepted invalid order") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, original.templates.size)
    }
}
