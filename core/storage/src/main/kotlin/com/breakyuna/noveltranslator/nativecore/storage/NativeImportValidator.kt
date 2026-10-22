package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.security.MessageDigest

/** Untrusted input is validated read-only, before publication or local instance registration. */
internal object NativeImportValidator {
    fun validate(file: File, limits: TextProjectLimits, budget: NativeImportLimits,
                 checkActive: () -> Unit): ProjectId {
        if (file.length() > budget.maxFileBytes) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        NativeProjectDatabase.open(file).use { db ->
            NativeProjectDatabase.validateSchema(db)
            for ((table, columns) in listOf(
                "project" to listOf("singleton", "revision", "fingerprint_version"),
                "assets" to listOf("id", "original_size"), "asset_chunks" to listOf("asset_id", "part"),
                "items" to listOf("id", "asset_id", "row_index"),
                "item_revisions" to listOf("revision", "item_id", "generation", "committed_at"),
                "write_receipts" to listOf("generation", "item_id", "revision", "committed_at"),
            )) {
                val predicate = columns.joinToString(" OR ") { "typeof($it) != 'integer'" }
                db.rawQuery("SELECT 1 FROM $table WHERE $predicate LIMIT 1", null).use { if (it.moveToFirst()) corrupt() }
            }
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) corrupt()
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) corrupt() }
            if (count(db, "project") != 1L || count(db, "assets") != 1L) corrupt()
            requireTextBudget(db, "project", "name", 200)
            requireTextBudget(db, "project", "project_id", 2048)
            for ((table, columns) in listOf(
                "assets" to listOf("path" to 4096, "file_type" to 16, "codec" to 16, "sha256" to 64),
                "items" to listOf("fingerprint" to 64, "status" to 16),
                "item_revisions" to listOf("previous_fingerprint" to 64, "fingerprint" to 64, "status" to 16,
                    "instance_id" to 2048, "command_id" to 2048),
                "write_receipts" to listOf("instance_id" to 2048, "command_id" to 2048, "digest" to 64, "outcome" to 32),
            )) for ((column, max) in columns) requireTextBudget(db, table, column, max)
            val metadata = db.rawQuery("SELECT project_id, name, revision, fingerprint_version FROM project WHERE singleton = 1", null).use {
                if (!it.moveToFirst() || it.getLong(2) < 0 || it.getInt(3) != 1) corrupt()
                val name = it.getString(1)
                if (name.isBlank() || name.length > 200 || name.any(Char::isISOControl)) corrupt()
                TextProjectFingerprint.utf8(name)
                val id = it.getString(0)
                if (id.length > 2048) corrupt()
                ProjectId(id) to it.getLong(2)
            }
            validateAsset(db, limits, checkActive)
            if (count(db, "items") > limits.maxItems) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            if (count(db, "item_revisions") > budget.maxHistoryRows || count(db, "write_receipts") > budget.maxHistoryRows) {
                fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            requireTextBudget(db, "items", "source", limits.maxItemChars)
            requireTextBudget(db, "items", "translation", limits.maxItemChars)
            requireTextBudget(db, "item_revisions", "translation", limits.maxItemChars)
            var validationChars = 0L
            val chains = HashMap<Long, Pair<ObjectFingerprint, ObjectFingerprint>>()
            fun charge(chars: Long) {
                validationChars += chars
                if (validationChars > budget.maxValidationChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS}, asset_id FROM items ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    if (cursor.getLong(0) <= 0 || cursor.getLong(1) !in 0 until limits.maxItems.toLong() || cursor.getLong(6) != 1L) corrupt()
                    charge(cursor.getString(2).length.toLong() * 2 + cursor.getString(3).length)
                    val item = NativeProjectDatabase.item(cursor)
                    if (item.source.length > limits.maxItemChars || item.translation.length > limits.maxItemChars) {
                        fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                    }
                    chains[item.id.value] = TextProjectFingerprint.item(item.id, item.row, item.source, "", TextItemStatus.NONE) to item.fingerprint
                }
            }
            val maxItemId = NativeProjectDatabase.scalar(db, "SELECT COALESCE(MAX(id), 0) FROM items")
            db.rawQuery("SELECT name, seq FROM sqlite_sequence", null).use {
                var seen = false
                while (it.moveToNext()) {
                    if (seen || it.getString(0) != "items" || it.getLong(1) < maxItemId) corrupt()
                    seen = true
                }
                if (maxItemId > 0 && !seen) corrupt()
            }
            // All successful v1 edits produce one immutable revision. Never import a truncated history.
            if (count(db, "item_revisions") != metadata.second) corrupt()
            db.rawQuery("""SELECT r.revision, r.item_id, r.previous_fingerprint, r.fingerprint,
                r.translation, r.status, r.instance_id, r.generation, r.command_id, r.committed_at,
                i.row_index, i.source,
                (SELECT w.digest FROM write_receipts w WHERE w.instance_id = r.instance_id AND w.generation = r.generation
                    AND w.command_id = r.command_id) FROM item_revisions r JOIN items i ON i.id = r.item_id ORDER BY r.revision""", null).use { cursor ->
                var revision = 0L
                while (cursor.moveToNext()) {
                    checkActive()
                    if (cursor.getLong(0) != ++revision || cursor.getLong(7) <= 0 || cursor.getLong(9) < 0) corrupt()
                    if (cursor.getString(4).length > limits.maxItemChars) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                    charge(cursor.getString(11).length.toLong() + cursor.getString(4).length)
                    ObjectFingerprint(cursor.getString(2))
                    val chain = chains[cursor.getLong(1)] ?: corrupt()
                    if (chain.first.sha256 != cursor.getString(2)) corrupt()
                    val expected = TextProjectFingerprint.item(ItemId(cursor.getLong(1)), cursor.getInt(10),
                        cursor.getString(11), cursor.getString(4), TextItemStatus.valueOf(cursor.getString(5)))
                    if (expected.sha256 != cursor.getString(3)) corrupt()
                    chains[cursor.getLong(1)] = expected to chain.second
                    boundedIdentity(cursor.getString(6)); boundedIdentity(cursor.getString(8))
                    if (cursor.isNull(12)) corrupt()
                    val intent = ManualTextEdit(CommandId(cursor.getString(8)),
                        LocalProjectRef(metadata.first, ProjectInstanceId(cursor.getString(6)), RecoveryGeneration(cursor.getLong(7))),
                        ItemId(cursor.getLong(1)), revision - 1, ObjectFingerprint(cursor.getString(2)),
                        cursor.getString(4), TextItemStatus.valueOf(cursor.getString(5)))
                    if (TextProjectFingerprint.command(intent) != cursor.getString(12)) corrupt()
                }
            }
            if (chains.values.any { it.first != it.second }) corrupt()
            db.rawQuery("SELECT instance_id, generation, command_id, digest, item_id, outcome, revision, committed_at FROM write_receipts", null).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    boundedIdentity(cursor.getString(0)); boundedIdentity(cursor.getString(2))
                    ObjectFingerprint(cursor.getString(3))
                    ManualEditStatus.valueOf(cursor.getString(5))
                    if (cursor.getLong(1) <= 0 || cursor.getLong(4) <= 0 || cursor.getLong(6) !in 0..metadata.second || cursor.getLong(7) < 0) corrupt()
                }
            }
            db.rawQuery("""SELECT 1 FROM item_revisions r LEFT JOIN write_receipts w ON
                w.instance_id = r.instance_id AND w.generation = r.generation AND w.command_id = r.command_id
                WHERE w.command_id IS NULL OR w.outcome != 'COMMITTED' OR w.item_id != r.item_id OR w.revision != r.revision LIMIT 1""", null).use {
                if (it.moveToFirst()) corrupt()
            }
            db.rawQuery("""SELECT 1 FROM write_receipts w LEFT JOIN item_revisions r ON
                w.instance_id = r.instance_id AND w.generation = r.generation AND w.command_id = r.command_id
                WHERE w.outcome = 'COMMITTED' AND (r.revision IS NULL OR r.revision != w.revision OR r.item_id != w.item_id
                    OR r.committed_at != w.committed_at) LIMIT 1""", null).use { if (it.moveToFirst()) corrupt() }
            return metadata.first
        }
    }

    private fun validateAsset(db: SQLiteDatabase, limits: TextProjectLimits, checkActive: () -> Unit) {
        val asset = db.rawQuery("SELECT path, file_type, codec, original_size, sha256 FROM assets WHERE id = 1", null).use {
            if (!it.moveToFirst() || it.getString(1) != "TXT" || it.getString(2) != "raw-v1") corrupt()
            val path = RelativePath(it.getString(0))
            if (path.value.length > 4096 || !path.value.endsWith(".txt", ignoreCase = true)) corrupt()
            TextProjectFingerprint.utf8(path.value)
            val size = it.getLong(3)
            if (size !in 0..limits.maxSourceBytes.toLong()) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            size to ObjectFingerprint(it.getString(4)).sha256
        }
        // Check lengths before getBlob(), avoiding an oversized untrusted CursorWindow value.
        db.rawQuery("SELECT 1 FROM asset_chunks WHERE typeof(data) != 'blob' OR length(data) > ? OR length(data) = 0 OR asset_id != 1 LIMIT 1",
            arrayOf(NativeProjectDatabase.CHUNK_BYTES.toString())).use { if (it.moveToFirst()) corrupt() }
        val digest = MessageDigest.getInstance("SHA-256")
        val chunks = (asset.first + NativeProjectDatabase.CHUNK_BYTES - 1) / NativeProjectDatabase.CHUNK_BYTES
        if (count(db, "asset_chunks") != chunks) corrupt()
        var size = 0L
        var part = 0L
        db.rawQuery("SELECT part, data FROM asset_chunks ORDER BY part", null).use {
            while (it.moveToNext()) {
                checkActive()
                if (it.getLong(0) != part++) corrupt()
                val bytes = it.getBlob(1)
                val expectedLength = minOf(NativeProjectDatabase.CHUNK_BYTES.toLong(), asset.first - size).toInt()
                if (bytes.size != expectedLength) corrupt()
                size += bytes.size
                if (size > asset.first) corrupt()
                digest.update(bytes)
            }
        }
        val hash = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (size != asset.first || hash != asset.second) corrupt()
    }

    private fun requireTextBudget(db: SQLiteDatabase, table: String, column: String, limit: Int) {
        db.rawQuery("SELECT 1 FROM $table WHERE length($column) > ? OR length(CAST($column AS BLOB)) > ? LIMIT 1",
            arrayOf(limit.toString(), (limit * 4).toString())).use {
            if (it.moveToFirst()) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
    }
    private fun boundedIdentity(value: String) {
        if (value.isBlank() || value.length > 2048 || value.any(Char::isISOControl)) corrupt()
    }
    private fun count(db: SQLiteDatabase, table: String): Long = NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM $table")
    private fun corrupt(): Nothing = fail(TextProjectException.Reason.CORRUPT_PROJECT)
    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)
}
