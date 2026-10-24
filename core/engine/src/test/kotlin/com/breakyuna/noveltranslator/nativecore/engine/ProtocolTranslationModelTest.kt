package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Interceptor-only fixtures; no DNS, sockets or billable requests. */
class ProtocolTranslationModelTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val configuration = ModelConfigurationRef("remote", 7)
    private val identity = RunIdentity(ref.projectId, null, RunId("run"), CommandId("command"))
    private fun request() = TranslationModelRequest(ref, identity, RequestAttemptId("attempt"), configuration,
        listOf(TranslationInput(ItemId(1), "source", ObjectFingerprint("a".repeat(64)))), RemoteTranslationOptions("en"))

    private class Audit : TranslationRequestAuditPort {
        var sent = 0; var received = 0; var usage: RequestUsage? = null; var failure: ModelFailureKind? = null
        override suspend fun dispatched(lease: RunLease, request: TranslationModelRequest, digest: String): TranslationAuditHandle {
            sent++
            return TranslationAuditHandle(request.attempt, request.ref, request.run, digest)
        }
        override suspend fun received(handle: TranslationAuditHandle, usage: RequestUsage, failure: ModelFailureKind?) {
            received++; this.usage = usage; this.failure = failure
        }
    }

    @Test fun anthropicProtocolReusesStrictEnvelopeAndRecordsUsage() = runBlocking {
        val runtime = RuntimeCoordinator(); val audit = Audit(); var calls = 0
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val envelope = buildJsonObject {
            put("request_id", "attempt")
            put("items", buildJsonArray { add(buildJsonObject {
                put("id", "1"); put("item_fingerprint", "a".repeat(64)); put("text", "translated")
            }) })
        }.toString()
        val payload = buildJsonObject {
            put("type", "message"); put("role", "assistant"); put("stop_reason", "end_turn")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", envelope) }) })
            put("usage", buildJsonObject { put("input_tokens", 30); put("output_tokens", 4) })
        }.toString()
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("https://example.com/v1/messages", chain.request().url.toString())
            assertEquals("secret", chain.request().header("x-api-key"))
            assertEquals("2023-06-01", chain.request().header("anthropic-version"))
            val body = chain.request().body?.let { requestBody ->
                val buffer = okio.Buffer(); requestBody.writeTo(buffer); buffer.readUtf8()
            }.orEmpty()
            assertTrue(body.contains("\"system\""))
            assertTrue(body.contains("request_id"))
            assertTrue(body.contains("attempt"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val settings = object : NativeModelSettingsPort {
            override suspend fun current() = NativeModelSettings(configuration, "https://example.com", "fixture",
                hasCredential = true, protocol = NativeModelProtocol.ANTHROPIC_MESSAGES)
            override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings { require(reference == configuration); return current() }
            override suspend fun credential(reference: ModelConfigurationRef): String { require(reference == configuration); return "secret" }
            override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("unused")
        }
        try {
            val gateway = NativeModelGateway(runtime, LocalSimulationModel(),
                remoteTranslation = CompatibleTranslationModel(settings, runtime, audit, transport))
            val result = gateway.complete(lease, request())
            assertEquals("translated", result.outputs.single().text)
            assertEquals(1, calls); assertEquals(1, audit.sent); assertEquals(1, audit.received)
            assertEquals(30L, audit.usage!!.input.tokens); assertEquals(4L, audit.usage!!.output.tokens)
            assertNull(audit.failure)
        } finally { runtime.finishRun(lease); runtime.close() }
    }
}
