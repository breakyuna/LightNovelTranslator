package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
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
class NativeItemProposalDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private fun view(state: ItemProposalState) = ItemProposalSummary(ApprovalRequestId("proposal"), ref, ChatId("chat"),
        RunId("run"), CommandId("command"), 0, ConflictPolicy.REJECT_BATCH, ObjectFingerprint("a".repeat(64)), 2, state)
    private val members = listOf(
        ItemProposalMemberSummary(ItemId(1), 0, 1, true, false, true),
        ItemProposalMemberSummary(ItemId(2), 1, 1, false, true, false))
    private fun detail(id: ItemId) = ItemProposalMember(id, id.value.toInt() - 1, 1, ObjectFingerprint("b".repeat(64)),
        ItemProposalValue("before", TextItemStatus.NONE, null, null),
        ItemProposalValue("after", TextItemStatus.PROCESSED, null, null))

    @Test fun confirmationUsesExactlyTheUserSelectedMembers() {
        val proposal = view(ItemProposalState.PENDING)
        var accepted: Set<ItemId>? = null
        compose.setContent { NativeTheme {
            NativeItemProposalDialog(proposal, { members }, { _, id -> detail(id) }, { null },
                accept = { actual, ids -> assertEquals(proposal.id, actual.id); accepted = ids }, reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("native-proposal-select-1").performClick()
        compose.onNodeWithTag("native-proposal-accept").performClick()
        compose.runOnIdle { assertEquals(setOf(ItemId(2)), accepted) }
    }

    @Test fun partialHistoryShowsReceiptCountsAndCannotGrantAnotherAcceptance() {
        val proposal = view(ItemProposalState.PARTIAL)
        val receipt = ItemProposalReceipt(proposal.command, ObjectFingerprint("c".repeat(64)), 1,
            listOf(ItemId(2)), mapOf(ItemId(1) to ItemProposalRejection.NOT_SELECTED))
        compose.setContent { NativeTheme {
            NativeItemProposalDialog(proposal, { members }, { _, id -> detail(id) }, { receipt },
                accept = { _, _ -> error("History must not authorize") }, reject = {}, dismiss = {})
        } }
        compose.waitForIdle()
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.onNodeWithText(context.getString(R.string.native_item_proposal_saved_count, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_item_proposal_not_selected)).assertIsDisplayed()
        compose.onNodeWithTag("native-proposal-accept").assertDoesNotExist()
    }
}
