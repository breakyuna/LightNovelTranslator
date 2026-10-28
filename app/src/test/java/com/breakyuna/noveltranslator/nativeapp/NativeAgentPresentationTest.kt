package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.AgentChatLine
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessageDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class NativeAgentPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun toolReceiptsLoadOnlyWhenOpened() {
        var loads = 0
        compose.setContent { NativeTheme { Surface(Modifier.fillMaxSize()) {
            NativeAgentBubble(AgentChatLine("tool", "TOOL", "lookup : complete"), true, {}, load = {
                loads++
                AgentMessageDetail("tool", "TOOL", "unique receipt")
            })
        } } }
        compose.onNodeWithText("unique receipt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loads) }
        compose.onNodeWithText("lookup : complete").performClick()
        compose.onNodeWithText("unique receipt").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loads) }
        compose.onNodeWithText("lookup : complete").performClick()
        compose.onNodeWithText("unique receipt").assertDoesNotExist()
    }

    @Test fun messageDetailsKeepReasoningCollapsedAndRenderTheReply() {
        compose.setContent { NativeTheme {
            NativeAgentMessageDialog(AgentMessageDetail("reply", "ASSISTANT", "# Heading\n\n**bold reply**",
                reasoning = "private reasoning", tools = "tool payload"), {})
        } }
        compose.waitUntil { compose.onAllNodesWithText("Heading").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Heading").assertIsDisplayed()
        compose.onNodeWithText("bold reply").assertIsDisplayed()
        compose.onNodeWithText("# Heading").assertDoesNotExist()
        compose.onNodeWithText("private reasoning").assertDoesNotExist()
        compose.onNodeWithText("tool payload").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.native_agent_reasoning)).performClick()
        compose.waitUntil { compose.onAllNodesWithText("private reasoning").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("private reasoning").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_close)).assertIsDisplayed()
    }
}
