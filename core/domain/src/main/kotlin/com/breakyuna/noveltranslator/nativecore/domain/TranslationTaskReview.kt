package com.breakyuna.noveltranslator.nativecore.domain

/** Immutable task-level review. Uncertain requests survive termination for later audit. */
data class TranslationTaskReview(
    val ref: LocalProjectRef,
    val run: RunIdentity,
    val stage: String,
    val revision: Long,
    val committed: Int,
    val remaining: Int,
    val requests: Int,
    val unresolvedRequests: Int,
    val fingerprint: ObjectFingerprint,
) {
    init {
        require(run.projectId == ref.projectId && run.chatId == null)
        require(revision >= 0 && committed >= 0 && remaining >= 0 && requests >= 0 && unresolvedRequests >= 0)
    }
}

/** A terminal decision never changes source items, paid receipts, or committed translations. */
interface TranslationTaskReviewPort {
    suspend fun review(ref: LocalProjectRef, run: RunIdentity): TranslationTaskReview
    suspend fun terminate(review: TranslationTaskReview, command: CommandId)
}

/** Bounded current-item evidence, without source or translation bodies. */
data class TranslationTaskMemberReview(val id: ItemId, val fingerprint: ObjectFingerprint, val status: TextItemStatus, val decision: String? = null)
class TranslationTaskMemberPage(val revision: Long, members: List<TranslationTaskMemberReview>, val next: Long?) {
    val members = frozenList(members)
}

interface TranslationTaskRetryPort {
    suspend fun keepErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId)

    suspend fun members(ref: LocalProjectRef, run: RunIdentity, after: Long = 0): TranslationTaskMemberPage
    /** Creates a new fixed task and budget; does not dispatch. The old task remains auditable. */
    suspend fun retryErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId): TranslationTaskSpec
}
