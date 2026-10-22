package com.breakyuna.noveltranslator.nativecore.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File

/** Native SQLite schema v1, not LG's schema and not a Room-managed project database. */
internal object NativeProjectDatabase {
    private val schema = listOf(
        """CREATE TABLE project (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            project_id TEXT NOT NULL, name TEXT NOT NULL,
            revision INTEGER NOT NULL CHECK(revision >= 0),
            fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1))""",
        """CREATE TABLE assets (
            id INTEGER PRIMARY KEY CHECK(id = 1), path TEXT NOT NULL UNIQUE,
            file_type TEXT NOT NULL CHECK(file_type = 'TXT'), codec TEXT NOT NULL CHECK(codec = 'raw-v1'),
            original_size INTEGER NOT NULL CHECK(original_size >= 0), sha256 TEXT NOT NULL)""",
        """CREATE TABLE asset_chunks (
            asset_id INTEGER NOT NULL REFERENCES assets(id), part INTEGER NOT NULL CHECK(part >= 0),
            data BLOB NOT NULL, PRIMARY KEY(asset_id, part))""",
        """CREATE TABLE items (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER NOT NULL REFERENCES assets(id),
            row_index INTEGER NOT NULL CHECK(row_index >= 0), source TEXT NOT NULL,
            translation TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('NONE','PROCESSED','EXCLUDED')),
            fingerprint TEXT NOT NULL, UNIQUE(asset_id, row_index))""",
        """CREATE TABLE write_receipts (
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            digest TEXT NOT NULL, item_id INTEGER NOT NULL, outcome TEXT NOT NULL,
            revision INTEGER NOT NULL, committed_at INTEGER NOT NULL,
            PRIMARY KEY(instance_id, generation, command_id))""",
        """CREATE TABLE item_revisions (
            revision INTEGER PRIMARY KEY, item_id INTEGER NOT NULL REFERENCES items(id),
            previous_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL, translation TEXT NOT NULL,
            status TEXT NOT NULL, instance_id TEXT NOT NULL, generation INTEGER NOT NULL,
            command_id TEXT NOT NULL, committed_at INTEGER NOT NULL)""",
    )

    fun create(file: File, projectId: ProjectId, name: String, path: RelativePath,
               source: ByteArray, lines: List<String>, checkActive: () -> Unit) {
        if (!file.createNewFile()) fail(TextProjectException.Reason.STORAGE_IO)
        SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            preserveCorruption).use { db ->
            db.rawQuery("PRAGMA journal_mode = DELETE", null).use {
                // Android opens read-write connections with its platform journal mode
                // (TRUNCATE by default); switch this connection explicitly.
                if (!it.moveToFirst() || it.getString(0) != "delete") {
                    fail(TextProjectException.Reason.STORAGE_IO)
                }
            }
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous = FULL")
            transaction(db) {
                schema.forEach(db::execSQL)
                db.execSQL("PRAGMA application_id = ${NativeProjectFormat.APPLICATION_ID}")
                db.version = NativeProjectFormat.SCHEMA_VERSION
                db.insertOrThrow("project", null, ContentValues().apply {
                    put("singleton", 1); put("project_id", projectId.value); put("name", name)
                    put("revision", 0); put("fingerprint_version", 1)
                })
                db.insertOrThrow("assets", null, ContentValues().apply {
                    put("id", 1); put("path", path.value); put("file_type", "TXT"); put("codec", "raw-v1")
                    put("original_size", source.size); put("sha256", TextProjectFingerprint.bytes(source))
                })
                var offset = 0
                var part = 0
                while (offset < source.size) {
                    checkActive()
                    val end = minOf(source.size, offset + CHUNK_BYTES)
                    db.insertOrThrow("asset_chunks", null, ContentValues().apply {
                        put("asset_id", 1); put("part", part++)
                        put("data", source.copyOfRange(offset, end))
                    })
                    offset = end
                }
                lines.forEachIndexed { index, text ->
                    checkActive()
                    val id = ItemId(index.toLong() + 1)
                    db.insertOrThrow("items", null, ContentValues().apply {
                        put("id", id.value); put("asset_id", 1); put("row_index", index)
                        put("source", text); put("translation", ""); put("status", TextItemStatus.NONE.name)
                        put("fingerprint", TextProjectFingerprint.item(id, index, text, "", TextItemStatus.NONE).sha256)
                    })
                }
                checkActive()
            }
        }
    }

    fun open(file: File, writable: Boolean = false): SQLiteDatabase {
        if (!file.isFile) fail(TextProjectException.Reason.UNKNOWN_PROJECT)
        verifyRollbackJournal(file)
        val db = SQLiteDatabase.openDatabase(file.path, null,
            (if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY) or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS, preserveCorruption)
        try {
            val application = scalar(db, "PRAGMA application_id")
            if (application != NativeProjectFormat.APPLICATION_ID.toLong()) fail(TextProjectException.Reason.INVALID_FORMAT)
            if (db.version != NativeProjectFormat.SCHEMA_VERSION) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
            if (writable) {
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("PRAGMA synchronous = FULL")
            }
            return db
        } catch (error: Throwable) {
            db.close()
            throw error
        }
    }

    /**
     * Rejects WAL projects before opening them. Android resets the connection journal mode on
     * every read-write open (platform default is TRUNCATE) and rollback journal modes are not
     * persisted in the file, so the portable signal is the SQLite header: file format write/read
     * version bytes are 1 for a rollback journal and 2 for WAL.
     */
    private fun verifyRollbackJournal(file: File) {
        val header = ByteArray(SQLITE_HEADER_BYTES)
        val read = file.inputStream().use { input -> input.read(header) }
        if (read < SQLITE_HEADER_BYTES) fail(TextProjectException.Reason.INVALID_FORMAT)
        if (!header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) {
            fail(TextProjectException.Reason.INVALID_FORMAT)
        }
        if (header[18].toInt() != 1 || header[19].toInt() != 1) {
            fail(TextProjectException.Reason.INVALID_FORMAT)
        }
    }

    /** API-24-compatible logical snapshot. Never copies the main file of an open database. */
    fun snapshot(db: SQLiteDatabase, destination: File, checkActive: () -> Unit) {
        if (!destination.isFile || destination.length() != 0L) {
            fail(TextProjectException.Reason.STORAGE_IO)
        }
        db.execSQL("ATTACH DATABASE ? AS snapshot", arrayOf(destination.path))
        try {
            transaction(db) {
                schema.forEach { sql -> db.execSQL(sql.replaceFirst("CREATE TABLE ", "CREATE TABLE snapshot.")) }
                db.execSQL("PRAGMA snapshot.application_id = ${NativeProjectFormat.APPLICATION_ID}")
                db.execSQL("PRAGMA snapshot.user_version = ${NativeProjectFormat.SCHEMA_VERSION}")
                for (table in listOf("project", "assets", "asset_chunks", "items", "write_receipts", "item_revisions")) {
                    checkActive()
                    db.execSQL("INSERT INTO snapshot.$table SELECT * FROM main.$table")
                }
                // Inserting explicit ids leaves AUTOINCREMENT high-water marks low, so a restored
                // snapshot could reuse a deleted item id. Copy the sequence table verbatim.
                if (hasSequenceTable(db, "snapshot") && hasSequenceTable(db, "main")) {
                    db.execSQL("DELETE FROM snapshot.sqlite_sequence")
                    db.execSQL("INSERT INTO snapshot.sqlite_sequence(name, seq) "
                        + "SELECT name, seq FROM main.sqlite_sequence")
                }
                checkActive()
            }
        } finally {
            db.execSQL("DETACH DATABASE snapshot")
        }
    }

    private fun hasSequenceTable(db: SQLiteDatabase, schema: String): Boolean = db.rawQuery(
        "SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'", null,
    ).use { it.moveToFirst() }

    fun summary(db: SQLiteDatabase, ref: LocalProjectRef): TextProjectSummary = db.rawQuery(
        "SELECT project_id, name, revision FROM project WHERE singleton = 1", null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        if (cursor.getString(0) != ref.projectId.value) fail(TextProjectException.Reason.WRONG_IDENTITY)
        val path = db.rawQuery("SELECT path FROM assets WHERE id = 1", null).use { asset ->
            if (!asset.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            RelativePath(asset.getString(0))
        }
        TextProjectSummary(ref, cursor.getString(1), cursor.getLong(2), path,
            scalar(db, "SELECT COUNT(*) FROM items").toInt())
    }

    fun item(cursor: Cursor): TextProjectItem {
        val id = ItemId(cursor.getLong(0))
        val row = cursor.getInt(1)
        val source = cursor.getString(2)
        val translation = cursor.getString(3)
        val status = try {
            TextItemStatus.valueOf(cursor.getString(4))
        } catch (_: IllegalArgumentException) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        val fingerprint = ObjectFingerprint(cursor.getString(5))
        if (fingerprint != TextProjectFingerprint.item(id, row, source, translation, status)) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        return TextProjectItem(id, row, source, translation, status, fingerprint)
    }

    fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        if (!it.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        it.getLong(0)
    }

    inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    const val ITEM_COLUMNS = "id, row_index, source, translation, status, fingerprint"
    const val CHUNK_BYTES = 64 * 1024
    private const val SQLITE_HEADER_BYTES = 100
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    // Android's default corruption handler may delete a corrupt database. Preserve user facts.
    private val preserveCorruption = DatabaseErrorHandler { fail(TextProjectException.Reason.CORRUPT_PROJECT) }
    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)
}
