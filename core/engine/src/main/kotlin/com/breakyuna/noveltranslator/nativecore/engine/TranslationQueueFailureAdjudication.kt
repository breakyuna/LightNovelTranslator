package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException

/** Outcome of one explicit member adjudication; [receipt] is null for a refused/unknown command. */
data class TranslationFailureAdjudicationResult(
    val queue: TranslationQueueResult,
    val receipt: TranslationTaskFailureDecisionReceipt? = null,
)

/**
 * UI-neutral adjudication over the durable translation queue.
 *
 * The caller selects members only after reading [evidence]. Every command re-reads the exact
 * persisted queue batch and the fixed-task evidence, then records one durable decision on the SAME
 * task before applying the queue command. It never creates a Run, a new task, a new retry budget or
 * an automatic request:
 * - RETRY reuses the same task identity and queue sequence; only an explicit later dispatch can
 *   spend, and the manual retry grants no automatic replacement requests ([retryBudget] = 0);
 * - SKIP records final member decisions and leaves completion to the scheduler's persisted
 *   committed/skipped/receipt proof;
 * - KEEP_ERROR settles the task at FAILED without clearing any request or failure fact;
 * - TERMINATE is task-level and survives uncertain receipts.
 *
 * Stale review facts, changed item/target fingerprints, a foreign task/batch/member, duplicate
 * commands and an already-decided member fail closed before any paid dispatch. A successful
 * queue-changing decision only requeues/settles the task; the application-owned scheduler must be
 * kicked separately, and no command in this class starts a request by itself.
 */
class TranslationFailureAdjudicator(
    private val coordinator: TranslationQueueCoordinator,
    private val failures: TranslationTaskFailureEvidencePort,
    private val decisions: TranslationTaskFailureAdjudicationPort? = null,
    private val reviews: TranslationTaskReviewPort? = null,
) {
    /** Bounded read of the exact fixed-task failure facts; no source or translation text. */
    suspend fun evidence(
        identity: TranslationTaskQueueIdentity,
        after: Long = 0,
    ): TranslationTaskFailureEvidence {
        val snapshot = coordinator.snapshot(identity.ref)
        requireNotNull(snapshot.task(identity)) { "Unknown translation task" }
        val evidence = failures.failures(identity.ref, identity.run, after)
        require(evidence.review.ref == identity.ref && evidence.review.run == identity.run) {
            "Failure evidence belongs to another translation task"
        }
        return evidence
    }

    suspend fun retry(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        decide(identity, batchId, memberIds, command, TranslationTaskCommandAction.RETRY_MEMBERS)

    suspend fun skip(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        decide(identity, batchId, memberIds, command, TranslationTaskCommandAction.SKIP_MEMBERS)

    suspend fun keepErrors(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        decide(identity, batchId, memberIds, command, TranslationTaskCommandAction.KEEP_ERROR)

    /** Task-level stop; every batch, request receipt and decision remains auditable. */
    suspend fun terminate(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationFailureAdjudicationResult {
        val snapshot = coordinator.snapshot(identity.ref)
        val task = snapshot.task(identity) ?: return result(snapshot, identity)
        if (task.command(command) != null) {
            return TranslationFailureAdjudicationResult(coordinator.terminate(identity, command))
        }
        if (!task.state.terminal) {
            try {
                val page = failures.failures(identity.ref, identity.run, 0)
                reviewTerminate(page.review, command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The queue command still records an explicit task-level stop. A stale/foreign
                // fixed-task review is never rewritten to fake a successful termination.
            }
        }
        return TranslationFailureAdjudicationResult(coordinator.terminate(identity, command))
    }

    private suspend fun decide(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
        action: TranslationTaskCommandAction,
    ): TranslationFailureAdjudicationResult {
        val snapshot = coordinator.snapshot(identity.ref)
        val task = snapshot.task(identity) ?: return result(snapshot, identity)
        // An exact command replay stays idempotent even after the task became terminal; a reused
        // command identity with a different payload is refused by the queue aggregate.
        if (task.command(command) != null) {
            return TranslationFailureAdjudicationResult(queueCommand(identity, batchId, memberIds, command, action))
        }
        if (!task.state.awaitingDecision) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_TRANSITION)
        }
        val batch = task.batch(batchId)
            ?: return rejected(snapshot, TranslationQueueRejection.UNKNOWN_BATCH)
        if (memberIds.isEmpty() || memberIds.size > TranslationTaskFailureBatch.MAX_MEMBERS ||
            memberIds.distinct().size != memberIds.size
        ) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        val ordered = memberIds.sortedBy { it.value }
        val selected = ArrayList<TranslationFailureMember>(ordered.size)
        for (itemId in ordered) {
            val member = batch.member(itemId)
                ?: return rejected(snapshot, TranslationQueueRejection.MEMBER_NOT_IN_BATCH)
            if (task.decision(batchId, itemId) != null || task.hasFinalErrorDecision(itemId)) {
                return rejected(snapshot, TranslationQueueRejection.MEMBER_ALREADY_DECIDED)
            }
            if (task.isCommitted(itemId)) {
                return rejected(snapshot, TranslationQueueRejection.COMMITTED_MEMBER)
            }
            if (task.isTerminalFailure(itemId) && !member.terminal) {
                return rejected(snapshot, TranslationQueueRejection.TERMINAL_FAILURE)
            }
            when (action) {
                TranslationTaskCommandAction.RETRY_MEMBERS -> {
                    if (member.committed) {
                        return rejected(snapshot, TranslationQueueRejection.COMMITTED_MEMBER)
                    }
                    if (member.terminal) {
                        return rejected(snapshot, TranslationQueueRejection.TERMINAL_FAILURE)
                    }
                    if (member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                        return rejected(snapshot, TranslationQueueRejection.UNCERTAIN_RECEIPT)
                    }
                }
                TranslationTaskCommandAction.SKIP_MEMBERS -> {
                    if (member.committed) {
                        return rejected(snapshot, TranslationQueueRejection.COMMITTED_MEMBER)
                    }
                    if (member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                        return rejected(snapshot, TranslationQueueRejection.UNCERTAIN_RECEIPT)
                    }
                }
                TranslationTaskCommandAction.KEEP_ERROR -> {
                    if (member.committed) {
                        return rejected(snapshot, TranslationQueueRejection.COMMITTED_MEMBER)
                    }
                }
                else -> return rejected(snapshot, TranslationQueueRejection.INVALID_TRANSITION)
            }
            selected.add(member)
        }
        // The queue batch is only a frozen claim. The fixed-task store must still report the exact
        // same item fingerprint, receipt state, failure class, usage certainty and committed flag.
        val fresh = try {
            failures.failureMembers(identity.ref, identity.run, ordered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TranslationTaskNeedsReview) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        } catch (_: IllegalArgumentException) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        if (fresh.sortedBy { it.itemId.value } != selected) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        val evidence = try {
            failures.failures(identity.ref, identity.run, 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TranslationTaskNeedsReview) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        } catch (_: IllegalArgumentException) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        val receipt = try {
            decisions?.recordFailureDecision(evidence, batchId, action, selected, command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TranslationTaskNeedsReview) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        } catch (_: IllegalArgumentException) {
            return rejected(snapshot, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        return TranslationFailureAdjudicationResult(
            queue = queueCommand(identity, batchId, ordered, command, action),
            receipt = receipt,
        )
    }

    private suspend fun queueCommand(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
        action: TranslationTaskCommandAction,
    ): TranslationQueueResult = when (action) {
        TranslationTaskCommandAction.RETRY_MEMBERS ->
            coordinator.retryMembers(identity, command, batchId, memberIds)
        TranslationTaskCommandAction.SKIP_MEMBERS ->
            coordinator.skipMembers(identity, command, batchId, memberIds)
        TranslationTaskCommandAction.KEEP_ERROR ->
            coordinator.keepErrors(identity, command, batchId, memberIds)
        else -> throw IllegalArgumentException("Not a member adjudication action")
    }

    private suspend fun reviewTerminate(review: TranslationTaskReview, command: CommandId) {
        reviews?.terminate(review, command)
    }

    private fun result(
        snapshot: TranslationQueueSnapshot,
        identity: TranslationTaskQueueIdentity,
    ): TranslationFailureAdjudicationResult =
        TranslationFailureAdjudicationResult(
            TranslationQueueResult.Rejected(
                snapshot.queue,
                if (snapshot.queue.tasks.any {
                        it.identity.run.runId == identity.run.runId ||
                            it.identity.run.commandId == identity.run.commandId
                    }
                ) {
                    TranslationQueueRejection.IDENTITY_MISMATCH
                } else {
                    TranslationQueueRejection.UNKNOWN_TASK
                },
            ),
        )

    private fun rejected(
        snapshot: TranslationQueueSnapshot,
        reason: TranslationQueueRejection,
    ): TranslationFailureAdjudicationResult =
        TranslationFailureAdjudicationResult(TranslationQueueResult.Rejected(snapshot.queue, reason))
}
