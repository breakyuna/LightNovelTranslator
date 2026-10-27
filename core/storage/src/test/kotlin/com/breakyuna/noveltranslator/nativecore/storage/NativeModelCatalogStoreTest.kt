package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelCatalog
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.ModelDescriptor
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class NativeModelCatalogStoreTest {
    private lateinit var context: Context
    private val endpoint = "http://localhost:1234/v1"
    private val other = "https://example.com/api/v1"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clearFiles()
    }

    @After fun cleanup() = clearFiles()

    private fun clearFiles() {
        for (suffix in listOf("", ".bak", ".new")) {
            File(context.filesDir, "native-model-catalog.json$suffix").delete()
        }
    }

    private fun snapshot(endpoint: String? = null,
        protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
        fetchedAt: Long, models: List<ModelDescriptor> = emptyList()) =
        ModelCatalogSnapshot(endpoint ?: this.endpoint, protocol, fetchedAt, models)

    @Test fun emptySuccessfulFetchReplacesThePreviousList() = runBlocking {
        val store = NativeModelCatalogStore(context)
        store.save(snapshot(fetchedAt = 10, models = listOf(ModelDescriptor("old-model"))))
        val empty = snapshot(fetchedAt = 20)
        store.save(empty)
        val loaded = requireNotNull(NativeModelCatalogStore(context).load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
        assertTrue(loaded.descriptors.isEmpty())
        assertEquals(20L, loaded.fetchedAtMillis)
        assertEquals(empty.fingerprint, loaded.fingerprint)
        assertNotNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
    }

    @Test fun endpointAndProtocolKeysStayIsolated() = runBlocking {
        val store = NativeModelCatalogStore(context)
        val openai = snapshot(fetchedAt = 10, models = listOf(ModelDescriptor("openai")))
        val gemini = snapshot(protocol = NativeModelProtocol.GOOGLE_GEMINI, fetchedAt = 11,
            models = listOf(ModelDescriptor("gemini")))
        val otherOpenai = snapshot(endpoint = other, fetchedAt = 12,
            models = listOf(ModelDescriptor("other")))
        store.save(openai)
        store.save(gemini)
        store.save(otherOpenai)

        assertEquals(listOf("openai"), store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)?.descriptors?.map { it.id })
        assertEquals(listOf("gemini"), store.load(endpoint, NativeModelProtocol.GOOGLE_GEMINI)?.descriptors?.map { it.id })
        assertEquals(listOf("other"), store.load(other, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)?.descriptors?.map { it.id })
        assertEquals(listOf("openai"),
            store.load("$endpoint/", NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)?.descriptors?.map { it.id })

        store.clear(endpoint, NativeModelProtocol.GOOGLE_GEMINI)
        assertNull(store.load(endpoint, NativeModelProtocol.GOOGLE_GEMINI))
        assertNotNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
        assertNotNull(store.load(other, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
    }

    @Test fun corruptTamperedOrOutOfRangeFilesFailClosedWithoutBeingOverwritten() = runBlocking {
        val store = NativeModelCatalogStore(context)
        store.save(snapshot(fetchedAt = 10, models = listOf(ModelDescriptor("model", contextTokens = 100))))
        val file = File(context.filesDir, "native-model-catalog.json")
        val original = file.readText()
        val replacement = snapshot(fetchedAt = 30, models = listOf(ModelDescriptor("replacement")))
        for (content in listOf(
            "broken",
            original.dropLast(1) + ",\"extra\":true}",
            original.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            original.replace("\"fetchedAtMillis\":10", "\"fetchedAtMillis\":1.0"),
            original.replace("\"fetchedAtMillis\":10", "\"fetchedAtMillis\":11"),
            original.replace("\"contextTokens\":100", "\"contextTokens\":0"),
            original.replace("\"fingerprint\":\"", "\"fingerprint\":\"0"),
        )) {
            file.writeText(content)
            assertThrows(InvalidModelCatalog::class.java) {
                runBlocking { store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS) }
            }
            assertThrows(InvalidModelCatalog::class.java) {
                runBlocking { store.save(replacement) }
            }
            assertThrows(InvalidModelCatalog::class.java) { runBlocking { store.clearAll() } }
            assertEquals(content, file.readText())
        }
        file.writeText(original)
        assertEquals(listOf("model"),
            store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)?.descriptors?.map { it.id })
    }

    @Test fun clearingTheLastSnapshotRemovesTheFileAndLoadIsAbsentNotEmpty() = runBlocking {
        val store = NativeModelCatalogStore(context)
        store.save(snapshot(fetchedAt = 10, models = listOf(ModelDescriptor("model"))))
        store.clear(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)
        assertNull(store.load(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS))
        val file = File(context.filesDir, "native-model-catalog.json")
        assertTrue(!file.exists() || file.length() == 0L)
    }
}
