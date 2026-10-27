package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeLaboratorySettingsStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After fun cleanup() = clear()

    private fun clear() {
        File(context.filesDir, "native-laboratory-settings.json").delete()
        File(context.filesDir, "native-laboratory-settings.json.bak").delete()
    }

    @Test fun missingStateIsDefaultAndRoundTripSurvivesReopen() = runBlocking {
        val store = NativeLaboratorySettingsStore(context)
        assertEquals(LaboratorySettings.DEFAULT, store.load())
        assertNull(store.fingerprint())
        val saved = store.save(LaboratorySettings(cleanRuby = true, promptEnhancement = true,
            duplicateSourceDeduplication = true))
        assertEquals(saved, NativeLaboratorySettingsStore(context).load())
    }

    @Test fun staleFingerprintCannotOverwriteAndCorruptFileFailsClosed() = runBlocking {
        val store = NativeLaboratorySettingsStore(context)
        store.save(LaboratorySettings(cleanRuby = true))
        val stale = store.fingerprint()
        store.save(LaboratorySettings(promptEnhancement = true), stale)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(LaboratorySettings(cleanRuby = false), stale) }
        }
        File(context.filesDir, "native-laboratory-settings.json").writeText("not-json")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { NativeLaboratorySettingsStore(context).load() }
        }
        Unit
    }
}
