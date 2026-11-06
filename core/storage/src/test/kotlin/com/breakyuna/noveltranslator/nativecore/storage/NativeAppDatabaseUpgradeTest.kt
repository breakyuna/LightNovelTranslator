package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun completeV18PreservesFactsAndAddsMixedApprovalTables() {
        val initial = NativeAppDatabase.open(context)
        try {
            initial.openHelper.writableDatabase.execSQL(
                "INSERT INTO native_agent_contexts VALUES " +
                    "('instance',1,'chat-v18','boundary',7,'keep v18 summary','digest',3)",
            )
        } finally { initial.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            // A v18 device predates both additive mixed-approval tables.
            db.execSQL("DROP TABLE native_mixed_members")
            db.execSQL("DROP TABLE native_mixed_approvals")
            db.version = 18
        }
        val upgraded = NativeAppDatabase.open(context)
        try {
            val db = upgraded.openHelper.writableDatabase
            assertEquals(20, db.version)
            // The additive DDL is idempotent: rerunning it must not drop or change facts.
            NativeAppDatabase.MIGRATION_18_19.migrate(db)
            db.query("SELECT summary FROM native_agent_contexts WHERE chatId='chat-v18'").use {
                check(it.moveToFirst())
                assertEquals("keep v18 summary", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM native_mixed_approvals").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM native_mixed_members").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='index' " +
                "AND name='index_native_mixed_approvals_commandId'").use {
                check(it.moveToFirst())
                assertEquals("index_native_mixed_approvals_commandId", it.getString(0))
            }
        } finally { upgraded.close() }
    }

    @Test fun v19ReceiptKeepsFactsAndGetsAnExplicitUnknownPriceState() {
        val initial = NativeAppDatabase.open(context)
        try {
            initial.openHelper.writableDatabase.execSQL(
                "INSERT INTO native_usage_receipts " +
                    "(id,channel,attemptId,instanceId,generation,projectId,chatId,runId,commandId," +
                    "configurationId,configurationRevision,model,state,usage,stop,failure) VALUES " +
                    "('legacy','agent','legacy',NULL,NULL,'project',NULL,'run','command','config',1," +
                    "'legacy-model','DISPATCHED',NULL,NULL,NULL)",
            )
        } finally { initial.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Rebuild the v19 receipt shape so this migration test stays portable to old SQLite.
            db.execSQL("CREATE TABLE native_usage_receipts_v19 (id TEXT NOT NULL PRIMARY KEY, " +
                "channel TEXT NOT NULL, attemptId TEXT NOT NULL, instanceId TEXT, generation INTEGER, " +
                "projectId TEXT NOT NULL, chatId TEXT, runId TEXT NOT NULL, commandId TEXT NOT NULL, " +
                "configurationId TEXT NOT NULL, configurationRevision INTEGER NOT NULL, model TEXT, " +
                "state TEXT NOT NULL, usage TEXT, stop TEXT, failure TEXT)")
            db.execSQL("INSERT INTO native_usage_receipts_v19 " +
                "SELECT id,channel,attemptId,instanceId,generation,projectId,chatId,runId,commandId," +
                "configurationId,configurationRevision,model,state,usage,stop,failure " +
                "FROM native_usage_receipts")
            db.execSQL("DROP TABLE native_usage_receipts")
            db.execSQL("ALTER TABLE native_usage_receipts_v19 RENAME TO native_usage_receipts")
            db.execSQL("CREATE INDEX IF NOT EXISTS " +
                "index_native_usage_receipts_instanceId_generation_projectId_chatId_runId_commandId " +
                "ON native_usage_receipts(instanceId,generation,projectId,chatId,runId,commandId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS " +
                "index_native_usage_receipts_configurationId_configurationRevision " +
                "ON native_usage_receipts(configurationId,configurationRevision)")
            db.version = 19
        }
        val upgraded = NativeAppDatabase.open(context)
        try {
            val db = upgraded.openHelper.writableDatabase
            assertEquals(20, db.version)
            assertLegacyReceipt(db)
            // Rerunning the additive migration must not delete the row or invent a version.
            NativeAppDatabase.MIGRATION_19_20.migrate(db)
            assertLegacyReceipt(db)
        } finally { upgraded.close() }
    }

    private fun assertLegacyReceipt(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT projectId,state,priceVersion,priceCurrency,priceFingerprint," +
            "pricedModelKey,costState FROM native_usage_receipts WHERE id='legacy'").use { row ->
            check(row.moveToFirst())
            assertEquals("project", row.getString(0))
            assertEquals("DISPATCHED", row.getString(1))
            assertTrue(row.isNull(2))
            assertTrue(row.isNull(3))
            assertTrue(row.isNull(4))
            assertTrue(row.isNull(5))
            assertEquals("UNKNOWN", row.getString(6))
        }
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
            assertEquals(20, db.version)
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
            db.query("SELECT COUNT(*) FROM native_mixed_approvals").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM native_mixed_members").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        } finally { upgraded.close() }
    }
}
