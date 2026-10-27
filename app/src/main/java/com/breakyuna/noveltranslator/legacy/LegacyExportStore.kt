package com.breakyuna.noveltranslator.legacy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.PromptProfileEntity
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Opens the legacy database with `SQLiteDatabase.OPEN_READONLY` and copies the tables that the
 * export needs into a throw-away Room database. The legacy file is never handed to Room, never
 * migrated, and never written to. The scratch database exists only so `EditionExporter` can be
 * reused unchanged; it is removed by [close] and never receives the provider credential table.
 *
 * Row copying is column-name based, so a legacy database from an older schema can still be
 * read: columns that do not exist yet are left to the current schema defaults, and columns that
 * are no longer known are ignored. Missing optional tables become empty exports; a missing
 * `books` table is fatal.
 */
class LegacyExportStore private constructor(
    internal val database: AppDatabase,
    val source: LegacyExportSourceInfo,
    val warnings: List<String>,
    private val scratchFiles: List<File>,
) : Closeable {

    override fun close() {
        runCatching { database.close() }
        // Cleanup is limited to this export's random scratch database. The legacy database is
        // never in this list and is never deleted by this class.
        scratchFiles.forEach { file ->
            runCatching { if (file.exists()) file.delete() }
        }
    }

    /** All immutable prompt versions, not just each project's latest one. */
    internal suspend fun allPromptProfiles(): List<PromptProfileEntity> = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT * FROM translation_prompt_profiles ORDER BY translationProjectId, version"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val projectIndex = cursor.getColumnIndexOrThrow("translationProjectId")
            val versionIndex = cursor.getColumnIndexOrThrow("version")
            val translationSystemIndex = cursor.getColumnIndexOrThrow("translationSystemPrompt")
            val translationUserIndex = cursor.getColumnIndexOrThrow("translationUserPromptTemplate")
            val polishSystemIndex = cursor.getColumnIndexOrThrow("polishSystemPrompt")
            val polishUserIndex = cursor.getColumnIndexOrThrow("polishUserPromptTemplate")
            val metadataIndex = cursor.getColumnIndexOrThrow("includeMetadataRules")
            val createdIndex = cursor.getColumnIndexOrThrow("createdAt")
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PromptProfileEntity(
                            id = cursor.getLong(idIndex),
                            translationProjectId = cursor.getLong(projectIndex),
                            version = cursor.getInt(versionIndex),
                            translationSystemPrompt = cursor.getString(translationSystemIndex).orEmpty(),
                            translationUserPromptTemplate = cursor.getString(translationUserIndex).orEmpty(),
                            polishSystemPrompt = cursor.getString(polishSystemIndex).orEmpty(),
                            polishUserPromptTemplate = cursor.getString(polishUserIndex).orEmpty(),
                            includeMetadataRules = cursor.getInt(metadataIndex) != 0,
                            createdAt = cursor.getLong(createdIndex),
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val SCRATCH_PREFIX = "legacy-export-scratch-"

        /**
         * Tables copied for export. `api_providers`, translation runs and request logs are
         * intentionally absent: they either contain credentials or are operational noise.
         */
        private val COPIED_TABLES = listOf(
            "books",
            "editions",
            "logical_chapters",
            "logical_segments",
            "edition_chapters",
            "edition_segments",
            "edition_segment_mappings",
            "segment_revisions",
            "translation_projects_v2",
            "translation_prompt_profiles",
            "lexicon_entries",
            "quality_rule_sets",
            "agent_sessions",
            "agent_messages",
            "reader_progress",
        )
        private val REQUIRED_TABLES = setOf("books")

        suspend fun openReadOnly(context: Context, legacyDatabase: File): LegacyExportStore =
            withContext(Dispatchers.IO) {
                val sourceFile = legacyDatabase.absoluteFile
                if (!sourceFile.isFile || !sourceFile.canRead()) {
                    throw IOException("Legacy database is missing or unreadable: ${sourceFile.name}")
                }
                val sourceBytes = sourceFile.length()
                val sourceSha256 = sha256(sourceFile)
                val sourceDatabase = SQLiteDatabase.openDatabase(
                    sourceFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                val schemaVersion = try {
                    sourceDatabase.version
                } catch (error: Throwable) {
                    sourceDatabase.close()
                    throw error
                }

                val scratchName = SCRATCH_PREFIX + UUID.randomUUID() + ".db"
                val scratchDatabase = context.getDatabasePath(scratchName)
                val scratchFiles = listOf(
                    scratchDatabase,
                    File(scratchDatabase.path + "-wal"),
                    File(scratchDatabase.path + "-shm"),
                    File(scratchDatabase.path + "-journal"),
                )
                val target = try {
                    Room.databaseBuilder(
                        context.applicationContext ?: context,
                        AppDatabase::class.java,
                        scratchName,
                    ).build()
                } catch (error: Throwable) {
                    sourceDatabase.close()
                    throw error
                }
                var success = false
                try {
                    val targetDatabase = target.openHelper.writableDatabase
                    // The scratch copy is disposable and is read through Room afterwards. Turning
                    // foreign keys off lets malformed legacy rows (for example a project whose
                    // provider was deleted) be preserved instead of aborting the whole export.
                    targetDatabase.execSQL("PRAGMA foreign_keys=OFF")
                    val warnings = mutableListOf<String>()
                    targetDatabase.beginTransaction()
                    try {
                        for (table in COPIED_TABLES) {
                            currentCoroutineContext().ensureActive()
                            if (!sourceTableExists(sourceDatabase, table)) {
                                if (table in REQUIRED_TABLES) {
                                    throw IOException("Legacy database has no $table table")
                                }
                                warnings += "Legacy table $table is missing; exported as empty."
                                continue
                            }
                            copyTable(sourceDatabase, targetDatabase, table)
                        }
                        targetDatabase.setTransactionSuccessful()
                    } finally {
                        targetDatabase.endTransaction()
                    }
                    success = true
                    LegacyExportStore(
                        database = target,
                        source = LegacyExportSourceInfo(
                            fileName = sourceFile.name,
                            schemaVersion = schemaVersion,
                            bytes = sourceBytes,
                            sha256 = sourceSha256,
                        ),
                        warnings = warnings,
                        scratchFiles = scratchFiles,
                    )
                } finally {
                    sourceDatabase.close()
                    if (!success) {
                        runCatching { target.close() }
                        scratchFiles.forEach { file ->
                            runCatching { if (file.exists()) file.delete() }
                        }
                    }
                }
            }

        private fun sourceTableExists(database: SQLiteDatabase, table: String): Boolean =
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table),
            ).use { it.moveToFirst() }

        private suspend fun copyTable(
            source: SQLiteDatabase,
            target: SupportSQLiteDatabase,
            table: String,
        ) {
            val targetColumns = target.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            TargetColumn(
                                name = cursor.getString(nameIndex),
                                type = cursor.getString(typeIndex).orEmpty(),
                                notNull = cursor.getInt(notNullIndex) != 0,
                                hasDefault = !cursor.isNull(defaultIndex),
                            )
                        )
                    }
                }
            }
            if (targetColumns.isEmpty()) return

            source.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                var rowCount = 0
                while (cursor.moveToNext()) {
                    if (rowCount++ % 256 == 0) currentCoroutineContext().ensureActive()
                    val values = ContentValues(targetColumns.size)
                    for (column in targetColumns) {
                        val index = cursor.getColumnIndex(column.name)
                        if (index < 0) {
                            if (column.notNull && !column.hasDefault) {
                                putFallback(values, column)
                            }
                            continue
                        }
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> {
                                if (column.notNull) {
                                    if (!column.hasDefault) putFallback(values, column)
                                } else {
                                    values.putNull(column.name)
                                }
                            }
                            Cursor.FIELD_TYPE_INTEGER -> values.put(column.name, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(column.name, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_STRING -> values.put(column.name, cursor.getString(index))
                            Cursor.FIELD_TYPE_BLOB -> values.put(column.name, cursor.getBlob(index))
                        }
                    }
                    val inserted = target.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                    if (inserted == -1L) {
                        throw IOException("Unable to stage legacy table $table")
                    }
                }
            }
        }

        private fun putFallback(values: ContentValues, column: TargetColumn) {
            val type = column.type.uppercase(Locale.ROOT)
            when {
                type.contains("INT") || type.contains("BOOL") -> values.put(column.name, 0L)
                type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") ->
                    values.put(column.name, 0.0)
                type.contains("BLOB") -> values.put(column.name, ByteArray(0))
                else -> values.put(column.name, "")
            }
        }

        private data class TargetColumn(
            val name: String,
            val type: String,
            val notNull: Boolean,
            val hasDefault: Boolean,
        )

        private suspend fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        }
    }
}
