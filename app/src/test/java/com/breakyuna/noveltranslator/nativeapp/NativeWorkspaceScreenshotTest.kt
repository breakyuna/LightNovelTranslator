package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic component fixtures only; screenshots still require human visual inspection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class NativeWorkspaceScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("local-fixture"), RecoveryGeneration(1))
    private val summary = TextProjectSummary(ref, "Synthetic project", 0, RelativePath("sample.txt"), 2)
    private val first = TextProjectItem(ItemId(41), 0, "Synthetic first line", "", TextItemStatus.NONE,
        TextProjectFingerprint.item(ItemId(41), 0, "Synthetic first line", "", TextItemStatus.NONE))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun projectHome() {
        val state = NativeProjectUiState(projects = listOf(RegisteredProject(ref, summary, null)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            ProjectsHome(state, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_create_txt)).assertExists()
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-projects.png")
    }

    @Test fun agentLandingOffersRealWorkspaceInsteadOfAnUnconnectedSendAction() {
        var selected: NativePage? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            AgentLanding(NativeProjectUiState(project = summary)) { selected = it }
        } } }
        compose.onNodeWithText(context.getString(R.string.native_open_proofreading)).performClick()
        assertEquals(NativePage.PROOFREADING, selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-agent.png")
    }

    @Test fun workbenchOffersNativeSnapshotAndBilingualExport() {
        var selected: NativeExport? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Workbench(NativeProjectUiState(project = summary), {}) { selected = it }
        } } }
        compose.onNodeWithText(context.getString(R.string.native_export_bilingual)).performClick()
        assertEquals(NativeExport.BILINGUAL, selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-workbench.png")
    }

    @Test fun proofreadingDispatchesStableItemIdentity() {
        var selected: ItemId? = null
        val state = NativeProjectUiState(project = summary, items = listOf(ItemPreview.of(first)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state, { selected = it }, {}, {})
        } } }
        compose.onNodeWithText(first.source).performClick()
        assertEquals(ItemId(41), selected)
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/native-proofreading.png")
    }

    @Test fun editorPresentsDraftAndExplicitSave() {
        var saved = false
        val draft = NativeEditDraft(ref, first, 0, first.fingerprint, "Synthetic translation", TextItemStatus.PROCESSED, CommandId("save"))
        compose.setContent { NativeTheme {
            Editor(draft, false, {}, {}, {}, { saved = true }, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.native_save)).performClick()
        assertTrue(saved)
        compose.onNode(isRoot() and hasAnyDescendant(hasText(context.getString(R.string.native_edit_row, 1))))
            .captureRoboImage(filePath = "src/test/screenshots/native-editor.png")
    }
}
