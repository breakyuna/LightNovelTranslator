package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("""
        SELECT b.id, b.title, b.coverPath, b.preferredReadingEditionId,
               EXISTS(SELECT 1 FROM translation_projects_v2 p WHERE p.bookId = b.id) AS hasTranslationProject,
               b.shelfOrder
        FROM books b WHERE b.hiddenFromShelf = 0 ORDER BY b.shelfOrder, b.createdAt DESC
    """)
    fun observeShelf(): Flow<List<ShelfBook>>

    @Query("SELECT * FROM books WHERE hiddenFromShelf = 1 ORDER BY updatedAt DESC")
    fun observeHiddenBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): BookEntity?

    @Insert suspend fun insert(book: BookEntity): Long
    @Update suspend fun update(book: BookEntity)

    @Query("UPDATE books SET hiddenFromShelf = :hidden, updatedAt = :now WHERE id = :bookId")
    suspend fun setHidden(bookId: Long, hidden: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET title = :title, updatedAt = :now WHERE id = :bookId")
    suspend fun rename(bookId: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET title = :title, author = :author, description = :description, originalLanguage = :language, updatedAt = :now WHERE id = :bookId")
    suspend fun updateMetadata(bookId: Long, title: String, author: String, description: String, language: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET coverPath = :coverPath, updatedAt = :now WHERE id = :bookId")
    suspend fun updateCover(bookId: Long, coverPath: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET shelfOrder = :shelfOrder, updatedAt = :now WHERE id = :bookId")
    suspend fun updateShelfOrder(bookId: Long, shelfOrder: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET primaryEditionId = :editionId, preferredReadingEditionId = :editionId, updatedAt = :now WHERE id = :bookId")
    suspend fun setInitialEdition(bookId: Long, editionId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET preferredReadingEditionId = :editionId, updatedAt = :now WHERE id = :bookId")
    suspend fun setPreferredEdition(bookId: Long, editionId: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deletePermanently(bookId: Long)

    @Query("SELECT * FROM editions WHERE bookId = :bookId ORDER BY createdAt")
    fun observeEditions(bookId: Long): Flow<List<EditionEntity>>

    @Query("SELECT * FROM editions ORDER BY createdAt")
    fun observeAllEditions(): Flow<List<EditionEntity>>

    @Query("SELECT * FROM editions ORDER BY createdAt")
    suspend fun getAllEditions(): List<EditionEntity>

    @Query("SELECT * FROM editions WHERE bookId = :bookId ORDER BY createdAt")
    suspend fun getEditions(bookId: Long): List<EditionEntity>

    @Query("SELECT * FROM editions WHERE id = :editionId")
    suspend fun getEdition(editionId: Long): EditionEntity?

    @Query("SELECT * FROM editions WHERE id = :editionId")
    fun observeEdition(editionId: Long): Flow<EditionEntity?>

    @Insert suspend fun insertEdition(edition: EditionEntity): Long
    @Update suspend fun updateEdition(edition: EditionEntity)
    @Query("DELETE FROM editions WHERE id = :editionId")
    suspend fun deleteEdition(editionId: Long)

    @Query("SELECT * FROM logical_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeChapters(bookId: Long): Flow<List<LogicalChapterEntity>>

    @Query("SELECT * FROM logical_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChapters(bookId: Long): List<LogicalChapterEntity>

    @Query("SELECT * FROM logical_chapters WHERE id = :chapterId")
    suspend fun getLogicalChapter(chapterId: Long): LogicalChapterEntity?

    @Query("DELETE FROM logical_chapters WHERE bookId = :bookId")
    suspend fun deleteLogicalChaptersByBook(bookId: Long)

    @Insert suspend fun insertLogicalChapter(chapter: LogicalChapterEntity): Long
    @Insert suspend fun insertLogicalChapters(chapters: List<LogicalChapterEntity>): List<Long>
    @Insert suspend fun insertLogicalSegments(segments: List<LogicalSegmentEntity>): List<Long>
    @Insert suspend fun insertEditionChapter(chapter: EditionChapterEntity): Long
    @Insert suspend fun insertEditionChapters(chapters: List<EditionChapterEntity>): List<Long>
    @Update suspend fun updateEditionChapter(chapter: EditionChapterEntity)
    @Insert suspend fun insertEditionSegments(segments: List<EditionSegmentEntity>): List<Long>
    @Insert suspend fun insertMappings(mappings: List<EditionSegmentMappingEntity>)

    @Query("SELECT * FROM logical_segments WHERE logicalChapterId = :chapterId ORDER BY segmentIndex")
    suspend fun getLogicalSegments(chapterId: Long): List<LogicalSegmentEntity>

    @Query("""
        SELECT ls.* FROM logical_segments ls
        JOIN logical_chapters lc ON lc.id = ls.logicalChapterId
        WHERE lc.bookId = :bookId
        ORDER BY lc.chapterIndex, ls.segmentIndex
    """)
    suspend fun getLogicalSegmentsByBook(bookId: Long): List<LogicalSegmentEntity>

    @Query("SELECT * FROM edition_chapters WHERE editionId = :editionId AND logicalChapterId = :logicalChapterId LIMIT 1")
    suspend fun getEditionChapter(editionId: Long, logicalChapterId: Long): EditionChapterEntity?

    @Query("SELECT * FROM edition_chapters WHERE editionId = :editionId")
    suspend fun getEditionChapters(editionId: Long): List<EditionChapterEntity>

    @Query("DELETE FROM edition_chapters WHERE editionId = :editionId AND logicalChapterId = :logicalChapterId")
    suspend fun deleteEditionChapter(editionId: Long, logicalChapterId: Long)

    @Query("SELECT * FROM edition_segments WHERE editionChapterId = :editionChapterId ORDER BY segmentIndex")
    suspend fun getEditionSegments(editionChapterId: Long): List<EditionSegmentEntity>

    @Query("""
        SELECT es.* FROM edition_segments es
        JOIN edition_chapters ec ON ec.id = es.editionChapterId
        WHERE ec.editionId IN (:editionIds)
        ORDER BY ec.logicalChapterId, es.segmentIndex
    """)
    suspend fun getEditionSegmentsByEditions(editionIds: List<Long>): List<EditionSegmentEntity>

    @Query("SELECT * FROM edition_segment_mappings WHERE logicalSegmentId IN (:logicalSegmentIds) ORDER BY mappingOrder")
    suspend fun getMappings(logicalSegmentIds: List<Long>): List<EditionSegmentMappingEntity>

    @Query("""
        SELECT m.* FROM edition_segment_mappings m
        JOIN edition_segments es ON es.id = m.editionSegmentId
        JOIN edition_chapters ec ON ec.id = es.editionChapterId
        WHERE ec.editionId IN (:editionIds)
        ORDER BY m.mappingOrder
    """)
    suspend fun getMappingsByEditions(editionIds: List<Long>): List<EditionSegmentMappingEntity>

    @Query("SELECT * FROM segment_revisions WHERE editionSegmentId IN (:editionSegmentIds) AND isActive = 1 ORDER BY priority DESC, createdAt DESC")
    suspend fun getActiveRevisions(editionSegmentIds: List<Long>): List<SegmentRevisionEntity>

    @Query("""
        SELECT r.* FROM segment_revisions r
        JOIN edition_segments es ON es.id = r.editionSegmentId
        JOIN edition_chapters ec ON ec.id = es.editionChapterId
        WHERE ec.editionId IN (:editionIds) AND r.isActive = 1
        ORDER BY r.priority DESC, r.createdAt DESC
    """)
    suspend fun getActiveRevisionsByEditions(editionIds: List<Long>): List<SegmentRevisionEntity>

    @Query("""
        SELECT r.id FROM segment_revisions r
        JOIN edition_segments es ON es.id = r.editionSegmentId
        JOIN edition_chapters ec ON ec.id = es.editionChapterId
        JOIN editions e ON e.id = ec.editionId
        WHERE e.bookId = :bookId AND r.isActive = 1 ORDER BY r.id
    """)
    fun observeRevisionIds(bookId: Long): Flow<List<Long>>

    @Insert suspend fun insertRevision(revision: SegmentRevisionEntity): Long

    @Query("UPDATE segment_revisions SET isActive = 0 WHERE id = :revisionId")
    suspend fun deactivateRevision(revisionId: Long)
}

@Dao
interface TranslationProjectV2Dao {
    @Query("SELECT * FROM translation_projects_v2 WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeByBook(bookId: Long): Flow<List<TranslationProjectV2Entity>>

    @Query("SELECT * FROM translation_projects_v2 WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getByBook(bookId: Long): List<TranslationProjectV2Entity>

    @Query("SELECT * FROM translation_projects_v2 WHERE targetEditionId = :editionId ORDER BY createdAt DESC")
    fun observeByTargetEdition(editionId: Long): Flow<List<TranslationProjectV2Entity>>

    @Query("SELECT * FROM translation_projects_v2 WHERE targetEditionId = :editionId ORDER BY createdAt DESC")
    suspend fun getByTargetEdition(editionId: Long): List<TranslationProjectV2Entity>

    @Query("SELECT * FROM translation_projects_v2 WHERE sourceEditionId = :editionId ORDER BY createdAt DESC")
    suspend fun getBySourceEdition(editionId: Long): List<TranslationProjectV2Entity>

    @Query("DELETE FROM translation_projects_v2 WHERE targetEditionId = :editionId")
    suspend fun deleteByTargetEdition(editionId: Long)

    @Query("DELETE FROM translation_projects_v2 WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM translation_projects_v2 WHERE id = :id")
    suspend fun get(id: Long): TranslationProjectV2Entity?

    @Query("SELECT * FROM translation_projects_v2 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TranslationProjectV2Entity>>

    @Insert suspend fun insert(project: TranslationProjectV2Entity): Long
    @Update suspend fun update(project: TranslationProjectV2Entity)

    @Query("UPDATE translation_projects_v2 SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE translation_projects_v2 SET state = 'COMPLETED', updatedAt = :now WHERE id = :id AND state NOT IN ('CANCELLED', 'RUNNING', 'PAUSED')")
    suspend fun completeIfNotActive(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE translation_projects_v2 SET state = 'INTERRUPTED', updatedAt = :now WHERE state IN ('RUNNING', 'PAUSED')")
    suspend fun markInterrupted(now: Long = System.currentTimeMillis())
}

@Dao
interface PromptProfileDao {
    @Query("SELECT * FROM translation_prompt_profiles WHERE translationProjectId = :projectId ORDER BY version DESC LIMIT 1")
    fun observeLatest(projectId: Long): Flow<PromptProfileEntity?>

    @Query("SELECT * FROM translation_prompt_profiles WHERE translationProjectId = :projectId ORDER BY version DESC LIMIT 1")
    suspend fun getLatest(projectId: Long): PromptProfileEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM translation_prompt_profiles WHERE translationProjectId = :projectId")
    suspend fun getMaxVersion(projectId: Long): Int

    @Insert
    suspend fun insert(profile: PromptProfileEntity): Long
}

@Dao
interface LexiconV2Dao {
    @Query("SELECT * FROM lexicon_entries WHERE translationProjectId = :projectId ORDER BY kind, priority DESC, sourceTerm")
    fun observe(projectId: Long): Flow<List<LexiconEntryEntity>>

    @Query("SELECT * FROM lexicon_entries WHERE translationProjectId = :projectId AND enabled = 1 AND reviewStatus = 'CONFIRMED'")
    suspend fun getConfirmed(projectId: Long): List<LexiconEntryEntity>

    @Query("SELECT * FROM lexicon_entries WHERE translationProjectId = :projectId")
    suspend fun getAll(projectId: Long): List<LexiconEntryEntity>

    @Query("SELECT * FROM lexicon_entries WHERE translationProjectId = :projectId AND sourceTerm = :sourceTerm COLLATE NOCASE LIMIT 1")
    suspend fun getBySourceTerm(projectId: Long, sourceTerm: String): LexiconEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: LexiconEntryEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(entries: List<LexiconEntryEntity>): List<Long>
    @Update suspend fun update(entry: LexiconEntryEntity)
    @Query("DELETE FROM lexicon_entries WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM lexicon_entries WHERE translationProjectId = :projectId") suspend fun deleteByProject(projectId: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM story_memory WHERE translationProjectId = :projectId ORDER BY lastUpdatedChapterIndex DESC")
    fun observeStoryMemory(projectId: Long): Flow<List<StoryMemoryEntity>>

    @Query("SELECT * FROM story_memory WHERE translationProjectId = :projectId ORDER BY lastUpdatedChapterIndex DESC")
    suspend fun getStoryMemory(projectId: Long): List<StoryMemoryEntity>

    @Query("SELECT * FROM story_memory WHERE translationProjectId = :projectId AND factKey = :factKey LIMIT 1")
    suspend fun getStoryFact(projectId: Long, factKey: String): StoryMemoryEntity?

    @Query("SELECT * FROM chapter_memory WHERE translationProjectId = :projectId AND chapterIndex < :beforeChapter ORDER BY chapterIndex DESC LIMIT :limit")
    suspend fun getRecentChapterMemory(projectId: Long, beforeChapter: Int, limit: Int): List<ChapterMemoryEntity>

    @Query("SELECT * FROM chapter_memory WHERE translationProjectId = :projectId ORDER BY chapterIndex")
    fun observeChapterMemory(projectId: Long): Flow<List<ChapterMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertChapterMemory(memory: ChapterMemoryEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStoryMemory(memory: StoryMemoryEntity): Long

    @Query("SELECT * FROM context_snapshots WHERE translationProjectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSnapshot(projectId: Long): ContextSnapshotEntity?

    @Insert suspend fun insertSnapshot(snapshot: ContextSnapshotEntity): Long
}

@Dao
interface ReaderProgressDao {
    @Query("SELECT * FROM reader_progress WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<ReaderProgressEntity?>

    @Query("SELECT * FROM reader_progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ReaderProgressEntity?

    @Query("""
        SELECT p.bookId, b.title, b.author, b.coverPath, p.logicalChapterId,
               c.chapterIndex, c.canonicalTitle AS chapterTitle, p.updatedAt
        FROM reader_progress p
        JOIN books b ON b.id = p.bookId
        LEFT JOIN logical_chapters c ON c.id = p.logicalChapterId
        WHERE p.logicalChapterId IS NOT NULL
        ORDER BY p.updatedAt DESC
    """)
    fun observeHistory(): Flow<List<ReadingHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReaderProgressEntity)
}

@Dao
interface ProviderCacheDao {
    @Query("SELECT * FROM provider_cache_records WHERE translationProjectId = :projectId ORDER BY updatedAt DESC")
    suspend fun getByProject(projectId: Long): List<ProviderCacheRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ProviderCacheRecordEntity): Long
}

@Dao
interface PlatformTaskDao {
    @Query("SELECT * FROM platform_translation_runs ORDER BY updatedAt DESC")
    fun observeRuns(): Flow<List<PlatformTranslationRunEntity>>

    @Query("SELECT * FROM platform_translation_runs WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeRunsByBook(bookId: Long): Flow<List<PlatformTranslationRunEntity>>

    @Query("SELECT * FROM platform_translation_runs WHERE translationProjectId = :projectId ORDER BY createdAt DESC")
    fun observeRunsByProject(projectId: Long): Flow<List<PlatformTranslationRunEntity>>

    @Query("SELECT * FROM platform_translation_runs WHERE id = :id")
    suspend fun getRun(id: Long): PlatformTranslationRunEntity?

    @Query("SELECT * FROM platform_translation_batches WHERE runId = :runId ORDER BY batchIndex")
    fun observeBatches(runId: Long): Flow<List<PlatformTranslationBatchEntity>>

    @Query("""
        SELECT id, runId, batchId, operation, attemptCount, promptTokens, completionTokens,
               cachedTokens, estimatedCost, durationMs, finishReason, errorCategory, errorMessage,
               isSuccess, status, timestamp
        FROM platform_request_logs WHERE runId = :runId ORDER BY timestamp DESC
    """)
    fun observeRequestLogs(runId: Long): Flow<List<PlatformRequestLogSummary>>

    @Query("SELECT * FROM platform_request_logs WHERE id = :id")
    suspend fun getRequestLog(id: Long): PlatformRequestLogEntity?

    @Query("SELECT * FROM platform_translation_batches WHERE id = :id")
    suspend fun getBatch(id: Long): PlatformTranslationBatchEntity?

    @Query("SELECT * FROM platform_translation_batches WHERE runId = :runId AND state = 'RUNNING'")
    suspend fun getRunningBatches(runId: Long): List<PlatformTranslationBatchEntity>

    @Insert suspend fun insertRun(run: PlatformTranslationRunEntity): Long
    @Update suspend fun updateRun(run: PlatformTranslationRunEntity)
    @Insert suspend fun insertBatch(batch: PlatformTranslationBatchEntity): Long
    @Update suspend fun updateBatch(batch: PlatformTranslationBatchEntity)
    @Insert suspend fun insertRequestLog(log: PlatformRequestLogEntity): Long

    @Query("UPDATE platform_translation_runs SET state = 'INTERRUPTED', updatedAt = :now WHERE state IN ('RUNNING', 'PAUSED')")
    suspend fun markInterrupted(now: Long = System.currentTimeMillis())
}
