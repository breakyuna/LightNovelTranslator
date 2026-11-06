package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalState
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalBinding
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberReceipt
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalConsumption
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalPreparation
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalSettlement
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mixed approval planning, order, recovery and one-shot UI entry; no project or file writes. */
class NativeMixedApprovalCoordinatorTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatId("chat")
    private val run = RunId("run")

    @Test
    fun planFreezesAllKindsWithOneDeterministicParentCommand() {
        val children = listOf(
            child(MixedApprovalMemberKind.ITEM, "item-1", selection = listOf("1", "2"),
                expected = listOf(fingerprint("a"), fingerprint("b")), objects = 2),
            child(MixedApprovalMemberKind.POLICY, "policy-1", added = 1, updated = 2, deleted = 3),
            child(MixedApprovalMemberKind.PDF_PAGE, "pdf-1", selection = listOf("1", "2"), objects = 2),
            child(MixedApprovalMemberKind.ASSET_IMPORT, "asset-1", target = "assets/book.txt"),
            child(MixedApprovalMemberKind.WORKSPACE_FILE, "workspace-1",
                selection = listOf("work/a.md"), roots = "ws-root", paths = listOf("work/a.md")),
        )
        val first = NativeMixedApprovalRules.plan(ref, chat, run, children, 7, 100)
        val second = NativeMixedApprovalRules.plan(ref, chat, run, children, 7, 200)
        assertTrue(first is NativeMixedApprovalPlan.Ready)
        assertTrue(second is NativeMixedApprovalPlan.Ready)
        first as NativeMixedApprovalPlan.Ready
        second as NativeMixedApprovalPlan.Ready

        assertEquals(5, first.request.members.size)
        assertEquals(5, first.members.size)
        assertEquals(MixedApprovalState.PENDING, first.approval.state)
        assertEquals(first.request.id, second.request.id)
        assertEquals(first.request.commandId, second.request.commandId)
        assertEquals(first.request.digest, second.request.digest)
        assertTrue(first.request.commandId.value.startsWith("mixed-command-"))
        assertTrue(first.request.members.none { it.childCommandId == first.request.commandId })
        assertEquals(setOf(
            MixedApprovalMemberKind.ITEM, MixedApprovalMemberKind.POLICY,
            MixedApprovalMemberKind.PDF_PAGE, MixedApprovalMemberKind.ASSET_IMPORT,
            MixedApprovalMemberKind.WORKSPACE_FILE,
        ), first.request.members.map { it.kind }.toSet())
    }

    @Test
    fun planRejectsMixedRevisionPolicyStaleAndSingleMember() {
        val one = listOf(child(MixedApprovalMemberKind.ITEM, "item-1", revision = 7))
        assertEquals(
            NativeMixedApprovalBlockReason.NOT_ENOUGH_MEMBERS,
            blocked(NativeMixedApprovalRules.plan(ref, chat, run, one, 7, 1)),
        )
        val mixedRevision = listOf(
            child(MixedApprovalMemberKind.ITEM, "item-1", revision = 7),
            child(MixedApprovalMemberKind.POLICY, "policy-1", revision = 8),
        )
        assertEquals(
            NativeMixedApprovalBlockReason.MIXED_REVISION,
            blocked(NativeMixedApprovalRules.plan(ref, chat, run, mixedRevision, 8, 1)),
        )
        val mixedPolicy = listOf(
            child(MixedApprovalMemberKind.ITEM, "item-1", policy = ConflictPolicy.REJECT_BATCH),
            child(MixedApprovalMemberKind.POLICY, "policy-1",
                policy = ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS),
        )
        assertEquals(
            NativeMixedApprovalBlockReason.MIXED_POLICY,
            blocked(NativeMixedApprovalRules.plan(ref, chat, run, mixedPolicy, 7, 1)),
        )
        val stale = listOf(
            child(MixedApprovalMemberKind.ITEM, "item-1", revision = 7),
            child(MixedApprovalMemberKind.POLICY, "policy-1", revision = 7),
        )
        assertEquals(
            NativeMixedApprovalBlockReason.STALE_REVISION,
            blocked(NativeMixedApprovalRules.plan(ref, chat, run, stale, 8, 1)),
        )
    }

    @Test
    fun workspaceMustFullyCommitBeforeProjectUnderRejectBatch() {
        val plan = readyPlan()
        val workspace = plan.request.members.first { it.kind == MixedApprovalMemberKind.WORKSPACE_FILE }
        val committed = workspace.withOutcome(
            MixedApprovalMemberState.COMMITTED, receiptDigest = fingerprint("workspace"),
        )
        assertTrue(NativeMixedApprovalRules.projectMayRun(
            ConflictPolicy.REJECT_BATCH, listOf(committed),
        ))
        val conflicted = workspace.withOutcome(
            MixedApprovalMemberState.CONFLICT, rejection = MixedApprovalRejection.STALE_FINGERPRINT,
        )
        assertFalse(NativeMixedApprovalRules.projectMayRun(
            ConflictPolicy.REJECT_BATCH, listOf(conflicted),
        ))
        assertTrue(NativeMixedApprovalRules.projectMayRun(
            ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS, listOf(conflicted),
        ))
        assertEquals(
            listOf(
                NativeMixedApprovalRules.Step.WORKSPACE,
                NativeMixedApprovalRules.Step.PROJECT,
                NativeMixedApprovalRules.Step.SETTLE,
            ),
            NativeMixedApprovalRules.steps(hasWorkspace = true, hasProject = true),
        )
    }

    @Test
    fun settleStateDistinguishesCommittedPartialAndConflict() {
        val plan = readyPlan()
        val workspace = plan.request.members.first { it.kind == MixedApprovalMemberKind.WORKSPACE_FILE }
        val project = plan.request.members.first { it.kind != MixedApprovalMemberKind.WORKSPACE_FILE }
        val committed = workspace.withOutcome(
            MixedApprovalMemberState.COMMITTED, receiptDigest = fingerprint("workspace"),
        )
        val indeterminate = project.withOutcome(MixedApprovalMemberState.INDETERMINATE)
        assertEquals(
            MixedApprovalState.PARTIAL,
            NativeMixedApprovalRules.settleState(listOf(committed, indeterminate)),
        )
        val conflicted = workspace.withOutcome(
            MixedApprovalMemberState.CONFLICT, rejection = MixedApprovalRejection.STALE_FINGERPRINT,
        )
        assertEquals(
            MixedApprovalState.CONFLICT,
            NativeMixedApprovalRules.settleState(listOf(conflicted, indeterminate)),
        )
        assertEquals(
            MixedApprovalState.COMMITTED,
            NativeMixedApprovalRules.settleState(listOf(committed, project.withOutcome(
                MixedApprovalMemberState.COMMITTED, receiptDigest = fingerprint("project"),
            ))),
        )
        assertNull(NativeMixedApprovalRules.settleState(listOf(indeterminate)))
    }

    @Test
    fun consumedWithoutReceiptIsConflictAndCompleteReceiptsCommit() {
        val plan = readyPlan()
        val consumed = consumed(plan)
        val empty = NativeMixedApprovalRules.recoverySettlement(consumed, emptyList())
        assertNotNull(empty)
        assertEquals(MixedApprovalState.CONFLICT, empty!!.state)
        assertTrue(empty.receipts.isEmpty())

        val all = consumed.members.map { receipt(it) }
        assertEquals(MixedApprovalState.COMMITTED,
            NativeMixedApprovalRules.recoverySettlement(consumed, all)?.state)
        assertEquals(MixedApprovalState.PARTIAL,
            NativeMixedApprovalRules.recoverySettlement(consumed, all.take(1))?.state)
        assertEquals(MixedApprovalState.COMMITTED,
            NativeMixedApprovalRules.recoverySettlement(consumed, all.take(2))?.state)
        val first = consumed.members.first()
        val mismatch = listOf(MixedApprovalMemberReceipt(
            first.kind, first.childId, first.childDigest, first.childCommandId,
            fingerprint("wrong-selection"), MixedApprovalMemberState.COMMITTED,
            fingerprint("receipt"),
        ))
        val mismatchSettlement = NativeMixedApprovalRules.recoverySettlement(consumed, mismatch)
        assertEquals(MixedApprovalState.CONFLICT, mismatchSettlement?.state)
        assertTrue(mismatchSettlement!!.receipts.isEmpty())
    }

    @Test
    fun referencedChildCannotBeConsumedAlone() {
        assertFalse(NativeMixedApprovalRules.childConsumptionBlocked(null))
        assertTrue(NativeMixedApprovalRules.childConsumptionBlocked(readyPlan().approval))
        val rejectedMembers = readyPlan().request.members.map {
            it.withOutcome(MixedApprovalMemberState.REJECTED,
                rejection = MixedApprovalRejection.USER_REJECTED)
        }
        assertTrue(NativeMixedApprovalRules.childConsumptionBlocked(
            MixedApproval(
                readyPlan().request,
                rejectedMembers,
                MixedApprovalState.REJECTED,
                null,
                null,
                200,
            ),
        ))
    }

    @Test
    fun reviewEntryRequiresTwoPendingMembersInOneRunRevision() {
        val item = itemSummary(run, 7)
        val workspace = workspaceSummary(run, 7)
        val both = NativeProjectUiState(
            itemProposals = listOf(item),
            workspaceChangeProposals = listOf(workspace),
        )
        assertEquals(2, both.mixedApprovalCandidates().size)
        assertTrue(both.mixedApprovalReviewAvailable)
        assertEquals(2, both.mixedApprovalMemberCount)

        val single = NativeProjectUiState(itemProposals = listOf(item))
        assertTrue(single.mixedApprovalCandidates().isEmpty())
        assertFalse(single.mixedApprovalReviewAvailable)

        val differentRuns = NativeProjectUiState(
            itemProposals = listOf(item),
            workspaceChangeProposals = listOf(workspaceSummary(RunId("other"), 7)),
        )
        assertTrue(differentRuns.mixedApprovalCandidates().isEmpty())
        assertFalse(differentRuns.mixedApprovalReviewAvailable)
    }

    @Test
    fun planIsIdempotentWhenOnlyApprovalTimeChanges() {
        val plan = readyPlan()
        val other = NativeMixedApprovalRules.plan(
            ref, chat, run, baseChildren(), 7, 99_999,
        ) as NativeMixedApprovalPlan.Ready
        assertEquals(plan.request.id, other.request.id)
        assertEquals(plan.request.commandId, other.request.commandId)
        assertEquals(plan.request.digest, other.request.digest)
        assertTrue(plan.request.sameContent(other.request))
    }

    private fun readyPlan(): NativeMixedApprovalPlan.Ready =
        NativeMixedApprovalRules.plan(ref, chat, run, baseChildren(), 7, 100)
            as NativeMixedApprovalPlan.Ready

    private fun baseChildren(): List<NativeMixedChildSnapshot> = listOf(
        child(MixedApprovalMemberKind.ITEM, "item-1", selection = listOf("1"),
            expected = listOf(fingerprint("a")), objects = 1),
        child(MixedApprovalMemberKind.WORKSPACE_FILE, "workspace-1",
            selection = listOf("work/a.md"), roots = "ws-root", paths = listOf("work/a.md")),
    )

    private fun child(
        kind: MixedApprovalMemberKind,
        id: String,
        revision: Long = 7,
        policy: ConflictPolicy? = ConflictPolicy.REJECT_BATCH,
        selection: List<String> = emptyList(),
        expected: List<ObjectFingerprint> = emptyList(),
        roots: String? = null,
        objects: Int = 0,
        added: Int = 0,
        updated: Int = 0,
        deleted: Int = 0,
        target: String? = null,
        paths: List<String> = emptyList(),
    ): NativeMixedChildSnapshot {
        val digest = fingerprint(id)
        val command = CommandId("child-$id")
        return NativeMixedChildSnapshot(
            kind = kind,
            childId = id,
            childCommandId = command,
            childDigest = digest,
            ref = ref,
            chat = chat,
            run = run,
            revision = revision,
            policy = policy,
            selection = selection,
            expectedFingerprints = expected,
            workspaceRootIdentity = roots,
            view = NativeMixedApprovalMemberView(
                kind, id, digest, command, revision, objects, added, updated, deleted,
                0, target, paths,
            ),
        )
    }

    private fun consumed(plan: NativeMixedApprovalPlan.Ready): MixedApproval = MixedApproval(
        plan.request,
        plan.request.members,
        MixedApprovalState.CONSUMED,
        MixedApprovalBinding(plan.request),
        200,
        null,
    )

    private fun receipt(
        member: MixedApprovalMember,
        digest: ObjectFingerprint = fingerprint("receipt-" + member.childId),
    ): MixedApprovalMemberReceipt = MixedApprovalMemberReceipt(
        member.kind, member.childId, member.childDigest, member.childCommandId,
        member.selectionDigest, MixedApprovalMemberState.COMMITTED, digest,
    )

    private fun itemSummary(run: RunId, revision: Long) = com.breakyuna.noveltranslator.nativecore.domain.ItemProposalSummary(
        ApprovalRequestId("item-1"), ref, chat, run, CommandId("item-command"), revision,
        ConflictPolicy.REJECT_BATCH, fingerprint("item-1"), 1, ItemProposalState.PENDING,
    )

    private fun workspaceSummary(run: RunId, revision: Long) = WorkspaceChangeProposalSummary(
        ApprovalRequestId("workspace-1"), ref, chat, run, CommandId("workspace-command"), revision,
        WorkspaceDigest(sha256("workspace-1")), WorkspaceChangeSummary(1, 0, 0, 12),
        listOf("work/a.md"), WorkspaceChangeProposalState.PENDING, 1, null,
    )

    private fun blocked(plan: NativeMixedApprovalPlan): NativeMixedApprovalBlockReason =
        (plan as NativeMixedApprovalPlan.Blocked).reason

    private fun fingerprint(value: String) = ObjectFingerprint(sha256(value))

    private fun sha256(value: String): String =
        TextProjectFingerprint.bytes(value.toByteArray(Charsets.UTF_8))
}

/** Durable parent tests exercise the real Room authority, not a fake store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeMixedApprovalStoreIntegrationTest {
    @Test
    fun multiMemberPrepareConsumeSettleIsOneShot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val ref = LocalProjectRef(
            ProjectId("mixed-project-$suffix"),
            ProjectInstanceId("mixed-instance-$suffix"),
            RecoveryGeneration(1),
        )
        val chat = ChatId("mixed-chat-$suffix")
        val run = RunId("mixed-run-$suffix")
        fun fp(value: String) = ObjectFingerprint(TextProjectFingerprint.bytes(
            value.toByteArray(Charsets.UTF_8),
        ))
        fun child(kind: MixedApprovalMemberKind, id: String): NativeMixedChildSnapshot =
            NativeMixedChildSnapshot(
                kind, id, CommandId("child-$id-$suffix"), fp(id), ref, chat, run, 7,
                ConflictPolicy.REJECT_BATCH, listOf("1"), listOf(fp("expected-$id")), null,
                NativeMixedApprovalMemberView(
                    kind, id, fp(id), CommandId("child-$id-$suffix"), 7, objects = 1,
                ),
            )
        val plan = NativeMixedApprovalRules.plan(
            ref, chat, run,
            listOf(
                child(MixedApprovalMemberKind.ITEM, "item"),
                child(MixedApprovalMemberKind.POLICY, "policy"),
            ),
            7, 100,
        ) as NativeMixedApprovalPlan.Ready
        val store = MixedApprovalStore(context)
        try {
            val prepared = store.prepare(plan.request, MixedApprovalState.PENDING)
            assertTrue(prepared is MixedApprovalPreparation.Ready)
            val ready = prepared as MixedApprovalPreparation.Ready
            assertTrue(ready.created)

            val again = store.prepare(plan.request, MixedApprovalState.PENDING)
            assertTrue(again is MixedApprovalPreparation.Ready)
            assertFalse((again as MixedApprovalPreparation.Ready).created)

            val consumed = store.consume(ready.approval, ready.approval.commandId, 200)
            assertTrue(consumed is MixedApprovalConsumption.Won)
            val consumedApproval = (consumed as MixedApprovalConsumption.Won).approval
            assertEquals(MixedApprovalState.CONSUMED, consumedApproval.state)
            val second = store.consume(ready.approval, ready.approval.commandId, 210)
            assertTrue(second is MixedApprovalConsumption.AlreadyConsumed)

            val receipts = consumedApproval.members.map {
                MixedApprovalMemberReceipt(
                    it.kind, it.childId, it.childDigest, it.childCommandId,
                    it.selectionDigest, MixedApprovalMemberState.COMMITTED, fp("receipt-" + it.childId),
                )
            }
            val settled = store.settle(
                consumedApproval, MixedApprovalState.COMMITTED, receipts, 300,
            )
            assertTrue(settled is MixedApprovalSettlement.Settled)
            assertEquals(
                MixedApprovalState.COMMITTED,
                (settled as MixedApprovalSettlement.Settled).approval.state,
            )
            assertNull(store.active(ref, chat))
            assertEquals(
                MixedApprovalState.COMMITTED,
                store.referencingParent(plan.request.members.first().childCommandId)?.state,
            )
        } finally {
            store.close()
        }
    }
}
