package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativePolicyProposalStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var policies: NativePolicyProposalStore
    private lateinit var ref: LocalProjectRef
    private var chat: ChatId by kotlin.properties.Delegates.notNull()
    private var sequence = 0
    private val original = ProjectTextPolicy(listOf(
        ProjectTextRule("a", TextRuleKind.TERM, "cat", "猫"),
        ProjectTextRule("b", TextRuleKind.PROTECT, "[0-9]+", regex = true),
        ProjectTextRule("c", TextRuleKind.PRE_REPLACE, "old", "new")), "old instructions", true)
    private val replacement get() = ProjectTextPolicy(listOf(original.rules[1].copy(enabled = false, caseSensitive = false),
        original.rules[0].copy(target = "ねこ", note = "prepared"),
        ProjectTextRule("d", TextRuleKind.POST_REPLACE, "new", "final")), "new instructions", false)

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); runtime = RuntimeCoordinator()
        reopen()
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray()).ref
        chat = agent.currentChat(ref)
        val before = projects.textPolicy(ref)
        projects.editTextPolicy(ProjectPolicyEdit(CommandId("seed"), ref, before.revision, before.fingerprint, original))
    }
    private fun reopen() {
        projects = NativeTextProjectStore(context, runtime); agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime); policies = NativePolicyProposalStore(context, projects, runtime)
    }
    private suspend fun closeStores() { policies.close(); items.close(); agent.close(); projects.close() }
    @After fun cleanup() = runBlocking { closeStores(); runtime.close() }
    private fun begin(run: RunId = RunId("run-${++sequence}"), command: CommandId = CommandId("send-${++sequence}")): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        return runtime.beginRun(RunIdentity(ref.projectId, chat, run, command), RuntimeOwner.AGENT)
    }
    private fun end(lease: RunLease) { runtime.finishRun(lease); runtime.selectChat(null) }
    private suspend fun prepare(): PolicyApprovalSummary {
        val before = projects.textPolicy(ref); val lease = begin()
        return try { policies.prepare(lease, ref, before.fingerprint, replacement) } finally { end(lease) }
    }
    private suspend fun accept(view: PolicyApprovalSummary): TextApprovalState {
        val lease = begin(view.run, view.command)
        return try { policies.apply(lease, view) } finally { end(lease) }
    }
    private suspend fun consume(view: PolicyApprovalSummary) = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try { assertEquals(1, db.policyProposals().consume(view.id.value, System.currentTimeMillis().coerceAtLeast(0))) }
        finally { db.close() }
    }

    @Test fun selectingOneRuleCreatesFreshAuthorityWithoutWritingUntilSecondApproval() = runBlocking {
        val view = prepare()
        val revision = projects.open(ref).revision
        val selected = policies.select(view, setOf("a"), false, false)
        assertNotEquals(view.id, selected.id)
        assertNotEquals(view.command, selected.command)
        assertNotEquals(view.digest, selected.digest)
        assertEquals(TextApprovalState.REJECTED, policies.find(ref, chat, view.id).state)
        assertEquals(revision, projects.open(ref).revision)
        assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
        val expected = original.changed(rules = original.rules.map {
            if (it.id == "a") replacement.rules.single { rule -> rule.id == "a" } else it
        })
        assertEquals(expected.fingerprint(), policies.detail(selected).after.fingerprint())
        assertEquals(TextApprovalState.REJECTED, accept(view))
        assertEquals(TextApprovalState.COMMITTED, accept(selected))
        assertEquals(expected.fingerprint(), projects.textPolicy(ref).fingerprint)
        assertNull(policies.receipt(view))
        assertEquals(PolicyEditStatus.COMMITTED, policies.receipt(selected)!!.status)
    }

    @Test fun emptySelectionDoesNotRevokeOriginalAuthority() = runBlocking {
        val view = prepare()
        try { policies.select(view, emptySet(), false, false); fail("Accepted empty selection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(TextApprovalState.PENDING, policies.find(ref, chat, view.id).state)
        assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
    }

    @Test fun completePolicyCommitsAllFieldsOnceAndReceiptDoesNotReapplyAfterHumanEdits() = runBlocking {
        val view = prepare()
        assertTrue(agent.hasPending(ref, chat))
        assertEquals(TextApprovalState.COMMITTED, accept(view))
        assertEquals(ProjectTextPolicyCodec.encode(replacement), ProjectTextPolicyCodec.encode(projects.textPolicy(ref).policy))
        val receipt = policies.receipt(view)!!
        assertEquals(PolicyEditStatus.COMMITTED, receipt.status); assertEquals(2L, receipt.revision)
        val copy = projects.copyProject(CommandId("after-policy-copy"), ref)
        assertEquals(replacement.fingerprint(), projects.textPolicy(copy.ref).fingerprint)
        val current = projects.textPolicy(ref)
        val human = replacement.changed(prompt = "human instructions")
        projects.editTextPolicy(ProjectPolicyEdit(CommandId("human"), ref, current.revision, current.fingerprint, human))
        assertEquals(TextApprovalState.COMMITTED, accept(view))
        assertEquals(3L, projects.open(ref).revision)
        assertEquals(human.fingerprint(), projects.textPolicy(ref).fingerprint)
        assertEquals(receipt, policies.receipt(view))
    }

    @Test fun changedProjectRevisionCreatesAStableConflictWithoutOverwritingPolicy() = runBlocking {
        val view = prepare()
        val item = projects.item(ref, ItemId(1))!!
        projects.edit(ManualTextEdit(CommandId("human-item"), ref, item.item.id, item.revision, item.item.fingerprint, "human"))
        assertEquals(TextApprovalState.CONFLICT, accept(view))
        assertEquals(PolicyEditStatus.STALE_REVISION, policies.receipt(view)!!.status)
        assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
        assertEquals(TextApprovalState.CONFLICT, accept(view))
        assertEquals(2L, projects.open(ref).revision)
    }

    @Test fun staleBaseInvalidPatternAndNoOpNeverPublishPendingAuthority() = runBlocking {
        val lease = begin()
        try {
            try { policies.prepare(lease, ref, ObjectFingerprint("a".repeat(64)), replacement); fail("Accepted stale base") }
            catch (_: IllegalArgumentException) { }
            try { policies.prepare(lease, ref, original.fingerprint(), original); fail("Accepted no-op policy") }
            catch (_: IllegalArgumentException) { }
            val invalid = ProjectTextPolicy(listOf(ProjectTextRule("invalid", TextRuleKind.PROTECT, "(?=x)", regex = true)))
            try { policies.prepare(lease, ref, original.fingerprint(), invalid); fail("Accepted unsupported pattern") }
            catch (_: InvalidTextRule) { }
        } finally { end(lease) }
        assertFalse(agent.hasPending(ref, chat)); assertEquals(1L, projects.open(ref).revision)
    }

    @Test fun allApprovalFamiliesShareThePendingSlotAndStopCancelsPolicy() = runBlocking {
        val view = prepare()
        val item = projects.item(ref, ItemId(1))!!
        val lease = begin()
        try {
            try { agent.prepare(lease, ref, ItemId(1), "other"); fail("Created concurrent text approval") }
            catch (_: IllegalStateException) { }
            val json = """{"items":[{"item_id":1,"fingerprint":"${item.item.fingerprint.sha256}","translation":"other"}]}"""
            try { items.prepare(lease, ref, json, ConflictPolicy.REJECT_BATCH); fail("Created concurrent item approval") }
            catch (_: IllegalStateException) { }
        } finally { end(lease) }
        agent.cancelPending(ref, view.run)
        assertEquals(TextApprovalState.CANCELLED, policies.find(ref, chat, view.id).state)
        assertFalse(agent.hasPending(ref, chat)); assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
    }

    @Test fun consumedWithoutReceiptIsCancelledWithoutRepeatingThePolicyWrite() = runBlocking {
        val view = prepare(); consume(view)
        policies.reconcile(ref, chat)
        assertEquals(TextApprovalState.CANCELLED, policies.find(ref, chat, view.id).state)
        assertNull(policies.receipt(view)); assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
    }

    @Test fun missingRoomCompletionRecoversTheStoredProjectReceiptAfterReopen() = runBlocking {
        val view = prepare(); val proposal = policies.detail(view); consume(view)
        val lease = begin(view.run, view.command)
        val receipt = try { projects.approvedPolicy(lease, proposal) } finally { end(lease) }
        policies.close(); policies = NativePolicyProposalStore(context, projects, runtime)
        policies.reconcile(ref, chat)
        assertEquals(TextApprovalState.COMMITTED, policies.find(ref, chat, view.id).state)
        assertEquals(receipt, policies.receipt(view)); assertEquals(2L, projects.open(ref).revision)
    }

    @Test fun rollbackKeepsOriginalPolicyRevisionAndReceiptAbsent() = runBlocking {
        val view = prepare(); val proposal = policies.detail(view); consume(view)
        val file = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file, writable = true).use { db ->
            try { ProjectWriteStore.editPolicy(db, proposal.intent()) { throw IOException("injected") }; fail("Did not roll back") }
            catch (_: IOException) { }
            assertNull(ProjectWriteStore.checkedPolicyReceipt(db, proposal.intent()))
        }
        policies.reconcile(ref, chat)
        assertEquals(original.fingerprint(), projects.textPolicy(ref).fingerprint)
        assertEquals(1L, projects.open(ref).revision)
        assertEquals(TextApprovalState.CANCELLED, policies.find(ref, chat, view.id).state)
    }

    @Test fun copyPreservesPolicyFactsButCannotConsumeOriginalApproval() = runBlocking {
        val view = prepare()
        val copy = projects.copyProject(CommandId("copy"), ref)
        try { policies.find(copy.ref, chat, view.id); fail("Accepted foreign instance") }
        catch (_: IllegalArgumentException) { }
        val lease = begin(view.run, view.command)
        try {
            try { projects.approvedPolicy(lease, policies.detail(view)); fail("Skipped authority consumption") }
            catch (_: IllegalArgumentException) { }
        } finally { end(lease) }
        assertEquals(original.fingerprint(), projects.textPolicy(copy.ref).fingerprint)
    }

    @Test fun room14To15PreservesAnExistingItemProposalAndAddsPolicyAuthority() = runBlocking {
        val item = projects.item(ref, ItemId(1))!!
        val lease = begin()
        val view = try { items.prepare(lease, ref,
            """{"items":[{"item_id":1,"fingerprint":"${item.item.fingerprint.sha256}","translation":"pending"}]}""",
            ConflictPolicy.REJECT_BATCH) } finally { end(lease) }
        closeStores()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE native_policy_proposals"); db.execSQL("DELETE FROM room_master_table"); db.version = 14
        }
        reopen()
        assertEquals(view.digest, items.pending(ref, chat)!!.digest)
        assertTrue(policies.recent(ref, chat).isEmpty())
        agent.cancelPending(ref, view.run)
        assertEquals(TextApprovalState.PENDING, prepare().state)
    }
}
