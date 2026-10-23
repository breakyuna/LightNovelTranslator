package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTranslationRunnerTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun request(command: String = "start") = ForegroundTranslationRequest(ref,
        RunIdentity(ref.projectId, null, RunId(command), CommandId(command)), ModelConfigurationRef("fixture", 1), 0, 0)
    private fun progress(saved: Int = 0) = TranslationProgress(saved, 1, saved.toLong(), unknownRequestUsage())
    private class Journal : RunJournalPort {
        var began = 0
        val terminals = mutableListOf<StoredRunState>()
        var finishGate: CompletableDeferred<Unit>? = null
        var beforeFinish: () -> Unit = {}
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle {
            began++; return RunJournalHandle("execution-$began", ref, run)
        }
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) {
            beforeFinish(); finishGate?.await(); terminals.add(state)
        }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun recent(ref: LocalProjectRef, limit: Int) = emptyList<StoredRun>()
    }

    @Test fun reservationAndJournalPrecedeForegroundGateAndNoWorkStartsForAnUnknownToken() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> calls++; progress(2).also(emit) })
        val handle = runner.prepare(request())
        assertNotNull(runtime.snapshot().activeRun)
        runCurrent()
        assertEquals(1, journal.began); assertEquals(0, calls)
        assertFalse(runner.foregroundStarted("foreign")); assertFalse(runner.stop("foreign"))
        assertTrue(runner.foregroundStarted(handle.token))
        assertFalse(runner.foregroundStarted(handle.token))
        assertEquals(2, handle.await().committedItems)
        assertEquals(1, calls); assertEquals(listOf(StoredRunState.SUCCEEDED), journal.terminals)
        assertEquals(ForegroundTranslationPhase.SUCCEEDED, runner.state.value!!.phase)
        assertNull(runtime.snapshot().activeRun)
        assertNull(runtime.snapshot().selectedChat)
    }

    @Test fun authenticationWriteRejectsPreparationWithoutChangingSelectionAndAllowsExplicitRetry() = runTest {
        for (previous in listOf(null, ChatIdentity(ref.projectId, ChatId("agent")))) {
            val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
            runtime.selectChat(previous)
            val write = runtime.beginAuthenticationWrite()
            val before = runtime.snapshot()
            val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
                translate = { _, _, _, _ -> calls++; progress() })
            val error = assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request()) }
            assertEquals(RuntimeUnavailable.Reason.BUSY, error.reason)
            assertEquals(before, runtime.snapshot())
            assertNull(runner.state.value)
            runCurrent(); assertEquals(0, journal.began); assertEquals(0, calls)
            runtime.endAuthenticationWrite(write)
            val handle = runner.prepare(request())
            assertEquals(ChatIdentity(ref.projectId, ChatId("translation-workspace")), runtime.snapshot().selectedChat)
            runner.foregroundStarted(handle.token)
            handle.await()
            assertEquals(1, journal.began); assertEquals(1, calls)
            assertNull(runtime.snapshot().activeRun); assertNull(runtime.snapshot().selectedChat)
        }
    }

    @Test fun duplicateCommandAttachesButPayloadChangesAndConcurrentNewWorkAreRejected() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, _ -> progress() })
        val original = request(); val handle = runner.prepare(original)
        assertSame(handle, runner.prepare(original)); assertSame(handle, runner.attach(handle.token))
        assertThrows(ForegroundTranslationConflict::class.java) { runner.prepare(original.copy(afterId = 4)) }
        assertThrows(ForegroundTranslationConflict::class.java) { runner.prepare(original.copy(scope = TranslationScope.PROJECT)) }
        assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request("other")) }
        assertTrue(runner.stop(handle.token)); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        assertEquals(1, journal.began); assertEquals(listOf(StoredRunState.CANCELLED), journal.terminals)
        assertSame(handle, runner.prepare(original)) // A duplicate completed command is never replayed.
    }

    @Test fun projectScopeRejectsAPartialStartAndIsFrozenBeforeForegroundAcknowledgement() = runTest {
        assertThrows(IllegalArgumentException::class.java) { request().copy(afterId = 4, scope = TranslationScope.PROJECT) }
        val runtime = RuntimeCoordinator(); val journal = Journal(); var observed: TranslationScope? = null
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { request, _, _, _ -> observed = request.scope; progress(4) })
        val original = request().copy(scope = TranslationScope.PROJECT)
        val handle = runner.prepare(original)
        runCurrent(); assertNull(observed)
        assertSame(handle, runner.prepare(original))
        runner.foregroundStarted(handle.token)
        assertEquals(4, handle.await().committedItems)
        assertEquals(TranslationScope.PROJECT, observed)
        assertEquals(1, journal.began)
    }

    @Test fun cancellingAUiWaiterLeavesTheOwnedExecutionAndLaterAttachmentAlive() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> entered.complete(Unit); release.await(); progress(3).also(emit) })
        val handle = runner.prepare(request()); runner.foregroundStarted(handle.token)
        entered.await()
        val waiter = launch { handle.await() }; runCurrent(); waiter.cancelAndJoin()
        assertTrue(runner.accepts(handle.token)); assertNotNull(runtime.snapshot().activeRun)
        val attached = requireNotNull(runner.attach(handle.token))
        release.complete(Unit)
        assertEquals(3, attached.await().committedItems)
        assertEquals(1, journal.began); assertEquals(listOf(StoredRunState.SUCCEEDED), journal.terminals)
    }

    @Test fun failedOrMissingForegroundStartNeverRunsBusinessAndReleasesTheLease() = runTest {
        for (explicitFailure in listOf(true, false)) {
            val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
            val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
                translate = { _, _, _, _ -> calls++; progress() }, startTimeoutMillis = 100)
            val handle = runner.prepare(request())
            runCurrent()
            if (explicitFailure) runner.startFailed(handle.token) else advanceTimeBy(101)
            runCurrent()
            try { handle.await(); fail() } catch (_: ForegroundTranslationStartFailed) { }
            assertEquals(0, calls); assertNull(runtime.snapshot().activeRun)
            assertEquals(listOf(StoredRunState.FAILED), journal.terminals)
            assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
        }
    }

    @Test fun rejectedStartCannotBeAcknowledgedWhileFailedJournalCleanupStillOwnsTheRun() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val cleanup = CompletableDeferred<Unit>(); journal.finishGate = cleanup
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, _ -> calls++; progress() })
        val handle = runner.prepare(request()); runCurrent()
        runner.startFailed(handle.token)
        assertEquals(ForegroundTranslationPhase.STOPPING, runner.state.value!!.phase)
        assertFalse(runner.foregroundStarted(handle.token))
        runner.startFailed(handle.token) // Rejection is idempotent, not another execution.
        runCurrent()
        assertEquals(0, calls); assertNotNull(runtime.snapshot().activeRun)
        assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request("next")) }
        cleanup.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (_: ForegroundTranslationStartFailed) { }
        assertEquals(listOf(StoredRunState.FAILED), journal.terminals)
        assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun delayedStartFailureCannotRejectAnAlreadyAcknowledgedExecution() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val release = CompletableDeferred<Unit>()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, _ -> calls++; release.await(); progress(2) })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        runner.startFailed(handle.token); runCurrent()
        assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
        release.complete(Unit)
        assertEquals(2, handle.await().committedItems)
        assertEquals(1, calls); assertEquals(listOf(StoredRunState.SUCCEEDED), journal.terminals)
    }

    @Test fun stopAfterStartRejectionKeepsFailureBeforeAndDuringJournalCleanup() = runTest {
        for (duringCleanup in listOf(false, true)) {
            val runtime = RuntimeCoordinator(); val journal = Journal()
            val cleanup = CompletableDeferred<Unit>(); journal.finishGate = cleanup
            val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
                translate = { _, _, _, _ -> error("A rejected start must not translate") })
            val handle = runner.prepare(request()); runCurrent()
            runner.startFailed(handle.token)
            if (duringCleanup) runCurrent()
            assertTrue(runner.stop(handle.token))
            runCurrent()
            assertNotNull(runtime.snapshot().activeRun)
            assertEquals(ForegroundTranslationPhase.STOPPING, runner.state.value!!.phase)
            cleanup.complete(Unit); runCurrent()
            try { handle.await(); fail() } catch (_: ForegroundTranslationStartFailed) { }
            assertEquals(listOf(StoredRunState.FAILED), journal.terminals)
            assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
            assertNull(runtime.snapshot().activeRun); assertNull(runtime.snapshot().selectedChat)
        }
    }

    @Test fun journalFinalizationFailureRetainsCommittedProgressButDoesNotClaimSuccess() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        journal.beforeFinish = { error("Synthetic journal finalization failure") }
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> progress(2).also(emit) })
        val handle = runner.prepare(request()); runner.foregroundStarted(handle.token)
        try { handle.await(); fail() } catch (_: IllegalStateException) { }
        assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
        assertEquals(2, runner.state.value!!.progress!!.committedItems)
        assertTrue(journal.terminals.isEmpty()); assertNull(runtime.snapshot().activeRun)
    }

    @Test fun stopBlocksNewWorkAndDoesNotReleaseOwnershipBeforeDurableCleanup() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        journal.finishGate = cleanup
        journal.beforeFinish = { assertNotNull(runtime.snapshot().activeRun) }
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> emit(progress(2)); entered.complete(Unit); awaitCancellation() })
        val handle = runner.prepare(request()); runner.foregroundStarted(handle.token); entered.await()
        assertTrue(runner.stop(handle.token)); runCurrent()
        assertEquals(ForegroundTranslationPhase.STOPPING, runner.state.value!!.phase)
        assertEquals(2, runner.state.value!!.progress!!.committedItems)
        assertNotNull(runtime.snapshot().activeRun)
        assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request("next")) }
        cleanup.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        assertNull(runtime.snapshot().activeRun); assertEquals(listOf(StoredRunState.CANCELLED), journal.terminals)
        assertNull(runtime.snapshot().selectedChat)
        val next = runner.prepare(request("next")); runCurrent()
        assertFalse(runner.stop(handle.token)); assertFalse(runner.foregroundStarted(handle.token))
        assertEquals(next.token, runner.state.value!!.token)
        runner.stop(next.token); runCurrent()
    }

    @Test fun alreadyCancelledApplicationScopeStillSettlesItsSynchronousReservation() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val owner = Job().also { it.cancel() }
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal),
            CoroutineScope(coroutineContext + owner), translate = { _, _, _, _ -> calls++; progress() })
        val handle = runner.prepare(request()); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        assertEquals(0, calls); assertEquals(1, journal.began)
        assertNull(runtime.snapshot().activeRun); assertEquals(listOf(StoredRunState.CANCELLED), journal.terminals)
    }

    @Test fun lateProgressFromAFinishedExecutionCannotOverwriteTheNextExecution() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        var oldProgress: ((TranslationProgress) -> Unit)? = null
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> oldProgress = emit; progress(1) })
        val first = runner.prepare(request()); runner.foregroundStarted(first.token); first.await()
        val next = runner.prepare(request("next"))
        val before = runner.state.value
        requireNotNull(oldProgress)(progress(99))
        assertEquals(before, runner.state.value)
        assertEquals(next.token, runner.state.value!!.token)
        runner.stop(next.token); runCurrent()
    }
}
