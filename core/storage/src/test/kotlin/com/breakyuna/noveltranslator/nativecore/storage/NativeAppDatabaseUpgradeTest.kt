package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Opens through Room so migration validation, not just the additive SQL, is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAppDatabaseUpgradeTest {
    private lateinit var context: Context
    private val name = "lnt_native_app.db"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun earlyV17WithoutEitherAdditiveTableKeepsExistingContext() {
        upgradeV17(dropProfiles = true, dropImports = true)
    }

    @Test fun v17WithProfilesButWithoutImportsPreservesProfileRows() {
        upgradeV17(dropProfiles = false, dropImports = true)
    }

    @Test fun v17WithoutProfilesStillAddsTheMissingCatalog() {
        upgradeV17(dropProfiles = true, dropImports = false)
    }

    @Test fun completeV17PreservesBothTables() {
        upgradeV17(dropProfiles = false, dropImports = false)
    }

    private fun upgradeV17(dropProfiles: Boolean, dropImports: Boolean) {
        val initial = NativeAppDatabase.open(context)
        try {
            val db = initial.openHelper.writableDatabase
            db.execSQL("INSERT INTO native_agent_contexts VALUES ('instance',1,'chat','boundary',7,'keep summary','digest',3)")
            db.execSQL("""INSERT INTO native_model_profiles
                (profileId,name,kind,purpose,groupName,orderIndex,enabled,extraHeaders,
                 selectedTranslation,selectedAgent,revision)
                VALUES ('profile','Keep profile','CUSTOM','BOTH','',0,1,'{}',1,1,1)""")
        } finally { initial.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (dropProfiles) db.execSQL("DROP TABLE native_model_profiles")
            if (dropImports) db.execSQL("DROP TABLE native_asset_import_proposals")
            // Keep the old identity hash: real development databases still have one. Room's
            // version upgrade must run before checking the current generated identity hash.
            db.version = 17
        }
        val upgraded = NativeAppDatabase.open(context)
        try {
            val db = upgraded.openHelper.writableDatabase
            assertEquals(18, db.version)
            db.query("SELECT summary FROM native_agent_contexts WHERE chatId='chat'").use {
                check(it.moveToFirst())
                assertEquals("keep summary", it.getString(0))
            }
            db.query("SELECT name FROM native_model_profiles").use {
                assertEquals(if (dropProfiles) 0 else 1, it.count)
                if (!dropProfiles) {
                    check(it.moveToFirst())
                    assertEquals("Keep profile", it.getString(0))
                }
            }
            db.query("SELECT COUNT(*) FROM native_asset_import_proposals").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        } finally { upgraded.close() }
    }
}
