package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeModelGatewayTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val identity = RunIdentity(ref.projectId, null, RunId("run"), CommandId("start"))
    private val config = ModelConfigurationRef("test", 1)
    private fun runtime() = RuntimeCoordinator().also { it.selectChat(ChatIdentity(ref.projectId, ChatId("workspace"))) }
    private fun request() = TranslationModelRequest(ref, identity, RequestAttemptId("attempt"), config,
        listOf(TranslationInput(ItemId(1), "source", TextProjectFingerprint.item(ItemId(1), 0, "source", "", TextItemStatus.NONE))))
    private fun backend(block: () -> TranslationModelResponse) = object : TranslationModelBackend {
        override val configuration = config
        override suspend fun complete(request: TranslationModelRequest) = block()
    }
    private fun response(ids: List<Long>, stop: ModelStopReason = ModelStopReason.COMPLETE) = TranslationModelResponse(
        ids.map { TranslationOutput(ItemId(it), "result") }, unknownRequestUsage(), stop)

    @Test fun rejectsDuplicateAndUnknownItemsBeforeAnyWrite() = runTest {
        for (ids in listOf(listOf(1L, 1L), listOf(2L), emptyList())) {
            val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
            try {
                try { NativeModelGateway(runtime, backend { response(ids) }).complete(lease, request()); fail("Accepted invalid identities") }
                catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, failure.kind) }
                assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations)
            } finally { runtime.finishRun(lease) }
        }
    }

    @Test fun truncatedResponseIsNeverAcceptedAsComplete() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        try {
            try { NativeModelGateway(runtime, backend { response(listOf(1), ModelStopReason.TRUNCATED) }).complete(lease, request()); fail("Accepted truncation") }
            catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.TRUNCATED, failure.kind) }
        } finally { runtime.finishRun(lease) }
    }

    @Test fun diagnosticsReportClassifiedFailureWithoutRequestBodies() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val events = mutableListOf<ModelDiagnosticEvent>()
        try {
            val gateway = NativeModelGateway(runtime, backend { throw AgentModelFailure(ModelFailureKind.TIMEOUT) },
                diagnostic = { events += it })
            try { gateway.complete(lease, request()); fail("Expected failure") }
            catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.TIMEOUT, failure.kind) }
            assertEquals(listOf(ModelDiagnosticPhase.STARTED, ModelDiagnosticPhase.FAILED), events.map { it.phase })
            assertEquals(ModelFailureKind.TIMEOUT, events.last().failure)
            assertFalse(events.toString().contains("source"))
        } finally { runtime.finishRun(lease) }
    }

    @Test fun diagnosticCallbackFailureDoesNotChangeModelResult() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        try {
            val gateway = NativeModelGateway(runtime, backend { response(listOf(1)) },
                diagnostic = { throw IllegalStateException("diagnostic unavailable") })
            assertEquals(ModelStopReason.COMPLETE, gateway.complete(lease, request()).stop)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun debugEligibilityIsCapturedWhenTheGatewayCallStarts() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val events = mutableListOf<ModelDiagnosticEvent>()
        var enabled = false
        try {
            val gateway = NativeModelGateway(runtime, backend { enabled = true; response(listOf(1)) },
                diagnosticEnabled = { enabled }, diagnostic = { events += it })
            gateway.complete(lease, request())
            assertEquals(listOf(ModelDiagnosticPhase.STARTED, ModelDiagnosticPhase.COMPLETED), events.map { it.phase })
            assertTrue(events.none { it.debugAtStart })
        } finally { runtime.finishRun(lease) }
    }

    @Test fun stopBlocksNextRequestWithoutCallingBackend() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        var calls = 0
        runtime.requestStop(lease)
        try {
            try { NativeModelGateway(runtime, backend { calls++; response(listOf(1)) }).complete(lease, request()); fail("Called stopped run") }
            catch (_: RuntimeUnavailable) { assertEquals(0, calls) }
        } finally { runtime.finishRun(lease) }
    }

    @Test fun lateResponseAfterStopIsDiscardedAndReservationReleased() = runTest {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        try {
            try { NativeModelGateway(runtime, backend { runtime.requestStop(lease); response(listOf(1)) }).complete(lease, request()); fail("Accepted late result") }
            catch (_: RuntimeUnavailable) { assertEquals(0, runtime.snapshot().activeRun!!.outstandingOperations) }
        } finally { runtime.finishRun(lease) }
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun localModelReportsUnknownTokensAndObviousSimulationText() = runTest {
        val model = LocalSimulationModel()
        val input = request()
        val output = model.complete(TranslationModelRequest(ref, identity, input.attempt, model.configuration, input.inputs))
        assertEquals("[SIMULATION] source", output.outputs.single().text)
        assertEquals(UsageSource.UNKNOWN, output.usage.output.source)
        assertNull(output.usage.output.tokens)
    }
}
