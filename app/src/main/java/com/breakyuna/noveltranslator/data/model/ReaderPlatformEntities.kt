package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EditionType { IMPORTED, AI_TRANSLATION, MANUAL, WEB_CAPTURE }
enum class RevisionType { AI_TRANSLATION, AUTO_REPAIR, LEXICON_REPLACEMENT, USER_CONFIRMED_REPLACEMENT, MANUAL_EDIT }
enum class LexiconKind { PROPER_NOUN, TERMINOLOGY }
enum class LexiconSource { AI, MANUAL, IMPORTED }
enum class ReviewStatus { CANDIDATE, CONFIRMED }
enum class TranslationMode { FULL_BOOK, CHAPTER_RANGE, SEAMLESS }
enum class MemoryOperation { ADD, UPDATE }
enum class MemoryRepairState { READY, PENDING_REPAIR }
enum class DisplayMode { TRANSLATION, ORIGINAL, BILINGUAL, QUICK_EDIT }
enum class PagingMode { CONTINUOUS, HORIZONTAL, VERTICAL }
enum class ReaderLayoutMode { CLEAN, STANDARD, WORKBENCH }
enum class PageAnimation { NONE, SLIDE, FADE, CURL }
enum class AcquisitionType { LOCAL_FILE, PASTED_TEXT, WEB_CAPTURE }

@Entity(tableName = "books", indices = [Index("shelfOrder")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "Unknown",
    val coverPath: String? = null,
    val description: String = "",
    val originalLanguage: String = "Auto",
    val primaryEditionId: Long? = null,
    val preferredReadingEditionId: Long? = null,
    val hiddenFromShelf: Boolean = false,
    val shelfOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "editions",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index(value = ["bookId", "type"])]
)
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val type: String = EditionType.IMPORTED.name,
    val language: String = "Auto",
    val sourceEditionId: Long? = null,
    val isComplete: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "logical_chapters",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "chapterIndex"], unique = true)]
)
data class LogicalChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val canonicalTitle: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "logical_segments",
    foreignKeys = [ForeignKey(entity = LogicalChapterEntity::class, parentColumns = ["id"], childColumns = ["logicalChapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["logicalChapterId", "segmentIndex"], unique = true)]
)
data class LogicalSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logicalChapterId: Long,
    val segmentIndex: Int,
    val segmentType: String = "PARAGRAPH",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "edition_chapters",
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LogicalChapterEntity::class, parentColumns = ["id"], childColumns = ["logicalChapterId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["editionId", "logicalChapterId"], unique = true), Index("logicalChapterId")]
)
data class EditionChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionId: Long,
    val logicalChapterId: Long,
    val title: String,
    val contentFileName: String,
    val wordCount: Int = 0,
    val isAvailable: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "edition_segments",
    foreignKeys = [ForeignKey(entity = EditionChapterEntity::class, parentColumns = ["id"], childColumns = ["editionChapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["editionChapterId", "segmentIndex"], unique = true)]
)
data class EditionSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionChapterId: Long,
    val segmentIndex: Int,
    val baseText: String,
    val sourceHash: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Separate mapping table permits 1:1, 1:N, N:1 and N:N alignment. */
@Entity(
    tableName = "edition_segment_mappings",
    primaryKeys = ["logicalSegmentId", "editionSegmentId"],
    foreignKeys = [
        ForeignKey(entity = LogicalSegmentEntity::class, parentColumns = ["id"], childColumns = ["logicalSegmentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EditionSegmentEntity::class, parentColumns = ["id"], childColumns = ["editionSegmentId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("editionSegmentId")]
)
data class EditionSegmentMappingEntity(
    val logicalSegmentId: Long,
    val editionSegmentId: Long,
    val mappingOrder: Int = 0
)

@Entity(
    tableName = "segment_revisions",
    foreignKeys = [ForeignKey(entity = EditionSegmentEntity::class, parentColumns = ["id"], childColumns = ["editionSegmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("editionSegmentId"), Index(value = ["editionSegmentId", "priority", "createdAt"])]
)
data class SegmentRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val editionSegmentId: Long,
    val revisionType: String,
    val text: String,
    val priority: Int = revisionPriority(revisionType),
    val sourceRevisionId: Long? = null,
    val isActive: Boolean = true,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

fun revisionPriority(type: String): Int = when (type) {
    RevisionType.MANUAL_EDIT.name -> 400
    RevisionType.USER_CONFIRMED_REPLACEMENT.name, RevisionType.LEXICON_REPLACEMENT.name -> 300
    RevisionType.AI_TRANSLATION.name -> 200
    RevisionType.AUTO_REPAIR.name -> 100
    else -> 0
}

@Entity(
    tableName = "translation_projects_v2",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["sourceEditionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["targetEditionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("bookId"), Index("sourceEditionId"), Index("targetEditionId")]
)
data class TranslationProjectV2Entity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val sourceEditionId: Long,
    val targetEditionId: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: Long?,
    val modelName: String = "",
    val styleGuide: String = "Literary Novel",
    val promptProtocolVersion: Int = 1,
    val translationMode: String = TranslationMode.FULL_BOOK.name,
    val maxBatchChapters: Int = 1,
    val seamlessAheadChapters: Int = 5,
    val rangeStart: Int? = null,
    val rangeEnd: Int? = null,
    val highQualityReview: Boolean = false,
    val state: String = "IDLE",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init { require(maxBatchChapters in 1..5) }
}

@Entity(
    tableName = "lexicon_entries",
    foreignKeys = [ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("translationProjectId"), Index(value = ["translationProjectId", "sourceTerm"], unique = true)]
)
data class LexiconEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val sourceTerm: String,
    val targetTerm: String,
    val kind: String = LexiconKind.PROPER_NOUN.name,
    val category: String = "CUSTOM",
    val aliases: String = "",
    val notes: String = "",
    val caseSensitive: Boolean = false,
    val exactMatch: Boolean = true,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val source: String = LexiconSource.MANUAL.name,
    val reviewStatus: String = ReviewStatus.CONFIRMED.name,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "story_memory",
    foreignKeys = [ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("translationProjectId"), Index(value = ["translationProjectId", "factKey"], unique = true)]
)
data class StoryMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val factKey: String,
    val factValue: String,
    val entities: String = "",
    val sourceChapterIndex: Int,
    val lastUpdatedChapterIndex: Int,
    val conflictNote: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapter_memory",
    foreignKeys = [
        ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LogicalChapterEntity::class, parentColumns = ["id"], childColumns = ["logicalChapterId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["translationProjectId", "logicalChapterId"], unique = true), Index("logicalChapterId")]
)
data class ChapterMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val logicalChapterId: Long,
    val chapterIndex: Int,
    val summary: String,
    val entities: String = "",
    val stateChanges: String = "",
    val newFacts: String = "",
    val unresolvedThreads: String = "",
    val repairState: String = MemoryRepairState.READY.name,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "context_snapshots",
    foreignKeys = [ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("translationProjectId")]
)
data class ContextSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val protocolVersion: Int,
    val styleGuideVersion: Int,
    val coreLexiconVersion: Int,
    val storyMemoryVersion: Int,
    val stablePrefix: String,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reader_progress",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("preferredEditionId")]
)
data class ReaderProgressEntity(
    @PrimaryKey val bookId: Long,
    val preferredEditionId: Long?,
    val logicalChapterId: Long?,
    val logicalSegmentId: Long?,
    val segmentOffset: Int = 0,
    val displayMode: String = DisplayMode.TRANSLATION.name,
    val pagingMode: String = PagingMode.CONTINUOUS.name,
    val readerLayoutMode: String = ReaderLayoutMode.CLEAN.name,
    val pageAnimation: String = PageAnimation.SLIDE.name,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "provider_cache_records",
    foreignKeys = [ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("translationProjectId"), Index("fingerprint")]
)
data class ProviderCacheRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val providerName: String,
    val modelName: String,
    val fingerprint: String,
    val remoteCacheId: String? = null,
    val cachedTokenCount: Long = 0,
    val hitTokens: Long = 0,
    val missTokens: Long = 0,
    val estimatedSavedCost: Double = 0.0,
    val expiresAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "platform_translation_runs",
    foreignKeys = [
        ForeignKey(entity = TranslationProjectV2Entity::class, parentColumns = ["id"], childColumns = ["translationProjectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("translationProjectId"), Index("bookId"), Index("state")]
)
data class PlatformTranslationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val bookId: Long,
    val providerId: Long,
    val providerName: String,
    val modelName: String,
    val state: String = "QUEUED",
    val completedChapters: Int = 0,
    val failedChapters: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalCost: Double = 0.0,
    val currency: String = "USD",
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "platform_translation_batches",
    foreignKeys = [ForeignKey(entity = PlatformTranslationRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId"), Index(value = ["runId", "batchIndex"], unique = true)]
)
data class PlatformTranslationBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val batchIndex: Int,
    val firstChapterIndex: Int,
    val lastChapterIndex: Int,
    val state: String = "PENDING",
    val contextSnapshotId: Long? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cost: Double = 0.0,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "platform_request_logs",
    foreignKeys = [ForeignKey(entity = PlatformTranslationRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId"), Index("timestamp")]
)
data class PlatformRequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val batchId: Long?,
    val operation: String,
    val attemptCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long = 0,
    val estimatedCost: Double,
    val durationMs: Long,
    val finishReason: String? = null,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ShelfBook(
    val id: Long,
    val title: String,
    val coverPath: String?,
    val preferredReadingEditionId: Long?,
    val hasTranslationProject: Boolean,
    val shelfOrder: Int
)

data class ReaderSegmentRow(
    val logicalChapterId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val logicalSegmentId: Long,
    val segmentIndex: Int,
    val editionId: Long,
    val editionSegmentId: Long,
    val text: String,
    val isFallback: Boolean
)
