package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One task that is durably RUNNING and ready for the app's existing foreground executor. */
class TranslationQueueDispatch(
    val stored: StoredTranslationQueue,
    val entry: TranslationQueueEntry,
) {
    val spec: TranslationTaskSpec get() = entry.spec
    val task: TranslationQueuedTask get() = entry.task
    val identity: TranslationTaskQueueIdentity get() = entry.task.identity

    /**
     * The frozen spec with the latest audited [TranslationQueuedTask.executionRevision] applied.
     * The admitted spec remains in [entry] so queue audit and receipts keep their original binding.
     */
    val executionSpec: TranslationTaskSpec
        get() = entry.spec.copy(revision = entry.task.actionableRevision)

    override fun toString(): String =
        "TranslationQueueDispatch(run=${spec.run.runId.value}, revision=${stored.snapshot.revision}, " +
            "executionRevision=$executionSpec.revision)"
}

/**
 * Consumer boundary for a dispatched task. The app wires this to its existing foreground
 * translation runner; the coordinator itself never calls a model, writes a project or retries.
 * The executor must revalidate the current project revision, configuration and receipts before
 * spending, then record the outcome (completed/completed with errors/failed/needs review) before
 * the next task may use the global slot.
 */
fun interface TranslationQueueExecutionPort {
    suspend fun execute(dispatch: TranslationQueueDispatch)
}

/** A scheduler/lifecycle request refused by the durable queue; no state was written. */
class TranslationQueueRefused(
    val reason: TranslationQueueRejection,
) : IllegalStateException("Translation queue refused: $reason")

/**
 * Durable coordination for the pure [TranslationTaskQueue] state machine.
 *
 * Guarantees:
 * - every applied transition is persisted through [TranslationQueueStorePort.compareAndSet]
 *   before this class returns success;
 * - a queue has exactly one RUNNING task across all local projects, and one project never has
 *   more than one task occupying its slot (the pure aggregate enforces the project slot);
 * - scheduling follows persisted enqueue time, then a stable project identity, so the global
 *   head never depends on map or file iteration order; a project whose earlier task holds its
 *   slot is skipped without losing its sequence position;
 * - a QUEUED task is only dispatched when its failure facts are fully decided. FAILED and
 *   NEEDS_REVIEW never dispatch automatically, and a crash-restored RUNNING task is never
 *   re-dispatched; [recoverRunning] may only pause or stop it from durable evidence, while resume
 *   requires an explicit command;
 * - a newer project revision is adopted only through the audited
 *   [revalidateRevision]/[refuseRevision] commands; the admitted task identity is never rewritten;
 * - command replay is delegated to the pure aggregate, so the same [CommandId] and payload is
 *   idempotent and a reused identity with a different payload is refused;
 * - no model call, project write, source text, translation, credential or HTTP header ever
 *   passes through.
 *
 * This is a single-process scheduler: every coordinator instance in the process shares the same
 * scheduling mutex, but a second process would need its own cross-process reservation before
 * dispatching.
 */
class TranslationQueueCoordinator(
    private val store: TranslationQueueStorePort,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0) },
    private val limits: TranslationTaskQueueLimits = TranslationTaskQueueLimits.DEFAULT,
) {
    /** Current durable snapshot; a missing file is an empty queue for this exact project. */
    suspend fun snapshot(ref: LocalProjectRef): TranslationQueueSnapshot = MUTEX.withLock {
        store.load(ref)?.snapshot ?: TranslationQueueSnapshot.empty(ref, now(), limits)
    }

    /** All durable project queues, strictly decoded; corrupt files are never skipped. */
    suspend fun snapshot(): List<TranslationQueueSnapshot> = MUTEX.withLock {
        store.list().map { it.snapshot }
    }

    /**
     * Admits exactly the frozen paid spec at the tail. [command] must be the paid run command; it
     * is the durable idempotence identity for repeated taps. An existing task with the same
     * identity and spec is Unchanged; the same identity with different options is a conflict.
     */
    suspend fun enqueue(spec: TranslationTaskSpec, command: CommandId): TranslationQueueResult =
        MUTEX.withLock {
            require(command == spec.run.commandId) {
                "Queue admission must reuse the paid run command identity"
            }
            val stored = store.load(spec.ref)
            val base = stored?.snapshot ?: TranslationQueueSnapshot.empty(spec.ref, now(), limits)
            val identity = spec.queueIdentity()
            val existing = base.queue.task(identity)
            if (existing != null) {
                return@withLock if (base.spec(identity) == spec) {
                    TranslationQueueResult.Unchanged(base.queue, existing)
                } else {
                    TranslationQueueResult.Rejected(base.queue, TranslationQueueRejection.IDENTITY_CONFLICT)
                }
            }
            val result = base.queue.enqueue(identity)
            if (result !is TranslationQueueResult.Applied) return@withLock result
            val next = base.updated(result.queue, result.task.identity, nextTimestamp(base), newSpec = spec)
            val committed = store.compareAndSet(spec.ref, stored, next)
            TranslationQueueResult.Applied(committed.snapshot.queue, committed.snapshot.task(identity)!!)
        }

    /** Claims the globally next runnable task (all projects) and persists it as RUNNING. */
    suspend fun nextRunnable(): TranslationQueueDispatch? = MUTEX.withLock { claimNext(null, null) }

    /**
     * Claims the next runnable task only when the global head belongs to [ref]. A task from
     * another project or a running task elsewhere returns null without writing.
     */
    suspend fun nextRunnable(ref: LocalProjectRef): TranslationQueueDispatch? =
        MUTEX.withLock { claimNext(ref, null) }

    /**
     * Marks exactly [identity] RUNNING only if it is the deterministic global head. Any other
     * head, an occupied project slot or a task that is still uncertain refuses without a write.
     */
    suspend fun markRunning(identity: TranslationTaskQueueIdentity): TranslationQueueDispatch =
        MUTEX.withLock {
            claimNext(identity.ref, identity)
                ?: throw TranslationQueueRefused(TranslationQueueRejection.INVALID_TRANSITION)
        }

    /** Runs one claimed task through the app's foreground executor; the outcome must be recorded. */
    suspend fun executeNext(execution: TranslationQueueExecutionPort): TranslationQueueDispatch? {
        val dispatch = nextRunnable() ?: return null
        execution.execute(dispatch)
        return dispatch
    }

    /** Explicit user pause. Only the exact RUNNING task identity may pause. */
    suspend fun markPaused(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.pause(identity, command) }
    }

    /** Explicit user cancel; committed and failed facts remain in the snapshot. */
    suspend fun markCancelled(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.cancel(identity, command) }
    }

    /** Records an execution-level NEEDS_REVIEW stop without inventing a member batch. */
    suspend fun markNeedsReview(identity: TranslationTaskQueueIdentity): TranslationQueueResult =
        MUTEX.withLock {
            apply(identity.ref) { it.queue.markNeedsReview(identity) }
        }

    /** Records a task-level FAILED stop without inventing a member batch. */
    suspend fun markFailed(identity: TranslationTaskQueueIdentity): TranslationQueueResult =
        MUTEX.withLock {
            apply(identity.ref) { it.queue.markFailed(identity) }
        }

    /**
     * Audited revision refresh after a narrow evidence port proved that the newer project revision
     * belongs to earlier queue work, not to unobserved user edits. The task identity stays frozen;
     * only the next execution revision advances.
     */
    suspend fun revalidateRevision(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
        revision: Long,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.revalidateRevision(identity, command, revision) }
    }

    /** Audited refusal of an unproven revision; stops the task at NEEDS_REVIEW without dispatch. */
    suspend fun refuseRevision(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.refuseRevision(identity, command) }
    }

    /**
     * Reconciles persisted RUNNING tasks after process restart. For each task the narrow
     * [TranslationQueueRecoveryPort] returns durable run/task evidence; a proven-safe execution is
     * moved to PAUSED (explicit resume still required), an uncertain or failed execution to
     * NEEDS_REVIEW/FAILED. A live same-process execution is never touched and no model request,
     * project write or new dispatch is performed here.
     */
    suspend fun recoverRunning(
        recovery: TranslationQueueRecoveryPort,
    ): List<TranslationQueueRecoveryResult> = MUTEX.withLock {
        val running = store.list().flatMap { stored ->
            stored.snapshot.queue.tasks.filter { it.state == TranslationTaskQueueState.RUNNING }
                .map { stored to it }
        }
        if (running.isEmpty()) return@withLock emptyList()
        val results = ArrayList<TranslationQueueRecoveryResult>(running.size)
        for ((stored, task) in running) {
            val dispatch = TranslationQueueDispatch(
                stored,
                requireNotNull(stored.snapshot.entry(task.identity)),
            )
            val evidence = try {
                recovery.evidence(dispatch)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val verdict = when {
                task.batches.any { batch ->
                    batch.members.any { member ->
                        !member.committed && member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT
                    }
                } -> TranslationQueueRecoveryVerdict.NEEDS_REVIEW
                evidence == null -> TranslationQueueRecoveryVerdict.NEEDS_REVIEW
                else -> recoveryVerdict(evidence)
            }
            if (verdict == TranslationQueueRecoveryVerdict.LIVE) {
                results.add(TranslationQueueRecoveryResult(verdict, task))
                continue
            }
            val result = apply(task.identity.ref) { base ->
                canRecover(task.identity, base)?.let { current ->
                    when (verdict) {
                        TranslationQueueRecoveryVerdict.PAUSED ->
                            base.queue.recoverPaused(current.identity)
                        TranslationQueueRecoveryVerdict.NEEDS_REVIEW ->
                            base.queue.markNeedsReview(current.identity)
                        TranslationQueueRecoveryVerdict.FAILED ->
                            base.queue.markFailed(current.identity)
                        TranslationQueueRecoveryVerdict.CANCELLED ->
                            base.queue.cancel(current.identity,
                                CommandId("queue-recover-cancel-${current.identity.run.commandId.value}"))
                        else -> TranslationQueueResult.Unchanged(base.queue, current)
                    }
                } ?: TranslationQueueResult.Unchanged(base.queue, base.queue.task(task.identity))
            }
            results.add(TranslationQueueRecoveryResult(verdict, (result as? TranslationQueueResult.Applied)?.task
                ?: (result as? TranslationQueueResult.Unchanged)?.task))
        }
        results
    }

    /** Returns the durable dispatch only while exactly this identity is globally RUNNING. */
    suspend fun running(identity: TranslationTaskQueueIdentity): TranslationQueueDispatch? = MUTEX.withLock {
        val stored = store.load(identity.ref) ?: return@withLock null
        val entry = stored.snapshot.entry(identity) ?: return@withLock null
        if (entry.task.state != TranslationTaskQueueState.RUNNING) return@withLock null
        if (store.list().any { other ->
                other.snapshot.ref != identity.ref &&
                    other.snapshot.queue.tasks.any { it.state == TranslationTaskQueueState.RUNNING }
            }
        ) return@withLock null
        TranslationQueueDispatch(stored, entry)
    }

    private fun canRecover(
        identity: TranslationTaskQueueIdentity,
        snapshot: TranslationQueueSnapshot,
    ): TranslationQueuedTask? =
        snapshot.queue.task(identity)?.takeIf { it.state == TranslationTaskQueueState.RUNNING }

    private fun recoveryVerdict(evidence: TranslationQueueRecoveryEvidence): TranslationQueueRecoveryVerdict {
        if (evidence.live) return TranslationQueueRecoveryVerdict.LIVE
        val task = evidence.task
        val run = evidence.run
        // A journal that still says RUNNING/WAITING may belong to this process or to an
        // unreconciled execution. Never rewrite it as paused; the caller must recover the journal
        // first, otherwise an active paid request could be misclassified.
        if (run?.state == StoredRunState.RUNNING || run?.state == StoredRunState.WAITING_APPROVAL) {
            return TranslationQueueRecoveryVerdict.LIVE
        }
        if (task != null && task.unresolvedDispatches > 0) {
            return TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
        if (task?.stage == "NEEDS_REVIEW" || run?.needsReview == true ||
            run?.stage == StoredRunStage.REQUESTING
        ) {
            return TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
        // An explicit cancellation outranks a COMMITTING/MARKING_FAILURE checkpoint. A successful
        // commit batch may already be complete, but cancellation is never silently rewritten into
        // an ordinary review state. `needsReview` above still preserves genuine uncertainty.
        if (run?.state == StoredRunState.CANCELLED) {
            return TranslationQueueRecoveryVerdict.CANCELLED
        }
        if (task?.stage == "CANCELLED" || task?.stage == "FAILED") {
            return TranslationQueueRecoveryVerdict.FAILED
        }
        if (task?.stage == "COMPLETE") {
            // The queue outcome was not durably recorded. Completion must be reconstructed from
            // target/receipt/usage evidence by the scheduler rather than guessed in recovery.
            return TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
        if (task == null && run == null) return TranslationQueueRecoveryVerdict.PAUSED
        if (task != null && (task.stage == "COMMITTING" || task.stage == "MARKING_FAILURE")) {
            // Without the task store's receipt reconciliation a commit is not proven safe to
            // resume. Keep the facts for explicit review instead of replaying the commit.
            return TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
        if (run?.state == StoredRunState.FAILED) {
            // A classified run failure with an intact task checkpoint and no unresolved receipt is
            // safe to pause; the user still has to resume explicitly. A missing or partially
            // committing task stays FAILED for adjudication.
            return if (task == null || task.stage == "READY" || task.stage == "RESPONSE_RECORDED") {
                TranslationQueueRecoveryVerdict.PAUSED
            } else {
                TranslationQueueRecoveryVerdict.FAILED
            }
        }
        if (task == null && run?.stage != StoredRunStage.ACCEPTED) {
            // A run that crossed a request/response/commit boundary without any fixed-task
            // evidence cannot be proven safe to replay.
            return TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
        return when (task?.stage) {
            null, "READY", "RESPONSE_RECORDED", "COMMITTING", "MARKING_FAILURE" ->
                TranslationQueueRecoveryVerdict.PAUSED
            else -> TranslationQueueRecoveryVerdict.NEEDS_REVIEW
        }
    }

    /** Proven completion without skipped members; any skip is refused instead of reclassified. */
    suspend fun markCompleted(
        identity: TranslationTaskQueueIdentity,
        facts: TranslationTaskCompletionFacts,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { snapshot ->
            if (facts.skippedItems != 0) {
                TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.COMPLETION_NOT_PROVEN)
            } else {
                snapshot.queue.complete(identity, facts)
            }
        }
    }

    /**
     * Proven completion for a review/failed task with no failure batch. Used only after restart
     * reconciliation has re-read the fixed task, exact Run journal and usage evidence; tasks with
     * member batches still require explicit adjudication.
     */
    suspend fun markCompletedAfterReview(
        identity: TranslationTaskQueueIdentity,
        facts: TranslationTaskCompletionFacts,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.completeAfterReview(identity, facts) }
    }

    /** Proven completion that accounts for explicitly skipped members as retained errors. */
    suspend fun markCompletedWithErrors(
        identity: TranslationTaskQueueIdentity,
        facts: TranslationTaskCompletionFacts,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { snapshot ->
            if (facts.skippedItems == 0) {
                TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.COMPLETION_NOT_PROVEN)
            } else {
                snapshot.queue.complete(identity, facts)
            }
        }
    }

    /** Appends one bounded failure batch; identical re-reporting is idempotent. */
    suspend fun recordFailureBatch(
        identity: TranslationTaskQueueIdentity,
        batch: TranslationTaskFailureBatch,
        /**
         * Execution settlement requires the task to still be RUNNING. A user pause/cancel that won
         * the race keeps its own state instead of being replaced by a late failure batch.
         */
        requireRunning: Boolean = false,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { snapshot ->
            val current = snapshot.task(identity)
            if (requireRunning && current?.state != TranslationTaskQueueState.RUNNING) {
                TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.INVALID_TRANSITION)
            } else {
                snapshot.queue.recordFailure(identity, batch)
            }
        }
    }

    suspend fun retryMembers(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.retryMembers(identity, command, batchId, memberIds) }
    }

    suspend fun skipMembers(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.skipMembers(identity, command, batchId, memberIds) }
    }

    suspend fun keepErrors(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.keepError(identity, command, batchId, memberIds) }
    }

    /** Task-level termination; every batch, member fact and prior decision is retained. */
    suspend fun terminate(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationQueueResult = MUTEX.withLock {
        apply(identity.ref) { it.queue.terminate(identity, command) }
    }

    /**
     * Explicit resume of the project's PAUSED task. Uses a deterministic command identity derived
     * from the paused episode so a retry after a crash cannot duplicate the resume. The task only
     * becomes RUNNING when the global slot is free; otherwise it waits as QUEUED.
     */
    suspend fun resumeQueue(ref: LocalProjectRef): TranslationQueueResult = MUTEX.withLock {
        resumeLocked(ref, null)
    }

    /** Same as [resumeQueue] but binds a caller-supplied command identity. */
    suspend fun resumeQueue(ref: LocalProjectRef, command: CommandId): TranslationQueueResult =
        MUTEX.withLock { resumeLocked(ref, command) }

    private suspend fun resumeLocked(ref: LocalProjectRef, command: CommandId?): TranslationQueueResult {
        val stored = store.load(ref)
        val base = stored?.snapshot ?: TranslationQueueSnapshot.empty(ref, now(), limits)
        val active = base.queue.activeTask(ref)
        val paused = active?.takeIf { it.state == TranslationTaskQueueState.PAUSED }
            ?: return TranslationQueueResult.Unchanged(base.queue, active)
        val result = base.queue.resume(paused.identity, command ?: resumeCommand(paused))
        if (result !is TranslationQueueResult.Applied) return result
        val runningElsewhere = store.list().any { other ->
            other.snapshot.ref != ref &&
                other.snapshot.queue.tasks.any { it.state == TranslationTaskQueueState.RUNNING }
        }
        if (runningElsewhere) {
            val next = base.updated(result.queue, paused.identity, nextTimestamp(base))
            val committed = store.compareAndSet(ref, stored, next)
            return TranslationQueueResult.Applied(
                committed.snapshot.queue,
                committed.snapshot.task(paused.identity)!!,
            )
        }
        val dispatched = result.queue.dispatchNext()
        val changed = (dispatched as? TranslationQueueResult.Applied)?.task?.identity ?: paused.identity
        val next = base.updated(dispatched.queue, changed, nextTimestamp(base))
        val committed = store.compareAndSet(ref, stored, next)
        return TranslationQueueResult.Applied(committed.snapshot.queue, committed.snapshot.task(changed)!!)
    }

    private suspend fun apply(
        ref: LocalProjectRef,
        operation: (TranslationQueueSnapshot) -> TranslationQueueResult,
    ): TranslationQueueResult {
        val stored = store.load(ref)
        val base = stored?.snapshot ?: TranslationQueueSnapshot.empty(ref, now(), limits)
        val result = operation(base)
        if (result !is TranslationQueueResult.Applied) return result
        val next = base.updated(result.queue, result.task.identity, nextTimestamp(base))
        val committed = store.compareAndSet(ref, stored, next)
        return TranslationQueueResult.Applied(
            committed.snapshot.queue,
            committed.snapshot.task(result.task.identity)!!,
        )
    }

    /**
     * Selects the global head without relying on store order. A snapshot with any RUNNING task
     * blocks every other project until its outcome is recorded.
     */
    private suspend fun claimNext(
        requested: LocalProjectRef?,
        required: TranslationTaskQueueIdentity?,
    ): TranslationQueueDispatch? {
        val all = store.list()
        if (all.any { stored ->
                stored.snapshot.queue.tasks.any { it.state == TranslationTaskQueueState.RUNNING }
            }
        ) return null
        val candidates = all.mapNotNull { stored ->
            val dispatched = stored.snapshot.queue.dispatchNext()
            if (dispatched !is TranslationQueueResult.Applied) return@mapNotNull null
            val entry = stored.snapshot.entry(dispatched.task.identity) ?: return@mapNotNull null
            Candidate(stored, dispatched.queue, entry)
        }
        val head = candidates.minWithOrNull(CANDIDATE_ORDER) ?: return null
        if (requested != null && head.stored.snapshot.ref != requested) return null
        if (required != null && head.entry.task.identity != required) return null
        val next = head.stored.snapshot.updated(
            queue = head.queue,
            changed = head.entry.task.identity,
            nowMillis = nextTimestamp(head.stored.snapshot),
        )
        val committed = store.compareAndSet(head.stored.snapshot.ref, head.stored, next)
        return TranslationQueueDispatch(
            committed,
            requireNotNull(committed.snapshot.entry(head.entry.task.identity)),
        )
    }

    private fun now(): Long = clock().coerceAtLeast(0)

    private fun nextTimestamp(snapshot: TranslationQueueSnapshot): Long =
        maxOf(now(), snapshot.updatedAtMillis)

    private class Candidate(
        val stored: StoredTranslationQueue,
        val queue: TranslationTaskQueue,
        val entry: TranslationQueueEntry,
    )

    private fun resumeCommand(task: TranslationQueuedTask): CommandId {
        val resumes = task.commands.count { it.action == TranslationTaskCommandAction.RESUME }
        val identity = task.identity
        val material = buildString {
            append(identity.ref.projectId.value); append('\u0000')
            append(identity.ref.instanceId.value); append('\u0000')
            append(identity.ref.generation.value); append('\u0000')
            append(identity.run.runId.value); append('\u0000')
            append(identity.run.commandId.value); append('\u0000')
            append(identity.revision); append('\u0000')
            append(resumes)
        }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material))
        return CommandId("queue-resume-$digest")
    }

    private companion object {
        val MUTEX = Mutex()

        val CANDIDATE_ORDER: Comparator<Candidate> = compareBy<Candidate>(
            { it.entry.meta.enqueuedAtMillis },
            { it.stored.snapshot.ref.projectId.value },
            { it.stored.snapshot.ref.instanceId.value },
            { it.stored.snapshot.ref.generation.value },
            { it.entry.task.sequence },
        )
    }
}
