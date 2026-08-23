package com.example.data.db

import androidx.room.*
import com.example.data.model.ChapterEntity
import com.example.data.model.ChapterStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY chapterIndex ASC")
    fun getChaptersByProject(projectId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY chapterIndex ASC")
    suspend fun getChaptersListByProject(projectId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapterById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE projectId = :projectId AND chapterIndex = :index")
    suspend fun getChapterByIndex(projectId: Long, index: Int): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>): List<Long>

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Query("UPDATE chapters SET status = :status, translatedWordCount = :wordCount, promptTokens = :promptTokens, completionTokens = :completionTokens, estimatedCost = :cost, errorMessage = :errorMsg, lastTranslatedAt = :translatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTranslationResult(
        id: Long,
        status: ChapterStatus,
        wordCount: Int,
        promptTokens: Long,
        completionTokens: Long,
        cost: Double,
        errorMsg: String?,
        translatedAt: Long? = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chapters SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ChapterStatus)

    @Query("UPDATE chapters SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    @Query("DELETE FROM chapters WHERE projectId = :projectId")
    suspend fun deleteChaptersByProject(projectId: Long)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun deleteChapterById(id: Long)
}
