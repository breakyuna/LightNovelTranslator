package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeAgentModelProfileCodecTest {
    private fun value() = buildJsonObject {
        put("name", "Local"); put("kind", "CUSTOM"); put("purpose", "BOTH"); put("group", "")
        put("enabled", true); put("endpoint", "http://127.0.0.1:1234/v1"); put("model", "local")
        put("protocol", "OPENAI_CHAT_COMPLETIONS"); put("concurrency", 2); put("requests_per_minute", 30)
        put("tools", true); put("vision", false); put("context_tokens", 8192); put("output_tokens", 2048)
        put("temperature", "0.7"); put("generation_output_tokens", 1024)
    }
    @Test fun typedAdvancedSettingsHaveNoCredentialChannel() {
        val draft = NativeAgentModelProfileCodec.decode(value())
        assertTrue(draft.keys.isEmpty())
        assertEquals(2, draft.limits!!.concurrency)
        assertEquals(false, draft.capabilities.vision)
        assertEquals(1024, draft.generation.maxOutputTokens)
        assertEquals(0.7, draft.generation.temperature!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            NativeAgentModelProfileCodec.decode(JsonObject(value() + ("api_key" to JsonPrimitive("synthetic-rejected-field"))))
        }
    }
    @Test fun fractionalIntegerAndUnpairedSchedulingLimitsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeAgentModelProfileCodec.decode(JsonObject(value() + ("concurrency" to JsonPrimitive(1.5))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAgentModelProfileCodec.decode(JsonObject(value() - "requests_per_minute"))
        }
    }
}
