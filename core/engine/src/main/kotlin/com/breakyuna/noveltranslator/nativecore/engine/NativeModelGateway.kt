package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope

/** One scheduling/validation boundary for the first translation slice; no implicit retries. */
class NativeModelGateway(private val runtime: RuntimePort, private val backend: TranslationModelBackend,
    private val agentTransport: AgentModelPort? = null,
    private val catalog: NativeModelCatalogPort? = null,
    private val scheduler: ModelRequestScheduler = ModelRequestScheduler(),
    private val remoteTranslation: ConfiguredTranslationBackend? = null) : DispatchAwareTranslationGateway, DispatchAwareAgentModelPort, NativeModelCatalogPort {
    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?): List<String> =
        models(endpoint, key, savedCredential, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)

    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): List<String> {
        currentCoroutineContext().ensureActive()
        return (catalog ?: throw AgentModelFailure(ModelFailureKind.CONTENT))
            .models(endpoint, key, savedCredential, protocol)
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

    override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse =
        completePrepared(lease, request) {}

    override suspend fun completePrepared(lease: RunLease, request: TranslationModelRequest,
        beforeDispatch: suspend () -> Unit): TranslationModelResponse {
        require(lease.identity == request.run)
        val local = request.configuration == backend.configuration
        require(if (local) request.options == null else request.options != null)
        val remote = if (local) null else remoteTranslation ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
        currentCoroutineContext().ensureActive()
        return scheduler.execute(request.configuration) {
            val owner = runtime.snapshot().activeRun
            if (owner == null || owner.identity != request.run || owner.owner != RuntimeOwner.TRANSLATION ||
                owner.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
            val operation = runtime.beginOperation(lease, OperationKind.MODEL_REQUEST)
            try {
                currentCoroutineContext().ensureActive()
                beforeDispatch()
                currentCoroutineContext().ensureActive()
                val active = runtime.snapshot().activeRun
                if (active == null || active.identity != request.run || active.owner != RuntimeOwner.TRANSLATION ||
                    active.phase != RuntimePhase.RUNNING) throw RuntimeUnavailable(RuntimeUnavailable.Reason.STOPPING)
                val result = try { coroutineScope { if (local) backend.complete(request) else requireNotNull(remote).complete(lease, request) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (classified: AgentModelFailure) { throw classified }
                catch (notStored: TranslationResponseNotStored) { throw notStored }
                // A local network-policy failure is not a provider/model failure and must not cool
                // a key or be retried. Preserve the typed cause for the settings UI.
                catch (local: InvalidModelNetworkSettings) { throw local }
                catch (local: ModelNetworkSettingsUnavailable) { throw local }
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
