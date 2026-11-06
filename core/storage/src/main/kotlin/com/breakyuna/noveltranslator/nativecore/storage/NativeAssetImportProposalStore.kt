package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device-local, single-consumption authority for one workspace-artifact import.
 *
 * Preparation freezes only metadata and the whole-object digest. The artifact body is read and
 * verified by the app-layer [AssetImportApplier] at apply time, and the writer's receipt is the
 * only evidence of a commit. Room never reports APPLIED without that receipt; interrupted or
 * failed applies settle as STALE/CONFLICT instead of guessing success.
 */
class NativeAssetImportProposalStore(
    context: Context,
    private val projects: TextProjectPort,
    private val runtime: RuntimePort,
    private val applier: AssetImportApplier,
) {
    private val db = NativeAppDatabase.open(context.applicationContext)
    private val mutex = Mutex()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }

    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, baseRevision: Long,
                        artifact: AssetImportArtifact, target: RelativePath,
                        enabled: Boolean = true): AssetImportProposalSummary = io {
        require(lease.identity.projectId == ref.projectId &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val chat = requireNotNull(lease.identity.chatId) { "Asset import requires a bound Chat" }
            require(artifact.run == lease.identity.runId) { "Artifact belongs to another run" }
            require(baseRevision >= 0 && target.value.length <= 512) { "Invalid asset import target" }
            val snapshot = projects.open(ref)
            require(snapshot.revision == baseRevision) { "Project revision changed" }
            require(projects.open(ref).revision == snapshot.revision) { "Project changed while preparing" }
            currentCoroutineContext().ensureActive()
            val proposal = AssetImportProposal(
                ApprovalRequestId(UUID.randomUUID().toString()), ref, chat, lease.identity.runId,
                CommandId(UUID.randomUUID().toString()), snapshot.revision,
                artifact.run, artifact.id, artifact.path, artifact.sha256, artifact.sizeBytes,
                target, enabled, System.currentTimeMillis().coerceAtLeast(0),
            )
            val row = NativeAssetImportProposalRow(
                proposal.id.value, ref.instanceId.value, ref.generation.value, ref.projectId.value,
                proposal.chat.value, proposal.run.value, proposal.command.value, proposal.revision,
                proposal.artifactRun.value, proposal.artifactId.value, proposal.artifactPath.value,
                proposal.artifactSha256, proposal.artifactSizeBytes, proposal.target.value, proposal.enabled,
                proposal.digest.sha256, AssetImportProposalState.PENDING.name,
                null, null, null, null, null, null, null, proposal.createdAt,
            )
            transaction {
                val chatRow = requireNotNull(db.agent().chat(proposal.chat.value))
                require(chatRow.instanceId == ref.instanceId.value && chatRow.generation == ref.generation.value)
                check(db.agent().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value) == null)
                check(db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value,
                    proposal.chat.value).isEmpty())
                db.assetImportProposals().insert(row)
            }
            summary(row, ref)
        } finally { runtime.endOperation(operation) }
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<AssetImportProposalSummary> = io {
        projects.open(ref)
        db.assetImportProposals().recent(ref.instanceId.value, ref.generation.value, chat.value)
            .map { summary(it, ref) }
    }

    suspend fun pending(ref: LocalProjectRef, chat: ChatId): AssetImportProposalSummary? = io {
        projects.open(ref)
        val rows = db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
        require(rows.size <= 1) { "Multiple unresolved asset import proposals" }
        rows.singleOrNull()?.let { summary(it, ref) }
    }

    suspend fun find(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): AssetImportProposalSummary = io {
        projects.open(ref)
        summary(requireNotNull(db.assetImportProposals().find(id.value)), ref).also { require(it.chat == chat) }
    }

    /** Read-only mixed-approval loader; the artifact is always the single selected object. */
    internal suspend fun loadMixedProposal(ref: LocalProjectRef, chat: ChatId,
                                           id: ApprovalRequestId): AssetImportProposal = io {
        projects.open(ref)
        val row = requireNotNull(db.assetImportProposals().find(id.value))
        require(row.chatId == chat.value)
        payload(row, ref)
    }

    suspend fun receipt(view: AssetImportProposalSummary): AssetImportReceipt? = io {
        val row = verifyView(view)
        val status = row.receiptStatus?.let { AssetMutationStatus.valueOf(it) } ?: return@io null
        AssetImportReceipt(view.command, view.ref, status, requireNotNull(row.receiptRevision),
            row.receiptAssetId, requireNotNull(row.receiptAt), view.artifactSha256, view.artifactSizeBytes)
    }

    suspend fun reject(view: AssetImportProposalSummary) = io {
        val row = verifyView(view)
        if (row.state != AssetImportProposalState.PENDING.name) return@io
        val at = now()
        transaction {
            if (row.binding == null) {
                check(db.assetImportProposals().settle(row.id,
                    AssetImportProposalState.PENDING.name, AssetImportProposalState.REJECTED.name, at) == 1)
            } else {
                // A claimed apply may already have committed in the project writer; the UI never
                // turns that uncertainty into a rejection or a success. Reconciliation records it
                // as CONFLICT without repeating the command.
                check(db.assetImportProposals().settle(row.id,
                    AssetImportProposalState.PENDING.name, AssetImportProposalState.CONFLICT.name, at) == 1)
            }
        }
    }

    suspend fun apply(lease: RunLease, view: AssetImportProposalSummary): AssetImportProposalState = io {
        val row = verifyView(view)
        require(lease.identity.projectId == view.ref.projectId && lease.identity.chatId == view.chat &&
            lease.identity.runId == view.run && lease.identity.commandId == view.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val proposal = payload(row, view.ref)
            if (row.state != AssetImportProposalState.PENDING.name) {
                return@io AssetImportProposalState.valueOf(row.state)
            }
            val binding = proposal.binding().fingerprint.sha256
            if (row.binding == null) {
                transaction {
                    if (db.assetImportProposals().claim(row.id, binding, now()) == 0) {
                        val claimed = requireNotNull(db.assetImportProposals().find(row.id))
                        require(claimed.binding == binding) { "Approval binding mismatch" }
                    }
                }
            } else {
                require(row.binding == binding) { "Approval binding mismatch" }
            }
            // A concurrent terminal settlement (or a replay after process recovery) must not
            // call the project writer again with the same command.
            val claimedState = requireNotNull(db.assetImportProposals().find(row.id)).state
            if (claimedState != AssetImportProposalState.PENDING.name) {
                return@io AssetImportProposalState.valueOf(claimedState)
            }
            currentCoroutineContext().ensureActive()
            val receipt = try {
                applier.apply(proposal, proposal.command)
            } catch (failure: AssetImportApplyFailure) {
                settleFailure(row.id, failure.state.name)
                return@io failure.state
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { settleFailure(row.id,
                    AssetImportProposalState.CONFLICT.name) } }
                throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable) { runCatching { settleFailure(row.id,
                    AssetImportProposalState.CONFLICT.name) } }
                return@io AssetImportProposalState.CONFLICT
            }
            require(receipt.commandId == proposal.command && receipt.ref == proposal.ref) {
                "Asset receipt identity changed"
            }
            val result = receipt.status.toAssetImportProposalState()
            withContext(NonCancellable) {
                transaction {
                    check(db.assetImportProposals().settleReceipt(row.id,
                        AssetImportProposalState.PENDING.name, result.name, receipt.status.name,
                        receipt.revision, receipt.assetId, receipt.committedAtMillis, now()) == 1)
                }
            }
            result
        } finally { runtime.endOperation(operation) }
    }

    /**
     * Called on app open with no active run. An unclaimed pending proposal stays reviewable; a
     * claimed apply that never settled crossed a process boundary, so its outcome is unknown.
     * It becomes CONFLICT, never APPLIED, and the command is not replayed automatically.
     */
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId) = io {
        if (runtime.snapshot().activeRun != null) return@io
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
                .filter { it.binding != null }.forEach { row ->
                    payload(row, ref)
                    transaction {
                        check(db.assetImportProposals().settle(row.id,
                            AssetImportProposalState.PENDING.name, AssetImportProposalState.CONFLICT.name, now()) == 1)
                    }
                }
        } finally { runtime.endProjectWrite(write) }
    }

    private fun settleFailure(id: String, state: String) {
        transaction {
            check(db.assetImportProposals().settle(id, AssetImportProposalState.PENDING.name, state, now()) == 1)
        }
    }

    private suspend fun verifyView(view: AssetImportProposalSummary): NativeAssetImportProposalRow {
        projects.open(view.ref)
        val row = requireNotNull(db.assetImportProposals().find(view.id.value))
        val verified = summary(row, view.ref)
        require(verified.chat == view.chat && verified.run == view.run && verified.command == view.command &&
            verified.revision == view.revision && verified.digest == view.digest &&
            verified.artifactRun == view.artifactRun && verified.artifactId == view.artifactId &&
            verified.artifactPath == view.artifactPath && verified.artifactSha256 == view.artifactSha256 &&
            verified.artifactSizeBytes == view.artifactSizeBytes && verified.target == view.target &&
            verified.enabled == view.enabled && verified.createdAt == view.createdAt)
        return row
    }

    private fun payload(row: NativeAssetImportProposalRow, ref: LocalProjectRef): AssetImportProposal {
        summary(row, ref)
        val proposal = AssetImportProposal(
            ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.revision, RunId(row.artifactRunId), AssetImportArtifactId(row.artifactId),
            RelativePath(row.artifactPath), row.sha256, row.size, RelativePath(row.targetPath),
            row.enabled, row.createdAt,
        )
        require(proposal.digest.sha256 == row.digest) { "Asset proposal payload mismatch" }
        if (row.binding != null) {
            require(row.binding == proposal.binding().fingerprint.sha256) { "Asset approval binding mismatch" }
        }
        return proposal
    }

    private fun summary(row: NativeAssetImportProposalRow, ref: LocalProjectRef): AssetImportProposalSummary {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value &&
            row.projectId == ref.projectId.value)
        require(row.revision >= 0 && row.size in 0..AssetImportProposal.MAX_ARTIFACT_BYTES)
        require(row.sha256.length == 64 && row.sha256.all { it in "0123456789abcdef" })
        require(row.artifactPath.length <= 512 && row.targetPath.length <= 512)
        require(row.createdAt >= 0)
        val state = AssetImportProposalState.valueOf(row.state)
        require((row.binding == null) == (row.claimedAt == null))
        require(row.claimedAt == null || row.claimedAt >= 0)
        require((row.state == AssetImportProposalState.PENDING.name) == (row.settledAt == null))
        require(row.settledAt == null || row.settledAt >= 0)
        require((row.receiptStatus == null) == (row.receiptRevision == null))
        require((row.receiptStatus == null) == (row.receiptAt == null))
        require(row.receiptRevision == null || row.receiptRevision >= 0)
        require(row.receiptAssetId == null || row.receiptAssetId > 0)
        require(row.receiptAt == null || row.receiptAt >= 0)
        when (state) {
            AssetImportProposalState.PENDING -> {
                require(row.receiptStatus == null)
            }
            AssetImportProposalState.APPLIED -> {
                require(row.binding != null && row.receiptStatus == AssetMutationStatus.COMMITTED.name)
                require(row.receiptRevision != null && row.receiptAssetId != null)
            }
            AssetImportProposalState.REJECTED, AssetImportProposalState.CANCELLED -> {
                require(row.binding == null && row.receiptStatus == null)
            }
            AssetImportProposalState.STALE -> {
                require(row.binding != null)
                require(row.receiptStatus == null || row.receiptStatus == AssetMutationStatus.STALE_REVISION.name)
            }
            AssetImportProposalState.CONFLICT -> {
                require(row.binding != null)
                require(row.receiptStatus == null || row.receiptStatus == AssetMutationStatus.CONFLICT.name ||
                    row.receiptStatus == AssetMutationStatus.UNKNOWN_ASSET.name)
            }
        }
        val summary = AssetImportProposalSummary(
            ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.revision, ObjectFingerprint(row.digest), RunId(row.artifactRunId),
            AssetImportArtifactId(row.artifactId), RelativePath(row.artifactPath), row.sha256, row.size,
            RelativePath(row.targetPath), row.enabled, state, row.createdAt,
        )
        if (row.binding != null) {
            val rebuilt = AssetImportProposal(
                ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
                row.revision, RunId(row.artifactRunId), AssetImportArtifactId(row.artifactId),
                RelativePath(row.artifactPath), row.sha256, row.size, RelativePath(row.targetPath),
                row.enabled, row.createdAt,
            )
            require(rebuilt.digest.sha256 == row.digest && row.binding == rebuilt.binding().fingerprint.sha256)
        }
        return summary
    }

    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0)

    suspend fun close() = io { db.close() }
}
