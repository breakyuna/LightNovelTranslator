package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*

/** Synthetic store only. Production requires a durable CAS/transaction adapter. */
internal class FakeRequestStore : AgentPersistencePort {
    private class Row(val digest: String) {
        var dispatched = false
        var response: AgentModelResponse? = null
    }
    private val rows = mutableMapOf<AgentRequestKey, Row>()
    private val lock = Any()
    var afterReserve: suspend () -> Unit = {}
    var failCompletion = false
    var saveCount = 0
        private set

    override suspend fun reserveRequest(request: AgentModelRequest): RequestReservation {
        val result = synchronized(lock) {
            val digest = AgentRequestFingerprint.sha256(request)
            val row = rows.getOrPut(request.key()) { Row(digest) }
            when {
                row.digest != digest -> RequestReservation.Conflict
                row.response != null -> RequestReservation.Completed(row.response!!)
                row.dispatched -> RequestReservation.Uncertain
                else -> RequestReservation.Prepared
            }
        }
        afterReserve()
        return result
    }

    override suspend fun markDispatched(key: AgentRequestKey): Boolean = synchronized(lock) {
        val row = rows[key] ?: return@synchronized false
        if (row.dispatched || row.response != null) return@synchronized false
        row.dispatched = true
        true
    }

    override suspend fun completeRequest(key: AgentRequestKey, response: AgentModelResponse) {
        if (failCompletion) throw IllegalStateException("synthetic persistence failure")
        synchronized(lock) {
            val row = checkNotNull(rows[key])
            check(row.dispatched)
            if (row.response == null) {
                row.response = response
                saveCount += 1
            }
        }
    }
}

internal class PreviewSink : AgentEventSink {
    var finishCount = 0
    val events = mutableListOf<AgentModelEvent>()
    var failPreview = false
    override fun preview(key: AgentRequestKey, event: AgentModelEvent) {
        if (failPreview) throw IllegalStateException("synthetic presentation failure")
        events.add(event)
    }
    override fun previewFinished(key: AgentRequestKey) { finishCount += 1 }
}

internal fun request(identity: RunIdentity, revision: Long = 1, history: List<AgentMessage>? = null) = AgentModelRequest(
    identity, BranchId("main"), RequestAttemptId("attempt"), ModelConfigurationRef("synthetic-model", revision),
    history ?: listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("synthetic input")))), emptyList(),
)

internal fun response(parts: List<AssistantPart> = listOf(AssistantPart.Text("synthetic result")),
                      stopReason: ModelStopReason = ModelStopReason.COMPLETE) = AgentModelResponse(
    AgentMessage.Assistant(MessageId("assistant-result"), parts),
    RequestUsage(TokenMeasurement(10, UsageSource.ACTUAL), TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(null, UsageSource.UNKNOWN), TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(5, UsageSource.ACTUAL)), stopReason,
)

internal fun json(text: String = "{}") = JsonObjectValue.parse(text, JsonInputLimits(4096, 16))
