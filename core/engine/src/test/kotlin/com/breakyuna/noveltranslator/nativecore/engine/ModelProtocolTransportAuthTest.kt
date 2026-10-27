package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Header matrix for API-key, OAuth access tokens and explicit no-auth. */
class ModelProtocolTransportAuthTest {
    private val transport = ModelProtocolTransport()
    private val request = ModelProtocolRequest(
        "fixture",
        null,
        listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")),
    )

    private fun plan(protocol: ModelProtocol, credential: String, authMode: ModelAuthMode) =
        transport.plan(protocol, "https://example.com", "fixture", credential, request,
            authMode = authMode)

    @Test fun oauthUsesBearerForEveryProtocolWithoutApiKeyHeaders() {
        for (protocol in ModelProtocol.entries) {
            val plan = plan(protocol, "oauth-access", ModelAuthMode.OAUTH)
            assertEquals("Bearer oauth-access", plan.headers["Authorization"])
            assertNull(plan.headers["x-api-key"])
            assertNull(plan.headers["x-goog-api-key"])
            assertFalse(plan.toString().contains("oauth-access"))
        }
        assertEquals("2023-06-01",
            plan(ModelProtocol.ANTHROPIC_MESSAGES, "oauth-access", ModelAuthMode.OAUTH)
                .headers["anthropic-version"])
    }

    @Test fun apiKeyKeepsTheHistoricalProtocolHeaders() {
        val chat = plan(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "api-key", ModelAuthMode.API_KEY)
        assertEquals("Bearer api-key", chat.headers["Authorization"])

        val responses = plan(ModelProtocol.OPENAI_RESPONSES, "api-key", ModelAuthMode.API_KEY)
        assertEquals("Bearer api-key", responses.headers["Authorization"])

        val anthropic = plan(ModelProtocol.ANTHROPIC_MESSAGES, "api-key", ModelAuthMode.API_KEY)
        assertEquals("api-key", anthropic.headers["x-api-key"])
        assertNull(anthropic.headers["Authorization"])

        val gemini = plan(ModelProtocol.GOOGLE_GEMINI, "api-key", ModelAuthMode.API_KEY)
        assertEquals("api-key", gemini.headers["x-goog-api-key"])
        assertNull(gemini.headers["Authorization"])

        val sakura = plan(ModelProtocol.SAKURA_LLM, "api-key", ModelAuthMode.API_KEY)
        assertEquals("Bearer api-key", sakura.headers["Authorization"])

        val empty = plan(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "", ModelAuthMode.API_KEY)
        assertNull(empty.headers["Authorization"])
    }

    @Test fun noneSendsNoAuthenticationHeaderForAnyProtocol() {
        for (protocol in ModelProtocol.entries) {
            val plan = plan(protocol, "", ModelAuthMode.NONE)
            assertNull(plan.headers["Authorization"])
            assertNull(plan.headers["x-api-key"])
            assertNull(plan.headers["x-goog-api-key"])
        }
        val suppressed = plan(ModelProtocol.ANTHROPIC_MESSAGES, "ignored", ModelAuthMode.NONE)
        assertNull(suppressed.headers["Authorization"])
        assertNull(suppressed.headers["x-api-key"])
    }

    @Test fun requestBuilderAppliesTheSameMode() {
        val client = CompatibleHttpTransport()
        val oauth = client.request("https://example.com/v1/models", "oauth-access",
            ModelAuthMode.OAUTH).build()
        assertEquals("Bearer oauth-access", oauth.header("Authorization"))

        val apiKey = client.request("https://example.com/v1/models", "api-key",
            ModelAuthMode.API_KEY).build()
        assertEquals("Bearer api-key", apiKey.header("Authorization"))

        val none = client.request("https://example.com/v1/models", "ignored",
            ModelAuthMode.NONE).build()
        assertNull(none.header("Authorization"))
    }
}
