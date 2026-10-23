package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
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
class NativeEditorNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun dirtyNavigationRequiresDiscardAndNeverCallsSaveImplicitly() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val fp = ObjectFingerprint("a".repeat(64))
        val draft = NativeEditDraft(ref, TextProjectItem(ItemId(1), 0, "source", "", TextItemStatus.NONE, fp),
            0, fp, "draft", TextItemStatus.PROCESSED, CommandId("draft"))
        var moves = 0; var saves = 0
        compose.setContent { NativeTheme {
            Editor(draft, false, {}, {}, {}, { saves++ }, {}, {}, onNavigate = { direction, discard ->
                assertEquals(TextItemDirection.NEXT, direction); assertTrue(discard); moves++
            })
        } }
        compose.onNodeWithText(context.getString(R.string.native_editor_next)).performClick()
        assertEquals(0, moves)
        compose.onNodeWithText(context.getString(R.string.native_editor_move_confirm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_keep_editing)).performClick()
        assertEquals(0, moves)
        compose.onNodeWithText(context.getString(R.string.native_editor_next)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_discard)).performClick()
        assertEquals(1, moves); assertEquals(0, saves)
    }
}
