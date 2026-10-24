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
class NativeRemoteTranslationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val config = ModelConfigurationRef("remote", 7)
    private fun state() = NativeProjectUiState(
        project = TextProjectSummary(ref, "fixture", 12, RelativePath("source.txt"), 1),
        items = listOf(ItemPreview(ItemId(1), 0, "source", "", TextItemStatus.NONE)),
        itemStatistics = TextItemStatistics(1, 0, 0, 1),
        modelSettings = NativeModelSettings(config, "http://localhost:1234/v1", "fixture-model", false))

    @Test fun draftsAndCancellationDoNotStartRemoteCalls() {
        var starts = 0; var closed = false
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeRemoteTranslationDialog(state(), { closed = true }, start = { _, _, _, _, _ -> starts++ })
        } } }
        compose.onNodeWithTag("native-remote-start").assertIsNotEnabled()
        compose.onNodeWithTag("native-remote-target").performScrollTo().performTextInput("English")
        compose.onNodeWithTag("native-remote-start").assertIsEnabled()
        assertEquals(0, starts)
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertTrue(closed); assertEquals(0, starts)
    }

    @Test fun explicitConfirmationPassesTheDisplayedRevisionAndModelExactlyOnce() {
        var starts = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeRemoteTranslationDialog(state(), {}, start = { selectedRef, revision, selectedConfig, options, whole ->
                starts++; assertEquals(ref, selectedRef); assertEquals(12L, revision); assertEquals(config, selectedConfig)
                assertEquals("English", options.targetLanguage); assertFalse(whole)
            })
        } } }
        compose.onNodeWithTag("native-remote-target").performScrollTo().performTextInput("English")
        compose.onNodeWithTag("native-remote-start").performClick()
        assertEquals(1, starts)
    }

    @Test fun missingModelDoesNotOfferRemoteTranslation() {
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state().copy(modelSettings = null), {}, {}, {}, {}, remoteTranslation = { _, _, _, _, _ -> fail("Started without model") })
        } } }
        compose.onNodeWithText(context.getString(R.string.native_remote_translate)).assertIsNotEnabled()
    }

    @Test fun activeFilterDoesNotOfferRemoteTranslation() {
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            Proofreading(state().copy(proofreadingQuery = TextItemQuery(text = "source")), {}, {}, {}, {},
                remoteTranslation = { _, _, _, _, _ -> fail("Translated outside confirmed scope") })
        } } }
        compose.onNodeWithText(context.getString(R.string.native_remote_translate)).assertIsNotEnabled()
    }
}
