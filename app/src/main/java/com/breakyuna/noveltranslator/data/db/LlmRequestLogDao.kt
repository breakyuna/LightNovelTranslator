package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.LlmRequestLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LlmRequestLogDao {
    @Insert
    suspend fun insert(log: LlmRequestLogEntity): Long

    @Insert
    suspend fun insertAll(logs: List<LlmRequestLogEntity>): List<Long>

    @Query("SELECT * FROM llm_request_logs WHERE projectId = :projectId ORDER BY timestamp DESC")
    suspend fun getByProject(projectId: Long): List<LlmRequestLogEntity>

    @Query("SELECT * FROM llm_request_logs WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getFlowByProject(projectId: Long): Flow<List<LlmRequestLogEntity>>

    @Query("SELECT * FROM llm_request_logs ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<LlmRequestLogEntity>>

    @Query("SELECT * FROM llm_request_logs WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun getByRun(runId: Long): List<LlmRequestLogEntity>

    @Query("DELETE FROM llm_request_logs")
    suspend fun deleteAll()
}
