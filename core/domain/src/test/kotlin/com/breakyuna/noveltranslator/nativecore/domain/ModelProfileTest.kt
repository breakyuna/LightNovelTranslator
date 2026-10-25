package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {
    private fun settings() = NativeModelSettings(
        ModelConfigurationRef("profile-a", 1), "https://example.com/v1", "model-a", false,
        NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, 0)

    private fun profile(
        id: String = "profile-a",
        purpose: ModelProfilePurpose = ModelProfilePurpose.BOTH,
        selectedFor: Set<ModelProfilePurpose> = setOf(ModelProfilePurpose.AGENT),
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ) = NativeModelProfile(id, "Profile A", ModelProfileKind.CUSTOM, purpose, "", 0, true,
        settings(), selectedFor = selectedFor, extraHeaders = headers, extraBody = body)

    @Test
    fun `purpose controls which selectors are legal`() {
        assertTrue(ModelProfilePurpose.BOTH.serves(ModelProfilePurpose.AGENT))
        assertTrue(ModelProfilePurpose.BOTH.serves(ModelProfilePurpose.TRANSLATION))
        assertTrue(ModelProfilePurpose.AGENT.serves(ModelProfilePurpose.AGENT))
        assertFalse(ModelProfilePurpose.AGENT.serves(ModelProfilePurpose.TRANSLATION))
        assertEquals(setOf(ModelProfilePurpose.AGENT), profile().selectedFor)
    }

    @Test
    fun `a translation-only profile cannot be selected for agent`() {
        try {
            profile(purpose = ModelProfilePurpose.TRANSLATION, selectedFor = setOf(ModelProfilePurpose.AGENT))
            throw AssertionError("Expected InvalidModelProfile")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `credentials and structural headers are rejected`() {
        for (headers in listOf(
            mapOf("Authorization" to "Bearer secret"),
            mapOf("X-Api-Key" to "secret"),
            mapOf("Content-Type" to "text/plain"),
            mapOf("Bad Header" to "value"),
            mapOf("X-Test" to "line\nbreak"),
        )) {
            try {
                profile(headers = headers)
                throw AssertionError("Expected InvalidModelProfile for $headers")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `extra body must be a JSON object`() {
        profile(body = """{"vendor_flag":true}""")
        for (body in listOf("[]", "not-json", "null")) {
            try {
                NativeModelProfileValidation.validateExtraBody(body)
                throw AssertionError("Expected InvalidModelProfile for $body")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `profile ids are bounded path-free identities`() {
        for (id in listOf("", "../escape", "a/b", "a b", "a".repeat(65))) {
            try {
                NativeModelProfile(id = id, name = "x", kind = ModelProfileKind.CUSTOM,
                    purpose = ModelProfilePurpose.BOTH, group = "", order = 0, enabled = true,
                    settings = settings())
                throw AssertionError("Expected InvalidModelProfile for $id")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
