package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranslationQueueCoordinatorTest {
    private val projectA = ref("project-a", "instance-a")
    private val projectB = ref("project-b", "instance-b")
    private val configuration = ModelConfigurationRef("model", 3)

    @Test
    fun enqueueDispatchesTheGlobalHeadInAdmissionOrderAcrossProjects() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val a1 = spec(projectA, "run-a1")
        val b1 = spec(projectB, "run-b1")
        val a2 = spec(projectA, "run-a2")
        assertTrue(coordinator.enqueue(a1, a1.run.commandId) is TranslationQueueResult.Applied)
        assertTrue(coordinator.enqueue(b1, b1.run.commandId) is TranslationQueueResult.Applied)
        assertTrue(coordinator.enqueue(a2, a2.run.commandId) is TranslationQueueResult.Applied)

        assertEquals("run-a1", requireNotNull(coordinator.nextRunnable()).spec.run.runId.value)
        assertEquals(
            TranslationTaskQueueState.RUNNING,
            store.queues.getValue(projectA).snapshot.task(a1.queueIdentity())!!.state,
        )

        assertTrue(coordinator.markCompleted(a1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)
        assertNull("another project is the global head", coordinator.nextRunnable(projectA))
        assertEquals("run-b1", requireNotNull(coordinator.nextRunnable(projectB)).spec.run.runId.value)

        assertTrue(coordinator.markCompleted(b1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)
        assertEquals("run-a2", requireNotNull(coordinator.nextRunnable(projectA)).spec.run.runId.value)
    }

    @Test
    fun oneActiveProjectSlotAndOneGlobalRunningTask() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val a1 = spec(projectA, "run-a1")
        val a2 = spec(projectA, "run-a2")
        val b1 = spec(projectB, "run-b1")
        coordinator.enqueue(a1, a1.run.commandId)
        coordinator.enqueue(a2, a2.run.commandId)
        coordinator.enqueue(b1, b1.run.commandId)

        assertEquals("run-a1", requireNotNull(coordinator.nextRunnable(projectA)).spec.run.runId.value)
        assertNull("a second RUNNING task is refused", coordinator.nextRunnable(projectA))
        assertNull("another project cannot run in the same global slot", coordinator.nextRunnable(projectB))
        assertNull(coordinator.nextRunnable())
        assertTrue(coordinator.markPaused(a1.queueIdentity(), CommandId("pause-a1")) is TranslationQueueResult.Applied)

        assertNull("the paused task keeps project A's slot", coordinator.nextRunnable(projectA))
        assertEquals("run-b1", requireNotNull(coordinator.nextRunnable(projectB)).spec.run.runId.value)
        assertNull(coordinator.nextRunnable())
        assertTrue(coordinator.markCompleted(b1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)

        val resumed = coordinator.resumeQueue(projectA)
        val resumedTask = requireNotNull((resumed as? TranslationQueueResult.Applied)?.task)
        assertEquals(TranslationTaskQueueState.RUNNING, resumedTask.state)
        assertEquals(a1.queueIdentity(), resumedTask.identity)
        assertEquals(
            TranslationTaskQueueState.QUEUED,
            store.queues.getValue(projectA).snapshot.task(a2.queueIdentity())!!.state,
        )

        assertTrue(coordinator.markCompleted(a1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)
        assertEquals("run-a2", requireNotNull(coordinator.nextRunnable()).spec.run.runId.value)
    }

    @Test
    fun commandReplayIsIdempotentAndReusedCommandIdentityIsRefused() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-commands")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val writes = store.writes

        val paused = coordinator.markPaused(task.queueIdentity(), CommandId("pause-1"))
        assertEquals(TranslationTaskQueueState.PAUSED, (paused as TranslationQueueResult.Applied).task.state)
        assertEquals(writes + 1, store.writes)
        val commandsAfterPause = store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.commands.size

        val replay = coordinator.markPaused(task.queueIdentity(), CommandId("pause-1"))
        assertTrue(replay is TranslationQueueResult.Duplicate)
        assertEquals(writes + 1, store.writes)
        assertEquals(
            commandsAfterPause,
            store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.commands.size,
        )

        val conflict = coordinator.markCancelled(task.queueIdentity(), CommandId("pause-1"))
        assertEquals(TranslationQueueRejection.COMMAND_CONFLICT, conflict.rejection())
        assertEquals(writes + 1, store.writes)

        val duplicateAdmission = coordinator.enqueue(task, task.run.commandId)
        assertTrue(duplicateAdmission is TranslationQueueResult.Unchanged)
        val changedPolicy = task.copy(options = RemoteTranslationOptions(targetLanguage = "ja"))
        assertEquals(
            TranslationQueueRejection.IDENTITY_CONFLICT,
            coordinator.enqueue(changedPolicy, changedPolicy.run.commandId).rejection(),
        )
    }

    @Test
    fun failureBatchDecisionsRetainEvidenceAndControlCompletion() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-failures")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val identity = task.queueIdentity()
        val first = batch("batch-1", member(1))

        assertEquals(
            TranslationTaskQueueState.FAILED,
            (coordinator.recordFailureBatch(identity, first) as TranslationQueueResult.Applied).task.state,
        )
        assertNull("FAILED never dispatches automatically", coordinator.nextRunnable())
        assertEquals(
            TranslationTaskQueueState.QUEUED,
            (coordinator.retryMembers(identity, CommandId("retry-1"), first.id, listOf(ItemId(1)))
                as TranslationQueueResult.Applied).task.state,
        )
        assertNotNull(coordinator.nextRunnable())

        val second = batch("batch-2", member(1, failure = ModelFailureKind.TIMEOUT))
        assertEquals(
            TranslationTaskQueueState.FAILED,
            (coordinator.recordFailureBatch(identity, second) as TranslationQueueResult.Applied).task.state,
        )
        assertEquals(
            TranslationTaskQueueState.QUEUED,
            (coordinator.skipMembers(identity, CommandId("skip-1"), second.id, listOf(ItemId(1)))
                as TranslationQueueResult.Applied).task.state,
        )
        requireNotNull(coordinator.nextRunnable())
        val completed = coordinator.markCompletedWithErrors(identity, completion(committed = 0, skipped = 1))
        assertEquals(
            TranslationTaskQueueState.COMPLETED_WITH_ERRORS,
            (completed as TranslationQueueResult.Applied).task.state,
        )

        val persisted = store.queues.getValue(projectA).snapshot
        val saved = requireNotNull(persisted.task(identity))
        assertEquals(2, saved.batches.size)
        assertEquals(setOf(ItemId(1)), saved.skippedItemIds)
        assertEquals(second.id, saved.decisions.last().batchId)
        assertNull("a terminal queue has no runnable task", coordinator.nextRunnable())
    }

    @Test
    fun uncertainDispatchRequiresExplicitKeepOrTerminateAndNeverDispatches() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-uncertain")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val identity = task.queueIdentity()
        val uncertain = batch(
            "uncertain",
            member(1, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT),
        )

        assertEquals(
            TranslationTaskQueueState.NEEDS_REVIEW,
            (coordinator.recordFailureBatch(identity, uncertain) as TranslationQueueResult.Applied).task.state,
        )
        assertNull(coordinator.nextRunnable())
        try {
            coordinator.markRunning(identity)
            fail("An uncertain task was dispatched")
        } catch (_: TranslationQueueRefused) {
        }
        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            coordinator.retryMembers(identity, CommandId("retry-uncertain"), uncertain.id, listOf(ItemId(1))).rejection(),
        )
        assertEquals(
            TranslationQueueRejection.UNCERTAIN_RECEIPT,
            coordinator.skipMembers(identity, CommandId("skip-uncertain"), uncertain.id, listOf(ItemId(1))).rejection(),
        )
        assertEquals(
            TranslationTaskQueueState.FAILED,
            (coordinator.keepErrors(identity, CommandId("keep-uncertain"), uncertain.id, listOf(ItemId(1)))
                as TranslationQueueResult.Applied).task.state,
        )
        assertNull(coordinator.nextRunnable())
        assertTrue(
            coordinator.terminate(identity, CommandId("terminate-uncertain")) is TranslationQueueResult.Applied,
        )
        val saved = requireNotNull(store.queues.getValue(projectA).snapshot.task(identity))
        assertEquals(TranslationTaskQueueState.CANCELLED, saved.state)
        assertEquals(1, saved.batches.size)
    }

    @Test
    fun markRunningMarksOnlyTheDeterministicHead() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val a1 = spec(projectA, "run-a1")
        val a2 = spec(projectA, "run-a2")
        coordinator.enqueue(a1, a1.run.commandId)
        coordinator.enqueue(a2, a2.run.commandId)

        val dispatch = coordinator.markRunning(a1.queueIdentity())
        assertEquals(a1.queueIdentity(), dispatch.identity)
        assertEquals(TranslationTaskQueueState.RUNNING, dispatch.task.state)
        try {
            coordinator.markRunning(a2.queueIdentity())
            fail("A later queued task was marked RUNNING")
        } catch (_: TranslationQueueRefused) {
        }
        assertEquals(
            TranslationTaskQueueState.QUEUED,
            store.queues.getValue(projectA).snapshot.task(a2.queueIdentity())!!.state,
        )
    }

    @Test
    fun executeNextHandsThePersistedSpecToThePortOnlyOnce() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-execute")
        coordinator.enqueue(task, task.run.commandId)
        var executed: TranslationQueueDispatch? = null

        val dispatch = coordinator.executeNext(object : TranslationQueueExecutionPort {
            override suspend fun execute(dispatch: TranslationQueueDispatch) {
                executed = dispatch
            }
        })
        assertNotNull(dispatch)
        assertEquals(task, executed?.spec)
        assertEquals(TranslationTaskQueueState.RUNNING, store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
        assertNull(coordinator.executeNext(object : TranslationQueueExecutionPort {
            override suspend fun execute(dispatch: TranslationQueueDispatch) {
                fail("A second task was executed")
            }
        }))
    }

    @Test
    fun resumedTaskWaitsWhenAnotherProjectOwnsTheGlobalSlot() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val a1 = spec(projectA, "run-a1")
        val b1 = spec(projectB, "run-b1")
        coordinator.enqueue(a1, a1.run.commandId)
        coordinator.enqueue(b1, b1.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        coordinator.markPaused(a1.queueIdentity(), CommandId("pause-a1"))
        assertEquals("run-b1", requireNotNull(coordinator.nextRunnable()).spec.run.runId.value)

        val resumed = coordinator.resumeQueue(projectA)
        val waiting = requireNotNull((resumed as? TranslationQueueResult.Applied)?.task)
        assertEquals(TranslationTaskQueueState.QUEUED, waiting.state)

        assertTrue(coordinator.markCompleted(b1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)
        assertEquals(a1.queueIdentity(), requireNotNull(coordinator.nextRunnable(projectA)).identity)
    }

    @Test
    fun crashRestoredRunningTaskIsNeverRedispatchedUntilExplicitResume() = runBlocking {
        val store = MemoryStore()
        val first = coordinator(store)
        val task = spec(projectA, "run-crash")
        first.enqueue(task, task.run.commandId)
        requireNotNull(first.nextRunnable())
        val writes = store.writes

        // A new coordinator over the same persisted store represents a process restart.
        val restarted = coordinator(store)
        assertNull(restarted.nextRunnable())
        val unchanged = restarted.resumeQueue(projectA)
        assertTrue(unchanged is TranslationQueueResult.Unchanged)
        assertEquals(writes, store.writes)
        val saved = requireNotNull(store.queues.getValue(projectA).snapshot.task(task.queueIdentity()))
        assertEquals(TranslationTaskQueueState.RUNNING, saved.state)
        assertTrue(
            restarted.markFailed(task.queueIdentity()) is TranslationQueueResult.Applied,
        )
        assertEquals(
            TranslationTaskQueueState.FAILED,
            store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state,
        )
    }

    @Test
    fun markNeedsReviewBlocksDispatchAndCancelIsAvailable() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-review")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val identity = task.queueIdentity()

        assertTrue(coordinator.markNeedsReview(identity) is TranslationQueueResult.Applied)
        assertEquals(
            TranslationTaskQueueState.NEEDS_REVIEW,
            store.queues.getValue(projectA).snapshot.task(identity)!!.state,
        )
        assertNull(coordinator.nextRunnable())
        assertEquals(
            TranslationQueueRejection.INVALID_TRANSITION,
            coordinator.markFailed(identity).rejection(),
        )
        assertTrue(coordinator.markCancelled(identity, CommandId("cancel-review")) is TranslationQueueResult.Applied)
        assertEquals(TranslationTaskQueueState.CANCELLED, store.queues.getValue(projectA).snapshot.task(identity)!!.state)
    }

    @Test
    fun staleWriteIsReportedAsAConflictAndDoesNotChangeTheStoredQueue() = runBlocking {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val task = spec(projectA, "run-conflict")
        coordinator.enqueue(task, task.run.commandId)
        val before = store.queues.getValue(projectA)

        store.beforeWrite = {
            val snapshot = before.snapshot
            val competing = snapshot.updated(
                queue = snapshot.queue,
                changed = task.queueIdentity(),
                nowMillis = snapshot.updatedAtMillis + 1,
            )
            store.queues[projectA] = StoredTranslationQueue(
                competing,
                ObjectFingerprint("b".repeat(64)),
            )
        }
        try {
            coordinator.markCancelled(task.queueIdentity(), CommandId("cancel-conflict"))
            fail("A stale writer was not rejected")
        } catch (_: TranslationQueueConflict) {
        }
        assertEquals(2L, store.queues.getValue(projectA).snapshot.revision)
        assertEquals(TranslationTaskQueueState.QUEUED, store.queues.getValue(projectA).snapshot.task(task.queueIdentity())!!.state)
    }

    private fun coordinator(store: MemoryStore): TranslationQueueCoordinator {
        var now = 1_000L
        return TranslationQueueCoordinator(store, clock = { now++ })
    }

    private class MemoryStore : TranslationQueueStorePort {
        val queues = LinkedHashMap<LocalProjectRef, StoredTranslationQueue>()
        var writes = 0
        var beforeWrite: (() -> Unit)? = null

        override suspend fun load(ref: LocalProjectRef): StoredTranslationQueue? = queues[ref]

        override suspend fun list(): List<StoredTranslationQueue> = queues.values.toList()

        override suspend fun compareAndSet(
            ref: LocalProjectRef,
            expected: StoredTranslationQueue?,
            next: TranslationQueueSnapshot,
        ): StoredTranslationQueue {
            beforeWrite?.let { hook ->
                beforeWrite = null
                hook()
            }
            val current = queues[ref]
            if (expected == null) {
                if (current != null) throw TranslationQueueConflict("Translation queue already exists")
            } else if (current == null || current.snapshot.revision != expected.snapshot.revision ||
                current.fingerprint != expected.fingerprint
            ) {
                throw TranslationQueueConflict("Translation queue changed")
            }
            check(next.revision == (expected?.snapshot?.revision ?: 0L) + 1L)
            val stored = StoredTranslationQueue(next, fingerprint(next))
            queues[ref] = stored
            writes++
            return stored
        }

        private fun fingerprint(snapshot: TranslationQueueSnapshot): ObjectFingerprint =
            ObjectFingerprint(
                TextProjectFingerprint.bytes(
                    TextProjectFingerprint.utf8("${snapshot.revision}:${snapshot.queue.tasks.size}"),
                ),
            )
    }

    private fun spec(
        ref: LocalProjectRef,
        runId: String,
        configuration: ModelConfigurationRef = this.configuration,
        revision: Long = 4,
    ): TranslationTaskSpec = TranslationTaskSpec(
        ref = ref,
        run = RunIdentity(
            projectId = ref.projectId,
            chatId = null,
            runId = RunId(runId),
            commandId = CommandId("command-$runId"),
        ),
        configuration = configuration,
        revision = revision,
        afterId = 0,
        pageSize = 20,
        wholeProject = false,
        options = RemoteTranslationOptions(targetLanguage = "zh"),
    )

    private fun ref(project: String, instance: String): LocalProjectRef = LocalProjectRef(
        projectId = ProjectId(project),
        instanceId = ProjectInstanceId(instance),
        generation = RecoveryGeneration(1),
    )

    private fun member(
        id: Long,
        failure: ModelFailureKind = ModelFailureKind.NETWORK,
        receipt: TranslationFailureReceipt = TranslationFailureReceipt.RECEIVED,
    ): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint("a".repeat(64)),
        failure = failure,
        receipt = receipt,
    )

    private fun batch(id: String, vararg members: TranslationFailureMember): TranslationTaskFailureBatch =
        TranslationTaskFailureBatch(TranslationTaskFailureBatchId(id), members.toList())

    private fun completion(
        committed: Int,
        skipped: Int = 0,
        target: Int = committed + skipped,
    ): TranslationTaskCompletionFacts = TranslationTaskCompletionFacts(
        committedItems = committed,
        skippedItems = skipped,
        receiptedItems = committed,
        targetItems = target,
        unreceiptedDispatches = 0,
        usage = UsageSource.ACTUAL,
    )

    private fun TranslationQueueResult.rejection(): TranslationQueueRejection {
        assertTrue("Expected Rejected but was $this", this is TranslationQueueResult.Rejected)
        return (this as TranslationQueueResult.Rejected).reason
    }
}
