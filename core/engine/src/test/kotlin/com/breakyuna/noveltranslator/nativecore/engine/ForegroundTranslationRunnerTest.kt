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
        var finishFailure: Exception? = null
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle {
            began++; return RunJournalHandle("execution-$began", ref, run)
        }
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) {
            beforeFinish(); finishGate?.await(); finishFailure?.let { throw it }; terminals.add(state)
        }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun recent(ref: LocalProjectRef, limit: Int) = emptyList<StoredRun>()
    }

    @Test fun forcedStopDuringInflightPauseFinishesCancelled() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, lease, _, emit ->
                emit(progress())
                val operation = runtime.beginOperation(lease, OperationKind.MODEL_REQUEST)
                try { awaitCancellation() } finally { runtime.endOperation(operation) }
            })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        runner.foregroundStarted(handle.token); runCurrent()
        assertTrue(runner.pause(handle.token)); assertTrue(runner.stop(handle.token)); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
        assertEquals(listOf(StoredRunState.CANCELLED), journal.terminals)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun providerFailureDuringPauseIsNotReportedAsPaused() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val response = CompletableDeferred<Unit>()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, lease, _, emit ->
                emit(progress())
                val operation = runtime.beginOperation(lease, OperationKind.MODEL_REQUEST)
                try { response.await(); throw IllegalStateException("provider failed") }
                finally { runtime.endOperation(operation) }
            })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        runner.foregroundStarted(handle.token); runCurrent()
        assertTrue(runner.pause(handle.token))
        response.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (failure: IllegalStateException) {
            assertEquals("provider failed", failure.message)
        }
        assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
        assertEquals(listOf(StoredRunState.FAILED), journal.terminals)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun repeatedExplicitResumeAfterPauseUsesANewTokenEvenWithoutRevisionAdvance() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> emit(progress()); awaitCancellation() })
        val continued = request().copy(options = RemoteTranslationOptions("en"), continuation = true)
        val first = runner.prepare(continued)
        runner.foregroundStarted(first.token); runCurrent()
        assertTrue(runner.pause(first.token)); runCurrent()
        try { first.await(); fail() } catch (_: ForegroundTranslationPaused) { }
        val next = runner.prepare(continued)
        assertNotEquals(first.token, next.token)
        assertSame(next, runner.prepare(continued))
        assertFalse(runner.pause(first.token))
        runner.stop(next.token); runCurrent()
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun failedPauseJournalFinalizationIsAFailureRatherThanADurablePause() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        journal.finishFailure = IllegalStateException("journal unavailable")
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> emit(progress()); awaitCancellation() })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        runner.foregroundStarted(handle.token); runCurrent()
        runner.pause(handle.token); runCurrent()
        try { handle.await(); fail() } catch (failure: IllegalStateException) {
            assertEquals("journal unavailable", failure.message)
        }
        assertEquals(ForegroundTranslationPhase.FAILED, runner.state.value!!.phase)
        assertTrue(journal.terminals.isEmpty())
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun pauseWaitsForInflightResponseAndFinishesWithDurablePausedState() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val response = CompletableDeferred<Unit>(); var saved = false
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, lease, _, emit ->
                emit(progress())
                val operation = runtime.beginOperation(lease, OperationKind.MODEL_REQUEST)
                try { response.await(); saved = true } finally { runtime.endOperation(operation) }
                progress()
            })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        assertFalse(runner.pause(handle.token))
        runner.foregroundStarted(handle.token); runCurrent()
        assertTrue(runner.pause(handle.token)); runCurrent()
        assertFalse(saved)
        assertEquals(ForegroundTranslationPhase.PAUSING, runner.state.value!!.phase)
        assertTrue(journal.terminals.isEmpty())
        response.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (_: ForegroundTranslationPaused) { }
        assertTrue(saved)
        assertEquals(listOf(StoredRunState.PAUSED), journal.terminals)
        assertEquals(ForegroundTranslationPhase.PAUSED, runner.state.value!!.phase)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun pauseCancelsAWaiterBeforeAnyNewModelOperationStarts() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, emit -> emit(progress()); awaitCancellation() })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        runner.foregroundStarted(handle.token); runCurrent()
        assertFalse(runner.pause("stale-token"))
        assertTrue(runner.pause(handle.token)); runCurrent()
        try { handle.await(); fail() } catch (_: ForegroundTranslationPaused) { }
        assertEquals(listOf(StoredRunState.PAUSED), journal.terminals)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun pauseIsAvailableBeforeTheFirstProgressCallback() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val entered = CompletableDeferred<Unit>()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, _ -> entered.await(); progress() })
        val handle = runner.prepare(request().copy(options = RemoteTranslationOptions("en")))
        runner.foregroundStarted(handle.token); runCurrent()
        entered.complete(Unit)
        assertNull(runner.state.value!!.progress)
        assertTrue(runner.pause(handle.token)); runCurrent()
        try { handle.await(); fail() } catch (_: ForegroundTranslationPaused) { }
        assertEquals(listOf(StoredRunState.PAUSED), journal.terminals)
        assertNull(runtime.snapshot().activeRun)
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
    @Test fun explicitContinuationOnlyAdvancesRevisionAndStartsANewTokenAfterSettlement() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, journal), this,
            translate = { _, _, _, _ -> calls++; progress() })
        val initial = request().copy(options = RemoteTranslationOptions("en"))
        val first = runner.prepare(initial)
        runner.foregroundStarted(first.token); first.await()
        val continued = initial.copy(expectedRevision = 2, continuation = true)
        val second = runner.prepare(continued)
        assertNotEquals(first.token, second.token)
        assertSame(second, runner.prepare(continued))
        runner.foregroundStarted(second.token); second.await()
        assertEquals(2, calls)
        assertThrows(ForegroundTranslationConflict::class.java) {
            runner.prepare(continued.copy(expectedRevision = 3, options = RemoteTranslationOptions("ja")))
        }
        assertEquals(2, calls)
    }

}
