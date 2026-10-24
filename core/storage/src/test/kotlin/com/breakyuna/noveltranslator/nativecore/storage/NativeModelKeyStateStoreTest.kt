package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeModelKeyStateStoreTest {
    private lateinit var context: Context
    private val ref = ModelConfigurationRef("model", 3)
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); clear() }
    @After fun clear() { File(context.filesDir, "native_model_key_states").deleteRecursively() }

    @Test fun reopenRetainsDisabledAndCoolingKeysAndRotationBoundToRevision() = runBlocking {
        val store = NativeModelKeyStateStore(context)
        assertNull(store.read(ref))
        store.write(ModelKeyPoolState(ref, 1, listOf(
            ModelKeyState(false, 1, 0, ModelKeyFailure.AUTHENTICATION),
            ModelKeyState(true, 2, 123456, ModelKeyFailure.RATE_LIMIT))))
        val reopened = requireNotNull(NativeModelKeyStateStore(context).read(ref))
        assertEquals(1, reopened.nextIndex); assertFalse(reopened.keys.first().enabled)
        assertEquals(123456L, reopened.keys.last().cooldownUntilMillis)
        assertNull(store.read(ModelConfigurationRef("model", 4)))
        val file = requireNotNull(File(context.filesDir, "native_model_key_states").listFiles()).single()
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertFalse(file.readText().contains("credential"))
    }

    @Test fun corruptOrUnexpectedFieldsDoNotResetThePool() = runBlocking {
        val store = NativeModelKeyStateStore(context)
        store.write(ModelKeyPoolState(ref, 0, listOf(ModelKeyState(false, 1, 0, ModelKeyFailure.AUTHENTICATION))))
        val file = requireNotNull(File(context.filesDir, "native_model_key_states").listFiles()).single()
        val original = file.readText()
        for (content in listOf("broken", original.dropLast(1) + ",\"unexpected\":true}",
            original.replace("\"revision\":3", "\"revision\":3.0"),
            original.replace("\"next\":0", "\"next\":100"))) {
            file.writeText(content)
            try { store.read(ref); fail("Reset corrupt state") } catch (_: IllegalArgumentException) { }
        }
    }
}
