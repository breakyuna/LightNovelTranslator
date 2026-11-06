package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalConsumption
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalInsert
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalPersistencePort
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRecord
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalSettle
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalStatus
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalGrant
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.ChangeOperation
import com.breakyuna.noveltranslator.nativecore.domain.ChangeSetId
import com.breakyuna.noveltranslator.nativecore.domain.ChangeTarget
import com.breakyuna.noveltranslator.nativecore.domain.ChangeTargetKind
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.PreparedChange
import com.breakyuna.noveltranslator.nativecore.domain.PreparedChangeSet
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId

/**
 * Synthetic CAS store. A production adapter must provide the same atomic transitions
 * inside the application-record transaction; this class never enters app sources.
 */
internal class FakeApprovalStore : ApprovalPersistencePort {
    private class Entry(val record: ApprovalRecord, var status: ApprovalStatus) {
        fun chat() = ChatIdentity(record.changeSet.projectId, record.changeSet.chatId)
    }

    private val entries = LinkedHashMap<ApprovalRequestId, Entry>()
    private val lock = Any()
    var consumeCount = 0
        private set

    override suspend fun insert(record: ApprovalRecord): ApprovalInsert = synchronized(lock) {
        val existing = entries[record.id]
        if (existing != null) {
            return@synchronized if (existing.record.changeSet.digest == record.changeSet.digest &&
                existing.record.changeSet.runId == record.changeSet.runId &&
                existing.record.mode == record.mode
            ) {
                ApprovalInsert.Stored(existing.record)
            } else {
                ApprovalInsert.Conflict(existing.record)
            }
        }
        val chat = ChatIdentity(record.changeSet.projectId, record.changeSet.chatId)
        if (entries.values.any { it.status == ApprovalStatus.PENDING && it.chat() == chat }) {
            return@synchronized ApprovalInsert.Busy
        }
        entries[record.id] = Entry(record, ApprovalStatus.PENDING)
        ApprovalInsert.Stored(record)
    }

    override suspend fun find(id: ApprovalRequestId): ApprovalRecord? = synchronized(lock) {
        entries[id]?.record
    }

    override suspend fun status(id: ApprovalRequestId): ApprovalStatus? = synchronized(lock) {
        entries[id]?.status
    }

    override suspend fun pending(chat: ChatIdentity): List<ApprovalRecord> = synchronized(lock) {
        entries.values.filter { it.status == ApprovalStatus.PENDING && it.chat() == chat }.map { it.record }
    }

    override suspend fun settle(
        id: ApprovalRequestId,
        status: ApprovalStatus,
        atMillis: Long,
    ): ApprovalSettle = synchronized(lock) {
        require(status != ApprovalStatus.PENDING && status != ApprovalStatus.CONSUMED) {
            "settle only accepts terminal decisions"
        }
        val entry = entries[id] ?: return@synchronized ApprovalSettle.NotFound
        if (entry.status != ApprovalStatus.PENDING) {
            return@synchronized ApprovalSettle.AlreadySettled(entry.status)
        }
        entry.status = status
        ApprovalSettle.Won
    }

    override suspend fun consume(grant: ApprovalGrant, atMillis: Long): ApprovalConsumption = synchronized(lock) {
        val entry = entries[grant.requestId] ?: return@synchronized ApprovalConsumption.NotFound
        val changeSet = entry.record.changeSet
        val mismatch = when {
            changeSet.projectId != grant.projectId -> ApprovalConsumption.Mismatch.Field.PROJECT
            changeSet.chatId != grant.chatId -> ApprovalConsumption.Mismatch.Field.CHAT
            changeSet.runId != grant.runId -> ApprovalConsumption.Mismatch.Field.RUN
            changeSet.id != grant.changeSetId -> ApprovalConsumption.Mismatch.Field.CHANGE_SET
            changeSet.digest != grant.changeSetDigest -> ApprovalConsumption.Mismatch.Field.DIGEST
            else -> null
        }
        if (mismatch != null) return@synchronized ApprovalConsumption.Mismatch(mismatch)
        if (entry.status != ApprovalStatus.APPROVED) {
            return@synchronized ApprovalConsumption.NotApproved(entry.status)
        }
        entry.status = ApprovalStatus.CONSUMED
        consumeCount += 1
        ApprovalConsumption.Won
    }
}

internal fun changeSet(
    id: String = "changes",
    runId: RunId = RunId("run"),
    projectId: ProjectId = ProjectId("project"),
    chatId: ChatId = ChatId("chat"),
    content: String = "a",
    kind: ChangeTargetKind = ChangeTargetKind.ITEM,
    target: String = "item-1",
) = PreparedChangeSet(
    ChangeSetId(id), projectId, chatId, runId,
    listOf(
        PreparedChange(
            ChangeTarget(kind, target), ChangeOperation.UPDATE,
            ObjectFingerprint("a".repeat(64)), ObjectFingerprint(content.repeat(64)),
        ),
    ),
)
