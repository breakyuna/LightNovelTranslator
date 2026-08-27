package com.breakyuna.noveltranslator.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.breakyuna.noveltranslator.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class Converters {
    @TypeConverter
    fun fromChapterStatus(status: ChapterStatus): String = status.name

    @TypeConverter
    fun toChapterStatus(value: String): ChapterStatus = try {
        ChapterStatus.valueOf(value)
    } catch (e: Exception) {
        ChapterStatus.PENDING
    }

    @TypeConverter
    fun fromTermCategory(category: TermCategory): String = category.name

    @TypeConverter
    fun toTermCategory(value: String): TermCategory = try {
        TermCategory.valueOf(value)
    } catch (e: Exception) {
        TermCategory.CUSTOM
    }

    @TypeConverter
    fun fromProviderType(type: ProviderType): String = type.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = try {
        ProviderType.valueOf(value)
    } catch (e: Exception) {
        ProviderType.OPENAI_COMPATIBLE
    }
}

private data class LegacyCandidateSeed(
    val translationProjectId: Long,
    val normalizedSourceTerm: String,
    var sourceTerm: String,
    val targetVotes: MutableMap<String, Int> = mutableMapOf(),
    val categoryVotes: MutableMap<String, Int> = mutableMapOf(),
    val notesVotes: MutableMap<String, Int> = mutableMapOf(),
    var observationCount: Int = 0,
    var firstSeenAt: Long = 0L,
    var lastSeenAt: Long = 0L,
    var caseSensitive: Boolean = false
)

@Database(
    entities = [
        ProjectEntity::class,
        ChapterEntity::class,
        GlossaryEntity::class,
        ApiProviderEntity::class,
        TranslationLogEntity::class,
        TranslationRunEntity::class,
        TranslationChunkEntity::class,
        LlmRequestLogEntity::class,
        ChapterSegmentEntity::class,
        BookEntity::class,
        EditionEntity::class,
        LogicalChapterEntity::class,
        LogicalSegmentEntity::class,
        EditionChapterEntity::class,
        EditionSegmentEntity::class,
        EditionSegmentMappingEntity::class,
        SegmentRevisionEntity::class,
        TranslationProjectV2Entity::class,
        LexiconEntryEntity::class,
        StoryMemoryEntity::class,
        ChapterMemoryEntity::class,
        ContextSnapshotEntity::class,
        ReaderProgressEntity::class,
        ProviderCacheRecordEntity::class,
        PlatformTranslationRunEntity::class,
        PlatformTranslationBatchEntity::class,
        PlatformRequestLogEntity::class,
        LexiconCandidateAggregateEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun chapterDao(): ChapterDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun apiProviderDao(): ApiProviderDao
    abstract fun translationLogDao(): TranslationLogDao
    abstract fun translationRunDao(): TranslationRunDao
    abstract fun translationChunkDao(): TranslationChunkDao
    abstract fun llmRequestLogDao(): LlmRequestLogDao
    abstract fun chapterSegmentDao(): ChapterSegmentDao
    abstract fun bookDao(): BookDao
    abstract fun translationProjectV2Dao(): TranslationProjectV2Dao
    abstract fun lexiconV2Dao(): LexiconV2Dao
    abstract fun lexiconCandidateAggregateDao(): LexiconCandidateAggregateDao
    abstract fun memoryDao(): MemoryDao
    abstract fun readerProgressDao(): ReaderProgressDao
    abstract fun providerCacheDao(): ProviderCacheDao
    abstract fun platformTaskDao(): PlatformTaskDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN costCurrency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE translation_logs ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE projects SET costCurrency = 'UNKNOWN' WHERE totalCost > 0 AND (costCurrency IS NULL OR costCurrency = '')")
                db.execSQL("UPDATE translation_logs SET currency = 'UNKNOWN' WHERE currency IS NULL OR currency = ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        projectId INTEGER NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        state TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastErrorCategory TEXT,
                        lastErrorMessage TEXT,
                        nextRetryAt INTEGER,
                        totalPromptTokens INTEGER NOT NULL,
                        totalCompletionTokens INTEGER NOT NULL,
                        totalCost REAL NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_runs_projectId ON translation_runs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_runs_state ON translation_runs(state)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        chapterId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        sourceHash TEXT NOT NULL,
                        parentChunkId INTEGER,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        translatedTempFileName TEXT,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        cost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        lastErrorCategory TEXT,
                        lastErrorMessage TEXT,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(runId) REFERENCES translation_runs(id) ON DELETE CASCADE,
                        FOREIGN KEY(chapterId) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkId ON translation_chunks(runId, chapterId, chunkIndex, parentChunkId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_chunks_chapterId ON translation_chunks(chapterId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS llm_request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER,
                        projectId INTEGER NOT NULL,
                        chapterId INTEGER,
                        chapterIndex INTEGER,
                        chunkIndex INTEGER,
                        attemptNumber INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        totalTokens INTEGER NOT NULL,
                        usageSource TEXT NOT NULL,
                        estimatedCost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        httpStatus INTEGER,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        finishReason TEXT,
                        requestId TEXT,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_projectId ON llm_request_logs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_runId ON llm_request_logs(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_timestamp ON llm_request_logs(timestamp)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_segments (
                        stableKey TEXT NOT NULL,
                        chapterId INTEGER NOT NULL,
                        sourceSegmentId TEXT NOT NULL,
                        translatedSegmentId TEXT NOT NULL,
                        sourceOrdinal INTEGER,
                        translatedOrdinal INTEGER,
                        sourceText TEXT NOT NULL,
                        translatedText TEXT NOT NULL,
                        segmentType TEXT NOT NULL,
                        relation TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(stableKey),
                        FOREIGN KEY(chapterId) REFERENCES chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_segments_chapterId ON chapter_segments(chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_segments_chapterId_sourceSegmentId ON chapter_segments(chapterId, sourceSegmentId)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_chunks ADD COLUMN parentChunkKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE translation_chunks SET parentChunkKey = COALESCE(parentChunkId, 0)")
                db.execSQL("DROP INDEX IF EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkId")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_chunks_runId_chapterId_chunkIndex_parentChunkKey ON translation_chunks(runId, chapterId, chunkIndex, parentChunkKey)")

                // Provider connection tests are global operations, so request logs must allow no project.
                db.execSQL("ALTER TABLE llm_request_logs RENAME TO llm_request_logs_v5")
                db.execSQL("""
                    CREATE TABLE llm_request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER,
                        projectId INTEGER,
                        chapterId INTEGER,
                        chapterIndex INTEGER,
                        chunkIndex INTEGER,
                        attemptNumber INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        currency TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        totalTokens INTEGER NOT NULL,
                        usageSource TEXT NOT NULL,
                        estimatedCost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        httpStatus INTEGER,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        finishReason TEXT,
                        requestId TEXT,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO llm_request_logs (
                        id, runId, projectId, chapterId, chapterIndex, chunkIndex, attemptNumber,
                        operation, providerId, providerName, modelName, inputPricePerMillion,
                        outputPricePerMillion, currency, promptTokens, completionTokens, totalTokens,
                        usageSource, estimatedCost, durationMs, httpStatus, errorCategory, errorMessage,
                        finishReason, requestId, isSuccess, timestamp
                    ) SELECT
                        id, runId, projectId, chapterId, chapterIndex, chunkIndex, attemptNumber,
                        operation, providerId, providerName, modelName, inputPricePerMillion,
                        outputPricePerMillion, currency, promptTokens, completionTokens, totalTokens,
                        usageSource, estimatedCost, durationMs, httpStatus, errorCategory, errorMessage,
                        finishReason, requestId, isSuccess, timestamp
                    FROM llm_request_logs_v5
                """.trimIndent())
                db.execSQL("DROP TABLE llm_request_logs_v5")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_projectId ON llm_request_logs(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_runId ON llm_request_logs(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_llm_request_logs_timestamp ON llm_request_logs(timestamp)")
            }
        }

        /**
         * The reader-platform tables were introduced at v7 without a checked-in migration.
         * Create them additively so a v6 installation can reach the current schema without the
         * destructive fallback that used to hide this gap.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS books (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        coverPath TEXT,
                        description TEXT NOT NULL,
                        originalLanguage TEXT NOT NULL,
                        primaryEditionId INTEGER,
                        preferredReadingEditionId INTEGER,
                        hiddenFromShelf INTEGER NOT NULL,
                        shelfOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_shelfOrder ON books(shelfOrder)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS editions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        language TEXT NOT NULL,
                        sourceEditionId INTEGER,
                        isComplete INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_bookId ON editions(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_bookId_type ON editions(bookId, type)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS logical_chapters (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        canonicalTitle TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_logical_chapters_bookId_chapterIndex ON logical_chapters(bookId, chapterIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS logical_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        logicalChapterId INTEGER NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        segmentType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(logicalChapterId) REFERENCES logical_chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_logical_segments_logicalChapterId_segmentIndex ON logical_segments(logicalChapterId, segmentIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS edition_chapters (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        editionId INTEGER NOT NULL,
                        logicalChapterId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        contentFileName TEXT NOT NULL,
                        wordCount INTEGER NOT NULL,
                        isAvailable INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(editionId) REFERENCES editions(id) ON DELETE CASCADE,
                        FOREIGN KEY(logicalChapterId) REFERENCES logical_chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_edition_chapters_editionId_logicalChapterId ON edition_chapters(editionId, logicalChapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_chapters_logicalChapterId ON edition_chapters(logicalChapterId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS edition_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        editionChapterId INTEGER NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        baseText TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(editionChapterId) REFERENCES edition_chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_edition_segments_editionChapterId_segmentIndex ON edition_segments(editionChapterId, segmentIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS edition_segment_mappings (
                        logicalSegmentId INTEGER NOT NULL,
                        editionSegmentId INTEGER NOT NULL,
                        mappingOrder INTEGER NOT NULL,
                        PRIMARY KEY(logicalSegmentId, editionSegmentId),
                        FOREIGN KEY(logicalSegmentId) REFERENCES logical_segments(id) ON DELETE CASCADE,
                        FOREIGN KEY(editionSegmentId) REFERENCES edition_segments(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_segment_mappings_editionSegmentId ON edition_segment_mappings(editionSegmentId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS segment_revisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        editionSegmentId INTEGER NOT NULL,
                        revisionType TEXT NOT NULL,
                        text TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        sourceRevisionId INTEGER,
                        isActive INTEGER NOT NULL,
                        note TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(editionSegmentId) REFERENCES edition_segments(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segment_revisions_editionSegmentId ON segment_revisions(editionSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segment_revisions_editionSegmentId_priority_createdAt ON segment_revisions(editionSegmentId, priority, createdAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_projects_v2 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        sourceEditionId INTEGER NOT NULL,
                        targetEditionId INTEGER NOT NULL,
                        sourceLanguage TEXT NOT NULL,
                        targetLanguage TEXT NOT NULL,
                        providerId INTEGER,
                        modelName TEXT NOT NULL,
                        styleGuide TEXT NOT NULL,
                        promptProtocolVersion INTEGER NOT NULL,
                        translationMode TEXT NOT NULL,
                        maxBatchChapters INTEGER NOT NULL,
                        seamlessAheadChapters INTEGER NOT NULL,
                        rangeStart INTEGER,
                        rangeEnd INTEGER,
                        highQualityReview INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE,
                        FOREIGN KEY(sourceEditionId) REFERENCES editions(id) ON DELETE CASCADE,
                        FOREIGN KEY(targetEditionId) REFERENCES editions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_projects_v2_bookId ON translation_projects_v2(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_projects_v2_sourceEditionId ON translation_projects_v2(sourceEditionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_projects_v2_targetEditionId ON translation_projects_v2(targetEditionId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lexicon_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        sourceTerm TEXT NOT NULL,
                        targetTerm TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        category TEXT NOT NULL,
                        aliases TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        caseSensitive INTEGER NOT NULL,
                        exactMatch INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        reviewStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lexicon_entries_translationProjectId ON lexicon_entries(translationProjectId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lexicon_entries_translationProjectId_sourceTerm ON lexicon_entries(translationProjectId, sourceTerm)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS story_memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        factKey TEXT NOT NULL,
                        factValue TEXT NOT NULL,
                        entities TEXT NOT NULL,
                        sourceChapterIndex INTEGER NOT NULL,
                        lastUpdatedChapterIndex INTEGER NOT NULL,
                        conflictNote TEXT,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_story_memory_translationProjectId ON story_memory(translationProjectId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_story_memory_translationProjectId_factKey ON story_memory(translationProjectId, factKey)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chapter_memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        logicalChapterId INTEGER NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        entities TEXT NOT NULL,
                        stateChanges TEXT NOT NULL,
                        newFacts TEXT NOT NULL,
                        unresolvedThreads TEXT NOT NULL,
                        repairState TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE,
                        FOREIGN KEY(logicalChapterId) REFERENCES logical_chapters(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chapter_memory_translationProjectId_logicalChapterId ON chapter_memory(translationProjectId, logicalChapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_memory_logicalChapterId ON chapter_memory(logicalChapterId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS context_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        protocolVersion INTEGER NOT NULL,
                        styleGuideVersion INTEGER NOT NULL,
                        coreLexiconVersion INTEGER NOT NULL,
                        storyMemoryVersion INTEGER NOT NULL,
                        stablePrefix TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_context_snapshots_translationProjectId ON context_snapshots(translationProjectId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reader_progress (
                        bookId INTEGER NOT NULL PRIMARY KEY,
                        preferredEditionId INTEGER,
                        logicalChapterId INTEGER,
                        logicalSegmentId INTEGER,
                        segmentOffset INTEGER NOT NULL,
                        displayMode TEXT NOT NULL,
                        pagingMode TEXT NOT NULL,
                        readerLayoutMode TEXT NOT NULL,
                        pageAnimation TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reader_progress_preferredEditionId ON reader_progress(preferredEditionId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_cache_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        remoteCacheId TEXT,
                        cachedTokenCount INTEGER NOT NULL,
                        hitTokens INTEGER NOT NULL,
                        missTokens INTEGER NOT NULL,
                        estimatedSavedCost REAL NOT NULL,
                        expiresAt INTEGER,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_cache_records_translationProjectId ON provider_cache_records(translationProjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_cache_records_fingerprint ON provider_cache_records(fingerprint)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS platform_translation_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        bookId INTEGER NOT NULL,
                        providerId INTEGER NOT NULL,
                        providerName TEXT NOT NULL,
                        modelName TEXT NOT NULL,
                        state TEXT NOT NULL,
                        completedChapters INTEGER NOT NULL,
                        failedChapters INTEGER NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        cachedTokens INTEGER NOT NULL,
                        totalCost REAL NOT NULL,
                        currency TEXT NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_translation_runs_translationProjectId ON platform_translation_runs(translationProjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_translation_runs_bookId ON platform_translation_runs(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_translation_runs_state ON platform_translation_runs(state)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS platform_translation_batches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        batchIndex INTEGER NOT NULL,
                        firstChapterIndex INTEGER NOT NULL,
                        lastChapterIndex INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        contextSnapshotId INTEGER,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        cost REAL NOT NULL,
                        errorMessage TEXT,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(runId) REFERENCES platform_translation_runs(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_translation_batches_runId ON platform_translation_batches(runId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_platform_translation_batches_runId_batchIndex ON platform_translation_batches(runId, batchIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS platform_request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        batchId INTEGER,
                        operation TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL,
                        cachedTokens INTEGER NOT NULL,
                        estimatedCost REAL NOT NULL,
                        durationMs INTEGER NOT NULL,
                        finishReason TEXT,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(runId) REFERENCES platform_translation_runs(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_request_logs_runId ON platform_request_logs(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_platform_request_logs_timestamp ON platform_request_logs(timestamp)")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE platform_request_logs ADD COLUMN systemPrompt TEXT")
                db.execSQL("ALTER TABLE platform_request_logs ADD COLUMN userPrompt TEXT")
                db.execSQL("ALTER TABLE platform_request_logs ADD COLUMN responseText TEXT")
                db.execSQL("ALTER TABLE platform_request_logs ADD COLUMN attemptTrace TEXT")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Old legacy rows only persisted provenance. In particular saveExtractedTerms()
                // wrote isAutoExtracted=true after an explicit user save, so migration must not
                // reinterpret those already-saved terms as unreviewed candidates.
                db.execSQL("ALTER TABLE glossary ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE glossary ADD COLUMN reviewStatus TEXT NOT NULL DEFAULT 'CONFIRMED'")
                db.execSQL("UPDATE glossary SET source = 'AI', reviewStatus = 'CONFIRMED' WHERE isAutoExtracted = 1")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lexicon_candidate_aggregates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        translationProjectId INTEGER NOT NULL,
                        sourceTerm TEXT NOT NULL,
                        normalizedSourceTerm TEXT NOT NULL,
                        targetVotesJson TEXT NOT NULL,
                        categoryVotesJson TEXT NOT NULL,
                        notesVotesJson TEXT NOT NULL,
                        winnerTargetTerm TEXT NOT NULL,
                        winnerCategory TEXT NOT NULL,
                        winnerNotes TEXT NOT NULL,
                        observationCount INTEGER NOT NULL,
                        firstSeenChapterIndex INTEGER NOT NULL,
                        lastSeenChapterIndex INTEGER NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        sourceHitCount INTEGER NOT NULL,
                        independentHitCount INTEGER NOT NULL,
                        parentHitCount INTEGER NOT NULL,
                        caseSensitive INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        FOREIGN KEY(translationProjectId) REFERENCES translation_projects_v2(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lexicon_candidate_aggregates_translationProjectId ON lexicon_candidate_aggregates(translationProjectId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lexicon_candidate_aggregates_translationProjectId_normalizedSourceTerm ON lexicon_candidate_aggregates(translationProjectId, normalizedSourceTerm)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lexicon_candidate_aggregates_translationProjectId_state ON lexicon_candidate_aggregates(translationProjectId, state)")

                // Older V2 scans wrote AI candidates directly to lexicon_entries. Preserve their
                // observations and vote evidence while moving them into the review-only table.
                val seeds = linkedMapOf<Pair<Long, String>, LegacyCandidateSeed>()
                db.query(
                    "SELECT translationProjectId, sourceTerm, targetTerm, category, notes, " +
                        "caseSensitive, createdAt, updatedAt FROM lexicon_entries " +
                        "WHERE source = 'AI' AND reviewStatus = 'CANDIDATE'"
                ).use { cursor ->
                    val projectIndex = cursor.getColumnIndexOrThrow("translationProjectId")
                    val sourceIndex = cursor.getColumnIndexOrThrow("sourceTerm")
                    val targetIndex = cursor.getColumnIndexOrThrow("targetTerm")
                    val categoryIndex = cursor.getColumnIndexOrThrow("category")
                    val notesIndex = cursor.getColumnIndexOrThrow("notes")
                    val caseSensitiveIndex = cursor.getColumnIndexOrThrow("caseSensitive")
                    val createdIndex = cursor.getColumnIndexOrThrow("createdAt")
                    val updatedIndex = cursor.getColumnIndexOrThrow("updatedAt")
                    while (cursor.moveToNext()) {
                        val projectId = cursor.getLong(projectIndex)
                        val sourceTerm = cursor.getString(sourceIndex).trim()
                        val normalized = LexiconCandidateVoting.normalizeSourceTerm(sourceTerm)
                        if (normalized.isBlank()) continue
                        val seed = seeds.getOrPut(projectId to normalized) {
                            LegacyCandidateSeed(projectId, normalized, sourceTerm)
                        }
                        if (sourceTerm < seed.sourceTerm) seed.sourceTerm = sourceTerm
                        val target = cursor.getString(targetIndex).trim()
                        val category = cursor.getString(categoryIndex).trim().uppercase(Locale.ROOT)
                        val notes = cursor.getString(notesIndex).trim()
                        seed.targetVotes[target] = (seed.targetVotes[target] ?: 0) + 1
                        if (category.isNotBlank()) seed.categoryVotes[category] = (seed.categoryVotes[category] ?: 0) + 1
                        if (notes.isNotBlank()) seed.notesVotes[notes] = (seed.notesVotes[notes] ?: 0) + 1
                        seed.observationCount++
                        val createdAt = cursor.getLong(createdIndex)
                        val updatedAt = cursor.getLong(updatedIndex)
                        seed.firstSeenAt = if (seed.firstSeenAt == 0L) createdAt else minOf(seed.firstSeenAt, createdAt)
                        seed.lastSeenAt = maxOf(seed.lastSeenAt, updatedAt)
                        seed.caseSensitive = seed.caseSensitive || cursor.getInt(caseSensitiveIndex) != 0
                    }
                }
                seeds.values.forEach { seed ->
                    val values = ContentValues().apply {
                        put("translationProjectId", seed.translationProjectId)
                        put("sourceTerm", seed.sourceTerm)
                        put("normalizedSourceTerm", seed.normalizedSourceTerm)
                        put("targetVotesJson", LexiconCandidateVoting.encodeVotes(seed.targetVotes))
                        put("categoryVotesJson", LexiconCandidateVoting.encodeVotes(seed.categoryVotes))
                        put("notesVotesJson", LexiconCandidateVoting.encodeVotes(seed.notesVotes))
                        put("winnerTargetTerm", LexiconCandidateVoting.winner(seed.targetVotes).orEmpty())
                        put("winnerCategory", LexiconCandidateVoting.winner(seed.categoryVotes).orEmpty())
                        put("winnerNotes", LexiconCandidateVoting.winner(seed.notesVotes).orEmpty())
                        put("observationCount", seed.observationCount)
                        put("firstSeenChapterIndex", 0)
                        put("lastSeenChapterIndex", 0)
                        put("firstSeenAt", seed.firstSeenAt)
                        put("lastSeenAt", seed.lastSeenAt)
                        put("sourceHitCount", 0)
                        put("independentHitCount", 0)
                        put("parentHitCount", 0)
                        put("caseSensitive", if (seed.caseSensitive) 1 else 0)
                        put("state", LexiconCandidateState.ACTIVE.name)
                    }
                    db.insert("lexicon_candidate_aggregates", SQLiteDatabase.CONFLICT_ABORT, values)
                }
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_translator_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Populate default provider presets
                            CoroutineScope(Dispatchers.IO).launch {
                                val dao = getDatabase(context).apiProviderDao()
                                if (dao.getProviderCount() == 0) {
                                    // Keep first-run configuration minimal; more vendors are available
                                    // as selectable templates in the provider editor.
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "OpenAI (Official)",
                                            providerType = ProviderType.OPENAI_COMPATIBLE,
                                            baseUrl = "https://api.openai.com/v1",
                                            apiKey = "",
                                            selectedModel = "gpt-5.6-luna",
                                            inputPricePerMillion = 0.25,
                                            outputPricePerMillion = 2.00,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = true
                                        )
                                    )
                                    dao.insertProvider(
                                        ApiProviderEntity(
                                            name = "DeepSeek (Official)",
                                            providerType = ProviderType.DEEPSEEK,
                                            baseUrl = "https://api.deepseek.com/v1",
                                            apiKey = "",
                                            selectedModel = "deepseek-v4-flash",
                                            inputPricePerMillion = 0.14,
                                            outputPricePerMillion = 0.28,
                                            currency = "USD",
                                            maxContextTokens = 32_768,
                                            isDefault = false
                                        )
                                    )
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
