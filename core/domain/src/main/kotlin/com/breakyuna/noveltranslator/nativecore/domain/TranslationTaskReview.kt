package com.breakyuna.noveltranslator.nativecore.domain

/** Immutable task-level review. Uncertain requests survive termination for later audit. */
data class TranslationTaskReview(
    val ref: LocalProjectRef,
    val run: RunIdentity,
    val stage: String,
    val revision: Long,
    val committed: Int,
    val remaining: Int,
    val requests: Int,
    val unresolvedRequests: Int,
    val fingerprint: ObjectFingerprint,
) {
    init {
        require(run.projectId == ref.projectId && run.chatId == null)
        require(revision >= 0 && committed >= 0 && remaining >= 0 && requests >= 0 && unresolvedRequests >= 0)
    }
}

/** A terminal decision never changes source items, paid receipts, or committed translations. */
interface TranslationTaskReviewPort {
    suspend fun review(ref: LocalProjectRef, run: RunIdentity): TranslationTaskReview
    suspend fun terminate(review: TranslationTaskReview, command: CommandId)
}

/** Bounded current-item evidence, without source or translation bodies. */
data class TranslationTaskMemberReview(val id: ItemId, val fingerprint: ObjectFingerprint, val status: TextItemStatus, val decision: String? = null)
class TranslationTaskMemberPage(val revision: Long, members: List<TranslationTaskMemberReview>, val next: Long?) {
    val members = frozenList(members)
}

interface TranslationTaskRetryPort {
    suspend fun keepErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId)

    suspend fun members(ref: LocalProjectRef, run: RunIdentity, after: Long = 0): TranslationTaskMemberPage
    /** Creates a new fixed task and budget; does not dispatch. The old task remains auditable. */
    suspend fun retryErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId): TranslationTaskSpec
}

/**
 * Durable completion evidence for one fixed task. Counts come from the persisted target rows and
 * proven commit receipts, never from the last in-memory progress callback. [skippedItems] counts
 * the queue's explicit skip decisions that still exist as unfinished target rows.
 */
data class TranslationTaskOutcomeEvidence(
    val stage: String,
    val targetItems: Int,
    val committedItems: Int,
    val skippedItems: Int,
    val receiptedItems: Int,
    val unresolvedDispatches: Int,
    val usage: UsageSource,
) {
    init {
        require(targetItems >= 0 && committedItems >= 0 && skippedItems >= 0 && receiptedItems >= 0 &&
            unresolvedDispatches >= 0)
        require(committedItems <= targetItems && skippedItems <= targetItems &&
            receiptedItems <= targetItems)
        require(committedItems.toLong() + skippedItems <= targetItems.toLong())
        require(receiptedItems <= committedItems)
    }
}

/** Narrow evidence port used by the durable queue scheduler; it never starts work. */
fun interface TranslationTaskOutcomePort {
    suspend fun outcome(ref: LocalProjectRef, run: RunIdentity,
        skippedItemIds: Set<ItemId>): TranslationTaskOutcomeEvidence
}

/**
 * One bounded page of durable item-failure facts for exactly one fixed task. [review] binds the
 * exact task row (stage/revision/counters/fingerprint), [projectRevision] binds the project state
 * observed with the same read and [members] are ordered by item ID. No source text, translation,
 * prompt or credential is present.
 */
class TranslationTaskFailureEvidence(
    val review: TranslationTaskReview,
    val projectRevision: Long,
    members: List<TranslationFailureMember>,
    val next: Long? = null,
) {
    val members: List<TranslationFailureMember> = frozenList(members)

    init {
        require(projectRevision >= 0) { "Invalid project revision" }
        require(this.members.size <= TranslationTaskFailureBatch.MAX_MEMBERS) {
            "Failure evidence exceeds one bounded batch"
        }
        require(this.members.map { it.itemId }.distinct().size == this.members.size) {
            "Duplicate failure evidence member"
        }
        require(this.members.zipWithNext().all { (left, right) -> left.itemId.value < right.itemId.value }) {
            "Failure evidence is not ordered by item identity"
        }
        require(next == null || next >= 0) { "Invalid failure evidence cursor" }
    }

    override fun toString(): String =
        "TranslationTaskFailureEvidence(run=${review.run.runId.value}, stage=${review.stage}, " +
            "members=${members.size})"
}

/**
 * Narrow read port over the fixed-task store. A member is returned only while the persisted target
 * and request audit facts prove it: [TranslationFailureReceipt.DISPATCHED_NO_RECEIPT] is never
 * interchangeable with a received failure, and no usage is invented for an absent receipt.
 */
interface TranslationTaskFailureEvidencePort {
    /**
     * Reads at most [TranslationTaskFailureBatch.MAX_MEMBERS] failed/uncertain members ordered by
     * item ID, starting after [after]. [TranslationTaskFailureEvidence.next] is the last examined
     * target item ID when more targets remain.
     */
    suspend fun failures(
        ref: LocalProjectRef,
        run: RunIdentity,
        after: Long = 0,
    ): TranslationTaskFailureEvidence

    /**
     * Exact persisted facts for the named targets. A caller must reject the selection unless every
     * requested member is returned unchanged.
     */
    suspend fun failureMembers(
        ref: LocalProjectRef,
        run: RunIdentity,
        itemIds: List<ItemId>,
    ): List<TranslationFailureMember>
}

/**
 * Auditable user decision on the SAME fixed task. Implementations revalidate the persisted target
 * rows, project item fingerprints, request receipts and review fingerprint under the exclusive
 * project write reservation before recording anything; a stale, foreign or duplicate selection
 * throws instead of reopening another Run or creating a new task.
 */
interface TranslationTaskFailureAdjudicationPort {
    suspend fun recordFailureDecision(
        evidence: TranslationTaskFailureEvidence,
        batchId: TranslationTaskFailureBatchId,
        action: TranslationTaskCommandAction,
        members: List<TranslationFailureMember>,
        command: CommandId,
    ): TranslationTaskFailureDecisionReceipt

    /**
     * Marks the same fixed task terminal after the queue proved committed + explicitly skipped
     * targets. [skippedItemIds] must match real unfinished target rows with durable skip decisions;
     * unknown usage or an unresolved dispatch refuses the finalization.
     */
    suspend fun completeWithErrors(
        ref: LocalProjectRef,
        run: RunIdentity,
        skippedItemIds: Set<ItemId>,
        command: CommandId,
    )
}

/**
 * Durable receipt for one member decision. [retryBudget] is the number of automatic replacement
 * requests granted by this manual retry; it is 0 for skip/keep and for a manual retry that may
 * issue only the explicitly authorized request. The original spec budget is never replenished.
 */
class TranslationTaskFailureDecisionReceipt(
    val command: CommandId,
    val action: TranslationTaskCommandAction,
    val batchId: TranslationTaskFailureBatchId,
    memberIds: List<ItemId>,
    val retryBudget: Int,
) {
    val memberIds: List<ItemId> = frozenList(memberIds)

    init {
        require(action.adjudication) { "A member decision requires an adjudication action" }
        require(this.memberIds.isNotEmpty() &&
            this.memberIds.size <= TranslationTaskFailureBatch.MAX_MEMBERS) {
            "Invalid failure decision member count"
        }
        require(this.memberIds.distinct().size == this.memberIds.size) {
            "Duplicate failure decision member"
        }
        require(retryBudget in 0..5) { "Invalid manual retry budget" }
    }
}

/**
 * The execution layer may read explicit retry selections for the current lease. This never
 * authorizes a request by itself: a queue member-retry command must have reopened the same task,
 * and the caller still passes the shared stop/audit/gateway boundaries.
 */
fun interface TranslationTaskRetrySelectionPort {
    suspend fun retrySelection(lease: RunLease): Set<ItemId>
}

/**
 * Explicit skip selections for the current fixed task. The engine must exclude these members from
 * a resumed request scan; another engine retry must never silently translate an item the user chose
 * to skip while the queue finalizes committed + skipped evidence.
 */
fun interface TranslationTaskSkipSelectionPort {
    suspend fun skipSelection(lease: RunLease): Set<ItemId>
}
