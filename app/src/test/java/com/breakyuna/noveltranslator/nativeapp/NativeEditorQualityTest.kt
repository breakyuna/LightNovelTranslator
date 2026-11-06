package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.material3.Surface
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
class NativeEditorQualityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val fingerprint = ObjectFingerprint("a".repeat(64))
    private fun draft() = NativeEditDraft(ref, TextProjectItem(ItemId(1), 0, "hello", "你好",
        TextItemStatus.PROCESSED, fingerprint), 0, fingerprint, "你好", TextItemStatus.PROCESSED, CommandId("draft"))

    @Test fun onlyExplicitCheckInvokesTheCallbackAndLanguageChangesHideOldResults() {
        var requested: TextProofreadingOptions? = null
        val result = TextProofreadingResult(emptyList(), emptyList(), true, fingerprint, TextProofreadingOptions())
        compose.setContent { NativeTheme { Surface {
            NativeEditorQuality(draft(), false, true, result) { requested = it }
        } } }
        assertNull(requested)
        compose.onNodeWithText(context.getString(R.string.native_quality_clear)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_remote_target) + ": zh").performClick()
        compose.onNodeWithText("en").performClick()
        compose.onNodeWithText(context.getString(R.string.native_quality_clear)).assertDoesNotExist()
        assertNull(requested)
        compose.onNodeWithTag("native-quality-check").performClick()
        assertEquals(ProofreadingLanguage.EN, requested!!.target)
    }
    @Test fun unsavedRulesDisableInspectionAndDoNotDisplayAnOldClearResult() {
        val result = TextProofreadingResult(emptyList(), emptyList(), true, fingerprint, TextProofreadingOptions())
        compose.setContent { NativeTheme { Surface {
            NativeEditorQuality(draft(), false, false, result) { fail("Inspected unsaved rules") }
        } } }
        compose.onNodeWithTag("native-quality-check").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_quality_clear)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.native_quality_unavailable)).assertIsDisplayed()
    }
}
