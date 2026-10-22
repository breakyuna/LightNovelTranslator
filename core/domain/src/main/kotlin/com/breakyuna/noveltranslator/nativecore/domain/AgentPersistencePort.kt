package com.breakyuna.noveltranslator.nativecore.domain

/** Durable key spans project, Chat, branch, Run and attempt; no default project. */
data class AgentRequestKey(
    val run: RunIdentity,
    val branchId: BranchId,
    val attemptId: RequestAttemptId,
)

sealed interface RequestReservation {
    data object Prepared : RequestReservation
    data class Completed(val response: AgentModelResponse) : RequestReservation
    /** The previous request may have reached the provider. Requires explicit reconciliation. */
    data object Uncertain : RequestReservation
    /** Attempt ID exists with a different configuration/history/tool content. */
    data object Conflict : RequestReservation
}

/**
 * Implementations atomically compare a canonical content fingerprint, including
 * model revision, typed history, tool schema and continuation payloads. Preparing
 * alone does not imply a billable dispatch. markDispatched is a durable CAS from
 * prepared to in-flight; only its winner may send. No in-memory production fallback.
 */
interface AgentPersistencePort {
    suspend fun reserveRequest(request: AgentModelRequest): RequestReservation
    suspend fun markDispatched(key: AgentRequestKey): Boolean
    /** Response and usage are recorded once in the same application-store transaction. */
    suspend fun completeRequest(key: AgentRequestKey, response: AgentModelResponse)
}

/** Non-blocking presentation sink; every update must retain its full request identity. */
interface AgentEventSink {
    fun preview(key: AgentRequestKey, event: AgentModelEvent)
    fun previewFinished(key: AgentRequestKey)
}

fun AgentModelRequest.key(): AgentRequestKey = AgentRequestKey(run, branchId, attemptId)
