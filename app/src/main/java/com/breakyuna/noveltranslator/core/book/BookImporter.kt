package com.breakyuna.noveltranslator.core.book

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.core.parser.EpubParser
import com.breakyuna.noveltranslator.core.parser.ParsedChapter
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.*
import java.io.File
import java.security.MessageDigest

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
    suspend fun import(
        fileName: String,
        sourceFile: File,
        originalLanguage: String = "Auto",
        customRegex: String? = null,
        cropTableOfContents: Boolean = false
    ): Long {
        val isEpub = fileName.endsWith(".epub", true) || fileName.endsWith(".equb", true)
        val effectiveRegex = customRegex?.trim()?.takeIf(String::isNotBlank)
        if (!isEpub) effectiveRegex?.let(TxtParser::validateChapterRegex)
        var title = fileName.substringBeforeLast('.').trim().ifBlank { "Imported novel" }
        var author = "Unknown"
        var coverPath: String? = null
        var temporaryBookId: Long? = null
        try {
            val bookId = database.bookDao().insert(BookEntity(title = title, author = author, originalLanguage = originalLanguage))
            temporaryBookId = bookId
            val preservedSource = sourceFile.inputStream().use {
                files.saveImportedSource(bookId, fileName, it, 100L * 1024L * 1024L)
            }
            if (!isEpub) {
                persistTxtStreaming(
                    bookId = bookId,
                    title = title,
                    author = author,
                    originalLanguage = originalLanguage,
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
                originalLanguage = originalLanguage,
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
        regexPattern: String = TxtParser.REGEX_CHINESE,
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
            ?.sortedBy { it.name.lowercase() }
            ?.firstOrNull()
            ?: error("The preserved source file was not found")

        val isEpub = sourceFile.extension.equals("epub", ignoreCase = true) ||
            sourceFile.extension.equals("equb", ignoreCase = true)
        val effectiveRegex = regexPattern.trim().ifBlank { TxtParser.REGEX_CHINESE }
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
                cropTableOfContents = cropTableOfContents
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
        return database.withTransaction {
            val dao = database.bookDao()
            dao.deleteLogicalChaptersByBook(bookId)
            files.clearEditionChapters(bookId, originalEdition.id)
            var chapterCount = 0
            while (chapters.hasNext()) {
                val parsed = chapters.next()
                chapterCount++
                persistChapterInTransaction(
                    bookId = bookId,
                    editionId = originalEdition.id,
                    chapterIndex = chapterCount,
                    title = parsed.title,
                    content = parsed.content,
                    wordCount = parsed.wordCount
                )
            }
            require(chapterCount > 0) { "No readable chapters were found" }
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
            chapterCount
        }
    }

    suspend fun importAcquired(book: AcquiredBook, language: String = "Auto"): Long {
        val bookId = database.bookDao().insert(
            BookEntity(
                title = book.title,
                author = book.author ?: "Unknown",
                coverPath = book.coverPath,
                originalLanguage = language
            )
        )
        try {
            val normalizedSource = book.chapters.joinToString("\n\n") { "${it.title}\n\n${it.renderedText}" }
            files.saveImportedSource(bookId, "acquired_source.txt", normalizedSource.toByteArray(Charsets.UTF_8), 100L * 1024L * 1024L)
            persistNormalized(
                bookId = bookId,
                title = book.title,
                author = book.author ?: "Unknown",
                coverPath = book.coverPath,
                originalLanguage = language,
                editionName = book.acquisitionType.name,
                chapters = book.chapters
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
        val editionId = database.withTransaction {
            database.bookDao().insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = editionName,
                    type = EditionType.IMPORTED.name,
                    language = originalLanguage,
                    isComplete = false
                )
            )
        }
        chapters.forEachIndexed { index, chapter ->
            persistChapter(bookId, editionId, index + 1, chapter)
        }
        finalizeImport(bookId, editionId, title, author, coverPath)
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
        TxtParser.openDetectedReader(sourceFile).use { reader ->
            val chapters = TxtParser.chapterSequence(
                reader,
                customRegex?.takeIf(String::isNotBlank) ?: TxtParser.REGEX_CHINESE,
                cropTableOfContents = cropTableOfContents
            ).iterator()
            while (chapters.hasNext()) {
                val parsed = chapters.next()
                chapterCount++
                persistChapter(bookId, editionId, chapterCount, AcquiredChapter(parsed.title, parsed.content))
            }
        }
        require(chapterCount > 0) { "No readable chapters were found" }
        finalizeImport(bookId, editionId, title, author, null)
    }

    private suspend fun persistChapter(
        bookId: Long,
        editionId: Long,
        chapterIndex: Int,
        chapter: AcquiredChapter
    ) {
        database.withTransaction {
            persistChapterInTransaction(
                bookId = bookId,
                editionId = editionId,
                chapterIndex = chapterIndex,
                title = chapter.title,
                content = chapter.renderedText,
                wordCount = chapter.renderedText.length
            )
        }
    }

    private suspend fun persistChapterInTransaction(
        bookId: Long,
        editionId: Long,
        chapterIndex: Int,
        title: String,
        content: String,
        wordCount: Int
    ) {
        val parts = splitSegments(content)
        val fileName = files.saveEditionChapter(bookId, editionId, chapterIndex, title, content)
        val dao = database.bookDao()
        val logicalChapterId = dao.insertLogicalChapter(
            LogicalChapterEntity(bookId = bookId, chapterIndex = chapterIndex, canonicalTitle = title)
        )
        val editionChapterId = dao.insertEditionChapter(
            EditionChapterEntity(
                editionId = editionId,
                logicalChapterId = logicalChapterId,
                title = title,
                contentFileName = fileName,
                wordCount = wordCount
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
