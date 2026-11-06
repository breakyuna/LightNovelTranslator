package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical SHA-256 of one mixed parent approval; the user approves exactly this value. */
@JvmInline
value class MixedApprovalDigest(val sha256: String) {
    init { requireSha256(sha256) }
    override fun toString(): String = "MixedApprovalDigest($sha256)"
}

/** Child approval families a mixed parent may reference. No child body is copied into the parent. */
enum class MixedApprovalMemberKind { ITEM, POLICY, PDF_PAGE, ASSET_IMPORT, WORKSPACE_FILE }

enum class MixedApprovalState {
    PREPARED,
    PENDING,
    CONSUMED,
    COMMITTED,
    PARTIAL,
    REJECTED,
    CONFLICT,
    CANCELLED;

    /** A terminal parent has a durable outcome and can never authorize another write. */
    val terminal: Boolean get() = this != PREPARED && this != PENDING && this != CONSUMED
}

enum class MixedApprovalMemberState {
    PENDING,
    COMMITTED,
    REJECTED,
    CONFLICT,
    CANCELLED,

    /** The child receipt could not be proven; this is never success. */
    INDETERMINATE,
}

enum class MixedApprovalRejection {
    NOT_SELECTED,
    STALE_REVISION,
    STALE_FINGERPRINT,
    UNKNOWN_TARGET,
    DEPENDENCY_FAILED,
    VALIDATION,
    POLICY,
    USER_REJECTED,
    CANCELLED,
}

/**
 * One immutable reference to a child approval. It deliberately stores only the child identity,
 * its digest, command, selection identifiers and expected object fingerprints. Novel text,
 * prompts, credentials and workspace file bodies never enter a mixed approval record.
 *
 * [selection] and [expectedFingerprints] are sorted by the stable selection identifier so the
 * same set always produces the same selection digest and parent digest.
 */
class MixedApprovalMember(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val childDigest: ObjectFingerprint,
    val childCommandId: CommandId,
    selection: List<String>,
    expectedFingerprints: List<ObjectFingerprint>,
    val workspaceRootIdentity: String?,
    val state: MixedApprovalMemberState = MixedApprovalMemberState.PENDING,
    val receiptDigest: ObjectFingerprint? = null,
    val rejection: MixedApprovalRejection? = null,
) {
    val selection: List<String>
    val expectedFingerprints: List<ObjectFingerprint>

    init {
        requireBoundedText(childId, "mixed child identity", MAX_CHILD_ID)
        require(selection.size <= MAX_SELECTION) { "Mixed selection is too large" }
        selection.forEach { requireBoundedText(it, "mixed selection entry", MAX_SELECTION_ENTRY) }
        require(selection.toSet().size == selection.size) { "Duplicate mixed selection entry" }
        require(expectedFingerprints.size <= MAX_SELECTION) { "Mixed fingerprint list is too large" }
        require(expectedFingerprints.isEmpty() || expectedFingerprints.size == selection.size) {
            "Mixed fingerprints must match the selection"
        }
        if (kind == MixedApprovalMemberKind.WORKSPACE_FILE) {
            require(workspaceRootIdentity != null) { "A workspace file requires a root identity" }
            requireBoundedText(requireNotNull(workspaceRootIdentity), "mixed workspace root",
                MAX_WORKSPACE_ROOT)
        } else {
            require(workspaceRootIdentity == null) { "Only a workspace file has a root identity" }
        }
        when (state) {
            MixedApprovalMemberState.PENDING ->
                require(receiptDigest == null && rejection == null) { "A pending member has no receipt" }
            MixedApprovalMemberState.COMMITTED ->
                require(receiptDigest != null && rejection == null) {
                    "A committed member requires a receipt digest"
                }
            MixedApprovalMemberState.REJECTED ->
                require(rejection != null && rejection != MixedApprovalRejection.CANCELLED) {
                    "A rejected member requires a rejection reason"
                }
            MixedApprovalMemberState.CONFLICT ->
                require(rejection != null) { "A conflicted member requires a rejection reason" }
            MixedApprovalMemberState.CANCELLED ->
                require(receiptDigest == null &&
                    (rejection == null || rejection == MixedApprovalRejection.CANCELLED)) {
                    "A cancelled member has no receipt"
                }
            MixedApprovalMemberState.INDETERMINATE ->
                require(receiptDigest == null && rejection == null) {
                    "An indeterminate member has no receipt"
                }
        }
        if (expectedFingerprints.isEmpty()) {
            this.selection = frozenList(selection.sorted())
            this.expectedFingerprints = frozenList(emptyList<ObjectFingerprint>())
        } else {
            val sorted = selection.zip(expectedFingerprints).sortedBy { it.first }
            this.selection = frozenList(sorted.map { it.first })
            this.expectedFingerprints = frozenList(sorted.map { it.second })
        }
    }

    val identityKey: String get() = mixedMemberKey(kind, childId)

    val selectionDigest: ObjectFingerprint = ObjectFingerprint(sha256Text(buildJsonObject {
        put("version", 1)
        put("format", "mixed-selection")
        // The constructor parameter can be unsorted; bind the canonical frozen property.
        put("selection", JsonArray(this@MixedApprovalMember.selection.map { JsonPrimitive(it) }))
    }.toString()))

    fun hasSameReferences(other: MixedApprovalMember): Boolean =
        kind == other.kind && childId == other.childId && childDigest == other.childDigest &&
            childCommandId == other.childCommandId && selection == other.selection &&
            expectedFingerprints == other.expectedFingerprints &&
            workspaceRootIdentity == other.workspaceRootIdentity

    /** A body-free copy of the prepared reference with no outcome attached. */
    fun asPending(): MixedApprovalMember = MixedApprovalMember(
        kind, childId, childDigest, childCommandId, selection, expectedFingerprints, workspaceRootIdentity,
    )

    fun withOutcome(
        state: MixedApprovalMemberState,
        receiptDigest: ObjectFingerprint? = null,
        rejection: MixedApprovalRejection? = null,
    ): MixedApprovalMember = MixedApprovalMember(
        kind, childId, childDigest, childCommandId, selection, expectedFingerprints, workspaceRootIdentity,
        state, receiptDigest, rejection,
    )

    fun applyReceipt(receipt: MixedApprovalMemberReceipt): MixedApprovalMember {
        require(kind == receipt.kind && childId == receipt.childId && childDigest == receipt.childDigest &&
            childCommandId == receipt.childCommandId && selectionDigest == receipt.selectionDigest) {
            "Mixed child receipt does not bind this member"
        }
        return withOutcome(receipt.state, receipt.receiptDigest, receipt.rejection)
    }

    override fun toString(): String = "MixedApprovalMember(kind=$kind, state=$state)"

    companion object {
        const val MAX_CHILD_ID = 256
        const val MAX_SELECTION = 1_024
        const val MAX_SELECTION_ENTRY = 512
        const val MAX_WORKSPACE_ROOT = 1_024
    }
}

/** A terminal child receipt supplied by the child store. It contains no child body. */
class MixedApprovalMemberReceipt(
    val kind: MixedApprovalMemberKind,
    val childId: String,
    val childDigest: ObjectFingerprint,
    val childCommandId: CommandId,
    val selectionDigest: ObjectFingerprint,
    val state: MixedApprovalMemberState,
    val receiptDigest: ObjectFingerprint? = null,
    val rejection: MixedApprovalRejection? = null,
) {
    init {
        requireBoundedText(childId, "mixed child identity", MixedApprovalMember.MAX_CHILD_ID)
        require(state in RECEIPT_STATES) { "A child receipt must be terminal" }
        when (state) {
            MixedApprovalMemberState.COMMITTED ->
                require(receiptDigest != null && rejection == null) {
                    "A committed receipt requires a receipt digest"
                }
            MixedApprovalMemberState.REJECTED ->
                require(rejection != null && rejection != MixedApprovalRejection.CANCELLED) {
                    "A rejected receipt requires a rejection reason"
                }
            MixedApprovalMemberState.CONFLICT ->
                require(rejection != null) { "A conflicted receipt requires a rejection reason" }
            MixedApprovalMemberState.CANCELLED ->
                require(receiptDigest == null &&
                    (rejection == null || rejection == MixedApprovalRejection.CANCELLED)) {
                    "A cancelled receipt has no receipt digest"
                }
            else -> Unit
        }
    }

    val identityKey: String get() = mixedMemberKey(kind, childId)

    override fun toString(): String = "MixedApprovalMemberReceipt(kind=$kind, state=$state)"

    private companion object {
        val RECEIPT_STATES = setOf(
            MixedApprovalMemberState.COMMITTED,
            MixedApprovalMemberState.REJECTED,
            MixedApprovalMemberState.CONFLICT,
            MixedApprovalMemberState.CANCELLED,
        )
    }
}

/**
 * Immutable prepared parent content. [digest] binds the identity, base revision, conflict policy
 * and the ordered child reference tuple. Editing any reference after preparation invalidates the
 * digest, so a stale approval cannot authorize a different batch.
 */
class MixedApprovalRequest(
    val id: ApprovalRequestId,
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val baseRevision: Long,
    val policy: ConflictPolicy,
    members: List<MixedApprovalMember>,
    val createdAtMillis: Long,
) {
    val members: List<MixedApprovalMember> = frozenList(members.sortedWith(MIXED_MEMBER_ORDER))

    init {
        requireBoundedText(id.value, "mixed request identity", MAX_IDENTITY_TEXT)
        requireBoundedText(commandId.value, "mixed command identity", MAX_IDENTITY_TEXT)
        requireBoundedText(ref.projectId.value, "mixed project identity", MAX_IDENTITY_TEXT)
        requireBoundedText(ref.instanceId.value, "mixed instance identity", MAX_IDENTITY_TEXT)
        requireBoundedText(chat.value, "mixed chat identity", MAX_IDENTITY_TEXT)
        requireBoundedText(run.value, "mixed run identity", MAX_IDENTITY_TEXT)
        require(baseRevision >= 0) { "Invalid mixed approval revision" }
        require(createdAtMillis >= 0) { "Invalid mixed approval time" }
        require(this.members.size in 1..MAX_MEMBERS) { "Invalid mixed approval member count" }
        this.members.forEach {
            require(it.state == MixedApprovalMemberState.PENDING && it.receiptDigest == null &&
                it.rejection == null) { "Prepared members must be pending" }
        }
        val keys = HashSet<String>(this.members.size * 2)
        this.members.forEach { require(keys.add(it.identityKey)) { "Duplicate mixed approval member" } }
        val commands = HashSet<String>(this.members.size + 1)
        commands.add(commandId.value)
        this.members.forEach {
            require(commands.add(it.childCommandId.value)) { "Duplicate mixed approval command" }
        }
        require(estimatedCanonicalBytes(this) <= MAX_CANONICAL_BYTES) { "Mixed approval is too large" }
    }

    val digest: MixedApprovalDigest = MixedApprovalDigest(MixedApprovalCanonical.sha256(this))

    fun sameContent(other: MixedApprovalRequest): Boolean =
        id == other.id && commandId == other.commandId && ref == other.ref && chat == other.chat &&
            run == other.run && baseRevision == other.baseRevision && policy == other.policy &&
            members.size == other.members.size &&
            members.zip(other.members).all { (left, right) -> left.hasSameReferences(right) }

    override fun toString(): String =
        "MixedApprovalRequest(id=${id.value}, members=${members.size}, digest=${digest.sha256})"

    companion object {
        const val MAX_MEMBERS = 64
        const val MAX_CANONICAL_BYTES = 8L * 1024 * 1024
    }
}

private const val MAX_IDENTITY_TEXT = 256

/** One-time binding of a consumed parent to its command and exact prepared content. */
class MixedApprovalBinding(
    val requestId: ApprovalRequestId,
    val digest: MixedApprovalDigest,
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val baseRevision: Long,
    val policy: ConflictPolicy,
) {
    init {
        requireBoundedText(requestId.value, "mixed binding request", MAX_IDENTITY_TEXT)
        requireBoundedText(commandId.value, "mixed binding command", MAX_IDENTITY_TEXT)
        requireBoundedText(ref.projectId.value, "mixed binding project", MAX_IDENTITY_TEXT)
        requireBoundedText(ref.instanceId.value, "mixed binding instance", MAX_IDENTITY_TEXT)
        requireBoundedText(chat.value, "mixed binding chat", MAX_IDENTITY_TEXT)
        requireBoundedText(run.value, "mixed binding run", MAX_IDENTITY_TEXT)
        require(baseRevision >= 0) { "Invalid binding revision" }
    }

    constructor(request: MixedApprovalRequest) : this(
        request.id, request.digest, request.commandId, request.ref, request.chat, request.run,
        request.baseRevision, request.policy,
    )

    val fingerprint: ObjectFingerprint = ObjectFingerprint(sha256Text(buildJsonObject {
        put("version", 1)
        put("format", "mixed-approval-binding")
        put("request", requestId.value)
        put("digest", digest.sha256)
        put("command", commandId.value)
        put("project", ref.projectId.value)
        put("instance", ref.instanceId.value)
        put("generation", ref.generation.value)
        put("chat", chat.value)
        put("run", run.value)
        put("base_revision", baseRevision)
        put("policy", policy.name)
    }.toString()))

    override fun toString(): String =
        "MixedApprovalBinding(request=${requestId.value}, fingerprint=${fingerprint.sha256})"
}

/**
 * Immutable parent snapshot. [members] carries the observed child outcomes; [request.members]
 * remains the frozen prepared references. A CONSUMED parent has a binding but no outcome. Only
 * a terminal state proves something about the children; CONSUMED itself is never success.
 */
class MixedApproval(
    val request: MixedApprovalRequest,
    members: List<MixedApprovalMember>,
    val state: MixedApprovalState,
    val binding: MixedApprovalBinding?,
    val claimedAtMillis: Long?,
    val settledAtMillis: Long?,
) {
    val members: List<MixedApprovalMember> = frozenList(members.sortedWith(MIXED_MEMBER_ORDER))

    init {
        require(this.members.size == request.members.size) { "Mixed member count changed" }
        this.members.forEachIndexed { index, member ->
            require(member.hasSameReferences(request.members[index])) { "Mixed member content changed" }
        }
        require((binding == null) == (claimedAtMillis == null)) { "Invalid mixed approval claim" }
        require(claimedAtMillis == null || claimedAtMillis >= request.createdAtMillis) {
            "Invalid mixed approval claim time"
        }
        require(settledAtMillis == null ||
            settledAtMillis >= (claimedAtMillis ?: request.createdAtMillis)) {
            "Invalid mixed approval settlement time"
        }
        require(state.terminal == (settledAtMillis != null)) { "Invalid mixed approval settlement" }
        when (state) {
            MixedApprovalState.PREPARED, MixedApprovalState.PENDING ->
                require(binding == null && claimedAtMillis == null) { "An unsettled parent has no binding" }
            MixedApprovalState.CONSUMED ->
                require(binding != null && claimedAtMillis != null) { "A consumed parent requires a binding" }
            MixedApprovalState.COMMITTED, MixedApprovalState.PARTIAL, MixedApprovalState.CONFLICT ->
                require(binding != null && claimedAtMillis != null) {
                    "A written mixed outcome requires a binding"
                }
            MixedApprovalState.REJECTED, MixedApprovalState.CANCELLED -> Unit
        }
        binding?.let {
            require(it.requestId == request.id && it.digest == request.digest &&
                it.commandId == request.commandId && it.ref == request.ref && it.chat == request.chat &&
                it.run == request.run && it.baseRevision == request.baseRevision &&
                it.policy == request.policy) { "Mixed approval binding changed" }
        }
        validateMemberStates(state, this.members)
    }

    val id: ApprovalRequestId get() = request.id
    val commandId: CommandId get() = request.commandId
    val ref: LocalProjectRef get() = request.ref
    val chat: ChatId get() = request.chat
    val run: RunId get() = request.run
    val baseRevision: Long get() = request.baseRevision
    val policy: ConflictPolicy get() = request.policy
    val digest: MixedApprovalDigest get() = request.digest
    val createdAtMillis: Long get() = request.createdAtMillis

    override fun toString(): String =
        "MixedApproval(id=${id.value}, state=$state, members=${members.size}, digest=${digest.sha256})"

    private companion object {
        fun validateMemberStates(state: MixedApprovalState, members: List<MixedApprovalMember>) {
            when (state) {
                MixedApprovalState.PREPARED, MixedApprovalState.PENDING,
                MixedApprovalState.CONSUMED ->
                    require(members.all { it.state == MixedApprovalMemberState.PENDING }) {
                        "An unsettled parent has pending members"
                    }
                MixedApprovalState.COMMITTED ->
                    require(members.all {
                        it.state == MixedApprovalMemberState.COMMITTED && it.receiptDigest != null
                    }) { "A committed parent requires committed members" }
                MixedApprovalState.PARTIAL -> {
                    require(members.none { it.state == MixedApprovalMemberState.PENDING }) {
                        "A partial parent has no pending member"
                    }
                    require(members.any { it.state == MixedApprovalMemberState.COMMITTED } ||
                        (members.any { it.state == MixedApprovalMemberState.INDETERMINATE } &&
                            members.any {
                                it.state == MixedApprovalMemberState.REJECTED ||
                                    it.state == MixedApprovalMemberState.CANCELLED ||
                                    it.state == MixedApprovalMemberState.CONFLICT
                            })) { "A partial parent requires a known or indeterminate outcome" }
                    require(members.any { it.state != MixedApprovalMemberState.COMMITTED }) {
                        "A partial parent has a non-committed member"
                    }
                }
                MixedApprovalState.REJECTED -> {
                    require(members.none {
                        it.state == MixedApprovalMemberState.COMMITTED ||
                            it.state == MixedApprovalMemberState.CONFLICT ||
                            it.state == MixedApprovalMemberState.INDETERMINATE ||
                            it.state == MixedApprovalMemberState.PENDING
                    }) { "A rejected parent has no committed member" }
                    require(members.any { it.state == MixedApprovalMemberState.REJECTED }) {
                        "A rejected parent requires a rejected member"
                    }
                }
                MixedApprovalState.CONFLICT -> {
                    require(members.none { it.state == MixedApprovalMemberState.COMMITTED }) {
                        "A conflicted parent has no committed member"
                    }
                    require(members.none { it.state == MixedApprovalMemberState.PENDING }) {
                        "A conflicted parent has no pending member"
                    }
                    require(members.any {
                        it.state == MixedApprovalMemberState.CONFLICT ||
                            it.state == MixedApprovalMemberState.INDETERMINATE
                    }) { "A conflicted parent requires a conflict or indeterminate member" }
                }
                MixedApprovalState.CANCELLED ->
                    require(members.all { it.state == MixedApprovalMemberState.CANCELLED }) {
                        "A cancelled parent has cancelled members"
                    }
            }
        }
    }
}

/**
 * Pure restart classification for a persisted parent and the child receipts the caller could
 * observe. It never mutates storage: a CONSUMED parent with no receipt is INDETERMINATE, not a
 * success; a partial receipt set is PARTIAL; a mismatched child digest or selection is refused.
 */
sealed interface MixedApprovalRecovery {
    data class Rejectable(val approval: MixedApproval) : MixedApprovalRecovery
    data class Indeterminate(val approval: MixedApproval) : MixedApprovalRecovery
    data class Partial(val approval: MixedApproval, val observed: Int, val total: Int) : MixedApprovalRecovery {
        init { require(observed in 0..total) }
    }
    data class Committed(val approval: MixedApproval) : MixedApprovalRecovery
    data class Rejected(val approval: MixedApproval) : MixedApprovalRecovery
    data class Cancelled(val approval: MixedApproval) : MixedApprovalRecovery
    data class Conflicted(val approval: MixedApproval) : MixedApprovalRecovery
    data class AlreadySettled(val approval: MixedApproval) : MixedApprovalRecovery
    data class Mismatch(val reason: Reason) : MixedApprovalRecovery {
        enum class Reason {
            UNKNOWN_MEMBER,
            DUPLICATE_RECEIPT,
            CHILD_DIGEST,
            CHILD_COMMAND,
            SELECTION_DIGEST,
            RECEIPT_BEFORE_CONSUME,
        }
    }

    companion object {
        fun classify(approval: MixedApproval, receipts: List<MixedApprovalMemberReceipt>): MixedApprovalRecovery {
            val byKey = LinkedHashMap<String, MixedApprovalMemberReceipt>(receipts.size)
            val members = approval.members.associateBy { it.identityKey }
            receipts.forEach { receipt ->
                val key = mixedMemberKey(receipt.kind, receipt.childId)
                val member = members[key] ?: return Mismatch(Mismatch.Reason.UNKNOWN_MEMBER)
                if (byKey.put(key, receipt) != null) return Mismatch(Mismatch.Reason.DUPLICATE_RECEIPT)
                if (receipt.childDigest != member.childDigest) return Mismatch(Mismatch.Reason.CHILD_DIGEST)
                if (receipt.childCommandId != member.childCommandId) {
                    return Mismatch(Mismatch.Reason.CHILD_COMMAND)
                }
                if (receipt.selectionDigest != member.selectionDigest) {
                    return Mismatch(Mismatch.Reason.SELECTION_DIGEST)
                }
            }
            if (approval.state.terminal) return AlreadySettled(approval)
            if (approval.state == MixedApprovalState.PREPARED || approval.state == MixedApprovalState.PENDING) {
                return if (receipts.isEmpty()) Rejectable(approval)
                else Mismatch(Mismatch.Reason.RECEIPT_BEFORE_CONSUME)
            }
            if (receipts.isEmpty()) return Indeterminate(approval)
            if (byKey.size < approval.members.size) {
                return Partial(approval, byKey.size, approval.members.size)
            }
            val committed = receipts.count { it.state == MixedApprovalMemberState.COMMITTED }
            return when {
                committed == approval.members.size -> Committed(approval)
                committed > 0 -> Partial(approval, byKey.size, approval.members.size)
                receipts.all { it.state == MixedApprovalMemberState.CANCELLED } -> Cancelled(approval)
                receipts.all {
                    it.state == MixedApprovalMemberState.REJECTED ||
                        it.state == MixedApprovalMemberState.CANCELLED
                } -> Rejected(approval)
                else -> Conflicted(approval)
            }
        }
    }
}

private val MIXED_MEMBER_ORDER = compareBy<MixedApprovalMember>({ it.kind.ordinal }, { it.childId })

private fun mixedMemberKey(kind: MixedApprovalMemberKind, childId: String): String =
    kind.name + '\u0000' + childId

private fun estimatedCanonicalBytes(request: MixedApprovalRequest): Long {
    var bytes = 384L + request.id.value.length * 4L + request.commandId.value.length * 4L +
        request.ref.projectId.value.length * 4L + request.ref.instanceId.value.length * 4L +
        request.chat.value.length * 4L + request.run.value.length * 4L
    request.members.forEach { member ->
        bytes += 192L + member.childId.length * 4L +
            (member.workspaceRootIdentity?.length ?: 0) * 4L
        bytes += member.expectedFingerprints.size * 66L
    }
    return bytes
}

/** Deterministic, versioned parent encoding shared by persistence, approval and recovery. */
private object MixedApprovalCanonical {
    private const val VERSION = 1

    fun sha256(request: MixedApprovalRequest): String = sha256Text(buildJsonObject {
        put("version", VERSION)
        put("format", "mixed-approval")
        put("id", request.id.value)
        put("command", request.commandId.value)
        put("instance", request.ref.instanceId.value)
        put("generation", request.ref.generation.value)
        put("project", request.ref.projectId.value)
        put("chat", request.chat.value)
        put("run", request.run.value)
        put("base_revision", request.baseRevision)
        put("policy", request.policy.name)
        put("members", JsonArray(request.members.map(::member)))
    }.toString())

    private fun member(value: MixedApprovalMember) = buildJsonObject {
        put("kind", value.kind.name)
        put("child", value.childId)
        put("child_digest", value.childDigest.sha256)
        put("child_command", value.childCommandId.value)
        put("selection_digest", value.selectionDigest.sha256)
        put("expected", JsonArray(value.expectedFingerprints.map { JsonPrimitive(it.sha256) }))
        put("workspace_root", value.workspaceRootIdentity?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun sha256Text(value: String): String =
    TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(value))

private fun requireSha256(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
}

private fun requireBoundedText(value: String, field: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength) { "Invalid $field" }
    var index = 0
    while (index < value.length) {
        val character = value[index]
        require(!character.isISOControl()) { "Invalid $field" }
        if (character.isHighSurrogate()) {
            require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "Invalid $field" }
            index += 2
        } else {
            require(!character.isLowSurrogate()) { "Invalid $field" }
            index += 1
        }
    }
}
