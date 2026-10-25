package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import com.breakyuna.noveltranslator.nativecore.storage.NativeRunStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationTaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** UI-neutral notification that one queue drain pass observed a terminal task. */
data class TranslationQueueSchedulerEvent(
    val ref: LocalProjectRef,
    val identity: TranslationTaskQueueIdentity?,
    val state: TranslationTaskQueueState?,
)

/**
 * Application-owned adapter for [TranslationQueueScheduler].
 *
 * It binds the durable coordinator to the already existing foreground runner/service, the fixed
 * task review store, the run journal and the usage ledger. The adapter never touches a ViewModel,
 * navigation or the selected visible project; the ViewModel only receives informational events.
 * Pause/stop from a notification reach the same runner and the queue outcome is recorded by the
 * application scope even after the Activity/ViewModel is gone.
 */
class NativeTranslationQueueScheduler(
    private val scope: CoroutineScope,
    private val coordinator: TranslationQueueCoordinator,
    private val runner: ForegroundTranslationRunner,
    private val tasks: NativeTranslationTaskStore,
    private val runs: NativeRunStore,
    private val projects: TextProjectPort,
    private val runtime: RuntimePort,
    private val startService: (String) -> Unit,
) {
    private val mutableEvents = MutableSharedFlow<TranslationQueueSchedulerEvent>(extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()
    private val startLock = Any()
    private var drainJob: Job? = null
    private val adjudicator = TranslationFailureAdjudicator(
        coordinator = coordinator,
        failures = tasks,
        decisions = tasks,
        reviews = tasks,
    )

    private val scheduler = TranslationQueueScheduler(
        coordinator = coordinator,
        launch = TranslationQueueLaunchPort { dispatch, continuation -> launch(dispatch, continuation) },
        outcomes = TranslationTaskOutcomePort { ref, run, skipped -> tasks.outcome(ref, run, skipped) },
        revisions = TranslationQueueRevisionPort { spec -> validateRevision(spec) },
        recovery = TranslationQueueRecoveryPort { dispatch -> evidence(dispatch) },
        control = object : TranslationQueueControlPort {
            override fun pause(identity: TranslationTaskQueueIdentity): Boolean =
                live(identity)?.let { runner.pause(it.token) } == true

            override fun stop(identity: TranslationTaskQueueIdentity): Boolean =
                live(identity)?.let { runner.stop(it.token) } == true
        },
        isBusy = {
            runtime.snapshot().activeRun != null || runner.state.value?.phase?.active == true
        },
        recoverJournal = { runs.recoverInterrupted() },
        onSettled = { dispatch -> publish(dispatch.identity) },
        failures = tasks,
        decisions = tasks,
    )

    /** Coalesced application-scope kick; concurrent enqueues share the active drain loop. */
    fun kick() {
        synchronized(startLock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch {
                try {
                    scheduler.recover()
                    scheduler.drain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Queue conflicts/corrupt snapshots are surfaced by the queue dialog; a
                    // scheduler pass must not crash the application process.
                } finally {
                    synchronized(startLock) { drainJob = null }
                    val snapshot = runner.state.value
                    if (snapshot != null && !snapshot.phase.active) {
                        mutableEvents.tryEmit(TranslationQueueSchedulerEvent(
                            snapshot.request.ref, null, null))
                    }
                }
            }
        }
    }

    suspend fun recover(): List<TranslationQueueRecoveryResult> = scheduler.recover()

    /**
     * Starts a QUEUED task in the application scope so ViewModel destruction cannot orphan the
     * durable queue outcome. The job claims the exact global head before opening any paid work.
     */
    fun start(identity: TranslationTaskQueueIdentity) {
        scope.launch {
            try {
                scheduler.start(identity)
                publish(identity)
                scheduler.drain()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The queue projection stays authoritative; a later kick/recovery can retry.
            }
        }
    }

    suspend fun pause(identity: TranslationTaskQueueIdentity, command: CommandId): TranslationQueueResult =
        scheduler.pause(identity, command).also { publish(identity) }

    suspend fun cancel(identity: TranslationTaskQueueIdentity, command: CommandId): TranslationQueueResult =
        scheduler.cancel(identity, command).also { publish(identity) }

    /** Bounded failure-member facts for review; never source text. */
    suspend fun failureEvidence(
        identity: TranslationTaskQueueIdentity,
        after: Long = 0,
    ): TranslationTaskFailureEvidence = adjudicator.evidence(identity, after)

    suspend fun retryFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        adjudicator.retry(identity, batchId, memberIds, command).also { publish(identity); kick() }

    suspend fun skipFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        adjudicator.skip(identity, batchId, memberIds, command).also { publish(identity); kick() }

    suspend fun keepFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        adjudicator.keepErrors(identity, batchId, memberIds, command).also { publish(identity); kick() }

    suspend fun terminateFailureTask(
        identity: TranslationTaskQueueIdentity,
        command: CommandId,
    ): TranslationFailureAdjudicationResult =
        adjudicator.terminate(identity, command).also { publish(identity); kick() }

    /**
     * Resumes a PAUSED task in the application scope. If the global slot belongs to another
     * project the task stays QUEUED and this job drains once that slot is free.
     */
    fun resume(ref: LocalProjectRef, command: CommandId? = null) {
        scope.launch {
            try {
                scheduler.resume(ref, command)
                scheduler.drain()
                publish(null, ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Durable state remains; the user can retry from the queue dialog.
            }
        }
    }

    private suspend fun publish(
        identity: TranslationTaskQueueIdentity?,
        ref: LocalProjectRef? = identity?.ref,
    ) {
        if (ref == null) return
        if (identity == null) {
            val task = try {
                coordinator.snapshot(ref).queue.activeTask(ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableEvents.tryEmit(TranslationQueueSchedulerEvent(ref, task?.identity, task?.state))
            return
        }
        val task = try {
            coordinator.snapshot(ref).task(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        mutableEvents.tryEmit(TranslationQueueSchedulerEvent(ref, identity, task?.state))
    }

    private fun live(identity: TranslationTaskQueueIdentity): ForegroundTranslationSnapshot? =
        runner.state.value?.takeIf { it.request.identity == identity && it.phase.active && runner.accepts(it.token) }

    /** Reads the narrow run/task facts; RUNNING in this process is live, never "safe to pause". */
    private suspend fun evidence(dispatch: TranslationQueueDispatch): TranslationQueueRecoveryEvidence {
        val current = runner.state.value
        val live = current != null && current.phase.active &&
            current.request.identity == dispatch.identity && runner.accepts(current.token)
        val review = try {
            tasks.review(dispatch.spec.ref, dispatch.spec.run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // No fixed task exists; the run journal remains the only evidence.
            null
        } catch (_: Exception) {
            return TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("NEEDS_REVIEW", 0, 0, 0, 1, UsageSource.UNKNOWN),
                run = null,
                live = live,
            )
        }
        val run = try {
            runs.recent(dispatch.spec.ref, 20).firstOrNull { it.handle.run == dispatch.spec.run }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unreadable journal is uncertain even when the task checkpoint looks intact.
            return TranslationQueueRecoveryEvidence(
                task = review?.let {
                    TranslationQueueTaskEvidence(it.stage, it.committed + it.remaining, it.committed,
                        it.remaining, it.unresolvedRequests, UsageSource.UNKNOWN)
                },
                run = TranslationQueueRunEvidence(
                    StoredRunState.INTERRUPTED, StoredRunStage.REQUESTING, true),
                live = live,
            )
        }
        val taskEvidence = review?.let {
            TranslationQueueTaskEvidence(
                stage = it.stage,
                targetItems = it.committed + it.remaining,
                committedItems = it.committed,
                remainingItems = it.remaining,
                unresolvedDispatches = it.unresolvedRequests,
                usage = run?.translationUsage?.usageSource() ?: UsageSource.UNKNOWN,
            )
        }
        return TranslationQueueRecoveryEvidence(
            task = taskEvidence,
            run = run?.let { TranslationQueueRunEvidence(it.state, it.checkpoint.stage, it.needsReview) },
            live = live || run?.state == StoredRunState.RUNNING ||
                run?.state == StoredRunState.WAITING_APPROVAL,
        )
    }

    /**
     * Revision refresh uses two durable proofs:
     * - an existing fixed-task checkpoint for this exact Run is authoritative at its recorded
     *   revision; continuing that task does not need a new user confirmation;
     * - a task that has not started yet may advance only when the immediately preceding
     *   same-project queue task durably completed at that revision with no unresolved request.
     * Any other advance is refused for review rather than silently reusing queue policy over
     * user edits.
     */
    private suspend fun validateRevision(spec: TranslationTaskSpec): TranslationQueueRevisionDecision {
        val current = projects.open(spec.ref).revision
        if (current == spec.revision) return TranslationQueueRevisionDecision.Current(current)
        if (current < spec.revision) {
            return TranslationQueueRevisionDecision.Refused(current, "project-revision-regressed")
        }
        val taskReview = try {
            tasks.review(spec.ref, spec.run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // No fixed task exists yet; this is a first execution.
            null
        } catch (_: Exception) {
            return TranslationQueueRevisionDecision.Refused(current, "task-checkpoint-unreadable")
        }
        if (taskReview != null) {
            if (taskReview.unresolvedRequests > 0 ||
                taskReview.stage in setOf("FAILED", "NEEDS_REVIEW", "CANCELLED")
            ) {
                return TranslationQueueRevisionDecision.Refused(current, "task-checkpoint-uncertain")
            }
            return if (taskReview.revision == current) {
                TranslationQueueRevisionDecision.SafeAdvance(current, "task-checkpoint")
            } else {
                TranslationQueueRevisionDecision.Refused(current, "task-checkpoint-revision-mismatch")
            }
        }
        val snapshot = coordinator.snapshot(spec.ref)
        val index = snapshot.entries.indexOfFirst { it.spec.queueIdentity() == spec.queueIdentity() }
        val previous = if (index > 0) {
            snapshot.entries.take(index).lastOrNull {
                it.spec.ref == spec.ref && it.task.state.terminal &&
                    it.task.state != TranslationTaskQueueState.CANCELLED
            }
        } else null
        if (previous != null && previous.spec.revision <= spec.revision) {
            val review = try {
                tasks.review(spec.ref, previous.spec.run)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val target = review?.let { it.committed + it.remaining }
            val accounted = review?.let { it.committed + previous.task.skippedItemIds.size }
            if (review != null && review.revision == current && review.unresolvedRequests == 0 &&
                target != null && accounted == target &&
                (review.stage == "COMPLETE" || previous.task.state == TranslationTaskQueueState.COMPLETED_WITH_ERRORS)
            ) {
                return TranslationQueueRevisionDecision.SafeAdvance(current, "previous-queue-task-complete")
            }
        }
        return TranslationQueueRevisionDecision.Refused(current, "project-revision-unproven")
    }

    private suspend fun launch(
        dispatch: TranslationQueueDispatch,
        continuation: Boolean,
    ): TranslationQueueLaunchResult {
        val active = runtime.snapshot().activeRun
        if (active != null) {
            // The scheduler normally never claims while busy. If another reservation appears in
            // the claim-to-launch window, record a safe pause instead of forcing a paid request.
            return TranslationQueueLaunchResult(ForegroundTranslationPhase.PAUSED)
        }
        val spec = dispatch.executionSpec
        // Close the claim-to-reservation window: an unobserved project write must not turn the
        // frozen revision into a permanent task failure. Pause for explicit resume/revalidation.
        val latest = try {
            projects.open(spec.ref).revision
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (latest != null && latest != spec.revision) {
            return TranslationQueueLaunchResult(ForegroundTranslationPhase.PAUSED)
        }
        val handle = try {
            runner.prepare(ForegroundTranslationRequest(
                ref = spec.ref,
                identity = spec.run,
                configuration = spec.configuration,
                expectedRevision = spec.revision,
                afterId = spec.afterId,
                pageSize = spec.pageSize,
                scope = if (spec.wholeProject) TranslationScope.PROJECT else TranslationScope.PAGE,
                options = spec.options,
                continuation = continuation,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeUnavailable) {
            return TranslationQueueLaunchResult(ForegroundTranslationPhase.PAUSED)
        } catch (_: Exception) {
            return TranslationQueueLaunchResult(ForegroundTranslationPhase.FAILED)
        }
        try {
            val queueState = coordinator.snapshot(spec.ref).task(dispatch.identity)?.state
            if (queueState != TranslationTaskQueueState.RUNNING) {
                runner.stop(handle.token)
                return TranslationQueueLaunchResult(ForegroundTranslationPhase.CANCELLED, handle.token)
            }
            startService(handle.token)
        } catch (cancelled: CancellationException) {
            runner.stop(handle.token)
            throw cancelled
        } catch (_: Exception) {
            runner.startFailed(handle.token)
        }
        try {
            handle.await()
        } catch (_: ForegroundTranslationPaused) {
            // The runner records the durable PAUSED journal before completing exceptionally.
        } catch (cancelled: CancellationException) {
            // A cancelled application scope must not be reported as a business outcome; the
            // runner keeps its own durable cleanup path.
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // The terminal phase below carries the classified outcome.
        }
        val phase = runner.state.value?.takeIf { it.token == handle.token }?.phase
        return TranslationQueueLaunchResult(
            if (phase == null || phase.active) ForegroundTranslationPhase.FAILED else phase,
            handle.token,
        )
    }
}
