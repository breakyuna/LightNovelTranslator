package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PromptTemplateImportTest {
    private fun template(id: String, body: String = "body", order: Int = 0, enabled: Boolean = true) =
        PromptTemplate(id, "Template $id", enabled = enabled, order = order, body = body)

    @Test fun defaultMergeReportsConflictAndKeepsExistingBodyWhileAddingNewIdentity() {
        val old = template("one", "original")
        val base = PromptTemplateSet(listOf(old))
        val result = base.previewImport(PromptTemplateSet(listOf(template("one", "changed"), template("two"))))
        assertFalse(result.clean)
        assertEquals(1, result.conflicts); assertEquals(1, result.added)
        assertEquals("original", result.templates.templates.first().body)
        assertEquals("original", base.templates.single().body)
        assertEquals(PromptTemplateCodec.fingerprint(base), result.expected)
        assertEquals(PromptTemplateImportDisposition.CONFLICT, result.changes.first().disposition)
        assertEquals("original", result.changes.first().before!!.body)
        assertEquals("changed", result.changes.first().after!!.body)
    }

    @Test fun updateRequiresExplicitChoiceAndPreservesUnmentionedTemplates() {
        val base = PromptTemplateSet(listOf(template("one"), template("keep")))
        val incoming = PromptTemplateSet(listOf(template("one", "new {{name}}", order = 7, enabled = false)))
        val result = base.previewImport(incoming, PromptTemplateImportMode.UPDATE)
        assertTrue(result.clean); assertEquals(1, result.updated); assertEquals(0, result.removed)
        val updated = result.templates.templates.first { it.id == "one" }
        assertEquals("new {{name}}", updated.body); assertEquals(7, updated.order); assertFalse(updated.enabled)
        assertTrue(result.templates.templates.any { it.id == "keep" })
    }

    @Test fun replaceIncludesRemovalsAndEmptyLibraryIsAnExplicitClear() {
        val base = PromptTemplateSet(listOf(template("one"), template("two")))
        val result = base.previewImport(PromptTemplateSet(listOf(template("one"))), PromptTemplateImportMode.REPLACE)
        assertEquals(1, result.unchanged); assertEquals(1, result.removed)
        assertEquals(listOf("one"), result.templates.templates.map { it.id })
        assertEquals("two", result.changes.single { it.disposition == PromptTemplateImportDisposition.REMOVED }.id)
        val cleared = base.previewImport(PromptTemplateSet(emptyList()), PromptTemplateImportMode.REPLACE)
        assertEquals(2, cleared.removed); assertTrue(cleared.templates.templates.isEmpty())
        assertTrue(base.previewImport(PromptTemplateSet(emptyList())).templates.templates.size == 2)
    }

    @Test fun exportImportKeepsLanguageRestrictionsAndStableOrder() {
        val second = PromptTemplate("b", "B", enabled = false, languages = listOf(PromptLanguageTag("ja")), order = 2, body = "{{text}}")
        val first = template("a", order = 1)
        val file = PromptTemplateCodec.encode(PromptTemplateSet(listOf(second, first)))
        val result = PromptTemplateSet(emptyList()).previewImport(PromptTemplateCodec.decode(file))
        assertEquals(listOf("a", "b"), result.templates.ordered().map { it.id })
        val copy = result.templates.ordered().last()
        assertEquals(listOf(PromptLanguageTag("ja")), copy.languages); assertFalse(copy.enabled)
        assertEquals("{{text}}", copy.body)
    }

    @Test fun mergeLimitIsCheckedBeforeAnyLibraryMutation() {
        val base = PromptTemplateSet((1..64).map { template("t$it") })
        try {
            base.previewImport(PromptTemplateSet(listOf(template("extra"))))
            fail("Accepted too many templates")
        } catch (_: IllegalArgumentException) { }
        assertEquals(64, base.templates.size)
    }
}
