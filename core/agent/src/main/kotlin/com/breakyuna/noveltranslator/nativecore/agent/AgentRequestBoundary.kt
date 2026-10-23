package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.AgentEventSink
import com.breakyuna.noveltranslator.nativecore.domain.AgentHistory
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelEvent
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelPort
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelRequest
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelResponse
import com.breakyuna.noveltranslator.nativecore.domain.AgentPersistencePort
import com.breakyuna.noveltranslator.nativecore.domain.AgentRequestKey
import com.breakyuna.noveltranslator.nativecore.domain.AssistantPart
import com.breakyuna.noveltranslator.nativecore.domain.DispatchAwareAgentModelPort
import com.breakyuna.noveltranslator.nativecore.domain.InvalidAgentHistory
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelStopReason
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.OperationLease
import com.breakyuna.noveltranslator.nativecore.domain.RequestReservation
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.domain.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AgentAttemptConflict : IllegalStateException("Agent request attempt conflicts with stored content")
class AgentAttemptUncertain : IllegalStateException("Agent request needs explicit reconciliation")

/** Known response retained for reconciliation; exception messages never include material. */
class AgentResponseNotStored(val key: AgentRequestKey, val response: AgentModelResponse) :
    IllegalStateException("Agent response received but receipt persistence failed")

/**
 * One-request boundary for the future Koog PromptExecutor, not a replacement tool
 * loop/Agent framework. The only model dependency is the shared model port.
 * A production store is mandatory; request history is never silently downgraded.
 */
class AgentRequestBoundary(
    private val runtime: RuntimePort,
    private val model: AgentModelPort,
    private val persistence: AgentPersistencePort,
    private val events: AgentEventSink,
) {
    suspend fun complete(run: RunLease, request: AgentModelRequest): AgentModelResponse {
        require(request.run == run.identity) { "Agent request identity mismatch" }
        currentCoroutineContext().ensureActive()
        val owner = runtime.snapshot().activeRun
        require(owner != null && owner.identity == run.identity && owner.owner == RuntimeOwner.AGENT) {
            "Agent request requires an active Agent run"
        }
        // This lease also holds occupancy during asynchronous receipt preparation.
        val preparation = runtime.beginOperation(run, OperationKind.MODEL_REQUEST)
        val key = request.key()
        var ownsPreview = false
        var dispatch: OperationLease? = null
        try {
            when (val reservation = persistence.reserveRequest(request)) {
                RequestReservation.Conflict -> throw AgentAttemptConflict()
                RequestReservation.Uncertain -> throw AgentAttemptUncertain()
                is RequestReservation.Completed -> {
                    currentCoroutineContext().ensureActive()
                    return validateResponse(request, reservation.response)
                }
                RequestReservation.Prepared -> Unit
            }
            suspend fun beforeDispatch() {
                currentCoroutineContext().ensureActive()
                if (dispatch != null || !persistence.markDispatched(key)) throw AgentAttemptUncertain()
                // A durable dispatch without a send remains conservatively uncertain.
                currentCoroutineContext().ensureActive()
                dispatch = runtime.beginOperation(run, OperationKind.MODEL_REQUEST)
                ownsPreview = true
                try { events.previewStarted(key) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Presentation only. */ }
            }
            val emit: suspend (AgentModelEvent) -> Unit = { event ->
                currentCoroutineContext().ensureActive()
                try {
                    events.preview(key, event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Presentation failure cannot cause another paid request.
                }
            }
            val dispatchAware = model as? DispatchAwareAgentModelPort
            val response = if (dispatchAware != null)
                dispatchAware.completePrepared(request, ::beforeDispatch, emit)
            else {
                beforeDispatch()
                model.complete(request, emit)
            }
            // Preserve a known provider receipt even if Stop arrived with it.
            // Store implementations must bound their IO and commit atomically.
            val stored = withContext(NonCancellable) {
                try {
                    persistence.completeRequest(key, response)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (!stored) throw AgentResponseNotStored(key, response)
            try { events.previewCompleted(key, response.message.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Presentation only. */ }
            currentCoroutineContext().ensureActive()
            return validateResponse(request, response)
        } finally {
            dispatch?.let(runtime::endOperation)
            runtime.endOperation(preparation)
            if (ownsPreview) {
                try {
                    events.previewFinished(key)
                } catch (_: Exception) {
                    // Best effort cleanup; do not replace a stored result or cancellation.
                }
            }
        }
    }

    private fun validateResponse(request: AgentModelRequest, response: AgentModelResponse): AgentModelResponse {
        if (response.stopReason == ModelStopReason.TRUNCATED) throw AgentModelFailure(ModelFailureKind.TRUNCATED)
        if (response.stopReason == ModelStopReason.CONTENT_FILTERED) throw AgentModelFailure(ModelFailureKind.CONTENT_FILTERED)
        val hasCalls = response.message.parts.any { it is AssistantPart.ToolCall }
        if ((response.stopReason == ModelStopReason.TOOL_CALLS) != hasCalls) {
            throw AgentModelFailure(ModelFailureKind.PARSING)
        }
        if (!hasCalls && response.message.parts.none { it is AssistantPart.Text && it.text.isNotBlank() }) {
            throw AgentModelFailure(ModelFailureKind.EMPTY_RESPONSE)
        }
        try {
            AgentHistory.validate(request.history + response.message, allowPendingAtTail = true)
        } catch (_: InvalidAgentHistory) {
            throw AgentModelFailure(ModelFailureKind.PARSING)
        }
        return response
    }
}
