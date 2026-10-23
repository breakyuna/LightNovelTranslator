package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope

/** One scheduling/validation boundary for the first translation slice; no implicit retries. */
class NativeModelGateway(private val runtime: RuntimePort, private val backend: TranslationModelBackend,
    private val agentTransport: AgentModelPort? = null,
    private val catalog: NativeModelCatalogPort? = null,
    private val scheduler: ModelRequestScheduler = ModelRequestScheduler()) : TranslationModelGateway, DispatchAwareAgentModelPort, NativeModelCatalogPort {
    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?): List<String> {
        currentCoroutineContext().ensureActive()
        return (catalog ?: throw AgentModelFailure(ModelFailureKind.CONTENT)).models(endpoint, key, savedCredential)
    }
    override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse =
        completePrepared(request, {}, emit)

    override suspend fun completePrepared(request: AgentModelRequest, beforeDispatch: suspend () -> Unit,
        emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
        currentCoroutineContext().ensureActive()
        val owner = runtime.snapshot().activeRun
        require(owner != null && owner.identity == request.run && owner.owner == RuntimeOwner.AGENT)
        if (owner.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
        val transport = agentTransport ?: throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
        return scheduler.execute(request.configuration) {
            val current = runtime.snapshot().activeRun
            if (current == null || current.identity != request.run || current.owner != RuntimeOwner.AGENT ||
                current.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
            beforeDispatch()
            currentCoroutineContext().ensureActive()
            val ready = runtime.snapshot().activeRun
            if (ready == null || ready.identity != request.run || ready.owner != RuntimeOwner.AGENT ||
                ready.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
            transport.complete(request, emit)
        }
    }

    override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
        require(lease.identity == request.run && request.configuration == backend.configuration)
        currentCoroutineContext().ensureActive()
        return scheduler.execute(request.configuration) {
            val operation = runtime.beginOperation(lease, OperationKind.MODEL_REQUEST)
            try {
                currentCoroutineContext().ensureActive()
                val result = try { coroutineScope { backend.complete(request) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (classified: AgentModelFailure) { throw classified }
                catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.UNKNOWN) }
                currentCoroutineContext().ensureActive()
                // A stopped run may not accept a late, otherwise valid model response.
                if (runtime.snapshot().activeRun?.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
                if (result.stop != ModelStopReason.COMPLETE) throw AgentModelFailure(when (result.stop) {
                    ModelStopReason.TRUNCATED -> ModelFailureKind.TRUNCATED
                    ModelStopReason.CONTENT_FILTERED -> ModelFailureKind.CONTENT_FILTERED
                    else -> ModelFailureKind.PARSING
                })
                if (result.outputs.map { it.id }.toSet() != request.inputs.map { it.id }.toSet() ||
                    result.outputs.size != request.inputs.size) throw AgentModelFailure(ModelFailureKind.PARSING)
                for (output in result.outputs) {
                    if (output.text.length > 65_536) throw AgentModelFailure(ModelFailureKind.CONTENT)
                    try { TextProjectFingerprint.utf8(output.text) }
                    catch (_: TextProjectException) { throw AgentModelFailure(ModelFailureKind.CONTENT) }
                }
                result
            } finally { runtime.endOperation(operation) }
        }
    }
}

/** Local S1 test backend. Never connects to a network or reports actual model token usage. */
class LocalSimulationModel : TranslationModelBackend {
    override val configuration = ModelConfigurationRef("local-simulation-v1", 1)
    override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
        currentCoroutineContext().ensureActive()
        require(request.configuration == configuration)
        return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "[SIMULATION] " + it.source) },
            unknownRequestUsage(), ModelStopReason.COMPLETE)
    }
}

fun unknownRequestUsage(): RequestUsage {
    val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
    return RequestUsage(unknown, unknown, unknown, unknown, unknown)
}
