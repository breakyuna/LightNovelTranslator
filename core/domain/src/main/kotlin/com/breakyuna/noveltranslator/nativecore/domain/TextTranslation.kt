package com.breakyuna.noveltranslator.nativecore.domain

/** Immutable, bounded model boundary. Bodies deliberately have no generated toString(). */
class TranslationInput(val id: ItemId, val source: String, val fingerprint: ObjectFingerprint)
class TranslationOutput(val id: ItemId, val text: String)
class TranslationModelRequest(
    val ref: LocalProjectRef, val run: RunIdentity, val attempt: RequestAttemptId,
    val configuration: ModelConfigurationRef, inputs: List<TranslationInput>,
) {
    val inputs: List<TranslationInput> = frozenList(inputs)
    init {
        require(run.projectId == ref.projectId && this.inputs.size in 1..20)
        require(this.inputs.map { it.id }.toSet().size == this.inputs.size)
        require(this.inputs.sumOf { it.source.length.toLong() } <= 65_536)
    }
}
class TranslationModelResponse(outputs: List<TranslationOutput>, val usage: RequestUsage, val stop: ModelStopReason) {
    val outputs: List<TranslationOutput> = frozenList(outputs)
}

/** Backend transport only; consumers call the runtime-aware gateway, never this directly. */
interface TranslationModelBackend {
    val configuration: ModelConfigurationRef
    suspend fun complete(request: TranslationModelRequest): TranslationModelResponse
}
interface TranslationModelGateway {
    suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse
}

class TranslationBatch(
    val ref: LocalProjectRef, val expectedRevision: Long, edits: List<ManualTextEdit>,
) {
    val edits: List<ManualTextEdit> = frozenList(edits)
    init {
        require(expectedRevision >= 0 && this.edits.size in 1..20)
        require(this.edits.map { it.itemId }.toSet().size == this.edits.size)
        require(this.edits.map { it.commandId }.toSet().size == this.edits.size)
        require(this.edits.all { it.ref == ref && it.status == TextItemStatus.PROCESSED })
        require(this.edits.withIndex().all { (index, edit) -> edit.expectedRevision == Math.addExact(expectedRevision, index.toLong()) })
    }
}
/** Trusted translation service only. Agent execution must use durable approval instead. */
interface TranslationCommitPort {
    suspend fun commitTranslation(lease: RunLease, batch: TranslationBatch): Long
}
data class TranslationProgress(val committedItems: Int, val requests: Int, val revision: Long, val usage: RequestUsage,
    val usageTotals: RequestUsageTotals? = null, val run: RunIdentity? = null)
