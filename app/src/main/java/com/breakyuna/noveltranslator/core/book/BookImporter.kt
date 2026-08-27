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
        customRegex: String? = null
    ): Long {
        val isEpub = fileName.endsWith(".epub", true) || fileName.endsWith(".equb", true)
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
                    customRegex = customRegex
                )
                return bookId
            }
            val parsed: List<ParsedChapter> = run {
                val epub = EpubParser.parseEpubFile(preservedSource, files.sharedImagesDir(bookId))
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
        customRegex: String?
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
                customRegex?.takeIf(String::isNotBlank) ?: TxtParser.REGEX_CHINESE
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
        val parts = splitSegments(chapter.renderedText)
        val fileName = files.saveEditionChapter(bookId, editionId, chapterIndex, chapter.title, chapter.renderedText)
        database.withTransaction {
            val dao = database.bookDao()
            val logicalChapterId = dao.insertLogicalChapter(
                LogicalChapterEntity(bookId = bookId, chapterIndex = chapterIndex, canonicalTitle = chapter.title)
            )
            val editionChapterId = dao.insertEditionChapter(
                EditionChapterEntity(
                    editionId = editionId,
                    logicalChapterId = logicalChapterId,
                    title = chapter.title,
                    contentFileName = fileName,
                    wordCount = chapter.renderedText.length
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
