package com.breakyuna.noveltranslator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.breakyuna.noveltranslator.data.model.ChapterSegmentEntity

@Dao
interface ChapterSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<ChapterSegmentEntity>)

    @Query("DELETE FROM chapter_segments WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)

    @Query("SELECT * FROM chapter_segments WHERE chapterId = :chapterId ORDER BY COALESCE(sourceOrdinal, translatedOrdinal), stableKey")
    suspend fun getByChapter(chapterId: Long): List<ChapterSegmentEntity>

    @Transaction
    suspend fun replaceForChapter(chapterId: Long, segments: List<ChapterSegmentEntity>) {
        deleteByChapter(chapterId)
        if (segments.isNotEmpty()) insertAll(segments)
    }
}
