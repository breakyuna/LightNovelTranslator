package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PromptTemplateCodecTest {
    @Test fun collectionPreservesMetadataAndCanonicalOrderAndLanguageSelection() {
        val disabled = PromptTemplate("disabled", "Disabled", "Description", false,
            listOf(PromptLanguageTag.of("EN-us")), 5, "Translate {{source_language}}")
        val active = PromptTemplate("active", "Active", languages = listOf(PromptLanguageTag.of("ja")), order = 1, body = "Prompt")
        val set = PromptTemplateSet(listOf(disabled, active))
        val restored = PromptTemplateCodec.decode(PromptTemplateCodec.encode(set))
        assertEquals(listOf("active", "disabled"), restored.ordered().map { it.id })
        val entry = restored.ordered().last()
        assertEquals(disabled.description, entry.description)
        assertEquals(disabled.body, entry.body)
        assertFalse(entry.enabled)
        assertEquals(listOf("en-us"), entry.languages.map { it.value })
        assertEquals(listOf("active"), restored.enabledFor(PromptLanguageTag.of("ja")).map { it.id })
        assertTrue(restored.enabledFor(PromptLanguageTag.of("en-us")).isEmpty())
        assertEquals(PromptTemplateCodec.fingerprint(set), PromptTemplateCodec.fingerprint(restored))
    }
    @Test fun unknownFieldsVersionsAndNonIntegralOrderAreRejected() {
        val text = PromptTemplateCodec.encode(PromptTemplateSet(listOf(PromptTemplate("id", "Name", order = 2, body = "{{value}}"))))
        assertThrows(IllegalArgumentException::class.java) { PromptTemplateCodec.decode(text.replace("\"version\":1", "\"version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { PromptTemplateCodec.decode(text.dropLast(1) + ",\"extra\":true}") }
        assertThrows(IllegalArgumentException::class.java) { PromptTemplateCodec.decode(text.replace("\"order\":2", "\"order\":2.5")) }
        assertThrows(IllegalArgumentException::class.java) { PromptTemplateCodec.decode(text.replace("{{value}}", "{{bad name}}")) }
    }
}
