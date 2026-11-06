package com.breakyuna.noveltranslator.nativecore.domain

/** Persisted preference at request time; a later switch never re-decides a shown request. */
enum class ApprovalMode { MANUAL, AUTO }

/** LG exposes exactly these two outcomes; there is no session-wide grant. */
enum class ApprovalDecision { ALLOW_ONCE, REJECT }

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED, CONSUMED }

/**
 * A fixed change set waiting for one decision. The record carries the mode that was
 * active when the request was created, so changing the preference afterwards keeps
 * this request manual (or automatic) as it was shown to the user.
 */
data class ApprovalRecord(
    val id: ApprovalRequestId,
    val changeSet: PreparedChangeSet,
    val mode: ApprovalMode,
    val requestedAtMillis: Long,
    val deadlineMillis: Long?,
) {
    init {
        require(requestedAtMillis >= 0) { "Invalid request time" }
        require(deadlineMillis == null || deadlineMillis > requestedAtMillis) { "Invalid approval deadline" }
        require(mode == ApprovalMode.MANUAL || deadlineMillis == null) {
            "An automatic approval has no pending deadline"
        }
    }
}

/** Immutable projection for UI and command replies. */
data class ApprovalView(val record: ApprovalRecord, val status: ApprovalStatus)

/**
 * Evidence that exactly one approval was consumed for this batch. The write store
 * must verify these identities and the digest against its own durable receipt; a
 * grant object alone is not a write authorization.
 */
data class ApprovalGrant(
    val requestId: ApprovalRequestId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    val changeSetId: ChangeSetId,
    val changeSetDigest: ChangeSetDigest,
)

sealed interface ApprovalInsert {
    data class Stored(val record: ApprovalRecord) : ApprovalInsert
    /** Same request identity but different content/mode/run. */
    data class Conflict(val existing: ApprovalRecord) : ApprovalInsert
    /** Another request for the same Chat is already pending. */
    data object Busy : ApprovalInsert
}

sealed interface ApprovalSettle {
    data object Won : ApprovalSettle
    data class AlreadySettled(val status: ApprovalStatus) : ApprovalSettle
    data object NotFound : ApprovalSettle
}

sealed interface ApprovalConsumption {
    data object Won : ApprovalConsumption
    data class NotApproved(val status: ApprovalStatus) : ApprovalConsumption
    data class Mismatch(val field: Field) : ApprovalConsumption {
        enum class Field { PROJECT, CHAT, RUN, CHANGE_SET, DIGEST }
    }
    data object NotFound : ApprovalConsumption
}

/**
 * Durable approval authority. There is no production implementation yet; an adapter
 * must make insert/settle/consume atomic CAS transitions with an at-most-once
 * consumption receipt, and must reject a second PENDING row for the same Chat.
 */
interface ApprovalPersistencePort {
    suspend fun insert(record: ApprovalRecord): ApprovalInsert
    suspend fun find(id: ApprovalRequestId): ApprovalRecord?
    suspend fun status(id: ApprovalRequestId): ApprovalStatus?
    suspend fun pending(chat: ChatIdentity): List<ApprovalRecord>

    /** CAS from PENDING to APPROVED/REJECTED/EXPIRED/CANCELLED only. */
    suspend fun settle(id: ApprovalRequestId, status: ApprovalStatus, atMillis: Long): ApprovalSettle

    /** CAS from APPROVED to CONSUMED, verifying every grant identity first. */
    suspend fun consume(grant: ApprovalGrant, atMillis: Long): ApprovalConsumption
}
