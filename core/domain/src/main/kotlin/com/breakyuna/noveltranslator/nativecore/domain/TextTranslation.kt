package com.breakyuna.noveltranslator.nativecore.domain

/** Immutable, bounded model boundary. Bodies deliberately have no generated toString(). */
class TranslationInput(val id: ItemId, val source: String, val fingerprint: ObjectFingerprint,
    terms: List<TranslationTerm> = emptyList()) {
    val terms: List<TranslationTerm> = frozenList(terms)
}
/** Read-only preceding text; it never participates in the output identity set. */
class TranslationContextLine(val id: ItemId, val source: String, val fingerprint: ObjectFingerprint) {
    init {
        require(source.isNotBlank() && source.length <= 2_048 && '\u0000' !in source)
        TextProjectFingerprint.utf8(source)
    }
}

/** Same-run reuse only: older translations may have been produced with different rules. */
interface TranslationReusePort {
    suspend fun committedDuplicate(ref: LocalProjectRef, revision: Long, afterRevision: Long,
        target: ItemId): TextItemDetails?
}

interface TranslationContextPort {
    /** Earlier rows of the anchor asset in source order, from exactly the requested revision. */
    suspend fun precedingTranslationItems(ref: LocalProjectRef, revision: Long, anchor: ItemId,
        limit: Int = 64): TextItemPage
}

class TranslationOutput(val id: ItemId, val text: String)
class TranslationModelRequest(
    val ref: LocalProjectRef, val run: RunIdentity, val attempt: RequestAttemptId,
    val configuration: ModelConfigurationRef, inputs: List<TranslationInput>,
    val options: RemoteTranslationOptions? = null,
    val policyFingerprint: ObjectFingerprint? = null, val projectInstructions: String = "",
    precedings: List<TranslationContextLine> = emptyList(),
) {
    val inputs: List<TranslationInput> = frozenList(inputs)
    val precedings: List<TranslationContextLine> = frozenList(precedings)
    init {
        require(run.projectId == ref.projectId && this.inputs.size in 1..20)
        require(this.inputs.map { it.id }.toSet().size == this.inputs.size)
        require(this.inputs.sumOf { it.source.length.toLong() } <= 65_536)
        require(this.precedings.size <= 16 && this.precedings.sumOf { it.source.length } <= 2_048)
        require(this.precedings.map { it.id }.toSet().size == this.precedings.size)
        require(this.precedings.none { previous -> this.inputs.any { it.id == previous.id } })
        require(projectInstructions.length <= 16_384 && '\u0000' !in projectInstructions)
        TextProjectFingerprint.utf8(projectInstructions)
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

/** Resolved by exact configuration revision inside the shared gateway, never by UI code. */
interface ConfiguredTranslationBackend {
    suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse
}

/** Persist dispatch only after queue eligibility; waiting for capacity is not a sent request. */
interface DispatchAwareTranslationGateway : TranslationModelGateway {
    suspend fun completePrepared(lease: RunLease, request: TranslationModelRequest,
        beforeDispatch: suspend () -> Unit): TranslationModelResponse
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
/** Terminal item failures preserve the text and cannot overwrite a successful translation. */
class TranslationFailureBatch(val ref: LocalProjectRef, val expectedRevision: Long, edits: List<ManualTextEdit>) {
    val edits: List<ManualTextEdit> = frozenList(edits)
    init {
        require(expectedRevision >= 0 && this.edits.size in 1..20)
        require(this.edits.map { it.itemId }.toSet().size == this.edits.size)
        require(this.edits.map { it.commandId }.toSet().size == this.edits.size)
        require(this.edits.withIndex().all { (offset, edit) -> edit.ref == ref &&
            edit.status == TextItemStatus.ERROR && edit.expectedRevision == Math.addExact(expectedRevision, offset.toLong()) })
    }
}
interface TranslationFailurePort {
    suspend fun markTranslationFailures(lease: RunLease, batch: TranslationFailureBatch): Long
}

/** Trusted translation service only. Agent execution must use durable approval instead. */
interface TranslationCommitPort {
    suspend fun commitTranslation(lease: RunLease, batch: TranslationBatch): Long
}
data class TranslationProgress(val committedItems: Int, val requests: Int, val revision: Long, val usage: RequestUsage,
    val usageTotals: RequestUsageTotals? = null, val run: RunIdentity? = null)
