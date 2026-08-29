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
    suspend fun getBook(bookId: Long) = books.getBook(bookId)
    fun observeEditions(bookId: Long) = books.observeEditions(bookId)
    fun observeEdition(editionId: Long) = books.observeEdition(editionId)
    fun observeChapters(bookId: Long) = books.observeChapters(bookId)
    fun observeTranslationProjects(bookId: Long) = projects.observeByBook(bookId)
    fun observeTranslationProjectsForEdition(editionId: Long) = projects.observeByTargetEdition(editionId)
    fun observeProgress(bookId: Long) = progressDao.observe(bookId)
    fun observeLexicon(projectId: Long) = database.lexiconV2Dao().observe(projectId)
    fun observeLexiconCandidates(projectId: Long) = database.lexiconCandidateAggregateDao().observeAllActive(projectId)
    fun observeStoryMemory(projectId: Long) = database.memoryDao().observeStoryMemory(projectId)
    fun observeChapterMemory(projectId: Long) = database.memoryDao().observeChapterMemory(projectId)
    fun observeRunsByBook(bookId: Long) = database.platformTaskDao().observeRunsByBook(bookId)
    fun observeRunsByProject(projectId: Long) = database.platformTaskDao().observeRunsByProject(projectId)
    fun observeBatches(runId: Long) = database.platformTaskDao().observeBatches(runId)
    fun observeRequestLogs(runId: Long) = database.platformTaskDao().observeRequestLogs(runId)
    fun observeAllPlatformRequestLogs() = database.platformTaskDao().observeAllRequestLogs()

    suspend fun getTranslationProject(projectId: Long) = projects.get(projectId)
    suspend fun updateTranslationProject(project: TranslationProjectV2Entity) = projects.update(project)
    suspend fun getChapters(bookId: Long) = books.getChapters(bookId)
    suspend fun getEditionChapters(bookId: Long, editionId: Long): Set<Long> {
        val all = books.getChapters(bookId)
        return all.filter { books.getEditionChapter(editionId, it.id) != null }.map { it.id }.toSet()
    }
    suspend fun retranslateChapter(editionId: Long, logicalChapterId: Long) {
        books.deleteEditionChapter(editionId, logicalChapterId)
    }
    suspend fun upsertLexiconEntry(entry: LexiconEntryEntity) = database.lexiconV2Dao().upsert(entry)
    suspend fun upsertLexiconEntries(entries: List<LexiconEntryEntity>) = database.lexiconV2Dao().upsertAll(entries)
    suspend fun updateLexiconEntry(entry: LexiconEntryEntity) = database.lexiconV2Dao().update(entry)
    suspend fun deleteLexiconEntry(id: Long) = database.lexiconV2Dao().delete(id)
    suspend fun markLexiconCandidateImported(id: Long) = database.lexiconCandidateAggregateDao().markImported(id)
    suspend fun markLexiconCandidateIgnored(id: Long) = database.lexiconCandidateAggregateDao().markIgnored(id)

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

        val editionsToFetch = if (original.id == preferred.id) listOf(original.id) else listOf(original.id, preferred.id)

        // Fetch all required data in bulk to avoid O(N) queries
        val allLogicalSegments = books.getLogicalSegmentsByBook(book.id).groupBy { it.logicalChapterId }
        val allEditionSegments = books.getEditionSegmentsByEditions(editionsToFetch).associateBy { it.id }
        val allMappings = books.getMappingsByEditions(editionsToFetch).groupBy { it.logicalSegmentId }
        val allRevisions = books.getActiveRevisionsByEditions(editionsToFetch)
            .groupBy { it.editionSegmentId }
            .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt }) }

        val editionChapterIds = editionsToFetch.associateWith { editionId ->
            books.getEditionChapters(editionId).mapTo(HashSet()) { it.id }
        }

        fun resolveContentForEdition(editionId: Long, logicalSegments: List<LogicalSegmentEntity>): Map<Long, EffectiveSegment> {
            if (logicalSegments.isEmpty()) return emptyMap()
            val editionContentMap = mutableMapOf<Long, EffectiveSegment>()
            val validChapterIds = editionChapterIds[editionId].orEmpty()
            for (logicalSegment in logicalSegments) {
                val mappingsForLogical = allMappings[logicalSegment.id] ?: continue
                val mappingsForThisEdition = mappingsForLogical.filter { mapping ->
                    val segment = allEditionSegments[mapping.editionSegmentId]
                    segment != null && segment.editionChapterId in validChapterIds
                }
                if (mappingsForThisEdition.isEmpty()) continue

                val orderedSegments = mappingsForThisEdition.sortedBy { it.mappingOrder }.mapNotNull { allEditionSegments[it.editionSegmentId] }
                if (orderedSegments.isEmpty()) continue

                val text = orderedSegments.joinToString("\n\n") { segment ->
                    allRevisions[segment.id]?.text ?: segment.baseText
                }
                editionContentMap[logicalSegment.id] = EffectiveSegment(orderedSegments.map { it.id }, text)
            }
            return editionContentMap
        }

        val seenRenderedSegments = mutableSetOf<Long>()
        return buildList {
            inputs.chapters.forEach { logicalChapter ->
                val logicalSegments = allLogicalSegments[logicalChapter.id] ?: emptyList()
                val originalContent = resolveContentForEdition(original.id, logicalSegments)
                val preferredContent = if (preferred.id == original.id) originalContent
                else resolveContentForEdition(preferred.id, logicalSegments)

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
        seamlessAheadChapters: Int = 5,
        styleGuide: String = "保持文学韵味与专有名词一致性",
        highQualityReview: Boolean = false
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
                styleGuide = styleGuide.trim().take(2_000).ifBlank { "保持文学韵味与专有名词一致性" },
                translationMode = mode.name,
                maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                seamlessAheadChapters = seamlessAheadChapters.coerceAtLeast(1),
                highQualityReview = highQualityReview
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
