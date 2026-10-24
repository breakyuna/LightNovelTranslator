package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class NativeManualBatchUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val summary = TextProjectSummary(ref, "fixture", 0, RelativePath("source.txt"), 2)
    private val batch = ManualTextBatch(CommandId("batch"), ref, 0, TextBatchChange.CLEAR_AND_RESET,
        listOf(TextBatchTarget(ItemId(1), 0, ObjectFingerprint("a".repeat(64)))))

    @Test fun selectingARowNeverOpensTheEditorAndTheMenuOnlyPreparesAChange() {
        var selected: ItemId? = null; var edited: ItemId? = null
        var prepared: TextBatchChange? = null; var confirmed = 0
        val actions = NativeBatchActions({}, { selected = it }, {}, { prepared = it }, { confirmed++ }, {})
        val state = NativeProjectUiState(project = summary, selectingItems = true, selectedItems = setOf(ItemId(2)),
            items = listOf(ItemPreview(ItemId(1), 0, "one", "", TextItemStatus.NONE, true),
                ItemPreview(ItemId(2), 1, "two", "", TextItemStatus.NONE, true)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state, { edited = it }, {}, {}, {}, batchActions = actions)
        } } }
        compose.onNodeWithContentDescription(context.getString(R.string.native_batch_select_row, 1L)).performClick()
        assertEquals(ItemId(1), selected); assertNull(edited)
        compose.onNodeWithTag("native-batch-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_batch_clear)).performClick()
        assertEquals(TextBatchChange.CLEAR_AND_RESET, prepared); assertEquals(0, confirmed)
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [35])
    fun clearingTranslationsRequiresASeparateExplicitConfirmation() {
        var confirmed = 0
        val actions = NativeBatchActions({}, {}, {}, {}, { confirmed++ }, {})
        compose.setContent { NativeTheme { NativeManualBatchDialog(NativeProjectUiState(project = summary, manualBatch = batch), actions) } }
        compose.onNodeWithText(context.getString(R.string.native_batch_clear_warning)).performScrollTo().assertIsDisplayed()
        assertEquals(0, confirmed)
        compose.onNodeWithTag("native-batch-confirm").performClick()
        assertEquals(1, confirmed)
        compose.onNodeWithTag("native-batch-dialog").captureRoboImage(filePath = "src/test/screenshots/native-proofreading-batch.png")
    }

    @Test fun conflictCannotBeConfirmedAndBusyStateCannotDismissAnInFlightWrite() {
        var confirmed = 0; var dismissed = 0
        val state = mutableStateOf(NativeProjectUiState(project = summary, manualBatch = batch, manualBatchConflict = true))
        val actions = NativeBatchActions({}, {}, {}, {}, { confirmed++ }, { dismissed++ })
        compose.setContent { NativeTheme { NativeManualBatchDialog(state.value, actions) } }
        compose.onNodeWithTag("native-batch-confirm").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText(context.getString(R.string.native_cancel)).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertEquals(0, confirmed); assertEquals(1, dismissed)
    }

    @Test fun queryReplacementEntryOnlyPreparesTheCapturedScopeAndNeverConfirmsAWrite() {
        var prepared: Pair<TextReplacementOptions, NativeQueryReplacementScope>? = null
        var confirmed = 0
        val query = TextItemQuery(TextItemStatus.PROCESSED, "source")
        val state = NativeProjectUiState(project = summary, proofreadingQuery = query, matchingItems = 2)
        val actions = NativeBatchActions({}, {}, {}, {}, { confirmed++ }, {}, prepareQuery = {},
            prepareReplacement = { options, scope -> prepared = options to scope })
        compose.setContent { NativeTheme { NativeQueryBatchButton(state, actions) } }
        compose.onNodeWithTag("native-query-batch-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_batch_replace)).performClick()
        compose.onNodeWithTag("native-query-replace-find").performScrollTo().performTextInput("old")
        compose.onNodeWithTag("native-query-replace-target").performScrollTo().performTextInput("\$1\\1")
        compose.onNodeWithTag("native-policy-apply").performClick()
        assertEquals(TextReplacementOptions("old", "\$1\\1"), prepared!!.first)
        assertEquals(ref, prepared!!.second.ref); assertEquals(0L, prepared!!.second.revision)
        assertEquals(query, prepared!!.second.query); assertEquals(0, confirmed)
    }

    @Test fun replacementBatchShowsTheActualParametersAndRequiresItsOwnConfirmation() {
        val replacement = ManualTextBatch(CommandId("replace"), ref, 0, TextBatchChange.REPLACE_TRANSLATION,
            batch.targets, TextReplacementOptions("old", "new", false))
        var confirmed = 0
        val actions = NativeBatchActions({}, {}, {}, {}, { confirmed++ }, {})
        compose.setContent { NativeTheme {
            NativeManualBatchDialog(NativeProjectUiState(project = summary, manualBatch = replacement, manualBatchQuery = true), actions)
        } }
        compose.onNodeWithText(context.getString(R.string.native_query_replace_preview, "old", "new"))
            .performScrollTo().assertIsDisplayed()
        assertEquals(0, confirmed)
        compose.onNodeWithTag("native-batch-confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun regexToggleIsPartOfThePreparedReplacementAndInvalidPatternsCannotProceed() {
        var prepared: TextReplacementOptions? = null
        val actions = NativeBatchActions({}, {}, {}, {}, { fail("Implicit confirmation") }, {}, prepareQuery = {},
            prepareReplacement = { options, _ -> prepared = options })
        compose.setContent { NativeTheme { NativeQueryBatchButton(NativeProjectUiState(project = summary, matchingItems = 2), actions) } }
        compose.onNodeWithTag("native-query-batch-actions").performClick()
        compose.onNodeWithText(context.getString(R.string.native_batch_replace)).performClick()
        compose.onNodeWithTag("native-query-replace-regex").performScrollTo().performClick()
        compose.onNodeWithTag("native-query-replace-find").performScrollTo().performTextInput("[")
        compose.onNodeWithTag("native-policy-apply").assertIsNotEnabled()
        compose.onNodeWithTag("native-query-replace-find").performTextReplacement("(old)")
        compose.onNodeWithTag("native-query-replace-target").performScrollTo().performTextInput("\$1")
        compose.onNodeWithTag("native-policy-apply").performClick()
        assertEquals(TextReplacementOptions("(old)", "\$1", regex = true), prepared)
    }
}
