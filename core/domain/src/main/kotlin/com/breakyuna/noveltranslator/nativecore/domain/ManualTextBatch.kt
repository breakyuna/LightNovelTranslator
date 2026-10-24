package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

enum class TextBatchChange(val status: TextItemStatus, val clearsTranslation: Boolean = false) {
    MARK_UNPROCESSED(TextItemStatus.NONE), MARK_PROCESSED(TextItemStatus.PROCESSED),
    EXCLUDE(TextItemStatus.EXCLUDED), CLEAR_AND_RESET(TextItemStatus.NONE, true),
}

data class TextBatchTarget(val id: ItemId, val row: Int, val fingerprint: ObjectFingerprint) {
    init { require(row >= 0) }
}

/** Explicit manual confirmation, bounded to a fixed selection; never an Agent approval. */
class ManualTextBatch(
    val commandId: CommandId, val ref: LocalProjectRef, val expectedRevision: Long,
    val change: TextBatchChange, targets: List<TextBatchTarget>,
) {
    val targets: List<TextBatchTarget> = java.util.Collections.unmodifiableList(ArrayList(targets))
    init {
        require(expectedRevision >= 0 && this.targets.size in 1..MAX_TARGETS)
        require(commandId.value.length <= 2048)
        require(this.targets.zipWithNext().all { (a, b) -> a.id.value < b.id.value })
    }
    private val digestValue: String by lazy { TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("version", "manual-batch-v1")
        put("command", commandId.value); put("project", ref.projectId.value)
        put("instance", ref.instanceId.value); put("generation", ref.generation.value)
        put("revision", expectedRevision); put("change", change.name)
        put("targets", buildJsonArray { targets.forEach { target -> add(buildJsonObject {
            put("id", target.id.value); put("row", target.row); put("fingerprint", target.fingerprint.sha256)
        }) } })
    }.toString())) }
    fun digest(): String = digestValue

    fun itemCommand(index: Int): CommandId {
        require(index in targets.indices)
        return CommandId(receiptPrefix(commandId) + digest() + ":" + index)
    }

    companion object {
        /** Matches TextProjectLimits.maxItems: a full-query batch may cover the whole project. */
        const val MAX_TARGETS = 100_000

        /** Only generated ASCII/hex enters a receipt-prefix lookup, never caller SQL patterns. */
        fun receiptPrefix(commandId: CommandId): String = "manual-batch-v1:" +
            TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(commandId.value)) + ":"
    }
}

data class ManualBatchReceipt(
    val commandId: CommandId, val ref: LocalProjectRef, val status: ManualEditStatus,
    val revision: Long, val affectedItems: Int,
)


/** Query selection is frozen before confirmation; the commit never reruns the query. */
interface QueryManualBatchPort {
    suspend fun prepareQualityBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        selection: TextQualitySelection, change: TextBatchChange): ManualTextBatch?
    suspend fun prepareQueryBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
        query: TextItemQuery, change: TextBatchChange): ManualTextBatch?
}
