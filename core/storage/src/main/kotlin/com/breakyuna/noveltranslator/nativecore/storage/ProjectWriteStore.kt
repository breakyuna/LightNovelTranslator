package com.breakyuna.noveltranslator.nativecore.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*

/**
 * Transaction owner for manual TXT and trusted translation writes, always under a runtime write lease.
 * Does not implement ProjectWritePort or accept Agent approvals: that requires R19's durable
 * consumption binding. No caller can edit rows through the public repository except here.
 */
internal object ProjectWriteStore {
    fun edit(db: SQLiteDatabase, command: ManualTextEdit, beforeCommit: () -> Unit): ManualEditReceipt {
        rejectPolicyCommand(db, command.ref, command.commandId)
        if (batchReceiptCount(db, command.ref, command.commandId) != 0)
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        return editBound(db, command, beforeCommit)
    }

    private fun editBound(db: SQLiteDatabase, command: ManualTextEdit, beforeCommit: () -> Unit): ManualEditReceipt =
        NativeProjectDatabase.transaction(db) {
            rejectPolicyCommand(db, command.ref, command.commandId)
            val digest = TextProjectFingerprint.command(command)
            val previous = find(db, command.ref, command.commandId)
            if (previous != null) {
                if (previous.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction previous.second
            }
            val revision = NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton = 1")
            val columns = if (db.version >= 3) NativeProjectDatabase.ITEM_COLUMNS
                else NativeProjectDatabase.ITEM_COLUMNS_V2
            val item = db.rawQuery(
                "SELECT $columns FROM items WHERE id = ?",
                arrayOf(command.itemId.value.toString()),
            ).use { if (it.moveToFirst()) NativeProjectDatabase.item(it, db.version) else null }
            val status = when {
                command.expectedRevision != revision -> ManualEditStatus.STALE_REVISION
                item == null -> ManualEditStatus.UNKNOWN_ITEM
                command.baseFingerprint != item.fingerprint -> ManualEditStatus.STALE_FINGERPRINT
                else -> ManualEditStatus.COMMITTED
            }
            val now = System.currentTimeMillis().coerceAtLeast(0)
            val nextRevision = if (status == ManualEditStatus.COMMITTED) Math.addExact(revision, 1) else revision
            if (status == ManualEditStatus.COMMITTED) {
                checkNotNull(item)
                val fingerprint = if (db.version >= 3) TextProjectFingerprint.item(item.id, item.row,
                    item.source, command.translation, command.status, item.metadata, item.assetId, item.slot)
                else TextProjectFingerprint.item(item.id, item.row, item.source, command.translation, command.status)
                check(db.update("items", ContentValues().apply {
                    put("translation", command.translation); put("status", command.status.name)
                    put("fingerprint", fingerprint.sha256)
                }, "id = ?", arrayOf(item.id.value.toString())) == 1)
                db.execSQL("UPDATE project SET revision = ? WHERE singleton = 1", arrayOf(nextRevision))
                db.insertOrThrow("item_revisions", null, ContentValues().apply {
                    put("revision", nextRevision); put("item_id", item.id.value)
                    put("previous_fingerprint", item.fingerprint.sha256); put("fingerprint", fingerprint.sha256)
                    put("translation", command.translation); put("status", command.status.name)
                    put("instance_id", command.ref.instanceId.value); put("generation", command.ref.generation.value)
                    put("command_id", command.commandId.value); put("committed_at", now)
                })
            }
            db.insertOrThrow("write_receipts", null, ContentValues().apply {
                put("instance_id", command.ref.instanceId.value); put("generation", command.ref.generation.value)
                put("command_id", command.commandId.value); put("digest", digest); put("item_id", command.itemId.value)
                put("outcome", status.name); put("revision", nextRevision); put("committed_at", now)
            })
            // A cancellation or injected IO failure here rolls back the row, revision and receipt together.
            beforeCommit()
            ManualEditReceipt(command.commandId, command.ref, command.itemId, status, nextRevision, now)
        }

    fun editBatch(db: SQLiteDatabase, command: ManualTextBatch, beforeCommit: () -> Unit): ManualBatchReceipt =
        NativeProjectDatabase.transaction(db) {
            rejectPolicyCommand(db, command.ref, command.commandId)
            if (find(db, command.ref, command.commandId) != null)
                throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
            val storedCount = batchReceiptCount(db, command.ref, command.commandId)
            if (storedCount != 0) {
                val previous = command.targets.mapIndexed { index, _ ->
                    beforeCommit(); find(db, command.ref, command.itemCommand(index))
                }
                // An atomic batch has every receipt or none. A collision/partial set is never
                // interpreted as permission to fill in or replace remaining commands.
                if (storedCount != command.targets.size) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                val receipts = previous.mapIndexed { index, stored ->
                    if (stored == null || stored.second.itemId != command.targets[index].id)
                        throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                    stored.second
                }
                val status = receipts.first().status
                if (receipts.any { it.status != status }) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                if (receipts.indices.any { index ->
                    val expected = if (status == ManualEditStatus.COMMITTED) Math.addExact(command.expectedRevision, index.toLong() + 1)
                        else receipts.first().revision
                    receipts[index].revision != expected
                }) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction ManualBatchReceipt(command.commandId, command.ref, status, receipts.last().revision,
                    if (status == ManualEditStatus.COMMITTED) receipts.size else 0)
            }
            val revision = NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton = 1")
            var conflict: ManualEditStatus? = if (revision != command.expectedRevision) ManualEditStatus.STALE_REVISION else null
            if (conflict == null) {
                for (target in command.targets) {
                    beforeCommit()
                    db.rawQuery("SELECT row_index,fingerprint FROM items WHERE id=?", arrayOf(target.id.value.toString())).use {
                        if (!it.moveToFirst()) conflict = ManualEditStatus.UNKNOWN_ITEM
                        else if (it.getInt(0) != target.row || it.getString(1) != target.fingerprint.sha256)
                            conflict = ManualEditStatus.STALE_FINGERPRINT
                    }
                    if (conflict != null) break
                }
            }
            if (conflict != null) {
                val now = System.currentTimeMillis().coerceAtLeast(0)
                command.targets.forEachIndexed { index, target ->
                    db.insertOrThrow("write_receipts", null, ContentValues().apply {
                        put("instance_id", command.ref.instanceId.value); put("generation", command.ref.generation.value)
                        put("command_id", command.itemCommand(index).value); put("digest", command.digest()); put("item_id", target.id.value)
                        put("outcome", requireNotNull(conflict).name); put("revision", revision); put("committed_at", now)
                    })
                }
                beforeCommit()
                return@transaction ManualBatchReceipt(command.commandId, command.ref, requireNotNull(conflict), revision, 0)
            }
            command.targets.forEachIndexed { index, target ->
                beforeCommit()
                val translation = db.rawQuery("SELECT translation FROM items WHERE id=?", arrayOf(target.id.value.toString())).use {
                    check(it.moveToFirst()); it.getString(0)
                }
                val edit = ManualTextEdit(command.itemCommand(index), command.ref, target.id,
                    Math.addExact(revision, index.toLong()), target.fingerprint,
                    if (command.change.clearsTranslation) "" else translation, command.change.status)
                check(editBound(db, edit, beforeCommit).status == ManualEditStatus.COMMITTED)
            }
            beforeCommit()
            ManualBatchReceipt(command.commandId, command.ref, ManualEditStatus.COMMITTED,
                Math.addExact(revision, command.targets.size.toLong()), command.targets.size)
        }

    fun receipt(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? =
        find(db, ref, commandId)?.second

    fun checkedReceipt(db: SQLiteDatabase, command: ManualTextEdit): ManualEditReceipt? {
        rejectPolicyCommand(db, command.ref, command.commandId)
        if (batchReceiptCount(db, command.ref, command.commandId) != 0)
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        val previous = find(db, command.ref, command.commandId) ?: return null
        if (previous.first != TextProjectFingerprint.command(command)) {
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        }
        return previous.second
    }

    private fun rejectPolicyCommand(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId) {
        if (db.version >= 2 && policyReceipt(db, ref, commandId) != null)
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
    }

    fun policy(db: SQLiteDatabase, ref: LocalProjectRef): ProjectPolicySnapshot {
        val summary = NativeProjectDatabase.summary(db, ref)
        if (db.version == 1) return ProjectPolicySnapshot(ref, summary.revision, ProjectTextPolicy())
        val value = db.rawQuery("SELECT payload, fingerprint FROM text_policy WHERE singleton=1", null).use {
            if (!it.moveToFirst()) throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT)
            val policy = ProjectTextPolicyCodec.decode(it.getString(0))
            if (policy.fingerprint().sha256 != it.getString(1)) throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT)
            policy
        }
        return ProjectPolicySnapshot(ref, summary.revision, value)
    }

    fun editPolicy(db: SQLiteDatabase, command: ProjectPolicyEdit, beforeCommit: () -> Unit): PolicyEditReceipt =
        NativeProjectDatabase.transaction(db) {
            NativeProjectDatabase.summary(db, command.ref)
            if (find(db, command.ref, command.commandId) != null || batchReceiptCount(db, command.ref, command.commandId) != 0)
                throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
            val prior = policyReceipt(db, command.ref, command.commandId)
            if (prior != null) {
                if (prior.first != command.digest()) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction prior.second
            }
            val current = policy(db, command.ref)
            val status = when {
                command.expectedRevision != current.revision -> PolicyEditStatus.STALE_REVISION
                command.base != current.fingerprint -> PolicyEditStatus.STALE_FINGERPRINT
                else -> PolicyEditStatus.COMMITTED
            }
            // Migration, the policy, its global revision and the terminal receipt share one transaction.
            NativeProjectDatabase.migratePolicy(db)
            val next = if (status == PolicyEditStatus.COMMITTED) Math.addExact(current.revision, 1) else current.revision
            val fingerprint = if (status == PolicyEditStatus.COMMITTED) command.policy.fingerprint() else current.fingerprint
            val now = System.currentTimeMillis().coerceAtLeast(0)
            if (status == PolicyEditStatus.COMMITTED) {
                val payload = ProjectTextPolicyCodec.encode(command.policy)
                db.execSQL("UPDATE text_policy SET payload=?,fingerprint=? WHERE singleton=1", arrayOf(payload, fingerprint.sha256))
                db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
                db.insertOrThrow("policy_revisions", null, ContentValues().apply {
                    put("revision", next); put("previous_fingerprint", current.fingerprint.sha256)
                    put("fingerprint", fingerprint.sha256); put("payload", payload)
                    put("instance_id", command.ref.instanceId.value); put("generation", command.ref.generation.value)
                    put("command_id", command.commandId.value); put("committed_at", now)
                })
            }
            db.insertOrThrow("policy_receipts", null, ContentValues().apply {
                put("instance_id", command.ref.instanceId.value); put("generation", command.ref.generation.value)
                put("command_id", command.commandId.value); put("digest", command.digest()); put("outcome", status.name)
                put("revision", next); put("fingerprint", fingerprint.sha256); put("committed_at", now)
            })
            beforeCommit()
            PolicyEditReceipt(command.commandId, command.ref, status, next, fingerprint, now)
        }

    fun checkedPolicyReceipt(db: SQLiteDatabase, command: ProjectPolicyEdit): PolicyEditReceipt? {
        if (find(db, command.ref, command.commandId) != null || batchReceiptCount(db, command.ref, command.commandId) != 0)
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        val previous = policyReceipt(db, command.ref, command.commandId) ?: return null
        if (previous.first != command.digest()) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        return previous.second
    }

    private fun policyReceipt(db: SQLiteDatabase, ref: LocalProjectRef, command: CommandId): Pair<String, PolicyEditReceipt>? {
        if (db.version == 1) return null
        return db.rawQuery("SELECT digest,outcome,revision,fingerprint,committed_at FROM policy_receipts WHERE instance_id=? AND generation=? AND command_id=?",
            arrayOf(ref.instanceId.value, ref.generation.value.toString(), command.value)).use {
            if (!it.moveToFirst()) null else it.getString(0) to PolicyEditReceipt(command, ref,
                PolicyEditStatus.valueOf(it.getString(1)), it.getLong(2), ObjectFingerprint(it.getString(3)), it.getLong(4))
        }
    }

    private fun batchReceiptCount(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId): Int =
        db.rawQuery("SELECT COUNT(*) FROM write_receipts WHERE instance_id = ? AND generation = ? AND command_id GLOB ?",
            arrayOf(ref.instanceId.value, ref.generation.value.toString(), ManualTextBatch.receiptPrefix(commandId) + "*")).use {
            check(it.moveToFirst()); it.getInt(0)
        }

    private fun find(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId): Pair<String, ManualEditReceipt>? =
        db.rawQuery("""SELECT digest, item_id, outcome, revision, committed_at FROM write_receipts
            WHERE instance_id = ? AND generation = ? AND command_id = ?""",
            arrayOf(ref.instanceId.value, ref.generation.value.toString(), commandId.value)).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0) to ManualEditReceipt(
                commandId, ref, ItemId(cursor.getLong(1)), ManualEditStatus.valueOf(cursor.getString(2)),
                cursor.getLong(3), cursor.getLong(4),
            )
        }
}
