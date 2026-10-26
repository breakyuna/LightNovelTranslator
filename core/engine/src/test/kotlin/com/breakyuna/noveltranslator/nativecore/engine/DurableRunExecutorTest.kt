package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DurableRunExecutorTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val identity = RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("command"))
    private fun runtime() = RuntimeCoordinator().also { it.selectChat(ChatIdentity(ref.projectId, identity.chatId!!)) }
    private class Journal : RunJournalPort {
        var began = false
        var failBegin = false
        var state: StoredRunState? = null
        var beforeFinish: suspend () -> Unit = {}
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle {
            if (failBegin) error("Synthetic journal failure")
            began = true
            return RunJournalHandle("execution", ref, run)
        }
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) { beforeFinish(); this.state = state }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun find(ref: LocalProjectRef, run: RunIdentity): StoredRun? = null
        override suspend fun recent(ref: LocalProjectRef, limit: Int): List<StoredRun> = emptyList()
    }

    @Test fun failureToJournalPreventsWorkAndReleasesReservation() = runTest {
        val runtime = runtime(); val journal = Journal().apply { failBegin = true }
        var calls = 0
        try { DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { _, _ -> calls++ }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, calls); assertNull(runtime.snapshot().activeRun)
    }

    @Test fun pendingApprovalIsDurableBeforeOccupancyIsReleased() = runTest {
        val runtime = runtime(); val journal = Journal()
        journal.beforeFinish = { assertNotNull(runtime.snapshot().activeRun) }
        DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { _, recorder ->
            assertTrue(journal.began); recorder.waitingForApproval()
        }
        assertEquals(StoredRunState.WAITING_APPROVAL, journal.state)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun nonThrowingApprovalResultsDetermineExecutionState() = runTest {
        for ((approval, expected) in listOf(
            TextApprovalState.COMMITTED to StoredRunState.SUCCEEDED,
            TextApprovalState.CONFLICT to StoredRunState.FAILED,
            TextApprovalState.CANCELLED to StoredRunState.CANCELLED,
            TextApprovalState.REJECTED to StoredRunState.CANCELLED,
            TextApprovalState.PENDING to StoredRunState.WAITING_APPROVAL,
            TextApprovalState.CONSUMED to StoredRunState.WAITING_APPROVAL,
        )) {
            val runtime = runtime(); val journal = Journal()
            journal.beforeFinish = { assertNotNull(runtime.snapshot().activeRun) }
            val result = DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { _, recorder ->
                recorder.approvalResult(approval)
                approval
            }
            assertEquals(approval, result); assertEquals(expected, journal.state)
            assertNull(runtime.snapshot().activeRun)
            runtime.close()
        }
    }

    @Test fun cancellationWaitsForChildrenAndJournalsBeforeReleasingRun() = runTest {
        val runtime = runtime(); val journal = Journal()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        journal.beforeFinish = { assertNotNull(runtime.snapshot().activeRun) }
        val job = launch {
            DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { _, _ ->
                coroutineScope { launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
            }
        }
        entered.await(); job.cancel(); runCurrent()
        assertNotNull(runtime.snapshot().activeRun); assertNull(journal.state)
        release.complete(Unit); job.join()
        assertEquals(StoredRunState.CANCELLED, journal.state); assertNull(runtime.snapshot().activeRun)
    }

    @Test fun stopWithoutCoroutineCancellationCannotReportSuccess() = runTest {
        val runtime = runtime(); val journal = Journal()
        try {
            DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { lease, _ -> runtime.requestStop(lease) }
            fail()
        } catch (_: CancellationException) { }
        assertEquals(StoredRunState.CANCELLED, journal.state); assertNull(runtime.snapshot().activeRun)
    }

    @Test fun journalCleanupFailureDoesNotReplaceCancellationOrLeakReservation() = runTest {
        val runtime = runtime(); val journal = Journal()
        journal.beforeFinish = { error("Synthetic finish failure") }
        try {
            DurableRunExecutor(runtime, journal).execute(ref, identity, RuntimeOwner.AGENT) { _, _ -> throw CancellationException("Stopped") }
            fail()
        } catch (_: CancellationException) { }
        assertNull(runtime.snapshot().activeRun)
    }
}
