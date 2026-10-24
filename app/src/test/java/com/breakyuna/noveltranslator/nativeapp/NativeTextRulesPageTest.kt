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
class NativeTextRulesPageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private fun state(policy: ProjectTextPolicy = ProjectTextPolicy()) = NativeProjectUiState(
        project = TextProjectSummary(ref, "fixture", 0, RelativePath("source.txt"), 1), page = NativePage.RULES,
        policyDraft = NativePolicyDraft.from(ProjectPolicySnapshot(ref, 0, policy)))

    @Test fun addingOrCancellingARuleNeverPersistsTheProjectImplicitly() {
        var saves = 0; var changes = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(), { changes++ }, { saves++ }, {})
        } } }
        compose.onNodeWithTag("native-policy-save").assertIsNotEnabled()
        compose.onNodeWithTag("native-policy-add").performScrollTo().performClick()
        compose.onNodeWithTag("native-policy-source").performScrollTo().performTextInput("source")
        compose.onNodeWithText(context.getString(R.string.native_cancel)).performClick()
        assertEquals(0, changes); assertEquals(0, saves)
        compose.onNodeWithTag("native-policy-add").performScrollTo().performClick()
        compose.onNodeWithTag("native-policy-source").performScrollTo().performTextInput("source")
        compose.onNodeWithTag("native-policy-target").performScrollTo().performTextInput("target")
        compose.onNodeWithTag("native-policy-apply").performClick()
        assertEquals(1, changes); assertEquals(0, saves)
    }
    @Test fun aConflictingDraftStaysVisibleAndCannotBeSaved() {
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("title", TextRuleKind.TERM, "source", "target")))
        val value = state().copy(policyDraft = NativePolicyDraft(ref, 0, ProjectTextPolicy().fingerprint(), policy, conflict = true))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(value, {}, { fail("Saved conflicting draft") }, {})
        } } }
        compose.onNodeWithTag("native-policy-save").assertIsNotEnabled()
        compose.onNodeWithText("source").performScrollTo().assertIsDisplayed()
    }
    @Test fun draftMetadataIsBoundToTheOriginalGenerationAndRejectsMalformedPayloads() {
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("id", TextRuleKind.TERM, "source", "target")))
        val draft = NativePolicyDraft(ref, 4, ProjectTextPolicy().fingerprint(), policy, CommandId("keep-command"))
        val restored = decodePolicyDraft(encodePolicyDraft(draft))!!
        assertEquals(ref, restored.ref); assertEquals(4L, restored.revision); assertEquals(draft.command, restored.command)
        assertTrue(restored.dirty); assertEquals(policy.fingerprint(), restored.policy.fingerprint())
        assertNull(decodePolicyDraft(encodePolicyDraft(draft).apply { this[2] = "1.5" }))
        assertNull(decodePolicyDraft(encodePolicyDraft(draft).apply { this[7] = "{}" }))
        assertNull(decodePolicyDraft(encodePolicyDraft(draft).apply { this[6] = "yes" }))
    }

    @Test fun lgJsonPreviewAppliesOnlyToDraftAndKeepsPrompt() {
        var changed: ProjectTextPolicy? = null
        val original = ProjectTextPolicy(prompt = "retain prompt", promptEnabled = true)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(original), { changed = it }, { fail("Implicit save") }, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_policy_import)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_policy_import_lg_json)).performScrollTo().performClick()
        compose.onNodeWithTag("native-policy-import-text").performScrollTo()
            .performTextInput("""[{"src":"Alice","dst":"アリス"}]""")
        compose.onNodeWithText(context.getString(R.string.native_policy_preview)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("native-policy-apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(changed)
        compose.onNodeWithTag("native-policy-apply").performClick()
        assertEquals("retain prompt", changed!!.prompt)
        assertTrue(changed!!.promptEnabled)
        assertEquals(TextRuleKind.TERM, changed!!.rules.single().kind)
        assertEquals("アリス", changed!!.rules.single().target)
    }

    @Test fun csvImportRetainsQuotedCommaAndDoesNotSaveImplicitly() {
        var changed: ProjectTextPolicy? = null
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(), { changed = it }, { fail("Implicit save") }, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.native_policy_import)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_policy_import_csv)).performScrollTo().performClick()
        compose.onNodeWithTag("native-policy-import-text").performScrollTo().performTextInput(
            "src,dst,info,regex,case_sensitive\r\n\"Alice, Jr\",アリス,,false,true\r\n")
        compose.onNodeWithText(context.getString(R.string.native_policy_preview)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("native-policy-apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(changed)
        compose.onNodeWithTag("native-policy-apply").performClick()
        assertEquals("Alice, Jr", changed!!.rules.single().source)
    }
}
