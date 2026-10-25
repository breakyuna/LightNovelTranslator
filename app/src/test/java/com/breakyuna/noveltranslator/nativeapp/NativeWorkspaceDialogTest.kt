package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialOrigin
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspacePath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeWorkspaceDialogTest {
    @get:Rule val compose = createComposeRule()

    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val ref2 = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i2"), RecoveryGeneration(2))
    private val chat = ChatId("chat")
    private val run = RunId("run")
    private val run2 = RunId("run-2")
    private val digest = "a".repeat(64)
    private val digest2 = "b".repeat(64)
    private val material = WorkspaceMaterialSummary(
        WorkspaceMaterialId("material-1"), WorkspaceMaterialKind.SOURCE,
        WorkspacePath("sources/source.txt"), digest, 12, "text/plain", 0,
        WorkspaceMaterialOrigin.PROJECT_ASSET,
    )
    private val artifact = WorkspaceArtifactRecord(
        WorkspaceArtifactId("artifact-1"), run, WorkspacePath("report.md"), "text/markdown",
        WorkspaceArtifactKind.MARKDOWN, 12, digest2, 0,
    )

    @Test fun artifactImportRequiresConfirmationAndPassesFrozenIdentity() {
        var calls = 0
        val actions = NativeWorkspaceActions(
            materials = { _, _ -> emptyList() }, readMaterial = { _, _, _ -> error("Unused") },
            artifacts = { _, _ -> listOf(artifact) }, readArtifact = { _, _, _ -> error("Unused") },
            importArtifact = { actualRef, revision, selected, path, _ ->
                assertEquals(ref, actualRef)
                assertEquals(3L, revision)
                assertSame(artifact, selected)
                assertEquals("report.md", path)
                calls++
            },
        )
        compose.setContent { NativeTheme { NativeWorkspaceDialog(ref, 3, chat, run, actions, {}, {}) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("native-workspace-import-" + artifact.id.value).performClick()
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithTag("native-workspace-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("native-workspace-import-cancel").performClick()
        compose.onNodeWithTag("native-workspace-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("native-workspace-import-" + artifact.id.value).performClick()
        compose.onNodeWithTag("native-workspace-import-confirm").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithTag("native-workspace-dialog").assertIsDisplayed()
    }

    @Test fun previewLoadsOnlyAfterExplicitRequestAndSaveKeepsTheRecordIdentity() {
        val materialReads = mutableListOf<Pair<LocalProjectRef, String>>()
        val saved = mutableListOf<WorkspaceArtifactRecord>()
        val actions = NativeWorkspaceActions(
            materials = { actual, revision ->
                assertEquals(ref, actual); assertEquals(3L, revision); listOf(material)
            },
            readMaterial = { actual, revision, id ->
                assertEquals(ref, actual); assertEquals(3L, revision)
                materialReads += actual to id.value
                "preview body"
            },
            artifacts = { actual, actualRun ->
                assertEquals(ref, actual); assertEquals(run, actualRun); listOf(artifact)
            },
            readArtifact = { _, _, _ -> error("Artifact preview is not requested in this test") },
        )
        compose.setContent { NativeTheme {
            NativeWorkspaceDialog(ref, 3, chat, run, actions, {}, { saved += it })
        } }
        compose.waitForIdle()
        assertTrue(materialReads.isEmpty())
        compose.onNodeWithText("sources/source.txt").assertIsDisplayed()
        compose.onNodeWithText("report.md").assertIsDisplayed()
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.onNodeWithText(context.getString(R.string.native_workspace_digest, digest))
            .assertIsDisplayed()
        compose.onNodeWithTag("native-workspace-save-" + artifact.id.value).performClick()
        compose.runOnIdle { assertEquals(listOf(artifact), saved) }
        compose.onNodeWithTag("native-workspace-preview-material-" + material.id.value).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(ref to material.id.value), materialReads) }
        compose.onNodeWithTag("native-workspace-preview-text").assertTextContains("preview body")
    }

    @Test fun changingProjectRevisionOrRunReloadsListsAndDropsOldPreviewState() {
        val current = mutableStateOf(ref to 3L)
        val artifactIdentity = mutableStateOf(run)
        val materialCalls = mutableListOf<Pair<LocalProjectRef, Long>>()
        val artifactCalls = mutableListOf<Pair<LocalProjectRef, RunId?>>()
        val newMaterial = WorkspaceMaterialSummary(
            WorkspaceMaterialId("material-2"), WorkspaceMaterialKind.DATASET,
            WorkspacePath("datasets/assets.jsonl"), digest, 10, "application/x-ndjson", 1,
            WorkspaceMaterialOrigin.HOST_REQUEST,
        )
        val newArtifact = WorkspaceArtifactRecord(
            WorkspaceArtifactId("artifact-2"), run2, WorkspacePath("report-new.md"), "text/markdown",
            WorkspaceArtifactKind.MARKDOWN, 10, digest2, 0,
        )
        val actions = NativeWorkspaceActions(
            materials = { actual, revision ->
                materialCalls += actual to revision
                if (revision == 3L) listOf(material) else listOf(newMaterial)
            },
            readMaterial = { _, _, _ -> "new preview" },
            artifacts = { actual, actualRun ->
                artifactCalls += actual to actualRun
                if (actualRun == run) listOf(artifact) else listOf(newArtifact)
            },
            readArtifact = { _, _, _ -> "new artifact" },
        )
        compose.setContent { NativeTheme {
            val identity = current.value
            NativeWorkspaceDialog(identity.first, identity.second, chat, artifactIdentity.value,
                actions, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("sources/source.txt").assertIsDisplayed()
        compose.onNodeWithText("report.md").assertIsDisplayed()
        compose.onNodeWithTag("native-workspace-preview-material-" + material.id.value).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("native-workspace-preview-text").assertTextContains("new preview")

        compose.runOnIdle { current.value = ref2 to 4L; artifactIdentity.value = run2 }
        compose.waitForIdle()
        assertTrue(materialCalls.contains(ref2 to 4L))
        assertTrue(artifactCalls.contains(ref2 to run2))
        compose.onNodeWithText("datasets/assets.jsonl").assertIsDisplayed()
        compose.onNodeWithText("report-new.md").assertIsDisplayed()
        compose.onNodeWithTag("native-workspace-preview-text").assertDoesNotExist()
    }
}
