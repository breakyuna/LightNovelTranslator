package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
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

    @Test fun endpointChangeCannotImplicitlyReuseEvenAnEmptyStoredCredential() = runBlocking {
        val first = store.save("http://localhost:1234/v1", "one", "")
        try { store.save("http://192.168.1.2:1234/v1", "two", null); fail() } catch (_: InvalidModelSettings) { }
        assertEquals(first.reference, store.current()!!.reference)
        assertFalse(runtime.snapshot().authenticationWriteRunning)
        val explicit = store.save("http://192.168.1.2:1234/v1", "two", "")
        assertFalse(explicit.hasCredential)
    }
}
