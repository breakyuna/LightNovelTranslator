package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberReceipt
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRecovery
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRequest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MixedApprovalStoreTest {
    private lateinit var context: Context
    private lateinit var store: MixedApprovalStore
    private var sequence = 0
    private val databaseName = "lnt_native_app.db"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        store = MixedApprovalStore(context)
    }

    @After fun cleanup(): Unit = runBlocking {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun fp(character: Char) = ObjectFingerprint(character.toString().repeat(64))

    private fun localRef(generation: Long = 1) = LocalProjectRef(
        ProjectId("project-1"), ProjectInstanceId("instance-1"), RecoveryGeneration(generation),
    )

    private fun member(
        kind: MixedApprovalMemberKind = MixedApprovalMemberKind.ITEM,
        childId: String = "child-${++sequence}",
        command: CommandId = CommandId("child-command-${++sequence}"),
        selection: List<String> = listOf("1"),
        fingerprints: List<ObjectFingerprint> = listOf(fp('a')),
        root: String? = null,
        childDigest: ObjectFingerprint = fp('a'),
    ) = MixedApprovalMember(
        kind = kind,
        childId = childId,
        childDigest = childDigest,
        childCommandId = command,
        selection = selection,
        expectedFingerprints = fingerprints,
        workspaceRootIdentity = root,
    )

    private fun request(
        id: String = "mixed-${++sequence}",
        command: String = "mixed-command-${++sequence}",
        ref: LocalProjectRef = localRef(),
        chat: String = "chat-1",
        run: String = "run-1",
        baseRevision: Long = 3,
        policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH,
        members: List<MixedApprovalMember> = listOf(member()),
        createdAt: Long = 1_000,
    ) = MixedApprovalRequest(
        ApprovalRequestId(id),
        CommandId(command),
        ref,
        ChatId(chat),
        RunId(run),
        baseRevision,
        policy,
        members,
        createdAt,
    )

    private suspend fun prepared(
        request: MixedApprovalRequest,
        state: MixedApprovalState = MixedApprovalState.PENDING,
    ): MixedApproval {
        val result = store.prepare(request, state)
        assertTrue("Expected prepared, got $result", result is MixedApprovalPreparation.Ready)
        return (result as MixedApprovalPreparation.Ready).approval
    }

    private suspend fun consumed(approval: MixedApproval, at: Long = 2_000): MixedApproval {
        val result = store.consume(approval, at = at)
        assertTrue("Expected consumed, got $result", result is MixedApprovalConsumption.Won)
        return (result as MixedApprovalConsumption.Won).approval
    }

    private fun committed(member: MixedApprovalMember, digest: Char = 'd') =
        MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId,
            member.selectionDigest, MixedApprovalMemberState.COMMITTED, fp(digest),
        )

    private fun rejected(
        member: MixedApprovalMember,
        reason: MixedApprovalRejection = MixedApprovalRejection.STALE_FINGERPRINT,
    ) = MixedApprovalMemberReceipt(
        member.kind, member.childId, member.childDigest, member.childCommandId,
        member.selectionDigest, MixedApprovalMemberState.REJECTED, rejection = reason,
    )

    private fun conflicted(member: MixedApprovalMember) = MixedApprovalMemberReceipt(
        member.kind, member.childId, member.childDigest, member.childCommandId,
        member.selectionDigest, MixedApprovalMemberState.CONFLICT,
        rejection = MixedApprovalRejection.VALIDATION,
    )

    private fun cancelled(member: MixedApprovalMember) = MixedApprovalMemberReceipt(
        member.kind, member.childId, member.childDigest, member.childCommandId,
        member.selectionDigest, MixedApprovalMemberState.CANCELLED,
    )

    private fun execute(sql: String) {
        val database = NativeAppDatabase.open(context)
        try {
            database.openHelper.writableDatabase.execSQL(sql)
        } finally {
            database.close()
        }
    }

    private suspend fun assertReadRejected(block: suspend () -> Unit) {
        val failure = try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    private suspend fun assertConflict(candidate: MixedApprovalRequest): MixedApprovalPreparationConflict {
        val result = store.prepare(candidate)
        assertTrue("Expected conflict, got $result", result is MixedApprovalPreparation.Conflict)
        return (result as MixedApprovalPreparation.Conflict).reason
    }

    private suspend fun assertRefused(
        approval: MixedApproval,
        receipts: List<MixedApprovalMemberReceipt>,
        reason: MixedApprovalRefusalReason,
    ) {
        val result = store.settle(approval, MixedApprovalState.COMMITTED, receipts, at = 3_000)
        assertTrue("Expected refused, got $result", result is MixedApprovalSettlement.Refused)
        assertEquals(reason, (result as MixedApprovalSettlement.Refused).reason)
    }

    @Test fun preparePersistsOrderedReferencesAndIsIdempotent() = runBlocking {
        val page = member(
            kind = MixedApprovalMemberKind.PDF_PAGE,
            childId = "pdf-batch",
            command = CommandId("child-page-command"),
            selection = listOf("2", "1"),
            fingerprints = listOf(fp('b'), fp('c')),
        )
        val item = member(
            kind = MixedApprovalMemberKind.ITEM,
            childId = "item-batch",
            command = CommandId("child-item-command"),
        )
        val request = request(members = listOf(page, item))
        val first = prepared(request)
        assertEquals(MixedApprovalState.PENDING, first.state)
        assertNull(first.binding)
        assertNull(first.claimedAtMillis)
        assertNull(first.settledAtMillis)
        assertEquals(2, first.members.size)
        assertEquals(MixedApprovalMemberKind.ITEM, first.members[0].kind)
        assertEquals(MixedApprovalMemberKind.PDF_PAGE, first.members[1].kind)
        assertEquals(listOf("1", "2"), first.members[1].selection)
        assertEquals(listOf(fp('c'), fp('b')), first.members[1].expectedFingerprints)

        val loaded = requireNotNull(store.find(request.ref, request.chat, request.id))
        assertEquals(first.digest, loaded.digest)
        assertEquals(first.members.map { it.identityKey }, loaded.members.map { it.identityKey })
        assertEquals(first.members.map { it.selection }, loaded.members.map { it.selection })
        assertEquals(first.members.map { it.selectionDigest }, loaded.members.map { it.selectionDigest })
        assertEquals(first.members.map { it.expectedFingerprints }, loaded.members.map { it.expectedFingerprints })
        assertEquals(request.id, requireNotNull(store.active(request.ref, request.chat)).id)
        val recent = store.recent(request.ref, request.chat)
        assertEquals(1, recent.size)
        assertEquals(request.id, recent.single().id)

        val duplicate = MixedApprovalRequest(
            request.id, request.commandId, request.ref, request.chat, request.run,
            request.baseRevision, request.policy, request.members, 9_999,
        )
        val again = store.prepare(duplicate)
        assertTrue(again is MixedApprovalPreparation.Ready)
        val duplicateReady = again as MixedApprovalPreparation.Ready
        assertFalse(duplicateReady.created)
        assertEquals(1_000L, duplicateReady.approval.createdAtMillis)
    }

    @Test fun prepareRejectsIdentityRevisionPolicyAndMemberConflicts() = runBlocking {
        val original = request()
        prepared(original)

        assertEquals(MixedApprovalPreparationConflict.IDENTITY, assertConflict(MixedApprovalRequest(
            original.id, original.commandId, original.ref, original.chat, original.run,
            4, original.policy, original.members, 1_000,
        )))
        assertEquals(MixedApprovalPreparationConflict.IDENTITY, assertConflict(MixedApprovalRequest(
            original.id, original.commandId, original.ref, original.chat, original.run,
            original.baseRevision, ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS, original.members, 1_000,
        )))
        assertEquals(MixedApprovalPreparationConflict.IDENTITY, assertConflict(MixedApprovalRequest(
            original.id, original.commandId, localRef(generation = 2), original.chat, original.run,
            original.baseRevision, original.policy, original.members, 1_000,
        )))
        val changedMember = member(childId = "changed-child", command = CommandId("changed-child-command"),
            selection = listOf("9"))
        assertEquals(MixedApprovalPreparationConflict.IDENTITY, assertConflict(MixedApprovalRequest(
            original.id, original.commandId, original.ref, original.chat, original.run,
            original.baseRevision, original.policy, listOf(changedMember), 1_000,
        )))
        assertEquals(MixedApprovalPreparationConflict.COMMAND, assertConflict(request(
            command = original.commandId.value, chat = "chat-command-conflict",
        )))
        assertEquals(MixedApprovalPreparationConflict.BUSY, assertConflict(request(chat = "chat-1")))
        assertEquals(MixedApprovalPreparationConflict.CHILD_COMMAND, assertConflict(request(
            chat = "chat-child-conflict", members = listOf(member(command = original.members[0].childCommandId)),
        )))
    }

    @Test fun activeParentIsUniquePerIdentityUntilSettled(): Unit = runBlocking {
        val firstRequest = request(chat = "chat-a")
        val first = prepared(firstRequest)
        val busy = store.prepare(request(chat = "chat-a"))
        assertEquals(MixedApprovalPreparationConflict.BUSY,
            (busy as MixedApprovalPreparation.Conflict).reason)
        prepared(request(chat = "chat-b"))
        prepared(request(ref = localRef(generation = 2), chat = "chat-a"))

        val cancel = store.cancel(first, at = 2_000)
        assertTrue("Expected cancel, got $cancel", cancel is MixedApprovalSettlement.Settled)
        assertEquals(MixedApprovalState.CANCELLED, (cancel as MixedApprovalSettlement.Settled).approval.state)
        prepared(request(chat = "chat-a"))
    }

    @Test fun consumeBindsCommandOnceAndProcessRestartDoesNotSucceed() = runBlocking {
        val mixed = request()
        val approval = prepared(mixed)
        val consumed = consumed(approval)
        assertEquals(MixedApprovalState.CONSUMED, consumed.state)
        assertNotNull(consumed.binding)
        assertEquals(2_000L, requireNotNull(consumed.claimedAtMillis))
        assertEquals(approval.digest, requireNotNull(consumed.binding).digest)

        val repeated = store.consume(consumed)
        assertTrue(repeated is MixedApprovalConsumption.AlreadyConsumed)
        assertEquals(consumed.binding!!.fingerprint,
            (repeated as MixedApprovalConsumption.AlreadyConsumed).approval.binding!!.fingerprint)

        val wrongCommand = store.consume(consumed, CommandId("another-command"))
        assertTrue(wrongCommand is MixedApprovalConsumption.Refused)
        assertEquals(MixedApprovalRefusalReason.COMMAND,
            (wrongCommand as MixedApprovalConsumption.Refused).reason)
        val busy = store.prepare(request(chat = mixed.chat.value))
        assertTrue(busy is MixedApprovalPreparation.Conflict)
        assertEquals(MixedApprovalPreparationConflict.BUSY,
            (busy as MixedApprovalPreparation.Conflict).reason)

        store.close()
        store = MixedApprovalStore(context)
        val reloaded = requireNotNull(store.find(mixed.ref, mixed.chat, mixed.id))
        assertEquals(MixedApprovalState.CONSUMED, reloaded.state)
        assertEquals(consumed.binding!!.fingerprint, reloaded.binding!!.fingerprint)
        val recovery = MixedApprovalRecovery.classify(reloaded, emptyList())
        assertTrue("CONSUMED must not recover as success: $recovery",
            recovery is MixedApprovalRecovery.Indeterminate)
        assertTrue(store.consume(reloaded) is MixedApprovalConsumption.AlreadyConsumed)
    }

    @Test fun preparedParentCanBeActivatedAndConsumed(): Unit = runBlocking {
        val approval = prepared(request(), MixedApprovalState.PREPARED)
        assertEquals(MixedApprovalState.PREPARED, approval.state)
        val refused = store.consume(approval)
        assertTrue(refused is MixedApprovalConsumption.Refused)
        assertEquals(MixedApprovalRefusalReason.NOT_PENDING,
            (refused as MixedApprovalConsumption.Refused).reason)
        val activated = store.activate(approval, at = 2_000)
        assertTrue(activated is MixedApprovalActivation.Activated)
        val activatedApproval = (activated as MixedApprovalActivation.Activated).approval
        assertEquals(MixedApprovalState.PENDING, activatedApproval.state)
        consumed(activatedApproval)
    }

    @Test fun committedSettlementRequiresEveryMemberReceipt() = runBlocking {
        val request = request(members = listOf(
            member(childId = "item-batch", command = CommandId("child-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "page-batch",
                command = CommandId("child-page-command"), selection = listOf("1"),
                fingerprints = listOf(fp('b'))),
        ))
        val approval = consumed(prepared(request))
        val firstReceipt = committed(approval.members[0])
        val missing = store.settle(approval, MixedApprovalState.COMMITTED, listOf(firstReceipt), at = 3_000)
        assertTrue(missing is MixedApprovalSettlement.Refused)
        assertEquals(MixedApprovalRefusalReason.RECEIPT_MISSING,
            (missing as MixedApprovalSettlement.Refused).reason)
        assertEquals(MixedApprovalState.CONSUMED,
            requireNotNull(store.find(request.ref, request.chat, request.id)).state)

        val settled = store.settle(approval, MixedApprovalState.COMMITTED,
            listOf(firstReceipt, committed(approval.members[1])), at = 3_000)
        assertTrue(settled is MixedApprovalSettlement.Settled)
        val done = (settled as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.COMMITTED, done.state)
        assertTrue(done.members.all { it.state == MixedApprovalMemberState.COMMITTED })
        assertEquals(MixedApprovalState.COMMITTED,
            requireNotNull(store.find(request.ref, request.chat, request.id)).state)

        val repeated = store.settle(done, MixedApprovalState.COMMITTED,
            listOf(firstReceipt, committed(done.members[1])), at = 3_000)
        assertTrue(repeated is MixedApprovalSettlement.AlreadySettled)
        val changed = store.settle(done, MixedApprovalState.PARTIAL, emptyList(), at = 3_000)
        assertTrue(changed is MixedApprovalSettlement.Refused)
        assertEquals(MixedApprovalRefusalReason.ALREADY_SETTLED,
            (changed as MixedApprovalSettlement.Refused).reason)
    }

    @Test fun partialAndConflictSettlementKeepUnobservedMembersIndeterminate() = runBlocking {
        val partialRequest = request(members = listOf(
            member(childId = "partial-item", command = CommandId("partial-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "partial-page",
                command = CommandId("partial-page-command")),
        ))
        val partialApproval = consumed(prepared(partialRequest))
        val partial = store.settle(partialApproval, MixedApprovalState.PARTIAL,
            listOf(committed(partialApproval.members[0])), at = 3_000)
        assertTrue(partial is MixedApprovalSettlement.Settled)
        val done = (partial as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.PARTIAL, done.state)
        assertEquals(MixedApprovalMemberState.COMMITTED, done.members[0].state)
        assertEquals(MixedApprovalMemberState.INDETERMINATE, done.members[1].state)

        val fullPartialRequest = request(chat = "chat-partial-full", members = listOf(
            member(childId = "full-partial-item", command = CommandId("full-partial-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "full-partial-page",
                command = CommandId("full-partial-page-command")),
        ))
        val fullPartialApproval = consumed(prepared(fullPartialRequest))
        val fullPartial = store.settle(fullPartialApproval, MixedApprovalState.PARTIAL, listOf(
            committed(fullPartialApproval.members[0]),
            rejected(fullPartialApproval.members[1]),
        ), at = 3_000)
        assertTrue(fullPartial is MixedApprovalSettlement.Settled)
        val fullPartialDone = (fullPartial as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.PARTIAL, fullPartialDone.state)
        assertEquals(MixedApprovalMemberState.COMMITTED, fullPartialDone.members[0].state)
        assertEquals(MixedApprovalMemberState.REJECTED, fullPartialDone.members[1].state)

        val conflictRequest = request(chat = "chat-conflict", members = listOf(
            member(childId = "conflict-item", command = CommandId("conflict-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "conflict-page",
                command = CommandId("conflict-page-command")),
        ))
        val conflictApproval = consumed(prepared(conflictRequest))
        val indeterminate = store.settle(conflictApproval, MixedApprovalState.REJECTED, emptyList(), at = 3_000)
        assertTrue(indeterminate is MixedApprovalSettlement.Refused)
        assertEquals(MixedApprovalRefusalReason.INDETERMINATE,
            (indeterminate as MixedApprovalSettlement.Refused).reason)
        val conflict = store.settle(conflictApproval, MixedApprovalState.CONFLICT, emptyList(), at = 3_000)
        assertTrue(conflict is MixedApprovalSettlement.Settled)
        val conflicted = (conflict as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.CONFLICT, conflicted.state)
        assertTrue(conflicted.members.all { it.state == MixedApprovalMemberState.INDETERMINATE })
        assertEquals(MixedApprovalState.CONFLICT,
            requireNotNull(store.find(conflictRequest.ref, conflictRequest.chat, conflictRequest.id)).state)
    }

    @Test fun rejectAndCancelBeforeConsumptionAreTerminalAndCannotConsume() = runBlocking {
        val rejectRequest = request()
        val rejectApproval = prepared(rejectRequest)
        val rejected = store.settle(rejectApproval, MixedApprovalState.REJECTED, at = 2_000)
        assertTrue(rejected is MixedApprovalSettlement.Settled)
        val rejectedApproval = (rejected as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.REJECTED, rejectedApproval.state)
        assertNull(rejectedApproval.binding)
        assertTrue(rejectedApproval.members.all {
            it.state == MixedApprovalMemberState.REJECTED &&
                it.rejection == MixedApprovalRejection.USER_REJECTED
        })

        val cancelRequest = request()
        val cancelApproval = prepared(cancelRequest)
        val cancelled = store.cancel(cancelApproval, at = 2_000)
        assertTrue(cancelled is MixedApprovalSettlement.Settled)
        val cancelledApproval = (cancelled as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.CANCELLED, cancelledApproval.state)
        assertTrue(store.consume(cancelledApproval) is MixedApprovalConsumption.Refused)
        val again = store.cancel(cancelledApproval, at = 3_000)
        assertTrue(again is MixedApprovalSettlement.AlreadySettled)

        val consumedCancelRequest = request(chat = "chat-consumed-cancel", members = listOf(
            member(childId = "consumed-cancel-item", command = CommandId("consumed-cancel-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "consumed-cancel-page",
                command = CommandId("consumed-cancel-page-command")),
        ))
        val consumedCancel = consumed(prepared(consumedCancelRequest))
        val consumedCancelled = store.settle(consumedCancel, MixedApprovalState.CANCELLED, listOf(
            cancelled(consumedCancel.members[0]),
            cancelled(consumedCancel.members[1]),
        ), at = 3_000)
        assertTrue(consumedCancelled is MixedApprovalSettlement.Settled)
        val consumedCancelledApproval = (consumedCancelled as MixedApprovalSettlement.Settled).approval
        assertEquals(MixedApprovalState.CANCELLED, consumedCancelledApproval.state)
        assertNotNull(consumedCancelledApproval.binding)
        assertTrue(consumedCancelledApproval.members.all {
            it.state == MixedApprovalMemberState.CANCELLED
        })
    }

    @Test fun receiptsWithWrongChildDigestSelectionOrCommandAreRefused() = runBlocking {
        val request = request()
        val approval = consumed(prepared(request))
        val member = approval.members[0]

        val wrongDigest = MixedApprovalMemberReceipt(
            member.kind, member.childId, fp('e'), member.childCommandId, member.selectionDigest,
            MixedApprovalMemberState.COMMITTED, fp('d'),
        )
        val wrongSelection = MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId, fp('e'),
            MixedApprovalMemberState.COMMITTED, fp('d'),
        )
        val wrongCommand = MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, CommandId("wrong-child-command"),
            member.selectionDigest, MixedApprovalMemberState.COMMITTED, fp('d'),
        )
        val unknown = MixedApprovalMemberReceipt(
            MixedApprovalMemberKind.ITEM, "unknown-child", member.childDigest, member.childCommandId,
            member.selectionDigest, MixedApprovalMemberState.COMMITTED, fp('d'),
        )

        assertRefused(approval, listOf(wrongDigest), MixedApprovalRefusalReason.RECEIPT_CHILD_DIGEST)
        assertRefused(approval, listOf(wrongSelection), MixedApprovalRefusalReason.RECEIPT_SELECTION)
        assertRefused(approval, listOf(wrongCommand), MixedApprovalRefusalReason.RECEIPT_CHILD_COMMAND)
        assertRefused(approval, listOf(unknown), MixedApprovalRefusalReason.RECEIPT_UNKNOWN_MEMBER)
        assertRefused(approval, listOf(committed(member), committed(member)), MixedApprovalRefusalReason.RECEIPT_DUPLICATE)
        assertEquals(MixedApprovalState.CONSUMED,
            requireNotNull(store.find(request.ref, request.chat, request.id)).state)
    }

    @Test fun parentDigestDatabaseTamperingIsRejected() = runBlocking {
        val request = request()
        prepared(request)
        execute("UPDATE native_mixed_approvals SET digest='${"f".repeat(64)}' WHERE id='${request.id.value}'")
        assertReadRejected { store.find(request.ref, request.chat, request.id) }
    }

    @Test fun memberDigestTamperingIsRejected() = runBlocking {
        val request = request()
        prepared(request)
        execute("UPDATE native_mixed_members SET childDigest='${"e".repeat(64)}' " +
            "WHERE approvalId='${request.id.value}' AND position=0")
        assertReadRejected { store.find(request.ref, request.chat, request.id) }
    }

    @Test fun memberSelectionTamperingIsRejected() = runBlocking {
        val request = request(members = listOf(member(selection = listOf("1"), fingerprints = listOf(fp('a')))))
        prepared(request)
        execute("UPDATE native_mixed_members SET selection='[\"2\"]' " +
            "WHERE approvalId='${request.id.value}' AND position=0")
        assertReadRejected { store.find(request.ref, request.chat, request.id) }
    }

    @Test fun bindingTamperingIsRejected() = runBlocking {
        val request = request()
        val approval = consumed(prepared(request))
        assertNotNull(approval.binding)
        execute("UPDATE native_mixed_approvals SET binding='${"f".repeat(64)}' WHERE id='${request.id.value}'")
        assertReadRejected { store.find(request.ref, request.chat, request.id) }
    }

    @Test fun childCommandCannotBeSharedAcrossParentsOrParentCommands() = runBlocking {
        val first = request(chat = "chat-a", members = listOf(
            member(childId = "shared-child", command = CommandId("shared-child-command")),
        ))
        prepared(first)
        assertEquals(first.id,
            requireNotNull(store.referencingParent(CommandId("shared-child-command"))).id)
        assertNull(store.referencingParent(CommandId("unreferenced-child-command")))
        val sharedChild = store.prepare(request(chat = "chat-b", members = listOf(
            member(childId = "other-child", command = CommandId("shared-child-command")),
        )))
        assertTrue(sharedChild is MixedApprovalPreparation.Conflict)
        assertEquals(MixedApprovalPreparationConflict.CHILD_COMMAND,
            (sharedChild as MixedApprovalPreparation.Conflict).reason)
        val sharedParent = store.prepare(request(command = first.commandId.value, chat = "chat-c"))
        assertTrue(sharedParent is MixedApprovalPreparation.Conflict)
        assertEquals(MixedApprovalPreparationConflict.COMMAND,
            (sharedParent as MixedApprovalPreparation.Conflict).reason)
    }

    @Test fun recoveryClassifiesParentAndReceiptsWithoutMutating() = runBlocking {
        val request = request(members = listOf(
            member(childId = "recovery-item", command = CommandId("recovery-item-command")),
            member(kind = MixedApprovalMemberKind.PDF_PAGE, childId = "recovery-page",
                command = CommandId("recovery-page-command")),
        ))
        val pending = prepared(request)
        assertTrue(MixedApprovalRecovery.classify(pending, emptyList()) is MixedApprovalRecovery.Rejectable)

        val approval = consumed(pending)
        assertTrue(MixedApprovalRecovery.classify(approval, emptyList()) is MixedApprovalRecovery.Indeterminate)
        val first = approval.members[0]
        val second = approval.members[1]
        val partial = MixedApprovalRecovery.classify(approval, listOf(committed(first)))
        assertTrue(partial is MixedApprovalRecovery.Partial)
        val partialRecovery = partial as MixedApprovalRecovery.Partial
        assertEquals(1, partialRecovery.observed)
        assertEquals(2, partialRecovery.total)
        assertTrue(MixedApprovalRecovery.classify(approval, listOf(committed(first), committed(second)))
            is MixedApprovalRecovery.Committed)
        assertTrue(MixedApprovalRecovery.classify(approval, listOf(rejected(first), rejected(second)))
            is MixedApprovalRecovery.Rejected)
        assertTrue(MixedApprovalRecovery.classify(approval, listOf(cancelled(first), cancelled(second)))
            is MixedApprovalRecovery.Cancelled)
        assertTrue(MixedApprovalRecovery.classify(approval, listOf(conflicted(first), conflicted(second)))
            is MixedApprovalRecovery.Conflicted)
        val wrongDigest = MixedApprovalMemberReceipt(
            first.kind, first.childId, fp('e'), first.childCommandId, first.selectionDigest,
            MixedApprovalMemberState.COMMITTED, fp('d'),
        )
        val mismatch = MixedApprovalRecovery.classify(approval, listOf(wrongDigest))
        assertTrue(mismatch is MixedApprovalRecovery.Mismatch)
        assertEquals(MixedApprovalRecovery.Mismatch.Reason.CHILD_DIGEST,
            (mismatch as MixedApprovalRecovery.Mismatch).reason)
        assertEquals(MixedApprovalState.CONSUMED,
            requireNotNull(store.find(request.ref, request.chat, request.id)).state)
    }

    @Test fun workspaceRootIsBoundByTheParentDigest() = runBlocking {
        val id = "workspace-${++sequence}"
        val command = "workspace-command-${++sequence}"
        val childId = "workspace-file"
        val childCommand = CommandId("workspace-child-command")
        val first = request(id = id, command = command, members = listOf(member(
            kind = MixedApprovalMemberKind.WORKSPACE_FILE,
            childId = childId,
            command = childCommand,
            root = "/root/one",
        )))
        val second = request(id = id, command = command, members = listOf(member(
            kind = MixedApprovalMemberKind.WORKSPACE_FILE,
            childId = childId,
            command = childCommand,
            root = "/root/two",
        )))
        assertTrue(first.digest != second.digest)
    }
}
