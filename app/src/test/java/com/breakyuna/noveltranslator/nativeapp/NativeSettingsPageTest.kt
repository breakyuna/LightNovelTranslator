package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeSettingsPageTest {
    @get:Rule val compose = createComposeRule()

    private fun networkState(settings: ModelNetworkSettings = ModelNetworkSettings.DEFAULT) =
        NativeNetworkSettingsState(settings = settings, fingerprint = "abc123", loaded = true, loading = false)

    private fun limitsState(limits: ModelRequestLimits = ModelRequestLimits()) =
        NativeRequestLimitsState(limits = limits, fingerprint = "def456", loaded = true, loading = false)

    @Test fun basicSelectionsAndBackCallTheRealHandlers() {
        var language: NativeAppLanguage? = null
        var theme: NativeThemeMode? = null
        var automatic = false
        var back = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSettingsPage(
                        NativeSettingsSection.BASIC, NativeAppLanguage.ENGLISH, NativeThemeMode.SYSTEM, false,
                        "", false, networkState(), limitsState(), back = { back++ },
                        selectSection = {}, selectLanguage = { language = it }, selectTheme = { theme = it },
                        setAutomaticContextSummary = { automatic = it }, saveAppPersona = {},
                        reloadNetwork = {}, saveNetwork = {},
                        reloadLimits = {}, saveLimits = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-settings-language-GERMAN").performScrollTo().performClick()
        compose.onNodeWithTag("native-settings-theme-DARK").performScrollTo().performClick()
        compose.onNodeWithTag("native-settings-auto-context").performScrollTo().performClick()
        compose.onNodeWithTag("native-settings-back").performClick()
        assertEquals(NativeAppLanguage.GERMAN, language)
        assertEquals(NativeThemeMode.DARK, theme)
        assertTrue(automatic)
        assertEquals(1, back)
    }

    @Test fun personaDraftIsBoundedAndKeptUntilASaveSucceeds() {
        var saved: String? = null
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSettingsPage(
                        NativeSettingsSection.BASIC, NativeAppLanguage.ENGLISH, NativeThemeMode.SYSTEM, true,
                        "Existing persona", false, networkState(), limitsState(), back = {},
                        selectSection = {}, selectLanguage = {}, selectTheme = {}, setAutomaticContextSummary = {},
                        saveAppPersona = { saved = it },
                        reloadNetwork = {}, saveNetwork = {}, reloadLimits = {}, saveLimits = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-settings-persona").performScrollTo()
            .performTextReplacement("x".repeat(NativeAppPersona.MAX_CHARS + 1))
        compose.onNodeWithTag("native-settings-persona-save").performScrollTo().assertIsNotEnabled()
        val invalid = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.native_settings_persona_invalid)
        compose.onNodeWithText(invalid).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-settings-persona").performTextReplacement("Warm and concise")
        compose.onNodeWithTag("native-settings-persona-save").performScrollTo().performClick()
        assertEquals("Warm and concise", saved)
        compose.onNodeWithTag("native-settings-persona").assertTextContains("Warm and concise")
    }

    @Test fun expertFormRejectsInvalidValuesAndKeepsTheDraftOnConflict() {
        var saved: NativeNetworkSettingsInput? = null
        var network by mutableStateOf(networkState())
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSettingsPage(
                        NativeSettingsSection.EXPERT, NativeAppLanguage.ENGLISH, NativeThemeMode.SYSTEM, false,
                        "", false, network, limitsState(), back = {},
                        selectSection = {}, selectLanguage = {}, selectTheme = {}, setAutomaticContextSummary = {},
                        saveAppPersona = {},
                        reloadNetwork = {}, saveNetwork = { input ->
                            saved = input
                            network = network.copy(conflict = true,
                                fingerprint = "external", settings = ModelNetworkSettings(1, 2, 3, 4,
                                    source = ModelNetworkSettingsSource.SAVED))
                        }, reloadLimits = {}, saveLimits = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-settings-connect-timeout").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("native-settings-network-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("native-settings-connect-timeout").performTextReplacement("60")
        compose.onNodeWithTag("native-settings-proxy-host").performScrollTo().performTextReplacement("proxy.example")
        compose.onNodeWithTag("native-settings-proxy-port").performScrollTo().performTextReplacement("8080")
        compose.onNodeWithTag("native-settings-network-save").performScrollTo().performClick()
        assertEquals("60", saved?.connectTimeoutSeconds)
        assertEquals("proxy.example", saved?.proxyHost)
        // The successful callback simulated an external edit, not a form reset. Typed values stay.
        compose.onNodeWithTag("native-settings-connect-timeout").assertTextContains("60")
        compose.onNodeWithTag("native-settings-proxy-host").assertTextContains("proxy.example")
        val conflict = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.native_settings_network_conflict)
        compose.onNodeWithText(conflict).performScrollTo().assertIsDisplayed()
    }

    @Test fun requestLimitsUseTheSameBoundsAndSaveCallback() {
        var saved: NativeRequestLimitsInput? = null
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSettingsPage(
                        NativeSettingsSection.EXPERT, NativeAppLanguage.ENGLISH, NativeThemeMode.SYSTEM, false,
                        "", false, networkState(), limitsState(), back = {},
                        selectSection = {}, selectLanguage = {}, selectTheme = {}, setAutomaticContextSummary = {},
                        saveAppPersona = {},
                        reloadNetwork = {}, saveNetwork = {}, reloadLimits = {}, saveLimits = { saved = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("native-settings-concurrency").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("native-settings-limits-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("native-settings-concurrency").performTextReplacement("16")
        compose.onNodeWithTag("native-settings-rpm").performScrollTo().performTextReplacement("60000")
        compose.onNodeWithTag("native-settings-limits-save").performScrollTo().performClick()
        assertEquals("16", saved?.concurrency)
        assertEquals("60000", saved?.requestsPerMinute)
    }

    @Test fun corruptStoredSettingsFailClosedInsteadOfShowingAnEditableDefault() {
        var reloads = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeSettingsPage(
                        NativeSettingsSection.EXPERT, NativeAppLanguage.ENGLISH, NativeThemeMode.SYSTEM, false,
                        "", false, networkState().copy(loaded = false, loading = false, loadFailed = true,
                            fingerprint = null), limitsState().copy(loaded = false, loading = false,
                            loadFailed = true, fingerprint = null), back = {},
                        selectSection = {}, selectLanguage = {}, selectTheme = {}, setAutomaticContextSummary = {},
                        saveAppPersona = {},
                        reloadNetwork = { reloads++ }, saveNetwork = {}, reloadLimits = { reloads++ }, saveLimits = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("native-settings-fail-closed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-settings-connect-timeout").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-network-reload").performScrollTo().performClick()
        assertEquals(1, reloads)
    }
}
