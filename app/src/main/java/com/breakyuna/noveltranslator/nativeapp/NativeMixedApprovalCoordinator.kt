@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportPlan
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposal
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposalState
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportReceipt
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportUnit
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationStatus
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalReceipt
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalRejection
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalState
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalBinding
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberReceipt
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRecovery
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRequest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalReceipt
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalRejection
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalState
import com.breakyuna.noveltranslator.nativecore.domain.PolicyChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.PolicyEditReceipt
import com.breakyuna.noveltranslator.nativecore.domain.PolicyEditStatus
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.TextApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.formats.FormatTextCodec
import com.breakyuna.noveltranslator.nativecore.formats.ProjectFormatService
import com.breakyuna.noveltranslator.nativecore.formats.resolveImportFormat
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalConsumption
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalPreparation
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalPreparationConflict
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalSettlement
import com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalStore
import com.breakyuna.noveltranslator.nativecore.storage.MixedProjectMemberInput
import com.breakyuna.noveltranslator.nativecore.storage.MixedProjectMemberStatus
import com.breakyuna.noveltranslator.nativecore.storage.NativeAssetImportProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeItemProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePdfPageProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePolicyProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeProposalReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Body-free member projection used by the aggregate approval surface. It carries identities,
 * counts, paths and digests only; proposal bodies and workspace file contents never enter UI
 * state or a log line.
 */
data class NativeMixedApprovalMemberView(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val childDigest: ObjectFingerprint,
    val childCommandId: CommandId,
    val revision: Long,
    val objects: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val bytes: Long = 0,
    val target: String? = null,
    val paths: List<String> = emptyList(),
    val state: MixedApprovalMemberState = MixedApprovalMemberState.PENDING,
    val rejection: MixedApprovalRejection? = null,
)

/** Frozen child data used to build the parent request; never contains a child body. */
internal data class NativeMixedChildSnapshot(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val childCommandId: CommandId,
    val childDigest: ObjectFingerprint,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val revision: Long,
    val policy: ConflictPolicy?,
    val selection: List<String>,
    val expectedFingerprints: List<ObjectFingerprint>,
    val workspaceRootIdentity: String?,
    val view: NativeMixedApprovalMemberView,
)

internal data class NativeMixedApprovalSnapshot(
    val approval: MixedApproval,
    val members: List<NativeMixedApprovalMemberView>,
)

internal enum class NativeMixedApprovalBlockReason {
    NOT_ENOUGH_MEMBERS,
    MIXED_RUN,
    MIXED_REVISION,
    MIXED_POLICY,
    STALE_REVISION,
    ACTIVE_PARENT,
    ALREADY_CONSUMED,
    NOT_PENDING,
    CHILD_CHANGED,
    IDENTITY,
    BUSY,
    UNAVAILABLE,
}

internal fun NativeMixedApprovalBlockReason.messageRes(): Int = when (this) {
    NativeMixedApprovalBlockReason.NOT_ENOUGH_MEMBERS,
    NativeMixedApprovalBlockReason.MIXED_RUN,
    -> R.string.native_mixed_approval_unavailable
    NativeMixedApprovalBlockReason.MIXED_REVISION,
    NativeMixedApprovalBlockReason.MIXED_POLICY,
    NativeMixedApprovalBlockReason.STALE_REVISION,
    NativeMixedApprovalBlockReason.CHILD_CHANGED,
    -> R.string.native_mixed_approval_stale
    NativeMixedApprovalBlockReason.ACTIVE_PARENT -> R.string.native_mixed_approval_active
    NativeMixedApprovalBlockReason.ALREADY_CONSUMED,
    NativeMixedApprovalBlockReason.NOT_PENDING,
    -> R.string.native_mixed_approval_not_pending
    NativeMixedApprovalBlockReason.IDENTITY,
    NativeMixedApprovalBlockReason.BUSY,
    NativeMixedApprovalBlockReason.UNAVAILABLE,
    -> R.string.native_mixed_approval_unavailable
}

internal sealed interface NativeMixedApprovalPlan {
    data class Ready(
        val approval: MixedApproval,
        val members: List<NativeMixedApprovalMemberView>,
    ) : NativeMixedApprovalPlan {
        val request: MixedApprovalRequest get() = approval.request
    }

    data class Blocked(val reason: NativeMixedApprovalBlockReason) : NativeMixedApprovalPlan
}

/** Pure preparation/outcome rules shared by production and tests. */
internal object NativeMixedApprovalRules {
    private val ORDER = compareBy<NativeMixedChildSnapshot>({ it.kind.ordinal }, { it.childId })

    fun plan(
        ref: LocalProjectRef,
        chat: ChatId,
        run: RunId,
        children: List<NativeMixedChildSnapshot>,
        projectRevision: Long,
        createdAtMillis: Long,
    ): NativeMixedApprovalPlan {
        if (children.size < 2 || children.size > MixedApprovalRequest.MAX_MEMBERS) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.NOT_ENOUGH_MEMBERS)
        }
        if (children.any { it.ref != ref || it.chat != chat || it.run != run }) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.MIXED_RUN)
        }
        val revisions = children.map { it.revision }.toSet()
        if (revisions.size != 1) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.MIXED_REVISION)
        }
        val policies = children.mapNotNull { it.policy }.toSet()
        if (policies.size > 1) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.MIXED_POLICY)
        }
        val policy = policies.singleOrNull() ?: ConflictPolicy.REJECT_BATCH
        val baseRevision = revisions.single()
        if (policy == ConflictPolicy.REJECT_BATCH && projectRevision != baseRevision) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.STALE_REVISION)
        }
        val ordered = children.sortedWith(ORDER)
        val hex = planHex(ref, chat, run, baseRevision, policy, ordered)
        val request = try {
            MixedApprovalRequest(
                id = ApprovalRequestId("mixed-$hex"),
                commandId = CommandId("mixed-command-$hex"),
                ref = ref,
                chat = chat,
                run = run,
                baseRevision = baseRevision,
                policy = policy,
                members = ordered.map { child ->
                    MixedApprovalMember(
                        kind = child.kind,
                        childId = child.childId,
                        childDigest = child.childDigest,
                        childCommandId = child.childCommandId,
                        selection = child.selection,
                        expectedFingerprints = child.expectedFingerprints,
                        workspaceRootIdentity = child.workspaceRootIdentity,
                    )
                },
                createdAtMillis = createdAtMillis,
            )
        } catch (_: IllegalArgumentException) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.IDENTITY)
        }
        val prepared = try {
            MixedApproval(request, request.members, MixedApprovalState.PENDING, null, null, null)
        } catch (_: IllegalArgumentException) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.IDENTITY)
        }
        return NativeMixedApprovalPlan.Ready(prepared, ordered.map { it.view })
    }

    fun planHex(
        ref: LocalProjectRef,
        chat: ChatId,
        run: RunId,
        baseRevision: Long,
        policy: ConflictPolicy,
        ordered: List<NativeMixedChildSnapshot>,
    ): String {
        val canonical = buildString {
            append("mixed-approval-plan-v1\n")
            append(ref.projectId.value).append('\n')
            append(ref.instanceId.value).append('\n')
            append(ref.generation.value).append('\n')
            append(chat.value).append('\n')
            append(run.value).append('\n')
            append(baseRevision).append('\n')
            append(policy.name).append('\n')
            ordered.forEach { child ->
                append(child.kind.name).append('\u0000')
                append(child.childId).append('\u0000')
                append(child.childDigest.sha256).append('\u0000')
                append(child.childCommandId.value).append('\u0000')
                append(child.workspaceRootIdentity ?: "").append('\u0000')
                child.selection.forEach { append(it).append('\u0001') }
                append('\u0000')
                child.expectedFingerprints.forEach { append(it.sha256).append('\u0001') }
                append('\n')
            }
        }
        return TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(canonical))
    }

    /**
     * The workspace half is always committed before the .lnt transaction. Under REJECT_BATCH
     * any non-committed workspace member aborts the project half, so a partial file batch can
     * never be reported as a mixed success.
     */
    fun projectMayRun(
        policy: ConflictPolicy,
        workspaceMembers: List<MixedApprovalMember>,
    ): Boolean = policy != ConflictPolicy.REJECT_BATCH ||
        workspaceMembers.all { it.state == MixedApprovalMemberState.COMMITTED }

    /** Durable order used by the coordinator: workspace files, the .lnt transaction, settlement. */
    enum class Step { WORKSPACE, PROJECT, SETTLE }

    fun steps(hasWorkspace: Boolean, hasProject: Boolean): List<Step> = buildList {
        if (hasWorkspace) add(Step.WORKSPACE)
        if (hasProject) add(Step.PROJECT)
        add(Step.SETTLE)
    }

    fun settleState(members: List<MixedApprovalMember>): MixedApprovalState? {
        if (members.isEmpty()) return null
        val committed = members.count { it.state == MixedApprovalMemberState.COMMITTED }
        return when {
            committed == members.size -> MixedApprovalState.COMMITTED
            committed > 0 -> MixedApprovalState.PARTIAL
            members.any {
                it.state == MixedApprovalMemberState.CONFLICT ||
                    it.state == MixedApprovalMemberState.REJECTED ||
                    it.state == MixedApprovalMemberState.CANCELLED
            } -> MixedApprovalState.CONFLICT
            else -> null
        }
    }

    fun recovery(
        approval: MixedApproval,
        receipts: List<MixedApprovalMemberReceipt>,
    ): MixedApprovalRecovery = MixedApprovalRecovery.classify(approval, receipts)

    data class RecoverySettlement(
        val state: MixedApprovalState,
        val receipts: List<MixedApprovalMemberReceipt>,
    )

    /**
     * A CONSUMED parent without a proof becomes CONFLICT with no receipt; CONSUMED alone is
     * never treated as a successful write. Partial proofs settle PARTIAL only when at least one
     * child is proven committed.
     */
    fun recoverySettlement(
        approval: MixedApproval,
        receipts: List<MixedApprovalMemberReceipt>,
    ): RecoverySettlement? {
        val classification = recovery(approval, receipts)
        return when (classification) {
            is MixedApprovalRecovery.AlreadySettled -> null
            is MixedApprovalRecovery.Rejectable -> RecoverySettlement(
                MixedApprovalState.REJECTED, emptyList(),
            )
            is MixedApprovalRecovery.Indeterminate -> RecoverySettlement(
                MixedApprovalState.CONFLICT, emptyList(),
            )
            is MixedApprovalRecovery.Mismatch -> RecoverySettlement(
                MixedApprovalState.CONFLICT, emptyList(),
            )
            is MixedApprovalRecovery.Partial -> RecoverySettlement(
                if (receipts.any { it.state == MixedApprovalMemberState.COMMITTED })
                    MixedApprovalState.PARTIAL else MixedApprovalState.CONFLICT,
                receipts,
            )
            is MixedApprovalRecovery.Committed -> RecoverySettlement(
                MixedApprovalState.COMMITTED, receipts,
            )
            is MixedApprovalRecovery.Rejected -> RecoverySettlement(
                MixedApprovalState.REJECTED, receipts,
            )
            is MixedApprovalRecovery.Cancelled -> RecoverySettlement(
                MixedApprovalState.CANCELLED, receipts,
            )
            is MixedApprovalRecovery.Conflicted -> RecoverySettlement(
                MixedApprovalState.CONFLICT, receipts,
            )
        }
    }

    /** A child referenced by any parent must not be consumed or rejected through its own dialog. */
    fun childConsumptionBlocked(parent: MixedApproval?): Boolean = parent != null

    fun snapshotViews(
        approval: MixedApproval,
        children: Map<String, NativeMixedChildSnapshot>,
    ): List<NativeMixedApprovalMemberView> = approval.members.map { member ->
        val child = children[member.kind.name + '\u0000' + member.childId]
        NativeMixedApprovalMemberView(
            kind = member.kind,
            childId = member.childId,
            childDigest = member.childDigest,
            childCommandId = member.childCommandId,
            revision = child?.revision ?: approval.baseRevision,
            objects = child?.view?.objects ?: 0,
            added = child?.view?.added ?: 0,
            updated = child?.view?.updated ?: 0,
            deleted = child?.view?.deleted ?: 0,
            bytes = child?.view?.bytes ?: 0,
            target = child?.view?.target,
            paths = child?.view?.paths ?: emptyList(),
            state = member.state,
            rejection = member.rejection,
        )
    }

    fun workspaceReceiptDigest(receipt: WorkspaceChangeProposalReceipt): ObjectFingerprint =
        fingerprint("mixed-workspace-receipt-v1", buildJsonObject {
            put("status", receipt.status.name)
            put("committed", receipt.committed)
            put("conflicts", receipt.conflicts)
            put("revision", receipt.revision)
            put("at", receipt.at)
            put("committed_paths", JsonArray(receipt.committedPaths.map { JsonPrimitive(it) }))
            put("conflict_paths", JsonArray(receipt.conflictPaths.map { JsonPrimitive(it) }))
        })

    fun itemReceiptDigest(receipt: ItemProposalReceipt): ObjectFingerprint =
        fingerprint("mixed-child-receipt-v1", buildJsonObject {
            put("kind", MixedApprovalMemberKind.ITEM.name)
            put("command", receipt.command.value)
            put("binding", receipt.binding.sha256)
            put("revision", receipt.revision)
            put("committed_at", receipt.committedAtMillis)
            put("accepted", JsonArray(receipt.accepted.map { JsonPrimitive(it.value) }.sortedBy { it.content }))
            put("rejected", JsonArray(receipt.rejected.entries.sortedBy { it.key.value }.map { (id, reason) ->
                buildJsonObject { put("id", id.value); put("reason", reason.name) }
            }))
        })

    fun pdfReceiptDigest(receipt: PdfPageProposalReceipt): ObjectFingerprint =
        fingerprint("mixed-child-receipt-v1", buildJsonObject {
            put("kind", MixedApprovalMemberKind.PDF_PAGE.name)
            put("command", receipt.command.value)
            put("binding", receipt.binding.sha256)
            put("revision", receipt.revision)
            put("committed_at", receipt.committedAtMillis)
            put("accepted", JsonArray(receipt.accepted.map { JsonPrimitive(it) }))
            put("rejected", JsonArray(receipt.rejected.entries.map { (page, reason) ->
                buildJsonObject { put("page", page); put("reason", reason.name) }
            }))
        })

    fun policyReceiptDigest(
        proposal: PolicyChangeProposal,
        receipt: PolicyEditReceipt,
    ): ObjectFingerprint = fingerprint("mixed-child-receipt-v1", buildJsonObject {
        put("kind", MixedApprovalMemberKind.POLICY.name)
        put("command", receipt.commandId.value)
        put("command_digest", proposal.intent().digest())
        put("project", receipt.ref.projectId.value)
        put("instance", receipt.ref.instanceId.value)
        put("generation", receipt.ref.generation.value)
        put("status", receipt.status.name)
        put("revision", receipt.revision)
        put("fingerprint", receipt.fingerprint.sha256)
        put("committed_at", receipt.committedAtMillis)
    })

    fun assetReceiptDigest(
        proposal: AssetImportProposal,
        receipt: AssetImportReceipt,
    ): ObjectFingerprint = fingerprint("mixed-child-receipt-v1", buildJsonObject {
        put("kind", MixedApprovalMemberKind.ASSET_IMPORT.name)
        put("command", receipt.command.value)
        put("proposal_digest", proposal.digest.sha256)
        put("project", receipt.ref.projectId.value)
        put("instance", receipt.ref.instanceId.value)
        put("generation", receipt.ref.generation.value)
        put("status", receipt.status.name)
        put("revision", receipt.revision)
        receipt.assetId?.let { put("asset", it) }
        put("committed_at", receipt.committedAtMillis)
    })

    fun inferredAssetReceiptDigest(
        proposal: AssetImportProposal,
        assetId: Long,
        revision: Long,
    ): ObjectFingerprint = fingerprint("mixed-asset-receipt-recovery-v1", buildJsonObject {
        put("kind", MixedApprovalMemberKind.ASSET_IMPORT.name)
        put("command", proposal.command.value)
        put("proposal_digest", proposal.digest.sha256)
        put("project", proposal.ref.projectId.value)
        put("instance", proposal.ref.instanceId.value)
        put("generation", proposal.ref.generation.value)
        put("revision", revision)
        put("asset", assetId)
        put("target", proposal.target.value)
        put("sha256", proposal.artifactSha256)
        put("size", proposal.artifactSizeBytes)
    })

    private fun fingerprint(
        format: String,
        value: kotlinx.serialization.json.JsonObject,
    ): ObjectFingerprint = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
        buildJsonObject {
            put("format", format)
            put("value", value)
        }.toString(),
    )))
}

internal enum class NativeMixedApprovalOutcomeState { SETTLED, INDETERMINATE, BLOCKED }

internal data class NativeMixedApprovalOutcome(
    val state: NativeMixedApprovalOutcomeState,
    val approval: MixedApproval? = null,
    val approvalState: MixedApprovalState? = null,
    val blockReason: NativeMixedApprovalBlockReason? = null,
)

internal enum class NativeMixedApprovalMemberTaskAction { APPLY_RECEIPT, REJECT }

/** One pending child that can be included in the aggregate review entry. */
internal data class NativeMixedApprovalUiCandidate(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val run: RunId,
    val revision: Long,
    val policy: ConflictPolicy?,
    val count: Int,
    val paths: List<String> = emptyList(),
    val target: String? = null,
)

/** The largest same-Run, same-revision pending group; empty unless at least two members remain. */
internal fun NativeProjectUiState.mixedApprovalCandidates(): List<NativeMixedApprovalUiCandidate> {
    val all = ArrayList<NativeMixedApprovalUiCandidate>(5)
    itemProposals.firstOrNull { it.state == ItemProposalState.PENDING }?.let {
        all += NativeMixedApprovalUiCandidate(
            MixedApprovalMemberKind.ITEM, it.id.value, it.run, it.revision, it.policy, it.count,
        )
    }
    policyProposals.firstOrNull { it.state == TextApprovalState.PENDING }?.let {
        all += NativeMixedApprovalUiCandidate(
            MixedApprovalMemberKind.POLICY, it.id.value, it.run, it.revision, it.policy, 1,
        )
    }
    pdfPageProposals.firstOrNull { it.state == PdfPageProposalState.PENDING }?.let {
        all += NativeMixedApprovalUiCandidate(
            MixedApprovalMemberKind.PDF_PAGE, it.id.value, it.run, it.revision, it.policy, it.count,
        )
    }
    assetImportProposals.firstOrNull { it.state == AssetImportProposalState.PENDING }?.let {
        all += NativeMixedApprovalUiCandidate(
            MixedApprovalMemberKind.ASSET_IMPORT, it.id.value, it.run, it.revision, null, 1,
            target = it.target.value,
        )
    }
    workspaceChangeProposals.firstOrNull { it.state == WorkspaceChangeProposalState.PENDING }?.let {
        all += NativeMixedApprovalUiCandidate(
            MixedApprovalMemberKind.WORKSPACE_FILE, it.id.value, it.run, it.baseRevision, null,
            it.summary.total, it.paths,
        )
    }
    val group = all.groupBy { it.run }.values
        .filter { it.size >= 2 }
        .maxByOrNull { it.size }
        ?: return emptyList()
    if (group.map { it.revision }.distinct().size != 1) return emptyList()
    if (group.mapNotNull { it.policy }.distinct().size > 1) return emptyList()
    return group
}

internal val NativeProjectUiState.mixedApprovalMemberCount: Int
    get() = mixedApproval?.members?.size ?: mixedApprovalCandidates().size

internal val NativeProjectUiState.mixedApprovalReviewAvailable: Boolean
    get() = mixedApproval != null || mixedApprovalCandidates().size >= 2

internal val NativeProjectUiState.mixedApprovalSingleReviewBlocked: Boolean
    get() = mixedApproval != null


internal data class NativeMixedChildTask(
    val kind: MixedApprovalMemberKind,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val childId: ApprovalRequestId,
    val selection: List<String>,
    val action: NativeMixedApprovalMemberTaskAction,
)

internal fun interface NativeMixedChildRun {
    suspend fun run(
        ref: LocalProjectRef,
        identity: RunIdentity,
        block: suspend (RunLease) -> Unit,
    )
}

internal class NativeMixedApprovalCoordinator(
    private val approvals: MixedApprovalStore,
    private val projects: NativeTextProjectStore,
    private val items: NativeItemProposalStore,
    private val policies: NativePolicyProposalStore,
    private val pdfPages: NativePdfPageProposalStore,
    private val assetImports: NativeAssetImportProposalStore,
    private val workspace: NativeWorkspaceChangeProposalStore,
    private val artifactBytes: suspend (AssetImportProposal) -> ByteArray,
    private val now: () -> Long = { System.currentTimeMillis().coerceAtLeast(0) },
) {
    /**
     * Aggregates the single unresolved proposal from each store. Engineering stores already
     * enforce one unresolved proposal per Chat; this also accepts a workspace proposal and
     * requires every member to belong to one Run, revision and conflict policy.
     */
    suspend fun reviewable(
        ref: LocalProjectRef,
        chat: ChatId,
    ): NativeMixedApprovalSnapshot? {
        val active = approvals.active(ref, chat) ?: return null
        val approval = when (active.state) {
            MixedApprovalState.PENDING -> active
            MixedApprovalState.PREPARED -> when (val activated = approvals.activate(active, now())) {
                is com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalActivation.Activated ->
                    activated.approval
                is com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalActivation.AlreadyActive ->
                    activated.approval
                else -> return null
            }
            else -> return null
        }
        return snapshot(approval)
    }

    suspend fun prepare(
        ref: LocalProjectRef,
        chat: ChatId,
        expectedRun: RunId? = null,
    ): NativeMixedApprovalPlan {
        val children = pendingChildren(ref, chat)
        val allByRun = children.groupBy { it.run }
        val sameRun = if (expectedRun != null) allByRun[expectedRun].orEmpty() else
            allByRun.values.filter { it.size >= 2 }.maxByOrNull { it.size } ?: children
        if (sameRun.size < 2) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.NOT_ENOUGH_MEMBERS)
        }
        val run = sameRun.first().run
        if (sameRun.any { it.run != run }) {
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.MIXED_RUN)
        }
        val projectRevision = projects.open(ref).revision
        val plan = NativeMixedApprovalRules.plan(ref, chat, run, sameRun, projectRevision, now())
        if (plan is NativeMixedApprovalPlan.Blocked) return plan
        plan as NativeMixedApprovalPlan.Ready
        val existing = approvals.active(ref, chat)
        if (existing != null) {
            val active = when (existing.state) {
                MixedApprovalState.PREPARED -> when (val activated =
                    approvals.activate(existing, now())) {
                    is com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalActivation.Activated ->
                        activated.approval
                    is com.breakyuna.noveltranslator.nativecore.storage.MixedApprovalActivation.AlreadyActive ->
                        activated.approval
                    else -> null
                }
                MixedApprovalState.PENDING -> existing
                else -> null
            }
            if (active != null && active.request.sameContent(plan.request)) {
                return NativeMixedApprovalPlan.Ready(
                    active, NativeMixedApprovalRules.snapshotViews(active, childMap(sameRun)),
                )
            }
            return NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.ACTIVE_PARENT)
        }
        return when (val prepared = approvals.prepare(plan.request, MixedApprovalState.PENDING)) {
            is MixedApprovalPreparation.Ready -> {
                if (prepared.approval.state == MixedApprovalState.CONSUMED ||
                    prepared.approval.state.terminal) {
                    NativeMixedApprovalPlan.Blocked(NativeMixedApprovalBlockReason.ALREADY_CONSUMED)
                } else {
                    NativeMixedApprovalPlan.Ready(
                        prepared.approval,
                        NativeMixedApprovalRules.snapshotViews(prepared.approval, childMap(sameRun)),
                    )
                }
            }
            is MixedApprovalPreparation.Conflict -> NativeMixedApprovalPlan.Blocked(
                when (prepared.reason) {
                    MixedApprovalPreparationConflict.BUSY -> NativeMixedApprovalBlockReason.ACTIVE_PARENT
                    MixedApprovalPreparationConflict.IDENTITY -> NativeMixedApprovalBlockReason.IDENTITY
                    MixedApprovalPreparationConflict.COMMAND,
                    MixedApprovalPreparationConflict.CHILD_COMMAND,
                    -> NativeMixedApprovalBlockReason.UNAVAILABLE
                },
            )
        }
    }

    /**
     * One user approval. The parent is consumed first, workspace members are applied before the
     * single .lnt transaction and the parent only becomes terminal after child receipts exist.
     */
    suspend fun approve(
        lease: RunLease,
        approval: MixedApproval,
    ): NativeMixedApprovalOutcome {
        if (lease.identity.projectId != approval.ref.projectId ||
            lease.identity.chatId != approval.chat ||
            lease.identity.runId != approval.run ||
            lease.identity.commandId != approval.commandId) {
            return NativeMixedApprovalOutcome(
                NativeMixedApprovalOutcomeState.BLOCKED,
                blockReason = NativeMixedApprovalBlockReason.IDENTITY,
            )
        }
        val current = approvals.find(approval.ref, approval.chat, approval.id)
            ?: return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
        if (!current.request.sameContent(approval.request)) {
            return blocked(NativeMixedApprovalBlockReason.IDENTITY)
        }
        if (current.state != MixedApprovalState.PENDING) {
            return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
        }
        val consumed = when (val result = approvals.consume(current, approval.commandId, now())) {
            is MixedApprovalConsumption.Won -> result.approval
            is MixedApprovalConsumption.AlreadyConsumed ->
                return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
            is MixedApprovalConsumption.Refused -> return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
            is MixedApprovalConsumption.NotFound -> return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
        }
        val binding = consumed.binding ?: return blocked(NativeMixedApprovalBlockReason.IDENTITY)

        val loaded = try {
            loadForApply(consumed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return settleFailure(consumed, emptyList(), NativeMixedApprovalBlockReason.CHILD_CHANGED)
        }
        if (loaded == null) {
            return settleFailure(consumed, emptyList(), NativeMixedApprovalBlockReason.CHILD_CHANGED)
        }

        val workspaceReceipts = ArrayList<MixedApprovalMemberReceipt>()
        for (child in loaded.filter { it.member.kind == MixedApprovalMemberKind.WORKSPACE_FILE }) {
            val receipt = applyWorkspace(child)
            if (receipt != null) workspaceReceipts += receipt
        }
        val workspaceMembers = loaded
            .filter { it.member.kind == MixedApprovalMemberKind.WORKSPACE_FILE }
            .map { member ->
                workspaceReceipts.firstOrNull { it.identityKey == member.identityKey }?.let {
                    member.withOutcome(it.state, it.receiptDigest, it.rejection)
                } ?: member.withOutcome(MixedApprovalMemberState.INDETERMINATE)
            }
        if (!NativeMixedApprovalRules.projectMayRun(consumed.policy, workspaceMembers)) {
            val outcomes = loaded.map { member ->
                workspaceMembers.firstOrNull { it.identityKey == member.identityKey }
                    ?: member.withOutcome(MixedApprovalMemberState.INDETERMINATE)
            }
            val receipts = workspaceReceipts
            val state = NativeMixedApprovalRules.settleState(outcomes) ?: MixedApprovalState.CONFLICT
            return settle(consumed, state, receipts)
        }

        val projectChildren = loaded.filter { it.member.kind != MixedApprovalMemberKind.WORKSPACE_FILE }
        val projectResult = if (projectChildren.isEmpty()) null else try {
            projects.applyMixedProject(
                lease,
                consumed,
                binding,
                projectChildren.map { requireNotNull(it.input) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val outcomes = loaded.map { member ->
                workspaceMembers.firstOrNull { it.identityKey == member.identityKey }
                    ?: member.withOutcome(MixedApprovalMemberState.INDETERMINATE)
            }
            val state = NativeMixedApprovalRules.settleState(outcomes) ?: MixedApprovalState.CONFLICT
            return settle(consumed, state, workspaceReceipts)
        }

        val projectMembers = projectResult?.members.orEmpty()
        val outcomes = loaded.map { child ->
            val workspaceMember = workspaceMembers.firstOrNull { it.identityKey == child.identityKey }
            if (workspaceMember != null) return@map workspaceMember
            val result = projectMembers.firstOrNull {
                it.kind == child.member.kind && it.childId == child.member.childId
            } ?: return@map child.member.withOutcome(MixedApprovalMemberState.INDETERMINATE)
            child.member.withOutcome(result.approvalState, result.receiptDigest, result.reason)
        }
        val receipts = ArrayList<MixedApprovalMemberReceipt>(loaded.size)
        receipts += workspaceReceipts
        projectResult?.members?.forEach { result ->
            val member = consumed.members.firstOrNull {
                it.kind == result.kind && it.childId == result.childId
            } ?: return@forEach
            if (result.status != MixedProjectMemberStatus.INDETERMINATE) {
                runCatching { receipts += result.toApprovalReceipt(member) }
            }
        }
        val state = NativeMixedApprovalRules.settleState(outcomes) ?: MixedApprovalState.CONFLICT
        return settle(consumed, state, receipts)
    }

    suspend fun reject(approval: MixedApproval): NativeMixedApprovalOutcome {
        val current = approvals.find(approval.ref, approval.chat, approval.id)
            ?: return blocked(NativeMixedApprovalBlockReason.NOT_PENDING)
        if (!current.request.sameContent(approval.request)) {
            return blocked(NativeMixedApprovalBlockReason.IDENTITY)
        }
        if (current.state == MixedApprovalState.CONSUMED) {
            return blocked(NativeMixedApprovalBlockReason.ALREADY_CONSUMED)
        }
        if (current.state.terminal) {
            return NativeMixedApprovalOutcome(
                NativeMixedApprovalOutcomeState.SETTLED,
                current,
                current.state,
            )
        }
        val settled = when (val result =
            approvals.settle(current, MixedApprovalState.REJECTED, emptyList(), now())) {
            is MixedApprovalSettlement.Settled -> result.approval
            is MixedApprovalSettlement.AlreadySettled -> result.approval
            else -> return blocked(NativeMixedApprovalBlockReason.UNAVAILABLE)
        }
        // The parent is already terminal; failing to close one child must not undo that fact.
        runCatching { rejectChildren(settled) }
        return NativeMixedApprovalOutcome(
            NativeMixedApprovalOutcomeState.SETTLED,
            settled,
            MixedApprovalState.REJECTED,
        )
    }

    /**
     * Restart reconciliation. It never replays a consumed command: receipts are rebuilt from
     * durable child evidence, a missing proof settles CONFLICT, and only a proven partial set
     * settles PARTIAL. Child proposal rows are closed later by [settleResolvedChildren].
     */
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId): NativeMixedApprovalSnapshot? {
        val active = approvals.active(ref, chat)
        if (active == null) return null
        if (active.state == MixedApprovalState.PREPARED || active.state == MixedApprovalState.PENDING) {
            return if (active.state == MixedApprovalState.PENDING) snapshot(active) else null
        }
        if (active.state != MixedApprovalState.CONSUMED) return null
        val receipts = try {
            rebuildReceipts(active)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val settlement = NativeMixedApprovalRules.recoverySettlement(active, receipts) ?: return null
        when (val result = approvals.settle(active, settlement.state, settlement.receipts, now())) {
            is MixedApprovalSettlement.Settled -> return null
            is MixedApprovalSettlement.AlreadySettled -> return null
            else -> return null
        }
    }

    /**
     * Closes child proposal rows referenced by a terminal mixed parent. A child whose receipt is
     * proven is settled through its normal store recovery path in a child-command run; a
     * non-committed child is rejected. Indeterminate children stay untouched.
     */
    suspend fun settleResolvedChildren(
        ref: LocalProjectRef,
        chat: ChatId,
        runner: NativeMixedChildRun,
    ): Int {
        var settled = 0
        for (parent in approvals.recent(ref, chat, 20)) {
            if (!parent.state.terminal) continue
            for (member in parent.members) {
                val pending = childPending(ref, chat, member) ?: continue
                if (!pending) continue
                val action = when {
                    member.state == MixedApprovalMemberState.INDETERMINATE -> continue
                    member.receiptDigest != null -> NativeMixedApprovalMemberTaskAction.APPLY_RECEIPT
                    else -> NativeMixedApprovalMemberTaskAction.REJECT
                }
                val task = NativeMixedChildTask(
                    kind = member.kind,
                    ref = ref,
                    chat = chat,
                    run = parent.run,
                    command = member.childCommandId,
                    childId = ApprovalRequestId(member.childId),
                    selection = member.selection,
                    action = action,
                )
                runCatching { settleChild(task, runner) }.onSuccess { settled += 1 }
            }
        }
        return settled
    }

    suspend fun childConsumptionBlocked(commandId: CommandId): Boolean =
        NativeMixedApprovalRules.childConsumptionBlocked(approvals.referencingParent(commandId))

    private suspend fun settleFailure(
        approval: MixedApproval,
        receipts: List<MixedApprovalMemberReceipt>,
        reason: NativeMixedApprovalBlockReason,
    ): NativeMixedApprovalOutcome {
        val state = if (receipts.any { it.state == MixedApprovalMemberState.COMMITTED })
            MixedApprovalState.PARTIAL else MixedApprovalState.CONFLICT
        val result = settle(approval, state, receipts)
        return if (result.state == NativeMixedApprovalOutcomeState.SETTLED) result
        else NativeMixedApprovalOutcome(
            NativeMixedApprovalOutcomeState.INDETERMINATE, approval,
        )
    }

    private suspend fun settle(
        approval: MixedApproval,
        state: MixedApprovalState,
        receipts: List<MixedApprovalMemberReceipt>,
    ): NativeMixedApprovalOutcome {
        val result = approvals.settle(approval, state, receipts, now())
        return when (result) {
            is MixedApprovalSettlement.Settled -> NativeMixedApprovalOutcome(
                NativeMixedApprovalOutcomeState.SETTLED, result.approval, result.approval.state,
            )
            is MixedApprovalSettlement.AlreadySettled -> NativeMixedApprovalOutcome(
                NativeMixedApprovalOutcomeState.SETTLED, result.approval, result.approval.state,
            )
            else -> NativeMixedApprovalOutcome(
                NativeMixedApprovalOutcomeState.INDETERMINATE, approval,
            )
        }
    }

    private fun blocked(reason: NativeMixedApprovalBlockReason) = NativeMixedApprovalOutcome(
        NativeMixedApprovalOutcomeState.BLOCKED, blockReason = reason,
    )

    private suspend fun snapshot(approval: MixedApproval): NativeMixedApprovalSnapshot {
        val children = runCatching { pendingChildren(approval.ref, approval.chat) }
            .getOrDefault(emptyList())
        return NativeMixedApprovalSnapshot(
            approval,
            NativeMixedApprovalRules.snapshotViews(approval, childMap(children)),
        )
    }

    private fun childMap(children: List<NativeMixedChildSnapshot>): Map<String, NativeMixedChildSnapshot> =
        children.associateBy { it.kind.name + '\u0000' + it.childId }

    private suspend fun pendingChildren(
        ref: LocalProjectRef,
        chat: ChatId,
    ): List<NativeMixedChildSnapshot> {
        val result = ArrayList<NativeMixedChildSnapshot>(5)
        items.pending(ref, chat)?.let { summary ->
            if (summary.state == ItemProposalState.PENDING) {
                val proposal = items.loadMixedProposal(ref, chat, summary.id)
                result += itemChild(summary, proposal)
            }
        }
        policies.pending(ref, chat)?.let { summary ->
            if (summary.state == TextApprovalState.PENDING) {
                val proposal = policies.loadMixedProposal(ref, chat, summary.id)
                result += policyChild(summary, proposal)
            }
        }
        pdfPages.pending(ref, chat)?.let { summary ->
            if (summary.state == PdfPageProposalState.PENDING) {
                val proposal = pdfPages.loadMixedProposal(ref, chat, summary.id)
                result += pdfChild(summary, proposal)
            }
        }
        assetImports.pending(ref, chat)?.let { summary ->
            if (summary.state == AssetImportProposalState.PENDING) {
                val proposal = assetImports.loadMixedProposal(ref, chat, summary.id)
                result += assetChild(summary, proposal)
            }
        }
        workspace.pending(ref, chat)?.let { summary ->
            if (summary.state == WorkspaceChangeProposalState.PENDING) {
                result += workspaceChild(summary)
            }
        }
        return result
    }

    private fun itemChild(
        summary: com.breakyuna.noveltranslator.nativecore.domain.ItemProposalSummary,
        proposal: com.breakyuna.noveltranslator.nativecore.domain.ItemChangeProposal,
    ): NativeMixedChildSnapshot {
        val selected = proposal.members.map { it.id.value.toString() to it.base }.sortedBy { it.first }
        val selection = selected.map { it.first }
        val expected = selected.map { it.second }
        val view = NativeMixedApprovalMemberView(
            kind = MixedApprovalMemberKind.ITEM,
            childId = proposal.id.value,
            childDigest = proposal.digest,
            childCommandId = proposal.command,
            revision = proposal.revision,
            objects = proposal.members.size,
        )
        return NativeMixedChildSnapshot(
            MixedApprovalMemberKind.ITEM, proposal.id.value, summary.command, summary.digest,
            proposal.ref, proposal.chat, proposal.run, proposal.revision, proposal.policy,
            selection, expected, null, view,
        )
    }

    private fun policyChild(
        summary: com.breakyuna.noveltranslator.nativecore.domain.PolicyApprovalSummary,
        proposal: PolicyChangeProposal,
    ): NativeMixedChildSnapshot {
        val view = NativeMixedApprovalMemberView(
            kind = MixedApprovalMemberKind.POLICY,
            childId = proposal.id.value,
            childDigest = proposal.digest,
            childCommandId = proposal.command,
            revision = proposal.revision,
            objects = 1,
            added = summary.added,
            updated = summary.updated,
            deleted = summary.deleted,
        )
        return NativeMixedChildSnapshot(
            MixedApprovalMemberKind.POLICY, proposal.id.value, summary.command, summary.digest,
            proposal.ref, proposal.chat, proposal.run, proposal.revision, proposal.policy,
            emptyList(), emptyList(), null, view,
        )
    }

    private fun pdfChild(
        summary: com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalSummary,
        proposal: PdfPageChangeProposal,
    ): NativeMixedChildSnapshot {
        val selection = proposal.members.map { it.page.toString() }.sorted()
            .take(MixedApprovalMember.MAX_SELECTION)
        val view = NativeMixedApprovalMemberView(
            kind = MixedApprovalMemberKind.PDF_PAGE,
            childId = proposal.id.value,
            childDigest = proposal.digest,
            childCommandId = proposal.command,
            revision = proposal.revision,
            objects = proposal.members.size,
        )
        return NativeMixedChildSnapshot(
            MixedApprovalMemberKind.PDF_PAGE, proposal.id.value, summary.command, summary.digest,
            proposal.ref, proposal.chat, proposal.run, proposal.revision, proposal.policy,
            selection, emptyList(), null, view,
        )
    }

    private fun assetChild(
        summary: com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposalSummary,
        proposal: AssetImportProposal,
    ): NativeMixedChildSnapshot {
        val view = NativeMixedApprovalMemberView(
            kind = MixedApprovalMemberKind.ASSET_IMPORT,
            childId = proposal.id.value,
            childDigest = proposal.digest,
            childCommandId = proposal.command,
            revision = proposal.revision,
            objects = 1,
            target = proposal.target.value,
        )
        return NativeMixedChildSnapshot(
            MixedApprovalMemberKind.ASSET_IMPORT, proposal.id.value, summary.command, summary.digest,
            proposal.ref, proposal.chat, proposal.run, proposal.revision, null,
            emptyList(), emptyList(), null, view,
        )
    }

    private fun workspaceChild(
        summary: WorkspaceChangeProposalSummary,
    ): NativeMixedChildSnapshot {
        val paths = summary.paths.distinct().sorted()
        val view = NativeMixedApprovalMemberView(
            kind = MixedApprovalMemberKind.WORKSPACE_FILE,
            childId = summary.id.value,
            childDigest = ObjectFingerprint(summary.digest.sha256),
            childCommandId = summary.command,
            revision = summary.baseRevision,
            objects = summary.summary.total,
            added = summary.summary.created,
            updated = summary.summary.updated,
            deleted = summary.summary.deleted,
            bytes = summary.summary.totalBytes,
            paths = paths,
        )
        return NativeMixedChildSnapshot(
            MixedApprovalMemberKind.WORKSPACE_FILE, summary.id.value, summary.command,
            ObjectFingerprint(summary.digest.sha256), summary.ref, summary.chat, summary.run,
            summary.baseRevision, null, paths, emptyList(),
            workspaceRootIdentity(summary.ref), view,
        )
    }

    private class LoadedChild(
        val member: MixedApprovalMember,
        val input: MixedProjectMemberInput?,
        val workspaceSummary: WorkspaceChangeProposalSummary?,
        val digest: ObjectFingerprint,
        val command: CommandId,
        val selectionDigest: ObjectFingerprint,
    ) {
        val identityKey: String get() = member.identityKey
    }

    private suspend fun loadForApply(approval: MixedApproval): List<LoadedChild>? {
        val result = ArrayList<LoadedChild>(approval.members.size)
        for (member in approval.members) {
            if (member.state != MixedApprovalMemberState.PENDING) return null
            val child = when (member.kind) {
                MixedApprovalMemberKind.ITEM -> {
                    val proposal = items.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    if (proposal.ref != approval.ref || proposal.chat != approval.chat ||
                        proposal.run != approval.run || proposal.command != member.childCommandId ||
                        proposal.digest != member.childDigest) return null
                    val selected = selectedItems(member, proposal)
                    if (selected == null) return null
                    LoadedChild(
                        member,
                        MixedProjectMemberInput.Items(proposal, selected, member.selection),
                        null,
                        proposal.digest,
                        proposal.command,
                        member.selectionDigest,
                    )
                }
                MixedApprovalMemberKind.POLICY -> {
                    val proposal = policies.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    if (proposal.ref != approval.ref || proposal.chat != approval.chat ||
                        proposal.run != approval.run || proposal.command != member.childCommandId ||
                        proposal.digest != member.childDigest) return null
                    LoadedChild(
                        member,
                        MixedProjectMemberInput.Policy(proposal, member.selection),
                        null,
                        proposal.digest,
                        proposal.command,
                        member.selectionDigest,
                    )
                }
                MixedApprovalMemberKind.PDF_PAGE -> {
                    val proposal = pdfPages.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    if (proposal.ref != approval.ref || proposal.chat != approval.chat ||
                        proposal.run != approval.run || proposal.command != member.childCommandId ||
                        proposal.digest != member.childDigest) return null
                    val selected = selectedPages(member, proposal) ?: return null
                    LoadedChild(
                        member,
                        MixedProjectMemberInput.PdfPages(proposal, selected, member.selection),
                        null,
                        proposal.digest,
                        proposal.command,
                        member.selectionDigest,
                    )
                }
                MixedApprovalMemberKind.ASSET_IMPORT -> {
                    val proposal = assetImports.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    if (proposal.ref != approval.ref || proposal.chat != approval.chat ||
                        proposal.run != approval.run || proposal.command != member.childCommandId ||
                        proposal.digest != member.childDigest) return null
                    val plan = try {
                        assetPlan(proposal)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return null
                    }
                    LoadedChild(
                        member,
                        MixedProjectMemberInput.AssetImport(proposal, plan, member.selection),
                        null,
                        proposal.digest,
                        proposal.command,
                        member.selectionDigest,
                    )
                }
                MixedApprovalMemberKind.WORKSPACE_FILE -> {
                    val summary = workspace.find(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    if (summary.chat != approval.chat || summary.run != approval.run ||
                        summary.command != member.childCommandId ||
                        ObjectFingerprint(summary.digest.sha256) != member.childDigest) return null
                    if (summary.paths.distinct().sorted() != member.selection) return null
                    LoadedChild(
                        member,
                        null,
                        summary,
                        ObjectFingerprint(summary.digest.sha256),
                        summary.command,
                        member.selectionDigest,
                    )
                }
            }
            result += child
        }
        return result
    }

    private fun selectedItems(
        member: MixedApprovalMember,
        proposal: com.breakyuna.noveltranslator.nativecore.domain.ItemChangeProposal,
    ): Set<ItemId>? = try {
        val selected = member.selection.map { ItemId(it.toLong()) }.toSet()
        if (selected.isEmpty() || selected.any { id -> proposal.members.none { it.id == id } }) null
        else selected
    } catch (_: Exception) {
        null
    }

    private fun selectedPages(
        member: MixedApprovalMember,
        proposal: PdfPageChangeProposal,
    ): Set<Int>? = try {
        val selected = member.selection.map { it.toInt() }.toSet()
        if (selected.isEmpty() || selected.any { page -> proposal.members.none { it.page == page } }) null
        else selected
    } catch (_: Exception) {
        null
    }

    private suspend fun applyWorkspace(child: LoadedChild): MixedApprovalMemberReceipt? {
        val summary = child.workspaceSummary ?: return null
        val state = try {
            workspace.apply(childLease(summary), summary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val updated = try {
            workspace.find(summary.ref, summary.chat, summary.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (updated.state == WorkspaceChangeProposalState.PENDING) return null
        return runCatching { workspaceReceipt(child.member, updated, state) }.getOrNull()
    }

    private fun workspaceReceipt(
        member: MixedApprovalMember,
        summary: WorkspaceChangeProposalSummary,
        state: WorkspaceChangeProposalState,
    ): MixedApprovalMemberReceipt? {
        val receipt = summary.receipt
        return when (state) {
            WorkspaceChangeProposalState.APPLIED -> receipt?.let {
                MixedApprovalMemberReceipt(
                    member.kind, member.childId, member.childDigest, member.childCommandId,
                    member.selectionDigest, MixedApprovalMemberState.COMMITTED,
                    NativeMixedApprovalRules.workspaceReceiptDigest(it),
                )
            }
            WorkspaceChangeProposalState.PARTIAL,
            WorkspaceChangeProposalState.REJECTED,
            WorkspaceChangeProposalState.CONFLICT,
            -> MixedApprovalMemberReceipt(
                member.kind, member.childId, member.childDigest, member.childCommandId,
                member.selectionDigest, MixedApprovalMemberState.CONFLICT,
                receipt?.let { NativeMixedApprovalRules.workspaceReceiptDigest(it) },
                if (receipt?.status == WorkspaceApplyStatus.REJECTED)
                    MixedApprovalRejection.STALE_FINGERPRINT
                else MixedApprovalRejection.DEPENDENCY_FAILED,
            )
            WorkspaceChangeProposalState.PENDING -> null
        }
    }

    private fun childLease(summary: WorkspaceChangeProposalSummary): RunLease = object : RunLease {
        override val identity: RunIdentity = RunIdentity(
            summary.ref.projectId, summary.chat, summary.run, summary.command,
        )
    }

    private suspend fun rebuildReceipts(approval: MixedApproval): List<MixedApprovalMemberReceipt> {
        val result = ArrayList<MixedApprovalMemberReceipt>(approval.members.size)
        for (member in approval.members) {
            val receipt = when (member.kind) {
                MixedApprovalMemberKind.ITEM -> {
                    val proposal = items.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    val selected = selectedItems(member, proposal) ?: continue
                    projects.approvedItemsReceipt(proposal, selected)?.let {
                        itemReceipt(member, it)
                    }
                }
                MixedApprovalMemberKind.POLICY -> {
                    val proposal = policies.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    projects.textPolicyReceipt(proposal.intent())?.let {
                        policyReceipt(member, proposal, it)
                    }
                }
                MixedApprovalMemberKind.PDF_PAGE -> {
                    val proposal = pdfPages.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    val selected = selectedPages(member, proposal) ?: continue
                    projects.approvedPdfPageReceipt(proposal, selected)?.let {
                        pdfReceipt(member, it)
                    }
                }
                MixedApprovalMemberKind.ASSET_IMPORT -> {
                    val proposal = assetImports.loadMixedProposal(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    assetReceipt(member, proposal)
                }
                MixedApprovalMemberKind.WORKSPACE_FILE -> {
                    val summary = workspace.find(approval.ref, approval.chat,
                        ApprovalRequestId(member.childId))
                    workspaceReceipt(member, summary, summary.state)
                }
            }
            if (receipt != null) result += receipt
        }
        return result
    }

    private fun itemReceipt(
        member: MixedApprovalMember,
        receipt: ItemProposalReceipt,
    ): MixedApprovalMemberReceipt {
        val state = when (receipt.state) {
            ItemProposalState.COMMITTED -> MixedApprovalMemberState.COMMITTED
            ItemProposalState.PARTIAL, ItemProposalState.CONFLICT -> MixedApprovalMemberState.CONFLICT
            else -> MixedApprovalMemberState.CANCELLED
        }
        val rejection = if (state == MixedApprovalMemberState.COMMITTED) null else
            receipt.rejected.entries.firstOrNull()?.value?.toMixed() ?: MixedApprovalRejection.VALIDATION
        return MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId,
            member.selectionDigest, state,
            NativeMixedApprovalRules.itemReceiptDigest(receipt),
            if (state == MixedApprovalMemberState.COMMITTED) null else rejection,
        )
    }

    private fun pdfReceipt(
        member: MixedApprovalMember,
        receipt: PdfPageProposalReceipt,
    ): MixedApprovalMemberReceipt {
        val state = when (receipt.state) {
            PdfPageProposalState.COMMITTED -> MixedApprovalMemberState.COMMITTED
            PdfPageProposalState.PARTIAL, PdfPageProposalState.CONFLICT -> MixedApprovalMemberState.CONFLICT
            else -> MixedApprovalMemberState.CANCELLED
        }
        val rejection = if (state == MixedApprovalMemberState.COMMITTED) null else
            receipt.rejected.entries.firstOrNull()?.value?.toMixed() ?: MixedApprovalRejection.VALIDATION
        return MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId,
            member.selectionDigest, state,
            NativeMixedApprovalRules.pdfReceiptDigest(receipt),
            if (state == MixedApprovalMemberState.COMMITTED) null else rejection,
        )
    }

    private fun policyReceipt(
        member: MixedApprovalMember,
        proposal: PolicyChangeProposal,
        receipt: PolicyEditReceipt,
    ): MixedApprovalMemberReceipt {
        val committed = receipt.status == PolicyEditStatus.COMMITTED
        return MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId,
            member.selectionDigest,
            if (committed) MixedApprovalMemberState.COMMITTED else MixedApprovalMemberState.CONFLICT,
            NativeMixedApprovalRules.policyReceiptDigest(proposal, receipt),
            if (committed) null else when (receipt.status) {
                PolicyEditStatus.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
                PolicyEditStatus.STALE_FINGERPRINT -> MixedApprovalRejection.STALE_FINGERPRINT
                PolicyEditStatus.COMMITTED -> null
            },
        )
    }

    private suspend fun assetReceipt(
        member: MixedApprovalMember,
        proposal: AssetImportProposal,
    ): MixedApprovalMemberReceipt? {
        val summary = assetImports.find(proposal.ref, proposal.chat, proposal.id)
        val receipt = assetImports.receipt(summary)
        if (receipt != null) {
            val committed = receipt.status == AssetMutationStatus.COMMITTED
            return MixedApprovalMemberReceipt(
                member.kind, member.childId, member.childDigest, member.childCommandId,
                member.selectionDigest,
                if (committed) MixedApprovalMemberState.COMMITTED else MixedApprovalMemberState.CONFLICT,
                NativeMixedApprovalRules.assetReceiptDigest(proposal, receipt),
                if (committed) null else MixedApprovalRejection.VALIDATION,
            )
        }
        // The child proposal was never claimed by its own store. A matching committed asset after
        // the parent revision is durable evidence of the mixed import; nothing is written here.
        val snapshot = projects.open(proposal.ref)
        if (snapshot.revision <= proposal.revision) return null
        val asset = snapshot.assets.firstOrNull {
            it.path == proposal.target && it.sha256 == proposal.artifactSha256 &&
                it.originalSize == proposal.artifactSizeBytes
        } ?: return null
        return MixedApprovalMemberReceipt(
            member.kind, member.childId, member.childDigest, member.childCommandId,
            member.selectionDigest, MixedApprovalMemberState.COMMITTED,
            NativeMixedApprovalRules.inferredAssetReceiptDigest(proposal, asset.id, snapshot.revision),
        )
    }

    private suspend fun rejectChildren(approval: MixedApproval) {
        for (member in approval.members) {
            runCatching {
                when (member.kind) {
                    MixedApprovalMemberKind.ITEM -> items.reject(
                        items.find(approval.ref, approval.chat, ApprovalRequestId(member.childId)),
                    )
                    MixedApprovalMemberKind.POLICY -> policies.reject(
                        policies.find(approval.ref, approval.chat, ApprovalRequestId(member.childId)),
                    )
                    MixedApprovalMemberKind.PDF_PAGE -> pdfPages.reject(
                        pdfPages.find(approval.ref, approval.chat, ApprovalRequestId(member.childId)),
                    )
                    MixedApprovalMemberKind.ASSET_IMPORT -> assetImports.reject(
                        assetImports.find(approval.ref, approval.chat, ApprovalRequestId(member.childId)),
                    )
                    MixedApprovalMemberKind.WORKSPACE_FILE -> workspace.reject(
                        workspace.find(approval.ref, approval.chat, ApprovalRequestId(member.childId)),
                    )
                }
            }
        }
    }

    private suspend fun childPending(
        ref: LocalProjectRef,
        chat: ChatId,
        member: MixedApprovalMember,
    ): Boolean? = runCatching {
        when (member.kind) {
            MixedApprovalMemberKind.ITEM ->
                items.find(ref, chat, ApprovalRequestId(member.childId)).state == ItemProposalState.PENDING
            MixedApprovalMemberKind.POLICY ->
                policies.find(ref, chat, ApprovalRequestId(member.childId)).state == TextApprovalState.PENDING
            MixedApprovalMemberKind.PDF_PAGE ->
                pdfPages.find(ref, chat, ApprovalRequestId(member.childId)).state == PdfPageProposalState.PENDING
            MixedApprovalMemberKind.ASSET_IMPORT ->
                assetImports.find(ref, chat, ApprovalRequestId(member.childId)).state ==
                    AssetImportProposalState.PENDING
            MixedApprovalMemberKind.WORKSPACE_FILE ->
                workspace.find(ref, chat, ApprovalRequestId(member.childId)).state ==
                    WorkspaceChangeProposalState.PENDING
        }
    }.getOrNull()

    private suspend fun settleChild(task: NativeMixedChildTask, runner: NativeMixedChildRun) {
        val identity = RunIdentity(task.ref.projectId, task.chat, task.run, task.command)
        when (task.kind) {
            MixedApprovalMemberKind.ITEM -> {
                val view = items.find(task.ref, task.chat, task.childId)
                if (view.state != ItemProposalState.PENDING) return
                if (task.action == NativeMixedApprovalMemberTaskAction.REJECT) {
                    items.reject(view)
                } else {
                    runner.run(task.ref, identity) { lease ->
                        items.apply(lease, view, task.selection.map { ItemId(it.toLong()) }.toSet())
                    }
                }
            }
            MixedApprovalMemberKind.POLICY -> {
                val view = policies.find(task.ref, task.chat, task.childId)
                if (view.state != TextApprovalState.PENDING) return
                if (task.action == NativeMixedApprovalMemberTaskAction.REJECT) {
                    policies.reject(view)
                } else {
                    runner.run(task.ref, identity) { lease -> policies.apply(lease, view) }
                }
            }
            MixedApprovalMemberKind.PDF_PAGE -> {
                val view = pdfPages.find(task.ref, task.chat, task.childId)
                if (view.state != PdfPageProposalState.PENDING) return
                if (task.action == NativeMixedApprovalMemberTaskAction.REJECT) {
                    pdfPages.reject(view)
                } else {
                    runner.run(task.ref, identity) { lease ->
                        pdfPages.apply(lease, view, task.selection.map { it.toInt() }.toSet())
                    }
                }
            }
            MixedApprovalMemberKind.ASSET_IMPORT -> {
                val view = assetImports.find(task.ref, task.chat, task.childId)
                if (view.state != AssetImportProposalState.PENDING) return
                if (task.action == NativeMixedApprovalMemberTaskAction.REJECT) {
                    assetImports.reject(view)
                } else {
                    runner.run(task.ref, identity) { lease -> assetImports.apply(lease, view) }
                }
            }
            MixedApprovalMemberKind.WORKSPACE_FILE -> {
                val view = workspace.find(task.ref, task.chat, task.childId)
                if (view.state == WorkspaceChangeProposalState.PENDING) workspace.reject(view)
            }
        }
    }

    private suspend fun assetPlan(proposal: AssetImportProposal): AssetImportPlan {
        val bytes = artifactBytes(proposal)
        require(bytes.size.toLong() == proposal.artifactSizeBytes)
        require(TextProjectFingerprint.bytes(bytes) == proposal.artifactSha256)
        if (bytes.size > ProjectFormatService.MAX_INPUT_BYTES) {
            throw IllegalArgumentException("Asset exceeds the import limit")
        }
        val format = resolveImportFormat(proposal.target.value, bytes)
        if (format == ProjectAssetFormat.BINARY) {
            throw IllegalArgumentException("Unsupported asset format")
        }
        val document: FormatDocument = when (format) {
            ProjectAssetFormat.TXT -> txtDocument(bytes)
            else -> {
                val textAdapter = FormatAdapterRegistry.text(format)
                if (textAdapter != null) {
                    val decoded = FormatTextCodec.decode(bytes)
                    textAdapter.read(decoded.text, proposal.target)
                } else {
                    val binaryAdapter = FormatAdapterRegistry.binary(format)
                        ?: throw IllegalArgumentException("Unavailable asset adapter")
                    binaryAdapter.read(bytes, proposal.target)
                }
            }
        }
        val units = document.units.mapIndexed { index, unit ->
            AssetImportUnit(index, unit.row, unit.source, unit.translation, unit.status, unit.tag,
                unit.nameSource, unit.nameTranslation, unit.extraField, unit.skipInternalFilter)
        }
        return AssetImportPlan(
            proposal.command, proposal.ref, proposal.revision, proposal.target, format,
            bytes, units, proposal.enabled, document.metadata,
        )
    }

    private fun txtDocument(bytes: ByteArray): FormatDocument {
        val decoded = FormatTextCodec.decode(bytes)
        val lines = splitLines(decoded.text)
        require(lines.size <= 100_000)
        return FormatDocument(
            ProjectAssetFormat.TXT,
            lines.mapIndexed { index, line -> FormatUnit(index, line) },
        )
    }

    private fun splitLines(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\r' && text.getOrNull(index + 1) == '\n') {
                result.add(text.substring(start, index))
                index += 2
                start = index
                continue
            }
            if (isLineSeparator(character)) {
                result.add(text.substring(start, index))
                index++
                start = index
                continue
            }
            index++
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }

    private fun isLineSeparator(character: Char): Boolean =
        character == '\n' || character == '\r' || character == '\u000b' || character == '\u000c' ||
            character in '\u001c'..'\u001e' || character == '\u0085' ||
            character == '\u2028' || character == '\u2029'

    private fun workspaceRootIdentity(ref: LocalProjectRef): String = "ws-" + TextProjectFingerprint.bytes(
        TextProjectFingerprint.utf8(
            ref.projectId.value + "\u0000" + ref.instanceId.value + "\u0000" + ref.generation.value,
        ),
    )

    private companion object {
        fun ItemProposalRejection.toMixed(): MixedApprovalRejection = when (this) {
            ItemProposalRejection.NOT_SELECTED -> MixedApprovalRejection.NOT_SELECTED
            ItemProposalRejection.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
            ItemProposalRejection.STALE_FINGERPRINT -> MixedApprovalRejection.STALE_FINGERPRINT
            ItemProposalRejection.UNKNOWN_ITEM -> MixedApprovalRejection.UNKNOWN_TARGET
            ItemProposalRejection.BATCH_CONFLICT -> MixedApprovalRejection.DEPENDENCY_FAILED
        }

        fun PdfPageProposalRejection.toMixed(): MixedApprovalRejection = when (this) {
            PdfPageProposalRejection.NOT_SELECTED -> MixedApprovalRejection.NOT_SELECTED
            PdfPageProposalRejection.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
            PdfPageProposalRejection.STALE_ASSET, PdfPageProposalRejection.STALE_PAGE ->
                MixedApprovalRejection.STALE_FINGERPRINT
            PdfPageProposalRejection.BATCH_CONFLICT -> MixedApprovalRejection.DEPENDENCY_FAILED
        }
    }
}
