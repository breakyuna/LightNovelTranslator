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
            val parsed: List<ParsedChapter> = if (isEpub) {
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
            } else {
                val (text, _) = TxtParser.detectCharsetAndRead(preservedSource.readBytes())
                TxtParser.splitIntoChapters(text, customRegex?.takeIf(String::isNotBlank) ?: TxtParser.REGEX_CHINESE)
            }
            require(parsed.isNotEmpty()) { "No readable chapters were found" }
            persistNormalized(
                bookId = bookId,
                title = title,
                author = author,
                coverPath = coverPath,
                originalLanguage = originalLanguage,
                editionName = if (isEpub) "Imported EPUB" else "Imported TXT",
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
        database.withTransaction {
            val dao = database.bookDao()
            val editionId = dao.insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = editionName,
                    type = EditionType.IMPORTED.name,
                    language = originalLanguage,
                    isComplete = true
                )
            )
            val logicalChaptersToInsert = chapters.mapIndexed { position, chapter ->
                LogicalChapterEntity(bookId = bookId, chapterIndex = position + 1, canonicalTitle = chapter.title)
            }
            val logicalChapterIds = dao.insertLogicalChapters(logicalChaptersToInsert)

            val allLogicalSegments = mutableListOf<LogicalSegmentEntity>()
            val chapterParts = chapters.map { splitSegments(it.renderedText) }
            chapterParts.forEachIndexed { chapterIndex, parts ->
                val logicalChapterId = logicalChapterIds[chapterIndex]
                parts.forEachIndexed { segmentIndex, _ ->
                    allLogicalSegments.add(LogicalSegmentEntity(logicalChapterId = logicalChapterId, segmentIndex = segmentIndex))
                }
            }
            val logicalSegmentIds = dao.insertLogicalSegments(allLogicalSegments)

            val editionChaptersToInsert = chapters.mapIndexed { chapterIndex, chapter ->
                val logicalChapterId = logicalChapterIds[chapterIndex]
                val fileName = files.saveEditionChapter(bookId, editionId, chapterIndex + 1, chapter.title, chapter.renderedText)
                EditionChapterEntity(
                    editionId = editionId,
                    logicalChapterId = logicalChapterId,
                    title = chapter.title,
                    contentFileName = fileName,
                    wordCount = chapter.renderedText.length
                )
            }
            val editionChapterIds = dao.insertEditionChapters(editionChaptersToInsert)

            val allEditionSegments = mutableListOf<EditionSegmentEntity>()
            chapterParts.forEachIndexed { chapterIndex, parts ->
                val editionChapterId = editionChapterIds[chapterIndex]
                parts.forEachIndexed { segmentIndex, text ->
                    allEditionSegments.add(
                        EditionSegmentEntity(
                            editionChapterId = editionChapterId,
                            segmentIndex = segmentIndex,
                            baseText = text,
                            sourceHash = sha256(text)
                        )
                    )
                }
            }
            val editionSegmentIds = dao.insertEditionSegments(allEditionSegments)

            val allMappings = logicalSegmentIds.zip(editionSegmentIds).map { (logicalId, editionSegmentId) ->
                EditionSegmentMappingEntity(logicalId, editionSegmentId)
            }
            dao.insertMappings(allMappings)

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
    }

    companion object {
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
