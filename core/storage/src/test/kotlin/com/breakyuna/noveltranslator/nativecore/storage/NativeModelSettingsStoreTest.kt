package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** No-auth cases intentionally do not require an emulated AndroidKeyStore implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeModelSettingsStoreTest {
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeModelSettingsStore
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("lnt_native_app.db")
        runtime = RuntimeCoordinator(); store = NativeModelSettingsStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }

    @Test fun reviewedProfileSaveCreatesAdvancedSettingsAndRefusesCredentialEndpointReuse() = runBlocking {
        val initial = store.reviewedProfileSnapshot().getValue("fingerprint").jsonPrimitive.content
        val draft = NewModelProfile(name = "Reviewed model", purpose = ModelProfilePurpose.BOTH,
            endpoint = "http://127.0.0.1:1234/v1", model = "local", protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            keys = emptyList(), limits = ModelProfileLimits(2, 30), capabilities = ModelProfileCapabilities(tools = true),
            generation = ModelGenerationSettings(maxOutputTokens = 1024))
        val id = store.saveReviewedProfile(initial, null, draft, false)
        val current = store.find(id)!!
        assertEquals(1024, current.generation.maxOutputTokens)
        assertEquals(2, current.limits!!.concurrency)
        val expected = store.reviewedProfileSnapshot().getValue("fingerprint").jsonPrimitive.content
        try {
            store.saveReviewedProfile(expected, id, draft.copy(endpoint = "http://localhost:4321/v1"), true)
            fail("Moved retained credentials to another endpoint")
        } catch (_: IllegalArgumentException) { }
        assertEquals(current.reference, store.find(id)!!.reference)
        assertEquals(expected, store.reviewedProfileSnapshot().getValue("fingerprint").jsonPrimitive.content)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun reviewedModelMutationUsesCatalogCasAndDoesNotExposeCredentials() = runBlocking {
        val profile = store.create(NewModelProfile(name = "Reviewed local", purpose = ModelProfilePurpose.BOTH,
            endpoint = "http://127.0.0.1:1234/v1", model = "local", protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            keys = emptyList()))
        val snapshot = store.reviewedProfileSnapshot()
        val expected = snapshot.getValue("fingerprint").jsonPrimitive.content
        assertFalse(snapshot.toString().contains("encrypted"))
        assertFalse(snapshot.toString().contains("endpoint"))
        store.applyReviewedProfileMutation(expected, profile.id, "rename", "Reviewed name")
        try {
            store.applyReviewedProfileMutation(expected, profile.id, "delete", null)
            fail("Consumed a stale catalog approval")
        } catch (_: IllegalArgumentException) { }
        assertEquals("Reviewed name", store.find(profile.id)!!.name)
        assertEquals(profile.reference, store.find(profile.id)!!.reference)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
    }

    @Test fun noKeyLocalConfigurationPersistsAndResolvesWithoutAuthorization() = runBlocking {
        val saved = store.save("http://127.0.0.1:1234/v1/chat/completions", " local-model ", "")
        assertFalse(saved.hasCredential); assertEquals("http://127.0.0.1:1234/v1", saved.endpoint)
        store.close(); store = NativeModelSettingsStore(context, runtime)
        assertEquals(saved.reference, store.current()!!.reference)
        assertEquals("local-model", store.resolve(saved.reference).model)
        assertEquals("", store.credential(saved.reference))
    }

    @Test fun sameEndpointCanReuseCredentialModeWithoutChangingOldConfigurationRevision() = runBlocking {
        val first = store.save("http://localhost:1234/v1", "one", "")
        val second = store.save("http://localhost:1234/v1/models", "two", null)
        assertEquals(first.reference.revision + 1, second.reference.revision)
        assertEquals("one", store.resolve(first.reference).model); assertEquals("two", second.model)
    }

    @Test fun proxyPrefixSurvivesPersistenceReopenAndCredentialReuse() = runBlocking {
        val base = "https://example.com/api/models"
        val first = store.save("$base/chat/completions", "one", "")
        store.close(); store = NativeModelSettingsStore(context, runtime)
        val restored = requireNotNull(store.current())
        assertEquals(base, restored.endpoint)
        assertEquals("$base/chat/completions", NativeModelEndpoint.chat(store.resolve(first.reference).endpoint))
        val second = store.save(NativeModelEndpoint.chat(restored.endpoint), "two", null)
        assertEquals(base, second.endpoint)
        assertEquals("$base/models", NativeModelEndpoint.models(second.endpoint))
        assertEquals(first.reference.revision + 1, second.reference.revision)
    }

    @Test fun explicitProtocolAndNoAuthProfileSurviveReopen() = runBlocking {
        val saved = store.saveProfile("https://example.com/v1", "gemini-model",
            NativeModelProtocol.GOOGLE_GEMINI, emptyList())
        assertEquals(NativeModelProtocol.GOOGLE_GEMINI, saved.protocol)
        assertEquals(0, saved.keyCount); assertFalse(saved.hasCredential)
        store.close(); store = NativeModelSettingsStore(context, runtime)
        val restored = requireNotNull(store.current())
        assertEquals(NativeModelProtocol.GOOGLE_GEMINI, restored.protocol)
        assertEquals(0, restored.keyCount)
        assertTrue(store.credentials(restored.reference).isEmpty())
    }

    @Test fun endpointChangeCannotImplicitlyReuseEvenAnEmptyStoredCredential() = runBlocking {
        val first = store.save("http://localhost:1234/v1", "one", "")
        try { store.save("http://192.168.1.2:1234/v1", "two", null); fail() } catch (_: InvalidModelSettings) { }
        assertEquals(first.reference, store.current()!!.reference)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
        val explicit = store.save("http://192.168.1.2:1234/v1", "two", "")
        assertFalse(explicit.hasCredential)
    }
    @Test fun profileCatalogPersistsSelectionCopyMoveAndConfiguration() = runBlocking {
        val first = store.create(NewModelProfile(
            name = "Local", purpose = ModelProfilePurpose.BOTH, endpoint = "http://127.0.0.1:1234/v1",
            model = "one", protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            keys = emptyList(), limits = ModelProfileLimits(2, 60)))
        assertTrue(ModelProfilePurpose.AGENT in first.selectedFor)
        assertTrue(ModelProfilePurpose.TRANSLATION in first.selectedFor)
        val second = store.create(NewModelProfile(
            name = "Second", purpose = ModelProfilePurpose.AGENT, endpoint = "http://127.0.0.1:1234/v1",
            model = "two", protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, keys = emptyList(),
            limits = ModelProfileLimits(2, 60)))
        assertTrue(ModelProfilePurpose.AGENT !in second.selectedFor)
        val copied = store.copy(first.id, "Local copy")
        assertEquals(ModelProfileKind.CUSTOM, copied.kind)
        assertTrue(copied.selectedFor.isEmpty())
        assertEquals(3, store.list().size)
        val moved = store.move(second.id, -1)
        assertEquals(listOf(second.id, first.id, copied.id), moved.map { it.id })
        store.select(second.id, ModelProfilePurpose.AGENT)
        val selected = requireNotNull(store.selected(ModelProfilePurpose.AGENT))
        assertEquals(second.id, selected.id)
        val configured = store.saveConfiguration(second.id, "http://127.0.0.1:1234/v1",
            "three", NativeModelProtocol.OPENAI_RESPONSES, null)
        assertEquals("three", configured.settings.model)
        assertEquals(NativeModelProtocol.OPENAI_RESPONSES, configured.settings.protocol)
        assertEquals(2, configured.limits?.concurrency)
        store.delete(copied.id)
        assertEquals(2, store.list().size)
    }
}
