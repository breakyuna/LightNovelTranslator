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
    fun edit(db: SQLiteDatabase, command: ManualTextEdit, beforeCommit: () -> Unit): ManualEditReceipt =
        NativeProjectDatabase.transaction(db) {
            val digest = TextProjectFingerprint.command(command)
            val previous = find(db, command.ref, command.commandId)
            if (previous != null) {
                if (previous.first != digest) throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
                return@transaction previous.second
            }
            val revision = NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton = 1")
            val item = db.rawQuery(
                "SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items WHERE id = ?",
                arrayOf(command.itemId.value.toString()),
            ).use { if (it.moveToFirst()) NativeProjectDatabase.item(it) else null }
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
                val fingerprint = TextProjectFingerprint.item(item.id, item.row, item.source,
                    command.translation, command.status)
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

    fun receipt(db: SQLiteDatabase, ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? =
        find(db, ref, commandId)?.second

    fun checkedReceipt(db: SQLiteDatabase, command: ManualTextEdit): ManualEditReceipt? {
        val previous = find(db, command.ref, command.commandId) ?: return null
        if (previous.first != TextProjectFingerprint.command(command)) {
            throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        }
        return previous.second
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
