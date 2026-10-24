package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailureAdjudicationTest {
    private val project = LocalProjectRef(
        projectId = ProjectId("project"),
        instanceId = ProjectInstanceId("instance"),
        generation = RecoveryGeneration(1),
    )
    private val configuration = ModelConfigurationRef("model", 5)
    private val task = TranslationTaskQueueIdentity(
        ref = project,
        run = RunIdentity(
            projectId = project.projectId,
            chatId = null,
            runId = RunId("run"),
            commandId = CommandId("run-command"),
        ),
        configuration = configuration,
        revision = 7,
    )

    @Test
    fun adjudicatingOneBatchLeavesEveryOtherBatchAndMemberFactUntouched() {
        val first = batch("batch-1", member(1), member(2))
        val second = batch("batch-2", member(3, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT))

        var queue = runningQueue()
        queue = queue.recordFailure(task, first).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)
        queue = queue.recordFailure(task, second).appliedQueue()
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, queue.task(task)!!.state)

        val before = queue.task(task)!!
        val firstFacts = before.batch(first.id)!!.members
        val secondFacts = before.batch(second.id)!!.members

        val retriedResult = queue.retryMembers(
            task,
            CommandId("retry-1"),
            first.id,
            listOf(ItemId(1)),
        )
        val afterRetry = retriedResult.appliedQueue().task(task)!!
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, afterRetry.state)
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, queue.task(task)!!.state)
        assertNull(queue.task(task)!!.decision(first.id, ItemId(1)))
        assertEquals(firstFacts, afterRetry.batch(first.id)!!.members)
        assertEquals(secondFacts, afterRetry.batch(second.id)!!.members)
        assertNull(afterRetry.decision(second.id, ItemId(3)))

        val decision = afterRetry.decision(first.id, ItemId(1))!!
        assertEquals(CommandId("retry-1"), decision.commandId)
        assertEquals(task, decision.task)
        assertEquals(first.id, decision.batchId)
        assertEquals(listOf(ItemId(1)), decision.memberIds)
        assertEquals(listOf(requireNotNull(before.batch(first.id)!!.member(ItemId(1)))), decision.evidence)

        @Suppress("UNCHECKED_CAST")
        val mutableMembers = afterRetry.batch(first.id)!!.members as MutableList<TranslationFailureMember>
        assertThrows(UnsupportedOperationException::class.java) { mutableMembers.clear() }

        val keptResult = retriedResult.queue.keepError(
            task,
            CommandId("keep-2"),
            second.id,
            listOf(ItemId(3)),
        )
        val afterKeep = keptResult.appliedQueue().task(task)!!
        assertEquals(TranslationTaskQueueState.FAILED, afterKeep.state)
        assertEquals(
            TranslationTaskCommandAction.KEEP_ERROR,
            afterKeep.decision(second.id, ItemId(3))!!.action,
        )
        assertEquals(1, afterKeep.decisionsFor(first.id).size)
        assertEquals(1, afterKeep.decisionsFor(second.id).size)
        assertNull(afterKeep.decision(first.id, ItemId(2)))
        assertEquals(firstFacts, afterKeep.batch(first.id)!!.members)
        assertEquals(secondFacts, afterKeep.batch(second.id)!!.members)
    }

    @Test
    fun retrySelectsExactlyTheFixedMembersAndNeverRetriesACommittedOne() {
        val mixed = batch(
            "batch-mixed",
            member(1),
            member(2),
            member(3, committed = true),
        )
        val queue = runningQueue().recordFailure(task, mixed).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)
        assertEquals(
            TranslationQueueRejection.COMMITTED_MEMBER,
            queue.recordFailure(task, batch("mixed-again", member(3))).rejection(),
        )

        val afterFix = queue.retryMembers(
            task,
            CommandId("retry-fixed"),
            mixed.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        val current = afterFix.task(task)!!
        assertEquals(TranslationTaskQueueState.FAILED, current.state)
        assertEquals(listOf(ItemId(1)), current.decisionsFor(mixed.id).single().memberIds)
        assertNull(current.decision(mixed.id, ItemId(2)))
        assertNull(current.decision(mixed.id, ItemId(3)))
        assertEquals(listOf(ItemId(2)), current.unresolvedMembers(mixed.id).map { it.itemId })

        assertEquals(
            TranslationQueueRejection.COMMITTED_MEMBER,
            queue.retryMembers(
                task,
                CommandId("retry-committed"),
                mixed.id,
                listOf(ItemId(3)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.MEMBER_ALREADY_DECIDED,
            afterFix.retryMembers(
                task,
                CommandId("retry-again"),
                mixed.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        val duplicate = afterFix.retryMembers(
            task,
            CommandId("retry-fixed"),
            mixed.id,
            listOf(ItemId(1)),
        )
        assertTrue(duplicate is TranslationQueueResult.Duplicate)
        assertEquals(
            CommandId("retry-fixed"),
            (duplicate as TranslationQueueResult.Duplicate).command.commandId,
        )

        val ready = afterFix.retryMembers(
            task,
            CommandId("retry-second"),
            mixed.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, ready.task(task)!!.state)
        assertEquals(0L, ready.task(task)!!.sequence)
        assertEquals(
            mixed.members,
            ready.task(task)!!.batch(mixed.id)!!.members,
        )
        assertNull(ready.task(task)!!.finalDecision(ItemId(3)))

        val running = ready.dispatchNext().appliedQueue()
        val completed = running.complete(task, completion(3)).appliedQueue()
        assertEquals(TranslationTaskQueueState.COMPLETED, completed.task(task)!!.state)
        assertTrue(completed.task(task)!!.batch(mixed.id)!!.member(ItemId(3))!!.committed)
    }

    @Test
    fun retryingSeveralFixedMembersBindsCanonicalEvidenceAndReplaysIdempotently() {
        val failure = batch("batch-multi", member(4), member(5))
        val queue = runningQueue().recordFailure(task, failure).appliedQueue()
        val after = queue.retryMembers(
            task,
            CommandId("retry-multi"),
            failure.id,
            listOf(ItemId(5), ItemId(4)),
        ).appliedQueue()
        val current = after.task(task)!!
        assertEquals(TranslationTaskQueueState.QUEUED, current.state)
        val decision = current.decision(failure.id, ItemId(4))!!
        assertEquals(listOf(ItemId(4), ItemId(5)), decision.memberIds)
        assertEquals(listOf(ItemId(4), ItemId(5)), decision.evidence.map { it.itemId })

        val duplicate = after.retryMembers(
            task,
            CommandId("retry-multi"),
            failure.id,
            listOf(ItemId(4), ItemId(5)),
        )
        assertTrue(duplicate is TranslationQueueResult.Duplicate)
        assertEquals(CommandId("retry-multi"), (duplicate as TranslationQueueResult.Duplicate).command.commandId)
    }

    @Test
    fun oneItemCannotHaveTwoOpenBatchesButCanFailAgainAfterRetry() {
        val first = batch("batch-open-first", member(1), member(2))
        val second = batch("batch-open-second", member(1), member(3))
        var queue = runningQueue().recordFailure(task, first).appliedQueue()
        assertEquals(
            TranslationQueueRejection.MEMBER_STILL_UNRESOLVED,
            queue.recordFailure(task, second).rejection(),
        )

        queue = queue.retryMembers(
            task,
            CommandId("retry-open-1"),
            first.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        queue = queue.recordFailure(task, second).appliedQueue()
        assertEquals(2, queue.task(task)!!.batches.size)
        assertNull(queue.task(task)!!.decision(second.id, ItemId(1)))
        assertNull(queue.task(task)!!.finalDecision(ItemId(1)))

        queue = queue.skipMembers(
            task,
            CommandId("skip-open-1"),
            second.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        assertEquals(
            TranslationTaskCommandAction.SKIP_MEMBERS,
            queue.task(task)!!.finalDecision(ItemId(1)),
        )
        assertEquals(
            TranslationQueueRejection.MEMBER_ALREADY_DECIDED,
            queue.recordFailure(task, batch("batch-open-third", member(1))).rejection(),
        )
    }

    @Test
    fun skipAndRetryCanFinishAsCompletedWithErrors() {
        val failure = batch("batch-mixed-skip", member(1), member(2))
        var queue = runningQueue().recordFailure(task, failure).appliedQueue()
        queue = queue.skipMembers(
            task,
            CommandId("skip-first"),
            failure.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)

        queue = queue.retryMembers(
            task,
            CommandId("retry-second"),
            failure.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(task)!!.state)

        val running = queue.dispatchNext().appliedQueue()
        assertEquals(setOf(ItemId(1)), running.task(task)!!.skippedItemIds)
        assertEquals(
            TranslationTaskCommandAction.SKIP_MEMBERS,
            running.task(task)!!.finalDecision(ItemId(1)),
        )
        assertEquals(
            TranslationTaskCommandAction.RETRY_MEMBERS,
            running.task(task)!!.finalDecision(ItemId(2)),
        )
        assertNull(running.task(task)!!.finalDecision(ItemId(99)))
        @Suppress("UNCHECKED_CAST")
        val mutableSkipped = running.task(task)!!.skippedItemIds as MutableSet<ItemId>
        assertThrows(UnsupportedOperationException::class.java) { mutableSkipped.clear() }
        val completed = running.complete(
            task,
            TranslationTaskCompletionFacts(
                committedItems = 1,
                skippedItems = 1,
                receiptedItems = 1,
                targetItems = 2,
                unreceiptedDispatches = 0,
                usage = UsageSource.ACTUAL,
            ),
        ).appliedQueue()
        assertEquals(
            TranslationTaskQueueState.COMPLETED_WITH_ERRORS,
            completed.task(task)!!.state,
        )
        assertEquals(failure.members, completed.task(task)!!.batch(failure.id)!!.members)
        assertEquals(1, completed.task(task)!!.skippedItemIds.size)
        assertNull(completed.activeTask(project))
        val duplicate = completed.complete(
            task,
            TranslationTaskCompletionFacts(1, 1, 1, 2, 0, UsageSource.ACTUAL),
        )
        assertTrue(duplicate is TranslationQueueResult.Unchanged)
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            completed.complete(task, completion(1)).rejection(),
        )
    }

    @Test
    fun skippingATerminalFailureCanCompleteWithErrorsWithoutClearingTheFact() {
        val failure = batch(
            "batch-terminal-complete",
            member(1, terminal = true),
            member(2),
        )
        var queue = runningQueue().recordFailure(task, failure).appliedQueue()
        queue = queue.skipMembers(
            task,
            CommandId("skip-terminal-complete"),
            failure.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        queue = queue.retryMembers(
            task,
            CommandId("retry-terminal-peer"),
            failure.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(task)!!.state)

        val running = queue.dispatchNext().appliedQueue()
        val completed = running.complete(
            task,
            TranslationTaskCompletionFacts(1, 1, 1, 2, 0, UsageSource.ACTUAL),
        ).appliedQueue()
        assertEquals(
            TranslationTaskQueueState.COMPLETED_WITH_ERRORS,
            completed.task(task)!!.state,
        )
        assertTrue(completed.task(task)!!.batch(failure.id)!!.member(ItemId(1))!!.terminal)
        assertEquals(setOf(ItemId(1)), completed.task(task)!!.skippedItemIds)
    }

    @Test
    fun skipKeepErrorAndTerminateRetainTheTerminalFailureFacts() {
        val failure = batch(
            "batch-terminal",
            member(1, terminal = true),
            member(2),
        )
        var queue = runningQueue().recordFailure(task, failure).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)
        assertEquals(
            TranslationQueueRejection.TERMINAL_FAILURE,
            queue.retryMembers(
                task,
                CommandId("retry-terminal"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_FAILURE,
            queue.recordFailure(
                task,
                batch("reopened-terminal-fact", member(1)),
            ).rejection(),
        )

        queue = queue.skipMembers(
            task,
            CommandId("skip-terminal"),
            failure.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)

        queue = queue.keepError(
            task,
            CommandId("keep-ordinary"),
            failure.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        val decided = queue.task(task)!!
        assertEquals(TranslationTaskQueueState.FAILED, decided.state)
        assertEquals(2, decided.decisions.size)
        assertEquals(
            TranslationTaskCommandAction.SKIP_MEMBERS,
            decided.decision(failure.id, ItemId(1))!!.action,
        )
        assertEquals(
            TranslationTaskCommandAction.KEEP_ERROR,
            decided.decision(failure.id, ItemId(2))!!.action,
        )
        assertEquals(failure.members, decided.batch(failure.id)!!.members)
        assertTrue(decided.batch(failure.id)!!.member(ItemId(1))!!.terminal)
        assertEquals(
            TranslationQueueRejection.MEMBER_ALREADY_DECIDED,
            queue.recordFailure(task, batch("reopened-terminal", member(1))).rejection(),
        )

        val queuedBehindFailure = task.copy(
            run = task.run.copy(
                runId = RunId("run-2"),
                commandId = CommandId("run-command-2"),
            ),
        )
        val blocked = queue.enqueue(queuedBehindFailure).appliedQueue()
        assertTrue(blocked.dispatchNext() is TranslationQueueResult.Unchanged)
        assertEquals(
            TranslationTaskQueueState.QUEUED,
            blocked.task(queuedBehindFailure)!!.state,
        )

        val terminatedResult = queue.terminate(task, CommandId("terminate-1"))
        val terminated = terminatedResult.appliedQueue()
        val cancelled = terminated.task(task)!!
        assertEquals(TranslationTaskQueueState.CANCELLED, cancelled.state)
        assertEquals(3, cancelled.commands.size)
        assertEquals(2, cancelled.decisions.size)
        assertEquals(failure.members, cancelled.batch(failure.id)!!.members)
        assertTrue(cancelled.batch(failure.id)!!.member(ItemId(1))!!.terminal)
        assertEquals(setOf(ItemId(1)), cancelled.skippedItemIds)

        val duplicate = terminated.terminate(task, CommandId("terminate-1"))
        assertTrue(duplicate is TranslationQueueResult.Duplicate)
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            terminated.terminate(task, CommandId("terminate-2")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            terminated.retryMembers(
                task,
                CommandId("late-retry"),
                failure.id,
                listOf(ItemId(2)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            terminated.recordFailure(task, batch("late-batch", member(3))).rejection(),
        )
        assertEquals(failure.members, terminated.task(task)!!.batch(failure.id)!!.members)
    }

    @Test
    fun missingReceiptsAndUnknownUsageAreNeverTreatedAsSuccess() {
        val missing = batch(
            "batch-missing",
            member(1, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT),
        )
        var queue = runningQueue().recordFailure(task, missing).appliedQueue()
        assertEquals(TranslationTaskQueueState.NEEDS_REVIEW, queue.task(task)!!.state)
        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            queue.retryMembers(task, CommandId("retry-missing"), missing.id, listOf(ItemId(1))).rejection(),
        )

        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            queue.skipMembers(
                task,
                CommandId("skip-missing"),
                missing.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        val kept = queue.keepError(
            task,
            CommandId("keep-missing"),
            missing.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, kept.task(task)!!.state)
        assertEquals(missing.members, kept.task(task)!!.batch(missing.id)!!.members)
        assertEquals(0, kept.task(task)!!.skippedItemIds.size)
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            kept.complete(task, completion(1)).rejection(),
        )

        val fresh = runningQueue()
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            fresh.complete(
                task,
                TranslationTaskCompletionFacts(1, 0, 1, 2, 0, UsageSource.ACTUAL),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            fresh.complete(task, completion(1, receipted = 0)).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            fresh.complete(task, completion(1, unreceipted = 1)).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            fresh.complete(task, completion(1, usage = UsageSource.UNKNOWN)).rejection(),
        )
        assertEquals(
            TranslationTaskQueueState.COMPLETED,
            fresh.complete(task, completion(1, usage = UsageSource.ACTUAL)).appliedTask().state,
        )

        val unknown = batch("batch-unknown", member(2, usage = UsageSource.UNKNOWN))
        var retried = runningQueue().recordFailure(task, unknown).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, retried.task(task)!!.state)
        retried = retried.retryMembers(
            task,
            CommandId("retry-unknown"),
            unknown.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, retried.task(task)!!.state)
        retried = retried.dispatchNext().appliedQueue()
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            retried.complete(task, completion(1, usage = UsageSource.UNKNOWN)).rejection(),
        )
        val estimated = retried.complete(task, completion(1, usage = UsageSource.ESTIMATED)).appliedQueue()
        assertEquals(TranslationTaskQueueState.COMPLETED, estimated.task(task)!!.state)
        assertEquals(
            UsageSource.UNKNOWN,
            estimated.task(task)!!.batch(unknown.id)!!.member(ItemId(2))!!.usage,
        )
    }

    @Test
    fun duplicateAndLateCommandsReturnTheOriginalFactOrAreRefused() {
        val failure = batch("batch-commands", member(1), member(2))
        var queue = runningQueue().recordFailure(task, failure).appliedQueue()
        val firstCommand = CommandId("retry-1")

        val applied = queue.retryMembers(
            task,
            firstCommand,
            failure.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        val duplicate = applied.retryMembers(task, firstCommand, failure.id, listOf(ItemId(1)))
        assertTrue(duplicate is TranslationQueueResult.Duplicate)
        assertEquals(
            applied.task(task)!!.state,
            duplicate.queue.task(task)!!.state,
        )
        assertEquals(
            TranslationQueueRejection.COMMAND_CONFLICT,
            applied.retryMembers(task, firstCommand, failure.id, listOf(ItemId(2))).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.MEMBER_ALREADY_DECIDED,
            applied.retryMembers(
                task,
                CommandId("retry-2"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )

        queue = applied.retryMembers(
            task,
            CommandId("retry-3"),
            failure.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(task)!!.state)
        val running = queue.dispatchNext().appliedQueue()

        val duplicateAfterRun = running.retryMembers(
            task,
            firstCommand,
            failure.id,
            listOf(ItemId(1)),
        )
        assertTrue(duplicateAfterRun is TranslationQueueResult.Duplicate)
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            running.retryMembers(
                task,
                CommandId("retry-4"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )

        val cancelledResult = running.cancel(task, CommandId("cancel-1"))
        val cancelled = cancelledResult.appliedQueue()
        assertEquals(TranslationTaskQueueState.CANCELLED, cancelled.task(task)!!.state)
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            cancelled.retryMembers(
                task,
                CommandId("retry-5"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        val duplicateAfterCancel = cancelled.retryMembers(
            task,
            firstCommand,
            failure.id,
            listOf(ItemId(1)),
        )
        assertTrue(duplicateAfterCancel is TranslationQueueResult.Duplicate)
        assertEquals(
            TranslationQueueRejection.COMMAND_CONFLICT,
            cancelled.pause(task, CommandId("retry-3")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            cancelled.pause(task, CommandId("pause-after-cancel")).rejection(),
        )
    }

    @Test
    fun completionRetainsBatchesAndDecisionsAndReleasesTheProjectSlot() {
        val failure = batch("batch-complete", member(1), member(2))
        var queue = runningQueue().recordFailure(task, failure).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            queue.complete(task, completion(2)).rejection(),
        )

        queue = queue.retryMembers(
            task,
            CommandId("retry-complete-1"),
            failure.id,
            listOf(ItemId(1)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.FAILED, queue.task(task)!!.state)
        queue = queue.retryMembers(
            task,
            CommandId("retry-complete-2"),
            failure.id,
            listOf(ItemId(2)),
        ).appliedQueue()
        assertEquals(TranslationTaskQueueState.QUEUED, queue.task(task)!!.state)

        queue = queue.dispatchNext().appliedQueue()
        assertEquals(TranslationTaskQueueState.RUNNING, queue.task(task)!!.state)
        val completed = queue.complete(task, completion(2)).appliedQueue()
        val done = completed.task(task)!!
        assertEquals(TranslationTaskQueueState.COMPLETED, done.state)
        assertEquals(2, done.decisions.size)
        assertEquals(failure.members, done.batch(failure.id)!!.members)
        assertNull(completed.activeTask(project))

        val duplicate = completed.complete(task, completion(2))
        assertTrue(duplicate is TranslationQueueResult.Unchanged)
        assertEquals(
            TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            completed.complete(task, completion(2, usage = UsageSource.UNKNOWN)).rejection(),
        )
        assertTrue(
            completed.retryMembers(
                task,
                CommandId("retry-complete-1"),
                failure.id,
                listOf(ItemId(1)),
            ) is TranslationQueueResult.Duplicate,
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            completed.retryMembers(
                task,
                CommandId("late-after-complete"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.TERMINAL_STATE,
            completed.recordFailure(task, batch("after-complete", member(3))).rejection(),
        )
    }

    @Test
    fun adjudicationRejectsChangedIdentityConfigurationRevisionAndInvalidEvidence() {
        val failure = batch("batch-validation", member(1), member(2))
        val queue = runningQueue().recordFailure(task, failure).appliedQueue()
        val changedRevision = task.copy(revision = 8)
        val changedConfiguration = task.copy(configuration = configuration.copy(revision = 6))
        val changedGeneration = task.copy(
            ref = project.copy(generation = RecoveryGeneration(2)),
        )
        val foreignRun = task.copy(
            run = task.run.copy(
                runId = RunId("foreign-run"),
                commandId = CommandId("foreign-command"),
            ),
        )

        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            queue.retryMembers(
                changedRevision,
                CommandId("validation-revision"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            queue.retryMembers(
                changedConfiguration,
                CommandId("validation-config"),
                failure.id,
                listOf(ItemId(1)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.IDENTITY_MISMATCH,
            queue.terminate(changedGeneration, CommandId("validation-generation")).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.UNKNOWN_TASK,
            queue.recordFailure(foreignRun, batch("foreign-batch", member(9))).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.UNKNOWN_BATCH,
            queue.retryMembers(
                task,
                CommandId("validation-batch"),
                TranslationTaskFailureBatchId("missing-batch"),
                listOf(ItemId(1)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.MEMBER_NOT_IN_BATCH,
            queue.retryMembers(
                task,
                CommandId("validation-member"),
                failure.id,
                listOf(ItemId(9)),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.INVALID_EVIDENCE,
            queue.retryMembers(
                task,
                CommandId("validation-empty"),
                failure.id,
                emptyList(),
            ).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.INVALID_EVIDENCE,
            queue.retryMembers(
                task,
                CommandId("validation-duplicate"),
                failure.id,
                listOf(ItemId(1), ItemId(1)),
            ).rejection(),
        )
    }

    @Test
    fun failureRecordingIsIdempotentBoundedAndRejectedOutsideExecution() {
        val failure = batch("batch-idempotent", member(1))
        var queue = runningQueue()
        val recorded = queue.recordFailure(task, failure)
        queue = recorded.appliedQueue()
        assertEquals(1, queue.task(task)!!.batches.size)

        val same = queue.recordFailure(task, failure)
        assertTrue(same is TranslationQueueResult.Unchanged)
        assertEquals(1, same.queue.task(task)!!.batches.size)

        val changed = batch(
            "batch-idempotent",
            member(1, failure = ModelFailureKind.TIMEOUT),
        )
        assertEquals(
            TranslationQueueRejection.BATCH_CONFLICT,
            queue.recordFailure(task, changed).rejection(),
        )

        val queued = TranslationTaskQueue.empty().enqueue(task).appliedQueue()
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            queued.recordFailure(task, failure).rejection(),
        )

        val batchLimits = TranslationTaskQueueLimits(
            maxBatchesPerTask = 2,
            maxCommandsPerTask = 32,
        )
        var bounded = runningQueue(batchLimits)
            .recordFailure(task, batch("bounded-1", member(1))).appliedQueue()
        bounded = bounded.recordFailure(task, batch("bounded-2", member(2))).appliedQueue()
        assertEquals(
            TranslationQueueRejection.BATCH_LIMIT,
            bounded.recordFailure(task, batch("bounded-3", member(3))).rejection(),
        )

        val commandLimits = TranslationTaskQueueLimits(maxCommandsPerTask = 2)
        val commandBatch = batch("command-batch", member(4), member(5), member(6))
        var commandQueue = runningQueue(commandLimits)
            .recordFailure(task, commandBatch).appliedQueue()
        commandQueue = commandQueue.skipMembers(
            task,
            CommandId("command-1"),
            commandBatch.id,
            listOf(ItemId(4)),
        ).appliedQueue()
        commandQueue = commandQueue.skipMembers(
            task,
            CommandId("command-2"),
            commandBatch.id,
            listOf(ItemId(5)),
        ).appliedQueue()
        assertEquals(
            TranslationQueueRejection.COMMAND_LIMIT,
            commandQueue.skipMembers(
                task,
                CommandId("command-3"),
                commandBatch.id,
                listOf(ItemId(6)),
            ).rejection(),
        )
        val terminalResult = commandQueue.terminate(task, CommandId("command-terminal"))
        assertEquals(TranslationTaskQueueState.CANCELLED, terminalResult.appliedTask().state)
        assertEquals(3, terminalResult.appliedTask().commands.size)
    }

    private fun runningQueue(
        limits: TranslationTaskQueueLimits = TranslationTaskQueueLimits.DEFAULT,
    ): TranslationTaskQueue =
        TranslationTaskQueue.empty(limits)
            .enqueue(task).appliedQueue()
            .dispatchNext().appliedQueue()

    private fun member(
        item: Long,
        receipt: TranslationFailureReceipt = TranslationFailureReceipt.RECEIVED,
        usage: UsageSource = UsageSource.ACTUAL,
        committed: Boolean = false,
        terminal: Boolean = false,
        failure: ModelFailureKind = ModelFailureKind.NETWORK,
    ): TranslationFailureMember {
        val effectiveUsage = if (receipt == TranslationFailureReceipt.RECEIVED) {
            usage
        } else {
            UsageSource.UNKNOWN
        }
        return TranslationFailureMember(
            itemId = ItemId(item),
            fingerprint = ObjectFingerprint("a".repeat(64)),
            failure = failure,
            receipt = receipt,
            usage = effectiveUsage,
            committed = committed,
            terminal = terminal,
        )
    }

    private fun batch(id: String, vararg members: TranslationFailureMember): TranslationTaskFailureBatch =
        TranslationTaskFailureBatch(
            id = TranslationTaskFailureBatchId(id),
            members = members.toList(),
        )

    private fun completion(
        items: Int,
        usage: UsageSource = UsageSource.ACTUAL,
        unreceipted: Int = 0,
        receipted: Int = items,
        skipped: Int = 0,
    ): TranslationTaskCompletionFacts =
        TranslationTaskCompletionFacts(
            committedItems = items,
            skippedItems = skipped,
            receiptedItems = receipted,
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
