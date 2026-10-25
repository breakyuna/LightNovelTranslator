package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelGenerationSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileCapabilities
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfilePurpose
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaboratoryPipelineTest {
    @Test
    fun `conservative ruby cleaning keeps script syntax untouched`() {
        assertEquals("漢字", RubyCleaner.cleanConservative("<ruby>漢字<rt>かんじ</rt></ruby>"))
        assertEquals("漢字", RubyCleaner.cleanConservative("[ch_漢字]"))
        assertEquals("漢字", RubyCleaner.cleanConservative("\\r[漢字,かんじ]"))
        val script = "\\c[2]Text (かな/kanji)"
        assertEquals(script, RubyCleaner.cleanConservative(script))
    }

    @Test
    fun `aggressive rules stay disabled for script formats`() {
        assertEquals("(漢字/かんじ)", RubyCleaner.clean("(漢字/かんじ)", "RENPY"))
        assertEquals("漢字", RubyCleaner.clean("(漢字/かんじ)", "NONE"))
    }

    @Test
    fun `prompt enhancement is bounded`() {
        assertEquals(LaboratoryPromptEnhancement.INSTRUCTION, LaboratoryPromptEnhancement.apply(""))
        assertTrue(LaboratoryPromptEnhancement.apply("Keep names")
            .startsWith("Keep names\n\n"))
        try {
            LaboratoryPromptEnhancement.apply("x".repeat(LaboratoryPromptEnhancement.MAX_PROJECT_INSTRUCTIONS))
            throw AssertionError("Expected rejection")
        } catch (_: Exception) {
            // Expected.
        }
    }
}

class ModelProfileRuntimeTest {
    private fun profile(generation: ModelGenerationSettings = ModelGenerationSettings.DEFAULT,
        extraBody: String? = null,
        tools: Boolean? = null) = NativeModelProfile(
        "profile-a", "A", ModelProfileKind.CUSTOM, ModelProfilePurpose.BOTH, "", 0, true,
        NativeModelSettings(ModelConfigurationRef("profile-a", 1), "https://example.com", "model-a", false),
        capabilities = ModelProfileCapabilities(tools = tools), generation = generation, extraBody = extraBody)

    @Test
    fun `chat overrides merge generation and extra body`() {
        val body = applyProfileRequestOverrides("""{"model":"m","messages":[]}""",
            profile(ModelGenerationSettings(temperature = 0.2, topP = 0.9, maxOutputTokens = 100),
                """{"vendor_flag":true}"""), ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("0.2", json.getValue("temperature").toString())
        assertEquals("100", json.getValue("max_tokens").toString())
        assertEquals("true", json.getValue("vendor_flag").toString())
    }

    @Test
    fun `structural extra body fields are rejected`() {
        for (body in listOf("""{"messages":[]}""", """{"model":"x"}""", """{"tools":[]}""")) {
            try {
                applyProfileRequestOverrides("""{"model":"m"}""", profile(extraBody = body),
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS)
                throw AssertionError("Expected rejection for $body")
            } catch (_: Exception) {
                // Expected.
            }
        }
    }

    @Test
    fun `protocol system prompts cannot be replaced by extra body`() {
        for ((protocol, key) in listOf(
            ModelProtocol.OPENAI_RESPONSES to "instructions",
            ModelProtocol.GOOGLE_GEMINI to "systemInstruction",
        )) {
            try {
                applyProfileRequestOverrides(
                    """{"$key":"generated instructions"}""",
                    profile(extraBody = """{"$key":"replacement instructions"}"""), protocol)
                throw AssertionError("Expected rejection for $protocol system prompt")
            } catch (failure: com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure) {
                assertEquals(com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind.CONTENT,
                    failure.kind)
            }
        }
    }

    @Test
    fun `explicit false capability maps to unsupported`() {
        val catalog = profile(tools = false).capabilityCatalog(ModelProtocol.OPENAI_RESPONSES)
        assertEquals(CapabilitySupport.UNSUPPORTED, catalog.describe(ModelProtocol.OPENAI_RESPONSES, "model-a").tools)
        assertFalse(catalog.validate(ModelProtocol.OPENAI_RESPONSES, "model-a",
            CapabilityRequirement(tools = true)) is CapabilityDecision.Supported)
    }
}
