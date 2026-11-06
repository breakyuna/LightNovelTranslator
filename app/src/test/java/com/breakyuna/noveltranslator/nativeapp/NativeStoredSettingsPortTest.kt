package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelNetworkSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRequestLimitsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production port round-trip and stale-editor rejection over the real AtomicFile stores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeStoredSettingsPortTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var port: NativeStoredSettingsPort

    private val networkFile get() = File(context.filesDir, "native_model_network_settings.json")
    private val limitsFile get() = File(context.filesDir, "native_model_request_limits.json")

    @Before fun setup() {
        deleteFiles()
        port = NativeStoredSettingsPort(NativeModelNetworkSettingsStore(context), NativeRequestLimitsStore(context))
    }

    @After fun cleanup() = deleteFiles()

    private fun deleteFiles() {
        for (file in listOf(networkFile, limitsFile)) {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    @Test fun networkSettingsRoundTripAndStaleFingerprintAreRejected() = runBlocking {
        assertNull(port.loadNetworkSettings().fingerprint)
        val input = ModelNetworkSettings(5, 6, 7, 8, followRedirects = true)
        val saved = port.saveNetworkSettings(input, null)
        assertEquals(ModelNetworkSettingsSource.SAVED, saved.settings.source)
        assertEquals(saved, port.loadNetworkSettings())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { port.saveNetworkSettings(ModelNetworkSettings(1, 1, 1, 1), "stale") }
        }
        // The rejected write did not replace the saved policy.
        assertEquals(saved, port.loadNetworkSettings())
    }

    @Test fun requestLimitsRoundTripAndStaleFingerprintAreRejected() = runBlocking {
        assertNull(port.loadRequestLimits().fingerprint)
        val saved = port.saveRequestLimits(ModelRequestLimits(7, 300), null)
        assertEquals(saved, port.loadRequestLimits())
        assertTrue(saved.fingerprint != null)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { port.saveRequestLimits(ModelRequestLimits(2, 0), "stale") }
        }
        assertEquals(saved, port.loadRequestLimits())
    }
}
