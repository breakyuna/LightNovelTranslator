package com.breakyuna.noveltranslator.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
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
