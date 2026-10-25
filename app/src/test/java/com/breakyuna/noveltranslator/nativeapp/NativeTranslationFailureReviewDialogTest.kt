package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RemoteTranslationOptions
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TranslationFailureMember
import com.breakyuna.noveltranslator.nativecore.domain.TranslationFailureReceipt
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueRejection
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueResult
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueTaskMeta
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskCommandAction
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureBatch
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureBatchId
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureDecisionReceipt
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureEvidence
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueue
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskReview
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskSpec
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueuedTask
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import com.breakyuna.noveltranslator.nativecore.engine.TranslationFailureAdjudicationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Failure adjudication reads durable evidence and waits for a decision receipt. These tests do not
 * start a runner, scheduler drain or any paid request.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeTranslationFailureReviewDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val spec = TranslationTaskSpec(ref, RunIdentity(ref.projectId, null, RunId("run"), CommandId("command")),
        ModelConfigurationRef("model", 1), 1, 0, 40, false, RemoteTranslationOptions("en"))
    private val identity = spec.queueIdentity()
    private val batchId = TranslationTaskFailureBatchId("batch-1")

    @Test fun selectAllRetriesEverySelectedMemberAndWaitsForTheReceipt() {
        val members = listOf(member(1), member(2))
        val fixture = reviewTask(members)
        var loads = 0
        var changed = 0
        var retried: Triple<TranslationTaskQueueIdentity, TranslationTaskFailureBatchId, List<ItemId>>? = null
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { loads++; fixture.task },
                    loadEvidence = { requested, after ->
                        assertEquals(identity, requested)
                        assertEquals(0L, after)
                        evidence(members)
                    },
                    retry = { requested, batch, ids ->
                        retried = Triple(requested, batch, ids)
                        applied(fixture.snapshot, fixture.task, batch, TranslationTaskCommandAction.RETRY_MEMBERS, ids)
                    },
                    skip = { _, _, _ -> error("Unexpected skip") },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                    onChanged = { changed++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-select-all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-select-all").performClick()
        compose.runOnIdle {
            compose.onNodeWithTag("native-failure-retry").assertIsEnabled()
            compose.onNodeWithTag("native-failure-selected").assertTextContains("2", substring = true)
        }
        compose.onNodeWithTag("native-failure-retry").performClick()
        compose.waitUntil(5_000) { retried != null }
        compose.waitUntil(5_000) { loads >= 2 && changed >= 1 }
        compose.runOnIdle {
            assertEquals(identity, retried!!.first)
            assertEquals(batchId, retried!!.second)
            assertEquals(listOf(ItemId(1), ItemId(2)), retried!!.third)
            assertNotNull(compose.onAllNodesWithTag("native-failure-success").fetchSemanticsNodes().firstOrNull())
            assertNotNull(compose.onAllNodesWithTag("native-failure-receipt").fetchSemanticsNodes().firstOrNull())
        }
    }

    @Test fun uncertainReceiptCannotBeRetriedOrSkippedButCanBeKept() {
        val member = member(1, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT)
        val fixture = reviewTask(listOf(member))
        var kept: List<ItemId>? = null
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { fixture.task },
                    loadEvidence = { _, _ -> evidence(listOf(member)) },
                    retry = { _, _, _ -> error("Uncertain receipt must not be retried") },
                    skip = { _, _, _ -> error("Uncertain receipt must not be skipped") },
                    keep = { _, _, ids ->
                        kept = ids
                        applied(fixture.snapshot, fixture.task, batchId, TranslationTaskCommandAction.KEEP_ERROR, ids)
                    },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-member-batch-1-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-uncertain-batch-1-1").assertExists()
        compose.onNodeWithTag("native-failure-member-batch-1-1").performClick()
        compose.runOnIdle {
            compose.onNodeWithTag("native-failure-retry").assertIsNotEnabled()
            compose.onNodeWithTag("native-failure-skip").assertIsNotEnabled()
            compose.onNodeWithTag("native-failure-keep").assertIsEnabled()
        }
        compose.onNodeWithTag("native-failure-keep").performClick()
        compose.waitUntil(5_000) { kept != null }
        compose.runOnIdle { assertEquals(listOf(ItemId(1)), kept) }
    }

    @Test fun committedAndTerminalMembersAreNotSelectable() {
        val members = listOf(member(1), member(2, committed = true), member(3, terminal = true))
        val fixture = reviewTask(members)
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { fixture.task },
                    loadEvidence = { _, _ -> evidence(members) },
                    retry = { _, _, _ -> error("Unexpected retry") },
                    skip = { _, _, _ -> error("Unexpected skip") },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-member-batch-1-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-select-all").performClick()
        compose.onNodeWithTag("native-failure-list")
            .performScrollToNode(hasTestTag("native-failure-member-batch-1-2"))
        compose.onNodeWithTag("native-failure-member-batch-1-2").assertIsNotEnabled()
        compose.onNodeWithTag("native-failure-list")
            .performScrollToNode(hasTestTag("native-failure-member-batch-1-3"))
        compose.onNodeWithTag("native-failure-member-batch-1-3").assertIsNotEnabled()
        compose.runOnIdle {
            compose.onNodeWithTag("native-failure-selected").assertTextContains("1", substring = true)
        }
    }

    @Test fun skipAndTerminateUseTheExactTaskIdentityAndRefreshThenClose() {
        val member = member(1)
        var fixture = reviewTask(listOf(member))
        var skipped: Triple<TranslationTaskQueueIdentity, TranslationTaskFailureBatchId, List<ItemId>>? = null
        var terminated: TranslationTaskQueueIdentity? = null
        var changed = 0
        var dismissed = 0
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { fixture.task },
                    loadEvidence = { _, _ -> evidence(listOf(member)) },
                    retry = { _, _, _ -> error("Unexpected retry") },
                    skip = { requested, batch, ids ->
                        skipped = Triple(requested, batch, ids)
                        val queue = (fixture.snapshot.queue.skipMembers(requested, CommandId("skip"), batch, ids)
                            as TranslationQueueResult.Applied)
                        fixture = reviewTask(listOf(member), queue.queue, queue.task)
                        TranslationFailureAdjudicationResult(queue,
                            TranslationTaskFailureDecisionReceipt(CommandId("skip-receipt"),
                                TranslationTaskCommandAction.SKIP_MEMBERS, batch, ids, 0))
                    },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { requested ->
                        terminated = requested
                        val queue = (fixture.snapshot.queue.terminate(requested, CommandId("terminate"))
                            as TranslationQueueResult.Applied)
                        fixture = reviewTask(listOf(member), queue.queue, queue.task)
                        TranslationFailureAdjudicationResult(queue)
                    },
                    dismiss = { dismissed++ },
                    onChanged = { changed++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-select-all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-select-all").performClick()
        compose.onNodeWithTag("native-failure-skip").performClick()
        compose.waitUntil(5_000) { skipped != null }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-not-awaiting").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(identity, skipped!!.first)
            assertEquals(batchId, skipped!!.second)
            assertEquals(listOf(ItemId(1)), skipped!!.third)
            assertTrue(changed >= 1)
        }
        compose.onNodeWithTag("native-failure-terminate").performClick()
        compose.onNodeWithTag("native-failure-terminate-confirm").performClick()
        compose.waitUntil(5_000) { dismissed == 1 }
        compose.runOnIdle {
            assertEquals(identity, terminated)
            assertEquals(TranslationTaskQueueState.CANCELLED, fixture.task.state)
            assertTrue(changed >= 2)
        }
    }

    @Test fun appliedQueueCommandWithoutADecisionReceiptIsUnconfirmed() {
        val member = member(1)
        val fixture = reviewTask(listOf(member))
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { fixture.task },
                    loadEvidence = { _, _ -> evidence(listOf(member)) },
                    retry = { _, _, _ ->
                        TranslationFailureAdjudicationResult(
                            TranslationQueueResult.Applied(fixture.snapshot.queue, fixture.task))
                    },
                    skip = { _, _, _ -> error("Unexpected skip") },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-select-all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-select-all").performClick()
        compose.onNodeWithTag("native-failure-retry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-unconfirmed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-failure-success").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun rejectionShowsTheExactReasonAndNeverClaimsSuccess() {
        val member = member(1)
        val fixture = reviewTask(listOf(member))
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { fixture.task },
                    loadEvidence = { _, _ -> evidence(listOf(member)) },
                    retry = { _, _, _ ->
                        TranslationFailureAdjudicationResult(TranslationQueueResult.Rejected(
                            fixture.snapshot.queue, TranslationQueueRejection.UNCERTAIN_RECEIPT))
                    },
                    skip = { _, _, _ -> error("Unexpected skip") },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-select-all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-select-all").performClick()
        compose.onNodeWithTag("native-failure-retry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-rejection").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-failure-rejection")
            .assertTextContains("UNCERTAIN_RECEIPT", substring = true)
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-failure-success").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun foreignEvidenceRunIsRejectedAndNoSourceTextIsRendered() {
        val members = listOf(member(1))
        val fixture = reviewTask(members)
        var requestedIdentity: TranslationTaskQueueIdentity? = null
        compose.setContent {
            NativeTheme {
                NativeTranslationFailureReviewDialog(
                    identity = identity,
                    loadTask = { requestedIdentity = it; fixture.task },
                    loadEvidence = { _, _ ->
                        evidence(members, run = RunIdentity(ref.projectId, null, RunId("foreign"), CommandId("foreign")))
                    },
                    retry = { _, _, _ -> error("Unexpected retry") },
                    skip = { _, _, _ -> error("Unexpected skip") },
                    keep = { _, _, _ -> error("Unexpected keep") },
                    terminate = { error("Unexpected terminate") },
                    dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-failure-load-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(identity, requestedIdentity)
            assertTrue(compose.onAllNodesWithText("SOURCE-SENTINEL", substring = true)
                .fetchSemanticsNodes().isEmpty())
        }
    }

    private fun member(
        id: Long,
        receipt: TranslationFailureReceipt = TranslationFailureReceipt.RECEIVED,
        committed: Boolean = false,
        terminal: Boolean = false,
    ): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint(id.toString().padStart(64, 'a')),
        failure = ModelFailureKind.NETWORK,
        receipt = receipt,
        usage = if (receipt == TranslationFailureReceipt.RECEIVED) UsageSource.ACTUAL else UsageSource.UNKNOWN,
        committed = committed,
        terminal = terminal,
    )

    private fun evidence(
        members: List<TranslationFailureMember>,
        run: RunIdentity = spec.run,
    ): TranslationTaskFailureEvidence = TranslationTaskFailureEvidence(
        review = TranslationTaskReview(ref, run, "NEEDS_REVIEW", 1, 0, members.size, 1, 0,
            ObjectFingerprint("e".repeat(64))),
        projectRevision = 1,
        members = members.sortedBy { it.itemId.value },
        next = null,
    )

    private fun reviewTask(
        members: List<TranslationFailureMember>,
        queue: TranslationTaskQueue = TranslationTaskQueue.empty()
            .enqueue(identity).let { (it as TranslationQueueResult.Applied).queue }
            .dispatchNext().let { (it as TranslationQueueResult.Applied).queue }
            .recordFailure(identity, TranslationTaskFailureBatch(batchId, members))
            .let { (it as TranslationQueueResult.Applied).queue },
        task: TranslationQueuedTask = queue.task(identity)!!,
    ): Fixture {
        val snapshot = TranslationQueueSnapshot.of(
            ref = ref,
            revision = 1,
            queue = queue,
            specs = listOf(spec),
            metas = listOf(TranslationQueueTaskMeta(1, 1)),
            updatedAtMillis = 1,
        )
        return Fixture(snapshot, task)
    }

    private fun applied(
        snapshot: TranslationQueueSnapshot,
        task: TranslationQueuedTask,
        batch: TranslationTaskFailureBatchId,
        action: TranslationTaskCommandAction,
        ids: List<ItemId>,
    ): TranslationFailureAdjudicationResult = TranslationFailureAdjudicationResult(
        queue = TranslationQueueResult.Applied(snapshot.queue, task),
        receipt = TranslationTaskFailureDecisionReceipt(CommandId("receipt-${action.name}"), action,
            batch, ids, 0),
    )

    private data class Fixture(val snapshot: TranslationQueueSnapshot, val task: TranslationQueuedTask)
}
