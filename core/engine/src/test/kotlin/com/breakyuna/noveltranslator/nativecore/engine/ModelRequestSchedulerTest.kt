package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelRequestSchedulerTest {
    private val configuration = ModelConfigurationRef("model", 1)

    @Test fun fifoCancellationDoesNotConsumeTheNextRpmPermit() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val first = launch { scheduler.execute(configuration) { order.add(1); release.await() } }
        runCurrent()
        val cancelled = launch { scheduler.execute(configuration) { order.add(2) } }
        val third = launch { scheduler.execute(configuration) { order.add(3) } }
        runCurrent()
        cancelled.cancelAndJoin()
        release.complete(Unit)
        first.join()
        advanceTimeBy(999); runCurrent()
        assertEquals(listOf(1), order)
        advanceTimeBy(1); runCurrent()
        third.join()
        assertEquals(listOf(1, 3), order)
    }

    @Test fun concurrencyAndHiddenRpsUseTheSameDispatchBoundary() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(2) }, { testScheduler.currentTime })
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val jobs = (1..3).map { launch { scheduler.execute(configuration) { starts++; release.await() } } }
        runCurrent()
        assertEquals(2, starts)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, starts)
        advanceTimeBy(500); runCurrent()
        jobs.forEach { it.join() }
        assertEquals(3, starts)
    }

    @Test fun cancellingActiveWorkReleasesConcurrencyWithoutResettingRate() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        val release = CompletableDeferred<Unit>()
        val first = launch { scheduler.execute(configuration) { release.await() } }
        runCurrent()
        first.cancelAndJoin()
        var called = false
        val next = launch { scheduler.execute(configuration) { called = true } }
        advanceTimeBy(999); runCurrent(); assertFalse(called)
        advanceTimeBy(1); runCurrent(); next.join(); assertTrue(called)
    }

    @Test fun differentModelsDoNotBlockEachOtherAndTheSameModelKeepsItsClock() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(configuration) { }
        val waiting = async { scheduler.execute(configuration) { testScheduler.currentTime } }
        val other = scheduler.execute(ModelConfigurationRef("other", 1)) { testScheduler.currentTime }
        assertEquals(0L, other)
        runCurrent(); assertFalse(waiting.isCompleted)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1000L, waiting.await())
    }

    @Test fun backendFailureIsNotRetriedAndDoesNotStrandFollowingWork() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        var calls = 0
        try {
            scheduler.execute(configuration) { calls++; throw AgentModelFailure(ModelFailureKind.RATE_LIMIT) }
            fail("Expected provider failure")
        } catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.RATE_LIMIT, failure.kind) }
        assertEquals(1, calls)
        val next = async { scheduler.execute(configuration) { ++calls } }
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, next.await())
    }

    @Test fun aNewConfigurationRevisionDoesNotGrantAnotherRateBurst() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(configuration) { }
        val changedRevision = async { scheduler.execute(configuration.copy(revision = 2)) { testScheduler.currentTime } }
        runCurrent(); assertFalse(changedRevision.isCompleted)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1000L, changedRevision.await())
    }

    @Test fun aFullyReceivedReplyRemainsAvailableWhenStopRacesWithSlotRelease() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        var received: String? = null
        val request = launch {
            received = scheduler.execute(configuration) {
                currentCoroutineContext().cancel()
                "complete-receipt"
            }
        }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals("complete-receipt", received)
        val next = async { scheduler.execute(configuration) { "next" } }
        advanceTimeBy(1000); runCurrent()
        assertEquals("next", next.await())
    }

    @Test fun stoppedRunIsCheckedAgainAfterWaitingBeforeBackendDispatch() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(configuration) { }
        val runtime = RuntimeCoordinator()
        val project = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
        val identity = RunIdentity(project.projectId, null, RunId("run"), CommandId("start"))
        runtime.selectChat(ChatIdentity(project.projectId, ChatId("workspace")))
        val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = this@ModelRequestSchedulerTest.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++
                error("Stopped request reached backend")
            }
        }
        val result = async {
            try {
                NativeModelGateway(runtime, backend, scheduler = scheduler).complete(lease,
                    TranslationModelRequest(project, identity, RequestAttemptId("attempt"), configuration,
                        listOf(TranslationInput(ItemId(1), "source",
                            TextProjectFingerprint.item(ItemId(1), 0, "source", "", TextItemStatus.NONE)))))
                false
            } catch (_: RuntimeUnavailable) { true }
        }
        runCurrent()
        assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        runtime.requestStop(lease)
        advanceTimeBy(1000); runCurrent()
        assertTrue(result.await())
        assertEquals(0, calls)
        runtime.finishRun(lease)
    }

    @Test fun optionalLimitsProviderOverridesTheCallbackWithoutChangingDefaultBehaviour() = runTest {
        val provider = object : ModelRequestLimitsProvider {
            override suspend fun current(): ModelRequestLimits = ModelRequestLimits(1, 60)
        }
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(16) }, { testScheduler.currentTime },
            limitsProvider = provider)
        scheduler.execute(configuration) { }
        val waiting = async { scheduler.execute(configuration) { testScheduler.currentTime } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1000L, waiting.await())
    }

    @Test fun changingProviderLimitsForAnExistingResourceIsRejected() = runTest {
        var current = ModelRequestLimits(1, 60)
        val provider = object : ModelRequestLimitsProvider {
            override suspend fun current(): ModelRequestLimits = current
        }
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(16) }, { testScheduler.currentTime },
            limitsProvider = provider)
        scheduler.execute(configuration) { }
        current = ModelRequestLimits(2, 60)
        val failure = try {
            scheduler.execute(configuration) { }
            null
        } catch (error: IllegalArgumentException) { error }
        assertNotNull(failure)
    }

    @Test fun agentSharesTheRatePoolAndStopPreventsItsQueuedTransportCall() = runTest {
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(configuration) { }
        val runtime = RuntimeCoordinator()
        val project = ProjectId("project"); val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project, chat))
        val identity = RunIdentity(project, chat, RunId("agent-run"), CommandId("agent-start"))
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = this@ModelRequestSchedulerTest.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse = error("Wrong route")
        }
        val transport = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                calls++
                error("Stopped Agent reached transport")
            }
        }
        val gateway = NativeModelGateway(runtime, backend, agentTransport = transport, scheduler = scheduler)
        val result = async {
            try {
                gateway.complete(AgentModelRequest(identity, BranchId("branch"), RequestAttemptId("attempt"), configuration,
                    listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("hello")))), emptyList())) { }
                false
            } catch (_: RuntimeUnavailable) { true }
        }
        runCurrent(); assertFalse(result.isCompleted)
        runtime.requestStop(lease)
        advanceTimeBy(1000); runCurrent()
        assertTrue(result.await()); assertEquals(0, calls)
        runtime.finishRun(lease)
    }
}
