package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalConsumption
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalDecision
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalGrant
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalInsert
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalMode
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalPersistencePort
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRecord
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalSettle
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalStatus
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalView
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.PreparedChangeSet
import com.breakyuna.noveltranslator.nativecore.domain.RunId

class ApprovalConflict : IllegalStateException("Approval request conflicts with stored content")

class ApprovalUnavailable(val reason: Reason, val status: ApprovalStatus? = null) :
    IllegalStateException("Approval unavailable: $reason") {
    enum class Reason { NOT_FOUND, ALREADY_PENDING, ALREADY_SETTLED, EXPIRED }
}

class ApprovalGrantRejected(val reason: Reason) : IllegalStateException("Approval grant rejected: $reason") {
    enum class Reason { NOT_FOUND, NOT_APPROVED, ALREADY_CONSUMED, IDENTITY_MISMATCH }
}

/**
 * Decision semantics for AG11/AG12 prototypes: the user approves a fixed change set
 * exactly once, and the submit path must consume that approval through the durable
 * store. This class owns no UI, no timer and no project write; a foreground service
 * calls [expireOverdue] on resume and [cancelRun] when a run really ends.
 *
 * The store is the authority for transitions. Any in-memory coordination here is a
 * convenience; process death must leave the same facts in the store.
 */
class ApprovalCoordinator(
    private val store: ApprovalPersistencePort,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Insert a request and, for AUTO mode, settle it immediately. Repeating the same
     * request identity with identical content is idempotent; different content is a
     * conflict. A second pending request for the same Chat is rejected before insert
     * and by the store atomically.
     */
    suspend fun request(
        id: ApprovalRequestId,
        changeSet: PreparedChangeSet,
        mode: ApprovalMode,
        timeoutMillis: Long? = null,
    ): ApprovalView {
        require(mode == ApprovalMode.MANUAL || timeoutMillis == null) {
            "An automatic approval cannot have a pending deadline"
        }
        val now = clock()
        val deadline = timeoutMillis?.let { timeout ->
            require(timeout > 0) { "Approval timeout must be positive" }
            val candidate = now + timeout
            if (candidate <= now) throw IllegalArgumentException("Approval timeout overflows")
            candidate
        }
        // A transport retry may repeat the same command identity; it must observe the
        // existing request instead of being rejected as a second pending decision.
        val existing = store.find(id)
        if (existing != null) {
            if (existing.changeSet.digest != changeSet.digest || existing.mode != mode ||
                existing.changeSet.runId != changeSet.runId
            ) {
                throw ApprovalConflict()
            }
            return existingView(existing, mode, now)
        }

        val chat = ChatIdentity(changeSet.projectId, changeSet.chatId)
        if (store.pending(chat).isNotEmpty()) throw ApprovalUnavailable(ApprovalUnavailable.Reason.ALREADY_PENDING)

        val record = ApprovalRecord(id, changeSet, mode, now, deadline)
        val stored = when (val inserted = store.insert(record)) {
            is ApprovalInsert.Conflict -> throw ApprovalConflict()
            ApprovalInsert.Busy -> throw ApprovalUnavailable(ApprovalUnavailable.Reason.ALREADY_PENDING)
            is ApprovalInsert.Stored -> inserted.record
        }
        if (stored.changeSet.digest != changeSet.digest || stored.mode != mode ||
            stored.changeSet.runId != changeSet.runId
        ) {
            throw ApprovalConflict()
        }
        return existingView(stored, mode, now)
    }

    suspend fun pending(chat: ChatIdentity): List<ApprovalView> = store.pending(chat).mapNotNull { record ->
        val status = store.status(record.id) ?: return@mapNotNull null
        if (status == ApprovalStatus.PENDING) ApprovalView(record, status) else null
    }

    suspend fun find(id: ApprovalRequestId): ApprovalView? {
        val record = store.find(id) ?: return null
        return ApprovalView(record, store.status(id) ?: return null)
    }

    /**
     * Settle a manual request. The mode stored with the request decides; a preference
     * change after the request was created cannot approve or reject it automatically.
     * Expiry is evaluated against the record deadline before the CAS.
     */
    suspend fun decide(id: ApprovalRequestId, decision: ApprovalDecision): ApprovalView {
        val record = store.find(id) ?: throw ApprovalUnavailable(ApprovalUnavailable.Reason.NOT_FOUND)
        var status = store.status(id) ?: throw ApprovalUnavailable(ApprovalUnavailable.Reason.NOT_FOUND)
        if (status != ApprovalStatus.PENDING) {
            throw ApprovalUnavailable(
                if (status == ApprovalStatus.EXPIRED) ApprovalUnavailable.Reason.EXPIRED
                else ApprovalUnavailable.Reason.ALREADY_SETTLED,
                status,
            )
        }
        if (record.mode != ApprovalMode.MANUAL) {
            throw ApprovalUnavailable(ApprovalUnavailable.Reason.ALREADY_SETTLED, status)
        }
        val now = clock()
        val deadline = record.deadlineMillis
        if (deadline != null && now > deadline) {
            settle(id, ApprovalStatus.EXPIRED, now)
            throw ApprovalUnavailable(ApprovalUnavailable.Reason.EXPIRED, ApprovalStatus.EXPIRED)
        }
        val target = when (decision) {
            ApprovalDecision.ALLOW_ONCE -> ApprovalStatus.APPROVED
            ApprovalDecision.REJECT -> ApprovalStatus.REJECTED
        }
        status = settle(id, target, now)
        return ApprovalView(record, status)
    }

    /**
     * Consume the single approval for a batch. Callers submit the returned grant to the
     * write store, which verifies it again against its own receipt. A second consume is
     * rejected even when the caller still holds a copy of the grant.
     */
    suspend fun consume(id: ApprovalRequestId): ApprovalGrant {
        val record = store.find(id) ?: throw ApprovalGrantRejected(ApprovalGrantRejected.Reason.NOT_FOUND)
        val grant = ApprovalGrant(
            requestId = id,
            projectId = record.changeSet.projectId,
            chatId = record.changeSet.chatId,
            runId = record.changeSet.runId,
            changeSetId = record.changeSet.id,
            changeSetDigest = record.changeSet.digest,
        )
        return when (val result = store.consume(grant, clock())) {
            ApprovalConsumption.Won -> grant
            ApprovalConsumption.NotFound -> throw ApprovalGrantRejected(ApprovalGrantRejected.Reason.NOT_FOUND)
            is ApprovalConsumption.Mismatch ->
                throw ApprovalGrantRejected(ApprovalGrantRejected.Reason.IDENTITY_MISMATCH)
            is ApprovalConsumption.NotApproved -> throw ApprovalGrantRejected(
                if (result.status == ApprovalStatus.CONSUMED) ApprovalGrantRejected.Reason.ALREADY_CONSUMED
                else ApprovalGrantRejected.Reason.NOT_APPROVED,
            )
        }
    }

    /** Service-level resume hook; no timer is scheduled inside the coordinator. */
    suspend fun expireOverdue(chat: ChatIdentity): Int {
        val now = clock()
        var expired = 0
        store.pending(chat).forEach { record ->
            val deadline = record.deadlineMillis ?: return@forEach
            if (now > deadline && store.settle(record.id, ApprovalStatus.EXPIRED, now) == ApprovalSettle.Won) {
                expired += 1
            }
        }
        return expired
    }

    /** Cancel requests from the run that is actually ending, not every Chat request. */
    suspend fun cancelRun(chat: ChatIdentity, runId: RunId): Int {
        val now = clock()
        var cancelled = 0
        store.pending(chat).forEach { record ->
            if (record.changeSet.runId == runId &&
                store.settle(record.id, ApprovalStatus.CANCELLED, now) == ApprovalSettle.Won
            ) {
                cancelled += 1
            }
        }
        return cancelled
    }

    /**
     * A crash between insert and settling an AUTO request leaves PENDING; finish it
     * here rather than showing an automatic request that never resolves.
     */
    private suspend fun existingView(record: ApprovalRecord, mode: ApprovalMode, now: Long): ApprovalView {
        var status = store.status(record.id) ?: throw ApprovalUnavailable(ApprovalUnavailable.Reason.NOT_FOUND)
        if (mode == ApprovalMode.AUTO && status == ApprovalStatus.PENDING) {
            status = settle(record.id, ApprovalStatus.APPROVED, now)
        }
        return ApprovalView(record, status)
    }

    private suspend fun settle(id: ApprovalRequestId, status: ApprovalStatus, now: Long): ApprovalStatus =
        when (val result = store.settle(id, status, now)) {
            ApprovalSettle.Won -> status
            is ApprovalSettle.AlreadySettled -> throw ApprovalUnavailable(
                if (result.status == ApprovalStatus.EXPIRED) ApprovalUnavailable.Reason.EXPIRED
                else ApprovalUnavailable.Reason.ALREADY_SETTLED,
                result.status,
            )
            ApprovalSettle.NotFound -> throw ApprovalUnavailable(ApprovalUnavailable.Reason.NOT_FOUND)
        }
}
