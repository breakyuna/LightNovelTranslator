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
        compose.onNodeWithTag("native-template-t1").performScrollTo().performClick()
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
}
