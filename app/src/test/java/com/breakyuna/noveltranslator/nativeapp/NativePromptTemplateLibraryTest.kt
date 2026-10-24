package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
class NativePromptTemplateLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))

    @Test fun selectingTemplateOnlyHandsItToTheEditorWithoutWritingTheLibrary() {
        val template = PromptTemplate("t1", "Existing", body = "Hello {{name}}")
        var snapshot = PromptTemplateLibrarySnapshot(PromptTemplateSet(listOf(template)),
            PromptTemplateCodec.fingerprint(PromptTemplateSet(listOf(template))))
        var writes = 0
        var used: PromptTemplate? = null
        val actions = NativePromptTemplateActions({ snapshot }, { expected, next ->
            assertEquals(snapshot.fingerprint, expected)
            writes++
            snapshot = PromptTemplateLibrarySnapshot(next, PromptTemplateCodec.fingerprint(next))
            snapshot
        })
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "draft body", actions, true, {}) { used = it }
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-template-t1").fetchSemanticsNodes().isNotEmpty()
        }
        // The list renders from the same effect that clears `busy`; wait for the row's gate
        // explicitly so a click cannot land on the still-disabled button.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-t1").assertIsEnabled(); true }.getOrDefault(false)
        }
        // Gesture injection into this dialog's nested scroll/lazy content is not reliable in
        // Robolectric; invoke the row's semantic action so the selection wiring stays covered.
        compose.onNodeWithTag("native-template-t1")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-policy-apply").assertIsEnabled(); true }.getOrDefault(false)
        }
        assertNull(used)
        compose.onNodeWithTag("native-policy-apply").performClick()
        compose.runOnIdle {
            assertEquals("t1", used!!.id)
            assertEquals("Hello {{name}}", used!!.body)
            assertEquals(0, writes)
        }
    }

    @Test fun savingCurrentBodyCreatesATemplateWithoutUsingIt() {
        var snapshot = PromptTemplateLibrarySnapshot(PromptTemplateSet(emptyList()),
            PromptTemplateCodec.fingerprint(PromptTemplateSet(emptyList())))
        var writes = 0
        var used: PromptTemplate? = null
        val actions = NativePromptTemplateActions({ snapshot }, { expected, next ->
            assertEquals(snapshot.fingerprint, expected)
            writes++
            snapshot = PromptTemplateLibrarySnapshot(next, PromptTemplateCodec.fingerprint(next))
            snapshot
        })
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "New body", actions, true, {}) { used = it }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-prompt-template-name").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-prompt-template-name").performScrollTo().performTextInput("Reusable prompt")
        compose.onNodeWithTag("native-template-save-new").performScrollTo().performClick()
        compose.waitUntil(5_000) { writes == 1 }
        compose.runOnIdle {
            val saved = snapshot.templates.templates.single()
            assertEquals("Reusable prompt", saved.name)
            assertEquals("New body", saved.body)
            assertTrue(saved.enabled)
            assertNull(used)
            assertEquals(1, writes)
        }
    }

    @Test fun confirmingDeleteRemovesTheSelectedTemplateInsteadOfOverwritingItsBody() {
        val first = PromptTemplate("remove-me", "Delete me", body = "original body")
        val second = PromptTemplate("retain", "Retain me", body = "retained body")
        val initial = PromptTemplateSet(listOf(first, second))
        var snapshot = PromptTemplateLibrarySnapshot(initial, PromptTemplateCodec.fingerprint(initial))
        var writes = 0
        val actions = NativePromptTemplateActions({ snapshot }, { expected, next ->
            assertEquals(snapshot.fingerprint, expected)
            writes++
            PromptTemplateLibrarySnapshot(next, PromptTemplateCodec.fingerprint(next)).also { snapshot = it }
        })
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "unrelated current editor", actions, true, {}) { fail("Used template while deleting") }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-remove-me").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-remove-me")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-delete").performScrollTo().performClick()
        compose.onNodeWithTag("native-template-confirm").performClick()
        compose.waitUntil(5_000) { writes == 1 }
        compose.runOnIdle {
            assertEquals(listOf("retain"), snapshot.templates.templates.map { it.id })
            assertEquals("retained body", snapshot.templates.templates.single().body)
        }
    }

    @Test fun savingSelectedEditsPreservesItsIdentityAndNeverUsesTheProjectEditorBody() {
        val original = PromptTemplateSet(listOf(PromptTemplate("selected", "Existing", body = "saved body")))
        var snapshot = PromptTemplateLibrarySnapshot(original, PromptTemplateCodec.fingerprint(original))
        var writes = 0
        val actions = NativePromptTemplateActions({ snapshot }, { expected, next ->
            assertEquals(snapshot.fingerprint, expected); writes++
            PromptTemplateLibrarySnapshot(next, PromptTemplateCodec.fingerprint(next)).also { snapshot = it }
        })
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "unrelated project editor", actions, true, {}) { fail("Implicit use") }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-selected").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-selected").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-body").performScrollTo().performTextReplacement("edited {{name}}")
        compose.onNodeWithTag("native-template-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("native-template-save-edits").performScrollTo().performClick()
        compose.waitUntil(5_000) { writes == 1 }
        compose.runOnIdle {
            val saved = snapshot.templates.templates.single()
            assertEquals("selected", saved.id); assertEquals("edited {{name}}", saved.body)
            assertFalse(saved.enabled)
        }
    }

    @Test fun movingSelectedTemplateChangesOnlyOrderAndKeepsPendingBodyEditsUnsaved() {
        val original = PromptTemplateSet(listOf(PromptTemplate("a", "A", order = 0, body = "original A"),
            PromptTemplate("b", "B", order = 1, body = "original B")))
        var snapshot = PromptTemplateLibrarySnapshot(original, PromptTemplateCodec.fingerprint(original))
        var writes = 0
        val actions = NativePromptTemplateActions({ snapshot }, { expected, next ->
            assertEquals(snapshot.fingerprint, expected); writes++
            PromptTemplateLibrarySnapshot(next, PromptTemplateCodec.fingerprint(next)).also { snapshot = it }
        })
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "project body", actions, true, {}) { fail("Implicit use") }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-b").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-b").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-body").performScrollTo().performTextReplacement("pending edit")
        compose.onNodeWithTag("native-template-move-up").performScrollTo().performClick()
        compose.waitUntil(5_000) { writes == 1 }
        compose.runOnIdle {
            assertEquals(listOf("b", "a"), snapshot.templates.ordered().map { it.id })
            assertEquals("original B", snapshot.templates.ordered().first().body)
        }
        compose.onNodeWithTag("native-template-body").assertTextContains("pending edit")
        compose.onNodeWithTag("native-template-move-up").assertIsNotEnabled()
    }

    @Test fun switchingAwayFromEditedBodyRequiresExplicitDiscard() {
        val initial = PromptTemplateSet(listOf(PromptTemplate("a", "A", body = "original A"), PromptTemplate("b", "B", body = "original B")))
        val snapshot = PromptTemplateLibrarySnapshot(initial, PromptTemplateCodec.fingerprint(initial))
        var used: PromptTemplate? = null
        compose.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "project body", NativePromptTemplateActions({ snapshot }, { _, _ -> fail("Implicit write"); snapshot }),
                true, {}) { used = it }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-a").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-a").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-body").performScrollTo().performTextReplacement("pending draft")
        compose.onNodeWithTag("native-template-b").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-discard").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-template-body").assertTextContains("original B")
        compose.onNodeWithTag("native-policy-apply").performClick()
        compose.runOnIdle { assertEquals("b", used!!.id); assertEquals("original B", used!!.body) }
    }

    @Test fun restoredDraftCannotOverwriteALibraryChangedWhileTheScreenWasRecreated() {
        val original = PromptTemplateSet(listOf(PromptTemplate("a", "A", body = "original")))
        var snapshot = PromptTemplateLibrarySnapshot(original, PromptTemplateCodec.fingerprint(original))
        val restoration = StateRestorationTester(compose)
        var writes = 0
        val actions = NativePromptTemplateActions({ snapshot }, { _, _ -> writes++; fail("Stale draft write"); snapshot })
        restoration.setContent { NativeTheme {
            NativePromptTemplateLibrary(ref, "project body", actions, true, {}) { fail("Used stale restored draft") }
        } }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-a").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-a").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-body").performScrollTo().performTextReplacement("pending draft")
        compose.runOnIdle {
            val newer = PromptTemplateSet(listOf(PromptTemplate("a", "A", body = "new library content")))
            snapshot = PromptTemplateLibrarySnapshot(newer, PromptTemplateCodec.fingerprint(newer))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("native-template-a").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-template-body").assertTextContains("pending draft")
        compose.onNodeWithTag("native-template-save-edits").assertIsNotEnabled()
        compose.onNodeWithTag("native-policy-apply").assertIsNotEnabled()
        compose.onNodeWithTag("native-template-a").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("native-template-discard").performClick()
        compose.onNodeWithTag("native-template-body").assertTextContains("new library content")
        assertEquals(0, writes)
    }
}
