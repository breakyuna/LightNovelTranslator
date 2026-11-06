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
class NativePromptTemplateStoreTest {
    private lateinit var context: Context
    private lateinit var store: NativePromptTemplateStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clear(); store = NativePromptTemplateStore(context)
    }
    @After fun clear() {
        for (suffix in listOf("", ".bak", ".new")) File(context.filesDir, "native_prompt_templates.json$suffix").delete()
    }
    @Test fun savedLibraryReopensAndRejectsStaleReplacementWithoutChangingItsContents() = runBlocking {
        val empty = store.read()
        assertTrue(empty.templates.templates.isEmpty())
        val templates = PromptTemplateSet(listOf(PromptTemplate("id", "Template", enabled = false,
            languages = listOf(PromptLanguageTag.of("ja")), order = 7, body = "{{style}}")))
        val saved = store.replace(empty.fingerprint, templates)
        assertEquals(saved.fingerprint, NativePromptTemplateStore(context).read().fingerprint)
        try { store.replace(empty.fingerprint, PromptTemplateSet(emptyList())); fail("Accepted stale replacement") }
        catch (_: IllegalArgumentException) { }
        val reopened = store.read()
        assertEquals(saved.fingerprint, reopened.fingerprint)
        assertEquals("{{style}}", reopened.templates.templates.single().body)
        assertFalse(reopened.templates.templates.single().enabled)
        val cleared = store.replace(saved.fingerprint, PromptTemplateSet(emptyList()))
        assertTrue(cleared.templates.templates.isEmpty())
        assertEquals(empty.fingerprint, cleared.fingerprint)
    }

    @Test fun importPreviewCannotOverwriteALibraryChangedSincePreview() = runBlocking {
        val original = store.read()
        val incoming = PromptTemplateSet(listOf(PromptTemplate("incoming", "Imported", body = "Imported body")))
        val preview = original.templates.previewImport(PromptTemplateCodec.decode(PromptTemplateCodec.encode(incoming)),
            PromptTemplateImportMode.REPLACE)
        val newer = store.replace(original.fingerprint,
            PromptTemplateSet(listOf(PromptTemplate("newer", "New edit", body = "Keep this edit"))))
        try { store.replace(preview.expected, preview.templates); fail("Applied a stale import") }
        catch (_: IllegalArgumentException) { }
        val reopened = NativePromptTemplateStore(context).read()
        assertEquals(newer.fingerprint, reopened.fingerprint)
        assertEquals("Keep this edit", reopened.templates.templates.single().body)
        val fresh = reopened.templates.previewImport(incoming, PromptTemplateImportMode.UPDATE)
        store.replace(fresh.expected, fresh.templates)
        assertEquals(setOf("newer", "incoming"), store.read().templates.templates.map { it.id }.toSet())
    }
}
