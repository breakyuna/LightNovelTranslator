package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTaskQueueTest {
    private val projectA = ref("project-a", "instance-a")
    private val projectB = ref("project-b", "instance-b")
    private val configuration = ModelConfigurationRef("model", 3)

    @Test
    fun sameProjectTasksRunSeriallyInStableInsertionOrder() {
        val first = identity(projectA, "run-a1")
        val second = identity(projectA, "run-a2")
        val queued = TranslationTaskQueue.empty()
            .enqueue(first).appliedQueue()
            .enqueue(second).appliedQueue()

        assertEquals(listOf(first, second), queued.tasks.map { it.identity })
        assertEquals(listOf(0L, 1L), queued.tasks.map { it.sequence })
        assertTrue(queued.tasks.all { it.state == TranslationTaskQueueState.QUEUED })

        val firstRun = queued.dispatchNext()
        val firstTask = firstRun.appliedTask()
        assertEquals(TranslationTaskQueueState.RUNNING, firstTask.state)
        assertEquals(first, firstTask.identity)
        assertEquals(TranslationTaskQueueState.QUEUED, firstRun.queue.task(second)!!.state)

        assertTrue(firstRun.queue.dispatchNext() is TranslationQueueResult.Unchanged)

        val completed = firstRun.queue.complete(first, completion(3))
        assertEquals(TranslationTaskQueueState.COMPLETED, completed.appliedTask().state)
        assertEquals(TranslationTaskQueueState.QUEUED, completed.queue.task(second)!!.state)

        val secondRun = completed.queue.dispatchNext().appliedTask()
        assertEquals(second, secondRun.identity)
        assertEquals(TranslationTaskQueueState.RUNNING, secondRun.state)
        assertEquals(0L, completed.queue.task(first)!!.sequence)
        assertEquals(1L, completed.queue.task(second)!!.sequence)
    }

    @Test
    fun differentProjectsWaitInTheSameStableQueueWithoutIdentityConflicts() {
        val alpha = identity(projectA, "run-alpha")
        val beta = identity(projectB, "run-beta")
        val queued = TranslationTaskQueue.empty()
            .enqueue(alpha).appliedQueue()
            .enqueue(beta).appliedQueue()

        val alphaRunResult = queued.dispatchNext()
        val alphaRun = alphaRunResult.appliedTask()
        assertEquals(alpha, alphaRun.identity)
        assertEquals(TranslationTaskQueueState.QUEUED, alphaRunResult.queue.task(beta)!!.state)
        assertTrue(alphaRunResult.queue.dispatchNext() is TranslationQueueResult.Unchanged)

        val alphaDone = alphaRunResult.queue.complete(alpha, completion(2)).appliedQueue()
        val betaRun = alphaDone.dispatchNext().appliedTask()
        assertEquals(beta, betaRun.identity)
        assertEquals(TranslationTaskQueueState.QUEUED, alphaDone.task(beta)!!.state)
    }

    @Test
    fun revisionRevalidationIsAuditedWithoutRewritingTheFrozenIdentity() {
        val task = identity(projectA, "run-revalidate", revision = 4)
        val queued = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        val refreshed = queued.revalidateRevision(task, CommandId("revalidate-1"), 9)
        val refreshedTask = refreshed.appliedTask()

        assertEquals(4L, refreshedTask.identity.revision)
        assertEquals(9L, refreshedTask.executionRevision)
        assertEquals(9L, refreshedTask.actionableRevision)
        assertEquals(TranslationTaskCommandAction.REVALIDATE_REVISION, refreshedTask.commands.single().action)
        assertTrue(
            refreshed.queue.revalidateRevision(task, CommandId("revalidate-1"), 9) is
                TranslationQueueResult.Duplicate,
        )
        assertEquals(
            TranslationQueueRejection.COMMAND_CONFLICT,
            refreshed.queue.revalidateRevision(task, CommandId("revalidate-1"), 10).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.INVALID_EVIDENCE,
            refreshed.queue.revalidateRevision(task, CommandId("revalidate-back"), 5).rejection(),
        )

        val running = refreshed.queue.dispatchNext().appliedQueue()
        assertEquals(TranslationTaskQueueState.RUNNING, running.task(task)!!.state)
        val claimedRefresh = running.revalidateRevision(task, CommandId("revalidate-running"), 11)
        assertEquals(11L, claimedRefresh.appliedTask().executionRevision)
        assertEquals(TranslationTaskQueueState.RUNNING, claimedRefresh.appliedTask().state)
        assertEquals(
            TranslationQueueRejection.INVALID_EVIDENCE,
            running.revalidateRevision(task, CommandId("revalidate-back-while-running"), 8).rejection(),
        )
    }

    @Test
    fun revisionRefusalIsAuditedAndRequiresReviewWithoutReopeningFacts() {
        val task = identity(projectA, "run-refuse", revision = 4)
        val queued = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        val refused = queued.refuseRevision(task, CommandId("refuse-1"))
        val refusedTask = refused.appliedTask()
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, refusedTask.state)
        assertEquals(TranslationTaskCommandAction.REFUSE_REVISION, refusedTask.commands.single().action)
        assertEquals(4L, refusedTask.identity.revision)
        assertTrue(
            refused.queue.refuseRevision(task, CommandId("refuse-1")) is TranslationQueueResult.Duplicate,
        )
        assertTrue(refused.queue.recoverPaused(task) is TranslationQueueResult.Rejected)
    }

    @Test
    fun restartRecoveryPausesOnlyRunningAndNeverActsAsAUserResume() {
        val task = identity(projectA, "run-recovery", revision = 4)
        val queued = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            queued.recoverPaused(task).rejection(),
        )
        val running = queued.dispatchNext().appliedQueue()
        val paused = running.recoverPaused(task).appliedTask()
        assertEquals(TranslationTaskQueueState.PAUSED, paused.state)
        assertEquals(task, paused.identity)
        assertTrue(paused.commands.isEmpty())
        assertTrue(running.recoverPaused(task).queue.recoverPaused(task) is TranslationQueueResult.Unchanged)
    }

    @Test
    fun pauseResumeAndCancelRequireTheExactIdentityAndAUserCommand() {
        val task = identity(projectA, "run-lifecycle")
        val running = TranslationTaskQueue.empty()
            .enqueue(task).appliedQueue()
            .dispatchNext().appliedQueue()
        val pauseCommand = CommandId("pause-1")

        val pausedResult = running.pause(task, pauseCommand)
        val paused = pausedResult.appliedTask()
        assertEquals(TranslationTaskQueueState.PAUSED, paused.state)
        assertEquals(task, paused.identity)
        assertEquals(TranslationTaskCommandAction.PAUSE, paused.commands.single().action)
        assertEquals(TranslationTaskQueueState.RUNNING, running.task(task)!!.state)

        val duplicatePause = pausedResult.queue.pause(task, pauseCommand)
        assertTrue(duplicatePause is TranslationQueueResult.Duplicate)
        assertEquals(pauseCommand, (duplicatePause as TranslationQueueResult.Duplicate).command.commandId)
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            pausedResult.queue.pause(task, CommandId("pause-2")).rejection(),
        )

        val resumedResult = pausedResult.queue.resume(task, CommandId("resume-1"))
        val resumed = resumedResult.appliedTask()
        assertEquals(TranslationTaskQueueState.QUEUED, resumed.state)
        assertEquals(paused.sequence, resumed.sequence)
        assertEquals(TranslationTaskCommandAction.RESUME, resumed.commands.last().action)
        assertTrue(
            resumedResult.queue.resume(task, CommandId("resume-1")) is TranslationQueueResult.Duplicate,
        )
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            resumedResult.queue.resume(task, CommandId("resume-2")).rejection(),
        )

        val cancelledResult = resumedResult.queue.cancel(task, CommandId("cancel-1"))
        val cancelled = cancelledResult.appliedTask()
        assertEquals(TranslationTaskQueueState.CANCELLED, cancelled.state)
        assertEquals(TranslationTaskCommandAction.CANCEL, cancelled.commands.last().action)
        assertTrue(
            cancelledResult.queue.cancel(task, CommandId("cancel-1")) is TranslationQueueResult.Duplicate,
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            cancelledResult.queue.cancel(task, CommandId("cancel-2")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            cancelledResult.queue.pause(task, CommandId("pause-after-cancel")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            cancelledResult.queue.resume(task, CommandId("resume-after-cancel")).rejection(),
        )
    }

    @Test
    fun identityConfigurationRevisionAndGenerationMismatchesAreRejected() {
        val task = identity(projectA, "run-identity", revision = 4)
        val running = TranslationTaskQueue.empty()
            .enqueue(task).appliedQueue()
            .dispatchNext().appliedQueue()

        val changedRevision = task.copy(revision = 5)
        val changedConfiguration = task.copy(configuration = configuration.copy(revision = 4))
        val changedConfigurationId = task.copy(configuration = configuration.copy(id = "other-model"))
        val changedInstance = task.copy(ref = projectA.copy(instanceId = ProjectInstanceId("other")))
        val changedGeneration = task.copy(ref = projectA.copy(generation = RecoveryGeneration(2)))
        val foreignRun = task.copy(
            run = task.run.copy(
                runId = RunId("foreign-run"),
                commandId = CommandId("foreign-command"),
            ),
        )

        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.pause(changedRevision, CommandId("pause-revision")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.pause(changedConfiguration, CommandId("pause-config")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.pause(changedConfigurationId, CommandId("pause-config-id")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.pause(changedInstance, CommandId("pause-instance")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.pause(changedGeneration, CommandId("pause-generation")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.UNKNOWN_TASK,
            running.pause(foreignRun, CommandId("pause-foreign")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            running.complete(changedRevision, completion(1)).rejection(),
        )

        val queued = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        assertTrue(queued.enqueue(task) is TranslationQueueResult.Unchanged)
        assertEquals(
            TranslationQueueRejection.IDENTITY_CONFLICT,
            queued.enqueue(changedRevision).rejection(),
        )
    }

    @Test
    fun oneProjectHasAtMostOneActiveTaskWhileAnotherProjectCanQueue() {
        val first = identity(projectA, "run-a1")
        val second = identity(projectA, "run-a2")
        val other = identity(projectB, "run-b1")
        var queue = TranslationTaskQueue.empty()
            .enqueue(first).appliedQueue()
            .enqueue(second).appliedQueue()
            .enqueue(other).appliedQueue()

        queue = queue.dispatchNext().appliedQueue()
        assertEquals(first, queue.activeTask(projectA)!!.identity)
        assertEquals(1, queue.tasksFor(projectA).count { it.state.occupiesProject })

        queue = queue.pause(first, CommandId("pause-a1")).appliedQueue()
        assertEquals(TranslationTaskQueueState.PAUSED, queue.activeTask(projectA)!!.state)
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(second)!!.state)

        val crossProjectResult = queue.dispatchNext()
        assertEquals(other, crossProjectResult.appliedTask().identity)
        queue = crossProjectResult.queue
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(second)!!.state)
        assertTrue(queue.dispatchNext() is TranslationQueueResult.Unchanged)

        val afterOther = queue.complete(other, completion(1)).appliedQueue()
        assertTrue(afterOther.dispatchNext() is TranslationQueueResult.Unchanged)

        val resumed = afterOther.resume(first, CommandId("resume-a1")).appliedQueue()
        val firstAgain = resumed.dispatchNext().appliedTask()
        assertEquals(first, firstAgain.identity)
        assertEquals(TranslationTaskQueueState.QUEUED, resumed.task(second)!!.state)
    }

    @Test
    fun queueReturnsNewSnapshotsAndExposesOnlyImmutableCollections() {
        val task = identity(projectA, "run-immutable")
        val initial = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        val running = initial.dispatchNext()

        assertNotSame(initial, running.queue)
        assertEquals(TranslationTaskQueueState.QUEUED, initial.task(task)!!.state)
        assertEquals(TranslationTaskQueueState.RUNNING, running.queue.task(task)!!.state)
        assertEquals(TranslationTaskQueueState.QUEUED, initial.task(task)!!.state)
    }

    @Test
    fun queueAndTaskBudgetsAreBounded() {
        val limits = TranslationTaskQueueLimits(maxTasks = 2, maxBatchesPerTask = 1, maxCommandsPerTask = 8)
        val first = identity(projectA, "run-bound-1")
        val second = identity(projectA, "run-bound-2")
        val third = identity(projectA, "run-bound-3")
        var queue = TranslationTaskQueue.empty(limits)
            .enqueue(first).appliedQueue()
            .enqueue(second).appliedQueue()

        assertEquals(TranslationQueueRejection.QUEUE_FULL, queue.enqueue(third).rejection())

        queue = queue.dispatchNext().appliedQueue()
        queue = queue.recordFailure(first, batch("bound-batch-1", member(1))).appliedQueue()
        assertEquals(
            TranslationQueueRejection.BATCH_LIMIT,
            queue.recordFailure(first, batch("bound-batch-2", member(2))).rejection(),
        )
    }

    @Test
    fun enqueueingASpecUsesItsFrozenRevisionAndConfiguration() {
        val spec = TranslationTaskSpec(
            ref = projectA,
            run = RunIdentity(
                projectId = projectA.projectId,
                chatId = null,
                runId = RunId("run-spec"),
                commandId = CommandId("command-run-spec"),
            ),
            configuration = configuration,
            revision = 9,
            afterId = 0,
            pageSize = 20,
            wholeProject = false,
            options = RemoteTranslationOptions(targetLanguage = "zh"),
        )
        val identity = spec.queueIdentity()
        assertEquals(spec.ref, identity.ref)
        assertEquals(spec.run, identity.run)
        assertEquals(spec.configuration, identity.configuration)
        assertEquals(spec.revision, identity.revision)
        assertNull(identity.run.chatId)

        val queued = TranslationTaskQueue.empty().enqueue(spec).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, queued.task(identity)!!.state)
    }

    private fun member(id: Long): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint("c".repeat(64)),
        failure = ModelFailureKind.NETWORK,
        receipt = TranslationFailureReceipt.RECEIVED,
    )

    private fun batch(id: String, vararg members: TranslationFailureMember): TranslationTaskFailureBatch =
        TranslationTaskFailureBatch(TranslationTaskFailureBatchId(id), members.toList())

    private fun identity(
        ref: LocalProjectRef,
        runId: String,
        configuration: ModelConfigurationRef = this.configuration,
        revision: Long = 4,
    ): TranslationTaskQueueIdentity = TranslationTaskQueueIdentity(
        ref = ref,
        run = RunIdentity(
            projectId = ref.projectId,
            chatId = null,
            runId = RunId(runId),
            commandId = CommandId("command-$runId"),
        ),
        configuration = configuration,
        revision = revision,
    )

    private fun ref(project: String, instance: String, generation: Long = 1): LocalProjectRef =
        LocalProjectRef(
            projectId = ProjectId(project),
            instanceId = ProjectInstanceId(instance),
            generation = RecoveryGeneration(generation),
        )

    private fun completion(
        items: Int,
        usage: UsageSource = UsageSource.ACTUAL,
        unreceipted: Int = 0,
    ): TranslationTaskCompletionFacts =
        TranslationTaskCompletionFacts(
            committedItems = items,
            skippedItems = 0,
            receiptedItems = items,
            targetItems = items,
            unreceiptedDispatches = unreceipted,
            usage = usage,
        )

    private fun TranslationQueueResult.appliedQueue(): TranslationTaskQueue {
        assertTrue("Expected Applied but was $this", this is TranslationQueueResult.Applied)
        return (this as TranslationQueueResult.Applied).queue
    }

    private fun TranslationQueueResult.appliedTask(): TranslationQueuedTask {
        assertTrue("Expected Applied but was $this", this is TranslationQueueResult.Applied)
        return (this as TranslationQueueResult.Applied).task
    }

    private fun TranslationQueueResult.rejection(): TranslationQueueRejection {
        assertTrue("Expected Rejected but was $this", this is TranslationQueueResult.Rejected)
        return (this as TranslationQueueResult.Rejected).reason
    }
}
