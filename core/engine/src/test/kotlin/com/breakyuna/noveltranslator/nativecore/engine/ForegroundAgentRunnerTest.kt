package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundAgentRunnerTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private fun request(input: String = "translate", command: String = "c") = ForegroundAgentRequest(ref,
        RunIdentity(ref.projectId, ChatId("chat"), RunId(command), CommandId(command)),
        ModelConfigurationRef("model", 1), input)
    private class Journal : RunJournalPort {
        val states = mutableListOf<StoredRunState>()
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner) =
            RunJournalHandle("journal", ref, run)
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) { states.add(state) }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun recent(ref: LocalProjectRef, limit: Int) = emptyList<StoredRun>()
    }

    @Test fun detachedUiDoesNotCancelTurnAndNewUiAttachesToSameCompletion() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val runner = ForegroundAgentRunner(runtime, DurableRunExecutor(runtime, journal), this,
            runTurn = { _, _, _, emit ->
                calls++; emit(AgentExecutionProgress(true, 1)); release.await(); AgentExecutionProgress(true, 2)
            })
        val handle = runner.prepare(request())
        assertNotNull(runtime.snapshot().activeRun)
        runCurrent(); assertEquals(0, calls)
        assertFalse(runner.foregroundStarted("foreign"))
        assertTrue(runner.foregroundStarted(handle.token))
        val firstUi = launch { handle.await() }
        runCurrent(); firstUi.cancelAndJoin()
        assertEquals(1, calls)
        assertTrue(runner.state.value!!.phase.active)
        val restored = requireNotNull(runner.attach(handle.token))
        release.complete(Unit)
        assertEquals(2L, restored.await().timelineRevision)
        assertEquals(listOf(StoredRunState.SUCCEEDED), journal.states)
        assertNull(runtime.snapshot().activeRun); assertNull(runtime.snapshot().selectedChat)
    }

    @Test fun changedCommandPayloadAndStaleStopCannotAffectCurrentTurn() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundAgentRunner(runtime, DurableRunExecutor(runtime, journal), this,
            runTurn = { _, _, _, _ -> AgentExecutionProgress() })
        val first = runner.prepare(request())
        assertSame(first, runner.prepare(request()))
        assertThrows(ForegroundAgentConflict::class.java) { runner.prepare(request("different")) }
        assertFalse(runner.stop("stale"))
        runner.foregroundStarted(first.token); first.await()
        val second = runner.prepare(request(command = "next"))
        assertFalse(runner.stop(first.token))
        runner.foregroundStarted(second.token); second.await()
        assertEquals(2, journal.states.size)
    }

    @Test fun stoppingBeforeServiceAcknowledgesMakesNoRequestAndJournalsCancellation() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal(); var calls = 0
        val runner = ForegroundAgentRunner(runtime, DurableRunExecutor(runtime, journal), this,
            runTurn = { _, _, _, _ -> calls++; AgentExecutionProgress() })
        val handle = runner.prepare(request())
        runner.stop(handle.token)
        assertFalse(runner.foregroundStarted(handle.token))
        try { handle.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, calls); assertEquals(listOf(StoredRunState.CANCELLED), journal.states)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun rejectedStartCannotBeReclassifiedByLateStopAndApprovalWaitIsDurable() = runTest {
        val runtime = RuntimeCoordinator(); val journal = Journal()
        val runner = ForegroundAgentRunner(runtime, DurableRunExecutor(runtime, journal), this,
            runTurn = { _, _, recorder, _ -> recorder.waitingForApproval(); AgentExecutionProgress(true) })
        val first = runner.prepare(request())
        runner.startFailed(first.token); runner.stop(first.token)
        try { first.await(); fail("Expected rejected start") } catch (_: ForegroundAgentStartFailed) { }
        assertEquals(listOf(StoredRunState.FAILED), journal.states)
        val second = runner.prepare(request(command = "approval"))
        runner.foregroundStarted(second.token); second.await()
        assertEquals(StoredRunState.WAITING_APPROVAL, journal.states.last())
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun snapshotsNeverRenderInputInDiagnostics() {
        val request = request("private material")
        assertFalse(request.toString().contains("private material"))
        assertFalse(ForegroundAgentSnapshot(1, "token", request, ForegroundAgentPhase.RUNNING)
            .toString().contains("private material"))
    }
}
