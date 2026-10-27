package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogKey
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshotStore
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelEndpoint
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleModelCatalogSnapshotTest {
    private val endpoint = "http://localhost:1234/v1"

    private class Settings(private val endpoint: String = "http://localhost:1234/v1") : NativeModelSettingsPort {
        override suspend fun current() = null
        override suspend fun resolve(reference: ModelConfigurationRef) =
            NativeModelSettings(reference, endpoint, "model")
        override suspend fun credential(reference: ModelConfigurationRef) = "fixture-key"
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("Not used")
    }

    private class MemoryStore : ModelCatalogSnapshotStore {
        val values = mutableMapOf<ModelCatalogKey, ModelCatalogSnapshot>()
        var saves = 0

        override suspend fun load(endpoint: String, protocol: NativeModelProtocol): ModelCatalogSnapshot? =
            values[ModelCatalogKey(NativeModelEndpoint.base(endpoint), protocol)]

        override suspend fun save(snapshot: ModelCatalogSnapshot): ModelCatalogSnapshot {
            saves++
            values[snapshot.key] = snapshot
            return snapshot
        }
    }

    private fun http(body: String, code: Int = 200): CompatibleHttpTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CompatibleHttpTransport(client)
    }

    @Test fun emptySuccessWritesAnEmptyOverrideAndFailuresNeverOverwriteIt() = runBlocking {
        val runtime = RuntimeCoordinator()
        val store = MemoryStore()
        val first = CompatibleModelCatalog(Settings(), runtime,
            http("{\"data\":[{\"id\":\"old-model\"}]}"), snapshots = store, nowMillis = { 10 })
        assertEquals(listOf("old-model"), first.descriptors(endpoint, "").map { it.id })
        assertEquals(1, store.saves)

        val empty = CompatibleModelCatalog(Settings(), runtime,
            http("{\"data\":[]}"), snapshots = store, nowMillis = { 20 })
        assertTrue(empty.snapshot(endpoint, "", null, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .descriptors.isEmpty())
        val emptySnapshot = requireNotNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
        assertTrue(emptySnapshot.descriptors.isEmpty())
        assertEquals(20L, emptySnapshot.fetchedAtMillis)
        assertEquals(2, store.saves)

        val failed = CompatibleModelCatalog(Settings(), runtime,
            http("{\"data\":[{\"id\":\"new-model\"}]}", 500), snapshots = store, nowMillis = { 30 })
        assertThrows(AgentModelFailure::class.java) {
            runBlocking { failed.snapshot(endpoint, "", null, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS) }
        }
        val malformed = CompatibleModelCatalog(Settings(), runtime,
            http("{\"models\":[]}"), snapshots = store, nowMillis = { 40 })
        assertThrows(AgentModelFailure::class.java) {
            runBlocking { malformed.snapshot(endpoint, "", null, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS) }
        }
        assertEquals(2, store.saves)
        assertEquals(emptySnapshot.fingerprint,
            requireNotNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)).fingerprint)
        assertEquals(emptySnapshot.fingerprint, failed.cached(endpoint)?.fingerprint)
        runtime.close()
    }

    @Test fun idsAndMetadataAreSavedPerProtocolWithoutCrossContamination() = runBlocking {
        val runtime = RuntimeCoordinator()
        val store = MemoryStore()
        val openai = CompatibleModelCatalog(Settings(), runtime,
            http("{\"data\":[{\"id\":\"z\",\"name\":\"Zed\"},{\"id\":\"a\"}]}"),
            snapshots = store, nowMillis = { 10 })
        val openaiDescriptors = openai.descriptors(endpoint, "")
        assertEquals(listOf("a", "z"), openaiDescriptors.map { it.id })
        assertEquals("Zed", openaiDescriptors.single { it.id == "z" }.displayName)
        assertNull(openaiDescriptors.single { it.id == "z" }.contextTokens)

        val gemini = CompatibleModelCatalog(Settings(), runtime,
            http("{\"models\":[{\"name\":\"models/gemini-x\",\"displayName\":\"Gemini X\"," +
                "\"inputTokenLimit\":1048576,\"outputTokenLimit\":8192," +
                "\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]}]}"),
            snapshots = store, nowMillis = { 11 })
        val geminiDescriptor = gemini.descriptors(endpoint, "", null,
            NativeModelProtocol.GOOGLE_GEMINI).single()
        assertEquals("gemini-x", geminiDescriptor.id)
        assertEquals("Gemini X", geminiDescriptor.displayName)
        assertEquals(1048576, geminiDescriptor.contextTokens)
        assertEquals(8192, geminiDescriptor.outputTokens)
        assertEquals(listOf("generateContent", "countTokens"), geminiDescriptor.generationMethods)
        assertNull(geminiDescriptor.modalities)

        assertEquals(2, store.values.size)
        assertNotNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
        assertNotNull(store.load(endpoint, NativeModelProtocol.GOOGLE_GEMINI))
        runtime.close()
    }

    @Test fun nonGeminiMetadataNeverGuessCapabilitiesAndLegacyModelsDoesNotWrite() = runBlocking {
        val runtime = RuntimeCoordinator()
        val store = MemoryStore()
        val catalog = CompatibleModelCatalog(Settings(), runtime,
            http("{\"data\":[{\"id\":\"m\",\"name\":\"Model\",\"context_length\":999999," +
                "\"architecture\":{\"modality\":\"text\"},\"outputTokenLimit\":123}]}"),
            snapshots = store, nowMillis = { 10 })
        val descriptor = catalog.descriptors(endpoint, "").single()
        assertEquals("Model", descriptor.displayName)
        assertNull(descriptor.contextTokens)
        assertNull(descriptor.outputTokens)
        assertNull(descriptor.modalities)
        assertNull(descriptor.generationMethods)
        assertEquals(1, store.saves)

        assertEquals(listOf("m"), catalog.models(endpoint, ""))
        assertEquals(1, store.saves)
        runtime.close()
    }

    @Test fun invalidOptionalGeminiMetadataStaysUnknownAndIsStillSaved() = runBlocking {
        val runtime = RuntimeCoordinator()
        val store = MemoryStore()
        val catalog = CompatibleModelCatalog(Settings(), runtime,
            http("{\"models\":[{\"name\":\"models/gemini-x\",\"inputTokenLimit\":\"huge\"}]}"),
            snapshots = store, nowMillis = { 10 })
        assertEquals(listOf("gemini-x"),
            catalog.descriptors(endpoint, "", null, NativeModelProtocol.GOOGLE_GEMINI).map { it.id })
        assertNull(store.load(endpoint, NativeModelProtocol.GOOGLE_GEMINI)?.descriptors?.single()?.contextTokens)
        assertEquals(1, store.saves)
        runtime.close()
    }

    @Test fun malformedGeminiIdentityFailsWithoutSaving() = runBlocking {
        val runtime = RuntimeCoordinator()
        val store = MemoryStore()
        val catalog = CompatibleModelCatalog(Settings(), runtime,
            http("{\"models\":[{\"displayName\":\"no identity\"}]}"),
            snapshots = store, nowMillis = { 10 })
        assertThrows(AgentModelFailure::class.java) {
            runBlocking { catalog.snapshot(endpoint, "", null, NativeModelProtocol.GOOGLE_GEMINI) }
        }
        assertEquals(0, store.saves)
        runtime.close()
    }
}
