package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.util.concurrent.CancellationException

/** SHA-256 of a workspace file value or of the canonical change-set encoding. */
@JvmInline
value class WorkspaceDigest(val sha256: String) {
    init { requireSha256Hex(sha256) }
    override fun toString(): String = "WorkspaceDigest($sha256)"
}

@JvmInline
value class WorkspaceChangeSetId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 256) { "Invalid change set identity" }
        require(value.none { it.isISOControl() }) { "Invalid change set identity" }
    }
}

enum class WorkspaceChangeOperation { CREATE, UPDATE, DELETE }

/**
 * Planned file body. [Inline] stores bytes in memory; [FromMaterial] names an already
 * fingerprinted material so a large input is not duplicated into the change payload.
 */
sealed interface WorkspaceChangeContent {
    val sizeBytes: Long
    val sha256: WorkspaceDigest

    class Inline(bytes: ByteArray) : WorkspaceChangeContent {
        private val storage: ByteArray = bytes.copyOf()

        init {
            require(storage.size <= WorkspaceHardLimits.MAX_CHANGE_BYTES) { "Inline change too large" }
        }

        override val sizeBytes: Long get() = storage.size.toLong()
        override val sha256: WorkspaceDigest = WorkspaceDigest(sha256Hex(storage))

        /** Defensive copy; mutating it never changes the approved digest. */
        val bytes: ByteArray get() = storage.copyOf()

        override fun toString(): String = "Inline(size=$sizeBytes)"
    }

    /**
     * Names an already budgeted material instead of duplicating a large body. The apply
     * adapter must read it through [WorkspaceMaterialReadPort] and verify [sha256] and
     * [sizeBytes] before writing.
     */
    class FromMaterial(
        val materialId: WorkspaceMaterialId,
        override val sha256: WorkspaceDigest,
        override val sizeBytes: Long,
    ) : WorkspaceChangeContent {
        init {
            require(sizeBytes in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid material size" }
        }

        override fun toString(): String = "FromMaterial(id=${materialId.value}, size=$sizeBytes)"
    }
}

/**
 * One planned file mutation. CREATE must not claim a base value; UPDATE/DELETE must carry
 * the exact committed fingerprint so a stale script cannot overwrite newer work.
 */
class WorkspaceChange(
    val path: WorkspacePath,
    val operation: WorkspaceChangeOperation,
    val expectedFingerprint: WorkspaceDigest?,
    val content: WorkspaceChangeContent?,
) {
    init {
        when (operation) {
            WorkspaceChangeOperation.CREATE -> {
                require(expectedFingerprint == null) { "A created file has no base fingerprint" }
                require(content != null) { "A created file requires content" }
            }
            WorkspaceChangeOperation.UPDATE -> {
                require(expectedFingerprint != null) { "An updated file requires a base fingerprint" }
                require(content != null) { "An updated file requires content" }
            }
            WorkspaceChangeOperation.DELETE -> {
                require(expectedFingerprint != null) { "A deleted file requires a base fingerprint" }
                require(content == null) { "A deleted file has no content" }
            }
        }
    }

    val sizeBytes: Long get() = content?.sizeBytes ?: 0L

    override fun toString(): String =
        "WorkspaceChange(path=${path.value}, operation=$operation, size=$sizeBytes)"
}

data class WorkspaceChangeSummary(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val totalBytes: Long,
) {
    init {
        require(created >= 0 && updated >= 0 && deleted >= 0)
        require(totalBytes >= 0)
    }

    val total: Int get() = created + updated + deleted

    companion object {
        fun of(changes: List<WorkspaceChange>): WorkspaceChangeSummary {
            var created = 0
            var updated = 0
            var deleted = 0
            var bytes = 0L
            changes.forEach { change ->
                when (change.operation) {
                    WorkspaceChangeOperation.CREATE -> created += 1
                    WorkspaceChangeOperation.UPDATE -> updated += 1
                    WorkspaceChangeOperation.DELETE -> deleted += 1
                }
                bytes += change.sizeBytes
            }
            return WorkspaceChangeSummary(created, updated, deleted, bytes)
        }
    }
}

class InvalidWorkspaceChangeSet(val reason: Reason) : IllegalArgumentException("Invalid workspace change set: $reason") {
    enum class Reason { EMPTY, DUPLICATE_PATH, PATH_POLICY, TOO_MANY_CHANGES }
}

/**
 * Immutable, approval-bound batch of workspace file mutations. It is intentionally distinct
 * from the domain [com.breakyuna.noveltranslator.nativecore.domain.PreparedChangeSet], which
 * describes project objects; a production [WorkspaceApplyPort] maps approved workspace
 * batches onto ProjectWriteStore. [digest] is computed from the exact batch and re-checked
 * after approval, so editing the object graph afterwards invalidates the approval.
 */
class WorkspaceChangeSet(
    val id: WorkspaceChangeSetId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    changes: List<WorkspaceChange>,
    val policy: PathPolicy = PathPolicy.DEFAULT,
) {
    val changes: List<WorkspaceChange> = workspaceFrozenList(changes)

    init {
        if (this.changes.isEmpty()) throw InvalidWorkspaceChangeSet(InvalidWorkspaceChangeSet.Reason.EMPTY)
        if (this.changes.size > WorkspaceHardLimits.MAX_CHANGES) {
            throw InvalidWorkspaceChangeSet(InvalidWorkspaceChangeSet.Reason.TOO_MANY_CHANGES)
        }
        val keys = HashSet<String>(this.changes.size * 2)
        this.changes.forEach { change ->
            try {
                policy.parse(change.path.value)
            } catch (failure: InvalidWorkspacePath) {
                throw InvalidWorkspaceChangeSet(InvalidWorkspaceChangeSet.Reason.PATH_POLICY)
            }
            if (!keys.add(policy.identityKey(change.path))) {
                throw InvalidWorkspaceChangeSet(InvalidWorkspaceChangeSet.Reason.DUPLICATE_PATH)
            }
        }
    }

    val digest: WorkspaceDigest = digestOf(this)
    val summary: WorkspaceChangeSummary = WorkspaceChangeSummary.of(this.changes)

    override fun toString(): String =
        "WorkspaceChangeSet(id=${id.value}, digest=${digest.sha256}, changes=${changes.size})"

    private companion object {
        fun digestOf(changeSet: WorkspaceChangeSet): WorkspaceDigest {
            val builder = StringBuilder(256 + changeSet.changes.size * 96)
            builder.append("lnt-workspace-change-set-v1\n")
            builder.append(changeSet.id.value).append('\n')
            builder.append(changeSet.projectId.value).append('\n')
            builder.append(changeSet.chatId.value).append('\n')
            builder.append(changeSet.runId.value).append('\n')
            builder.append(changeSet.policy.casePolicy.name).append('\n')
            changeSet.changes.forEach { change ->
                builder.append(change.path.value).append('\u0000')
                builder.append(change.operation.name).append('\u0000')
                builder.append(change.expectedFingerprint?.sha256 ?: "-").append('\u0000')
                builder.append(change.content?.sha256?.sha256 ?: "-").append('\u0000')
                val source = when (val content = change.content) {
                    null -> "-"
                    is WorkspaceChangeContent.Inline -> "inline"
                    is WorkspaceChangeContent.FromMaterial -> "material:" + content.materialId.value
                }
                builder.append(source).append('\u0000')
                builder.append(change.content?.sizeBytes ?: 0L).append('\n')
            }
            val bytes = builder.toString().toByteArray(Charsets.UTF_8)
            return WorkspaceDigest(sha256Hex(bytes))
        }
    }
}

enum class WorkspaceConflictReason {
    DUPLICATE_PATH,
    PATH_POLICY,
    ALREADY_EXISTS,
    STALE_FINGERPRINT,
    MISSING_CONTENT,
    CHANGE_TOO_LARGE,
    UNKNOWN_MATERIAL,
    MATERIAL_MISMATCH,
    BUDGET,
}

class WorkspaceConflict(
    val path: WorkspacePath,
    val reason: WorkspaceConflictReason,
    val detail: String? = null,
) {
    init {
        if (detail != null) {
            require(detail.isNotBlank() && detail.length <= 200) { "Invalid conflict detail" }
            require(detail.none { it.isISOControl() }) { "Control character in conflict detail" }
        }
    }

    override fun toString(): String = "WorkspaceConflict(path=${path.value}, reason=$reason)"
}

/** Result of inspecting planned changes before approval; bodies are never included. */
class WorkspacePreflightReport(accepted: List<WorkspaceChange>, conflicts: List<WorkspaceConflict>) {
    val accepted: List<WorkspaceChange> = workspaceFrozenList(accepted)
    val conflicts: List<WorkspaceConflict> = workspaceFrozenList(conflicts)

    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/** Current committed fingerprint of one workspace path, or null when it does not exist. */
interface WorkspaceSnapshotPort {
    suspend fun fingerprint(path: WorkspacePath): WorkspaceDigest?
}

/**
 * Pure preflight for script output. It rejects duplicate/case-colliding paths, policy
 * violations, unknown material sources, over-budget batches and stale fingerprints before a
 * change set can be shown for approval. It performs no writes.
 */
object WorkspaceChangePreflight {
    suspend fun inspect(
        changes: List<WorkspaceChange>,
        materials: Map<WorkspaceMaterialId, WorkspaceMaterialSummary>,
        snapshots: WorkspaceSnapshotPort,
        budget: WorkspaceBudget,
        policy: PathPolicy = PathPolicy.DEFAULT,
        cancellation: WorkspaceCancellation? = null,
    ): WorkspacePreflightReport {
        val accepted = ArrayList<WorkspaceChange>(changes.size)
        val conflicts = ArrayList<WorkspaceConflict>()
        val identities = HashSet<String>(changes.size * 2)
        var changeCount = 0
        var totalBytes = 0L

        changes.forEach { change ->
            cancellation?.throwIfCancelled()
            var rejected = false
            val identity = policy.identityKey(change.path)
            if (!identities.add(identity)) {
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.DUPLICATE_PATH, "path")
                rejected = true
            }
            try {
                policy.parse(change.path.value)
            } catch (failure: InvalidWorkspacePath) {
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.PATH_POLICY, failure.reason.name)
                rejected = true
            }

            if (changeCount + 1 > budget.maxChanges) {
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.BUDGET, "changes")
                rejected = true
            } else {
                changeCount += 1
            }

            val size = change.sizeBytes
            if (size > budget.maxChangeBytes) {
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.CHANGE_TOO_LARGE, "bytes")
                rejected = true
            } else if (size > budget.maxTotalChangeBytes - totalBytes) {
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.BUDGET, "total_bytes")
                rejected = true
            } else {
                totalBytes += size
            }

            when (val content = change.content) {
                null -> if (change.operation != WorkspaceChangeOperation.DELETE) {
                    conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.MISSING_CONTENT, "content")
                    rejected = true
                }
                is WorkspaceChangeContent.FromMaterial -> {
                    val summary = materials[content.materialId]
                    if (summary == null) {
                        conflicts += WorkspaceConflict(
                            change.path, WorkspaceConflictReason.UNKNOWN_MATERIAL, content.materialId.value,
                        )
                        rejected = true
                    } else if (summary.sha256 != content.sha256.sha256 || summary.sizeBytes != content.sizeBytes) {
                        conflicts += WorkspaceConflict(
                            change.path, WorkspaceConflictReason.MATERIAL_MISMATCH, content.materialId.value,
                        )
                        rejected = true
                    }
                }
                is WorkspaceChangeContent.Inline -> Unit
            }

            val current = try {
                snapshots.fingerprint(change.path)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw WorkspacePreflightFailure(change.path)
            }
            when (change.operation) {
                WorkspaceChangeOperation.CREATE -> if (current != null) {
                    conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.ALREADY_EXISTS, "create")
                    rejected = true
                }
                WorkspaceChangeOperation.UPDATE, WorkspaceChangeOperation.DELETE ->
                    if (current == null || current != change.expectedFingerprint) {
                        conflicts += WorkspaceConflict(
                            change.path, WorkspaceConflictReason.STALE_FINGERPRINT, change.operation.name,
                        )
                        rejected = true
                    }
            }
            if (!rejected) accepted += change
        }
        return WorkspacePreflightReport(accepted, conflicts)
    }
}

/** The snapshot port failed; the run cannot prove whether a change is safe. */
class WorkspacePreflightFailure(val path: WorkspacePath) : IllegalStateException("Workspace snapshot unavailable")

/**
 * Evidence that one approval was consumed for this exact workspace batch. Only
 * [WorkspaceRuntime] can construct it (internal constructor), so an adapter cannot accept a
 * forged approval from a script.
 */
class ApprovedWorkspaceChangeSet internal constructor(
    val commandId: CommandId,
    val changeSet: WorkspaceChangeSet,
    val approvalRequestId: ApprovalRequestId,
    val approvedAtMillis: Long,
    /** Project revision observed when the batch was prepared; 0 for a scope without one. */
    val baseRevision: Long = 0,
    /**
     * True when a durable approval claim already exists. An adapter must reconcile its write
     * intent instead of starting a fresh write with the same command.
     */
    val resumed: Boolean = false,
) {
    init {
        require(approvedAtMillis >= 0) { "Invalid approval time" }
        require(baseRevision >= 0) { "Invalid approval revision" }
    }

    override fun toString(): String =
        "ApprovedWorkspaceChangeSet(requestId=${approvalRequestId.value}, digest=${changeSet.digest.sha256})"
}

enum class WorkspaceRejectionReason { USER_REJECTED, EXPIRED, CANCELLED, POLICY }
enum class WorkspaceApprovalUnavailableReason { NO_UI, PERSISTENCE, BUSY, TIMEOUT }

sealed interface WorkspaceApprovalDecision {
    class Approved(
        val requestId: ApprovalRequestId,
        val digest: WorkspaceDigest,
        val approvedAtMillis: Long,
    ) : WorkspaceApprovalDecision {
        init { require(approvedAtMillis >= 0) { "Invalid approval time" } }
    }

    class Rejected(
        val requestId: ApprovalRequestId?,
        val reason: WorkspaceRejectionReason,
    ) : WorkspaceApprovalDecision

    class Unavailable(val reason: WorkspaceApprovalUnavailableReason) : WorkspaceApprovalDecision
}

enum class WorkspaceConflictPolicy {
    /** Any conflict rejects the complete batch. */
    REJECT_BATCH,

    /** An adapter may commit independent uncontested objects and report the rest. */
    ACCEPT_UNCONFLICTED,
}

class WorkspaceApprovalRequest(
    val commandId: CommandId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    val changeSet: WorkspaceChangeSet?,
    val digest: WorkspaceDigest,
    val changeSummary: WorkspaceChangeSummary,
    artifacts: List<WorkspacePath>,
    val deadlineAtMillis: Long,
) {
    val artifacts: List<WorkspacePath> = workspaceFrozenList(artifacts)

    init {
        require(deadlineAtMillis > 0) { "Invalid approval deadline" }
        require((changeSet == null) == (changeSummary.total == 0)) {
            "Artifact-only approvals have no change summary"
        }
        require(changeSet == null || changeSet.digest == digest) {
            "Approval digest must bind the change set"
        }
        require(changeSet != null || this.artifacts.isNotEmpty()) { "Empty approval request" }
    }

    val artifactCount: Int get() = artifacts.size
}

/**
 * Approval authority for workspace batches. The production adapter must persist an immutable
 * prepared batch, bind it to Project/Chat/Run/command and return [WorkspaceApprovalDecision].
 * This core does not write approval records.
 */
interface WorkspaceApprovalPort {
    suspend fun request(
        request: WorkspaceApprovalRequest,
        cancellation: WorkspaceCancellation,
    ): WorkspaceApprovalDecision
}

class WorkspaceCommittedChange(
    val path: WorkspacePath,
    val operation: WorkspaceChangeOperation,
    val fingerprint: WorkspaceDigest,
)

enum class WorkspaceApplyStatus { COMMITTED, PARTIAL, REJECTED }

/**
 * Durable result of one submission. A receipt never claims success for a path it did not
 * commit; a repeated command must return the same receipt through the adapter.
 */
class WorkspaceApplyReceipt(
    val commandId: CommandId,
    val changeSetId: WorkspaceChangeSetId,
    val digest: WorkspaceDigest,
    val status: WorkspaceApplyStatus,
    committed: List<WorkspaceCommittedChange>,
    conflicts: List<WorkspaceConflict>,
    val revision: Long,
    val committedAtMillis: Long,
) {
    val committed: List<WorkspaceCommittedChange> = workspaceFrozenList(committed)
    val conflicts: List<WorkspaceConflict> = workspaceFrozenList(conflicts)

    init {
        require(revision >= 0 && committedAtMillis >= 0) { "Invalid receipt time" }
        when (status) {
            WorkspaceApplyStatus.COMMITTED -> {
                require(this.committed.isNotEmpty() && this.conflicts.isEmpty()) { "Invalid committed receipt" }
            }
            WorkspaceApplyStatus.PARTIAL -> {
                require(this.committed.isNotEmpty() && this.conflicts.isNotEmpty()) { "Invalid partial receipt" }
            }
            WorkspaceApplyStatus.REJECTED -> {
                require(this.committed.isEmpty() && this.conflicts.isNotEmpty()) { "Invalid rejected receipt" }
            }
        }
        require(this.committed.map { it.path }.toSet().size == this.committed.size) {
            "Duplicate committed path"
        }
        require(this.conflicts.map { it.path }.toSet().size == this.conflicts.size) {
            "Duplicate conflict path"
        }
        val committedPaths = this.committed.map { it.path }.toSet()
        require(this.conflicts.none { it.path in committedPaths }) { "Path both committed and rejected" }
    }

    override fun toString(): String =
        "WorkspaceApplyReceipt(command=${commandId.value}, status=$status, committed=${committed.size})"
}

/**
 * Unique project write entry for approved workspace batches. Production wiring must route
 * this to ProjectWriteStore plus approval consumption; this module never opens a database.
 * Implementations must also enforce the workspace root and must never overwrite immutable
 * source materials, even when a path survived preflight.
 */
interface WorkspaceApplyPort {
    suspend fun apply(
        approved: ApprovedWorkspaceChangeSet,
        conflictPolicy: WorkspaceConflictPolicy,
    ): WorkspaceApplyReceipt
}
