package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.TranslationRunEntity

@Dao
interface TranslationRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: TranslationRunEntity): Long

    @Update
    suspend fun update(run: TranslationRunEntity)

    @Query("SELECT * FROM translation_runs WHERE id = :id")
    suspend fun getById(id: Long): TranslationRunEntity?

    @Query("SELECT * FROM translation_runs WHERE projectId = :projectId ORDER BY updatedAt DESC")
    suspend fun getByProject(projectId: Long): List<TranslationRunEntity>

    @Query("SELECT * FROM translation_runs WHERE projectId = :projectId AND providerId = :providerId AND state IN ('QUEUED','RUNNING','RETRY_WAIT','PAUSE_REQUESTED','PAUSED','INTERRUPTED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findResumable(projectId: Long, providerId: Long): TranslationRunEntity?

    @Query("SELECT * FROM translation_runs WHERE projectId = :projectId AND state IN ('QUEUED','RUNNING','RETRY_WAIT','PAUSE_REQUESTED','PAUSED','INTERRUPTED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findLatestResumable(projectId: Long): TranslationRunEntity?

    @Query("UPDATE translation_runs SET state = :state, updatedAt = :updatedAt, lastErrorCategory = :category, lastErrorMessage = :message, nextRetryAt = :nextRetryAt WHERE id = :id")
    suspend fun updateState(id: Long, state: String, category: String?, message: String?, nextRetryAt: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE translation_runs SET state = 'INTERRUPTED', updatedAt = :updatedAt, lastErrorMessage = 'Application restarted before the run finished' WHERE state IN ('RUNNING','RETRY_WAIT','PAUSE_REQUESTED')")
    suspend fun markInFlightInterrupted(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE translation_runs SET totalPromptTokens = totalPromptTokens + :promptTokens, totalCompletionTokens = totalCompletionTokens + :completionTokens, totalCost = totalCost + :cost, updatedAt = :updatedAt WHERE id = :id")
    suspend fun addUsage(id: Long, promptTokens: Long, completionTokens: Long, cost: Double, updatedAt: Long = System.currentTimeMillis())
}
