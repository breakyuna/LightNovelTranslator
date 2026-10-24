package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
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
class NativeMorePageTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private fun state() = NativeProjectUiState(
        project = TextProjectSummary(ref, "fixture", 3, RelativePath("source.txt"), 4),
        page = NativePage.MORE,
        supportedFormats = setOf(ProjectAssetFormat.TXT, ProjectAssetFormat.PDF),
        storageBytes = 4_096,
    )

    @Test fun everyManagementEntryInvokesItsRealCallback() {
        var rules = 0; var models = 0; var settings = 0; var appearance = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), { rules++ }, { models++ }, { settings++ }, { appearance++ }, {}, {}, {}, {},
                        notificationsGranted = true, requestNotifications = {}, cleanup = {}, refresh = {},
                        refreshStorage = {})
                }
            }
        }
        compose.onNodeWithTag("native-more-rules").performClick()
        compose.onNodeWithTag("native-more-models").performClick()
        compose.onNodeWithTag("native-more-settings").performScrollTo().performClick()
        compose.onNodeWithTag("native-more-appearance").performScrollTo().performClick()
        assertEquals(1, rules); assertEquals(1, models); assertEquals(1, settings); assertEquals(1, appearance)
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-formats"))
        compose.onNodeWithTag("native-more-formats").assertIsDisplayed()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-notifications"))
        compose.onNodeWithTag("native-more-notifications").assertIsDisplayed()
    }

    @Test fun missingNotificationPermissionIsActionableButGrantedStateIsReadOnly() {
        var asked = false
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), {}, {}, {}, {}, {}, {}, {}, {}, notificationsGranted = false,
                        requestNotifications = { asked = true }, cleanup = {}, refresh = {}, refreshStorage = {})
                }
            }
        }
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-notifications"))
        compose.onNodeWithTag("native-more-notifications").performClick()
        assertEquals(true, asked)
    }

    @Test fun themeModeParsingFallsBackToSystemForDamagedPreferences() {
        assertEquals(NativeThemeMode.SYSTEM, NativeThemeMode.parse(null))
        assertEquals(NativeThemeMode.SYSTEM, NativeThemeMode.parse("BROKEN"))
        assertEquals(NativeThemeMode.DARK, NativeThemeMode.parse("DARK"))
        assertFalse(NativeThemeMode.parse("LIGHT") == NativeThemeMode.DARK)
    }
}
