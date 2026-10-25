package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import kotlinx.coroutines.runBlocking
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
class NativeAssetImportProposalStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var policies: NativePolicyProposalStore
    private lateinit var applier: RecordingApplier
    private lateinit var store: NativeAssetImportProposalStore
    private lateinit var ref: LocalProjectRef
    private var chat: ChatId by kotlin.properties.Delegates.notNull()
    private var sequence = 0
    private val artifactBytes = "prepared artifact".toByteArray()

    private class RecordingApplier : AssetImportApplier {
        var calls = 0
        var result: (AssetImportProposal) -> AssetMutationReceipt = { proposal ->
            AssetMutationReceipt(proposal.command, proposal.ref, AssetMutationKind.IMPORT,
                AssetMutationStatus.COMMITTED, proposal.revision + 1, 7, 2_000)
        }

        override suspend fun apply(proposal: AssetImportProposal, command: CommandId): AssetMutationReceipt {
            require(command == proposal.command)
            calls += 1
            return result(proposal)
        }
    }

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        applier = RecordingApplier()
        reopen()
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
            "source".toByteArray()).ref
        chat = agent.currentChat(ref)
    }

    private fun reopen() {
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime)
        policies = NativePolicyProposalStore(context, projects, runtime)
        store = NativeAssetImportProposalStore(context, projects, runtime, applier)
    }

    private suspend fun closeStores() {
        store.close(); policies.close(); items.close(); agent.close(); projects.close()
    }

    @After fun cleanup() = runBlocking { closeStores(); runtime.close() }

    private fun begin(run: RunId = RunId("run-${++sequence}"),
                      command: CommandId = CommandId("send-${++sequence}")): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        return runtime.beginRun(RunIdentity(ref.projectId, chat, run, command), RuntimeOwner.AGENT)
    }

    private fun end(lease: RunLease) { runtime.finishRun(lease); runtime.selectChat(null) }

    private fun artifact(run: RunId) = AssetImportArtifact(run,
        AssetImportArtifactId("artifact-${run.value}"), RelativePath("reports/report.md"),
        TextProjectFingerprint.bytes(artifactBytes), artifactBytes.size.toLong())

    private suspend fun prepare(): AssetImportProposalSummary {
        val lease = begin()
        return try {
            store.prepare(lease, ref, projects.open(ref).revision, artifact(lease.identity.runId),
                RelativePath("imports/report.md"), enabled = true)
        } finally { end(lease) }
    }

    private suspend fun apply(view: AssetImportProposalSummary): AssetImportProposalState {
        val lease = begin(view.run, view.command)
        return try { store.apply(lease, view) } finally { end(lease) }
    }

    @Test fun prepareFreezesMetadataAndSharesTheSinglePendingSlot() = runBlocking {
        val view = prepare()
        assertEquals(AssetImportProposalState.PENDING, view.state)
        assertEquals(artifactBytes.size.toLong(), view.artifactSizeBytes)
        assertEquals(TextProjectFingerprint.bytes(artifactBytes), view.artifactSha256)
        assertEquals("imports/report.md", view.target.value)
        assertTrue(agent.hasPending(ref, chat))
        assertEquals(view.digest, store.pending(ref, chat)!!.digest)

        val item = projects.item(ref, ItemId(1))!!
        val lease = begin()
        try {
            try {
                agent.prepare(lease, ref, ItemId(1), "other")
                fail("Created a concurrent single-item approval")
            } catch (_: IllegalStateException) { }
            try {
                items.prepare(lease, ref,
                    """{"items":[{"item_id":1,"fingerprint":"${item.item.fingerprint.sha256}","translation":"other"}]}""",
                    ConflictPolicy.REJECT_BATCH)
                fail("Created a concurrent item proposal")
            } catch (_: IllegalStateException) { }
            val policy = projects.textPolicy(ref)
            try {
                policies.prepare(lease, ref, policy.fingerprint, policy.policy.changed(prompt = "changed"))
                fail("Created a concurrent policy proposal")
            } catch (_: IllegalStateException) { }
        } finally { end(lease) }

        store.reject(view)
        assertNull(store.pending(ref, chat))
        assertFalse(agent.hasPending(ref, chat))
        assertEquals(AssetImportProposalState.REJECTED, store.find(ref, chat, view.id).state)
        assertNull(store.receipt(store.find(ref, chat, view.id)))
    }

    @Test fun applyClaimsOnceAndDuplicateCommandReturnsTheStoredReceipt() = runBlocking {
        val view = prepare()
        assertEquals(AssetImportProposalState.APPLIED, apply(view))
        assertEquals(1, applier.calls)
        val receipt = store.receipt(store.find(ref, chat, view.id))!!
        assertEquals(AssetMutationStatus.COMMITTED, receipt.status)
        assertEquals(7L, receipt.assetId)
        assertEquals(view.revision + 1, receipt.revision)

        assertEquals(AssetImportProposalState.APPLIED, apply(view))
        assertEquals(1, applier.calls)
        assertEquals(receipt, store.receipt(store.find(ref, chat, view.id)))
        assertNull(store.pending(ref, chat))
    }

    @Test fun typedStaleAndUnexpectedFailuresNeverBecomeApplied() = runBlocking {
        val stale = prepare()
        applier.result = { throw AssetImportApplyFailure(AssetImportProposalState.STALE) }
        assertEquals(AssetImportProposalState.STALE, apply(stale))
        assertEquals(1, applier.calls)
        assertNull(store.receipt(store.find(ref, chat, stale.id)))

        applier.result = { throw IllegalStateException("writer unavailable") }
        val conflict = prepare()
        assertEquals(AssetImportProposalState.CONFLICT, apply(conflict))
        assertEquals(2, applier.calls)
        assertNull(store.receipt(store.find(ref, chat, conflict.id)))
        assertEquals(AssetImportProposalState.CONFLICT, store.find(ref, chat, conflict.id).state)
    }

    @Test fun claimCasIsSingleUseAndReconcileRecordsConflictWithoutReplaying() = runBlocking {
        val view = prepare()
        val binding = AssetImportBinding(view.id, view.digest, view.command).fingerprint.sha256
        val db = NativeAppDatabase.open(context)
        try {
            assertEquals(1, db.assetImportProposals().claim(view.id.value, binding, 1_000))
            assertEquals(0, db.assetImportProposals().claim(view.id.value, binding, 1_000))
        } finally { db.close() }

        store.reconcile(ref, chat)
        val reconciled = store.find(ref, chat, view.id)
        assertEquals(AssetImportProposalState.CONFLICT, reconciled.state)
        assertNull(store.receipt(reconciled))
        assertEquals(0, applier.calls)

        assertEquals(AssetImportProposalState.CONFLICT, apply(reconciled))
        assertEquals(0, applier.calls)
    }

    @Test fun rejectAfterClaimNeverReportsRejectedOrApplied() = runBlocking {
        val view = prepare()
        val binding = AssetImportBinding(view.id, view.digest, view.command).fingerprint.sha256
        val db = NativeAppDatabase.open(context)
        try {
            assertEquals(1, db.assetImportProposals().claim(view.id.value, binding, 1_000))
        } finally { db.close() }

        store.reject(view)
        assertEquals(AssetImportProposalState.CONFLICT, store.find(ref, chat, view.id).state)
        assertNull(store.receipt(store.find(ref, chat, view.id)))
        assertEquals(0, applier.calls)
    }
}
