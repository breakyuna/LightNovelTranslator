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

/** Independent app store v1. No fallbackToDestructiveMigration and no legacy DB access. */
@Database(entities = [ProjectInstanceRecord::class], version = 1, exportSchema = true)
internal abstract class NativeAppDatabase : RoomDatabase() {
    abstract fun instances(): ProjectInstanceDao

    companion object {
        fun open(context: Context): NativeAppDatabase = Room.databaseBuilder(
            context.applicationContext,
            NativeAppDatabase::class.java,
            "lnt_native_app.db",
        ).build()
    }
}
