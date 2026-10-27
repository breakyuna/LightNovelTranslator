package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWorkspaceRunnerTest {
    private val ref = LocalProjectRef(
        ProjectId("project"),
        ProjectInstanceId("instance"),
        RecoveryGeneration(1),
    )
    private val scriptSha = "a".repeat(64)

    private fun request(
        command: String = "start",
        inputJson: String = "{}",
        policy: ForegroundWorkspacePolicy = ForegroundWorkspacePolicy(),
        allowFetch: Boolean = false,
    ) = ForegroundWorkspaceRequest(
        ref = ref,
        identity = RunIdentity(ref.projectId, ChatId("chat"), RunId(command), CommandId(command)),
        skillId = "fixture-skill",
        entryPoint = "scripts/main.js",
        scriptSha256 = scriptSha,
        scriptSizeBytes = 128,
        projectRevision = 7,
        policy = policy,
        inputJson = inputJson,
        allowFetch = allowFetch,
    )

    private fun result(summary: String = "ok") = ForegroundWorkspaceExecutionResult(summary)

    @Test
    fun prepareAttachesAndWaitsForOneTimeForegroundAcknowledgement() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, observer ->
            calls += 1
            observer.stage(ForegroundWorkspaceStage.SCRIPT)
            observer.log("working")
            entered.complete(Unit)
            release.await()
            result()
        })
        val handle = runner.prepare(request())
        assertEquals(ForegroundWorkspacePhase.PREPARING, runner.state.value!!.phase)
        assertTrue(runner.accepts(handle.token))
        assertSame(handle, runner.attach(handle.token))
        runCurrent()
        assertEquals(0, calls)

        assertFalse(runner.foregroundStarted("foreign-token"))
        assertTrue(runner.foregroundStarted(handle.token))
        assertFalse(runner.foregroundStarted(handle.token)) // start acknowledgement is one-shot

        entered.await()
        val running = requireNotNull(runner.state.value)
        assertEquals(ForegroundWorkspacePhase.RUNNING, running.phase)
        assertEquals(ForegroundWorkspaceStage.SCRIPT, running.observation.stage)
        assertEquals(listOf("working"), running.observation.logs)
        release.complete(Unit)
        assertEquals("ok", handle.await().summary)
        assertEquals(1, calls)
        assertEquals(ForegroundWorkspacePhase.COMPLETED, runner.state.value!!.phase)
        assertFalse(runner.accepts(handle.token))
        assertSame(handle, runner.attach(handle.token)) // terminal state can still be re-read
    }

    @Test
    fun cancellingAUiWaiterOnlyDetachesAndLaterAttachmentReceivesTheResult() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            entered.complete(Unit)
            release.await()
            result("finished")
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        entered.await()

        val waiter = launch { handle.await() }
        runCurrent()
        waiter.cancel()
        waiter.join()
        assertTrue(runner.accepts(handle.token))

        val attached = requireNotNull(runner.attach(handle.token))
        release.complete(Unit)
        assertEquals("finished", attached.await().summary)
        assertEquals(ForegroundWorkspacePhase.COMPLETED, runner.state.value!!.phase)
    }

    @Test
    fun stopKeepsTheSlotUntilTheAdapterCleanupFinishes() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupGate.await()
                    cleaned.complete(Unit)
                }
            }
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        entered.await()

        assertTrue(runner.stop(handle.token))
        assertTrue(runner.stop(handle.token)) // repeated stop is idempotent for the same token
        assertEquals(ForegroundWorkspacePhase.STOPPING, runner.state.value!!.phase)
        runCurrent()
        assertFalse(cleaned.isCompleted)
        assertTrue(runner.accepts(handle.token))
        assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request("next")) }

        cleanupGate.complete(Unit)
        runCurrent()
        assertTrue(cleaned.isCompleted)
        try {
            handle.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(ForegroundWorkspacePhase.CANCELLED, runner.state.value!!.phase)
        assertFalse(runner.accepts(handle.token))

        val next = runner.prepare(request("next"))
        assertNotEquals(handle.token, next.token)
        assertTrue(runner.stop(next.token))
        try {
            next.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(ForegroundWorkspacePhase.CANCELLED, runner.state.value!!.phase)
    }

    @Test
    fun expiredAndMismatchedTokensCannotAcknowledgeOrStopTheExecution() = runTest {
        var now = 1_000L
        var calls = 0
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            calls += 1
            result()
        }, startTimeoutMillis = 100, clock = { now })
        val handle = runner.prepare(request())
        now += 101

        assertFalse(runner.foregroundStarted("foreign-token"))
        assertFalse(runner.stop("foreign-token"))
        assertFalse(runner.foregroundStarted(handle.token))
        assertEquals(ForegroundWorkspacePhase.STOPPING, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.START_TIMEOUT, runner.state.value!!.failure)

        try {
            handle.await()
            fail()
        } catch (failure: ForegroundWorkspaceTimedOut) {
            assertEquals(ForegroundWorkspaceTimeoutStage.START, failure.stage)
        }
        assertEquals(ForegroundWorkspacePhase.FAILED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.START_TIMEOUT, runner.state.value!!.failure)
        assertEquals(0, calls)
        assertFalse(runner.accepts(handle.token))
    }

    @Test
    fun startFailureIsTerminalAndNeverReachesTheAdapter() = runTest {
        var calls = 0
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            calls += 1
            result()
        })
        val handle = runner.prepare(request())
        runCurrent()
        runner.startFailed(handle.token)
        assertEquals(ForegroundWorkspacePhase.STOPPING, runner.state.value!!.phase)
        assertFalse(runner.foregroundStarted(handle.token))
        runner.startFailed(handle.token) // repeated rejection does not create another execution
        runCurrent()

        try {
            handle.await()
            fail()
        } catch (_: ForegroundWorkspaceStartFailed) {
        }
        assertEquals(ForegroundWorkspacePhase.FAILED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.START_FAILED, runner.state.value!!.failure)
        assertEquals(0, calls)
    }

    @Test
    fun duplicateCommandAttachesWhileConcurrentNewWorkIsRejected() = runTest {
        var calls = 0
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            calls += 1
            result()
        })
        val original = request()
        val handle = runner.prepare(original)
        assertSame(handle, runner.prepare(original))
        assertThrows(ForegroundWorkspaceConflict::class.java) {
            runner.prepare(original.copy(inputJson = """{"different":true}"""))
        }
        assertThrows(RuntimeUnavailable::class.java) { runner.prepare(request("other")) }

        assertTrue(runner.stop(handle.token))
        try {
            handle.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(0, calls)
        assertEquals(ForegroundWorkspacePhase.CANCELLED, runner.state.value!!.phase)
        // Repeating a finished command returns the recorded handle instead of replaying it.
        assertSame(handle, runner.prepare(original))
        assertEquals(ForegroundWorkspacePhase.CANCELLED, runner.state.value!!.phase)
    }

    @Test
    fun lateObserverEventsCannotOverwriteANewerExecution() = runTest {
        var firstObserver: ForegroundWorkspaceObserver? = null
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { request, observer ->
            if (request.identity.commandId.value == "one") {
                firstObserver = observer
                result("first")
            } else {
                awaitCancellation()
            }
        })
        val first = runner.prepare(request("one"))
        assertTrue(runner.foregroundStarted(first.token))
        first.await()
        val second = runner.prepare(request("two"))
        runCurrent()
        val before = requireNotNull(runner.state.value)

        val stale = requireNotNull(firstObserver)
        assertFalse(runner.stop(first.token))
        stale.stage(ForegroundWorkspaceStage.APPLY)
        stale.log("late line")
        stale.artifact(ForegroundWorkspaceArtifact("work/late.txt", 4))
        assertEquals(before, runner.state.value)
        assertEquals(second.token, runner.state.value!!.token)
        assertTrue(runner.state.value!!.observation.logs.isEmpty())

        assertTrue(runner.stop(second.token))
        try {
            second.await()
            fail()
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun observerTailIsBoundedAndCommonCredentialShapesAreRedacted() = runTest {
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, observer ->
            repeat(100) { index ->
                observer.log("line-$index api_key=super-secret Bearer abcdefghijklmnop")
            }
            repeat(40) { index ->
                observer.artifact(ForegroundWorkspaceArtifact("work/$index.txt", index.toLong()))
            }
            result()
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        handle.await()

        val observation = requireNotNull(runner.state.value).observation
        assertEquals(ForegroundWorkspaceLimits.MAX_LOG_LINES, observation.logs.size)
        assertTrue(observation.logsTruncated)
        assertTrue(observation.logs.none { it.startsWith("line-0 ") })
        assertTrue(observation.logs.none { "super-secret" in it })
        assertTrue(observation.logs.none { "abcdefghijklmnop" in it })
        assertTrue(observation.logs.all { it.length <= ForegroundWorkspaceLimits.MAX_LOG_CHARS })
        assertEquals(ForegroundWorkspaceLimits.MAX_ARTIFACTS, observation.artifacts.size)
        assertTrue(observation.artifactsTruncated)
        assertEquals("work/8.txt", observation.artifacts.first().path)
        assertEquals("work/39.txt", observation.artifacts.last().path)
    }

    @Test
    fun requestAndSnapshotTextNeverContainTheSkillInput() = runTest {
        val serverInput = """{"api_key":"do-not-log-this"}"""
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, observer ->
            observer.log("ordinary line")
            result()
        })
        val preparedRequest = request(inputJson = serverInput)
        assertFalse("do-not-log-this" in preparedRequest.toString())
        val handle = runner.prepare(preparedRequest)
        assertTrue(runner.foregroundStarted(handle.token))
        handle.await()
        val snapshot = requireNotNull(runner.state.value)
        assertFalse("do-not-log-this" in snapshot.toString())
        assertFalse("ordinary line" in snapshot.toString())
    }

    @Test
    fun ownerScopeCancellationSettlesAsInterruptedRatherThanCancelled() = runTest {
        val owner = Job()
        val ownerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner)
        val entered = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(ownerScope, WorkspaceExecutionPort { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        entered.await()
        owner.cancel()
        runCurrent()

        try {
            handle.await()
            fail()
        } catch (_: ForegroundWorkspaceInterrupted) {
        }
        assertEquals(ForegroundWorkspacePhase.INTERRUPTED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.INTERRUPTED, runner.state.value!!.failure)
        assertFalse(runner.accepts(handle.token))
    }

    @Test
    fun adapterFailureSettlesAsFailedAndReleasesTheSlot() = runTest {
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            throw IllegalStateException("script failed")
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        try {
            handle.await()
            fail()
        } catch (failure: IllegalStateException) {
            assertEquals("script failed", failure.message)
        }
        assertEquals(ForegroundWorkspacePhase.FAILED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.EXECUTION_FAILED, runner.state.value!!.failure)
        assertFalse(runner.accepts(handle.token))
    }

    @Test
    fun policyTimeoutProducesAnExplicitFailureWithoutAutomaticReplay() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            calls += 1
            entered.complete(Unit)
            awaitCancellation()
        })
        val preparedRequest = request(policy = ForegroundWorkspacePolicy(maxRunMillis = 100))
        val handle = runner.prepare(preparedRequest)
        assertTrue(runner.foregroundStarted(handle.token))
        entered.await()
        advanceTimeBy(101)
        runCurrent()

        try {
            handle.await()
            fail()
        } catch (failure: ForegroundWorkspaceTimedOut) {
            assertEquals(ForegroundWorkspaceTimeoutStage.EXECUTION, failure.stage)
        }
        assertEquals(ForegroundWorkspacePhase.FAILED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.EXECUTION_TIMEOUT, runner.state.value!!.failure)
        assertEquals(1, calls)
        assertSame(handle, runner.prepare(preparedRequest))
        assertEquals(1, calls)
    }

    @Test
    fun virtualStartTimeoutFailsBeforeTheAdapterRuns() = runTest {
        var calls = 0
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            calls += 1
            result()
        }, startTimeoutMillis = 100)
        val handle = runner.prepare(request())
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        try {
            handle.await()
            fail()
        } catch (failure: ForegroundWorkspaceTimedOut) {
            assertEquals(ForegroundWorkspaceTimeoutStage.START, failure.stage)
        }
        assertEquals(ForegroundWorkspacePhase.FAILED, runner.state.value!!.phase)
        assertEquals(ForegroundWorkspaceFailureReason.START_TIMEOUT, runner.state.value!!.failure)
        assertEquals(0, calls)
        assertFalse(runner.foregroundStarted(handle.token))
    }

    @Test
    fun executionUsesOnlyTheInjectedWorkspacePort() = runTest {
        var portCalls = 0
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            portCalls += 1
            result()
        })
        val handle = runner.prepare(request())
        assertTrue(runner.foregroundStarted(handle.token))
        handle.await()
        assertEquals(1, portCalls)
    }

    @Test
    fun fetchRequiresTheMatchingSkillPolicyGrant() {
        assertThrows(IllegalArgumentException::class.java) {
            request(allowFetch = true)
        }
        val preparedRequest = request(
            policy = ForegroundWorkspacePolicy(allowFetch = true),
            allowFetch = true,
        )
        assertTrue(preparedRequest.allowFetch)
        assertTrue(preparedRequest.policy.grants(ForegroundWorkspaceCapability.FETCH))
    }
}
