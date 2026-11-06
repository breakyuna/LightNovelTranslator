package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportPlan
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposal
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationKind
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationReceipt
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationStatus
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ItemChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalReceipt
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalRejection
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalBinding
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalDigest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberReceipt
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalReceipt
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageProposalRejection
import com.breakyuna.noveltranslator.nativecore.domain.PolicyChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.PolicyEditReceipt
import com.breakyuna.noveltranslator.nativecore.domain.PolicyEditStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectLimits
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleValidation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a mixed parent cannot be written. These values contain no notice text or child body. */
internal enum class MixedProjectWriteRefusal {
    NOT_CONSUMED,
    BINDING,
    IDENTITY,
    INVALID_MEMBER_SET,
    DUPLICATE_MEMBER,
    UNSUPPORTED_MEMBER,
    PARENT_COMMAND,
    CHILD_COMMAND,
    CHILD_DIGEST,
    CHILD_SELECTION,
    CHILD_REVISION,
    CHILD_REJECTED,
    STALE_REVISION,
    RECEIPT_CONFLICT,
    RECEIPT_MISSING,
    VALIDATION,
}

internal class MixedProjectWriteException(val reason: MixedProjectWriteRefusal) :
    IllegalStateException("Mixed project write refused: $reason")

internal enum class MixedProjectWriteStatus {
    COMMITTED,
    PARTIAL,
    CONFLICT,
    INDETERMINATE,

    /** Engineering members are proven, but workspace files remain with the app coordinator. */
    WORKSPACE_DEFERRED,
}

internal enum class MixedProjectMemberStatus {
    COMMITTED,
    PARTIAL,
    REJECTED,
    CONFLICT,
    INDETERMINATE,

    /** A workspace file reference checked here but never written into the .lnt transaction. */
    WORKSPACE_DEFERRED,
}

internal data class MixedProjectRejectedObject(val objectId: String, val reason: String)

internal data class MixedProjectMemberResult(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val childCommandId: CommandId,
    val childDigest: ObjectFingerprint,
    val selection: List<String>,
    val selectionDigest: ObjectFingerprint,
    val status: MixedProjectMemberStatus,
    val receiptDigest: ObjectFingerprint?,
    val committedIds: List<String>,
    val rejected: List<MixedProjectRejectedObject>,
    val revision: Long,
    val reason: MixedApprovalRejection?,
) {
    val approvalState: MixedApprovalMemberState
        get() = when (status) {
            MixedProjectMemberStatus.COMMITTED -> MixedApprovalMemberState.COMMITTED
            MixedProjectMemberStatus.PARTIAL, MixedProjectMemberStatus.CONFLICT ->
                MixedApprovalMemberState.CONFLICT
            MixedProjectMemberStatus.REJECTED -> MixedApprovalMemberState.REJECTED
            MixedProjectMemberStatus.INDETERMINATE,
            MixedProjectMemberStatus.WORKSPACE_DEFERRED -> MixedApprovalMemberState.INDETERMINATE
        }

    /** Builds the terminal child receipt used by MixedApprovalStore.settle. */
    fun toApprovalReceipt(member: MixedApprovalMember): MixedApprovalMemberReceipt {
        require(kind == member.kind && childId == member.childId && childDigest == member.childDigest &&
            childCommandId == member.childCommandId && selectionDigest == member.selectionDigest) {
            "Mixed member result does not bind this member"
        }
        require(status != MixedProjectMemberStatus.WORKSPACE_DEFERRED) {
            "A deferred workspace member has no .lnt receipt"
        }
        val state = approvalState
        require(state != MixedApprovalMemberState.INDETERMINATE) { "An indeterminate child has no receipt" }
        return MixedApprovalMemberReceipt(kind, childId, childDigest, childCommandId, selectionDigest, state,
            receiptDigest, reason)
    }
}

internal data class MixedProjectWriteResult(
    val approvalId: ApprovalRequestId,
    val parentCommandId: CommandId,
    val parentDigest: MixedApprovalDigest,
    val baseRevision: Long,
    val status: MixedProjectWriteStatus,
    val revision: Long,
    val members: List<MixedProjectMemberResult>,
    /** Null while [status] is INDETERMINATE; otherwise binds every member, including deferred workspace refs. */
    val receiptDigest: ObjectFingerprint?,
) {
    /**
     * The parent state a coordinator may settle, or null while this writer cannot determine it.
     * A deferred workspace batch is never reported committed here: engineering receipts can only
     * support PARTIAL until the app coordinator adds the real workspace receipts.
     */
    val approvalState: MixedApprovalState?
        get() = when (status) {
            MixedProjectWriteStatus.COMMITTED -> MixedApprovalState.COMMITTED
            MixedProjectWriteStatus.PARTIAL -> if (members.any {
                it.status == MixedProjectMemberStatus.COMMITTED
            }) MixedApprovalState.PARTIAL else MixedApprovalState.CONFLICT
            MixedProjectWriteStatus.CONFLICT -> MixedApprovalState.CONFLICT
            MixedProjectWriteStatus.WORKSPACE_DEFERRED -> if (members.any {
                it.status == MixedProjectMemberStatus.COMMITTED
            }) MixedApprovalState.PARTIAL else null
            MixedProjectWriteStatus.INDETERMINATE -> null
        }

    /** Terminal child receipts ready for MixedApprovalStore.settle; missing/deferred children stay null. */
    fun toApprovalReceipts(approval: MixedApproval): List<MixedApprovalMemberReceipt> {
        require(approval.members.size == members.size) { "Mixed member count changed" }
        return members.zip(approval.members).mapNotNull { (memberResult, member) ->
            if (memberResult.status == MixedProjectMemberStatus.INDETERMINATE ||
                memberResult.status == MixedProjectMemberStatus.WORKSPACE_DEFERRED) null
            else memberResult.toApprovalReceipt(member)
        }
    }
}

/**
 * One child body participating in a mixed approval. The command, digest and selection are
 * derived from the frozen child proposal; the parent member is checked against all three before
 * any project write starts. A workspace file has no body here: it can be supplied to validate its
 * parent reference, but the .lnt transaction never writes it.
 */
internal sealed class MixedProjectMemberInput {
    abstract val kind: MixedApprovalMemberKind
    abstract val childId: String
    abstract val childCommandId: CommandId
    abstract val childDigest: ObjectFingerprint
    abstract val childRevision: Long
    abstract val selection: List<String>

    class Items(
        val proposal: ItemChangeProposal,
        val selected: Set<ItemId>,
        selection: List<String> = itemSelection(selected),
    ) : MixedProjectMemberInput() {
        override val kind: MixedApprovalMemberKind = MixedApprovalMemberKind.ITEM
        override val childId: String = proposal.id.value
        override val childCommandId: CommandId = proposal.command
        override val childDigest: ObjectFingerprint = proposal.digest
        override val childRevision: Long = proposal.revision
        override val selection: List<String> = frozenSelection(selection)

        init {
            require(selected.isNotEmpty() && selected.all { id -> proposal.members.any { it.id == id } })
            require(this.selection == itemSelection(selected)) {
                "Item selection does not match the frozen proposal objects"
            }
        }
    }

    class Policy(
        val proposal: PolicyChangeProposal,
        selection: List<String> = emptyList(),
    ) : MixedProjectMemberInput() {
        override val kind: MixedApprovalMemberKind = MixedApprovalMemberKind.POLICY
        override val childId: String = proposal.id.value
        override val childCommandId: CommandId = proposal.command
        override val childDigest: ObjectFingerprint = proposal.digest
        override val childRevision: Long = proposal.revision
        override val selection: List<String> = frozenSelection(selection)

        init {
            require(this.selection.isEmpty() || this.selection == listOf(proposal.id.value)) {
                "Policy approval selects one whole policy"
            }
        }
    }

    class PdfPages(
        val proposal: PdfPageChangeProposal,
        val selected: Set<Int>,
        selection: List<String> = pdfSelection(selected),
    ) : MixedProjectMemberInput() {
        override val kind: MixedApprovalMemberKind = MixedApprovalMemberKind.PDF_PAGE
        override val childId: String = proposal.id.value
        override val childCommandId: CommandId = proposal.command
        override val childDigest: ObjectFingerprint = proposal.digest
        override val childRevision: Long = proposal.revision
        override val selection: List<String> = frozenSelection(selection)

        init {
            require(selected.isNotEmpty() && selected.all { page -> proposal.members.any { it.page == page } })
            require(this.selection == pdfSelection(selected)) {
                "PDF selection does not match the frozen proposal pages"
            }
        }
    }

    class AssetImport(
        val proposal: AssetImportProposal,
        val plan: AssetImportPlan,
        selection: List<String> = emptyList(),
    ) : MixedProjectMemberInput() {
        override val kind: MixedApprovalMemberKind = MixedApprovalMemberKind.ASSET_IMPORT
        override val childId: String = proposal.id.value
        override val childCommandId: CommandId = proposal.command
        override val childDigest: ObjectFingerprint = proposal.digest
        override val childRevision: Long = proposal.revision
        override val selection: List<String> = frozenSelection(selection)

        init {
            require(this.selection.isEmpty() || this.selection == listOf(proposal.id.value)) {
                "Asset import approval selects one whole artifact"
            }
        }
    }

    /**
     * Reference-only workspace member. Its body is committed by the app workspace coordinator
     * before or after this writer; this class lets the caller prove the parent reference (including
     * the workspace root identity) without ever writing a workspace file into .lnt.
     */
    class WorkspaceFile(
        override val childId: String,
        override val childCommandId: CommandId,
        override val childDigest: ObjectFingerprint,
        override val childRevision: Long,
        selection: List<String>,
        val workspaceRootIdentity: String,
    ) : MixedProjectMemberInput() {
        override val kind: MixedApprovalMemberKind = MixedApprovalMemberKind.WORKSPACE_FILE
        override val selection: List<String> = frozenSelection(selection)

        init {
            require(childId.isNotBlank()) { "Workspace child identity is blank" }
            require(childRevision >= 0) { "Invalid workspace child revision" }
            require(workspaceRootIdentity.isNotBlank()) { "Workspace root identity is blank" }
        }
    }
}

internal fun itemSelection(selected: Set<ItemId>): List<String> =
    selected.map { it.value.toString() }.sorted()

internal fun pdfSelection(selected: Set<Int>): List<String> =
    selected.map { it.toString() }.sorted()

internal fun frozenSelection(selection: List<String>): List<String> {
    require(selection.size <= MixedApprovalMember.MAX_SELECTION) { "Mixed selection is too large" }
    require(selection.toSet().size == selection.size) { "Duplicate mixed selection entry" }
    selection.forEach { require(it.isNotBlank() && it.none(Char::isISOControl)) }
    return java.util.Collections.unmodifiableList(ArrayList(selection.sorted()))
}

/**
 * Owns one mixed engineering write. Child bodies are immutable proposals; every engineering child
 * receipt is written by its existing project writer inside the single enclosing transaction.
 * Workspace file members are validated against the parent but never written here; they are reported
 * as deferred for the app coordinator, which owns their real receipts. The parent approval is only
 * a reference set, so a parent result can always be rebuilt from the child receipts and never turns
 * CONSUMED alone into success.
 */
internal object MixedProjectWriter {
    fun apply(
        db: SQLiteDatabase,
        ref: LocalProjectRef,
        approval: MixedApproval,
        binding: MixedApprovalBinding,
        members: List<MixedProjectMemberInput>,
        beforeCommit: () -> Unit = {},
        maximumItems: Int = TextProjectLimits().maxItems,
    ): MixedProjectWriteResult {
        require(maximumItems > 0) { "Invalid item budget" }
        val prepared = prepare(ref, approval, binding, members)
        // A workspace-only parent never opens the .lnt transaction. The app coordinator owns the
        // real workspace receipt; this writer only proves the frozen parent references.
        if (prepared.engineering.isEmpty()) {
            return result(prepared, emptyList(), currentRevision(db))
        }
        NativeProjectDatabase.ensureV8(db)
        return NativeProjectDatabase.transaction(db) {
            val existing = prepared.engineering.map { loadReceipt(db, requireNotNull(it.input)) }
            if (existing.any { it != null }) {
                return@transaction result(prepared, existing, currentRevision(db))
            }
            val startRevision = currentRevision(db)
            if (approval.policy == ConflictPolicy.REJECT_BATCH && startRevision != approval.baseRevision) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.STALE_REVISION)
            }
            for (preparedMember in prepared.engineering) {
                val input = requireNotNull(preparedMember.input)
                beforeCommit()
                // Same-base children are rebased onto this mixed transaction. A child prepared
                // for a projected later revision keeps its own revision and must match it now.
                val childStart = if (input.childRevision == approval.baseRevision) startRevision else null
                val written = write(db, input, childStart, maximumItems, approval.policy, beforeCommit)
                if (approval.policy == ConflictPolicy.REJECT_BATCH &&
                    written.status != MixedProjectMemberStatus.COMMITTED) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REJECTED)
                }
                beforeCommit()
            }
            // The durable child receipts are the authority, including on an idempotent retry.
            val verified = prepared.engineering.map { preparedMember ->
                loadReceipt(db, requireNotNull(preparedMember.input))
                    ?: throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_MISSING)
            }
            if (approval.policy == ConflictPolicy.REJECT_BATCH &&
                verified.any { it.status != MixedProjectMemberStatus.COMMITTED }) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REJECTED)
            }
            beforeCommit()
            result(prepared, verified, verified.map { it.revision }.maxOrNull() ?: startRevision)
        }
    }

    /** Read-only restart classification; never writes or repairs a missing child receipt. */
    fun recover(
        db: SQLiteDatabase,
        ref: LocalProjectRef,
        approval: MixedApproval,
        binding: MixedApprovalBinding,
        members: List<MixedProjectMemberInput>,
    ): MixedProjectWriteResult {
        val prepared = prepare(ref, approval, binding, members)
        if (prepared.engineering.isEmpty()) {
            return result(prepared, emptyList(), currentRevision(db))
        }
        NativeProjectDatabase.ensureV8(db)
        return NativeProjectDatabase.transaction(db) {
            val existing = prepared.engineering.map { loadReceipt(db, requireNotNull(it.input)) }
            result(prepared, existing, currentRevision(db))
        }
    }

    private class Prepared(
        val approval: MixedApproval,
        val ordered: List<PreparedMember>,
    ) {
        /** Only these members join the single .lnt transaction and its receipt checks. */
        val engineering: List<PreparedMember> = ordered.filter { it.engineering }
        val hasWorkspace: Boolean = ordered.any { !it.engineering }
    }

    private class PreparedMember(
        val member: MixedApprovalMember,
        /** Null is allowed only for an omitted workspace reference, never for engineering. */
        val input: MixedProjectMemberInput?,
    ) {
        val engineering: Boolean get() = member.kind != MixedApprovalMemberKind.WORKSPACE_FILE
    }

    private class LoadedReceipt(
        val status: MixedProjectMemberStatus,
        val receiptDigest: ObjectFingerprint,
        val committedIds: List<String>,
        val rejected: List<MixedProjectRejectedObject>,
        val revision: Long,
        val reason: MixedApprovalRejection?,
    )

    private fun prepare(
        ref: LocalProjectRef,
        approval: MixedApproval,
        binding: MixedApprovalBinding,
        members: List<MixedProjectMemberInput>,
    ): Prepared {
        if (approval.state != MixedApprovalState.CONSUMED) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.NOT_CONSUMED)
        }
        val consumed = approval.binding ?: throw MixedProjectWriteException(MixedProjectWriteRefusal.BINDING)
        if (binding.requestId != approval.id || binding.digest != approval.digest ||
            binding.commandId != approval.commandId || binding.ref != approval.ref ||
            binding.chat != approval.chat || binding.run != approval.run ||
            binding.baseRevision != approval.baseRevision || binding.policy != approval.policy ||
            consumed.fingerprint != binding.fingerprint) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.BINDING)
        }
        if (approval.ref != ref) throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
        if (members.size > approval.members.size) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.INVALID_MEMBER_SET)
        }
        val byKey = LinkedHashMap<String, MixedProjectMemberInput>(members.size)
        for (input in members) {
            if (byKey.put(input.kind.name + '\u0000' + input.childId, input) != null) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.DUPLICATE_MEMBER)
            }
            if (input.childCommandId == approval.commandId) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.PARENT_COMMAND)
            }
        }
        val commands = HashSet<String>(members.size)
        val ordered = ArrayList<PreparedMember>(approval.members.size)
        for (member in approval.members) {
            val input = byKey.remove(member.identityKey)
            if (input == null) {
                // The app coordinator applies workspace members first. Their absence here is a
                // deferred reference, not a missing engineering body.
                if (member.kind != MixedApprovalMemberKind.WORKSPACE_FILE) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.INVALID_MEMBER_SET)
                }
                ordered.add(PreparedMember(member, null))
                continue
            }
            if (!commands.add(input.childCommandId.value)) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.DUPLICATE_MEMBER)
            }
            if (input.childCommandId != member.childCommandId) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_COMMAND)
            }
            if (input.childDigest != member.childDigest) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_DIGEST)
            }
            if (!selectionMatches(member, input)) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_SELECTION)
            }
            if (input is MixedProjectMemberInput.WorkspaceFile &&
                input.workspaceRootIdentity != member.workspaceRootIdentity) {
                throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
            }
            validateChild(ref, approval, input)
            ordered.add(PreparedMember(member, input))
        }
        if (byKey.isNotEmpty()) throw MixedProjectWriteException(MixedProjectWriteRefusal.INVALID_MEMBER_SET)
        return Prepared(approval, ordered)
    }

    private fun selectionMatches(member: MixedApprovalMember, input: MixedProjectMemberInput): Boolean =
        when (member.kind) {
            MixedApprovalMemberKind.POLICY, MixedApprovalMemberKind.ASSET_IMPORT ->
                wholeObjectSelection(member.selection, member.childId) &&
                    wholeObjectSelection(input.selection, input.childId)
            MixedApprovalMemberKind.WORKSPACE_FILE -> member.selection == input.selection
            else -> member.selection.toSet() == input.selection.toSet()
        }

    private fun wholeObjectSelection(selection: List<String>, childId: String): Boolean =
        selection.isEmpty() || selection == listOf(childId)

    private fun validateChild(
        ref: LocalProjectRef,
        approval: MixedApproval,
        input: MixedProjectMemberInput,
    ) {
        when (input) {
            is MixedProjectMemberInput.Items -> {
                val proposal = input.proposal
                if (proposal.ref != ref || proposal.chat != approval.chat || proposal.run != approval.run) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
                }
                if (proposal.revision < approval.baseRevision) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REVISION)
                }
                if (proposal.command != input.childCommandId || proposal.digest != input.childDigest) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_COMMAND)
                }
            }
            is MixedProjectMemberInput.Policy -> {
                val proposal = input.proposal
                if (proposal.ref != ref || proposal.chat != approval.chat || proposal.run != approval.run) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
                }
                if (proposal.revision < approval.baseRevision) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REVISION)
                }
                if (proposal.command != input.childCommandId || proposal.digest != input.childDigest) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_COMMAND)
                }
                TextRuleValidation.validate(proposal.after)
            }
            is MixedProjectMemberInput.PdfPages -> {
                val proposal = input.proposal
                if (proposal.ref != ref || proposal.chat != approval.chat || proposal.run != approval.run) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
                }
                if (proposal.revision < approval.baseRevision) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REVISION)
                }
                if (proposal.command != input.childCommandId || proposal.digest != input.childDigest) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_COMMAND)
                }
            }
            is MixedProjectMemberInput.AssetImport -> {
                val proposal = input.proposal
                if (proposal.ref != ref || proposal.chat != approval.chat || proposal.run != approval.run) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.IDENTITY)
                }
                if (proposal.revision < approval.baseRevision) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REVISION)
                }
                if (proposal.command != input.childCommandId || proposal.digest != input.childDigest) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_COMMAND)
                }
                validateAssetPlan(input)
            }
            is MixedProjectMemberInput.WorkspaceFile -> Unit
        }
    }

    private fun validateAssetPlan(input: MixedProjectMemberInput.AssetImport) {
        val proposal = input.proposal
        val plan = input.plan
        if (plan.ref != proposal.ref || plan.commandId != proposal.command ||
            plan.expectedRevision != proposal.revision) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.CHILD_REVISION)
        }
        if (plan.path != proposal.target || plan.enabled != proposal.enabled ||
            plan.bytes.size.toLong() != proposal.artifactSizeBytes ||
            TextProjectFingerprint.bytes(plan.bytes) != proposal.artifactSha256) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.VALIDATION)
        }
    }

    private fun write(
        db: SQLiteDatabase,
        input: MixedProjectMemberInput,
        transactionStartRevision: Long?,
        maximumItems: Int,
        approvalPolicy: ConflictPolicy,
        beforeCommit: () -> Unit,
    ): LoadedReceipt = when (input) {
        is MixedProjectMemberInput.Items -> fromItem(
            ApprovedItemBatchWriter.applyInTransaction(db, input.proposal, input.selected, beforeCommit,
                transactionStartRevision, approvalPolicy),
            input.proposal,
            input.selected,
        )
        is MixedProjectMemberInput.Policy -> fromPolicy(
            ProjectWriteStore.editPolicyInTransaction(db, input.proposal.intent(), beforeCommit,
                transactionStartRevision),
            input.proposal,
        )
        is MixedProjectMemberInput.PdfPages -> fromPdf(
            PdfPageBatchWriter.applyInTransaction(db, input.proposal, input.selected, beforeCommit,
                transactionStartRevision, approvalPolicy),
            input.proposal,
            input.selected,
        )
        is MixedProjectMemberInput.AssetImport -> fromAsset(
            ProjectAssetWriteStore.importAssetInTransaction(
                db, input.plan, maximumItems, beforeCommit, transactionStartRevision,
            ),
            input.proposal,
            input.plan,
        )
        is MixedProjectMemberInput.WorkspaceFile ->
            throw MixedProjectWriteException(MixedProjectWriteRefusal.UNSUPPORTED_MEMBER)
    }

    private fun loadReceipt(db: SQLiteDatabase, input: MixedProjectMemberInput): LoadedReceipt? = when (input) {
        is MixedProjectMemberInput.Items -> ApprovedItemBatchWriter.receipt(db, input.proposal, input.selected)
            ?.let { fromItem(it, input.proposal, input.selected) }
        is MixedProjectMemberInput.Policy -> ProjectWriteStore.checkedPolicyReceipt(db, input.proposal.intent())
            ?.let { fromPolicy(it, input.proposal) }
        is MixedProjectMemberInput.PdfPages -> PdfPageBatchWriter.receipt(db, input.proposal, input.selected)
            ?.let { fromPdf(it, input.proposal, input.selected) }
        is MixedProjectMemberInput.AssetImport -> {
            val prior = ProjectAssetWriteStore.receipt(db, input.proposal.ref, input.proposal.command)
            prior?.let { (digest, receipt) ->
                if (digest != input.plan.digest()) {
                    throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
                }
                fromAsset(receipt, input.proposal, input.plan)
            }
        }
        is MixedProjectMemberInput.WorkspaceFile ->
            throw MixedProjectWriteException(MixedProjectWriteRefusal.UNSUPPORTED_MEMBER)
    }

    private fun fromItem(
        receipt: ItemProposalReceipt,
        proposal: ItemChangeProposal,
        selected: Set<ItemId>,
    ): LoadedReceipt {
        if (receipt.command != proposal.command || receipt.binding != proposal.binding(selected) ||
            receipt.accepted.any { it !in selected } ||
            receipt.rejected.any { (id, reason) ->
                (reason == ItemProposalRejection.NOT_SELECTED) != (id !in selected)
            }) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val loaded = loadedItem(receipt)
        if (loaded.status == MixedProjectMemberStatus.COMMITTED && receipt.revision < proposal.revision) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        return loaded
    }

    private fun fromPolicy(receipt: PolicyEditReceipt, proposal: PolicyChangeProposal): LoadedReceipt {
        if (receipt.commandId != proposal.command || receipt.ref != proposal.ref) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val status = if (receipt.status == PolicyEditStatus.COMMITTED) MixedProjectMemberStatus.COMMITTED
        else MixedProjectMemberStatus.CONFLICT
        if (status == MixedProjectMemberStatus.COMMITTED && receipt.revision < proposal.revision) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val reason = when (receipt.status) {
            PolicyEditStatus.COMMITTED -> null
            PolicyEditStatus.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
            PolicyEditStatus.STALE_FINGERPRINT -> MixedApprovalRejection.STALE_FINGERPRINT
        }
        val committed = if (status == MixedProjectMemberStatus.COMMITTED) listOf("policy") else emptyList()
        val rejected = if (status == MixedProjectMemberStatus.COMMITTED) emptyList() else
            listOf(MixedProjectRejectedObject("policy", receipt.status.name))
        return LoadedReceipt(status, policyReceiptDigest(proposal, receipt), committed, rejected,
            receipt.revision, reason)
    }

    private fun fromPdf(
        receipt: PdfPageProposalReceipt,
        proposal: PdfPageChangeProposal,
        selected: Set<Int>,
    ): LoadedReceipt {
        if (receipt.command != proposal.command || receipt.binding != proposal.binding(selected)) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val loaded = loadedPdf(receipt)
        if (loaded.status == MixedProjectMemberStatus.COMMITTED && receipt.revision < proposal.revision) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        return loaded
    }

    private fun fromAsset(
        receipt: AssetMutationReceipt,
        proposal: AssetImportProposal,
        plan: AssetImportPlan,
    ): LoadedReceipt {
        if (receipt.commandId != proposal.command || receipt.ref != proposal.ref ||
            receipt.kind != AssetMutationKind.IMPORT) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val status = if (receipt.status == AssetMutationStatus.COMMITTED) MixedProjectMemberStatus.COMMITTED
        else MixedProjectMemberStatus.CONFLICT
        if (status == MixedProjectMemberStatus.COMMITTED && receipt.revision < proposal.revision) {
            throw MixedProjectWriteException(MixedProjectWriteRefusal.RECEIPT_CONFLICT)
        }
        val reason = when (receipt.status) {
            AssetMutationStatus.COMMITTED -> null
            AssetMutationStatus.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
            AssetMutationStatus.UNKNOWN_ASSET -> MixedApprovalRejection.UNKNOWN_TARGET
            AssetMutationStatus.CONFLICT -> MixedApprovalRejection.VALIDATION
        }
        val committed = if (status == MixedProjectMemberStatus.COMMITTED)
            listOf(requireNotNull(receipt.assetId).toString()) else emptyList()
        val rejected = if (status == MixedProjectMemberStatus.COMMITTED) emptyList() else
            listOf(MixedProjectRejectedObject(proposal.target.value, receipt.status.name))
        return LoadedReceipt(status, assetReceiptDigest(proposal, plan, receipt), committed, rejected,
            receipt.revision, reason)
    }

    private fun loadedItem(receipt: ItemProposalReceipt): LoadedReceipt {
        val selected = receipt.accepted.toSet() + receipt.rejected.keys
        val conflicts = receipt.rejected.entries
            .filter { (id, reason) -> id in selected && reason != ItemProposalRejection.NOT_SELECTED }
            .sortedBy { it.key.value }
        val committed = receipt.accepted.map { it.value.toString() }.sorted()
        val selectedCount = receipt.accepted.size + conflicts.size
        val status = when {
            conflicts.isEmpty() && receipt.accepted.isNotEmpty() &&
                receipt.accepted.size == selectedCount -> MixedProjectMemberStatus.COMMITTED
            receipt.accepted.isNotEmpty() -> MixedProjectMemberStatus.PARTIAL
            else -> MixedProjectMemberStatus.CONFLICT
        }
        val rejected = conflicts.map { (id, reason) ->
            MixedProjectRejectedObject(id.value.toString(), reason.name)
        }
        val reason = if (status == MixedProjectMemberStatus.COMMITTED) null else
            conflicts.firstOrNull()?.value?.toMixed() ?: MixedApprovalRejection.VALIDATION
        return LoadedReceipt(status, itemReceiptDigest(receipt), committed, rejected, receipt.revision, reason)
    }

    private fun loadedPdf(receipt: PdfPageProposalReceipt): LoadedReceipt {
        val selected = receipt.accepted.toSet() + receipt.rejected.keys
        val conflicts = receipt.rejected.entries
            .filter { (page, reason) -> page in selected && reason != PdfPageProposalRejection.NOT_SELECTED }
            .sortedBy { it.key }
        val committed = receipt.accepted.map { it.toString() }.sorted()
        val selectedCount = receipt.accepted.size + conflicts.size
        val status = when {
            conflicts.isEmpty() && receipt.accepted.isNotEmpty() &&
                receipt.accepted.size == selectedCount -> MixedProjectMemberStatus.COMMITTED
            receipt.accepted.isNotEmpty() -> MixedProjectMemberStatus.PARTIAL
            else -> MixedProjectMemberStatus.CONFLICT
        }
        val rejected = conflicts.map { (page, reason) ->
            MixedProjectRejectedObject(page.toString(), reason.name)
        }
        val reason = if (status == MixedProjectMemberStatus.COMMITTED) null else
            conflicts.firstOrNull()?.value?.toMixed() ?: MixedApprovalRejection.VALIDATION
        return LoadedReceipt(status, pdfReceiptDigest(receipt), committed, rejected, receipt.revision, reason)
    }

    private fun result(
        prepared: Prepared,
        receipts: List<LoadedReceipt?>,
        currentRevision: Long,
    ): MixedProjectWriteResult {
        require(receipts.size == prepared.engineering.size) { "Mixed engineering receipt count changed" }
        val knownRevision = receipts.mapNotNull { it?.revision }.maxOrNull() ?: currentRevision
        var engineeringIndex = 0
        val results = prepared.ordered.map { preparedMember ->
            val member = preparedMember.member
            if (!preparedMember.engineering) {
                // Reference-only: never a .lnt child receipt and never parent success on its own.
                MixedProjectMemberResult(member.kind, member.childId, member.childCommandId,
                    member.childDigest, member.selection, member.selectionDigest,
                    MixedProjectMemberStatus.WORKSPACE_DEFERRED, null, emptyList(), emptyList(),
                    knownRevision, null)
            } else {
                val receipt = receipts[engineeringIndex++]
                if (receipt == null) {
                    MixedProjectMemberResult(member.kind, member.childId, member.childCommandId,
                        member.childDigest, member.selection, member.selectionDigest,
                        MixedProjectMemberStatus.INDETERMINATE, null, emptyList(), emptyList(),
                        knownRevision, null)
                } else {
                    MixedProjectMemberResult(member.kind, member.childId, member.childCommandId,
                        member.childDigest, member.selection, member.selectionDigest, receipt.status,
                        receipt.receiptDigest, receipt.committedIds, receipt.rejected, receipt.revision,
                        receipt.reason)
                }
            }
        }
        val engineeringResults = results.filter { it.kind != MixedApprovalMemberKind.WORKSPACE_FILE }
        val status = when {
            prepared.engineering.isEmpty() -> MixedProjectWriteStatus.WORKSPACE_DEFERRED
            engineeringResults.all { it.status == MixedProjectMemberStatus.INDETERMINATE } ->
                MixedProjectWriteStatus.INDETERMINATE
            engineeringResults.any { it.status == MixedProjectMemberStatus.INDETERMINATE } ->
                if (engineeringResults.any {
                    it.status == MixedProjectMemberStatus.COMMITTED ||
                        it.status == MixedProjectMemberStatus.PARTIAL
                }) MixedProjectWriteStatus.PARTIAL else MixedProjectWriteStatus.CONFLICT
            engineeringResults.all { it.status == MixedProjectMemberStatus.COMMITTED } ->
                if (prepared.hasWorkspace) MixedProjectWriteStatus.WORKSPACE_DEFERRED
                else MixedProjectWriteStatus.COMMITTED
            engineeringResults.any {
                it.status == MixedProjectMemberStatus.COMMITTED ||
                    it.status == MixedProjectMemberStatus.PARTIAL
            } -> MixedProjectWriteStatus.PARTIAL
            else -> MixedProjectWriteStatus.CONFLICT
        }
        val result = MixedProjectWriteResult(prepared.approval.id, prepared.approval.commandId,
            prepared.approval.digest, prepared.approval.baseRevision, status, knownRevision, results, null)
        return if (status == MixedProjectWriteStatus.INDETERMINATE) result
        else result.copy(receiptDigest = parentReceiptDigest(result))
    }

    private fun currentRevision(db: SQLiteDatabase): Long =
        NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton=1")

    private fun itemReceiptDigest(receipt: ItemProposalReceipt): ObjectFingerprint =
        digest(buildJsonObject {
            put("format", "mixed-child-receipt-v1")
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

    private fun pdfReceiptDigest(receipt: PdfPageProposalReceipt): ObjectFingerprint =
        digest(buildJsonObject {
            put("format", "mixed-child-receipt-v1")
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

    private fun policyReceiptDigest(
        proposal: PolicyChangeProposal,
        receipt: PolicyEditReceipt,
    ): ObjectFingerprint = digest(buildJsonObject {
        put("format", "mixed-child-receipt-v1")
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

    private fun assetReceiptDigest(
        proposal: AssetImportProposal,
        plan: AssetImportPlan,
        receipt: AssetMutationReceipt,
    ): ObjectFingerprint = digest(buildJsonObject {
        put("format", "mixed-child-receipt-v1")
        put("kind", MixedApprovalMemberKind.ASSET_IMPORT.name)
        put("command", receipt.commandId.value)
        put("plan_digest", plan.digest())
        put("proposal_digest", proposal.digest.sha256)
        put("project", receipt.ref.projectId.value)
        put("instance", receipt.ref.instanceId.value)
        put("generation", receipt.ref.generation.value)
        put("status", receipt.status.name)
        put("revision", receipt.revision)
        receipt.assetId?.let { put("asset", it) }
        put("committed_at", receipt.committedAtMillis)
    })

    private fun parentReceiptDigest(result: MixedProjectWriteResult): ObjectFingerprint =
        digest(buildJsonObject {
            put("format", "mixed-project-receipt-v1")
            put("approval", result.approvalId.value)
            put("command", result.parentCommandId.value)
            put("digest", result.parentDigest.sha256)
            put("base_revision", result.baseRevision)
            put("status", result.status.name)
            put("revision", result.revision)
            put("members", JsonArray(result.members.map { member ->
                buildJsonObject {
                    put("kind", member.kind.name)
                    put("child", member.childId)
                    put("child_digest", member.childDigest.sha256)
                    put("child_command", member.childCommandId.value)
                    put("selection_digest", member.selectionDigest.sha256)
                    put("status", member.status.name)
                    member.receiptDigest?.let { put("receipt_digest", it.sha256) }
                    put("committed", JsonArray(member.committedIds.map { JsonPrimitive(it) }))
                    put("rejected", JsonArray(member.rejected.map {
                        buildJsonObject { put("object", it.objectId); put("reason", it.reason) }
                    }))
                    put("revision", member.revision)
                    member.reason?.let { put("reason", it.name) }
                }
            }))
        })

    private fun digest(value: kotlinx.serialization.json.JsonObject): ObjectFingerprint =
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(value.toString())))

    private fun ItemProposalRejection.toMixed(): MixedApprovalRejection = when (this) {
        ItemProposalRejection.NOT_SELECTED -> MixedApprovalRejection.NOT_SELECTED
        ItemProposalRejection.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
        ItemProposalRejection.STALE_FINGERPRINT -> MixedApprovalRejection.STALE_FINGERPRINT
        ItemProposalRejection.UNKNOWN_ITEM -> MixedApprovalRejection.UNKNOWN_TARGET
        ItemProposalRejection.BATCH_CONFLICT -> MixedApprovalRejection.DEPENDENCY_FAILED
    }

    private fun PdfPageProposalRejection.toMixed(): MixedApprovalRejection = when (this) {
        PdfPageProposalRejection.NOT_SELECTED -> MixedApprovalRejection.NOT_SELECTED
        PdfPageProposalRejection.STALE_REVISION -> MixedApprovalRejection.STALE_REVISION
        PdfPageProposalRejection.STALE_ASSET, PdfPageProposalRejection.STALE_PAGE ->
            MixedApprovalRejection.STALE_FINGERPRINT
        PdfPageProposalRejection.BATCH_CONFLICT -> MixedApprovalRejection.DEPENDENCY_FAILED
    }

}
