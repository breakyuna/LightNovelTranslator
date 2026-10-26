package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQueueSchedulerTest {
    private val projectA = ref("project-a", "instance-a")
    private val projectB = ref("project-b", "instance-b")
    private val configuration = ModelConfigurationRef("model", 3)

    @Test
    fun drainRunsTheGlobalHeadAcrossProjectsAndKicksTheNextTask() = runBlocking {
        val fixture = Fixture()
        val a1 = spec(projectA, "run-a1")
        val b1 = spec(projectB, "run-b1")
        fixture.coordinator.enqueue(a1, a1.run.commandId)
        fixture.coordinator.enqueue(b1, b1.run.commandId)
        fixture.outcomes.evidence = complete(committed = 1, target = 1)

        assertEquals(2, fixture.scheduler.drain())
        assertEquals(listOf("run-a1", "run-b1"), fixture.launcher.runs)
        assertEquals(TranslationTaskQueueState.COMPLETED,
            fixture.store.queues.getValue(projectA).snapshot.task(a1.queueIdentity())!!.state)
        assertEquals(TranslationTaskQueueState.COMPLETED,
            fixture.store.queues.getValue(projectB).snapshot.task(b1.queueIdentity())!!.state)
        assertNull(fixture.coordinator.nextRunnable())
    }

    @Test
    fun pauseReachesTheLiveRunnerBeforeTheQueueOutcomeAndLateSuccessCannotReopenIt() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-pause")
        fixture.coordinator.enqueue(task, task.run.commandId)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.launcher.block = { started.complete(Unit); release.await() }
        fixture.launcher.result = TranslationQueueLaunchResult(ForegroundTranslationPhase.PAUSED)
        val drain = launch { fixture.scheduler.drain() }
        started.await()

        val paused = fixture.scheduler.pause(task.queueIdentity(), CommandId("pause-user"))
        assertTrue(paused is TranslationQueueResult.Applied)
        assertEquals(listOf(task.queueIdentity()), fixture.controls.paused)
        assertEquals(TranslationTaskQueueState.PAUSED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)

        release.complete(Unit)
        drain.join()
        assertEquals(TranslationTaskQueueState.PAUSED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.commands.size)
    }

    @Test
    fun notificationStylePauseAndCancelAreRecordedWithoutAViewModelCommand() = runBlocking {
        val fixture = Fixture()
        val pausedTask = spec(projectA, "run-notify-pause")
        fixture.coordinator.enqueue(pausedTask, pausedTask.run.commandId)
        fixture.launcher.result = TranslationQueueLaunchResult(ForegroundTranslationPhase.PAUSED)
        assertEquals(1, fixture.scheduler.drain())
        assertEquals(TranslationTaskQueueState.PAUSED,
            fixture.store.queues.getValue(projectA).snapshot.task(pausedTask.queueIdentity())!!.state)

        val cancelledTask = spec(projectB, "run-notify-cancel")
        fixture.coordinator.enqueue(cancelledTask, cancelledTask.run.commandId)
        fixture.launcher.result = TranslationQueueLaunchResult(ForegroundTranslationPhase.CANCELLED)
        assertEquals(1, fixture.scheduler.drain())
        assertEquals(TranslationTaskQueueState.CANCELLED,
            fixture.store.queues.getValue(projectB).snapshot.task(cancelledTask.queueIdentity())!!.state)
    }

    @Test
    fun explicitStartOnlyClaimsTheDeterministicHeadAndLeavesLaterTasksQueued() = runBlocking {
        val fixture = Fixture()
        val a1 = spec(projectA, "run-a1")
        val a2 = spec(projectA, "run-a2")
        fixture.coordinator.enqueue(a1, a1.run.commandId)
        fixture.coordinator.enqueue(a2, a2.run.commandId)
        fixture.outcomes.evidence = complete(committed = 1, target = 1)

        assertTrue(fixture.scheduler.start(a1.queueIdentity()) is TranslationQueueResult.Applied)
        assertEquals(listOf("run-a1"), fixture.launcher.runs)
        assertEquals(TranslationTaskQueueState.QUEUED,
            fixture.store.queues.getValue(projectA).snapshot.task(a2.queueIdentity())!!.state)
        assertEquals(1, fixture.scheduler.drain())
        assertEquals(listOf("run-a1", "run-a2"), fixture.launcher.runs)
    }

    @Test
    fun cancelStopsTheLiveRunnerBeforeRecordingTheTerminalOutcome() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-cancel")
        fixture.coordinator.enqueue(task, task.run.commandId)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.launcher.block = { started.complete(Unit); release.await() }
        fixture.launcher.result = TranslationQueueLaunchResult(ForegroundTranslationPhase.CANCELLED)
        val drain = launch { fixture.scheduler.drain() }
        started.await()

        val cancelled = fixture.scheduler.cancel(task.queueIdentity(), CommandId("cancel-user"))
        assertTrue(cancelled is TranslationQueueResult.Applied)
        assertEquals(listOf(task.queueIdentity()), fixture.controls.stopped)
        assertEquals(TranslationTaskQueueState.CANCELLED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)

        release.complete(Unit)
        drain.join()
        assertEquals(TranslationTaskQueueState.CANCELLED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
    }

    @Test
    fun pauseDuringRevisionValidationPreventsThePaidLaunchEntirely() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-pause-before-launch")
        fixture.coordinator.enqueue(task, task.run.commandId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.revisions.entered = entered
        fixture.revisions.gate = release
        val drain = launch { fixture.scheduler.drain() }
        entered.await()

        val paused = fixture.scheduler.pause(task.queueIdentity(), CommandId("pause-before-launch"))
        assertTrue(paused is TranslationQueueResult.Applied)
        release.complete(Unit)
        drain.join()
        assertTrue("No paid launch may start after the queue was paused", fixture.launcher.calls.isEmpty())
        assertEquals(TranslationTaskQueueState.PAUSED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
    }

    @Test
    fun sameProjectFollowUpUsesAnAuditedSafeRevisionAdvance() = runBlocking {
        val fixture = Fixture()
        val a1 = spec(projectA, "run-a1", revision = 4)
        val a2 = spec(projectA, "run-a2", revision = 4)
        fixture.coordinator.enqueue(a1, a1.run.commandId)
        fixture.coordinator.enqueue(a2, a2.run.commandId)
        fixture.outcomes.evidence = complete(committed = 1, target = 1)
        fixture.revisions.decisions = { spec ->
            if (spec.run.runId.value == "run-a2") TranslationQueueRevisionDecision.SafeAdvance(9, "prior-queue-run")
            else TranslationQueueRevisionDecision.Current(spec.revision)
        }

        assertEquals(2, fixture.scheduler.drain())
        assertEquals(listOf("run-a1" to 4L, "run-a2" to 9L), fixture.launcher.calls)
        val followUp = fixture.store.queues.getValue(projectA).snapshot.task(a2.queueIdentity())!!
        assertEquals(4L, followUp.identity.revision)
        assertEquals(9L, followUp.executionRevision)
        assertEquals(TranslationTaskCommandAction.REVALIDATE_REVISION, followUp.commands.single().action)
    }

    @Test
    fun unprovenRevisionAdvanceRefusesDispatchAndStopsForReview() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-stale", revision = 4)
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.revisions.decisions = {
            TranslationQueueRevisionDecision.Refused(9, "user-edits")
        }

        assertEquals(1, fixture.scheduler.drain())
        assertTrue(fixture.launcher.calls.isEmpty())
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskCommandAction.REFUSE_REVISION, saved.commands.single().action)
    }

    @Test
    fun skippedMembersProduceCompletedWithErrorsFromDurableEvidence() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-skipped")
        fixture.coordinator.enqueue(task, task.run.commandId)
        requireNotNull(fixture.coordinator.nextRunnable())
        val batch = TranslationTaskFailureBatch(
            TranslationTaskFailureBatchId("batch-1"),
            listOf(TranslationFailureMember(ItemId(1), ObjectFingerprint("a".repeat(64)),
                ModelFailureKind.NETWORK, TranslationFailureReceipt.RECEIVED)),
        )
        fixture.coordinator.recordFailureBatch(task.queueIdentity(), batch)
        fixture.coordinator.skipMembers(task.queueIdentity(), CommandId("skip-1"), batch.id, listOf(ItemId(1)))
        fixture.outcomes.evidence = complete(committed = 1, skipped = 1, target = 2)

        assertEquals(1, fixture.scheduler.drain())
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.COMPLETED_WITH_ERRORS, saved.state)
        assertEquals(setOf(ItemId(1)), fixture.outcomes.lastSkipped)
    }

    @Test
    fun unknownUsageOrUnaccountedTargetsNeverBecomeCompletion() = runBlocking {
        for (evidence in listOf(
            complete(committed = 1, target = 1).copy(usage = UsageSource.UNKNOWN),
            complete(committed = 1, skipped = 1, target = 3),
        )) {
            val fixture = Fixture()
            val task = spec(projectA, "run-unproven")
            fixture.coordinator.enqueue(task, task.run.commandId)
            if (evidence.skippedItems > 0) {
                requireNotNull(fixture.coordinator.nextRunnable())
                val batch = TranslationTaskFailureBatch(
                    TranslationTaskFailureBatchId("batch-1"),
                    listOf(TranslationFailureMember(ItemId(1), ObjectFingerprint("a".repeat(64)),
                        ModelFailureKind.NETWORK, TranslationFailureReceipt.RECEIVED)),
                )
                fixture.coordinator.recordFailureBatch(task.queueIdentity(), batch)
                fixture.coordinator.skipMembers(task.queueIdentity(), CommandId("skip-1"), batch.id, listOf(ItemId(1)))
            }
            fixture.outcomes.evidence = evidence
            assertEquals(1, fixture.scheduler.drain())
            assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
                fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        }
    }

    @Test
    fun restartRecoveryUsesDurableEvidenceAndNeverRedispatches() = runBlocking {
        val safe = runningFixture("run-safe")
        safe.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("READY", 2, 1, 1, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(safe.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.PAUSED)
        assertEquals(TranslationTaskQueueState.PAUSED,
            safe.store.queues.getValue(projectA).snapshot.task(safe.task.queueIdentity())!!.state)
        assertTrue(safe.launcher.calls.isEmpty())

        val uncertain = runningFixture("run-uncertain")
        uncertain.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("REQUESTING", 2, 1, 1, 1, UsageSource.UNKNOWN),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.REQUESTING, true),
            )
        }
        assertTrue(uncertain.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.NEEDS_REVIEW)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            uncertain.store.queues.getValue(projectA).snapshot.task(uncertain.task.queueIdentity())!!.state)
        assertTrue(uncertain.launcher.calls.isEmpty())

        val failed = runningFixture("run-failed")
        failed.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("FAILED", 2, 1, 1, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(failed.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.FAILED)
        assertEquals(TranslationTaskQueueState.FAILED,
            failed.store.queues.getValue(projectA).snapshot.task(failed.task.queueIdentity())!!.state)

        val live = runningFixture("run-live")
        live.recovery.provider = {
            TranslationQueueRecoveryEvidence(null, null, live = true)
        }
        assertTrue(live.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.LIVE)
        assertEquals(TranslationTaskQueueState.RUNNING,
            live.store.queues.getValue(projectA).snapshot.task(live.task.queueIdentity())!!.state)
        assertEquals(1, live.recoveredJournals)

        val noEvidence = runningFixture("run-no-evidence")
        assertTrue(noEvidence.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.PAUSED)
        assertEquals(TranslationTaskQueueState.PAUSED,
            noEvidence.store.queues.getValue(projectA).snapshot.task(noEvidence.task.queueIdentity())!!.state)
        assertTrue(noEvidence.launcher.calls.isEmpty())

        val committing = runningFixture("run-committing")
        committing.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMMITTING", 2, 1, 1, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(committing.scheduler.recover().single().verdict ==
            TranslationQueueRecoveryVerdict.NEEDS_REVIEW)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            committing.store.queues.getValue(projectA).snapshot.task(committing.task.queueIdentity())!!.state)
    }

    @Test
    fun cancelledJournalRecoversToCancelledAndZeroSkipCompletionFinalizesTheTask() = runBlocking {
        val cancelled = runningFixture("run-cancelled-journal")
        cancelled.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("READY", 2, 1, 1, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.CANCELLED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(cancelled.scheduler.recover().single().verdict == TranslationQueueRecoveryVerdict.CANCELLED)
        assertEquals(TranslationTaskQueueState.CANCELLED,
            cancelled.store.queues.getValue(projectA).snapshot.task(cancelled.task.queueIdentity())!!.state)
        assertTrue(cancelled.launcher.calls.isEmpty())

        val completed = Fixture()
        val task = spec(projectA, "run-zero-skip")
        completed.coordinator.enqueue(task, task.run.commandId)
        completed.outcomes.evidence = complete(committed = 1, target = 1).copy(stage = "READY")
        assertEquals(1, completed.scheduler.drain())
        assertEquals(listOf(emptySet<ItemId>() to completionCommandForTest(task)),
            completed.decisions.completed)
    }

    @Test
    fun settledTaskIsCompletedFromPersistedEvidenceBeforeRecoveryWouldFreezeReview() = runBlocking {
        val committing = runningFixture("run-settled-committing")
        committing.outcomes.launched.add(committing.task.run.runId.value)
        committing.outcomes.evidence = complete(committed = 1, target = 1).copy(stage = "COMMITTING")
        committing.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMMITTING", 1, 1, 0, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(committing.scheduler.recover().isEmpty())
        assertEquals(TranslationTaskQueueState.COMPLETED,
            committing.store.queues.getValue(projectA).snapshot.task(committing.task.queueIdentity())!!.state)
        assertEquals(1, committing.decisions.completed.size)
        assertTrue(committing.launcher.calls.isEmpty())

        val completed = runningFixture("run-settled-complete")
        completed.outcomes.launched.add(completed.task.run.runId.value)
        completed.outcomes.evidence = complete(committed = 1, target = 1).copy(stage = "COMPLETE")
        completed.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMPLETE", 1, 1, 0, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.SUCCEEDED, StoredRunStage.COMMITTED, false),
            )
        }
        assertTrue(completed.scheduler.recover().isEmpty())
        assertEquals(TranslationTaskQueueState.COMPLETED,
            completed.store.queues.getValue(projectA).snapshot.task(completed.task.queueIdentity())!!.state)
        assertTrue(completed.decisions.completed.isEmpty())
        assertTrue(completed.launcher.calls.isEmpty())
    }

    @Test
    fun insufficientSettledEvidenceStaysInReviewAndNeverLaunches() = runBlocking {
        val fixture = runningFixture("run-unproven-settled")
        fixture.outcomes.launched.add(fixture.task.run.runId.value)
        fixture.outcomes.evidence = complete(committed = 1, target = 1)
            .copy(stage = "COMMITTING", usage = UsageSource.UNKNOWN)
        fixture.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMMITTING", 1, 1, 0, 0, UsageSource.UNKNOWN),
                run = TranslationQueueRunEvidence(StoredRunState.INTERRUPTED, StoredRunStage.COMMITTED, false),
            )
        }

        assertTrue(fixture.scheduler.recover().single().verdict ==
            TranslationQueueRecoveryVerdict.NEEDS_REVIEW)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            fixture.store.queues.getValue(projectA).snapshot.task(fixture.task.queueIdentity())!!.state)
        assertTrue(fixture.decisions.completed.isEmpty())
        assertTrue(fixture.launcher.calls.isEmpty())

        val missingRun = runningFixture("run-missing-journal")
        missingRun.outcomes.launched.add(missingRun.task.run.runId.value)
        missingRun.outcomes.evidence = complete(committed = 1, target = 1).copy(stage = "COMPLETE")
        missingRun.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMPLETE", 1, 1, 0, 0, UsageSource.ACTUAL),
                run = null,
            )
        }
        assertTrue(missingRun.scheduler.recover().single().verdict ==
            TranslationQueueRecoveryVerdict.NEEDS_REVIEW)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            missingRun.store.queues.getValue(projectA).snapshot.task(missingRun.task.queueIdentity())!!.state)
        assertTrue(missingRun.decisions.completed.isEmpty())
        assertTrue(missingRun.launcher.calls.isEmpty())
    }

    @Test
    fun explicitCancelledRunOutranksCommittingAndKeepsGenuineUncertaintyInReview() = runBlocking {
        val cancelled = runningFixture("run-cancelled-commit")
        cancelled.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMMITTING", 1, 0, 1, 0, UsageSource.ACTUAL),
                run = TranslationQueueRunEvidence(StoredRunState.CANCELLED, StoredRunStage.COMMITTING, false),
            )
        }
        assertTrue(cancelled.scheduler.recover().single().verdict ==
            TranslationQueueRecoveryVerdict.CANCELLED)
        assertEquals(TranslationTaskQueueState.CANCELLED,
            cancelled.store.queues.getValue(projectA).snapshot.task(cancelled.task.queueIdentity())!!.state)

        val uncertain = runningFixture("run-cancelled-uncertain")
        uncertain.recovery.provider = {
            TranslationQueueRecoveryEvidence(
                task = TranslationQueueTaskEvidence("COMMITTING", 1, 0, 1, 0, UsageSource.UNKNOWN),
                run = TranslationQueueRunEvidence(StoredRunState.CANCELLED, StoredRunStage.COMMITTING, true),
            )
        }
        assertTrue(uncertain.scheduler.recover().single().verdict ==
            TranslationQueueRecoveryVerdict.NEEDS_REVIEW)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            uncertain.store.queues.getValue(projectA).snapshot.task(uncertain.task.queueIdentity())!!.state)
    }

    @Test
    fun persistedDecisionIsReplayedExactlyOnceAndChangedEvidenceIsNotReplayed() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-persisted-decision")
        fixture.coordinator.enqueue(task, task.run.commandId)
        requireNotNull(fixture.coordinator.nextRunnable())
        val batch = TranslationTaskFailureBatch(
            TranslationTaskFailureBatchId("batch-replay"),
            listOf(member(1)),
        )
        fixture.coordinator.recordFailureBatch(task.queueIdentity(), batch)
        fixture.decisionJournal.decisions.add(TranslationQueueTaskDecision(
            CommandId("retry-persisted"), TranslationTaskCommandAction.RETRY_MEMBERS, batch.id,
            listOf(ItemId(1)), listOf(ObjectFingerprint("a".repeat(64))), 1,
        ))

        assertEquals(1, fixture.scheduler.reconcileDecisions())
        var saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.QUEUED, saved.state)
        assertEquals(listOf("retry-persisted"), saved.commands.map { it.commandId.value })

        assertEquals(0, fixture.scheduler.reconcileDecisions())
        saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(1, saved.commands.size)
        assertTrue(fixture.launcher.calls.isEmpty())

        val changed = Fixture()
        val changedTask = spec(projectA, "run-changed-decision")
        changed.coordinator.enqueue(changedTask, changedTask.run.commandId)
        requireNotNull(changed.coordinator.nextRunnable())
        changed.coordinator.recordFailureBatch(changedTask.queueIdentity(), batch)
        changed.decisionJournal.decisions.add(TranslationQueueTaskDecision(
            CommandId("retry-changed"), TranslationTaskCommandAction.RETRY_MEMBERS, batch.id,
            listOf(ItemId(1)), listOf(ObjectFingerprint("b".repeat(64))), 1,
        ))
        assertEquals(0, changed.scheduler.reconcileDecisions())
        val unchanged = changed.store.queues.getValue(projectA).snapshot.task(changedTask.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, unchanged.state)
        assertTrue(unchanged.commands.isEmpty())
    }

    @Test
    fun queuedTaskWithPersistedTerminateIsCancelledBeforeDispatch() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-queued-terminate")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.decisionJournal.decisions.add(TranslationQueueTaskDecision(
            CommandId("terminate-persisted"), TranslationTaskCommandAction.TERMINATE_TASK,
            null, emptyList(), emptyList(), 1,
        ))

        assertEquals(1, fixture.scheduler.reconcileDecisions())
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.CANCELLED, saved.state)
        assertTrue(fixture.launcher.calls.isEmpty())
    }

    private fun completionCommandForTest(task: TranslationTaskSpec): CommandId {
        val material = buildString {
            append(task.run.commandId.value); append('\u0000')
        }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material))
        return CommandId("queue-complete-errors-$digest")
    }

    @Test
    fun aBusyRuntimeRefusesToClaimWithoutCreatingAnotherRun() = runBlocking {
        val fixture = Fixture(busy = true)
        val task = spec(projectA, "run-busy")
        fixture.coordinator.enqueue(task, task.run.commandId)
        assertEquals(0, fixture.scheduler.drain())
        assertTrue(fixture.launcher.calls.isEmpty())
        assertEquals(TranslationTaskQueueState.QUEUED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
    }

    @Test
    fun runEndingWithItemFailuresRecordsExactlyOneBatchAndNeedsMemberReview() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-member-failure")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 1)
        fixture.failures.members.add(member(1))

        assertEquals(1, fixture.scheduler.drain())
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, saved.state)
        assertEquals(1, saved.batches.size)
        assertEquals(listOf(ItemId(1)), saved.batches.single().members.map { it.itemId })
        assertEquals(TranslationFailureReceipt.RECEIVED, saved.batches.single().members.single().receipt)
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.queue.tasks.size)
        assertEquals(listOf("run-member-failure"), fixture.launcher.runs)

        // A second scheduler pass cannot append another batch for the same stopped run.
        assertEquals(0, fixture.scheduler.drain())
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.batches.size)
    }

    @Test
    fun itemFailureSettlementIsBoundedToTwentyMembersInOneBatch() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-bounded-failure")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 25)
        repeat(25) { index -> fixture.failures.members.add(member((index + 1).toLong())) }

        assertEquals(1, fixture.scheduler.drain())
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, saved.state)
        assertEquals(1, saved.batches.size)
        assertEquals(TranslationTaskFailureBatch.MAX_MEMBERS, saved.batches.single().members.size)
        assertEquals((1L..20L).map { ItemId(it) }, saved.batches.single().members.map { it.itemId })
    }

    private fun adjudicator(fixture: Fixture) = TranslationFailureAdjudicator(
        coordinator = fixture.coordinator,
        failures = fixture.failures,
        decisions = fixture.decisions,
    )

    @Test
    fun memberRetryReusesTheSameTaskAndNeverCreatesANewRunOrTask() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-retry-same")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 1)
        fixture.failures.members.add(member(1))
        assertEquals(1, fixture.scheduler.drain())
        val failed = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        val batch = failed.batches.single()

        val retried = adjudicator(fixture).retry(
            task.queueIdentity(), batch.id, listOf(ItemId(1)), CommandId("retry-member"))
        val applied = retried.queue as TranslationQueueResult.Applied
        assertEquals(TranslationTaskQueueState.QUEUED, applied.task.state)
        assertEquals(failed.sequence, applied.task.sequence)
        assertEquals(task.queueIdentity(), applied.task.identity)
        assertEquals(TranslationTaskCommandAction.RETRY_MEMBERS, applied.task.commands.single().action)
        assertEquals(0, retried.receipt!!.retryBudget)
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.queue.tasks.size)

        fixture.failures.members.clear()
        fixture.outcomes.evidence = complete(committed = 0, target = 1)
        fixture.launcher.onLaunch = { run ->
            fixture.outcomes.launched.add(run)
            fixture.outcomes.evidence = complete(committed = 1, target = 1)
        }
        assertEquals(1, fixture.scheduler.drain())
        assertEquals(TranslationTaskQueueState.COMPLETED,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        assertEquals(listOf("run-retry-same", "run-retry-same"), fixture.launcher.runs)
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.queue.tasks.size)
    }

    @Test
    fun skippingEveryFailedMemberCompletesWithErrorsWithoutANewPaidLaunch() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-skip-member")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 2)
        fixture.failures.members.add(member(1))
        assertEquals(1, fixture.scheduler.drain())
        val batch = fixture.store.queues.getValue(projectA).snapshot
            .task(task.queueIdentity())!!.batches.single()

        val skipped = adjudicator(fixture).skip(
            task.queueIdentity(), batch.id, listOf(ItemId(1)), CommandId("skip-member"))
        assertEquals(TranslationTaskQueueState.QUEUED,
            (skipped.queue as TranslationQueueResult.Applied).task.state)
        fixture.failures.members.clear()
        fixture.outcomes.evidence = complete(committed = 1, skipped = 1, target = 2)
        assertEquals(1, fixture.scheduler.drain())
        assertEquals(TranslationTaskQueueState.COMPLETED_WITH_ERRORS,
            fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        assertEquals(1, fixture.launcher.runs.size)
    }

    @Test
    fun uncertainReceiptCanOnlyBeKeptOrTerminatedAndNeverRetried() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-uncertain-member")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 1)
        fixture.failures.members.add(member(1, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT))
        assertEquals(1, fixture.scheduler.drain())
        val batch = fixture.store.queues.getValue(projectA).snapshot
            .task(task.queueIdentity())!!.batches.single()

        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            (adjudicator(fixture).retry(task.queueIdentity(), batch.id, listOf(ItemId(1)),
                CommandId("retry-uncertain")).queue as TranslationQueueResult.Rejected).reason,
        )
        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            (adjudicator(fixture).skip(task.queueIdentity(), batch.id, listOf(ItemId(1)),
                CommandId("skip-uncertain")).queue as TranslationQueueResult.Rejected).reason,
        )
        val kept = adjudicator(fixture).keepErrors(
            task.queueIdentity(), batch.id, listOf(ItemId(1)), CommandId("keep-uncertain"))
        assertEquals(TranslationTaskQueueState.FAILED,
            (kept.queue as TranslationQueueResult.Applied).task.state)
        val terminated = adjudicator(fixture).terminate(task.queueIdentity(), CommandId("terminate-uncertain"))
        assertEquals(TranslationTaskQueueState.CANCELLED,
            (terminated.queue as TranslationQueueResult.Applied).task.state)
        assertEquals(1, fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.batches.size)
    }

    @Test
    fun staleForeignDuplicateAndPartialSelectionsFailClosed() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-validation")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 2)
        fixture.failures.members.add(member(1))
        fixture.failures.members.add(member(2))
        assertEquals(1, fixture.scheduler.drain())
        val batch = fixture.store.queues.getValue(projectA).snapshot
            .task(task.queueIdentity())!!.batches.single()
        val adjudicator = adjudicator(fixture)

        assertEquals(
            TranslationQueueRejection.MEMBER_NOT_IN_BATCH,
            (adjudicator.skip(task.queueIdentity(), batch.id, listOf(ItemId(99)),
                CommandId("foreign-member")).queue as TranslationQueueResult.Rejected).reason,
        )
        val partial = adjudicator.skip(task.queueIdentity(), batch.id, listOf(ItemId(1)),
            CommandId("skip-partial"))
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW,
            (partial.queue as TranslationQueueResult.Applied).task.state)
        val duplicate = adjudicator.skip(task.queueIdentity(), batch.id, listOf(ItemId(1)),
            CommandId("skip-partial"))
        assertTrue(duplicate.queue is TranslationQueueResult.Duplicate)
        assertEquals(
            TranslationQueueRejection.COMMAND_CONFLICT,
            (adjudicator.skip(task.queueIdentity(), batch.id, listOf(ItemId(2)),
                CommandId("skip-partial")).queue as TranslationQueueResult.Rejected).reason,
        )
        val retried = adjudicator.retry(task.queueIdentity(), batch.id, listOf(ItemId(2)),
            CommandId("retry-other"))
        assertEquals(TranslationTaskQueueState.QUEUED,
            (retried.queue as TranslationQueueResult.Applied).task.state)
    }

    @Test
    fun changedPersistedFingerprintRefusesTheSelectionBeforeAnyQueueCommand() = runBlocking {
        val fixture = Fixture()
        val task = spec(projectA, "run-stale-evidence")
        fixture.coordinator.enqueue(task, task.run.commandId)
        fixture.outcomes.evidence = complete(committed = 0, target = 1)
        fixture.failures.members.add(member(1))
        assertEquals(1, fixture.scheduler.drain())
        val batch = fixture.store.queues.getValue(projectA).snapshot
            .task(task.queueIdentity())!!.batches.single()
        fixture.failures.members[0] = member(1, fingerprint = "b".repeat(64))

        val stale = adjudicator(fixture).retry(task.queueIdentity(), batch.id, listOf(ItemId(1)),
            CommandId("retry-stale"))
        assertEquals(TranslationQueueRejection.INVALID_EVIDENCE,
            (stale.queue as TranslationQueueResult.Rejected).reason)
        val saved = fixture.store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, saved.state)
        assertEquals(0, saved.decisions.size)
        assertEquals(batch.members, saved.batch(batch.id)!!.members)
    }

    private fun member(
        id: Long,
        receipt: TranslationFailureReceipt = TranslationFailureReceipt.RECEIVED,
        fingerprint: String = "a".repeat(64),
    ): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint(fingerprint),
        failure = ModelFailureKind.NETWORK,
        receipt = receipt,
        usage = if (receipt == TranslationFailureReceipt.RECEIVED) UsageSource.ACTUAL else UsageSource.UNKNOWN,
    )

    private suspend fun runningFixture(run: String): RecoveryFixture {
        val fixture = Fixture()
        val task = spec(projectA, run)
        fixture.coordinator.enqueue(task, task.run.commandId)
        requireNotNull(fixture.coordinator.nextRunnable())
        return RecoveryFixture(fixture, task)
    }

    private class RecoveryFixture(val fixture: Fixture, val task: TranslationTaskSpec) {
        val scheduler get() = fixture.scheduler
        val store get() = fixture.store
        val launcher get() = fixture.launcher
        val recovery get() = fixture.recovery
        val outcomes get() = fixture.outcomes
        val decisions get() = fixture.decisions
        val recoveredJournals get() = fixture.recoveredJournals
    }

    private class Fixture(busy: Boolean = false) {
        val store = MemoryStore()
        var now = 1_000L
        val coordinator = TranslationQueueCoordinator(store, clock = { now++ })
        val outcomes = FakeOutcomes()
        val launcher = FakeLauncher().also { it.onLaunch = { run -> outcomes.launched.add(run) } }
        val revisions = FakeRevisions()
        val recovery = FakeRecovery()
        val controls = FakeControls()
        val failures = FakeFailures()
        val decisions = FakeDecisions()
        val decisionJournal = FakeDecisionJournal()
        var recoveredJournals = 0
        val scheduler = TranslationQueueScheduler(
            coordinator = coordinator,
            launch = launcher,
            outcomes = outcomes,
            revisions = revisions,
            recovery = recovery,
            decisionJournal = decisionJournal,
            control = controls,
            isBusy = { busy },
            recoverJournal = { recoveredJournals++ },
            failures = failures,
            decisions = decisions,
        )
    }

    private class FakeLauncher : TranslationQueueLaunchPort {
        val runs = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, Long>>()
        var result = TranslationQueueLaunchResult(ForegroundTranslationPhase.SUCCEEDED)
        var block: (suspend () -> Unit)? = null
        var onLaunch: ((String) -> Unit)? = null
        override suspend fun launch(
            dispatch: TranslationQueueDispatch,
            continuation: Boolean,
        ): TranslationQueueLaunchResult {
            runs.add(dispatch.spec.run.runId.value)
            calls.add(dispatch.spec.run.runId.value to dispatch.executionSpec.revision)
            onLaunch?.invoke(dispatch.spec.run.runId.value)
            block?.invoke()
            return result
        }
    }

    private class FakeOutcomes : TranslationTaskOutcomePort {
        val launched = mutableSetOf<String>()
        var evidence: TranslationTaskOutcomeEvidence =
            TranslationTaskOutcomeEvidence("COMPLETE", 1, 1, 0, 1, 0, UsageSource.ACTUAL)
        var lastSkipped: Set<ItemId>? = null
        override suspend fun outcome(
            ref: LocalProjectRef,
            run: RunIdentity,
            skippedItemIds: Set<ItemId>,
        ): TranslationTaskOutcomeEvidence {
            lastSkipped = skippedItemIds
            return if (run.runId.value in launched) evidence else
                TranslationTaskOutcomeEvidence("READY", 1, 0, 0, 0, 0, UsageSource.UNKNOWN)
        }
    }

    private class FakeFailures : TranslationTaskFailureEvidencePort {
        val members = mutableListOf<TranslationFailureMember>()
        override suspend fun failures(
            ref: LocalProjectRef,
            run: RunIdentity,
            after: Long,
        ): TranslationTaskFailureEvidence {
            val remaining = members.filter { it.itemId.value > after }.sortedBy { it.itemId.value }
            val page = remaining.take(TranslationTaskFailureBatch.MAX_MEMBERS)
            val next = if (remaining.size > TranslationTaskFailureBatch.MAX_MEMBERS) {
                page.last().itemId.value
            } else null
            return TranslationTaskFailureEvidence(
                review = TranslationTaskReview(
                    ref = ref,
                    run = run,
                    stage = "FAILED",
                    revision = 4,
                    committed = 0,
                    remaining = members.size,
                    requests = 1,
                    unresolvedRequests = 0,
                    fingerprint = ObjectFingerprint("e".repeat(64)),
                ),
                projectRevision = 4,
                members = page,
                next = next,
            )
        }

        override suspend fun failureMembers(
            ref: LocalProjectRef,
            run: RunIdentity,
            itemIds: List<ItemId>,
        ): List<TranslationFailureMember> =
            members.filter { it.itemId in itemIds }.sortedBy { it.itemId.value }
    }

    private class FakeDecisions : TranslationTaskFailureAdjudicationPort {
        val recorded = mutableListOf<TranslationTaskFailureDecisionReceipt>()
        val completed = mutableListOf<Pair<Set<ItemId>, CommandId>>()
        override suspend fun recordFailureDecision(
            evidence: TranslationTaskFailureEvidence,
            batchId: TranslationTaskFailureBatchId,
            action: TranslationTaskCommandAction,
            members: List<TranslationFailureMember>,
            command: CommandId,
        ): TranslationTaskFailureDecisionReceipt =
            TranslationTaskFailureDecisionReceipt(
                command, action, batchId, members.map { it.itemId }, 0,
            ).also { recorded.add(it) }

        override suspend fun completeWithErrors(
            ref: LocalProjectRef,
            run: RunIdentity,
            skippedItemIds: Set<ItemId>,
            command: CommandId,
        ) { completed += skippedItemIds to command }
    }

    private class FakeRevisions : TranslationQueueRevisionPort {
        var decisions: (TranslationTaskSpec) -> TranslationQueueRevisionDecision =
            { TranslationQueueRevisionDecision.Current(it.revision) }
        var entered: CompletableDeferred<Unit>? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun validate(spec: TranslationTaskSpec): TranslationQueueRevisionDecision {
            entered?.complete(Unit)
            gate?.await()
            return decisions(spec)
        }
    }

    private class FakeRecovery : TranslationQueueRecoveryPort {
        var provider: (TranslationQueueDispatch) -> TranslationQueueRecoveryEvidence =
            { TranslationQueueRecoveryEvidence(null, null) }
        override suspend fun evidence(dispatch: TranslationQueueDispatch): TranslationQueueRecoveryEvidence =
            provider(dispatch)
    }

    private class FakeDecisionJournal : TranslationQueueDecisionJournalPort {
        val decisions = mutableListOf<TranslationQueueTaskDecision>()
        override suspend fun decisions(dispatch: TranslationQueueDispatch): List<TranslationQueueTaskDecision> =
            decisions
    }

    private class FakeControls : TranslationQueueControlPort {
        val paused = mutableListOf<TranslationTaskQueueIdentity>()
        val stopped = mutableListOf<TranslationTaskQueueIdentity>()
        override fun pause(identity: TranslationTaskQueueIdentity): Boolean {
            paused.add(identity)
            return true
        }
        override fun stop(identity: TranslationTaskQueueIdentity): Boolean {
            stopped.add(identity)
            return false
        }
    }

    private class MemoryStore : TranslationQueueStorePort {
        val queues = LinkedHashMap<LocalProjectRef, StoredTranslationQueue>()
        override suspend fun load(ref: LocalProjectRef): StoredTranslationQueue? = queues[ref]
        override suspend fun list(): List<StoredTranslationQueue> = queues.values.toList()
        override suspend fun compareAndSet(
            ref: LocalProjectRef,
            expected: StoredTranslationQueue?,
            next: TranslationQueueSnapshot,
        ): StoredTranslationQueue {
            val current = queues[ref]
            if (expected == null) {
                if (current != null) throw TranslationQueueConflict("exists")
            } else if (current == null || current.snapshot.revision != expected.snapshot.revision ||
                current.fingerprint != expected.fingerprint
            ) {
                throw TranslationQueueConflict("changed")
            }
            check(next.revision == (expected?.snapshot?.revision ?: 0L) + 1L)
            return StoredTranslationQueue(next, ObjectFingerprint("f".repeat(64))).also { queues[ref] = it }
        }
    }

    private fun spec(ref: LocalProjectRef, runId: String, revision: Long = 4): TranslationTaskSpec =
        TranslationTaskSpec(
            ref = ref,
            run = RunIdentity(ref.projectId, null, RunId(runId), CommandId("command-$runId")),
            configuration = configuration,
            revision = revision,
            afterId = 0,
            pageSize = 20,
            wholeProject = false,
            options = RemoteTranslationOptions(targetLanguage = "zh"),
        )

    private fun ref(project: String, instance: String): LocalProjectRef = LocalProjectRef(
        ProjectId(project), ProjectInstanceId(instance), RecoveryGeneration(1))

    private fun complete(committed: Int, skipped: Int = 0, target: Int = committed + skipped) =
        completeEvidence(committed, skipped, target)

    private fun completeEvidence(committed: Int, skipped: Int, target: Int) =
        TranslationTaskOutcomeEvidence(
            stage = "COMPLETE",
            targetItems = target,
            committedItems = committed,
            skippedItems = skipped,
            receiptedItems = committed,
            unresolvedDispatches = 0,
            usage = UsageSource.ACTUAL,
        )
}
