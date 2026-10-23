package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
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
class NativeQualityPanelTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val fp = ObjectFingerprint("a".repeat(64))
    private fun state(): NativeProjectUiState {
        val options = TextProofreadingOptions()
        val facts = listOf(TextQualityFact(ItemId(1), fp, TextProofreadingResult(listOf(TextWarningCode.GLOSSARY),
            listOf("term"), true, fp, options)))
        val index = TextQualityIndex(ref, 0, TextItemQuery(), options, fp, facts, 1)
        return NativeProjectUiState(project = TextProjectSummary(ref, "fixture", 0, RelativePath("source.txt"), 1),
            qualityView = index.select(TextQualityFilter()))
    }
    @Test fun openingAndCancellingTheScanFormDoesNotStartWork() {
        var scans = 0
        compose.setContent { NativeTheme { Surface { NativeQualityPanel(state(), NativeQualityActions({ scans++ }, {}, {})) } } }
        compose.onNodeWithTag("native-quality-scan").performClick()
        assertEquals(0, scans)
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertEquals(0, scans)
        compose.onNodeWithTag("native-quality-scan").performClick()
        compose.onNodeWithTag("native-quality-scan-confirm").performClick()
        assertEquals(1, scans)
    }
    @Test fun selectingATermDoesNotFilterUntilTheExplicitApplyAction() {
        var selected: TextQualityFilter? = null
        compose.setContent { NativeTheme { Surface { NativeQualityPanel(state(), NativeQualityActions({}, { selected = it }, {})) } } }
        compose.onNodeWithTag("native-quality-filter").performClick()
        compose.onNodeWithTag("native-quality-filter-list").performScrollToNode(hasText("term (1)"))
        compose.onNodeWithText("term (1)").performClick()
        assertNull(selected)
        compose.onNodeWithTag("native-quality-filter-apply").performClick()
        assertEquals(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY, "term"), selected)
    }
    @Test fun staleSnapshotDisablesFilteringAndReplacesStatisticsWithAStaleMessage() {
        val stale = state().let { it.copy(project = it.project!!.copy(revision = 1)) }
        compose.setContent { NativeTheme { Surface { NativeQualityPanel(stale, NativeQualityActions({}, {}, {})) } } }
        compose.onNodeWithTag("native-quality-filter").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_quality_stale)).assertIsDisplayed()
        compose.onNodeWithTag("native-quality-clear").assertIsEnabled()
    }
}
