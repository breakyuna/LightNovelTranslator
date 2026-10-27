package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeArchiveSettingsCheckpointTest {
    @Test fun newCheckpointInstanceRestoresRowsFilesAndPreferencesAfterInterruptedPublish() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = context.getDatabasePath("lnt_native_app.db")
        database.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            for (table in listOf("native_model_settings", "native_model_profiles")) {
                db.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY, value TEXT)")
                db.execSQL("INSERT INTO $table VALUES (1, 'original')")
            }
        }
        val prices = File(context.filesDir, "native-model-prices.json")
        prices.writeText("original")
        val prefs = context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE)
        check(prefs.edit().putString("theme_mode", "DARK").commit())
        NativeArchiveSettingsCheckpoint(context).prepare()
        prices.writeText("replacement")
        File(context.filesDir, "native_prompt_templates.json").writeText("new")
        check(prefs.edit().putString("theme_mode", "LIGHT").commit())
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE native_model_settings SET value='replacement'")
        }
        NativeArchiveSettingsCheckpoint(context).rollback()
        NativeArchiveSettingsCheckpoint(context).rollback() // recovery is idempotent
        assertEquals("original", prices.readText())
        assertFalse(File(context.filesDir, "native_prompt_templates.json").exists())
        assertEquals("DARK", prefs.getString("theme_mode", null))
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM native_model_settings", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("original", cursor.getString(0))
            }
        }
    }
}
