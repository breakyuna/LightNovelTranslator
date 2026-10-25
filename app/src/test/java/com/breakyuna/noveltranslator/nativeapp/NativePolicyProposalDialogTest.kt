package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativePolicyProposalDialogTest {
    @get:Rule val compose = createComposeRule()
    private val proposal = PolicyChangeProposal(ApprovalRequestId("policy-proposal"),
        LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1)), ChatId("chat"), RunId("run"),
        CommandId("command"), 1, ProjectTextPolicy(),
        ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "cat", "猫")), "instructions", true))

    @Test fun onlyAnExplicitConfirmationPassesTheFrozenProposalToTheExecutor() {
        val view = proposal.summary(TextApprovalState.PENDING)
        var calls = 0
        compose.setContent { NativeTheme {
            NativePolicyProposalDialog(view, { proposal }, { null }, accept = { accepted ->
                assertEquals(proposal.digest, accepted.digest); assertEquals(proposal.id, accepted.id); calls++
            }, reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        assertEquals(0, calls)
        compose.onNodeWithTag("native-policy-proposal-accept").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun settledHistoryHasNoSecondGrantButton() {
        compose.setContent { NativeTheme {
            NativePolicyProposalDialog(proposal.summary(TextApprovalState.COMMITTED), { proposal }, { null },
                accept = { error("History cannot grant authority") }, reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("native-policy-proposal-accept").assertDoesNotExist()
    }
}
