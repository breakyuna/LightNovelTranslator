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
class NativePdfPageProposalDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private fun view(state: PdfPageProposalState) = PdfPageProposalSummary(ApprovalRequestId("proposal"), ref,
        ChatId("chat"), RunId("run"), CommandId("command"), 4, 2, "a".repeat(64), 3,
        ConflictPolicy.REJECT_BATCH, ObjectFingerprint("b".repeat(64)), 2, state)
    private val members = listOf(
        PdfPageChangeMemberSummary(1, PdfPageMode.TRANSLATE, PdfPageMode.KEEP),
        PdfPageChangeMemberSummary(3, PdfPageMode.TRANSLATE, PdfPageMode.OMIT))

    @Test fun confirmationUsesExactlyTheSelectedPages() {
        val proposal = view(PdfPageProposalState.PENDING)
        var accepted: Set<Int>? = null
        compose.setContent { NativeTheme {
            NativePdfPageProposalDialog(proposal, { members }, { null },
                accept = { actual, pages -> assertEquals(proposal.id, actual.id); accepted = pages },
                reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("native-pdf-proposal-select-3").performClick()
        compose.onNodeWithTag("native-pdf-proposal-accept").performClick()
        compose.runOnIdle { assertEquals(setOf(1), accepted) }
    }

    @Test fun settledReceiptHasNoSecondGrantButtonAndShowsItsPageState() {
        val receipt = PdfPageProposalReceipt(CommandId("command"), ObjectFingerprint("c".repeat(64)), 5,
            listOf(1), mapOf(3 to PdfPageProposalRejection.STALE_PAGE), 9)
        compose.setContent { NativeTheme {
            NativePdfPageProposalDialog(view(PdfPageProposalState.PARTIAL), { members }, { receipt },
                accept = { _, _ -> error("History cannot grant authority") }, reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("native-pdf-proposal-accept").assertDoesNotExist()
        compose.onNodeWithTag("native-pdf-proposal-page-3").assertExists()
    }
}
