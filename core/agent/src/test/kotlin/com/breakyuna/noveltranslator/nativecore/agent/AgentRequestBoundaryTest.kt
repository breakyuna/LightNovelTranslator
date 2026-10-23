package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.engine.ModelRequestScheduler
import com.breakyuna.noveltranslator.nativecore.engine.NativeModelGateway
import com.breakyuna.noveltranslator.nativecore.engine.LocalSimulationModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentRequestBoundaryTest {
    private val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("command"))

    @Test fun cancelledQueueEntryRemainsPreparedAndDoesNotStartAPreviewOrProviderCall() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val request = request(identity); val store = FakeRequestStore(); val sink = PreviewSink()
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(request.configuration) { }
        var calls = 0
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model { calls++; response() }, scheduler = scheduler)
        val boundary = AgentRequestBoundary(runtime, gateway, store, sink)
        val queued = launch { boundary.complete(run, request) }
        runCurrent()
        assertEquals(RequestReservation.Prepared, store.reserveRequest(request))
        assertEquals(1, runtime.snapshot().activeRun!!.outstandingOperations)
        queued.cancelAndJoin()
        assertEquals(0, calls); assertEquals(0, store.saveCount); assertEquals(0, sink.finishCount)
        assertEquals(RequestReservation.Prepared, store.reserveRequest(request))
        assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        // Only an explicit new call may dispatch the still-prepared attempt.
        advanceTimeBy(1000); runCurrent()
        boundary.complete(run, request)
        assertEquals(1, calls); assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test fun stopWhileQueuedDoesNotMarkARequestAsSent() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val request = request(identity); val store = FakeRequestStore(); val sink = PreviewSink()
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(request.configuration) { }
        var calls = 0
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model { calls++; response() }, scheduler = scheduler)
        val queued = async {
            try { AgentRequestBoundary(runtime, gateway, store, sink).complete(run, request); false }
            catch (_: RuntimeUnavailable) { true }
        }
        runCurrent(); runtime.requestStop(run)
        advanceTimeBy(1000); runCurrent()
        assertTrue(queued.await()); assertEquals(0, calls)
        assertEquals(RequestReservation.Prepared, store.reserveRequest(request))
        assertEquals(0, sink.finishCount)
        runtime.finishRun(run)
    }

    @Test fun stopDuringTheDispatchCallbackKeepsUncertaintyAndBlocksTransport() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val request = request(identity); val store = FakeRequestStore()
        var calls = 0
        val sink = object : AgentEventSink {
            override fun previewStarted(key: AgentRequestKey) { runtime.requestStop(run) }
            override fun preview(key: AgentRequestKey, event: AgentModelEvent) = Unit
            override fun previewFinished(key: AgentRequestKey) = Unit
        }
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model { calls++; response() })
        try { AgentRequestBoundary(runtime, gateway, store, sink).complete(run, request); fail("Expected stop") }
        catch (_: RuntimeUnavailable) { }
        assertEquals(0, calls)
        assertEquals(RequestReservation.Uncertain, store.reserveRequest(request))
        assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        runtime.finishRun(run)
    }

    @Test fun stopDuringPreparationBeforeTheScheduledGatewayLeavesTheAttemptUnsent() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val request = request(identity); val store = FakeRequestStore()
        store.afterReserve = { runtime.requestStop(run) }
        var calls = 0
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model { calls++; response() })
        try { AgentRequestBoundary(runtime, gateway, store, PreviewSink()).complete(run, request); fail("Expected stop") }
        catch (_: RuntimeUnavailable) { }
        assertEquals(0, calls); assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
        assertEquals(RequestReservation.Prepared, store.reserveRequest(request))
        runtime.finishRun(run)
    }

    @Test fun aReplyReceivedWithCancellationStillCrossesTheSchedulerAndIsStoredExactlyOnce() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore(); val request = request(identity)
        var calls = 0
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model {
            calls++; currentCoroutineContext().cancel(); response()
        })
        val boundary = AgentRequestBoundary(runtime, gateway, store, PreviewSink())
        val job = launch {
            try { boundary.complete(run, request); fail("Expected cancellation") }
            catch (_: CancellationException) { }
        }
        job.join()
        assertEquals(1, store.saveCount); assertEquals(1, calls)
        boundary.complete(run, request)
        assertEquals(1, store.saveCount); assertEquals(1, calls)
        runtime.finishRun(run)
    }

    @Test fun concurrentPreparedCopiesDoNotSendTheSameAttemptTwiceAfterWaiting() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val request = request(identity); val store = FakeRequestStore(); val sink = PreviewSink()
        val scheduler = ModelRequestScheduler({ ModelRequestLimits(1, 60) }, { testScheduler.currentTime })
        scheduler.execute(request.configuration) { }
        var calls = 0
        val gateway = NativeModelGateway(runtime, LocalSimulationModel(), model { calls++; response() }, scheduler = scheduler)
        val boundary = AgentRequestBoundary(runtime, gateway, store, sink)
        val first = async { boundary.complete(run, request) }
        val duplicate = async {
            try { boundary.complete(run, request); false } catch (_: AgentAttemptUncertain) { true }
        }
        runCurrent(); assertEquals(0, calls)
        advanceTimeBy(1000); runCurrent(); first.await()
        advanceTimeBy(1000); runCurrent()
        assertTrue(duplicate.await()); assertEquals(1, calls); assertEquals(1, store.saveCount)
        assertEquals(1, sink.finishCount)
        runtime.finishRun(run)
    }

    @Test fun previewLifecycleKeepsDeltasSeparateAndAnnouncesOnlyDurablyRecordedCompletion() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore(); val order = mutableListOf<String>()
        val sink = object : AgentEventSink {
            override fun previewStarted(key: AgentRequestKey) { order.add("started") }
            override fun preview(key: AgentRequestKey, event: AgentModelEvent) {
                assertEquals(0, store.saveCount); order.add("delta")
            }
            override fun previewCompleted(key: AgentRequestKey, message: MessageId) {
                assertEquals(1, store.saveCount); order.add("recorded")
            }
            override fun previewFinished(key: AgentRequestKey) { order.add("finished") }
        }
        val gateway = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                emit(AgentModelEvent.TextDelta("partial")); emit(AgentModelEvent.ThinkingDelta("thinking")); return response()
            }
        }
        try {
            AgentRequestBoundary(runtime, gateway, store, sink).complete(run, request(identity))
            assertEquals(listOf("started", "delta", "delta", "recorded", "finished"), order)
            assertEquals(1, store.saveCount)
        } finally { runtime.finishRun(run) }
    }

    @Test fun streamFailureRetainsUncertainDispatchAndNeverPersistsPartialAssistant() = runTest {
        val runtime = runtime(); val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore(); val sink = PreviewSink(); var calls = 0
        val gateway = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                calls++; emit(AgentModelEvent.TextDelta("not a complete response")); throw AgentModelFailure(ModelFailureKind.NETWORK)
            }
        }
        val boundary = AgentRequestBoundary(runtime, gateway, store, sink)
        try {
            try { boundary.complete(run, request(identity)); fail() } catch (_: AgentModelFailure) { }
            assertEquals(0, store.saveCount); assertEquals(1, sink.finishCount)
            try { boundary.complete(run, request(identity)); fail() } catch (_: AgentAttemptUncertain) { }
            assertEquals(1, calls)
        } finally { runtime.finishRun(run) }
    }

    @Test
    fun completedAttemptReplaysReceiptWithoutAnotherModelCallOrUsageWrite() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val sink = PreviewSink()
        var calls = 0
        val expected = response()
        val boundary = AgentRequestBoundary(runtime, model {
            calls += 1
            expected
        }, store, sink)
        assertSame(expected, boundary.complete(run, request(identity)))
        assertSame(expected, boundary.complete(run, request(identity)))
        assertEquals(1, calls)
        assertEquals(1, store.saveCount)
        assertEquals(1, sink.finishCount)
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(run)
    }

    @Test
    fun changedConfigurationUnderSameAttemptConflictsBeforeAnotherRequest() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model { calls += 1; response() }, store, PreviewSink())
        boundary.complete(run, request(identity))
        try {
            boundary.complete(run, request(identity, revision = 2))
            fail("Expected conflict")
        } catch (_: AgentAttemptConflict) { }
        assertEquals(1, calls)
        runtime.finishRun(run)
    }

    @Test
    fun uncertainDispatchIsNotAutomaticallyRetried() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val original = request(identity)
        store.reserveRequest(original)
        store.markDispatched(original.key())
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model { calls += 1; response() }, store, PreviewSink())
        try {
            boundary.complete(run, original)
            fail("Expected reconciliation")
        } catch (_: AgentAttemptUncertain) { }
        assertEquals(0, calls)
        runtime.finishRun(run)
    }

    @Test
    fun stopDuringReceiptPreparationPreventsModelDispatch() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore().also { it.afterReserve = { runtime.requestStop(run) } }
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model { calls += 1; response() }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected Stop")
        } catch (failure: RuntimeUnavailable) {
            assertEquals(RuntimeUnavailable.Reason.STOPPING, failure.reason)
        }
        assertEquals(0, calls)
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(run)
    }

    @Test
    fun duplicateConcurrentAttemptCannotClearTheActivePreview() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val sink = PreviewSink()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model {
            calls += 1
            entered.complete(Unit)
            finish.await()
            response()
        }, store, sink)
        val active = async { boundary.complete(run, request(identity)) }
        entered.await()
        try {
            boundary.complete(run, request(identity))
            fail("Expected active attempt to require reconciliation")
        } catch (_: AgentAttemptUncertain) { }
        assertEquals(0, sink.finishCount)
        finish.complete(Unit)
        active.await()
        assertEquals(1, calls)
        assertEquals(1, store.saveCount)
        assertEquals(1, sink.finishCount)
        runtime.finishRun(run)
    }

    @Test
    fun cancellationArrivingWithKnownResponseStillStoresReceiptAndUsage() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val boundary = AgentRequestBoundary(runtime, model {
            currentCoroutineContext().cancel()
            response()
        }, store, PreviewSink())
        val job = launch {
            try { boundary.complete(run, request(identity)); fail("Expected cancellation") }
            catch (_: CancellationException) { }
        }
        job.join()
        assertEquals(1, store.saveCount)
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(run)
    }

    @Test
    fun failedReceiptWriteRetainsKnownResponseAndNeverRetriesPaidRequest() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore().also { it.failCompletion = true }
        val expected = response()
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model { calls += 1; expected }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected receipt failure")
        } catch (failure: AgentResponseNotStored) {
            assertSame(expected, failure.response)
        }
        try {
            boundary.complete(run, request(identity))
            fail("Expected reconciliation")
        } catch (_: AgentAttemptUncertain) { }
        assertEquals(1, calls)
        runtime.finishRun(run)
    }

    @Test
    fun presentationFailureDoesNotDiscardACompletedRequest() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val sink = PreviewSink().also { it.failPreview = true }
        val gateway = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                emit(AgentModelEvent.TextDelta("preview"))
                return response()
            }
        }
        AgentRequestBoundary(runtime, gateway, store, sink).complete(run, request(identity))
        assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun invalidNativeToolResponseIsRecordedButCannotBeUsedAsAValidTurn() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val call = AgentToolCall(ToolCallId("call"), "lookup", json())
        val boundary = AgentRequestBoundary(runtime,
            model { response(listOf(AssistantPart.ToolCall(call)), ModelStopReason.COMPLETE) }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected protocol failure")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.PARSING, failure.kind)
        }
        assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun truncatedToolResponseCannotEscapeAsExecutableSuccess() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val call = AgentToolCall(ToolCallId("call"), "lookup", json())
        val boundary = AgentRequestBoundary(runtime,
            model { response(listOf(AssistantPart.ToolCall(call)), ModelStopReason.TRUNCATED) }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected truncation failure")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.TRUNCATED, failure.kind)
        }
        assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun emptyFilteredProviderReceiptStillRecordsItsUsage() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val boundary = AgentRequestBoundary(runtime,
            model { response(emptyList(), ModelStopReason.CONTENT_FILTERED) }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected content filtering failure")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT_FILTERED, failure.kind)
        }
        assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun thinkingOnlyResponseDoesNotPretendToBeAFinalAnswer() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        val boundary = AgentRequestBoundary(runtime,
            model { response(listOf(AssistantPart.Thinking("synthetic thought", null))) }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity))
            fail("Expected empty final answer")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.EMPTY_RESPONSE, failure.kind)
        }
        assertEquals(1, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun mismatchedOrStaleIdentityNeverReachesStoreOrModel() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val store = FakeRequestStore()
        var calls = 0
        val boundary = AgentRequestBoundary(runtime, model { calls += 1; response() }, store, PreviewSink())
        try {
            boundary.complete(run, request(identity.copy(projectId = ProjectId("other"))))
            fail("Expected identity rejection")
        } catch (_: IllegalArgumentException) { }
        val forged = object : RunLease { override val identity = run.identity }
        try {
            boundary.complete(forged, request(identity))
            fail("Expected stale lease")
        } catch (failure: RuntimeUnavailable) {
            assertEquals(RuntimeUnavailable.Reason.STALE_EXECUTION, failure.reason)
        }
        assertEquals(0, calls)
        assertEquals(0, store.saveCount)
        runtime.finishRun(run)
    }

    @Test
    fun typedHistoryAndProviderContinuationReachGatewayWithoutFlattening() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val call = AgentToolCall(ToolCallId("call"), "lookup", json("""{"id":9007199254740993}"""))
        val previous = AgentMessage.Assistant(MessageId("previous"), listOf(AssistantPart.ToolCall(call)),
            ProviderContinuation("synthetic", json("""{"signature":"opaque"}""")))
        val history = listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("synthetic"))), previous,
            AgentMessage.ToolResult(MessageId("result"), call.id, call.name, json(), false))
        var captured: AgentModelRequest? = null
        val boundary = AgentRequestBoundary(runtime, model { value -> captured = value; response() }, FakeRequestStore(), PreviewSink())
        boundary.complete(run, request(identity, history = history))
        assertTrue(captured!!.history[1] is AgentMessage.Assistant)
        assertSame(previous.continuation, (captured!!.history[1] as AgentMessage.Assistant).continuation)
        assertTrue(captured!!.history[2] is AgentMessage.ToolResult)
        runtime.finishRun(run)
    }

    private fun runtime() = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
    private fun model(body: suspend (AgentModelRequest) -> AgentModelResponse) = object : AgentModelPort {
        override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit) = body(request)
    }
}
