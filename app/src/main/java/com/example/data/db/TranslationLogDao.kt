package com.example.data.db

import androidx.room.*
import com.example.data.model.TranslationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationLogDao {
    @Query("SELECT * FROM translation_logs WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getLogsByProject(projectId: Long): Flow<List<TranslationLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: TranslationLogEntity): Long

    @Query("DELETE FROM translation_logs WHERE projectId = :projectId")
    suspend fun deleteLogsByProject(projectId: Long)
}
