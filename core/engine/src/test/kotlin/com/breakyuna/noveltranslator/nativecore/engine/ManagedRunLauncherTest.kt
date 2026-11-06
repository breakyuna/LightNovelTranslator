package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedRunLauncherTest {
    private val chat = ChatIdentity(ProjectId("project"), ChatId("chat"))
    private val identity = RunIdentity(chat.projectId, chat.chatId, RunId("one"), CommandId("one"))

    @Test
    fun reservesSynchronouslyAndStopBeforeDispatchPreventsWork() = runTest {
        val runtime = runtime()
        var calls = 0
        val execution = ManagedRunLauncher(runtime).start(this, identity, RuntimeOwner.AGENT) {
            calls += 1
        }
        assertNotNull(runtime.snapshot().activeRun)
        assertThrows(RuntimeUnavailable::class.java) { runtime.selectChat(null) }
        assertTrue(execution.stop())
        execution.join()
        assertEquals(0, calls)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun alreadyCancelledParentDoesNotLeakReservation() = runTest {
        val runtime = runtime()
        val parent = Job().also { it.cancel() }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        var calls = 0
        val execution = ManagedRunLauncher(runtime).start(scope, identity, RuntimeOwner.AGENT) {
            calls += 1
        }
        execution.join()
        assertEquals(0, calls)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun stopWaitsForNonCancellableHostCleanupBeforeAllowingAnotherRun() = runTest {
        val runtime = runtime()
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val execution = ManagedRunLauncher(runtime).start(this, identity, RuntimeOwner.AGENT) { work ->
            work.operation(OperationKind.HOST_REQUEST) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finishCleanup.await()
                        cleaned.complete(Unit)
                    }
                }
            }
        }
        entered.await()
        assertTrue(execution.stop())
        runCurrent()
        assertFalse(cleaned.isCompleted)
        assertNotNull(runtime.snapshot().activeRun)
        assertThrows(RuntimeUnavailable::class.java) {
            runtime.beginRun(identity.copy(runId = RunId("two")), RuntimeOwner.AGENT)
        }
        finishCleanup.complete(Unit)
        execution.join()
        assertTrue(cleaned.isCompleted)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun finishingBodyWaitsForStructuredChildWork() = runTest {
        val runtime = runtime()
        val finishChild = CompletableDeferred<Unit>()
        val execution = ManagedRunLauncher(runtime).start(this, identity, RuntimeOwner.AGENT) { work ->
            launch {
                work.operation(OperationKind.SCRIPT) { finishChild.await() }
            }
        }
        runCurrent()
        assertEquals(1, runtime.snapshot().activeRun?.outstandingOperations)
        finishChild.complete(Unit)
        execution.join()
        assertNull(runtime.snapshot().activeRun)
    }

    @Test
    fun snapshotCollectorDoesNotRunUnderTheReservationMonitor() = runTest {
        val runtime = runtime()
        val pool = Executors.newSingleThreadExecutor()
        var observed = false
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.snapshots.drop(1).take(1).collect { published ->
                val concurrentRead = pool.submit<RunIdentity?> { runtime.snapshot().activeRun?.identity }
                assertEquals(published.activeRun?.identity, concurrentRead.get(5, TimeUnit.SECONDS))
                observed = true
            }
        }
        try {
            val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
            collector.join()
            assertTrue(observed)
            runtime.finishRun(lease)
        } finally {
            collector.cancel()
            pool.shutdownNow()
        }
    }

    @Test
    fun preparedWriteReleasesOnFailureAndStaleStopDoesNotCancelNextRun() = runTest {
        val runtime = runtime()
        var failureObserved = false
        val launcher = ManagedRunLauncher(runtime)
        val first = launcher.start(this, identity, RuntimeOwner.AGENT) { work ->
            try {
                work.projectWrite<Unit> { throw IllegalStateException("synthetic write failure") }
            } catch (_: IllegalStateException) {
                failureObserved = true
            }
        }
        first.join()
        assertTrue(failureObserved)
        assertFalse(runtime.snapshot().projectWriteRunning)
        val second = launcher.start(this, identity.copy(runId = RunId("two")), RuntimeOwner.AGENT) {
            awaitCancellation()
        }
        assertFalse(first.stop())
        assertEquals(second.identity, runtime.snapshot().activeRun?.identity)
        assertTrue(second.stop())
        second.join()
        assertNull(runtime.snapshot().activeRun)
    }

    private fun runtime() = RuntimeCoordinator().also { it.selectChat(chat) }
}
