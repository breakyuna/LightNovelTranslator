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

/** Independent app store v2. No fallbackToDestructiveMigration and no legacy DB access. */
@Database(entities = [ProjectInstanceRecord::class, NativeAgentMessageRow::class, NativeAgentApprovalRow::class, NativeAgentRequestRow::class, NativeModelSettingsRow::class, NativeAgentChatRow::class], version = 2, exportSchema = true)
internal abstract class NativeAppDatabase : RoomDatabase() {
    abstract fun instances(): ProjectInstanceDao
    abstract fun agent(): NativeAgentDao
    abstract fun models(): NativeModelSettingsDao

    companion object {
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
        ).addMigrations(MIGRATION_1_2).build()
    }
}
