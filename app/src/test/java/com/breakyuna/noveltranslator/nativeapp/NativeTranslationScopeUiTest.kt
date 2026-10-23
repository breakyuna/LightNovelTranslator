package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
class NativeTranslationScopeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun state(pendingHere: Boolean) = NativeProjectUiState(
        project = TextProjectSummary(ref, "fixture", 1, RelativePath("source.txt"), 2),
        itemStatistics = TextItemStatistics(1, 1, 0, 1),
        items = listOf(ItemPreview(ItemId(1), 0, "source", if (pendingHere) "" else "saved",
            if (pendingHere) TextItemStatus.NONE else TextItemStatus.PROCESSED, true)))

    @Test fun blankPendingRowsDoNotOfferAnotherSimulationAfterNonblankRowsAreDone() {
        var calls = 0
        val finished = state(false).copy(itemStatistics = TextItemStatistics(1, 1, 0, 0))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(finished, {}, {}, {}, { calls++ }, simulateProject = { calls++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.native_simulation)).assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun aProjectContainingOnlyBlankRowsDoesNotOfferSimulation() {
        val blank = state(false).copy(itemStatistics = TextItemStatistics(2, 0, 0, 0),
            items = listOf(ItemPreview(ItemId(1), 0, " \t", "", TextItemStatus.NONE, false)))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(blank, {}, {}, {}, {}, simulateProject = {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_simulation)).assertIsNotEnabled()
    }

    @Test fun selectingEntireProjectDoesNotStartWorkUntilSeparateConfirmation() {
        var pages = 0; var projects = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state(true), {}, {}, {}, { pages++ }, simulateProject = { projects++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.native_simulation)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_simulation_scope_project)).performScrollTo().performClick()
        assertEquals(0, projects); assertEquals(0, pages)
        compose.onNodeWithText(context.getString(R.string.native_simulation_project_confirm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_simulation_start)).performClick()
        assertEquals(1, projects); assertEquals(0, pages)
    }

    @Test fun aFinishedPageDoesNotHidePendingWorkOnOtherPagesAndCancellationDoesNothing() {
        var calls = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state(false), {}, {}, {}, { calls++ }, simulateProject = { calls++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.native_simulation)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.native_simulation_scope_page)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_simulation_project_confirm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertEquals(0, calls)
    }
}
