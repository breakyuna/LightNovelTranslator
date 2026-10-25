package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStoreException
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeSkillsDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun record(source: WorkspaceSkillSource = WorkspaceSkillSource.USER) = WorkspaceSkillRecord(
        WorkspaceSkillSummary(
            id = WorkspaceSkillId("skill-one"),
            name = "Guide",
            description = "Existing",
            enabled = true,
            order = 3,
            source = source,
            languages = setOf("en"),
            tags = setOf("style"),
        ),
        WorkspaceDigest("a".repeat(64)),
    )

    private fun actions(
        record: WorkspaceSkillRecord,
        update: suspend (WorkspaceSkillId, WorkspaceSkillDraft, WorkspaceDigest) -> WorkspaceSkillRecord =
            { _, _, _ -> record },
    ) = NativeSkillActions(
        list = { listOf(record) },
        read = { "body" },
        create = { error("Create was not expected") },
        update = update,
        delete = { _, _ -> },
        setEnabled = { _, _, _ -> record },
        setOrder = { _, _, _ -> record },
    )

    private fun openEditor() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.native_skills_edit))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.native_skills_edit)).performClick()
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("native-skill-content").assertTextContains("body")
                true
            }.getOrDefault(false)
        }
    }

    @Test fun editorSavesLanguagesTagsOrderAndEnabledThroughTheUpdatePath() {
        val record = record()
        var saved: WorkspaceSkillDraft? = null
        val skillActions = actions(record) { _, draft, _ ->
            saved = draft
            record
        }
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSkillsDialog(skillActions, {})
                }
            }
        }
        openEditor()
        compose.onNodeWithTag("native-skill-languages").performTextReplacement("JA, en")
        compose.onNodeWithTag("native-skill-tags").performTextReplacement("New, Style")
        compose.onNodeWithTag("native-skill-order").performTextReplacement("7")
        compose.onNodeWithTag("native-skill-enabled").performClick()
        compose.onNodeWithTag("native-skill-save").performClick()
        compose.waitUntil(5_000) { saved != null }
        val draft = requireNotNull(saved)
        assertEquals(setOf("ja", "en"), draft.languages)
        assertEquals(setOf("new", "style"), draft.tags)
        assertEquals(7, draft.order)
        assertFalse(draft.enabled)
    }

    @Test fun staleFingerprintShowsConflictInsideTheEditorAndKeepsTheDraft() {
        val record = record()
        val skillActions = actions(record) { _, _, _ ->
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT,
                record.summary.id)
        }
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSkillsDialog(skillActions, {})
                }
            }
        }
        openEditor()
        compose.onNodeWithTag("native-skill-tags").performTextReplacement("kept-draft")
        compose.onNodeWithTag("native-skill-save").performClick()
        val conflict = context.getString(R.string.native_skills_conflict)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(conflict).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-skill-editor-error").assertIsDisplayed()
        compose.onNodeWithTag("native-skill-tags").assertTextContains("kept-draft")
    }

    @Test fun builtInSkillEditorIsReadOnly() {
        val record = record(WorkspaceSkillSource.BUILT_IN)
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSkillsDialog(actions(record), {})
                }
            }
        }
        openEditor()
        compose.onNodeWithTag("native-skill-name").assertIsNotEnabled()
        compose.onNodeWithTag("native-skill-enabled").assertIsNotEnabled()
        compose.onNodeWithTag("native-skill-languages").assertIsNotEnabled()
        compose.onNodeWithTag("native-skill-tags").assertIsNotEnabled()
        compose.onNodeWithTag("native-skill-save").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_skills_builtin_readonly))
            .assertIsDisplayed()
    }
}
