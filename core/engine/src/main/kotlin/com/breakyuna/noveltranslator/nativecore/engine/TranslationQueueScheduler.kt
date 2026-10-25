package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Durable task-side facts used only to reconcile a persisted RUNNING queue task. */
data class TranslationQueueTaskEvidence(
    val stage: String,
    val targetItems: Int,
    val committedItems: Int,
    val remainingItems: Int,
    val unresolvedDispatches: Int,
    val usage: UsageSource,
) {
    init {
        require(targetItems >= 0 && committedItems >= 0 && remainingItems >= 0 && unresolvedDispatches >= 0)
        require(committedItems <= targetItems && remainingItems <= targetItems)
    }
}

/** Durable run-journal facts; a REQUESTING stage without a proven receipt stays uncertain. */
data class TranslationQueueRunEvidence(
    val state: StoredRunState,
    val stage: StoredRunStage,
    val needsReview: Boolean,
)

/** Complete durable input to restart reconciliation. Missing evidence is never treated as success. */
data class TranslationQueueRecoveryEvidence(
    val task: TranslationQueueTaskEvidence?,
    val run: TranslationQueueRunEvidence?,
    /** True only while this same process still owns the matching execution. */
    val live: Boolean = false,
)

/**
 * Narrow recovery port. Implementations may read the fixed-task review and run journal, but must
 * never dispatch, resume or write a project from [evidence].
 */
fun interface TranslationQueueRecoveryPort {
    suspend fun evidence(dispatch: TranslationQueueDispatch): TranslationQueueRecoveryEvidence
}

enum class TranslationQueueRecoveryVerdict { LIVE, PAUSED, NEEDS_REVIEW, FAILED, CANCELLED }

class TranslationQueueRecoveryResult(
    val verdict: TranslationQueueRecoveryVerdict,
    val task: TranslationQueuedTask?,
) {
    override fun toString(): String =
        "TranslationQueueRecoveryResult(verdict=$verdict, run=${task?.identity?.run?.runId?.value})"
}

/** Result of the narrow revision check performed immediately before a queue dispatch. */
sealed interface TranslationQueueRevisionDecision {
    /** The admitted revision is still current. */
    data class Current(val revision: Long) : TranslationQueueRevisionDecision

    /**
     * A newer revision is safe because durable queue work, not user editing, produced it.
     * [evidence] is a short diagnostic token, never source text or a credential.
     */
    data class SafeAdvance(val revision: Long, val evidence: String) : TranslationQueueRevisionDecision

    /** The revision changed without proof; dispatch is refused and the task needs review. */
    data class Refused(val currentRevision: Long, val evidence: String) : TranslationQueueRevisionDecision
}

fun interface TranslationQueueRevisionPort {
    suspend fun validate(spec: TranslationTaskSpec): TranslationQueueRevisionDecision
}

/** Terminal foreground outcome plus the service token that produced it (metadata only). */
data class TranslationQueueLaunchResult(
    val phase: ForegroundTranslationPhase,
    val token: String? = null,
) {
    init { require(!phase.active) { "A launched queue task must report a terminal phase" } }
}

/** Starts the existing foreground execution. The scheduler never navigates or changes the page. */
fun interface TranslationQueueLaunchPort {
    suspend fun launch(dispatch: TranslationQueueDispatch, continuation: Boolean): TranslationQueueLaunchResult
}

/** Process-local control of an execution already owned by the foreground runner. */
interface TranslationQueueControlPort {
    /** True when the matching run accepted a pause barrier. */
    fun pause(identity: TranslationTaskQueueIdentity): Boolean

    /** True when the matching run accepted a stop; a missing match is false. */
    fun stop(identity: TranslationTaskQueueIdentity): Boolean
}

/**
 * Application-level, UI-independent scheduler over the durable [TranslationQueueCoordinator].
 *
 * It claims the deterministic global head, revalidates the frozen project revision through a
 * narrow port, starts the existing foreground execution, derives completion from persisted target
 * and usage facts, records exactly one terminal queue outcome, and then continues with the next
 * runnable task. It never navigates, switches the visible project, or starts a second execution
 * while any runtime/foreground reservation is active.
 *
 * Safety invariants:
 * - restart reconciliation runs before dispatch and may only move RUNNING to PAUSED/NEEDS_REVIEW/
 *   FAILED from durable evidence; it never auto-resumes or re-sends an uncertain request;
 * - a stale revision is refreshed only with an audited REVALIDATE_REVISION command; otherwise the
 *   task stops at NEEDS_REVIEW without any paid dispatch;
 * - pause/cancel first reach the live runner, then persist the matching queue command, so the
 *   notification path works without an Activity or ViewModel;
 * - completion requires committed + explicitly skipped targets, proven receipts, no unresolved
 *   dispatch and known usage. Unknown usage always becomes NEEDS_REVIEW, never a synthetic ACTUAL.
 */
class TranslationQueueScheduler(
    private val coordinator: TranslationQueueCoordinator,
    private val launch: TranslationQueueLaunchPort,
    private val outcomes: TranslationTaskOutcomePort,
    private val revisions: TranslationQueueRevisionPort,
    private val recovery: TranslationQueueRecoveryPort,
    private val control: TranslationQueueControlPort = NoControl,
    private val isBusy: () -> Boolean = { false },
    private val recoverJournal: suspend () -> Unit = {},
    private val onSettled: suspend (TranslationQueueDispatch) -> Unit = {},
    /**
     * Durable item-failure evidence. Null preserves the pre-adjudication scheduler shape for
     * callers that have not wired the fixed-task store yet; no failure batch is invented when it
     * is absent. A real store must provide it before member decisions can be offered.
     */
    private val failures: TranslationTaskFailureEvidencePort? = null,
    /**
     * Finalizes the same fixed task after the queue proved committed + skipped targets. This is
     * optional for the same compatibility reason and never manufactures usage or completion.
     */
    private val decisions: TranslationTaskFailureAdjudicationPort? = null,
) {
    private val drainLock = Mutex()

    /** Reconciles abandoned RUNNING facts before any new dispatch. Does not start work. */
    suspend fun recover(): List<TranslationQueueRecoveryResult> {
        recoverJournal()
        return coordinator.recoverRunning(recovery)
    }

    /**
     * Claims and executes the global head repeatedly until no runnable task remains. Returns the
     * number of claimed tasks. Concurrent callers are coalesced; the active caller keeps looping
     * and therefore also sees tasks admitted while an earlier execution was in flight.
     */
    suspend fun drain(): Int {
        if (!drainLock.tryLock()) return 0
        try {
            var claimed = 0
            while (true) {
                val dispatch = claimNext() ?: break
                execute(dispatch)
                claimed++
            }
            return claimed
        } finally {
            drainLock.unlock()
        }
    }

    /** Starts exactly one QUEUED identity only when it is the deterministic global head. */
    suspend fun start(identity: TranslationTaskQueueIdentity): TranslationQueueResult {
        val dispatch = try {
            coordinator.markRunning(identity)
        } catch (refused: TranslationQueueRefused) {
            return TranslationQueueResult.Rejected(
                coordinator.snapshot(identity.ref).queue,
                refused.reason,
            )
        }
        execute(dispatch)
        return TranslationQueueResult.Applied(
            coordinator.snapshot(identity.ref).queue,
            coordinator.snapshot(identity.ref).task(identity) ?: dispatch.task,
        )
    }

    /**
     * Pauses the exact live run before writing the PAUSED outcome. If the run cannot pause (for
     * example before its foreground gate opens), it is stopped; no new paid request is allowed.
     */
    suspend fun pause(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult {
        val livePaused = control.pause(identity)
        if (!livePaused) control.stop(identity)
        val result = coordinator.markPaused(identity, command)
        if (result is TranslationQueueResult.Rejected) {
            val snapshot = coordinator.snapshot(identity.ref)
            if (snapshot.task(identity)?.state == TranslationTaskQueueState.PAUSED) {
                return TranslationQueueResult.Unchanged(snapshot.queue, snapshot.task(identity))
            }
        }
        return result
    }

    /** Stops the live run before recording the terminal cancel. Committed facts are retained. */
    suspend fun cancel(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult {
        control.stop(identity)
        val result = coordinator.markCancelled(identity, command)
        if (result is TranslationQueueResult.Rejected) {
            val snapshot = coordinator.snapshot(identity.ref)
            if (snapshot.task(identity)?.state == TranslationTaskQueueState.CANCELLED) {
                return TranslationQueueResult.Unchanged(snapshot.queue, snapshot.task(identity))
            }
        }
        return result
    }

    /**
     * Explicit resume: the coordinator requeues and may claim the global slot, after which the
     * same execution and outcome pipeline runs. A resume command is never replayed automatically.
     */
    suspend fun resume(ref: LocalProjectRef, command: CommandId? = null): TranslationQueueResult {
        val result = if (command == null) coordinator.resumeQueue(ref) else coordinator.resumeQueue(ref, command)
        if (result !is TranslationQueueResult.Applied) return result
        if (result.task.state == TranslationTaskQueueState.RUNNING) {
            val dispatch = coordinator.running(result.task.identity) ?: return result
            execute(dispatch)
            val current = coordinator.snapshot(ref).task(result.task.identity) ?: result.task
            return TranslationQueueResult.Applied(coordinator.snapshot(ref).queue, current)
        }
        return result
    }

    private suspend fun claimNext(): TranslationQueueDispatch? {
        if (isBusy()) return null
        return coordinator.nextRunnable()
    }

    private suspend fun execute(dispatch: TranslationQueueDispatch) {
        try {
            executeClaimed(dispatch)
        } finally {
            try {
                onSettled(dispatch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Observer failures cannot change the durable queue outcome.
            }
        }
    }

    private suspend fun executeClaimed(dispatch: TranslationQueueDispatch) {
        var current = dispatch
        if (stillRunning(current.identity)?.state != TranslationTaskQueueState.RUNNING) return
        // A previous attempt may already have committed or explicitly skipped every target
        // (for example a pause raced the final commit). Reconstruct completion from durable
        // evidence instead of opening a new paid execution.
        val existing = readOutcome(current.spec, current.task)
        if (existing != null) {
            if (existing.skippedItems == current.task.skippedItemIds.size &&
                completeFromEvidence(current.identity, current.task, existing)
            ) return
            // All targets are already accounted for but completion is not proven (for example
            // usage is unknown) or the task never acquired any target. Never start a new paid
            // execution just to re-derive facts; keep the task for explicit review.
            if (existing.targetItems == 0 ||
                (existing.targetItems > 0 &&
                    existing.committedItems.toLong() + existing.skippedItems == existing.targetItems.toLong() &&
                    existing.unresolvedDispatches == 0)
            ) {
                coordinator.markNeedsReview(current.identity)
                return
            }
        }
        val decision = try {
            revisions.validate(current.executionSpec)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TranslationQueueRevisionDecision.Refused(current.executionSpec.revision, "revision-unreadable")
        }
        when (decision) {
            is TranslationQueueRevisionDecision.Current -> Unit
            is TranslationQueueRevisionDecision.SafeAdvance -> {
                val refreshed = coordinator.revalidateRevision(
                    current.identity,
                    revalidationCommand(current.task, decision.revision),
                    decision.revision,
                )
                if (refreshed !is TranslationQueueResult.Applied &&
                    refreshed !is TranslationQueueResult.Duplicate
                ) {
                    coordinator.markNeedsReview(current.identity)
                    return
                }
                current = coordinator.running(current.identity) ?: return
            }
            is TranslationQueueRevisionDecision.Refused -> {
                // The task keeps its frozen identity and all failure facts. The refused revision
                // is bound into a durable command identity for audit; NEEDS_REVIEW is not
                // terminal, so an explicit user decision can still terminate or replace it.
                coordinator.refuseRevision(
                    current.identity,
                    CommandId("queue-revision-refused-${current.identity.run.commandId.value}-" +
                        decision.currentRevision),
                )
                return
            }
        }
        // A pause/cancel may have landed during revision validation. Never cross into the paid
        // launch after the durable queue stopped being RUNNING.
        if (stillRunning(current.identity)?.state != TranslationTaskQueueState.RUNNING) return
        val continuation = current.task.commands.any {
            it.action == TranslationTaskCommandAction.PAUSE ||
                it.action == TranslationTaskCommandAction.RESUME ||
                it.action == TranslationTaskCommandAction.RETRY_MEMBERS ||
                it.action == TranslationTaskCommandAction.SKIP_MEMBERS
        }
        val result = try {
            launch.launch(current, continuation)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TranslationQueueLaunchResult(ForegroundTranslationPhase.FAILED)
        }
        settle(current, result)
    }

    private suspend fun stillRunning(identity: TranslationTaskQueueIdentity): TranslationQueuedTask? =
        coordinator.snapshot(identity.ref).task(identity)

    private suspend fun settle(
        dispatch: TranslationQueueDispatch,
        launchResult: TranslationQueueLaunchResult,
    ) {
        val snapshot = coordinator.snapshot(dispatch.spec.ref)
        val current = snapshot.task(dispatch.identity) ?: return
        // A user pause/cancel may have reached the queue before the runner settled. Never replace
        // an explicit or already terminal queue fact with a late phase.
        if (current.state != TranslationTaskQueueState.RUNNING) return
        when (launchResult.phase) {
            ForegroundTranslationPhase.PAUSED -> {
                coordinator.markPaused(dispatch.identity, pauseCommand(current))
                return
            }
            ForegroundTranslationPhase.CANCELLED -> {
                coordinator.markCancelled(dispatch.identity, cancelCommand(current))
                return
            }
            else -> Unit
        }
        val evidence = readOutcome(dispatch.spec, current)
        // Item failures are recorded as one bounded batch on this same queue task before any
        // completion/FAILED settlement. Persisted uncertainty is never turned into a retry: the
        // task stops at NEEDS_REVIEW until the user chooses exact members.
        val failed = readFailureMembers(dispatch, current)
        if (!failed.isNullOrEmpty()) {
            // A pause/cancel that landed while reading evidence keeps its own outcome.
            val latest = coordinator.snapshot(dispatch.spec.ref).task(dispatch.identity) ?: return
            if (latest.state == TranslationTaskQueueState.RUNNING) {
                recordMemberFailure(dispatch.identity, latest, failed)
            }
            return
        }
        if (evidence != null && evidence.skippedItems != current.skippedItemIds.size) {
            coordinator.markNeedsReview(dispatch.identity)
            return
        }
        if (evidence != null && completeFromEvidence(dispatch.identity, current, evidence)) return
        when (evidence?.stage ?: launchResult.phase.name) {
            "FAILED" -> coordinator.markFailed(dispatch.identity)
            "NEEDS_REVIEW" -> coordinator.markNeedsReview(dispatch.identity)
            "CANCELLED" -> coordinator.markCancelled(dispatch.identity, cancelCommand(current))
            else -> {
                when (launchResult.phase) {
                    ForegroundTranslationPhase.SUCCEEDED,
                    ForegroundTranslationPhase.FAILED -> coordinator.markNeedsReview(dispatch.identity)
                    else -> coordinator.markFailed(dispatch.identity)
                }
            }
        }
    }

    /**
     * Reads all bounded failure pages until one batch is full. Skipped, committed, finally
     * decided and already-open members are excluded; anything left is a new failure fact for this
     * execution and must be adjudicated. A read failure returns null so the caller keeps the
     * conservative task-level stop instead of inventing member evidence.
     */
    private suspend fun readFailureMembers(
        dispatch: TranslationQueueDispatch,
        task: TranslationQueuedTask,
    ): List<TranslationFailureMember>? {
        val port = failures ?: return null
        val selected = LinkedHashMap<ItemId, TranslationFailureMember>()
        var after = 0L
        var pages = 0
        try {
            while (pages < MAX_FAILURE_EVIDENCE_PAGES &&
                selected.size < TranslationTaskFailureBatch.MAX_MEMBERS
            ) {
                val page = port.failures(dispatch.spec.ref, dispatch.spec.run, after)
                for (member in page.members) {
                    if (selected.size >= TranslationTaskFailureBatch.MAX_MEMBERS) break
                    if (task.isCommitted(member.itemId) || task.hasFinalErrorDecision(member.itemId) ||
                        task.hasUnresolvedMember(member.itemId) || member.committed
                    ) continue
                    selected.putIfAbsent(member.itemId, member)
                }
                val next = page.next ?: break
                if (next <= after) break
                after = next
                pages++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return selected.values.sortedBy { it.itemId.value }.take(TranslationTaskFailureBatch.MAX_MEMBERS)
    }

    /**
     * Exactly one bounded batch is appended to the same task. Its identity is derived from the
     * current batch round and the frozen member facts, so replaying the same settlement before or
     * after a crash is idempotent. A rejected append still stops at NEEDS_REVIEW; it never falls
     * through to COMPLETED/FAILED or opens a new Run.
     */
    private suspend fun recordMemberFailure(
        identity: TranslationTaskQueueIdentity,
        task: TranslationQueuedTask,
        members: List<TranslationFailureMember>,
    ) {
        if (members.isEmpty()) return
        val batch = TranslationTaskFailureBatch(failureBatchId(task, members), members)
        when (coordinator.recordFailureBatch(identity, batch, requireRunning = true)) {
            is TranslationQueueResult.Applied, is TranslationQueueResult.Unchanged -> {
                val saved = coordinator.snapshot(identity.ref).task(identity)
                if (saved?.state == TranslationTaskQueueState.RUNNING) {
                    coordinator.markNeedsReview(identity)
                }
            }
            is TranslationQueueResult.Rejected -> coordinator.markNeedsReview(identity)
        }
    }

    private fun failureBatchId(
        task: TranslationQueuedTask,
        members: List<TranslationFailureMember>,
    ): TranslationTaskFailureBatchId {
        val round = task.batches.size + 1
        val material = buildString {
            append(task.identity.run.commandId.value); append('\u0000')
            append(task.identity.revision); append('\u0000')
            append(round)
            for (member in members) {
                append('\u0000'); append(member.itemId.value)
                append('\u0000'); append(member.fingerprint.sha256)
                append('\u0000'); append(member.failure.name)
                append('\u0000'); append(member.receipt.name)
                append('\u0000'); append(member.usage.name)
                append('\u0000'); append(member.committed)
                append('\u0000'); append(member.terminal)
            }
        }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material))
        return TranslationTaskFailureBatchId("queue-failure-$round-$digest")
    }

    private fun completionCommand(task: TranslationQueuedTask): CommandId {
        val material = buildString {
            append(task.identity.run.commandId.value); append('\u0000')
            for (itemId in task.skippedItemIds.sortedBy { it.value }) {
                append(itemId.value); append('\u0000')
            }
        }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material))
        return CommandId("queue-complete-errors-$digest")
    }

    private suspend fun readOutcome(
        spec: TranslationTaskSpec,
        task: TranslationQueuedTask,
    ): TranslationTaskOutcomeEvidence? =
        try {
            outcomes.outcome(spec.ref, spec.run, task.skippedItemIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Records completion only when persisted targets, receipts and usage prove it. */
    private suspend fun completeFromEvidence(
        identity: TranslationTaskQueueIdentity,
        task: TranslationQueuedTask,
        evidence: TranslationTaskOutcomeEvidence,
    ): Boolean {
        if (evidence.skippedItems != task.skippedItemIds.size) return false
        if (!completionProven(evidence)) return false
        val facts = TranslationTaskCompletionFacts(
            committedItems = evidence.committedItems,
            skippedItems = evidence.skippedItems,
            receiptedItems = evidence.receiptedItems,
            targetItems = evidence.targetItems,
            unreceiptedDispatches = evidence.unresolvedDispatches,
            usage = evidence.usage,
        )
        // Always finalize the fixed task row, including a zero-skip completion. Binding the
        // exact queue skip set before publishing the queue outcome prevents a terminal queue
        // state with a still-READY task that the old task dialog would offer to resume.
        if (evidence.stage !in setOf("COMPLETE", "CANCELLED")) {
            try {
                decisions?.completeWithErrors(
                    identity.ref,
                    identity.run,
                    task.skippedItemIds,
                    completionCommand(task),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
        }
        val completed = if (evidence.skippedItems == 0) {
            coordinator.markCompleted(identity, facts)
        } else {
            coordinator.markCompletedWithErrors(identity, facts)
        }
        return completed is TranslationQueueResult.Applied || completed is TranslationQueueResult.Unchanged
    }

    private fun completionProven(evidence: TranslationTaskOutcomeEvidence): Boolean =
        evidence.targetItems > 0 &&
            evidence.committedItems.toLong() + evidence.skippedItems == evidence.targetItems.toLong() &&
            evidence.receiptedItems == evidence.committedItems &&
            evidence.unresolvedDispatches == 0 &&
            evidence.usage != UsageSource.UNKNOWN

    private fun revalidationCommand(task: TranslationQueuedTask, revision: Long): CommandId =
        CommandId("queue-revalidate-${task.identity.run.commandId.value}-$revision")

    private fun pauseCommand(task: TranslationQueuedTask): CommandId {
        val count = task.commands.count { it.action == TranslationTaskCommandAction.PAUSE }
        return CommandId("queue-pause-${task.identity.run.commandId.value}-${count + 1}")
    }

    private fun cancelCommand(task: TranslationQueuedTask): CommandId {
        val count = task.commands.count { it.action == TranslationTaskCommandAction.CANCEL }
        return CommandId("queue-cancel-${task.identity.run.commandId.value}-${count + 1}")
    }

    private object NoControl : TranslationQueueControlPort {
        override fun pause(identity: TranslationTaskQueueIdentity): Boolean = false
        override fun stop(identity: TranslationTaskQueueIdentity): Boolean = false
    }
}

/** 100 pages × 20 members bounds one settlement read without scanning an unbounded project. */
private const val MAX_FAILURE_EVIDENCE_PAGES = 100
