package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsCodec
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
class NativeRequestLimitsStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After fun cleanup() = clear()

    private fun clear() {
        File(context.filesDir, "native_model_request_limits.json").delete()
        File(context.filesDir, "native_model_request_limits.json.bak").delete()
    }

    @Test fun missingFileMeansTheHistoricalSchedulerDefaults() = runBlocking {
        val store = NativeRequestLimitsStore(context)
        assertEquals(ModelRequestLimits(4, 0), store.load())
        assertEquals(ModelRequestLimits(4, 0), store.current())
        assertNull(store.fingerprint())
    }

    @Test fun roundTripAndStaleWriterProtectionAreAtomic() = runBlocking {
        val store = NativeRequestLimitsStore(context)
        val first = store.save(ModelRequestLimits(2, 120))
        val firstFingerprint = requireNotNull(store.fingerprint())
        assertEquals(first, store.load())
        assertEquals(first, NativeRequestLimitsStore(context).load())
        assertEquals(ModelRequestLimitsCodec.fingerprint(first), store.fingerprint())
        val second = store.save(ModelRequestLimits(1, 60), firstFingerprint)
        assertEquals(second, store.load())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(ModelRequestLimits(16, 0), firstFingerprint) }
        }
        assertEquals(second, store.load())
        val text = File(context.filesDir, "native_model_request_limits.json").readText()
        assertTrue(text.contains("\"concurrency\":1"))
        assertFalse(text.contains("modelKey"))
        assertFalse(text.contains("apiKey"))
    }

    @Test fun corruptOrTamperedFilesAreRejectedInsteadOfRaisingTheAllowance() = runBlocking {
        val store = NativeRequestLimitsStore(context)
        store.save(ModelRequestLimits(2, 120))
        val file = File(context.filesDir, "native_model_request_limits.json")
        val original = file.readText()
        for (content in listOf(
            "broken",
            original.dropLast(1) + ",\"extra\":true}",
            original.replace("\"revision\":1", "\"revision\":0"),
            original.replace("\"revision\":1", "\"revision\":1.5"),
            original.replace("\"fingerprint\":\"", "\"fingerprint\":\"f"),
        )) {
            file.writeText(content)
            assertThrows(IllegalStateException::class.java) { runBlocking { store.load() } }
            assertThrows(IllegalStateException::class.java) { runBlocking { store.save(ModelRequestLimits(16, 0), store.fingerprint()) } }
            assertFalse(file.readText().contains("\"concurrency\":16"))
        }
        file.writeText(original)
        assertEquals(ModelRequestLimits(2, 120), store.load())
    }

    @Test fun firstSaveWithAnExpectedFingerprintIsRejected() = runBlocking {
        val store = NativeRequestLimitsStore(context)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(ModelRequestLimits(1, 0), "0".repeat(64)) }
        }
        assertEquals(ModelRequestLimits(4, 0), store.load())
    }
}
