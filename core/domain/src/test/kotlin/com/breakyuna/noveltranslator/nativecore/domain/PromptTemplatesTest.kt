package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun renderingSubstitutesBoundedVariablesOnceWithoutRescanningValues() {
        val template = PromptTemplate(
            id = "template",
            name = "Template",
            body = "Translate {{source}} to {{target}}: {{source}}",
        )
        val rendered = template.render(mapOf("source" to "{{target}}", "target" to "English"))
        assertEquals("Translate {{target}} to English: {{target}}", rendered)
        assertEquals(listOf("source", "target"), template.placeholders)
        assertFalse(template.toString().contains("Translate"))
    }

    @Test
    fun renderingRejectsMalformedMissingAndOversizedVariablesWithoutEchoingBody() {
        try {
            PromptTemplate(id = "t", name = "n", body = "{{Bad}}")
            fail("Accepted malformed placeholder")
        } catch (failure: PromptTemplateException) {
            assertEquals(PromptTemplateException.Reason.MALFORMED_PLACEHOLDER, failure.reason)
        }
        try {
            PromptTemplate(id = "t", name = "n", body = "{{open")
            fail("Accepted unterminated placeholder")
        } catch (failure: PromptTemplateException) {
            assertEquals(PromptTemplateException.Reason.MALFORMED_PLACEHOLDER, failure.reason)
        }
        val template = PromptTemplate(id = "t", name = "n", body = "{{source}} -> {{target}}")
        try {
            template.render(mapOf("source" to "body text"))
            fail("Accepted missing variable")
        } catch (failure: PromptTemplateException) {
            assertEquals(PromptTemplateException.Reason.MISSING_VARIABLE, failure.reason)
            assertEquals("target", failure.variable)
            assertFalse(failure.message.orEmpty().contains("body text"))
        }
        try {
            template.render(mapOf("source" to "x".repeat(PromptTemplate.MAX_VARIABLE_CHARS + 1), "target" to "y"))
            fail("Accepted oversized variable")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("A", PromptTemplate(id = "t", name = "n", body = "A").render(mapOf("unused" to "value")))
    }

    @Test
    fun renderedOutputIsBoundedEvenWhenInputsAreIndividuallyValid() {
        val body = "x" + "{{a}}".repeat(16)
        val template = PromptTemplate(id = "t", name = "n", body = body)
        try {
            template.render(mapOf("a" to "y".repeat(PromptTemplate.MAX_VARIABLE_CHARS)))
            fail("Accepted oversized rendered prompt")
        } catch (failure: PromptTemplateException) {
            assertEquals(PromptTemplateException.Reason.LIMIT, failure.reason)
        }
    }

    @Test
    fun templateSetOrdersByOrderThenIdentityAndFiltersByEnableAndLanguage() {
        val later = PromptTemplate(
            id = "z",
            name = "Z",
            order = 2,
            languages = listOf(PromptLanguageTag.of("EN")),
            body = "z",
        )
        val japanese = PromptTemplate(
            id = "a",
            name = "A",
            order = 1,
            languages = listOf(PromptLanguageTag.of("JA")),
            body = "a",
        )
        val disabled = PromptTemplate(id = "b", name = "B", enabled = false, order = 0, body = "b")
        val set = PromptTemplateSet(listOf(later, japanese, disabled))
        assertEquals(listOf("b", "a", "z"), set.ordered().map { it.id })
        assertEquals(listOf("a"), set.enabledFor(PromptLanguageTag.of("ja")).map { it.id })
        assertEquals(listOf("z"), set.enabledFor(PromptLanguageTag.of("en")).map { it.id })
        assertTrue(set.enabledFor(PromptLanguageTag.of("de")).isEmpty())
        assertEquals("ja", PromptLanguageTag.of(" JA ").value)
    }
}
