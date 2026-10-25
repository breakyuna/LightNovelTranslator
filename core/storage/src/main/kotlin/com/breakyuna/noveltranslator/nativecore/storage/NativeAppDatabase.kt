package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Local identities belong to Room, never to an imported/exported project schema. */
@Entity(tableName = "project_instances", indices = [Index(value = ["creationCommand"], unique = true)])
internal data class ProjectInstanceRecord(
    @PrimaryKey val instanceId: String,
    val projectId: String,
    val generation: Long,
    val creationCommand: String,
    val creationDigest: String,
)

@Dao
internal interface ProjectInstanceDao {
    @Insert fun insert(record: ProjectInstanceRecord)
    @Query("SELECT * FROM project_instances WHERE instanceId = :id")
    fun find(id: String): ProjectInstanceRecord?
    @Query("SELECT * FROM project_instances WHERE creationCommand = :command")
    fun byCreationCommand(command: String): ProjectInstanceRecord?
    @Query("SELECT * FROM project_instances ORDER BY instanceId LIMIT :limit")
    fun list(limit: Int): List<ProjectInstanceRecord>
}

/** Independent app store v17. No fallbackToDestructiveMigration and no legacy DB access. */
@Database(entities = [ProjectInstanceRecord::class, NativeAgentMessageRow::class, NativeAgentApprovalRow::class, NativeAgentRequestRow::class, NativeModelSettingsRow::class, NativeAgentChatRow::class, NativeModelProfileRow::class, NativeRunRow::class, NativeTranslationRequestRow::class, NativeUsageReceiptRow::class, NativeTranslationTaskRow::class, NativeTranslationTargetRow::class, NativeTranslationRequestInputRow::class, NativeTranslationResponseRow::class, NativeTranslationStagedRow::class, NativeAgentInputQueueRow::class, NativeAgentInputDraftRow::class, NativeAgentSteerRunRow::class, NativeAgentSteerAttemptRow::class, NativeTranslationTaskDecisionRow::class, NativeTranslationMemberDecisionRow::class, NativeAgentContextRow::class, NativeAgentBranchRow::class, NativeItemProposalRow::class, NativeItemProposalMemberRow::class, NativePolicyProposalRow::class, NativePdfPageProposalRow::class, NativePdfPageProposalPageRow::class, NativeAssetImportProposalRow::class], version = 18, exportSchema = true)
internal abstract class NativeAppDatabase : RoomDatabase() {
    abstract fun instances(): ProjectInstanceDao
    abstract fun policyProposals(): NativePolicyProposalDao
    abstract fun itemProposals(): NativeItemProposalDao
    abstract fun pdfPageProposals(): NativePdfPageProposalDao
    abstract fun assetImportProposals(): NativeAssetImportProposalDao
    abstract fun agent(): NativeAgentDao
    abstract fun agentInput(): NativeAgentInputDao
    abstract fun models(): NativeModelSettingsDao
    abstract fun profiles(): NativeModelProfileDao
    abstract fun runs(): NativeRunDao
    abstract fun translationRequests(): NativeTranslationRequestDao
    abstract fun usage(): NativeUsageReceiptDao
    abstract fun translationTaskDecisions(): NativeTranslationTaskDecisionDao
    abstract fun translationTasks(): NativeTranslationTaskDao
    abstract fun translationResponses(): NativeTranslationResponseDao

    companion object {
        // Early development v17 databases may predate either of these additive tables.
        // A new version is essential: changing 16->17 alone never repairs an existing v17.
        // Reuse idempotent DDL so current profiles/proposals and all older facts survive.
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_16_17.migrate(db)
                repairModelProfiles(db)
            }
        }

        /**
         * Early v17 builds created `native_model_profiles` without `serviceTier` and without the
         * entity defaults, so Room rejects the table even after the additive 16->17 DDL. Rebuild
         * the table in place and copy every row; unknown-by-then columns fall back to their entity
         * defaults instead of being invented.
         */
        private fun repairModelProfiles(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(native_model_profiles)").use { table ->
                if (!table.moveToFirst()) return
                val name = table.getColumnIndexOrThrow("name")
                buildSet {
                    do { add(table.getString(name)) } while (table.moveToNext())
                }
            }
            fun column(name: String, fallback: String) = if (name in columns) name else fallback
            db.execSQL("DROP TABLE IF EXISTS native_model_profiles_rebuild")
            db.execSQL("CREATE TABLE native_model_profiles_rebuild (profileId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, purpose TEXT NOT NULL, groupName TEXT NOT NULL, orderIndex INTEGER NOT NULL, enabled INTEGER NOT NULL, concurrency INTEGER, requestsPerMinute INTEGER, tools INTEGER, vision INTEGER, reasoning INTEGER, contextWindowTokens INTEGER, maxOutputTokens INTEGER, temperature REAL, topP REAL, generationMaxOutputTokens INTEGER, reasoningEffort TEXT, serviceTier TEXT, extraHeaders TEXT NOT NULL DEFAULT '{}', extraBody TEXT, selectedTranslation INTEGER NOT NULL DEFAULT 0, selectedAgent INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("INSERT INTO native_model_profiles_rebuild " +
                "(profileId,name,kind,purpose,groupName,orderIndex,enabled,concurrency,requestsPerMinute,tools,vision,reasoning," +
                "contextWindowTokens,maxOutputTokens,temperature,topP,generationMaxOutputTokens,reasoningEffort,serviceTier," +
                "extraHeaders,extraBody,selectedTranslation,selectedAgent,revision) SELECT " +
                "profileId,name,kind,purpose,groupName,orderIndex,enabled," +
                "${column("concurrency", "NULL")},${column("requestsPerMinute", "NULL")}," +
                "${column("tools", "NULL")},${column("vision", "NULL")},${column("reasoning", "NULL")}," +
                "${column("contextWindowTokens", "NULL")},${column("maxOutputTokens", "NULL")}," +
                "${column("temperature", "NULL")},${column("topP", "NULL")}," +
                "${column("generationMaxOutputTokens", "NULL")},${column("reasoningEffort", "NULL")}," +
                "${column("serviceTier", "NULL")},${column("extraHeaders", "'{}'")},${column("extraBody", "NULL")}," +
                "${column("selectedTranslation", "0")},${column("selectedAgent", "0")}," +
                "${column("revision", "1")} FROM native_model_profiles")
            db.execSQL("DROP TABLE native_model_profiles")
            db.execSQL("ALTER TABLE native_model_profiles_rebuild RENAME TO native_model_profiles")
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasProfileId = db.query("PRAGMA table_info(native_model_settings)").use { columns ->
                    val nameColumn = columns.getColumnIndexOrThrow("name")
                    var found = false
                    while (columns.moveToNext()) {
                        if (columns.getString(nameColumn) == "profileId") found = true
                    }
                    found
                }
                if (!hasProfileId) {
                    db.execSQL("ALTER TABLE native_model_settings ADD COLUMN profileId TEXT NOT NULL DEFAULT 'native-remote-model'")
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS native_model_profiles (profileId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, purpose TEXT NOT NULL, groupName TEXT NOT NULL, orderIndex INTEGER NOT NULL, enabled INTEGER NOT NULL, concurrency INTEGER, requestsPerMinute INTEGER, tools INTEGER, vision INTEGER, reasoning INTEGER, contextWindowTokens INTEGER, maxOutputTokens INTEGER, temperature REAL, topP REAL, generationMaxOutputTokens INTEGER, reasoningEffort TEXT, serviceTier TEXT, extraHeaders TEXT NOT NULL DEFAULT '{}', extraBody TEXT, selectedTranslation INTEGER NOT NULL DEFAULT 0, selectedAgent INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_asset_import_proposals (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, revision INTEGER NOT NULL, artifactRunId TEXT NOT NULL, artifactId TEXT NOT NULL, artifactPath TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, targetPath TEXT NOT NULL, enabled INTEGER NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, binding TEXT, claimedAt INTEGER, receiptStatus TEXT, receiptRevision INTEGER, receiptAssetId INTEGER, receiptAt INTEGER, settledAt INTEGER, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_asset_import_proposals_commandId ON native_asset_import_proposals(commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_asset_import_proposals_instanceId_generation_chatId ON native_asset_import_proposals(instanceId,generation,chatId)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_pdf_page_proposals (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, revision INTEGER NOT NULL, assetId INTEGER NOT NULL, sha256 TEXT NOT NULL, pageCount INTEGER NOT NULL, policy TEXT NOT NULL, digest TEXT NOT NULL, count INTEGER NOT NULL, state TEXT NOT NULL, selected TEXT, binding TEXT, result TEXT, consumedAt INTEGER, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_pdf_page_proposals_commandId ON native_pdf_page_proposals(commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_pdf_page_proposals_instanceId_generation_chatId ON native_pdf_page_proposals(instanceId,generation,chatId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_pdf_page_proposal_pages (proposalId TEXT NOT NULL, page INTEGER NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(proposalId,page))")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_policy_proposals (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, revision INTEGER NOT NULL, digest TEXT NOT NULL, beforeText TEXT NOT NULL, afterText TEXT NOT NULL, added INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL, reordered INTEGER NOT NULL, promptChanged INTEGER NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, consumedAt INTEGER)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_policy_proposals_commandId ON native_policy_proposals(commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_policy_proposals_instanceId_generation_chatId ON native_policy_proposals(instanceId,generation,chatId)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_item_proposals (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, revision INTEGER NOT NULL, policy TEXT NOT NULL, digest TEXT NOT NULL, count INTEGER NOT NULL, state TEXT NOT NULL, selected TEXT, binding TEXT, consumedAt INTEGER, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_item_proposals_commandId ON native_item_proposals(commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_item_proposals_instanceId_generation_chatId ON native_item_proposals(instanceId,generation,chatId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_item_proposal_members (proposalId TEXT NOT NULL, itemId INTEGER NOT NULL, position INTEGER NOT NULL, rowIndex INTEGER NOT NULL, assetId INTEGER NOT NULL, textChanged INTEGER NOT NULL, namesChanged INTEGER NOT NULL, statusChanged INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(proposalId,itemId))")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_branches (command TEXT NOT NULL PRIMARY KEY, sourceChat TEXT NOT NULL, targetChat TEXT NOT NULL, message TEXT NOT NULL, digest TEXT NOT NULL)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_contexts (instanceId TEXT NOT NULL, generation INTEGER NOT NULL, chatId TEXT NOT NULL, boundaryId TEXT NOT NULL, boundaryTime INTEGER NOT NULL, summary TEXT NOT NULL, fingerprint TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(instanceId,generation,chatId))")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_member_decisions (task TEXT NOT NULL, itemId INTEGER NOT NULL, command TEXT NOT NULL, action TEXT NOT NULL, fingerprint TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(task,itemId))")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_task_decisions (command TEXT NOT NULL PRIMARY KEY, task TEXT NOT NULL, action TEXT NOT NULL, fingerprint TEXT NOT NULL, previousStage TEXT NOT NULL, revision INTEGER NOT NULL, committed INTEGER NOT NULL, remaining INTEGER NOT NULL, requests INTEGER NOT NULL, unresolvedRequests INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_translation_task_decisions_task ON native_translation_task_decisions(task)")
            }
        }

        /** Adds the durable Agent input queue and steer ledger; existing v8 rows are untouched. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_input_queues (instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, paused INTEGER NOT NULL, runId TEXT, runCommandId TEXT, maxDrafts INTEGER NOT NULL, maxDraftChars INTEGER NOT NULL, maxTotalChars INTEGER NOT NULL, revision INTEGER NOT NULL, pendingDraftId TEXT, pendingDraftRevision INTEGER, pendingAttemptId TEXT, pendingMessageId TEXT, lastReceiptRunId TEXT, lastReceiptRunCommandId TEXT, lastReceiptDraftId TEXT, lastReceiptDraftRevision INTEGER, lastReceiptAttemptId TEXT, lastReceiptMessageId TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(instanceId,generation,projectId,chatId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_input_drafts (instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, draftId TEXT NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, revision INTEGER NOT NULL, runId TEXT, runCommandId TEXT, state TEXT NOT NULL, PRIMARY KEY(instanceId,generation,projectId,chatId,draftId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_agent_input_drafts_instanceId_generation_projectId_chatId_position ON native_agent_input_drafts(instanceId,generation,projectId,chatId,position)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_steer_runs (instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, runCommandId TEXT NOT NULL, stage TEXT NOT NULL, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(instanceId,generation,projectId,chatId,runId,runCommandId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_steer_attempts (instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, runCommandId TEXT NOT NULL, steerId TEXT NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, submittedDuring TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, injectedDuring TEXT, PRIMARY KEY(instanceId,generation,projectId,chatId,runId,runCommandId,steerId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_agent_steer_attempts_instanceId_generation_projectId_chatId_runId_runCommandId_position ON native_agent_steer_attempts(instanceId,generation,projectId,chatId,runId,runCommandId,position)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_request_inputs (attemptId TEXT NOT NULL, itemId INTEGER NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(attemptId,itemId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_responses (attemptId TEXT NOT NULL, itemId INTEGER NOT NULL, fingerprint TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(attemptId,itemId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_staged (task TEXT NOT NULL, itemId INTEGER NOT NULL, fingerprint TEXT NOT NULL, commandId TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(task,itemId))")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_tasks (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, configurationId TEXT NOT NULL, configurationRevision INTEGER NOT NULL, targetLanguage TEXT NOT NULL, specification TEXT NOT NULL, revision INTEGER NOT NULL, committed INTEGER NOT NULL, requests INTEGER NOT NULL, stage TEXT NOT NULL, pendingAttempt TEXT, pendingCommands TEXT NOT NULL, pendingItems TEXT NOT NULL, responseRemaining TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_translation_tasks_instanceId_generation_projectId ON native_translation_tasks(instanceId,generation,projectId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_targets (task TEXT NOT NULL, itemId INTEGER NOT NULL, fingerprint TEXT NOT NULL, done INTEGER NOT NULL, PRIMARY KEY(task,itemId))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_usage_receipts (id TEXT NOT NULL PRIMARY KEY, channel TEXT NOT NULL, attemptId TEXT NOT NULL, instanceId TEXT, generation INTEGER, projectId TEXT NOT NULL, chatId TEXT, runId TEXT NOT NULL, commandId TEXT NOT NULL, configurationId TEXT NOT NULL, configurationRevision INTEGER NOT NULL, model TEXT, state TEXT NOT NULL, usage TEXT, stop TEXT, failure TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_usage_receipts_instanceId_generation_projectId_chatId_runId_commandId ON native_usage_receipts(instanceId,generation,projectId,chatId,runId,commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_usage_receipts_configurationId_configurationRevision ON native_usage_receipts(configurationId,configurationRevision)")
            }
        }

        /**
         * Adds the explicit wire protocol and ordered credential envelope. The
         * legacy encryptedKey column is kept so a v4 row remains readable and a
         * single-key save still uses its original AAD shape.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE native_model_settings ADD COLUMN protocol TEXT NOT NULL DEFAULT 'OPENAI_CHAT_COMPLETIONS'")
                db.execSQL("ALTER TABLE native_model_settings ADD COLUMN encryptedKeys TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE native_model_settings ADD COLUMN keyCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE native_model_settings SET keyCount = CASE WHEN encryptedKey = '' THEN 0 ELSE 1 END")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_translation_requests (attemptId TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, configurationId TEXT NOT NULL, configurationRevision INTEGER NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, usage TEXT, failure TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_translation_requests_instanceId_generation_runId_commandId ON native_translation_requests(instanceId,generation,runId,commandId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_runs (executionId TEXT NOT NULL PRIMARY KEY, processId TEXT NOT NULL, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT, runId TEXT NOT NULL, commandId TEXT NOT NULL, owner TEXT NOT NULL, state TEXT NOT NULL, stage TEXT NOT NULL, revision INTEGER, committedItems INTEGER NOT NULL, requests INTEGER NOT NULL, pendingCommands TEXT NOT NULL, requestAttemptId TEXT, requestBranchId TEXT, needsReview INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_runs_instanceId_generation_createdAt ON native_runs(instanceId,generation,createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_runs_state_processId ON native_runs(state,processId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_chats (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, createdAt INTEGER NOT NULL, selected INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_agent_chats_instanceId_generation ON native_agent_chats(instanceId,generation)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_model_settings (revision INTEGER NOT NULL PRIMARY KEY, endpoint TEXT NOT NULL, model TEXT NOT NULL, encryptedKey TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_messages (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, chatId TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, payload TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_agent_messages_instanceId_generation_chatId ON native_agent_messages(instanceId,generation,chatId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_approvals (id TEXT NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, generation INTEGER NOT NULL, projectId TEXT NOT NULL, chatId TEXT NOT NULL, runId TEXT NOT NULL, commandId TEXT NOT NULL, digest TEXT NOT NULL, itemId INTEGER NOT NULL, revision INTEGER NOT NULL, base TEXT NOT NULL, rowIndex INTEGER NOT NULL, beforeStatus TEXT NOT NULL, beforeText TEXT NOT NULL, afterText TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_native_agent_approvals_commandId ON native_agent_approvals(commandId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_native_agent_approvals_instanceId_generation_chatId ON native_agent_approvals(instanceId,generation,chatId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS native_agent_requests (attemptId TEXT NOT NULL PRIMARY KEY, requestKey TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, responseText TEXT, responseId TEXT, usage TEXT)")
            }
        }
        fun open(context: Context): NativeAppDatabase = Room.databaseBuilder(
            context.applicationContext,
            NativeAppDatabase::class.java,
            "lnt_native_app.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18).build()
    }
}
