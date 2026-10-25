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

    @Test fun sectionNavigationKeepsThePageAndShowsDistinctTitlesAndSaveTargets() {
        val section = androidx.compose.runtime.mutableStateOf(NativeRuleSection.PRE_REPLACE)
        var changes = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(), { changes++ }, { fail("Implicit save") }, {},
                section = section.value, selectSection = { section.value = it })
        } } }
        compose.onNodeWithTag("native-rules-title")
            .assertTextEquals(context.getString(R.string.native_rules_title_pre))
        compose.onNodeWithTag("native-rules-save-target")
            .assertTextContains(context.getString(R.string.native_rules_title_pre), substring = true)
        compose.onNodeWithTag("native-rules-section-glossary").performClick()
        compose.runOnIdle { assertEquals(NativeRuleSection.GLOSSARY, section.value) }
        compose.onNodeWithTag("native-rules-title")
            .assertTextEquals(context.getString(R.string.native_rules_title_glossary))
        compose.onNodeWithTag("native-rules-save-target")
            .assertTextContains(context.getString(R.string.native_rules_title_glossary), substring = true)
        compose.onNodeWithTag("native-policy-save")
            .assertTextEquals(context.getString(R.string.native_rules_save_section,
                context.getString(R.string.native_rules_title_glossary)))
        assertEquals(0, changes)
    }

    @Test fun glossarySearchFiltersAllFieldsAndBatchDisableOnlyChangesSelectedRules() {
        var changed: ProjectTextPolicy? = null
        val policy = ProjectTextPolicy(listOf(
            ProjectTextRule("a", TextRuleKind.TERM, "Alice", "アリス", note = "main"),
            ProjectTextRule("b", TextRuleKind.TERM, "Bob", "ボブ"),
            ProjectTextRule("c", TextRuleKind.TERM, "Carol", "キャロル", note = "Alice note"),
        ))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(policy), { changed = it }, { fail("Implicit save") }, {}, section = NativeRuleSection.GLOSSARY)
        } } }
        compose.onNodeWithTag("native-rules-search").performTextInput("alice")
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rule-a"))
        compose.onNodeWithTag("native-rule-a").assertIsDisplayed()
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rule-c"))
        compose.onNodeWithTag("native-rule-c").assertIsDisplayed()
        compose.onNodeWithTag("native-rule-b").assertDoesNotExist()
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rule-a"))
        compose.onNodeWithTag("native-rule-select-a").performScrollTo().performClick()
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rules-toolbar"))
        compose.onNodeWithTag("native-rules-disable-selected").performClick()
        val result = requireNotNull(changed)
        assertFalse(result.rules.first { it.id == "a" }.enabled)
        assertTrue(result.rules.first { it.id == "b" }.enabled)
        assertTrue(result.rules.first { it.id == "c" }.enabled)
        assertEquals(listOf("a", "b", "c"), result.rules.map { it.id })
    }

    @Test fun glossaryShowsMissingTermEvidenceFromAnExistingQualityIndexWithoutScanning() {
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "アリス")))
        val fact = TextQualityFact(ItemId(1), ObjectFingerprint("a".repeat(64)),
            TextProofreadingResult(setOf(TextWarningCode.GLOSSARY), listOf("term"), true,
                policy.fingerprint(), TextProofreadingOptions()))
        val index = TextQualityIndex(ref, 0, TextItemQuery(), TextProofreadingOptions(),
            policy.fingerprint(), listOf(fact), 1)
        val view = index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, termRuleId = "term"))
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(policy).copy(qualityView = view), {}, { fail("Implicit save") }, {},
                section = NativeRuleSection.GLOSSARY)
        } } }
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rule-term"))
        compose.onNodeWithText(context.getString(R.string.native_rules_evidence_missing, 1))
            .assertIsDisplayed()
    }

    @Test fun promptSectionShowsTheDraftBodyUnsavedStateAndTemplateLibraryEntry() {
        val policy = ProjectTextPolicy(prompt = "Keep the tone.", promptEnabled = true)
        val draft = NativePolicyDraft(ref, 0, ProjectTextPolicy().fingerprint(), policy)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state().copy(policyDraft = draft), {}, { fail("Implicit save") }, {},
                section = NativeRuleSection.PROMPTS)
        } } }
        compose.onNodeWithTag("native-policy-prompt").performScrollTo().assertTextContains("Keep the tone.")
        compose.onNodeWithTag("native-rules-prompt-unsaved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-prompt-template-open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-rules-title")
            .assertTextEquals(context.getString(R.string.native_rules_title_prompts))
    }

    @Test fun backFromAReplacementSectionReturnsToMoreWithoutChangingSections() {
        var backs = 0; var sections = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state(), {}, { fail("Implicit save") }, {},
                section = NativeRuleSection.POST_REPLACE, selectSection = { sections++ }, back = { backs++ })
        } } }
        compose.onNodeWithTag("native-rules-title")
            .assertTextEquals(context.getString(R.string.native_rules_title_post))
        compose.onNodeWithTag("native-rules-back").performClick()
        assertEquals(1, backs)
        assertEquals(0, sections)
    }

    @Test fun aSaveErrorKeepsTheDraftAndNamesTheFailingRule() {
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("bad", TextRuleKind.PRE_REPLACE, "(", "",
            regex = true)))
        val draft = NativePolicyDraft(ref, 0, ProjectTextPolicy().fingerprint(), policy)
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeTextRulesPage(state().copy(policyDraft = draft, policyInvalidRuleId = "bad"), {},
                { fail("Saved invalid rule") }, {}, section = NativeRuleSection.PRE_REPLACE)
        } } }
        compose.onNodeWithTag("native-rules-invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-rules-invalid")
            .assertTextContains(context.getString(R.string.native_rules_invalid_rule, "("))
        compose.onNodeWithTag("native-policy-page").performScrollToNode(hasTestTag("native-rule-bad"))
        compose.onNodeWithTag("native-rule-invalid-bad").assertIsDisplayed()
    }
}
