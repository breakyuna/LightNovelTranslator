package com.breakyuna.noveltranslator.core.book

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.core.parser.EpubParser
import com.breakyuna.noveltranslator.core.parser.ParsedChapter
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class AcquiredChapter(val title: String, val renderedText: String, val sourceUrl: String? = null)
data class AcquiredBook(
    val title: String,
    val author: String? = null,
    val coverPath: String? = null,
    val sourceUrl: String? = null,
    val chapters: List<AcquiredChapter>,
    val acquisitionType: AcquisitionType
)

/** Future web capture and local import both terminate at this normalized boundary. */
fun interface AcquisitionSource {
    suspend fun acquire(): AcquiredBook
}

class BookImporter(
    private val database: AppDatabase,
    private val files: BookFileManager
) {
    private data class StagedChapter(val chapterIndex: Int, val title: String, val fileName: String, val wordCount: Int)
    private data class PreparedChapterData(
        val chapterIndex: Int,
        val title: String,
        val content: String,
        val wordCount: Int,
        val fileName: String
    )
    private data class NormalizedAcquiredBook(
        val title: String,
        val author: String,
        val coverPath: String?,
        val language: String,
        val chapters: List<AcquiredChapter>
    )

    suspend fun import(
        fileName: String,
        sourceFile: File,
        originalLanguage: String = "Auto",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ): Long {
        val isEpub = fileName.endsWith(".epub", true)
        require(isEpub || fileName.endsWith(".txt", true)) {
            "Unsupported book format; only .txt and .epub files are supported"
        }
        val effectiveRegex = customRegex?.trim()?.takeIf(String::isNotBlank)
        if (!isEpub) effectiveRegex?.let(TxtParser::validateChapterRegex)
        val normalizedLanguage = normalizeMetadata(originalLanguage, "Auto", 80)
        var title = normalizeMetadata(fileName.substringBeforeLast('.'), "Imported novel", 300)
        var author = "Unknown"
        var coverPath: String? = null
        var temporaryBookId: Long? = null
        try {
            val bookId = database.bookDao().insert(BookEntity(title = title, author = author, originalLanguage = normalizedLanguage))
            temporaryBookId = bookId
            val preservedSource = sourceFile.inputStream().use {
                files.saveImportedSource(bookId, fileName, it, BookFileManager.MAX_IMPORT_BYTES)
            }
            if (!isEpub) {
                persistTxtStreaming(
                    bookId = bookId,
                    title = title,
                    author = author,
                    originalLanguage = normalizedLanguage,
                    sourceFile = preservedSource,
                    customRegex = effectiveRegex,
                    cropTableOfContents = cropTableOfContents
                )
                return bookId
            }
            val parsed: List<ParsedChapter> = run {
                val epub = EpubParser.parseEpubFile(
                    preservedSource,
                    files.sharedImagesDir(bookId),
                    cropTableOfContents = cropTableOfContents
                )
                title = epub.title.ifBlank { title }
                author = epub.author.ifBlank { author }
                coverPath = epub.coverFileName
                    ?.let { File(files.sharedImagesDir(bookId), it) }
                    ?.takeIf(File::isFile)
                    ?.let { extractedCover ->
                        extractedCover.inputStream().use { input ->
                            files.saveCover(bookId, extractedCover.name, input).absolutePath
                        }
                    }
                epub.chapters
            }
            require(parsed.isNotEmpty()) { "No readable chapters were found" }
            persistNormalized(
                bookId = bookId,
                title = title,
                author = author,
                coverPath = coverPath,
                originalLanguage = normalizedLanguage,
                editionName = "Imported EPUB",
                chapters = parsed.map { AcquiredChapter(it.title, it.content) }
            )
            return bookId
        } catch (error: Throwable) {
            temporaryBookId?.let {
                database.bookDao().deletePermanently(it)
                files.deleteBook(it)
            }
            throw error
        }
    }

    /** Rebuilds the original Edition's logical chapters from its preserved source file. */
    suspend fun reSplit(
        bookId: Long,
        regexPattern: String = "",
        cropTableOfContents: Boolean = false
    ): Int {
        val dao = database.bookDao()
        val book = dao.getBook(bookId) ?: error("Book not found")
        val primaryEditionId = book.primaryEditionId ?: error("The book has no original Edition")
        val originalEdition = dao.getEdition(primaryEditionId)
            ?: error("The original Edition was not found")
        require(originalEdition.bookId == bookId && originalEdition.type == EditionType.IMPORTED.name) {
            "Only the original imported Edition can be re-split"
        }

        val editions = dao.getEditions(bookId)
        require(editions.all { it.id == originalEdition.id }) {
            "Please remove translation Editions before re-splitting the original book"
        }

        val sourceFile = files.sourceDir(bookId)
            .listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.firstOrNull()
            ?: error("The preserved source file was not found")

        val isEpub = sourceFile.extension.equals("epub", ignoreCase = true)
        val detection = if (!isEpub) runCatching { TxtParser.detectStructure(sourceFile, regexPattern.trim().takeIf(String::isNotBlank)) }.getOrNull() else null
        if (detection != null) {
            SystemLogger.info(
                "CHAPTER_SPLIT",
                "书籍分章检测: pattern=${detection.detectedPattern}, confidence=${detection.confidence.toInt()}%, chapters=${detection.headings.size}, warnings=${detection.warnings.joinToString()}"
            )
        }
        val effectiveRegex = regexPattern.trim().takeIf(String::isNotBlank)
            ?: if (isEpub) TxtParser.REGEX_CHINESE else TxtParser.inferChapterRegex(sourceFile, book.originalLanguage)
        val progress = database.readerProgressDao().get(bookId)
        if (isEpub) {
            val chapters = EpubParser.parseEpubFile(
                epubFile = sourceFile,
                imagesOutputDirectory = files.sharedImagesDir(bookId),
                cropTableOfContents = cropTableOfContents
            ).chapters.iterator()
            return rebuildOriginalEdition(bookId, originalEdition, chapters, progress)
        }

        val reader = TxtParser.openDetectedReader(sourceFile)
        return try {
            val chapters = TxtParser.chapterSequence(
                reader = reader,
                regexPattern = effectiveRegex,
                cropTableOfContents = cropTableOfContents || (detection?.detectedTocRange != null)
            ).iterator()
            rebuildOriginalEdition(bookId, originalEdition, chapters, progress)
        } finally {
            reader.close()
        }
    }

    /**
     * Applies an explicitly confirmed AI chapter-split result to the original Edition.
     * Translation Editions must not exist because rebuilding LogicalChapter IDs would invalidate
     * their mappings; callers should show the returned preview and ask for confirmation first.
     */
    suspend fun applyAiChapterSplit(bookId: Long, chapters: List<ParsedChapter>): Int {
        require(chapters.isNotEmpty()) { "AI splitter returned no chapters" }
        val dao = database.bookDao()
        val book = dao.getBook(bookId) ?: error("Book not found")
        val primaryEditionId = book.primaryEditionId ?: error("The book has no original Edition")
        val originalEdition = dao.getEdition(primaryEditionId)
            ?: error("The original Edition was not found")
        require(originalEdition.bookId == bookId && originalEdition.type == EditionType.IMPORTED.name) {
            "Only the original imported Edition can be re-split"
        }
        require(dao.getEditions(bookId).all { it.id == originalEdition.id }) {
            "Please remove translation Editions before applying an AI chapter split"
        }
        val progress = database.readerProgressDao().get(bookId)
        val normalized = chapters.mapIndexed { index, chapter ->
            chapter.copy(index = index + 1, title = chapter.title.trim().ifBlank { "Chapter ${index + 1}" })
        }
        return rebuildOriginalEdition(bookId, originalEdition, normalized.iterator(), progress)
    }

    private suspend fun rebuildOriginalEdition(
        bookId: Long,
        originalEdition: EditionEntity,
        chapters: Iterator<ParsedChapter>,
        progress: ReaderProgressEntity?
    ): Int {
        require(chapters.hasNext()) { "No readable chapters were found" }
        val now = System.currentTimeMillis()
        val stagingDir = files.createEditionChapterStagingDir(bookId, originalEdition.id)
        val staged = mutableListOf<StagedChapter>()
        var backupDir: File? = null
        try {
            var chapterCount = 0
            while (chapters.hasNext()) {
                val parsed = chapters.next()
                chapterCount++
                val normalized = normalizeChapter(
                    AcquiredChapter(parsed.title, parsed.content),
                    chapterCount
                )
                val fileName = files.saveStagedEditionChapter(
                    stagingDir,
                    chapterCount,
                    normalized.title,
                    normalized.renderedText
                )
                staged += StagedChapter(
                    chapterCount,
                    normalized.title,
                    fileName,
                    TxtParser.countWords(normalized.renderedText)
                )
            }
            require(chapterCount > 0) { "No readable chapters were found" }

            // Swap files only after every chapter has been written successfully. If the DB
            // transaction fails, restore the previous directory before exposing the error.
            backupDir = files.swapEditionChapterDirectory(bookId, originalEdition.id, stagingDir)
            val rebuiltCount = try {
                database.withTransaction {
                    val dao = database.bookDao()
                    dao.deleteLogicalChaptersByBook(bookId)
                }
                val batchBuffer = mutableListOf<PreparedChapterData>()
                for (chapter in staged) {
                    val content = files.readEditionChapter(
                        bookId,
                        originalEdition.id,
                        chapter.fileName
                    )
                    batchBuffer.add(
                        PreparedChapterData(
                            chapterIndex = chapter.chapterIndex,
                            title = chapter.title,
                            content = content,
                            wordCount = chapter.wordCount,
                            fileName = chapter.fileName
                        )
                    )
                    if (batchBuffer.size >= CHAPTER_TRANSACTION_BATCH_SIZE) {
                        persistChaptersBatch(bookId, originalEdition.id, batchBuffer)
                        batchBuffer.clear()
                    }
                }
                if (batchBuffer.isNotEmpty()) {
                    persistChaptersBatch(bookId, originalEdition.id, batchBuffer)
                    batchBuffer.clear()
                }

                database.withTransaction {
                    val dao = database.bookDao()
                    dao.updateEdition(originalEdition.copy(isComplete = true, updatedAt = now))
                    dao.update(
                        (dao.getBook(bookId) ?: error("Book not found")).copy(
                            primaryEditionId = originalEdition.id,
                            preferredReadingEditionId = originalEdition.id,
                            updatedAt = now
                        )
                    )
                    database.readerProgressDao().upsert(
                        (progress ?: ReaderProgressEntity(bookId, originalEdition.id, null, null)).copy(
                            preferredEditionId = originalEdition.id,
                            logicalChapterId = null,
                            logicalSegmentId = null,
                            segmentOffset = 0,
                            updatedAt = now
                        )
                    )
                }
                chapterCount
            } catch (error: Throwable) {
                files.rollbackEditionChapterSwap(bookId, originalEdition.id, backupDir)
                throw error
            }
            // The database now points at the new directory. Cleanup must not roll the files back
            // after a successful transaction, otherwise a cleanup failure would create a mismatch.
            files.finalizeEditionChapterSwap(backupDir)
            return rebuiltCount
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun importAcquired(book: AcquiredBook, language: String = "Auto"): Long {
        val normalized = normalizeAcquiredBook(book, language)
        val bookId = database.bookDao().insert(
            BookEntity(
                title = normalized.title,
                author = normalized.author,
                coverPath = null,
                originalLanguage = normalized.language
            )
        )
        try {
            val storedCoverPath = normalized.coverPath?.let { sourcePath ->
                val source = File(sourcePath).canonicalFile
                require(source.isFile && source.length() <= BookFileManager.MAX_COVER_BYTES) {
                    "Acquired cover image is missing or too large"
                }
                require(source.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")) {
                    "Acquired cover image must be JPG, PNG, or WebP"
                }
                source.inputStream().use { input -> files.saveCover(bookId, source.name, input).absolutePath }
            }
            val normalizedSource = normalized.chapters.joinToString("\n\n") { "${it.title}\n\n${it.renderedText}" }
            files.saveImportedSource(bookId, "acquired_source.txt", normalizedSource.toByteArray(Charsets.UTF_8), BookFileManager.MAX_IMPORT_BYTES)
            persistNormalized(
                bookId = bookId,
                title = normalized.title,
                author = normalized.author,
                coverPath = storedCoverPath,
                originalLanguage = normalized.language,
                editionName = book.acquisitionType.name,
                chapters = normalized.chapters
            )
            return bookId
        } catch (error: Throwable) {
            database.bookDao().deletePermanently(bookId)
            files.deleteBook(bookId)
            throw error
        }
    }

    private suspend fun persistNormalized(
        bookId: Long,
        title: String,
        author: String,
        coverPath: String? = null,
        originalLanguage: String,
        editionName: String,
        chapters: List<AcquiredChapter>
    ) {
        val normalizedTitle = normalizeMetadata(title, "Imported novel", 300)
        val normalizedAuthor = normalizeMetadata(author, "Unknown", 300)
        val normalizedLanguage = normalizeMetadata(originalLanguage, "Auto", 80)
        val normalizedEditionName = normalizeMetadata(editionName, "Imported edition", 200)
        val normalizedCoverPath = normalizeStoredCoverPath(bookId, coverPath)
        val normalizedChapters = chapters.mapIndexed { index, chapter -> normalizeChapter(chapter, index + 1) }
        val editionId = database.withTransaction {
            database.bookDao().insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = normalizedEditionName,
                    type = EditionType.IMPORTED.name,
                    language = normalizedLanguage,
                    isComplete = false
                )
            )
        }
        val batchBuffer = mutableListOf<PreparedChapterData>()
        normalizedChapters.forEachIndexed { index, chapter ->
            val chapterIndex = index + 1
            val storedFileName = files.saveEditionChapter(
                bookId, editionId, chapterIndex, chapter.title, chapter.renderedText
            )
            batchBuffer.add(
                PreparedChapterData(
                    chapterIndex = chapterIndex,
                    title = chapter.title,
                    content = chapter.renderedText,
                    wordCount = TxtParser.countWords(chapter.renderedText),
                    fileName = storedFileName
                )
            )
            if (batchBuffer.size >= CHAPTER_TRANSACTION_BATCH_SIZE) {
                persistChaptersBatch(bookId, editionId, batchBuffer)
                batchBuffer.clear()
            }
        }
        if (batchBuffer.isNotEmpty()) {
            persistChaptersBatch(bookId, editionId, batchBuffer)
            batchBuffer.clear()
        }
        finalizeImport(bookId, editionId, normalizedTitle, normalizedAuthor, normalizedCoverPath)
    }

    private suspend fun persistTxtStreaming(
        bookId: Long,
        title: String,
        author: String,
        originalLanguage: String,
        sourceFile: File,
        customRegex: String?,
        cropTableOfContents: Boolean
    ) {
        val editionId = database.withTransaction {
            database.bookDao().insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = "Imported TXT",
                    type = EditionType.IMPORTED.name,
                    language = originalLanguage,
                    isComplete = false
                )
            )
        }
        var chapterCount = 0
        val detection = runCatching { TxtParser.detectStructure(sourceFile, customRegex) }.getOrNull()
        if (detection != null) {
            SystemLogger.info(
                "CHAPTER_SPLIT",
                "导入 TXT 分章检测: pattern=${detection.detectedPattern}, confidence=${detection.confidence.toInt()}%, chapters=${detection.headings.size}"
            )
        }
        val effectiveRegex = customRegex?.takeIf(String::isNotBlank)
            ?: TxtParser.inferChapterRegex(sourceFile, originalLanguage)
        val batchBuffer = mutableListOf<PreparedChapterData>()
        TxtParser.openDetectedReader(sourceFile).use { reader ->
            val chapters = TxtParser.chapterSequence(
                reader,
                effectiveRegex,
                cropTableOfContents = cropTableOfContents || (detection?.detectedTocRange != null)
            ).iterator()
            while (chapters.hasNext()) {
                val parsed = chapters.next()
                chapterCount++
                val normalized = normalizeChapter(AcquiredChapter(parsed.title, parsed.content), chapterCount)
                val storedFileName = files.saveEditionChapter(
                    bookId, editionId, chapterCount, normalized.title, normalized.renderedText
                )
                batchBuffer.add(
                    PreparedChapterData(
                        chapterIndex = chapterCount,
                        title = normalized.title,
                        content = normalized.renderedText,
                        wordCount = TxtParser.countWords(normalized.renderedText),
                        fileName = storedFileName
                    )
                )
                if (batchBuffer.size >= CHAPTER_TRANSACTION_BATCH_SIZE) {
                    persistChaptersBatch(bookId, editionId, batchBuffer)
                    batchBuffer.clear()
                }
            }
            if (batchBuffer.isNotEmpty()) {
                persistChaptersBatch(bookId, editionId, batchBuffer)
                batchBuffer.clear()
            }
        }
        require(chapterCount > 0) { "No readable chapters were found" }
        finalizeImport(
            bookId,
            editionId,
            normalizeMetadata(title, "Imported novel", 300),
            normalizeMetadata(author, "Unknown", 300),
            null
        )
    }

    private fun normalizeAcquiredBook(book: AcquiredBook, language: String): NormalizedAcquiredBook {
        val title = normalizeMetadata(book.title, "Imported novel", 300)
        val author = normalizeMetadata(book.author, "Unknown", 300)
        val normalizedLanguage = normalizeMetadata(language, "Auto", 80)
        val coverPath = normalizeCoverPath(book.coverPath)
        val chapters = book.chapters.mapIndexed { index, chapter -> normalizeChapter(chapter, index + 1) }
        require(chapters.isNotEmpty()) { "No readable chapters were found" }
        val source = chapters.joinToString("\n\n") { "${it.title}\n\n${it.renderedText}" }
        require(source.toByteArray(Charsets.UTF_8).size.toLong() <= BookFileManager.MAX_IMPORT_BYTES) {
            "Acquired book exceeds the 100 MB import limit"
        }
        return NormalizedAcquiredBook(
            title = title,
            author = author,
            coverPath = coverPath,
            language = normalizedLanguage,
            chapters = chapters
        )
    }

    private fun normalizeCoverPath(path: String?): String? {
        val normalized = path?.trim()?.take(2_048)?.takeIf(String::isNotBlank)
        require(normalized == null || normalized.none { it.code < 32 || it.code == 127 }) {
            "Cover path contains unsupported control characters"
        }
        return normalized
    }

    private fun normalizeStoredCoverPath(bookId: Long, path: String?): String? {
        val normalized = normalizeCoverPath(path) ?: return null
        val cover = File(normalized).canonicalFile
        val coverDir = files.coverDir(bookId).canonicalFile
        require(
            cover.isFile && cover.parentFile == coverDir && cover.nameWithoutExtension == "cover" &&
                cover.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
        ) {
            "Cover path must point to a stored cover image"
        }
        return cover.absolutePath
    }

    private fun normalizeChapter(chapter: AcquiredChapter, index: Int): AcquiredChapter {
        val title = normalizeMetadata(chapter.title, "Chapter $index", 300)
        val content = chapter.renderedText.replace("\r\n", "\n").replace('\r', '\n')
        require(content.isNotBlank()) { "Chapter $index has no readable text" }
        require(isSafeImportedText(content)) { "Chapter $index contains unsupported control characters" }
        return chapter.copy(title = title, renderedText = content)
    }

    private fun normalizeMetadata(value: String?, fallback: String, maxLength: Int): String {
        val normalized = value?.trim()?.take(maxLength).orEmpty()
        require(isSafeMetadataText(normalized)) { "Imported metadata contains unsupported control characters" }
        return normalized.ifBlank { fallback }
    }

    private fun isSafeMetadataText(value: String): Boolean = value.none { it.code < 32 || it.code == 127 }

    private fun isSafeImportedText(value: String): Boolean = value.none {
        it.code == 0 || it.code == 127 || (it.code < 32 && it != '\n' && it != '\r' && it != '\t')
    }

    private suspend fun persistChaptersBatch(
        bookId: Long,
        editionId: Long,
        chapters: List<PreparedChapterData>
    ) {
        if (chapters.isEmpty()) return
        database.withTransaction {
            val dao = database.bookDao()
            for (chapter in chapters) {
                val parts = splitSegments(chapter.content)
                val logicalChapterId = dao.insertLogicalChapter(
                    LogicalChapterEntity(bookId = bookId, chapterIndex = chapter.chapterIndex, canonicalTitle = chapter.title)
                )
                val editionChapterId = dao.insertEditionChapter(
                    EditionChapterEntity(
                        editionId = editionId,
                        logicalChapterId = logicalChapterId,
                        title = chapter.title,
                        contentFileName = chapter.fileName,
                        wordCount = chapter.wordCount
                    )
                )
                var offset = 0
                while (offset < parts.size) {
                    val end = minOf(offset + SEGMENT_BATCH_SIZE, parts.size)
                    val batch = parts.subList(offset, end)
                    val logicalIds = dao.insertLogicalSegments(
                        batch.indices.map { index ->
                            LogicalSegmentEntity(logicalChapterId = logicalChapterId, segmentIndex = offset + index)
                        }
                    )
                    val editionIds = dao.insertEditionSegments(
                        batch.mapIndexed { index, text ->
                            EditionSegmentEntity(
                                editionChapterId = editionChapterId,
                                segmentIndex = offset + index,
                                baseText = text,
                                sourceHash = sha256(text)
                            )
                        }
                    )
                    dao.insertMappings(logicalIds.zip(editionIds) { logicalId, editionSegmentId ->
                        EditionSegmentMappingEntity(logicalId, editionSegmentId)
                    })
                    offset = end
                }
            }
        }
    }

    private suspend fun finalizeImport(
        bookId: Long,
        editionId: Long,
        title: String,
        author: String,
        coverPath: String?
    ) = database.withTransaction {
        val dao = database.bookDao()
        val edition = dao.getEdition(editionId) ?: error("Imported edition not found")
        dao.updateEdition(edition.copy(isComplete = true, updatedAt = System.currentTimeMillis()))
        dao.update(
            dao.getBook(bookId)!!.copy(
                title = title,
                author = author,
                coverPath = coverPath,
                primaryEditionId = editionId,
                preferredReadingEditionId = editionId,
                updatedAt = System.currentTimeMillis()
            )
        )
        database.readerProgressDao().upsert(
            ReaderProgressEntity(
                bookId = bookId,
                preferredEditionId = editionId,
                logicalChapterId = null,
                logicalSegmentId = null
            )
        )
    }

    companion object {
        private const val CHAPTER_TRANSACTION_BATCH_SIZE = 50
        private const val SEGMENT_BATCH_SIZE = 500

        fun splitSegments(text: String): List<String> = text
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(text.trim()) }

        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
