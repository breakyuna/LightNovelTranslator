package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Application interceptors return fixtures; these cases never contact a server. */
class CompatibleModelCatalogTest {
    private val ref = ModelConfigurationRef("fixture", 1)
    private class Settings(private val endpoint: String = "http://localhost:1234/v1") : NativeModelSettingsPort {
        var credentialReads = 0
        override suspend fun current() = null
        override suspend fun resolve(reference: ModelConfigurationRef) = NativeModelSettings(reference, endpoint, "model")
        override suspend fun credential(reference: ModelConfigurationRef): String { credentialReads++; return "fixture-key" }
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("Not used")
    }
    private fun http(body: String = "{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"a\"}]}", code: Int = 200,
        inspect: (Request) -> Unit = {}): CompatibleHttpTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CompatibleHttpTransport(client)
    }

    @Test fun explicitCatalogFetchUsesGetOnlyAndOmitsEmptyAuthorization() = runBlocking {
        val runtime = RuntimeCoordinator(); val settings = Settings()
        var observed: Request? = null
        val catalog = CompatibleModelCatalog(settings, runtime, http { observed = it })
        assertEquals(listOf("a", "z"), catalog.models("http://localhost:1234/v1/chat/completions", ""))
        val request = requireNotNull(observed)
        assertEquals("GET", request.method); assertNull(request.body)
        assertEquals("http://localhost:1234/v1/models", request.url.toString()); assertNull(request.header("Authorization"))
        assertEquals(0, settings.credentialReads); assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun savedKeyIsResolvedOnlyAtItsBoundEndpoint() = runBlocking {
        val settings = Settings(); val runtime = RuntimeCoordinator()
        var authorization: String? = null
        val catalog = CompatibleModelCatalog(settings, runtime, http { authorization = it.header("Authorization") })
        catalog.models("http://localhost:1234/v1/", "", ref)
        assertEquals(1, settings.credentialReads); assertEquals("Bearer fixture-key", authorization)
    }

    @Test fun discoveryPreservesProxyPrefixAndChecksTheExactStoredBase() = runBlocking {
        val base = "https://example.com/api/models"
        val settings = Settings(base); val runtime = RuntimeCoordinator()
        var observed: Request? = null
        val catalog = CompatibleModelCatalog(settings, runtime, http { observed = it })
        catalog.models("$base/chat/completions", "", ref)
        assertEquals("$base/models", requireNotNull(observed).url.toString())
        assertEquals(1, settings.credentialReads)
        observed = null
        try { catalog.models("https://example.com/api/chat/completions", "", ref); fail() }
        catch (_: InvalidModelSettings) { }
        assertNull(observed); assertEquals(1, settings.credentialReads)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun endpointChangeCannotForwardStoredCredential() = runBlocking {
        val settings = Settings(); val runtime = RuntimeCoordinator(); var calls = 0
        val catalog = CompatibleModelCatalog(settings, runtime, http { calls++ })
        try { catalog.models("http://192.168.1.2:1234/v1", "", ref); fail() }
        catch (_: InvalidModelSettings) { }
        assertEquals(0, settings.credentialReads); assertEquals(0, calls)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun authFailureDoesNotExposeResponseBodyOrKeyAndReleasesReservation() = runBlocking {
        val runtime = RuntimeCoordinator()
        val catalog = CompatibleModelCatalog(Settings(), runtime, http("fixture-private-body", 401))
        try { catalog.models("http://localhost:1234/v1", "fixture-key"); fail() }
        catch (error: AgentModelFailure) {
            assertEquals(ModelFailureKind.AUTHENTICATION, error.kind)
            assertFalse(error.toString().contains("fixture-private-body")); assertFalse(error.toString().contains("fixture-key"))
        }
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun malformedOrOversizedModelListsFailWithoutInventedIds() {
        val catalog = CompatibleModelCatalog(Settings(), RuntimeCoordinator())
        for (body in listOf("{\"models\":[]}", "{\"data\":[{\"id\":1}]}", "{\"data\":[{\"id\":\"\"}]}")) {
            assertThrows(AgentModelFailure::class.java) { catalog.parse(body) }
        }
        val excessive = "{\"data\":[" + (1..1001).joinToString(",") { "{\"id\":\"model-$it\"}" } + "]}"
        assertThrows(AgentModelFailure::class.java) { catalog.parse(excessive) }
        assertTrue(catalog.parse("{\"data\":[]}").isEmpty())
    }

    @Test fun activeRunPreventsConcurrentModelDiscovery() = runBlocking {
        val runtime = RuntimeCoordinator(); val project = ProjectId("project"); val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project, chat))
        val lease = runtime.beginRun(RunIdentity(project, chat, RunId("run"), CommandId("send")), RuntimeOwner.AGENT)
        try {
            var calls = 0
            val catalog = CompatibleModelCatalog(Settings(), runtime, http { calls++ })
            try { catalog.models("http://localhost:1234/v1", ""); fail() } catch (_: RuntimeUnavailable) { }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }
}
