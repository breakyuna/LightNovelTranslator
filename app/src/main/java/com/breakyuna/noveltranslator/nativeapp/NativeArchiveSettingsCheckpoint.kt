package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** Private rollback state, never an archive entry. Encrypted model rows remain encrypted. */
internal class NativeArchiveSettingsCheckpoint(private val context: Context) {
    private val root = File(context.filesDir, "native_archive_settings_rollback")
    private val ready get() = File(root, "ready")
    private val files = listOf(
        "native-model-prices.json", "native-model-prices-history.json", "native-model-prices-history",
        "native_prompt_templates.json", "native_rule_presets", "native_model_network_settings.json",
        "native_model_request_limits.json", "native-laboratory-settings.json",
    )
    private val tables = listOf("native_model_settings", "native_model_profiles")

    fun prepare() {
        check(!ready.exists()) { "A settings rollback is still pending" }
        if (root.exists()) check(root.deleteRecursively())
        check(root.mkdirs())
        files.forEach { name ->
            val source = File(context.filesDir, name)
            if (source.exists()) check(source.copyRecursively(File(root, name)))
            val backup = File(source.path + ".bak")
            if (backup.exists()) check(backup.copyRecursively(File(root, name + ".bak")))
        }
        val preferences = context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE)
        File(root, "preferences.json").writeText(buildJsonObject {
            preferences.all.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> error("Unsupported native preference type")
                }
            }
        }.toString())
        // ATTACH inherits the main connection's open flags. That connection intentionally
        // lacks CREATE, so create only our private snapshot before attaching it.
        SQLiteDatabase.openOrCreateDatabase(File(root, "models.db"), null).close()
        database { db ->
            tables.forEach { table -> db.execSQL("CREATE TABLE archive_rollback.$table AS SELECT * FROM main.$table") }
        }
        val atomic = AtomicFile(ready)
        val output = atomic.startWrite()
        try { output.write(1); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    /** Repeatable after interruption; retain the checkpoint until every original value is back. */
    fun rollback() {
        if (!ready.exists()) return
        database { db ->
            tables.forEach { table ->
                db.execSQL("DELETE FROM main.$table")
                db.execSQL("INSERT INTO main.$table SELECT * FROM archive_rollback.$table")
            }
        }
        files.forEach { name ->
            for (suffix in listOf("", ".bak")) {
                val target = File(context.filesDir, name + suffix)
                if (target.exists()) check(target.deleteRecursively())
                val original = File(root, name + suffix)
                if (original.exists()) check(original.copyRecursively(target))
            }
        }
        val values = Json.parseToJsonElement(File(root, "preferences.json").readText()).jsonObject
        val editor = context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            val primitive = value.jsonPrimitive
            if (primitive.isString) editor.putString(key, primitive.content)
            else editor.putBoolean(key, primitive.boolean)
        }
        check(editor.commit())
        commit()
    }

    fun commit() {
        // Remove the marker first: interrupted cleanup must never roll back a committed restore.
        if (ready.exists()) check(ready.delete())
        if (root.exists()) check(root.deleteRecursively())
    }

    private fun database(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS archive_rollback", arrayOf(File(root, "models.db").path))
            try {
                db.beginTransaction()
                try { block(db); db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE archive_rollback") }
        }
    }
}
