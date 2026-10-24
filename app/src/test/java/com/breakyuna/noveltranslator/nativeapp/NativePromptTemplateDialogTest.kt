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
class NativePromptTemplateDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun variablesArePrefilledButOnlyExplicitApplyChangesTheDraft() {
        val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
        val project = TextProjectSummary(ref, "Project", 0, RelativePath("source.txt"), 1,
            settings = ProjectSettings(targetLanguage = "ja", sourceLanguage = "de"))
        val baseline = ProjectTextPolicy(prompt = "Translate {{target_language}}: {{style}}", promptEnabled = false)
        var applied: ProjectTextPolicy? = null
        compose.setContent { NativeTheme {
            NativePromptTemplateDialog(project, baseline, true, {}) { expected, result ->
                assertEquals(baseline.fingerprint(), expected); applied = result
            }
        } }
        compose.onNodeWithTag("native-prompt-template-parse").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-prompt-variable-target_language").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-prompt-variable-target_language").assertTextContains("ja")
        compose.onNodeWithTag("native-prompt-variable-style").performScrollTo().performTextInput("formal")
        compose.onNodeWithTag("native-prompt-template-preview").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-policy-apply").assertIsEnabled(); true }.getOrDefault(false)
        }
        assertNull(applied)
        compose.onNodeWithTag("native-policy-apply").performClick()
        compose.runOnIdle { assertEquals("Translate ja: formal", applied!!.prompt); assertFalse(applied!!.promptEnabled) }
    }
}
