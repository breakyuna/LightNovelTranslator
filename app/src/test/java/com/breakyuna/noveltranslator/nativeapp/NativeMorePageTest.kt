package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        var models = 0; var settings = 0; var appearance = 0
        val sections = mutableListOf<NativeRuleSection>()
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), { sections += it }, { models++ }, { settings++ }, { appearance++ }, {}, {}, {}, {},
                        notificationsGranted = true, requestNotifications = {}, cleanup = {}, refresh = {},
                        refreshStorage = {})
                }
            }
        }
        compose.onNodeWithTag("native-more-rules-glossary").assertDoesNotExist()
        compose.onNodeWithTag("native-more-rules-prompts").assertDoesNotExist()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-models"))
        compose.onNodeWithTag("native-more-models").performClick()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-settings"))
        compose.onNodeWithTag("native-more-settings").performClick()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-appearance"))
        compose.onNodeWithTag("native-more-appearance").performClick()
        assertEquals(emptyList<NativeRuleSection>(), sections)
        assertEquals(1, models); assertEquals(1, settings); assertEquals(1, appearance)
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-formats"))
        compose.onNodeWithTag("native-more-formats").assertIsDisplayed()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-notifications"))
        compose.onNodeWithTag("native-more-notifications").assertIsDisplayed()
    }

    @Test fun appAndExpertSettingsEntriesOpenTheRoutedSettingsSurface() {
        var basic = 0; var expert = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), { _ -> }, {}, {}, {}, {}, {}, {}, {},
                        notificationsGranted = true, requestNotifications = {}, cleanup = {}, refresh = {},
                        refreshStorage = {}, onAppSettings = { basic++ }, onExpertSettings = { expert++ })
                }
            }
        }
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-app-settings"))
        compose.onNodeWithTag("native-more-app-settings").performClick()
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-expert-settings"))
        compose.onNodeWithTag("native-more-expert-settings").performClick()
        assertEquals(1, basic); assertEquals(1, expert)
    }

    @Test fun missingNotificationPermissionIsActionableButGrantedStateIsReadOnly() {
        var asked = false
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), { _ -> }, {}, {}, {}, {}, {}, {}, {}, notificationsGranted = false,
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

    @Test fun storageSummaryNeverExposesInstanceIdentifiers() {
        var opened = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeMorePage(state(), { _ -> }, {}, {}, {}, {}, {}, {}, {},
                        notificationsGranted = true, requestNotifications = {}, cleanup = {}, refresh = {},
                        refreshStorage = {}, onFiles = { opened++ })
                }
            }
        }
        compose.onAllNodesWithText(ref.instanceId.value, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("generation 1", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("native-more-page").performScrollToNode(hasTestTag("native-more-storage-open"))
        compose.onNodeWithTag("native-more-storage-open").performClick()
        assertEquals(1, opened)
    }
}
