package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelHttpProxy
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeModelNetworkSettingsStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After fun cleanup() = clear()

    private fun clear() {
        File(context.filesDir, "native_model_network_settings.json").delete()
        File(context.filesDir, "native_model_network_settings.json.bak").delete()
    }

    private fun configured() = ModelNetworkSettings(
        connectTimeoutSeconds = 5,
        readTimeoutSeconds = 30,
        writeTimeoutSeconds = 7,
        callTimeoutSeconds = 45,
        proxy = ModelHttpProxy.of("proxy.local", 3128),
        followRedirects = true,
    )

    @Test fun missingFileIsTheExplicitDefaultNotACorruptState() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        assertEquals(ModelNetworkSettings.DEFAULT, store.load())
        assertNull(store.fingerprint())
        assertEquals(ModelNetworkSettings.DEFAULT, store.networkSettings())
    }

    @Test fun roundTripMarksSavedAndReopenKeepsThePolicy() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        val saved = store.save(configured())
        assertEquals(ModelNetworkSettingsSource.SAVED, saved.source)
        assertEquals(saved, store.load())
        assertEquals(saved, NativeModelNetworkSettingsStore(context).load())
        assertEquals(ModelNetworkSettingsCodec.fingerprint(saved), store.fingerprint())
        val text = File(context.filesDir, "native_model_network_settings.json").readText()
        assertTrue(text.contains("proxy.local"))
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("credential"))
        assertFalse(text.contains("password"))
        assertTrue(text.contains("\"source\":\"SAVED\""))
    }

    @Test fun proxyCredentialReferencePersistsWithoutAnyPasswordField() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        val saved = store.save(configured().copy(proxyCredentialRef = "proxy-ref"))
        assertEquals(saved, store.load())
        val text = File(context.filesDir, "native_model_network_settings.json").readText()
        assertTrue(text.contains("\"proxyCredentialRef\":\"proxy-ref\""))
        assertFalse(text.contains("password"))
        assertFalse(text.contains("proxy-password"))
        assertFalse(saved.toString().contains("proxy-ref"))
    }

    @Test fun staleFingerprintCannotOverwriteANewerPolicy() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        val first = store.save(configured())
        val firstFingerprint = requireNotNull(store.fingerprint())
        val second = store.save(ModelNetworkSettings(readTimeoutSeconds = 60), firstFingerprint)
        assertNull(second.proxy)
        assertEquals(60, store.load().readTimeoutSeconds)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(ModelNetworkSettings(readTimeoutSeconds = 20), firstFingerprint) }
        }
        assertEquals(60, store.load().readTimeoutSeconds)
        assertTrue(first != second)
    }

    @Test fun corruptUnknownOrTamperedFilesFailClosed() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        store.save(configured())
        val file = File(context.filesDir, "native_model_network_settings.json")
        val original = file.readText()
        for (content in listOf(
            "broken",
            original.dropLast(1) + ",\"extra\":true}",
            original.replace("\"revision\":1", "\"revision\":0"),
            original.replace("\"revision\":1", "\"revision\":1.0"),
            original.replace("\"fingerprint\":\"", "\"fingerprint\":\"0"),
            original.replace("\"source\":\"SAVED\"", "\"source\":\"DEFAULT\""),
            original.replace("\"source\":\"SAVED\"", "\"source\":\"OTHER\""),
        )) {
            file.writeText(content)
            assertThrows(InvalidModelNetworkSettings::class.java) { runBlocking { store.load() } }
            assertThrows(InvalidModelNetworkSettings::class.java) {
                runBlocking { store.save(ModelNetworkSettings(readTimeoutSeconds = 20), store.fingerprint()) }
            }
            assertFalse(file.readText().contains("readTimeoutSeconds\":20"))
        }
        file.writeText(original)
        assertEquals(30, store.load().readTimeoutSeconds)
    }

    @Test fun corruptStateIsNotSilentlyResetByTheTransportFacingProviderMethod() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        File(context.filesDir, "native_model_network_settings.json").writeText("broken")
        assertThrows(InvalidModelNetworkSettings::class.java) { runBlocking { store.networkSettings() } }
        assertThrows(InvalidModelNetworkSettings::class.java) { runBlocking { store.fingerprint() } }
        Unit
    }

    @Test fun firstSaveWithAnExpectedFingerprintIsRejected() = runBlocking {
        val store = NativeModelNetworkSettingsStore(context)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(configured(), "0".repeat(64)) }
        }
        assertEquals(ModelNetworkSettings.DEFAULT, store.load())
    }
}
