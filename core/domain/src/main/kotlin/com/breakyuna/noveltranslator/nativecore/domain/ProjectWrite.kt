package com.breakyuna.noveltranslator.nativecore.domain

/** How a batch treats objects whose base fingerprint no longer matches. Frozen at approval. */
enum class ConflictPolicy { REJECT_BATCH, ACCEPT_INDEPENDENT_OBJECTS }

/**
 * Immutable body of an approved batch. The body itself lives in a versioned payload store;
 * this reference is what approval, the write store and recovery receipts agree on. It never
 * inlines material, so a log or receipt cannot leak novel text.
 */
data class ChangePayloadRef(
    val changeSetId: ChangeSetId,
    val digest: ChangeSetDigest,
    val payloadVersion: Int,
    val storageRef: String,
) {
    init {
        require(payloadVersion > 0) { "An unversioned payload cannot be replayed" }
        require(storageRef.isNotBlank() && storageRef.none { it.isISOControl() })
    }
}

/**
 * Single authorization for one submission intent, not for one process-local call.
 * Recovery compares every field; a different command, digest, instance or generation must be
 * refused instead of reusing an old approval.
 */
data class ApprovalCommandBinding(
    val commandId: CommandId,
    val requestId: ApprovalRequestId,
    val instanceId: ProjectInstanceId,
    val generation: RecoveryGeneration,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    val changeSetId: ChangeSetId,
    val changeSetDigest: ChangeSetDigest,
    val conflictPolicy: ConflictPolicy,
)

data class RejectedTarget(val target: ChangeTarget, val reason: RejectReason)

enum class RejectReason {
    STALE_FINGERPRINT,
    DUPLICATE_TARGET,
    DEPENDENCY_FAILED,
    VALIDATION,
    UNKNOWN_TARGET,
    POLICY_REJECTED,
}

/** Durable result of one submission; repeated queries return the same object set. */
class WriteReceipt(
    val commandId: CommandId,
    val changeSetId: ChangeSetId,
    val changeSetDigest: ChangeSetDigest,
    val revision: Long,
    val committedAtMillis: Long,
    accepted: List<ChangeTarget>,
    rejected: List<RejectedTarget>,
) {
    val accepted: List<ChangeTarget> = frozenList(accepted)
    val rejected: List<RejectedTarget> = frozenList(rejected)

    init {
        require(revision >= 0 && committedAtMillis >= 0)
        val acceptedTargets = this.accepted.toSet()
        require(this.rejected.none { it.target in acceptedTargets }) {
            "A target cannot be both accepted and rejected"
        }
        require(this.accepted.isNotEmpty() || this.rejected.isNotEmpty()) { "Empty receipt" }
    }
}

sealed interface WriteOutcome {
    /** Every object committed in one project transaction. */
    class Committed(val receipt: WriteReceipt) : WriteOutcome

    /** Whole batch refused; nothing was written. */
    class Rejected(val reason: RejectReason, conflicts: List<ChangeTarget>) : WriteOutcome {
        val conflicts: List<ChangeTarget> = frozenList(conflicts)
    }

    /**
     * The result cannot be proven (store unreachable, receipt unreadable, identity mismatch).
     * Callers must not retry blindly; see [WriteRecovery].
     */
    class Indeterminate(val reason: IndeterminateReason) : WriteOutcome
}

enum class IndeterminateReason { STORE_UNAVAILABLE, RECEIPT_UNREADABLE, IDENTITY_MISMATCH, EXECUTOR_STILL_RUNNING }

/**
 * Approval-bound submission contract for items, pages, rules, prompts and statistics.
 * Agent `workspace_apply` requires this binding; it cannot use TextProjectPort's explicit
 * manual intent. The first manual TXT adapter uses storage's ProjectWriteStore transaction
 * owner. Future approved/batch adapters must extend that same owner, not add a second writer.
 * This approval-bound port has no production adapter yet; implementations must make the project
 * transaction and its receipt atomic and must return the stored receipt for a repeated
 * command instead of applying the batch twice.
 */
interface ProjectWritePort {
    suspend fun submit(
        binding: ApprovalCommandBinding,
        payload: ChangePayloadRef,
        changes: List<PreparedChange>,
    ): WriteOutcome

    suspend fun receipt(commandId: CommandId): WriteReceipt?
}

/** Durable facts recovery may read; the store owns their truth, this is the classification input. */
data class WriteRecoveryFacts(
    val approvalApproved: Boolean,
    val approvalConsumed: Boolean,
    val commandBound: Boolean,
    val projectReceiptPresent: Boolean,
    val digestConsistent: Boolean,
    val sameInstanceAndGeneration: Boolean,
    val executorFinished: Boolean,
    val runActive: Boolean,
)

enum class RecoveryAction {
    /** Approval exists but no command binding yet: bind and execute atomically. */
    BIND_AND_EXECUTE,
    /** Consumed and bound, project has no receipt: re-enter with the same command after a fresh lease. */
    RESUME_SAME_COMMAND,
    /** Project already committed: return the stored result, never re-apply. */
    RETURN_STORED_RECEIPT,
    /** No write may happen; record the uncommitted terminal state. */
    TERMINATE_UNCOMMITTED,
    /** Facts disagree or an executor may still run: keep indeterminate and ask the user. */
    REQUIRE_USER_RECONCILIATION,
    /** Instance/generation changed (restore, copy, re-import): approvals cannot be reused. */
    REAUTHORIZE,
}

/**
 * Classification for the failure windows in APPROVAL_CONTRACT §5.3. Pure decision table:
 * the caller still has to perform the action under the runtime lease, and the store still
 * owns the atomic transitions.
 */
object WriteRecovery {
    fun classify(facts: WriteRecoveryFacts): RecoveryAction = when {
        !facts.digestConsistent -> RecoveryAction.REQUIRE_USER_RECONCILIATION
        !facts.sameInstanceAndGeneration -> RecoveryAction.REAUTHORIZE
        facts.projectReceiptPresent -> RecoveryAction.RETURN_STORED_RECEIPT
        !facts.approvalApproved -> RecoveryAction.TERMINATE_UNCOMMITTED
        !facts.approvalConsumed && !facts.commandBound ->
            if (facts.runActive) RecoveryAction.BIND_AND_EXECUTE else RecoveryAction.TERMINATE_UNCOMMITTED
        !facts.approvalConsumed || !facts.commandBound -> RecoveryAction.REQUIRE_USER_RECONCILIATION
        !facts.executorFinished -> RecoveryAction.REQUIRE_USER_RECONCILIATION
        facts.runActive -> RecoveryAction.RESUME_SAME_COMMAND
        else -> RecoveryAction.TERMINATE_UNCOMMITTED
    }
}
