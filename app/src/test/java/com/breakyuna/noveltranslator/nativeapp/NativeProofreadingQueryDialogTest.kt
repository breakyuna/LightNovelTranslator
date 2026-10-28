package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Synthetic interactions only; images require recording and visual inspection in CI/PC. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class NativeProofreadingQueryDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val summary = TextProjectSummary(ref, "fixture", 0, RelativePath("source.txt"), 3)
    private val state = NativeProjectUiState(project = summary, itemStatistics = TextItemStatistics(1, 1, 1, 1), matchingItems = 3)

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [35])
    fun controlsOnlyDispatchOnExplicitApplyAndKeepTheSelectedFieldAndStatus() {
        val applied = mutableListOf<TextItemQuery>()
        compose.setContent { NativeTheme { NativeProofreadingQueryDialog(state, {}, { applied.add(it) }) } }
        compose.onNodeWithTag("native-proof-status").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_status_excluded)).performClick()
        compose.onNodeWithTag("native-proof-field").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_translation)).performClick()
        compose.onNodeWithTag("native-proof-search").performScrollTo().performTextReplacement("%_literal")
        assertTrue(applied.isEmpty())
        compose.onNodeWithTag("native-proof-apply").performClick()
        assertEquals(listOf(TextItemQuery(TextItemStatus.EXCLUDED, "%_literal", TextSearchField.TRANSLATION)), applied)
        compose.onNodeWithTag("native-proof-dialog").captureRoboImage(filePath = "src/test/screenshots/native-proofreading-query.png")
    }

    @Test fun dismissingDraftSearchLeavesTheAppliedQueryAlone() {
        var dismissed = 0
        val applied = mutableListOf<TextItemQuery>()
        compose.setContent { NativeTheme { NativeProofreadingQueryDialog(state, { dismissed++ }, { applied.add(it) }) } }
        compose.onNodeWithTag("native-proof-search").performScrollTo().performTextReplacement("draft only")
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertEquals(1, dismissed); assertTrue(applied.isEmpty())
    }

    @Test fun clearDispatchesAnEmptyQueryAndBusyControlsCannotApplyChanges() {
        val applied = mutableListOf<TextItemQuery>()
        val current = androidx.compose.runtime.mutableStateOf(state.copy(proofreadingQuery = TextItemQuery(text = "saved")))
        compose.setContent { NativeTheme { NativeProofreadingQueryDialog(current.value, {}, { applied.add(it) }) } }
        compose.onNodeWithTag("native-proof-clear").performScrollTo().performClick()
        assertEquals(listOf(TextItemQuery()), applied)
        compose.runOnIdle { current.value = current.value.copy(busy = true) }
        compose.onNodeWithTag("native-proof-apply").assertIsNotEnabled()
        compose.onNodeWithTag("native-proof-clear").assertIsNotEnabled()
        compose.onNodeWithTag("native-proof-search").assertIsNotEnabled()
    }

    @Test fun filteredPageCannotStartUnfilteredSimulationAndOffersExplicitClear() {
        var starts = 0
        val applied = mutableListOf<TextItemQuery>()
        val item = ItemPreview(ItemId(1), 0, "source", "", TextItemStatus.NONE, true)
        val filtered = state.copy(proofreadingQuery = TextItemQuery(status = TextItemStatus.NONE), items = listOf(item), matchingItems = 1)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(filtered, {}, {}, {}, { starts++ }, query = { applied.add(it) })
        } } }
        compose.onNodeWithTag("native-proof-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_simulation)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_proof_filter)).performClick()
        compose.onNodeWithTag("native-proof-clear").performScrollTo().performClick()
        assertEquals(listOf(TextItemQuery()), applied); assertEquals(0, starts)
    }
}
