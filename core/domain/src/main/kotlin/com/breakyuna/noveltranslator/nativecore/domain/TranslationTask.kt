package com.breakyuna.noveltranslator.nativecore.domain

/** Frozen paid policy and scope. No credentials or source/translation bodies. */
data class TranslationTaskSpec(
    val ref: LocalProjectRef, val run: RunIdentity, val configuration: ModelConfigurationRef,
    val revision: Long, val afterId: Long, val pageSize: Int,
    val wholeProject: Boolean, val options: RemoteTranslationOptions,
) {
    init {
        require(run.projectId == ref.projectId && run.chatId == null)
        require(revision >= 0 && afterId >= 0 && pageSize in 1..100)
        require(!wholeProject || afterId == 0L)
    }
    override fun toString() = "TranslationTaskSpec(run=${run.runId}, policy=redacted)"
}

data class TranslationTaskProgress(val revision: Long, val committed: Int, val requests: Int, val initialRevision: Long = revision) {
    init { require(initialRevision in 0..revision && committed >= 0 && requests >= 0) }
}
class TranslationTaskSummary(val ref: LocalProjectRef, val run: RunIdentity, val configuration: ModelConfigurationRef,
    val targetLanguage: String, val progress: TranslationTaskProgress,
    val targetCount: Int, val remaining: Int, val stage: String, val active: Boolean)
class TranslationTaskPage(tasks: List<TranslationTaskSummary>, val nextCursor: Long?) {
    val tasks = frozenList(tasks)
    init { require(nextCursor == null || nextCursor > 0) }
}
class TranslationTaskNeedsReview : IllegalStateException("Translation task requires receipt review")

class TranslationTaskOutput(val id: ItemId, val fingerprint: ObjectFingerprint, val text: String) {
    override fun toString() = "TranslationTaskOutput(id=$id, text=redacted)"
}
class TranslationTaskResponse(outputs: List<TranslationTaskOutput>, targets: List<ItemId>) {
    val outputs = frozenList(outputs)
    val targets = frozenList(targets)
}
class TranslationResponseNotStored : IllegalStateException("Translation response could not be saved")

/** Persistent coordination; every mutating execution hook requires the real run lease. */
interface TranslationTaskPort {
    suspend fun open(lease: RunLease, spec: TranslationTaskSpec, resuming: Boolean = false): TranslationTaskProgress
    suspend fun remaining(lease: RunLease, ref: LocalProjectRef, items: List<TextProjectItem>): Set<ItemId>
    suspend fun requesting(lease: RunLease, revision: Long, attempt: RequestAttemptId, targets: List<ItemId>, requests: Int)
    suspend fun committing(lease: RunLease, revision: Long, edits: List<ManualTextEdit>, failures: Boolean = false)
    suspend fun committed(lease: RunLease, revision: Long, committed: Int)
    suspend fun stage(lease: RunLease, outputs: List<TranslationTaskOutput>)
    /** Only one bounded commit batch is loaded; the UI never reads these bodies. */
    suspend fun staged(lease: RunLease): List<ManualTextEdit>
    suspend fun response(lease: RunLease): TranslationTaskResponse?
    suspend fun failed(lease: RunLease) = Unit
    suspend fun finished(lease: RunLease)
    /** Explicit user action. Reconcile receipts, reject uncertainty or changed project, never dispatch. */
    suspend fun resume(ref: LocalProjectRef, run: RunIdentity): TranslationTaskSpec
    suspend fun recent(ref: LocalProjectRef): List<TranslationTaskSummary>
    /** Descending stable insertion cursor, scoped to the exact local project identity. */
    suspend fun page(ref: LocalProjectRef, before: Long? = null): TranslationTaskPage {
        require(before == null)
        return TranslationTaskPage(recent(ref), null)
    }
}
