package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.TextLiteralReplacer
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
class NativeEditorReplacementTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewDoesNotApplyAndChangingTheFindTextDisablesTheOldPreview() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val fp = ObjectFingerprint("a".repeat(64))
        val draft = NativeEditDraft(ref, TextProjectItem(ItemId(1), 0, "source", "one", TextItemStatus.PROCESSED, fp),
            0, fp, "one", TextItemStatus.PROCESSED, CommandId("draft"))
        val prepared = mutableStateOf<NativeEditorReplacement?>(null)
        var applies = 0; var previews = 0
        compose.setContent { NativeTheme {
            NativeEditorReplacementDialog(draft, false, null, prepared.value, {}, { options ->
                previews++
                prepared.value = NativeEditorReplacement(ref, draft.command, TextLiteralReplacer.preview(draft.text, options))
            }, { applies++ }, {})
        } }
        compose.onNodeWithTag("native-replace-find").performScrollTo().performTextInput("one")
        compose.onNodeWithTag("native-replace-with").performScrollTo().performTextInput("two")
        compose.onNodeWithTag("native-replace-apply").assertIsNotEnabled()
        compose.onNodeWithTag("native-replace-preview").performScrollTo().performClick()
        assertEquals(1, previews); assertEquals(0, applies)
        compose.onNodeWithTag("native-replace-apply").assertIsEnabled()
        compose.onNodeWithTag("native-replace-find").performScrollTo().performTextInput("x")
        compose.onNodeWithTag("native-replace-apply").assertIsNotEnabled()
        assertEquals(0, applies)
    }
}
