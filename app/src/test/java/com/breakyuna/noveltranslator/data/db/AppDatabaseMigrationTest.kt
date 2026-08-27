package com.breakyuna.noveltranslator.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration7To8AddsOptionalDebugPayloadColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("debug_migration_test_${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE platform_request_logs (id INTEGER PRIMARY KEY NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_7_8.migrate(db)
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(platform_request_logs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue("systemPrompt" in columns)
            assertTrue("userPrompt" in columns)
            assertTrue("responseText" in columns)
            assertTrue("attemptTrace" in columns)
        } finally {
            helper.close()
        }
    }

    @Test
    fun migration8To9AddsCandidateAggregatesWithoutDroppingLexiconEntries() {
        val name = "candidate_room_migration_${System.nanoTime()}.db"
        migrationHelper.createDatabase(name, 8).use { db ->
            db.execSQL("INSERT INTO projects(id, title, author, sourceFileName, fileType, projectDirPath, sourceLanguage, targetLanguage, translationStyle, totalChapters, translatedChapters, totalOriginalWords, totalPromptTokens, totalCompletionTokens, totalCost, costCurrency, createdAt, updatedAt) VALUES (42, 'Legacy', '', 'book.txt', 'TXT', '/project', 'en', 'zh', '', 0, 0, 0, 0, 0, 0, '', 1, 1)")
            db.execSQL("INSERT INTO glossary(projectId, originalTerm, translatedTerm, category, notes, isAutoExtracted, createdAt) VALUES (42, 'Irene', '艾琳', 'CHARACTER', '', 1, 1)")
            db.execSQL("INSERT INTO books(id, title, author, description, originalLanguage, hiddenFromShelf, shelfOrder, createdAt, updatedAt) VALUES (1, 'Book', '', '', 'en', 0, 0, 1, 1)")
            db.execSQL("INSERT INTO editions(id, bookId, name, type, language, isComplete, createdAt, updatedAt) VALUES (2, 1, 'Source', 'IMPORTED', 'en', 1, 1, 1)")
            db.execSQL("INSERT INTO editions(id, bookId, name, type, language, sourceEditionId, isComplete, createdAt, updatedAt) VALUES (3, 1, 'Target', 'AI_TRANSLATION', 'zh', 2, 0, 1, 1)")
            db.execSQL("INSERT INTO translation_projects_v2(id, bookId, sourceEditionId, targetEditionId, sourceLanguage, targetLanguage, modelName, styleGuide, promptProtocolVersion, translationMode, maxBatchChapters, seamlessAheadChapters, highQualityReview, state, createdAt, updatedAt) VALUES (7, 1, 2, 3, 'en', 'zh', 'model', '', 1, 'FULL_BOOK', 1, 0, 0, 'IDLE', 1, 1)")
            db.execSQL("INSERT INTO lexicon_entries(translationProjectId, sourceTerm, targetTerm, kind, category, aliases, notes, caseSensitive, exactMatch, priority, enabled, source, reviewStatus, createdAt, updatedAt) VALUES (7, 'Alice', '爱丽丝', 'PROPER_NOUN', 'CHARACTER', '', '', 0, 1, 0, 1, 'AI', 'CANDIDATE', 100, 200)")
        }
        migrationHelper.runMigrationsAndValidate(name, 9, true, AppDatabase.MIGRATION_8_9).use { db ->
            val lexiconCount = db.query("SELECT COUNT(*) FROM lexicon_entries").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val aggregate = db.query("SELECT observationCount, sourceTerm, winnerTargetTerm, state FROM lexicon_candidate_aggregates").use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
            }
            assertEquals(1, lexiconCount)
            assertEquals(1, aggregate[0])
            assertEquals("Alice", aggregate[1])
            assertEquals("爱丽丝", aggregate[2])
            assertEquals("ACTIVE", aggregate[3])

            val legacyReview = db.query("SELECT source, reviewStatus FROM glossary WHERE originalTerm = 'Irene'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0) to cursor.getString(1)
            }
            assertEquals("AI", legacyReview.first)
            assertEquals("CONFIRMED", legacyReview.second)

            val glossaryColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(glossary)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) glossaryColumns += cursor.getString(nameIndex)
            }
            assertTrue("source" in glossaryColumns)
            assertTrue("reviewStatus" in glossaryColumns)
        }
    }

    @Test
    fun migration6To9PreservesLegacyProjectsAndValidatesAllNewTables() {
        val name = "full_room_migration_${System.nanoTime()}.db"
        migrationHelper.createDatabase(name, 6).use { db ->
            db.execSQL("INSERT INTO projects(id, title, author, sourceFileName, fileType, projectDirPath, sourceLanguage, targetLanguage, translationStyle, totalChapters, translatedChapters, totalOriginalWords, totalPromptTokens, totalCompletionTokens, totalCost, costCurrency, createdAt, updatedAt) VALUES (42, 'Legacy', '', 'book.txt', 'TXT', '/project', 'en', 'zh', '', 0, 0, 0, 0, 0, 0, '', 1, 1)")
        }
        migrationHelper.runMigrationsAndValidate(
            name,
            9,
            true,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9
        ).use { db ->
            val count = db.query("SELECT COUNT(*) FROM projects WHERE id = 42").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun migration3To4CreatesRecoveryTablesWithoutDroppingOldProjectData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration_test_${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL("CREATE TABLE chapters (id INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL("INSERT INTO projects(id) VALUES (42)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_3_4.migrate(db)
            AppDatabase.MIGRATION_4_5.migrate(db)
            AppDatabase.MIGRATION_5_6.migrate(db)
            val projectCount = db.query("SELECT COUNT(*) FROM projects").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(1, projectCount)
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue("translation_runs" in tables)
            assertTrue("translation_chunks" in tables)
            assertTrue("llm_request_logs" in tables)
            assertTrue("chapter_segments" in tables)
            val chunkColumns = mutableMapOf<String, Int>()
            db.query("PRAGMA table_info(translation_chunks)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) chunkColumns[cursor.getString(nameIndex)] = cursor.getInt(notNullIndex)
            }
            assertEquals(1, chunkColumns["parentChunkKey"])
            val requestColumns = mutableMapOf<String, Int>()
            db.query("PRAGMA table_info(llm_request_logs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) requestColumns[cursor.getString(nameIndex)] = cursor.getInt(notNullIndex)
            }
            assertEquals(0, requestColumns["projectId"])
        } finally {
            helper.close()
        }
    }
}
