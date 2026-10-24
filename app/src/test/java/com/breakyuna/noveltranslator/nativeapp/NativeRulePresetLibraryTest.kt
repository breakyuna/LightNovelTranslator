package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class NativeRulePresetLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    @Test fun selectingAndPreviewingPresetDoesNotApplyUntilExplicitConfirmation() {
        val baseline = ProjectTextPolicy(listOf(ProjectTextRule("existing", TextRuleKind.TERM, "Alice", "艾莉丝")))
        val preset = RulePreset("selected", "Preset", rules = listOf(ProjectTextRule("new", TextRuleKind.TERM, "Bob", "鲍勃")))
        var applied: ProjectTextPolicy? = null
        var libraryWrites = 0
        val actions = NativeRulePresetActions(
            { listOf(RulePresetSummary(preset.id, preset.name, "", preset.rules.size)) }, { preset },
            { _, _ -> libraryWrites++ }, { _, _ -> libraryWrites++ })
        compose.setContent { NativeTheme {
            NativeRulePresetLibrary(ref, baseline, actions, true, {}) { fingerprint, result ->
                assertEquals(baseline.fingerprint(), fingerprint); applied = result
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-preset-selected").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-preset-selected").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-preset-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-preset-preview").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-policy-apply").assertIsEnabled(); true }.getOrDefault(false)
        }
        assertNull(applied)
        assertEquals(0, libraryWrites)
        compose.onNodeWithTag("native-policy-apply").performClick()
        compose.runOnIdle {
            assertEquals(listOf("existing", "new"), requireNotNull(applied).rules.map { it.id })
            assertEquals(0, libraryWrites)
        }
    }
    @Test fun savingCurrentDraftAsPresetDoesNotApplyOrCommitTheProject() {
        val baseline = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝")), "Prompt", true)
        var stored: RulePreset? = null
        var applied = 0
        val actions = NativeRulePresetActions({ stored?.let { listOf(RulePresetSummary(it.id, it.name, it.description, it.rules.size)) } ?: emptyList() },
            { requireNotNull(stored) }, { value, expected -> assertNull(expected); stored = value }, { _, _ -> fail("Unexpected deletion") })
        compose.setContent { NativeTheme {
            NativeRulePresetLibrary(ref, baseline, actions, true, {}) { _, _ -> applied++ }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-preset-save-new").assertIsNotEnabled();
                compose.onNodeWithTag("native-preset-name").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-preset-name").performScrollTo().performTextInput("Reusable rules")
        compose.onNodeWithTag("native-preset-save-new").performScrollTo().performClick()
        compose.waitUntil(5_000) { stored != null }
        compose.runOnIdle {
            assertEquals("Reusable rules", stored!!.name)
            assertEquals(baseline.rules, stored!!.rules)
            assertEquals(baseline.prompt, stored!!.prompt)
            assertTrue(stored!!.promptEnabled)
            assertEquals(0, applied)
        }
    }
}
