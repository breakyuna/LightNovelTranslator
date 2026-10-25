package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStoreException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeSkillMetadataTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun editorDraftUpdatesLanguagesTagsOrderAndEnabledThroughTheExistingCasPath() = runBlocking {
        val root = File(context.cacheDir, "skill-metadata-test-" + UUID.randomUUID())
        val store = WorkspaceSkillStore(root)
        try {
            val id = WorkspaceSkillId("user-alpha")
            val created = store.create(WorkspaceSkillDraft(id, "Alpha", content = "body", order = 1))
            val input = NativeSkillEditorInput(
                name = "  Alpha  ",
                description = "  First  ",
                content = "body",
                enabled = false,
                order = "4",
                languages = "JA, en, ja",
                tags = "Style, theme",
            )
            val updated = store.update(id, input.toDraft(id), created.fingerprint)
            assertEquals("Alpha", updated.summary.name)
            assertEquals("First", updated.summary.description)
            assertFalse(updated.summary.enabled)
            assertEquals(4, updated.summary.order)
            assertEquals(setOf("ja", "en"), updated.summary.languages)
            assertEquals(setOf("style", "theme"), updated.summary.tags)

            val stale = runCatching {
                store.update(id, input.copy(name = "Changed").toDraft(id), created.fingerprint)
            }.exceptionOrNull()
            assertTrue(stale is WorkspaceSkillStoreException)
            assertEquals(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT,
                (stale as WorkspaceSkillStoreException).reason)
            assertEquals("Alpha", store.record(id)!!.summary.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun labelsAreBoundedNormalizedAndConflictErrorsStayEditorLocal() {
        assertEquals(setOf("ja", "en"), NativeSkillMetadata.parseLabels(" JA, en\nja ", 2, 32))
        assertEquals("en, ja", NativeSkillMetadata.formatLabels(setOf("ja", "en")))
        assertThrows(IllegalArgumentException::class.java) {
            NativeSkillMetadata.parseLabels((0..32).joinToString(","), 32, 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeSkillMetadata.parseLabels("x".repeat(33), 32, 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeSkillMetadata.parseLabels("bad\u0001", 32, 32)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeSkillMetadata.parseOrder("1000000") }
        assertThrows(IllegalArgumentException::class.java) { NativeSkillMetadata.parseOrder("-1") }

        val stale = WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT,
            WorkspaceSkillId("user-alpha"),
        )
        assertEquals(R.string.native_skills_conflict, nativeSkillEditorError(stale))
        assertEquals(R.string.native_skills_readonly_error, nativeSkillEditorError(
            WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.READ_ONLY)))
        assertEquals(R.string.native_skills_metadata_invalid,
            nativeSkillEditorError(IllegalArgumentException("invalid")))
        assertEquals(R.string.native_skills_error, nativeSkillEditorError(IllegalStateException("io")))
    }
}
