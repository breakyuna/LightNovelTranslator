package com.breakyuna.noveltranslator.data.repository

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest

data class ResolvedReaderSegment(
    val logicalChapterId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val logicalSegmentId: Long,
    val segmentIndex: Int,
    val originalText: String,
    val translatedText: String?,
    val displayText: String,
    val editionSegmentId: Long,
    val isCompositeMapping: Boolean,
    val isFallback: Boolean
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookPlatformRepository(
    private val database: AppDatabase,
    private val files: BookFileManager
) {
    private val books = database.bookDao()
    private val projects = database.translationProjectV2Dao()
    private val progressDao = database.readerProgressDao()

    val shelf: Flow<List<ShelfBook>> = books.observeShelf()
    val allBooks: Flow<List<BookEntity>> = books.observeAllBooks()
    val hiddenBooks: Flow<List<BookEntity>> = books.observeHiddenBooks()
    val allTranslationProjects: Flow<List<TranslationProjectV2Entity>> = projects.observeAll()

    fun observeBook(bookId: Long) = books.observeBook(bookId)
    fun observeEditions(bookId: Long) = books.observeEditions(bookId)
    fun observeEdition(editionId: Long) = books.observeEdition(editionId)
    fun observeChapters(bookId: Long) = books.observeChapters(bookId)
    fun observeTranslationProjects(bookId: Long) = projects.observeByBook(bookId)
    fun observeTranslationProjectsForEdition(editionId: Long) = projects.observeByTargetEdition(editionId)
    fun observeProgress(bookId: Long) = progressDao.observe(bookId)
    fun observeLexicon(projectId: Long) = database.lexiconV2Dao().observe(projectId)
    fun observeStoryMemory(projectId: Long) = database.memoryDao().observeStoryMemory(projectId)
    fun observeChapterMemory(projectId: Long) = database.memoryDao().observeChapterMemory(projectId)

    fun observeReader(bookId: Long): Flow<List<ResolvedReaderSegment>> = combine(
        books.observeBook(bookId),
        books.observeEditions(bookId),
        books.observeChapters(bookId),
        progressDao.observe(bookId),
        books.observeRevisionIds(bookId)
    ) { book, editions, chapters, progress, _ ->
        ReaderInputs(book, editions, chapters, progress)
    }.mapLatest { inputs -> resolveReader(inputs) }

    fun observeEditionPreview(bookId: Long, editionId: Long): Flow<List<ResolvedReaderSegment>> = combine(
        books.observeBook(bookId),
        books.observeEditions(bookId),
        books.observeChapters(bookId),
        books.observeRevisionIds(bookId)
    ) { book, editions, chapters, _ ->
        ReaderInputs(
            book = book,
            editions = editions,
            chapters = chapters,
            progress = ReaderProgressEntity(bookId, editionId, null, null)
        )
    }.mapLatest { inputs -> resolveReader(inputs) }

    private suspend fun resolveReader(inputs: ReaderInputs): List<ResolvedReaderSegment> {
        val book = inputs.book ?: return emptyList()
        val original = inputs.editions.firstOrNull { it.id == book.primaryEditionId }
            ?: inputs.editions.firstOrNull { it.type == EditionType.IMPORTED.name }
            ?: return emptyList()
        val preferredId = inputs.progress?.preferredEditionId ?: book.preferredReadingEditionId ?: original.id
        val preferred = inputs.editions.firstOrNull { it.id == preferredId } ?: original
        val seenRenderedSegments = mutableSetOf<Long>()
        return buildList {
            inputs.chapters.forEach { logicalChapter ->
                val logicalSegments = books.getLogicalSegments(logicalChapter.id)
                val originalContent = resolveEditionChapter(original.id, logicalChapter, logicalSegments)
                val preferredContent = if (preferred.id == original.id) originalContent
                else resolveEditionChapter(preferred.id, logicalChapter, logicalSegments)
                logicalSegments.forEach { logical ->
                    val originalPart = originalContent[logical.id]
                    val preferredPart = preferredContent[logical.id]
                    val chosen = preferredPart ?: originalPart ?: return@forEach
                    if (chosen.editionSegmentIds.all { it in seenRenderedSegments }) return@forEach
                    seenRenderedSegments.addAll(chosen.editionSegmentIds)
                    add(
                        ResolvedReaderSegment(
                            logicalChapterId = logicalChapter.id,
                            chapterIndex = logicalChapter.chapterIndex,
                            chapterTitle = logicalChapter.canonicalTitle,
                            logicalSegmentId = logical.id,
                            segmentIndex = logical.segmentIndex,
                            originalText = originalPart?.text.orEmpty(),
                            translatedText = preferredPart?.text.takeIf { preferred.id != original.id },
                            displayText = chosen.text,
                            editionSegmentId = chosen.editionSegmentIds.first(),
                            isCompositeMapping = chosen.editionSegmentIds.size != 1,
                            isFallback = preferred.id != original.id && preferredPart == null
                        )
                    )
                }
            }
        }
    }

    private suspend fun resolveEditionChapter(
        editionId: Long,
        logicalChapter: LogicalChapterEntity,
        logicalSegments: List<LogicalSegmentEntity>
    ): Map<Long, EffectiveSegment> {
        val chapter = books.getEditionChapter(editionId, logicalChapter.id) ?: return emptyMap()
        if (!chapter.isAvailable) return emptyMap()
        val editionSegments = books.getEditionSegments(chapter.id)
        if (editionSegments.isEmpty() || logicalSegments.isEmpty()) return emptyMap()
        val byId = editionSegments.associateBy { it.id }
        val mappings = books.getMappings(logicalSegments.map { it.id })
            .filter { it.editionSegmentId in byId }
            .groupBy { it.logicalSegmentId }
        val revisions = books.getActiveRevisions(editionSegments.map { it.id })
            .groupBy { it.editionSegmentId }
            .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt }) }
        return mappings.mapValues { (_, rows) ->
            val ordered = rows.sortedBy { it.mappingOrder }.mapNotNull { byId[it.editionSegmentId] }
            val text = ordered.joinToString("\n\n") { segment -> revisions[segment.id]?.text ?: segment.baseText }
            EffectiveSegment(ordered.map { it.id }, text)
        }
    }

    suspend fun createTranslationEdition(
        bookId: Long,
        sourceEditionId: Long,
        targetLanguage: String,
        editionName: String
    ): Long = database.withTransaction {
        val source = books.getEdition(sourceEditionId) ?: error("Source edition not found")
        require(source.bookId == bookId)
        books.insertEdition(
            EditionEntity(
                bookId = bookId,
                name = editionName.trim().ifBlank { "$targetLanguage · AI translation" }.take(200),
                type = EditionType.AI_TRANSLATION.name,
                language = targetLanguage.trim().ifBlank { "Auto" },
                sourceEditionId = sourceEditionId,
                isComplete = false
            )
        )
    }

    suspend fun createTranslationProject(
        bookId: Long,
        sourceEditionId: Long,
        targetEditionId: Long,
        providerId: Long?,
        modelName: String,
        mode: TranslationMode = TranslationMode.FULL_BOOK,
        maxBatchChapters: Int = 1,
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
        seamlessAheadChapters: Int = 5
    ): Long = database.withTransaction {
        if (mode == TranslationMode.CHAPTER_RANGE) {
            require(rangeStart != null && rangeEnd != null && rangeStart > 0 && rangeEnd >= rangeStart) { "Invalid chapter range" }
        }
        val source = books.getEdition(sourceEditionId) ?: error("Source edition not found")
        require(source.bookId == bookId)
        val target = books.getEdition(targetEditionId) ?: error("Target edition not found")
        require(target.bookId == bookId && target.id != source.id)
        val projectId = projects.insert(
            TranslationProjectV2Entity(
                bookId = bookId,
                sourceEditionId = sourceEditionId,
                targetEditionId = targetEditionId,
                sourceLanguage = source.language,
                targetLanguage = target.language,
                providerId = providerId,
                modelName = modelName,
                translationMode = mode.name,
                maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                seamlessAheadChapters = seamlessAheadChapters.coerceAtLeast(1)
            )
        )
        books.setPreferredEdition(bookId, targetEditionId)
        val old = progressDao.get(bookId) ?: ReaderProgressEntity(bookId, sourceEditionId, null, null)
        progressDao.upsert(old.copy(preferredEditionId = targetEditionId, updatedAt = System.currentTimeMillis()))
        projectId
    }

    suspend fun saveReaderProgress(progress: ReaderProgressEntity) = progressDao.upsert(progress.copy(updatedAt = System.currentTimeMillis()))

    suspend fun selectReadingEdition(bookId: Long, editionId: Long) {
        val edition = books.getEdition(editionId) ?: return
        require(edition.bookId == bookId)
        books.setPreferredEdition(bookId, editionId)
        val current = progressDao.get(bookId) ?: ReaderProgressEntity(bookId, editionId, null, null)
        progressDao.upsert(current.copy(preferredEditionId = editionId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveManualRevision(editionSegmentId: Long, text: String, note: String? = null) =
        books.insertRevision(
            SegmentRevisionEntity(
                editionSegmentId = editionSegmentId,
                revisionType = RevisionType.MANUAL_EDIT.name,
                text = text,
                note = note
            )
        )

    suspend fun renameBook(bookId: Long, title: String) = books.rename(bookId, title.trim().take(300))
    suspend fun updateBookMetadata(bookId: Long, title: String, author: String, description: String, language: String) =
        books.updateMetadata(bookId, title.trim().take(300), author.trim().take(300), description.trim().take(3_000), language.trim().take(80))
    suspend fun updateCover(bookId: Long, path: String) = books.updateCover(bookId, path)
    suspend fun updateShelfOrder(bookId: Long, order: Int) = books.updateShelfOrder(bookId, order)
    suspend fun removeFromShelf(bookId: Long) = books.setHidden(bookId, true)
    suspend fun restoreToShelf(bookId: Long) = books.setHidden(bookId, false)

    suspend fun deletePermanently(bookId: Long) {
        database.withTransaction { books.deletePermanently(bookId) }
        files.deleteBook(bookId)
    }

    private data class ReaderInputs(
        val book: BookEntity?,
        val editions: List<EditionEntity>,
        val chapters: List<LogicalChapterEntity>,
        val progress: ReaderProgressEntity?
    )

    private data class EffectiveSegment(val editionSegmentIds: List<Long>, val text: String)
}
