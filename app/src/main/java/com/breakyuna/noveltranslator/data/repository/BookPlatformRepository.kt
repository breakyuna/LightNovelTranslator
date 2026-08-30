package com.breakyuna.noveltranslator.data.repository

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
    private val promptProfiles = database.promptProfileDao()
    private val progressDao = database.readerProgressDao()

    val shelf: Flow<List<ShelfBook>> = books.observeShelf()
    val allBooks: Flow<List<BookEntity>> = books.observeAllBooks()
    val hiddenBooks: Flow<List<BookEntity>> = books.observeHiddenBooks()
    val allEditions: Flow<List<EditionEntity>> = books.observeAllEditions()
    val allTranslationProjects: Flow<List<TranslationProjectV2Entity>> = projects.observeAll()

    fun observeBook(bookId: Long) = books.observeBook(bookId)
    suspend fun getBook(bookId: Long) = books.getBook(bookId)
    fun observeEditions(bookId: Long) = books.observeEditions(bookId)
    fun observeEdition(editionId: Long) = books.observeEdition(editionId)
    fun observeChapters(bookId: Long) = books.observeChapters(bookId)
    fun observeTranslationProjects(bookId: Long) = projects.observeByBook(bookId)
    fun observeTranslationProjectsForEdition(editionId: Long) = projects.observeByTargetEdition(editionId)
    fun observePromptProfile(projectId: Long) = promptProfiles.observeLatest(projectId)
    fun observeProgress(bookId: Long) = progressDao.observe(bookId)
    fun observeLexicon(projectId: Long) = database.lexiconV2Dao().observe(projectId)
    fun observeLexiconCandidates(projectId: Long) = database.lexiconCandidateAggregateDao().observeAllActive(projectId)
    fun observeStoryMemory(projectId: Long) = database.memoryDao().observeStoryMemory(projectId)
    fun observeChapterMemory(projectId: Long) = database.memoryDao().observeChapterMemory(projectId)
    fun observeRunsByBook(bookId: Long) = database.platformTaskDao().observeRunsByBook(bookId)
    fun observeRunsByProject(projectId: Long) = database.platformTaskDao().observeRunsByProject(projectId)
    fun observeBatches(runId: Long) = database.platformTaskDao().observeBatches(runId)
    fun observeRequestLogs(runId: Long) = database.platformTaskDao().observeRequestLogs(runId)

    suspend fun getTranslationProject(projectId: Long) = projects.get(projectId)
    suspend fun getPromptProfile(projectId: Long) = promptProfiles.getLatest(projectId)
    suspend fun getTranslationProjects(bookId: Long) = projects.getByBook(bookId)
    suspend fun getTranslationProjectsForEdition(editionId: Long) = projects.getByTargetEdition(editionId)
    suspend fun updateTranslationProject(
        project: TranslationProjectV2Entity,
        promptProfile: PromptProfileDraft? = null
    ) = database.withTransaction {
        // Serialize the state check with the scheduler's state writes. A UI snapshot that was idle
        // before the run started must not be able to overwrite RUNNING with a new configuration.
        val existing = projects.get(project.id) ?: error("Translation project not found")
        require(existing.bookId == project.bookId) { "Translation project book cannot be changed" }
        require(existing.sourceEditionId == project.sourceEditionId) { "Translation project source Edition cannot be changed" }
        require(existing.targetEditionId == project.targetEditionId) { "Translation project target Edition cannot be changed" }
        require(existing.state !in setOf("RUNNING", "PAUSED")) {
            "Stop the active translation task before changing its configuration"
        }
        validateTranslationConfiguration(project.bookId, project.translationMode, project.rangeStart, project.rangeEnd)
        val normalizedModelName = project.modelName.trim()
        val normalizedStyleGuide = project.styleGuide.trim()
        require(normalizedModelName.length <= 200) { "Model name is too long" }
        require(normalizedStyleGuide.length <= 2_000) { "Style guide is too long" }
        require(isSafeText(normalizedModelName) && isSafeText(normalizedStyleGuide, allowLineBreaks = true)) {
            "Translation configuration contains unsupported control characters"
        }
        project.providerId?.let { providerId ->
            require(database.apiProviderDao().getProviderById(providerId) != null) {
                "Selected API provider no longer exists"
            }
        }
        require(project.seamlessAheadChapters in 1..50) { "Invalid seamless translation buffer" }
        require(runCatching { TranslationMode.valueOf(project.translationMode) }.isSuccess) {
            "Unknown translation mode"
        }
        projects.update(
            project.copy(
                modelName = normalizedModelName,
                styleGuide = normalizedStyleGuide.ifBlank { "保持文学韵味与专有名词一致性" },
                maxBatchChapters = project.maxBatchChapters.coerceIn(1, 5),
                seamlessAheadChapters = project.seamlessAheadChapters.coerceIn(1, 50),
                rangeStart = project.rangeStart.takeIf { project.translationMode == TranslationMode.CHAPTER_RANGE.name },
                rangeEnd = project.rangeEnd.takeIf { project.translationMode == TranslationMode.CHAPTER_RANGE.name },
                state = existing.state,
                updatedAt = System.currentTimeMillis()
            )
        )
        promptProfile?.let { savePromptProfileInTransaction(existing.id, it) }
    }

    /** Saves a new immutable Prompt Profile version for the project when its content changed. */
    suspend fun savePromptProfile(projectId: Long, draft: PromptProfileDraft): PromptProfileEntity =
        database.withTransaction {
            val project = projects.get(projectId) ?: error("Translation project not found")
            require(project.state !in setOf("RUNNING", "PAUSED")) {
                "Stop the active translation task before changing prompts"
            }
            savePromptProfileInTransaction(projectId, draft)
        }

    private suspend fun savePromptProfileInTransaction(
        projectId: Long,
        draft: PromptProfileDraft
    ): PromptProfileEntity {
        val normalized = normalizePromptProfile(draft)
        val latest = promptProfiles.getLatest(projectId)
        if (latest != null &&
            latest.translationSystemPrompt == normalized.translationSystemPrompt &&
            latest.translationUserPromptTemplate == normalized.translationUserPromptTemplate &&
            latest.polishSystemPrompt == normalized.polishSystemPrompt &&
            latest.polishUserPromptTemplate == normalized.polishUserPromptTemplate
        ) {
            return latest
        }
        val profile = PromptProfileEntity(
            translationProjectId = projectId,
            version = promptProfiles.getMaxVersion(projectId) + 1,
            translationSystemPrompt = normalized.translationSystemPrompt,
            translationUserPromptTemplate = normalized.translationUserPromptTemplate,
            polishSystemPrompt = normalized.polishSystemPrompt,
            polishUserPromptTemplate = normalized.polishUserPromptTemplate
        )
        promptProfiles.insert(profile)
        return profile
    }

    private fun normalizePromptProfile(draft: PromptProfileDraft): PromptProfileDraft {
        val normalized = PromptProfileDraft(
            translationSystemPrompt = draft.translationSystemPrompt.trim().take(MAX_PROMPT_TEMPLATE_CHARS),
            translationUserPromptTemplate = draft.translationUserPromptTemplate.trim().take(MAX_PROMPT_TEMPLATE_CHARS),
            polishSystemPrompt = draft.polishSystemPrompt.trim().take(MAX_PROMPT_TEMPLATE_CHARS),
            polishUserPromptTemplate = draft.polishUserPromptTemplate.trim().take(MAX_PROMPT_TEMPLATE_CHARS)
        )
        require(isSafeText(normalized.translationSystemPrompt, allowLineBreaks = true) &&
            isSafeText(normalized.translationUserPromptTemplate, allowLineBreaks = true) &&
            isSafeText(normalized.polishSystemPrompt, allowLineBreaks = true) &&
            isSafeText(normalized.polishUserPromptTemplate, allowLineBreaks = true)
        ) {
            "Prompt templates contain unsupported control characters"
        }
        return normalized
    }
    suspend fun getChapters(bookId: Long) = books.getChapters(bookId)
    suspend fun retranslateChapter(editionId: Long, logicalChapterId: Long) {
        val edition = books.getEdition(editionId) ?: return
        require(edition.type == EditionType.AI_TRANSLATION.name) {
            "Only AI_TRANSLATION Editions can be retranslated"
        }
        val logicalChapter = books.getLogicalChapter(logicalChapterId) ?: return
        require(logicalChapter.bookId == edition.bookId) {
            "Chapter does not belong to the Edition's book"
        }
        val chapter = books.getEditionChapter(editionId, logicalChapterId) ?: return
        database.withTransaction {
            books.deleteEditionChapter(editionId, logicalChapterId)
            if (edition.isComplete) {
                books.updateEdition(edition.copy(isComplete = false, updatedAt = System.currentTimeMillis()))
            }
        }
        files.deleteEditionChapterFile(edition.bookId, editionId, chapter.contentFileName)
    }
    suspend fun upsertLexiconEntry(entry: LexiconEntryEntity): Long = database.withTransaction {
        upsertNormalizedLexiconEntry(normalizeLexiconEntry(entry))
    }
    suspend fun upsertLexiconEntries(entries: List<LexiconEntryEntity>): List<Long> = database.withTransaction {
        val ids = ArrayList<Long>(entries.size)
        for (entry in entries) {
            ids += upsertNormalizedLexiconEntry(normalizeLexiconEntry(entry))
        }
        ids
    }
    suspend fun updateLexiconEntry(entry: LexiconEntryEntity) = database.withTransaction {
        val normalized = normalizeLexiconEntry(entry)
        val dao = database.lexiconV2Dao()
        val current = dao.getAll(normalized.translationProjectId).firstOrNull { it.id == normalized.id }
        require(current != null) { "Glossary entry not found" }
        val conflicting = dao.getBySourceTerm(normalized.translationProjectId, normalized.sourceTerm)
        require(conflicting == null || conflicting.id == normalized.id) { "A glossary entry for this source term already exists" }
        dao.update(normalized)
    }
    suspend fun deleteLexiconEntry(id: Long) = database.lexiconV2Dao().delete(id)

    fun observeReader(bookId: Long): Flow<List<ResolvedReaderSegment>> = combine(
        books.observeBook(bookId),
        books.observeEditions(bookId),
        books.observeChapters(bookId),
        progressDao.observe(bookId),
        books.observeRevisionIds(bookId)
    ) { book, editions, chapters, progress, _ ->
        ReaderInputs(book, editions, chapters, progress)
    }.mapLatest { inputs -> withContext(Dispatchers.IO) { resolveReader(inputs) } }

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
    }.mapLatest { inputs -> withContext(Dispatchers.IO) { resolveReader(inputs) } }

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
                    allRevisions[segment.id]?.text?.takeIf { it.isNotBlank() } ?: segment.baseText
                }
                // An active revision can be present but empty after a failed/manual edit. Treat
                // an empty effective segment as unavailable so the reader/exporter can fall back
                // to the original Edition instead of rendering a blank paragraph.
                if (text.isBlank()) continue
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
        val normalizedLanguage = targetLanguage.trim().take(80).ifBlank { "Auto" }
        val normalizedName = editionName.trim().take(200).ifBlank { "$normalizedLanguage · AI translation" }
        require(isSafeText(normalizedLanguage) && isSafeText(normalizedName)) {
            "Edition metadata contains unsupported control characters"
        }
        books.insertEdition(
            EditionEntity(
                bookId = bookId,
                name = normalizedName,
                type = EditionType.AI_TRANSLATION.name,
                language = normalizedLanguage,
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
        highQualityReview: Boolean = false,
        promptProfile: PromptProfileDraft? = null
    ): Long = database.withTransaction {
        validateTranslationConfiguration(bookId, mode.name, rangeStart, rangeEnd)
        val normalizedModelName = modelName.trim().take(200)
        val normalizedStyleGuide = styleGuide.trim().take(2_000).ifBlank { "保持文学韵味与专有名词一致性" }
        require(isSafeText(normalizedModelName) && isSafeText(normalizedStyleGuide, allowLineBreaks = true)) {
            "Translation configuration contains unsupported control characters"
        }
        providerId?.let { id ->
            require(database.apiProviderDao().getProviderById(id) != null) {
                "Selected API provider no longer exists"
            }
        }
        val source = books.getEdition(sourceEditionId) ?: error("Source edition not found")
        require(source.bookId == bookId)
        val target = books.getEdition(targetEditionId) ?: error("Target edition not found")
        require(target.bookId == bookId && target.id != source.id)
        require(target.type == EditionType.AI_TRANSLATION.name) {
            "Translation projects must write to an AI_TRANSLATION Edition"
        }
        require(target.sourceEditionId == sourceEditionId) {
            "Target Edition must be derived from the selected source Edition"
        }
        // A target Edition is the durable output boundary. Reusing its latest project prevents
        // duplicate schedulable projects from competing for the same chapter/revision rows.
        projects.getByTargetEdition(targetEditionId).firstOrNull()?.let { existing ->
            require(existing.state !in setOf("RUNNING", "PAUSED")) {
                "Stop the active translation task before changing its configuration"
            }
            val updated = existing.copy(
                bookId = bookId,
                sourceEditionId = sourceEditionId,
                sourceLanguage = source.language,
                targetLanguage = target.language,
                providerId = providerId,
                modelName = normalizedModelName,
                translationMode = mode.name,
                maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                rangeStart = rangeStart.takeIf { mode == TranslationMode.CHAPTER_RANGE },
                rangeEnd = rangeEnd.takeIf { mode == TranslationMode.CHAPTER_RANGE },
                seamlessAheadChapters = seamlessAheadChapters.coerceIn(1, 50),
                styleGuide = normalizedStyleGuide,
                highQualityReview = highQualityReview,
                state = if (existing.state in setOf("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED", "INTERRUPTED")) "IDLE" else existing.state,
                updatedAt = System.currentTimeMillis()
            )
            projects.update(updated)
            promptProfile?.let { savePromptProfileInTransaction(existing.id, it) }
            books.setPreferredEdition(bookId, targetEditionId)
            val old = progressDao.get(bookId) ?: ReaderProgressEntity(bookId, sourceEditionId, null, null)
            progressDao.upsert(old.copy(preferredEditionId = targetEditionId, updatedAt = System.currentTimeMillis()))
            return@withTransaction existing.id
        }
        val projectId = projects.insert(
            TranslationProjectV2Entity(
                bookId = bookId,
                sourceEditionId = sourceEditionId,
                targetEditionId = targetEditionId,
                sourceLanguage = source.language,
                targetLanguage = target.language,
                providerId = providerId,
                modelName = normalizedModelName,
                styleGuide = normalizedStyleGuide,
                translationMode = mode.name,
                maxBatchChapters = maxBatchChapters.coerceIn(1, 5),
                rangeStart = rangeStart.takeIf { mode == TranslationMode.CHAPTER_RANGE },
                rangeEnd = rangeEnd.takeIf { mode == TranslationMode.CHAPTER_RANGE },
                seamlessAheadChapters = seamlessAheadChapters.coerceIn(1, 50),
                highQualityReview = highQualityReview
            )
        )
        promptProfile?.let { savePromptProfileInTransaction(projectId, it) }
        books.setPreferredEdition(bookId, targetEditionId)
        val old = progressDao.get(bookId) ?: ReaderProgressEntity(bookId, sourceEditionId, null, null)
        progressDao.upsert(old.copy(preferredEditionId = targetEditionId, updatedAt = System.currentTimeMillis()))
        projectId
    }

    suspend fun saveReaderProgress(progress: ReaderProgressEntity): ReaderProgressEntity? = database.withTransaction {
        val book = books.getBook(progress.bookId) ?: return@withTransaction null
        val editions = books.getEditions(progress.bookId)
        val fallbackEditionId = book.primaryEditionId?.takeIf { primary -> editions.any { it.id == primary } }
            ?: editions.firstOrNull { it.type == EditionType.IMPORTED.name }?.id
            ?: editions.firstOrNull()?.id
        val preferredEditionId = progress.preferredEditionId?.takeIf { preferred -> editions.any { it.id == preferred } }
            ?: fallbackEditionId
        val logicalChapter = progress.logicalChapterId?.let { chapterId ->
            books.getLogicalChapter(chapterId)?.takeIf { it.bookId == progress.bookId }
        }
        val logicalSegmentId = progress.logicalSegmentId?.takeIf { segmentId ->
            logicalChapter != null && books.getLogicalSegments(logicalChapter.id).any { it.id == segmentId }
        }
        val sanitized = progress.copy(
            preferredEditionId = preferredEditionId,
            logicalChapterId = logicalChapter?.id,
            logicalSegmentId = logicalSegmentId,
            segmentOffset = progress.segmentOffset.coerceAtLeast(0),
            fontSizeSp = progress.fontSizeSp.takeIf { it.isFinite() }?.coerceIn(12f, 40f) ?: 18f,
            fontFamily = runCatching { ReaderFontFamily.valueOf(progress.fontFamily) }
                .getOrDefault(ReaderFontFamily.SYSTEM).name,
            letterSpacingSp = progress.letterSpacingSp.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: 0f,
            lineSpacingMultiplier = progress.lineSpacingMultiplier.takeIf { it.isFinite() }?.coerceIn(1.0f, 3.0f) ?: 1.35f,
            paragraphSpacingDp = progress.paragraphSpacingDp.takeIf { it.isFinite() }?.coerceIn(0f, 64f) ?: 10f,
            pageMarginDp = progress.pageMarginDp.takeIf { it.isFinite() }?.coerceIn(4f, 96f) ?: 18f,
            readerBackground = runCatching { ReaderBackground.valueOf(progress.readerBackground) }
                .getOrDefault(ReaderBackground.SYSTEM).name,
            updatedAt = System.currentTimeMillis()
        )
        progressDao.upsert(sanitized)
        sanitized
    }

    suspend fun selectReadingEdition(bookId: Long, editionId: Long) {
        database.withTransaction {
            books.getBook(bookId) ?: return@withTransaction
            val edition = books.getEdition(editionId) ?: return@withTransaction
            require(edition.bookId == bookId)
            books.setPreferredEdition(bookId, editionId)
            val current = progressDao.get(bookId) ?: ReaderProgressEntity(bookId, editionId, null, null)
            progressDao.upsert(current.copy(preferredEditionId = editionId, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteEdition(bookId: Long, editionId: Long) {
        val deleted = database.withTransaction {
            val edition = books.getEdition(editionId) ?: return@withTransaction null
            require(edition.bookId == bookId) { "Edition does not belong to this book" }
            val book = books.getBook(bookId) ?: error("Book not found")
            require(editionId != book.primaryEditionId && edition.type != EditionType.IMPORTED.name) {
                "The original imported Edition cannot be deleted"
            }
            val editions = books.getEditions(bookId)
            require(editions.none { it.id != editionId && it.sourceEditionId == editionId }) {
                "Delete Editions derived from this Edition first"
            }
            require(projects.getBySourceEdition(editionId).isEmpty()) {
                "Delete translation projects that use this Edition as their source first"
            }
            val currentBook = books.getBook(bookId) ?: error("Book not found")
            val remainingEditions = editions.filter { it.id != editionId }
            require(remainingEditions.isNotEmpty()) { "A book must keep at least one Edition" }
            val fallback = currentBook.primaryEditionId?.takeIf { it != editionId && remainingEditions.any { edition -> edition.id == it } }
                ?: remainingEditions.firstOrNull()?.id
            val currentProgress = progressDao.get(bookId)
            if (currentBook.preferredReadingEditionId == editionId && fallback != null) {
                books.setPreferredEdition(bookId, fallback)
            }
            if (currentProgress?.preferredEditionId == editionId && fallback != null) {
                progressDao.upsert(currentProgress.copy(preferredEditionId = fallback, updatedAt = System.currentTimeMillis()))
            }
            // Remove target-bound projects explicitly before the Edition row. This makes the
            // dependent runs, glossary, memory, and cache cleanup deterministic even if a user
            // opens a database created by an older schema whose foreign-key actions differ.
            projects.deleteByTargetEdition(editionId)
            books.deleteEdition(editionId)
            edition
        } ?: return
        files.deleteEdition(deleted.bookId, deleted.id)
    }

    suspend fun saveManualRevision(editionSegmentId: Long, text: String, note: String? = null): Long {
        require(text.isNotBlank()) { "Revision text must not be blank" }
        require(text.length <= MAX_REVISION_TEXT_CHARS) { "Revision text is too long" }
        require(isSafeRevisionText(text)) { "Revision text contains unsupported control characters" }
        val normalizedNote = note?.trim()?.take(MAX_REVISION_NOTE_CHARS)
        require(normalizedNote == null || isSafeRevisionText(normalizedNote)) {
            "Revision note contains unsupported control characters"
        }
        return books.insertRevision(
            SegmentRevisionEntity(
                editionSegmentId = editionSegmentId,
                revisionType = RevisionType.MANUAL_EDIT.name,
                text = text,
                note = normalizedNote
            )
        )
    }

    suspend fun renameBook(bookId: Long, title: String) {
        require(books.getBook(bookId) != null) { "Book not found" }
        val normalizedTitle = title.trim().take(300)
        require(normalizedTitle.isNotBlank()) { "Book title must not be blank" }
        require(isSafeText(normalizedTitle)) { "Book title contains unsupported control characters" }
        books.rename(bookId, normalizedTitle)
    }

    suspend fun updateBookMetadata(bookId: Long, title: String, author: String, description: String, language: String) {
        require(books.getBook(bookId) != null) { "Book not found" }
        val normalizedTitle = title.trim().take(300)
        val normalizedAuthor = author.trim().take(300)
        val normalizedDescription = description.trim().take(3_000)
        val normalizedLanguage = language.trim().take(80)
        require(normalizedTitle.isNotBlank()) { "Book title must not be blank" }
        require(normalizedLanguage.isNotBlank()) { "Book language must not be blank" }
        require(isSafeText(normalizedTitle) && isSafeText(normalizedAuthor) &&
            isSafeText(normalizedDescription, allowLineBreaks = true) && isSafeText(normalizedLanguage)
        ) {
            "Book metadata contains unsupported control characters"
        }
        books.updateMetadata(bookId, normalizedTitle, normalizedAuthor, normalizedDescription, normalizedLanguage)
    }
    suspend fun updateCover(bookId: Long, path: String) {
        require(books.getBook(bookId) != null) { "Book not found" }
        val cover = File(path).canonicalFile
        val coverDir = files.coverDir(bookId).canonicalFile
        require(
            cover.isFile && cover.parentFile == coverDir && cover.nameWithoutExtension == "cover" &&
                cover.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
        ) {
            "Cover path must point to a file inside the book cover directory"
        }
        books.updateCover(bookId, cover.absolutePath)
    }
    suspend fun updateShelfOrder(bookId: Long, order: Int) = books.updateShelfOrder(bookId, order)
    suspend fun removeFromShelf(bookId: Long) = books.setHidden(bookId, true)
    suspend fun restoreToShelf(bookId: Long) = books.setHidden(bookId, false)

    suspend fun deletePermanently(bookId: Long) {
        database.withTransaction {
            // Delete project rows explicitly before the book/Edition cascade. The current schema
            // also declares cascading foreign keys, but the explicit order keeps cleanup safe for
            // databases upgraded from an older schema and makes all child rows observable as one
            // transaction.
            projects.deleteByBook(bookId)
            books.deletePermanently(bookId)
        }
        files.deleteBook(bookId)
    }

    private data class ReaderInputs(
        val book: BookEntity?,
        val editions: List<EditionEntity>,
        val chapters: List<LogicalChapterEntity>,
        val progress: ReaderProgressEntity?
    )

    private data class EffectiveSegment(val editionSegmentIds: List<Long>, val text: String)

    private suspend fun validateTranslationConfiguration(
        bookId: Long,
        mode: String,
        rangeStart: Int?,
        rangeEnd: Int?
    ) {
        if (mode != TranslationMode.CHAPTER_RANGE.name) return
        require(rangeStart != null && rangeEnd != null && rangeStart > 0 && rangeEnd >= rangeStart) {
            "Invalid chapter range"
        }
        val chapterCount = books.getChapters(bookId).size
        require(chapterCount > 0 && rangeEnd <= chapterCount) {
            "Chapter range exceeds the available chapters"
        }
    }

    /** Keeps hand-edited glossary rows bounded and valid before they reach Room or prompts. */
    private suspend fun normalizeLexiconEntry(entry: LexiconEntryEntity): LexiconEntryEntity {
        require(projects.get(entry.translationProjectId) != null) { "Translation project not found" }
        val source = entry.sourceTerm.trim()
        val target = entry.targetTerm.trim()
        val category = entry.category.trim().uppercase(Locale.ROOT).ifBlank { TermCategory.CUSTOM.name }
        val kind = entry.kind.trim().uppercase(Locale.ROOT).ifBlank { LexiconKind.PROPER_NOUN.name }
        val sourceType = entry.source.trim().uppercase(Locale.ROOT).ifBlank { LexiconSource.MANUAL.name }
        val reviewStatus = entry.reviewStatus.trim().uppercase(Locale.ROOT).ifBlank { ReviewStatus.CONFIRMED.name }
        require(source.isNotBlank() && source.length <= MAX_LEXICON_SOURCE_CHARS) {
            "Source term must contain 1-$MAX_LEXICON_SOURCE_CHARS characters"
        }
        require(target.isNotBlank() && target.length <= MAX_LEXICON_TARGET_CHARS) {
            "Target term must contain 1-$MAX_LEXICON_TARGET_CHARS characters"
        }
        require(notesAreSafe(source) && notesAreSafe(target)) {
            "Glossary terms must not contain control characters"
        }
        require(category in TermCategory.values().map { it.name }) { "Unknown glossary category" }
        require(kind in LexiconKind.values().map { it.name }) { "Unknown glossary kind" }
        require(sourceType in LexiconSource.values().map { it.name }) { "Unknown glossary source" }
        require(reviewStatus in ReviewStatus.values().map { it.name }) { "Unknown glossary review status" }
        val aliases = entry.aliases.trim()
        val notes = entry.notes.trim()
        require(aliases.length <= MAX_LEXICON_ALIASES_CHARS) { "Glossary aliases are too long" }
        require(notes.length <= MAX_LEXICON_NOTES_CHARS) { "Glossary notes are too long" }
        require(notesAreSafe(aliases) && notesAreSafe(notes)) {
            "Glossary aliases and notes must not contain control characters"
        }
        return entry.copy(
            sourceTerm = source,
            targetTerm = target,
            category = category,
            kind = kind,
            aliases = aliases,
            notes = notes,
            source = sourceType,
            reviewStatus = reviewStatus,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun upsertNormalizedLexiconEntry(entry: LexiconEntryEntity): Long {
        val dao = database.lexiconV2Dao()
        if (entry.id != 0L) {
            require(dao.getAll(entry.translationProjectId).any { it.id == entry.id }) {
                "Glossary entry does not belong to the translation project"
            }
        }
        val existing = dao.getBySourceTerm(entry.translationProjectId, entry.sourceTerm)
        if (existing != null && (entry.id == 0L || entry.id != existing.id)) {
            require(entry.id == 0L) { "A glossary entry for this source term already exists" }
            dao.update(entry.copy(id = existing.id, createdAt = existing.createdAt))
            return existing.id
        }
        return dao.upsert(entry)
    }

    private fun notesAreSafe(value: String): Boolean = value.none { it.code < 32 || it.code == 127 }

    private fun isSafeText(value: String, allowLineBreaks: Boolean = false): Boolean = value.none {
        it.code == 0 || it.code == 127 ||
            (it.code < 32 && (!allowLineBreaks || it != '\n' && it != '\r' && it != '\t'))
    }

    private fun isSafeRevisionText(value: String): Boolean = value.none {
        it.code == 0 || it.code == 127 || (it.code < 32 && it != '\n' && it != '\r' && it != '\t')
    }

    private companion object {
        const val MAX_REVISION_TEXT_CHARS = 1_000_000
        const val MAX_REVISION_NOTE_CHARS = 500
        const val MAX_LEXICON_SOURCE_CHARS = 120
        const val MAX_LEXICON_TARGET_CHARS = 160
        const val MAX_LEXICON_ALIASES_CHARS = 500
        const val MAX_LEXICON_NOTES_CHARS = 300
        const val MAX_PROMPT_TEMPLATE_CHARS = 24_000
    }
}
