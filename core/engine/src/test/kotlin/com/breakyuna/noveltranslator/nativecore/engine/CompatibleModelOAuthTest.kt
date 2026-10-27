package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentMessage
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelRequest
import com.breakyuna.noveltranslator.nativecore.domain.AssistantPart
import com.breakyuna.noveltranslator.nativecore.domain.BranchId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MessageId
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RemoteTranslationOptions
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.TranslationAuditHandle
import com.breakyuna.noveltranslator.nativecore.domain.TranslationInput
import com.breakyuna.noveltranslator.nativecore.domain.TranslationModelRequest
import com.breakyuna.noveltranslator.nativecore.domain.TranslationRequestAuditPort
import com.breakyuna.noveltranslator.nativecore.domain.TranslationModelResponse
import com.breakyuna.noveltranslator.nativecore.domain.UserPart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Injected OAuth credentials must reach all three model call sites without touching the key pool. */
class CompatibleModelOAuthTest {
    private val configuration = ModelConfigurationRef("oauth-fixture", 1)
    private val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("command"))
    private val localRef = LocalProjectRef(ProjectId("project"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val translationIdentity = RunIdentity(localRef.projectId, null, RunId("run"), CommandId("command"))
    private var keyReads = 0

    private inner class Settings(
        private val protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
    ) : NativeModelSettingsPort {
        override suspend fun current(): NativeModelSettings? = null
        override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings =
            NativeModelSettings(reference, "http://localhost:1234/v1", "fixture",
                hasCredential = true, protocol = protocol)
        override suspend fun credential(reference: ModelConfigurationRef): String {
            keyReads++
            return "api-key"
        }
        override suspend fun credentials(reference: ModelConfigurationRef): List<String> {
            keyReads++
            return listOf("api-key")
        }
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings =
            error("Not used")
    }

    private fun oauthResolver(): OAuthCredentialResolver {
        val store = object : OAuthCredentialStore {
            private var current = OAuthCredential.of("oauth-access", "oauth-refresh", Long.MAX_VALUE)
            override suspend fun load(reference: ModelConfigurationRef) = current
            override suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential) {
                current = credential
            }
            override suspend fun clear(reference: ModelConfigurationRef) { current = null }
        }
        val provider = OAuthTokenProvider(store, object : OAuthRefresher {
            override suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential) =
                error("A fresh injected credential must not refresh")
        })
        return OAuthCredentialResolver(provider)
    }

    private fun transport(code: Int, payload: String,
        observe: (Request) -> Unit = {}): CompatibleHttpTransport =
        CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            observe(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build())

    private fun agentRequest() = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"),
        configuration, listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("hello")))),
        emptyList())

    private fun agentPayload(): String = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "stop")
            put("message", buildJsonObject { put("content", "ok") })
        }) })
    }.toString()

    @Test fun agentUsesOAuthPortWithoutSelectingAnApiKey() = runBlocking {
        val runtime = RuntimeCoordinator()
        runtime.selectChat(ChatIdentity(identity.projectId, requireNotNull(identity.chatId)))
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var authorization: String? = null
        var calls = 0
        val model = CompatibleAgentModel(Settings(), runtime,
            transport(200, agentPayload()) { calls++; authorization = it.header("Authorization") },
            credentials = oauthResolver())
        try {
            val response = model.complete(agentRequest()) {}
            assertEquals(1, calls)
            assertEquals("Bearer oauth-access", authorization)
            assertEquals(0, keyReads)
            assertEquals("ok", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
        } finally {
            runtime.finishRun(lease)
            runtime.close()
        }
    }

    @Test fun propagated401IsAuthenticationAndIsNeverReplayed() = runBlocking {
        val runtime = RuntimeCoordinator()
        runtime.selectChat(ChatIdentity(identity.projectId, requireNotNull(identity.chatId)))
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val model = CompatibleAgentModel(Settings(), runtime,
            transport(401, "{\"error\":\"private-body\"}") { calls++ },
            credentials = oauthResolver())
        try {
            try {
                model.complete(agentRequest()) {}
                fail("Replayed or accepted an unauthorized request")
            } catch (failure: AgentModelFailure) {
                assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind)
            }
            assertEquals(1, calls)
            assertEquals(0, keyReads)
        } finally {
            runtime.finishRun(lease)
            runtime.close()
        }
    }

    @Test fun catalogUsesOAuthPortOnlyForSavedConfiguration() = runBlocking {
        val runtime = RuntimeCoordinator()
        var authorization: String? = null
        val catalog = CompatibleModelCatalog(Settings(), runtime,
            transport(200, "{\"data\":[{\"id\":\"fixture-model\"}]}") {
                authorization = it.header("Authorization")
            }, credentials = oauthResolver())
        try {
            assertEquals(listOf("fixture-model"),
                catalog.models("http://localhost:1234/v1", "", configuration))
            assertEquals("Bearer oauth-access", authorization)
            assertEquals(0, keyReads)
        } finally {
            runtime.close()
        }
    }

    @Test fun translationUsesOAuthPortWithoutSelectingAnApiKey() = runBlocking {
        val runtime = RuntimeCoordinator()
        runtime.selectChat(ChatIdentity(localRef.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(translationIdentity, RuntimeOwner.TRANSLATION)
        var authorization: String? = null
        val model = CompatibleTranslationModel(Settings(), runtime, Audit(),
            transport(200, translationPayload()) { authorization = it.header("Authorization") },
            credentials = oauthResolver())
        try {
            val response = model.complete(lease, translationRequest())
            assertEquals("translated", response.outputs.single().text)
            assertEquals("Bearer oauth-access", authorization)
            assertEquals(0, keyReads)
        } finally {
            runtime.finishRun(lease)
            runtime.close()
        }
    }

    @Test fun anthropicAgentOAuthUsesBearerAndNeverSendsApiKey() = runBlocking {
        val runtime = RuntimeCoordinator()
        runtime.selectChat(ChatIdentity(identity.projectId, requireNotNull(identity.chatId)))
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var authorization: String? = null
        var apiKey: String? = null
        val model = CompatibleAgentModel(Settings(NativeModelProtocol.ANTHROPIC_MESSAGES), runtime,
            transport(200, anthropicResponse("ok")) {
                authorization = it.header("Authorization")
                apiKey = it.header("x-api-key")
            }, credentials = oauthResolver())
        try {
            val response = model.complete(agentRequest()) {}
            assertEquals("Bearer oauth-access", authorization)
            assertNull(apiKey)
            assertEquals("ok", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            assertEquals(0, keyReads)
        } finally {
            runtime.finishRun(lease)
            runtime.close()
        }
    }

    @Test fun anthropicTranslationOAuthUsesBearerAndNeverSendsApiKey() = runBlocking {
        val runtime = RuntimeCoordinator()
        runtime.selectChat(ChatIdentity(localRef.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(translationIdentity, RuntimeOwner.TRANSLATION)
        var authorization: String? = null
        var apiKey: String? = null
        val model = CompatibleTranslationModel(Settings(NativeModelProtocol.ANTHROPIC_MESSAGES),
            runtime, Audit(),
            transport(200, anthropicResponse(translationEnvelope())) {
                authorization = it.header("Authorization")
                apiKey = it.header("x-api-key")
            }, credentials = oauthResolver())
        try {
            val response = model.complete(lease, translationRequest())
            assertEquals("translated", response.outputs.single().text)
            assertEquals("Bearer oauth-access", authorization)
            assertNull(apiKey)
            assertEquals(0, keyReads)
        } finally {
            runtime.finishRun(lease)
            runtime.close()
        }
    }

    @Test fun anthropicAndGeminiCatalogOAuthUseBearerWithoutProviderKeyHeaders() = runBlocking {
        val runtime = RuntimeCoordinator()
        var authorization: String? = null
        var apiKey: String? = null
        var googleKey: String? = null
        val anthropic = CompatibleModelCatalog(Settings(NativeModelProtocol.ANTHROPIC_MESSAGES), runtime,
            transport(200, "{\"data\":[{\"id\":\"fixture-model\"}]}") {
                authorization = it.header("Authorization")
                apiKey = it.header("x-api-key")
            }, credentials = oauthResolver())
        try {
            assertEquals(listOf("fixture-model"), anthropic.models("http://localhost:1234/v1", "",
                configuration, NativeModelProtocol.ANTHROPIC_MESSAGES))
            assertEquals("Bearer oauth-access", authorization)
            assertNull(apiKey)
        } finally {
            runtime.close()
        }

        val geminiRuntime = RuntimeCoordinator()
        val gemini = CompatibleModelCatalog(Settings(NativeModelProtocol.GOOGLE_GEMINI), geminiRuntime,
            transport(200, "{\"models\":[{\"name\":\"models/fixture-model\"}]}") {
                authorization = it.header("Authorization")
                googleKey = it.header("x-goog-api-key")
            }, credentials = oauthResolver())
        try {
            assertEquals(listOf("fixture-model"), gemini.models("http://localhost:1234/v1", "",
                configuration, NativeModelProtocol.GOOGLE_GEMINI))
            assertEquals("Bearer oauth-access", authorization)
            assertNull(googleKey)
        } finally {
            geminiRuntime.close()
        }
        assertEquals(0, keyReads)
    }

    private fun anthropicResponse(text: String): String = buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put("stop_reason", "end_turn")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
        put("usage", buildJsonObject { put("input_tokens", 1); put("output_tokens", 1) })
    }.toString()

    private fun translationEnvelope(): String = buildJsonObject {
        put("request_id", "attempt")
        put("items", buildJsonArray { add(buildJsonObject {
            put("id", "1")
            put("item_fingerprint", "a".repeat(64))
            put("text", "translated")
        }) })
    }.toString()

    private fun translationRequest() = TranslationModelRequest(localRef, translationIdentity,
        RequestAttemptId("attempt"), configuration,
        listOf(TranslationInput(ItemId(1), "source", ObjectFingerprint("a".repeat(64)))),
        RemoteTranslationOptions("en"))

    private fun translationPayload(): String {
        val content = buildJsonObject {
            put("request_id", "attempt")
            put("items", buildJsonArray { add(buildJsonObject {
                put("id", "1")
                put("item_fingerprint", "a".repeat(64))
                put("text", "translated")
            }) })
        }.toString()
        return buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", content) })
            }) })
        }.toString()
    }

    private class Audit : TranslationRequestAuditPort {
        override suspend fun dispatched(lease: RunLease, request: TranslationModelRequest,
            digest: String): TranslationAuditHandle =
            TranslationAuditHandle(request.attempt, request.ref, request.run, digest)
        override suspend fun received(handle: TranslationAuditHandle,
            usage: com.breakyuna.noveltranslator.nativecore.domain.RequestUsage,
            failure: ModelFailureKind?) = Unit
        override suspend fun receivedResponse(handle: TranslationAuditHandle,
            response: TranslationModelResponse) = Unit
    }
}
