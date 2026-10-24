package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic host/runner lifecycle tests; no Android notification or device proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeAgentHostTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun request(command: String = "start") = ForegroundAgentRequest(ref,
        RunIdentity(ref.projectId, ChatId("chat"), RunId(command), CommandId(command)), ModelConfigurationRef("fixture", 1), "input")
    private fun progress() = AgentExecutionProgress(true, 2)

    private class Journal : RunJournalPort {
        var began = 0
        var finishGate: CompletableDeferred<Unit>? = null
        val terminals = mutableListOf<StoredRunState>()
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle {
            began++; return RunJournalHandle("execution-$began", ref, run)
        }
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) {
            finishGate?.await(); terminals.add(state)
        }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun recent(ref: LocalProjectRef, limit: Int) = emptyList<StoredRun>()
    }

    private class Fixture(ownerScope: CoroutineScope, hostScope: CoroutineScope = ownerScope,
        work: suspend () -> AgentExecutionProgress) {
        val runtime = RuntimeCoordinator()
        val journal = Journal()
        val runner = ForegroundAgentRunner(runtime, DurableRunExecutor(runtime, journal), ownerScope,
            runTurn = { _, _, _, _ -> work() })
        val promoted = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val dismissed = mutableListOf<Int>()
        var failPromotion = false
        var failUpdate = false
        var beforePromotion: () -> Unit = {}
        val host = NativeAgentHost(runner, hostScope,
            promote = {
                beforePromotion()
                if (failPromotion) error("Synthetic foreground promotion failure")
                promoted.add(it.token)
            },
            update = {
                if (failUpdate) error("Synthetic notification update failure")
                updated.add(it.token)
            }, dismiss = { dismissed.add(it) })
    }

    @Test fun foregroundPromotionPrecedesWorkAndDuplicateStartsDoNotReplayIt() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val fixture = Fixture(this) { calls++; release.await(); progress() }
        fixture.beforePromotion = { assertEquals(0, calls) }
        val handle = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 1)
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 2)
        runCurrent()
        assertEquals(1, calls); assertEquals(listOf(handle.token, handle.token), fixture.promoted)
        assertTrue(fixture.dismissed.isEmpty())
        release.complete(Unit); handle.await(); runCurrent()
        assertEquals(listOf(2), fixture.dismissed)
        assertEquals(1, fixture.journal.began)
        assertEquals(listOf(StoredRunState.SUCCEEDED), fixture.journal.terminals)
        fixture.host.destroy()
    }

    @Test fun unownedNullStaleAndUnknownCommandsExitWithoutAcknowledgingAPendingExecution() = runTest {
        var calls = 0
        val fixture = Fixture(this) { calls++; progress() }
        val handle = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.IGNORE, null, 1)
        fixture.host.command(NativeAgentHost.Command.START, "obsolete", 2)
        fixture.host.command(NativeAgentHost.Command.IGNORE, handle.token, 3)
        assertEquals(listOf(1, 2, 3), fixture.dismissed)
        assertTrue(fixture.promoted.isEmpty()); assertEquals(0, calls)
        assertEquals(ForegroundAgentPhase.WAITING_FOR_SERVICE, fixture.runner.state.value!!.phase)
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 4)
        handle.await(); runCurrent()
        assertEquals(1, calls); assertEquals(listOf(1, 2, 3, 4), fixture.dismissed)
        fixture.host.destroy()
    }

    @Test fun staleStopCannotCancelAnOwnedRunAndMatchingStopKeepsTheHostUntilCleanup() = runTest {
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        val fixture = Fixture(this) { entered.complete(Unit); awaitCancellation() }
        fixture.journal.finishGate = cleanup
        val handle = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 1)
        entered.await(); runCurrent()
        fixture.host.command(NativeAgentHost.Command.STOP, "obsolete", 2)
        assertEquals(ForegroundAgentPhase.RUNNING, fixture.runner.state.value!!.phase)
        assertTrue(fixture.dismissed.isEmpty())
        fixture.host.command(NativeAgentHost.Command.STOP, handle.token, 3)
        runCurrent()
        assertEquals(ForegroundAgentPhase.STOPPING, fixture.runner.state.value!!.phase)
        assertNotNull(fixture.runtime.snapshot().activeRun); assertTrue(fixture.dismissed.isEmpty())
        fixture.host.command(NativeAgentHost.Command.START, "obsolete", 4)
        cleanup.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        assertEquals(listOf(4), fixture.dismissed)
        assertEquals(listOf(StoredRunState.CANCELLED), fixture.journal.terminals)
        assertNull(fixture.runtime.snapshot().activeRun)
        fixture.host.destroy()
    }

    @Test fun conflatedTerminalAndNewPreparationReleaseOnlyTheOldHost() = runTest {
        val executionScope = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(executionScope, this) { progress() }
        val first = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, first.token, 1)
        first.await() // Execution finishes inline; the host observer has not run yet.
        val next = fixture.runner.prepare(request("next"))
        runCurrent()
        assertEquals(listOf(1), fixture.dismissed)
        assertEquals(next.token, fixture.runner.state.value!!.token)
        assertEquals(ForegroundAgentPhase.WAITING_FOR_SERVICE, fixture.runner.state.value!!.phase)
        fixture.host.destroy() // Destruction of the old host must not stop this new reservation.
        assertTrue(fixture.runner.accepts(next.token))
        fixture.runner.stop(next.token); runCurrent()
        assertNull(fixture.runtime.snapshot().activeRun)
    }

    @Test fun newStartReplacesAnOldObserverBeforeItsTerminalEmissionArrives() = runTest {
        val executionScope = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(executionScope, this) { progress() }
        val first = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, first.token, 1); first.await()
        val next = fixture.runner.prepare(request("next"))
        fixture.host.command(NativeAgentHost.Command.START, next.token, 2); next.await()
        runCurrent()
        assertEquals(listOf(first.token, next.token), fixture.promoted)
        assertEquals(listOf(2), fixture.dismissed)
        assertEquals(2, fixture.journal.began)
        fixture.host.destroy()
    }

    @Test fun foregroundPromotionFailureNeverOpensTheBusinessGate() = runTest {
        var calls = 0
        val fixture = Fixture(this) { calls++; progress() }
        fixture.failPromotion = true
        val handle = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 1)
        assertFalse(fixture.runner.foregroundStarted(handle.token))
        runCurrent()
        try { handle.await(); fail() } catch (_: ForegroundAgentStartFailed) { }
        assertEquals(0, calls); assertEquals(listOf(1), fixture.dismissed)
        assertEquals(listOf(StoredRunState.FAILED), fixture.journal.terminals)
        assertNull(fixture.runtime.snapshot().activeRun)
        fixture.host.destroy()
    }

    @Test fun notificationFailureReleasesTheHostButRetainsReservationUntilCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val fixture = Fixture(this) { awaitCancellation() }
        fixture.journal.finishGate = cleanup; fixture.failUpdate = true
        val handle = fixture.runner.prepare(request())
        fixture.host.command(NativeAgentHost.Command.START, handle.token, 1)
        runCurrent()
        assertEquals(listOf(1), fixture.dismissed)
        assertEquals(ForegroundAgentPhase.STOPPING, fixture.runner.state.value!!.phase)
        assertNotNull(fixture.runtime.snapshot().activeRun)
        cleanup.complete(Unit); runCurrent()
        try { handle.await(); fail() } catch (_: CancellationException) { }
        val next = fixture.runner.prepare(request("next"))
        fixture.host.destroy()
        assertEquals(ForegroundAgentPhase.WAITING_FOR_SERVICE, fixture.runner.state.value!!.phase)
        fixture.runner.stop(next.token); runCurrent()
        assertNull(fixture.runtime.snapshot().activeRun)
    }

    @Test fun timeoutAndDestructionCancelOnlyOwnedWorkWhileCleanupRemainsApplicationOwned() = runTest {
        for (timeout in listOf(true, false)) {
            val cleanup = CompletableDeferred<Unit>()
            val fixture = Fixture(this) { awaitCancellation() }
            fixture.journal.finishGate = cleanup
            val handle = fixture.runner.prepare(request())
            fixture.host.command(NativeAgentHost.Command.START, handle.token, 1); runCurrent()
            if (timeout) fixture.host.timeout() else fixture.host.destroy()
            runCurrent()
            assertEquals(if (timeout) listOf(1) else emptyList<Int>(), fixture.dismissed)
            assertEquals(ForegroundAgentPhase.STOPPING, fixture.runner.state.value!!.phase)
            assertNotNull(fixture.runtime.snapshot().activeRun)
            cleanup.complete(Unit); runCurrent()
            try { handle.await(); fail() } catch (_: CancellationException) { }
            assertNull(fixture.runtime.snapshot().activeRun)
            assertEquals(listOf(StoredRunState.CANCELLED), fixture.journal.terminals)
            fixture.host.destroy()
        }
    }
}
