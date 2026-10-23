package com.breakyuna.noveltranslator.nativecore.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Native SQLite schema v3 with multi-asset, v1/v2 rule migration and per-item format metadata. */
internal object NativeProjectDatabase {
    private val v1Schema = listOf(
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

    private val policySchema = listOf(
        """CREATE TABLE text_policy (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL, fingerprint TEXT NOT NULL)""",
        """CREATE TABLE policy_revisions (
            revision INTEGER PRIMARY KEY, previous_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL,
            payload TEXT NOT NULL, instance_id TEXT NOT NULL, generation INTEGER NOT NULL,
            command_id TEXT NOT NULL, committed_at INTEGER NOT NULL)""",
        """CREATE TABLE policy_receipts (
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            digest TEXT NOT NULL, outcome TEXT NOT NULL, revision INTEGER NOT NULL,
            fingerprint TEXT NOT NULL, committed_at INTEGER NOT NULL,
            PRIMARY KEY(instance_id, generation, command_id))""",
    )

    private val v3Schema = listOf(
        """CREATE TABLE project (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            project_id TEXT NOT NULL, name TEXT NOT NULL,
            revision INTEGER NOT NULL CHECK(revision >= 0),
            fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1))""",
        """CREATE TABLE assets (
            id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE,
            file_type TEXT NOT NULL, codec TEXT NOT NULL,
            original_size INTEGER NOT NULL CHECK(original_size >= 0), sha256 TEXT NOT NULL,
            ordinal INTEGER NOT NULL CHECK(ordinal >= 0), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
            metadata TEXT NOT NULL)""",
        """CREATE TABLE asset_chunks (
            asset_id INTEGER NOT NULL REFERENCES assets(id), part INTEGER NOT NULL CHECK(part >= 0),
            data BLOB NOT NULL, PRIMARY KEY(asset_id, part))""",
        """CREATE TABLE items (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER NOT NULL REFERENCES assets(id),
            row_index INTEGER NOT NULL CHECK(row_index >= 0), slot INTEGER NOT NULL CHECK(slot >= 0),
            source TEXT NOT NULL, translation TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('NONE','PROCESSED','ERROR','EXCLUDED','RULE_SKIPPED','LANGUAGE_SKIPPED','DUPLICATED')),
            fingerprint TEXT NOT NULL, tag TEXT NOT NULL, name_source TEXT, name_translation TEXT,
            extra_field TEXT NOT NULL, skip_internal_filter INTEGER NOT NULL CHECK(skip_internal_filter IN (0,1)),
            UNIQUE(asset_id, slot))""",
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
        """CREATE TABLE text_policy (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL, fingerprint TEXT NOT NULL)""",
        """CREATE TABLE policy_revisions (
            revision INTEGER PRIMARY KEY, previous_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL,
            payload TEXT NOT NULL, instance_id TEXT NOT NULL, generation INTEGER NOT NULL,
            command_id TEXT NOT NULL, committed_at INTEGER NOT NULL)""",
        """CREATE TABLE policy_receipts (
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            digest TEXT NOT NULL, outcome TEXT NOT NULL, revision INTEGER NOT NULL,
            fingerprint TEXT NOT NULL, committed_at INTEGER NOT NULL,
            PRIMARY KEY(instance_id, generation, command_id))""",
        """CREATE TABLE project_settings (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL, fingerprint TEXT NOT NULL)""",
        """CREATE TABLE asset_receipts (
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            digest TEXT NOT NULL, outcome TEXT NOT NULL, revision INTEGER NOT NULL,
            asset_id INTEGER, kind TEXT NOT NULL, committed_at INTEGER NOT NULL,
            PRIMARY KEY(instance_id, generation, command_id))""",
        """CREATE TABLE asset_revisions (
            revision INTEGER PRIMARY KEY, kind TEXT NOT NULL, asset_id INTEGER, payload TEXT NOT NULL,
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            committed_at INTEGER NOT NULL)""",
    )

    private fun schema(version: Int): List<String> = when (version) {
        1 -> v1Schema
        2 -> v1Schema + policySchema
        else -> v3Schema
    }

    private fun initialPolicy(db: SQLiteDatabase) {
        val policy = ProjectTextPolicy()
        db.insertOrThrow("text_policy", null, ContentValues().apply {
            put("singleton", 1); put("payload", ProjectTextPolicyCodec.encode(policy)); put("fingerprint", policy.fingerprint().sha256)
        })
    }

    private fun initialSettings(db: SQLiteDatabase) {
        val settings = ProjectSettings()
        db.insertOrThrow("project_settings", null, ContentValues().apply {
            put("singleton", 1); put("payload", ProjectSettingsCodec.encode(settings)); put("fingerprint", settings.fingerprint().sha256)
        })
    }

    /**
     * Called by the writable open path before any repository operation. v1 receives both the
     * rules tables and the multi-asset rebuild in one transaction. This is deliberately not
     * lazy: every v3 read path expects the v3 columns.
     */
    fun migrate(db: SQLiteDatabase) {
        if (db.version >= 3) return
        if (db.version !in 1..2) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
        db.setForeignKeyConstraintsEnabled(false)
        try {
            transaction(db) {
                if (db.version == 1) {
                    validateSchema(db)
                    policySchema.forEach(db::execSQL)
                    initialPolicy(db)
                    db.version = 2
                }
                if (db.version == 2) upgradeV3(db)
                db.version = 3
            }
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
        validateSchema(db)
    }

    private fun upgradeV3(db: SQLiteDatabase) {
        if (db.version != 2) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
        // Rebuild assets so the v1 single-row CHECK/foreign keys do not block additional assets.
        db.execSQL("""CREATE TABLE assets_v3 (
            id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE,
            file_type TEXT NOT NULL, codec TEXT NOT NULL,
            original_size INTEGER NOT NULL CHECK(original_size >= 0), sha256 TEXT NOT NULL,
            ordinal INTEGER NOT NULL CHECK(ordinal >= 0), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
            metadata TEXT NOT NULL)""")
        db.execSQL("""INSERT INTO assets_v3(id,path,file_type,codec,original_size,sha256,ordinal,enabled,metadata)
            SELECT id,path,file_type,codec,original_size,sha256,0,1,'{}' FROM assets""")
        db.execSQL("DROP TABLE assets")
        db.execSQL("ALTER TABLE assets_v3 RENAME TO assets")

        db.execSQL("""CREATE TABLE items_v3 (
            id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER NOT NULL REFERENCES assets(id),
            row_index INTEGER NOT NULL CHECK(row_index >= 0), slot INTEGER NOT NULL CHECK(slot >= 0),
            source TEXT NOT NULL, translation TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('NONE','PROCESSED','ERROR','EXCLUDED','RULE_SKIPPED','LANGUAGE_SKIPPED','DUPLICATED')),
            fingerprint TEXT NOT NULL, tag TEXT NOT NULL, name_source TEXT, name_translation TEXT,
            extra_field TEXT NOT NULL, skip_internal_filter INTEGER NOT NULL CHECK(skip_internal_filter IN (0,1)),
            UNIQUE(asset_id, slot))""")
        db.execSQL("""INSERT INTO items_v3(id,asset_id,row_index,slot,source,translation,status,fingerprint,
            tag,name_source,name_translation,extra_field,skip_internal_filter)
            SELECT id,asset_id,row_index,row_index,source,translation,status,fingerprint,'',NULL,NULL,'{}',0 FROM items""")
        db.execSQL("DROP TABLE items")
        db.execSQL("ALTER TABLE items_v3 RENAME TO items")

        db.execSQL(projectSettingsSchema)
        db.execSQL(assetReceiptsSchema)
        db.execSQL(assetRevisionsSchema)
        initialSettings(db)
    }

    /** Kept for the policy write path; writable opens already migrated the project to v3. */
    fun migratePolicy(db: SQLiteDatabase) {
        if (db.version < 2) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
    }

    fun create(file: File, projectId: ProjectId, name: String, path: RelativePath,
               source: ByteArray, lines: List<String>, checkActive: () -> Unit) {
        if (!file.createNewFile()) fail(TextProjectException.Reason.STORAGE_IO)
        SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            preserveCorruption).use { db ->
            db.rawQuery("PRAGMA journal_mode = DELETE", null).use {
                if (!it.moveToFirst() || it.getString(0) != "delete") {
                    fail(TextProjectException.Reason.STORAGE_IO)
                }
            }
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous = FULL")
            transaction(db) {
                schema(NativeProjectFormat.SCHEMA_VERSION).forEach(db::execSQL)
                db.execSQL("PRAGMA application_id = ${NativeProjectFormat.APPLICATION_ID}")
                db.version = NativeProjectFormat.SCHEMA_VERSION
                db.insertOrThrow("project", null, ContentValues().apply {
                    put("singleton", 1); put("project_id", projectId.value); put("name", name)
                    put("revision", 0); put("fingerprint_version", 1)
                })
                db.insertOrThrow("assets", null, ContentValues().apply {
                    put("path", path.value); put("file_type", "TXT"); put("codec", "raw-v1")
                    put("original_size", source.size); put("sha256", TextProjectFingerprint.bytes(source))
                    put("ordinal", 0); put("enabled", 1); put("metadata", "{}")
                })
                initialPolicy(db)
                initialSettings(db)
                insertChunks(db, 1L, source, checkActive)
                lines.forEachIndexed { index, text ->
                    checkActive()
                    val id = ItemId(index.toLong() + 1)
                    db.insertOrThrow("items", null, ContentValues().apply {
                        put("id", id.value); put("asset_id", 1); put("row_index", index); put("slot", index)
                        put("source", text); put("translation", ""); put("status", TextItemStatus.NONE.name)
                        put("fingerprint", TextProjectFingerprint.item(id, index, text, "", TextItemStatus.NONE).sha256)
                        put("tag", ""); putNull("name_source"); putNull("name_translation")
                        put("extra_field", "{}"); put("skip_internal_filter", 0)
                    })
                }
                checkActive()
            }
        }
    }

    fun insertChunks(db: SQLiteDatabase, assetId: Long, source: ByteArray, checkActive: () -> Unit) {
        var offset = 0
        var part = 0
        while (offset < source.size) {
            checkActive()
            val end = minOf(source.size, offset + CHUNK_BYTES)
            db.insertOrThrow("asset_chunks", null, ContentValues().apply {
                put("asset_id", assetId); put("part", part++)
                put("data", source.copyOfRange(offset, end))
            })
            offset = end
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
            if (db.version !in 1..NativeProjectFormat.SCHEMA_VERSION) fail(TextProjectException.Reason.UNSUPPORTED_VERSION)
            if (writable) {
                // Migration opens foreign keys off so the parent rebuild cannot cascade or block.
                migrate(db)
                db.execSQL("PRAGMA synchronous = FULL")
            }
            return db
        } catch (error: Throwable) {
            db.close()
            throw error
        }
    }

    /** v1/v2/v3 import is strict: unknown tables/triggers/views cannot be discarded. */
    fun validateSchema(db: SQLiteDatabase) {
        if (scalar(db, "SELECT COUNT(*) FROM sqlite_master") > 40) fail(TextProjectException.Reason.INVALID_FORMAT)
        val expected = schema(db.version).associate { sql -> sql.substringAfter("CREATE TABLE ").substringBefore(' ').trim() to normalize(sql) }
        val seen = mutableSetOf<String>()
        db.rawQuery("SELECT type, name, sql FROM sqlite_master", null).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val name = cursor.getString(1)
                if (type == "index" && cursor.isNull(2)) continue
                if (name == "sqlite_sequence" && type == "table" && !cursor.isNull(2) &&
                    normalize(cursor.getString(2)) == "CREATETABLEsqlite_sequence(name,seq)") continue
                if (type != "table" || name !in expected || cursor.isNull(2)) fail(TextProjectException.Reason.INVALID_FORMAT)
                val sql = cursor.getString(2)
                if (sql.length > 16_384 || normalize(sql) != expected[name]) fail(TextProjectException.Reason.INVALID_FORMAT)
                seen.add(name)
            }
        }
        if (seen != expected.keys) fail(TextProjectException.Reason.INVALID_FORMAT)
    }

    private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), "").replace("\"", "")

    /**
     * Rejects WAL projects before opening them. Android resets the connection journal mode on
     * every read-write open (platform default is TRUNCATE) and rollback journal modes are not
     * persisted in the file, so the portable signal is the SQLite header.
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
                schema(db.version).forEach { sql -> db.execSQL(sql.replaceFirst("CREATE TABLE ", "CREATE TABLE snapshot.")) }
                db.execSQL("PRAGMA snapshot.application_id = ${NativeProjectFormat.APPLICATION_ID}")
                db.execSQL("PRAGMA snapshot.user_version = ${db.version}")
                for (table in tables(db.version)) {
                    checkActive()
                    db.execSQL("INSERT INTO snapshot.$table SELECT * FROM main.$table")
                }
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

    private fun tables(version: Int): List<String> = when (version) {
        1 -> listOf("project", "assets", "asset_chunks", "items", "write_receipts", "item_revisions")
        2 -> listOf("project", "assets", "asset_chunks", "items", "write_receipts", "item_revisions",
            "text_policy", "policy_revisions", "policy_receipts")
        else -> listOf("project", "assets", "asset_chunks", "items", "write_receipts", "item_revisions",
            "text_policy", "policy_revisions", "policy_receipts", "project_settings", "asset_receipts", "asset_revisions")
    }

    private fun hasSequenceTable(db: SQLiteDatabase, schema: String): Boolean = db.rawQuery(
        "SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'", null,
    ).use { it.moveToFirst() }

    fun summary(db: SQLiteDatabase, ref: LocalProjectRef): TextProjectSummary = db.rawQuery(
        "SELECT project_id, name, revision FROM project WHERE singleton = 1", null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        if (cursor.getString(0) != ref.projectId.value) fail(TextProjectException.Reason.WRONG_IDENTITY)
        val assets = assetSummaries(db)
        if (assets.isEmpty()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        val settings = if (db.version >= 3) projectSettings(db) else ProjectSettings()
        TextProjectSummary(ref, cursor.getString(1), cursor.getLong(2), assets.first().path,
            scalar(db, "SELECT COUNT(*) FROM items").toInt(), assets, settings)
    }

    fun assetSummaries(db: SQLiteDatabase): List<ProjectAssetSummary> {
        if (db.version < 3) {
            return db.rawQuery("SELECT id,path,file_type,original_size,sha256,0,1 FROM assets ORDER BY id", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(ProjectAssetSummary(cursor.getLong(0), RelativePath(cursor.getString(1)),
                            ProjectAssetFormat.valueOf(cursor.getString(2)), cursor.getInt(5), cursor.getInt(6) != 0,
                            cursor.getLong(3), cursor.getString(4),
                            scalar(db, "SELECT COUNT(*) FROM items WHERE asset_id = ${cursor.getLong(0)}").toInt()))
                    }
                }
            }
        }
        return db.rawQuery("""SELECT a.id,a.path,a.file_type,a.original_size,a.sha256,a.ordinal,a.enabled,
            (SELECT COUNT(*) FROM items i WHERE i.asset_id=a.id) FROM assets a ORDER BY a.ordinal,a.id""", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ProjectAssetSummary(cursor.getLong(0), RelativePath(cursor.getString(1)),
                        ProjectAssetFormat.valueOf(cursor.getString(2)), cursor.getInt(5), cursor.getInt(6) != 0,
                        cursor.getLong(3), cursor.getString(4), cursor.getInt(7)))
                }
            }
        }
    }

    fun projectSettings(db: SQLiteDatabase): ProjectSettings {
        if (db.version < 3) return ProjectSettings()
        return db.rawQuery("SELECT payload,fingerprint FROM project_settings WHERE singleton=1", null).use {
            if (!it.moveToFirst()) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            val settings = ProjectSettingsCodec.decode(it.getString(0))
            if (settings.fingerprint().sha256 != it.getString(1)) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            settings
        }
    }

    fun item(cursor: Cursor, version: Int = 3): TextProjectItem {
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
        if (version < 3) {
            if (fingerprint != TextProjectFingerprint.item(id, row, source, translation, status)) {
                fail(TextProjectException.Reason.CORRUPT_PROJECT)
            }
            return TextProjectItem(id, row, source, translation, status, fingerprint)
        }
        val assetId = cursor.getLong(6)
        val slot = cursor.getInt(7)
        val metadata = try {
            TextItemMetadata(cursor.getString(8), decodeNames(cursor.getString(9)),
                decodeNames(cursor.getString(10)), cursor.getString(11), cursor.getInt(12) != 0)
        } catch (_: IllegalArgumentException) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        if (fingerprint != TextProjectFingerprint.item(id, row, source, translation, status, metadata, assetId, slot)) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        return TextProjectItem(id, row, source, translation, status, fingerprint, assetId, slot, metadata)
    }

    fun encodeNames(names: List<String>?): String? = names?.let { JsonArray(it.map(::JsonPrimitive)).toString() }

    fun decodeNames(value: String?): List<String>? {
        if (value == null) return null
        val element = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        if (element !is JsonArray || element.size > 32) fail(TextProjectException.Reason.CORRUPT_PROJECT)
        return element.map { item ->
            val primitive = item as? JsonPrimitive ?: fail(TextProjectException.Reason.CORRUPT_PROJECT)
            if (!primitive.isString || primitive is JsonNull) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            primitive.contentOrNull ?: fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
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

    const val ITEM_COLUMNS = "id, row_index, source, translation, status, fingerprint, asset_id, slot, " +
        "tag, name_source, name_translation, extra_field, skip_internal_filter"
    const val ITEM_COLUMNS_V2 = "id, row_index, source, translation, status, fingerprint"
    const val CHUNK_BYTES = 64 * 1024
    private const val SQLITE_HEADER_BYTES = 100
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val preserveCorruption = DatabaseErrorHandler { fail(TextProjectException.Reason.CORRUPT_PROJECT) }
    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)

    private const val projectSettingsSchema = """CREATE TABLE project_settings (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL, fingerprint TEXT NOT NULL)"""
    private const val assetReceiptsSchema = """CREATE TABLE asset_receipts (
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            digest TEXT NOT NULL, outcome TEXT NOT NULL, revision INTEGER NOT NULL,
            asset_id INTEGER, kind TEXT NOT NULL, committed_at INTEGER NOT NULL,
            PRIMARY KEY(instance_id, generation, command_id))"""
    private const val assetRevisionsSchema = """CREATE TABLE asset_revisions (
            revision INTEGER PRIMARY KEY, kind TEXT NOT NULL, asset_id INTEGER, payload TEXT NOT NULL,
            instance_id TEXT NOT NULL, generation INTEGER NOT NULL, command_id TEXT NOT NULL,
            committed_at INTEGER NOT NULL)"""
}
