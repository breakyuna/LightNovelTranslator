package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Interceptor-only fixtures; no DNS, sockets or billable requests. */
class CompatibleTranslationModelTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val configuration = ModelConfigurationRef("remote", 7)
    private val identity = RunIdentity(ref.projectId, null, RunId("run"), CommandId("command"))
    private fun request() = TranslationModelRequest(ref, identity, RequestAttemptId("attempt"), configuration,
        listOf(TranslationInput(ItemId(1), "source", ObjectFingerprint("a".repeat(64)))), RemoteTranslationOptions("en"))
    private val settings = object : NativeModelSettingsPort {
        override suspend fun current() = NativeModelSettings(configuration, "http://localhost:1234/v1", "fixture", false)
        override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings { require(reference == configuration); return current() }
        override suspend fun credential(reference: ModelConfigurationRef): String { require(reference == configuration); return "" }
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("unused")
    }
    private class Audit : TranslationRequestAuditPort {
        var sent = 0; var received = 0; var usage: RequestUsage? = null; var failure: ModelFailureKind? = null
        override suspend fun dispatched(lease: RunLease, request: TranslationModelRequest, digest: String): TranslationAuditHandle {
            check(sent++ == 0)
            return TranslationAuditHandle(request.attempt, request.ref, request.run, digest)
        }
        override suspend fun received(handle: TranslationAuditHandle, usage: RequestUsage, failure: ModelFailureKind?) {
            received++; this.usage = usage; this.failure = failure
        }
    }
    private fun payload(valid: Boolean): String = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "stop")
            put("message", buildJsonObject { put("content", if (!valid) "invalid output" else buildJsonObject {
                put("request_id", "attempt"); put("items", buildJsonArray { add(buildJsonObject {
                    put("id", "1"); put("item_fingerprint", "a".repeat(64)); put("text", "translated")
                }) })
            }.toString()) })
        }) })
        put("usage", buildJsonObject { put("prompt_tokens", 30); put("completion_tokens", 4) })
    }.toString()

    @Test fun badContentStillRecordsKnownUsageAndNeverRetries() = runBlocking {
        val runtime = RuntimeCoordinator(); val audit = Audit(); var calls = 0
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls++; assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload(false).toResponseBody("application/json".toMediaType())).build()
        }.build())
        try {
            val gateway = NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = CompatibleTranslationModel(settings, runtime, audit, transport))
            try { gateway.complete(lease, request()); fail("Accepted malformed translation") }
            catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, error.kind) }
            assertEquals(1, calls); assertEquals(1, audit.sent); assertEquals(1, audit.received)
            assertEquals(30L, audit.usage!!.input.tokens); assertEquals(ModelFailureKind.PARSING, audit.failure)
        } finally { runtime.finishRun(lease); runtime.close() }
    }

    @Test fun explicitStopAfterHttpResponseRecordsUsageButDiscardsTranslation() = runBlocking {
        val runtime = RuntimeCoordinator(); val audit = Audit()
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            runtime.requestStop(lease)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload(true).toResponseBody("application/json".toMediaType())).build()
        }.build())
        try {
            val gateway = NativeModelGateway(runtime, LocalSimulationModel(), remoteTranslation = CompatibleTranslationModel(settings, runtime, audit, transport))
            try { gateway.complete(lease, request()); fail("Accepted result after Stop") }
            catch (_: RuntimeUnavailable) { }
            assertEquals(1, audit.received); assertEquals(30L, audit.usage!!.input.tokens)
            assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        } finally { runtime.finishRun(lease); runtime.close() }
    }

    @Test fun stoppingInDispatchCheckpointDoesNotReachAuditOrHttp() = runBlocking {
        val runtime = RuntimeCoordinator(); val audit = Audit()
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        try {
            val gateway = NativeModelGateway(runtime, LocalSimulationModel(),
                remoteTranslation = CompatibleTranslationModel(settings, runtime, audit))
            try { gateway.completePrepared(lease, request()) { runtime.requestStop(lease) }; fail("Passed stopped dispatch") }
            catch (_: RuntimeUnavailable) { }
            assertEquals(0, audit.sent); assertEquals(0, audit.received)
        } finally { runtime.finishRun(lease); runtime.close() }
    }

    @Test fun cancellationWhileClosingACompleteResponseStillPersistsUsage() = runBlocking {
        val runtime = RuntimeCoordinator(); val audit = Audit()
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val owner = SupervisorJob()
        lateinit var job: Deferred<TranslationModelResponse>
        val body = object : ResponseBody() {
            private val input = object : ForwardingSource(Buffer().writeUtf8(payload(true))) {
                override fun close() { job.cancel(); super.close() }
            }.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = input
        }
        val transport = CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build())
        try {
            val gateway = NativeModelGateway(runtime, LocalSimulationModel(),
                remoteTranslation = CompatibleTranslationModel(settings, runtime, audit, transport))
            job = CoroutineScope(owner + Dispatchers.Default).async(start = CoroutineStart.LAZY) { gateway.complete(lease, request()) }
            job.start()
            try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            job.join()
            assertEquals(1, audit.received); assertEquals(30L, audit.usage!!.input.tokens)
            assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        } finally { owner.cancelAndJoin(); runtime.finishRun(lease); runtime.close() }
    }
}
