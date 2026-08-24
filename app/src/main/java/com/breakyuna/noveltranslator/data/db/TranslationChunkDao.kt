package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.TranslationChunkEntity

@Dao
interface TranslationChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<TranslationChunkEntity>)

    @Update
    suspend fun update(chunk: TranslationChunkEntity)

    @Query("SELECT * FROM translation_chunks WHERE runId = :runId AND chapterId = :chapterId AND parentChunkId IS NULL ORDER BY chunkIndex ASC")
    suspend fun getByChapter(runId: Long, chapterId: Long): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE runId = :runId ORDER BY chapterIndex ASC, chunkIndex ASC")
    suspend fun getByRun(runId: Long): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE id = :id")
    suspend fun getById(id: Long): TranslationChunkEntity?

    @Query("SELECT * FROM translation_chunks WHERE runId = :runId AND parentChunkId = :parentChunkId ORDER BY chunkIndex ASC")
    suspend fun getChildren(runId: Long, parentChunkId: Long): List<TranslationChunkEntity>

    @Query("UPDATE translation_chunks SET state = 'PENDING', updatedAt = :updatedAt WHERE state = 'RUNNING'")
    suspend fun resetRunningChunks(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE translation_chunks SET attemptCount = attemptCount + :attempts, promptTokens = promptTokens + :promptTokens, completionTokens = completionTokens + :completionTokens, cost = cost + :cost, durationMs = durationMs + :durationMs, updatedAt = :updatedAt WHERE id = :id")
    suspend fun addUsage(id: Long, attempts: Int, promptTokens: Long, completionTokens: Long, cost: Double, durationMs: Long, updatedAt: Long = System.currentTimeMillis())
}
