package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.TranslationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationLogDao {
    @Query("SELECT * FROM translation_logs WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getLogsByProject(projectId: Long): Flow<List<TranslationLogEntity>>

    @Query("SELECT * FROM translation_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<TranslationLogEntity>>

    @Query("SELECT * FROM translation_logs WHERE projectId = :projectId ORDER BY timestamp ASC")
    suspend fun getLogsListByProject(projectId: Long): List<TranslationLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: TranslationLogEntity): Long

    @Query("DELETE FROM translation_logs WHERE projectId = :projectId")
    suspend fun deleteLogsByProject(projectId: Long)

    @Query("DELETE FROM translation_logs")
    suspend fun deleteAllLogs()
}
