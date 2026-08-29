package com.breakyuna.noveltranslator.core.exporter

import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.EditionChapterEntity
import com.breakyuna.noveltranslator.data.model.EditionType
import com.breakyuna.noveltranslator.data.model.LogicalSegmentEntity
import com.breakyuna.noveltranslator.data.model.SegmentRevisionEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exports the selected Edition's effective Revision text; TranslationProject is not an export boundary. */
class EditionExporter(private val database: AppDatabase, private val files: BookFileManager) {
    private data class ExportChapter(val index: Int, val title: String, val text: String)

    suspend fun exportTxt(bookId: Long, editionId: Long): File {
        val book = database.bookDao().getBook(bookId) ?: error("Book not found")
        val edition = database.bookDao().getEdition(editionId) ?: error("Edition not found")
        require(edition.bookId == bookId)
        val target = File(files.exportDir(bookId), "${safe(book.title)}_${safe(edition.name)}.txt")
        return writeAtomically(target) { temporary ->
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append("《").append(book.title).append("》\n")
                writer.append(book.author).append(" · ").append(edition.name).append("\n\n")
                forEachChapter(bookId, editionId) { chapter ->
                    currentCoroutineContext().ensureActive()
                    writer.append(chapter.title).append("\n\n")
                        .append(chapter.text).append("\n\n\n")
                }
            }
        }
    }

    suspend fun exportEpub(bookId: Long, editionId: Long): File {
        val book = database.bookDao().getBook(bookId) ?: error("Book not found")
        val edition = database.bookDao().getEdition(editionId) ?: error("Edition not found")
        require(edition.bookId == bookId)
        val target = File(files.exportDir(bookId), "${safe(book.title)}_${safe(edition.name)}.epub")
        return writeAtomically(target) { temporary ->
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(mimeEntry); zip.write(mime); zip.closeEntry()
            add(zip, "META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            add(zip, "OEBPS/style.css", "body{font-family:serif;line-height:1.8;margin:5%}h1{text-align:center}p{text-indent:2em}img{max-width:100%;height:auto}")
            val manifest = StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
            val spine = StringBuilder()
            val nav = StringBuilder()
            val images = files.sharedImagesDir(bookId).listFiles()
                ?.filter { it.isFile && isSafeArchiveName(it.name) }
                .orEmpty()
            val imageNames = images.mapTo(hashSetOf()) { it.name }
            images.forEachIndexed { index, image ->
                manifest.append("<item id=\"img$index\" href=\"images/${xml(image.name)}\" media-type=\"${mimeType(image.extension)}\"/>")
                zip.putNextEntry(ZipEntry("OEBPS/images/${image.name}"))
                image.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            var chapterNumber = 0
            forEachChapter(bookId, editionId) { chapter ->
                currentCoroutineContext().ensureActive()
                chapterNumber++
                val id = "c$chapterNumber"; val href = "$id.xhtml"
                manifest.append("<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"$id\"/>")
                nav.append("<li><a href=\"$href\">${xml(chapter.title)}</a></li>")
                addChapter(zip, "OEBPS/$href", chapter.title, chapter.text, imageNames)
            }
            add(zip, "OEBPS/nav.xhtml", xhtml(book.title, "<nav epub:type=\"toc\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><ol>$nav</ol></nav>"))
            add(zip, "OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">book-$bookId-edition-$editionId</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator><dc:language>${lang(edition.language)}</dc:language></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>""")
            }
        }
    }

    private suspend fun forEachChapter(
        bookId: Long,
        editionId: Long,
        action: suspend (ExportChapter) -> Unit
    ) {
        val dao = database.bookDao()
        val book = dao.getBook(bookId) ?: error("Book not found")
        val originalEditionId = book.primaryEditionId
            ?: dao.getEditions(bookId).firstOrNull { it.type == EditionType.IMPORTED.name }?.id
            ?: error("Original Edition not found")
        suspend fun resolveChapter(
            chapter: EditionChapterEntity?,
            logicalSegments: List<LogicalSegmentEntity>
        ): Map<Long, String> {
            if (chapter == null || logicalSegments.isEmpty()) return emptyMap()
            val editionSegments = dao.getEditionSegments(chapter.id).associateBy { it.id }
            if (editionSegments.isEmpty()) return emptyMap()
            val mappings = dao.getMappings(logicalSegments.map { it.id })
                .filter { it.editionSegmentId in editionSegments }
                .groupBy { it.logicalSegmentId }
            val revisions = dao.getActiveRevisions(editionSegments.keys.toList())
                .groupBy { it.editionSegmentId }
                .mapValues { (_, rows) ->
                    rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt })
                }
            return mappings.mapValues { (_, rows) ->
                rows.sortedBy { it.mappingOrder }
                    .mapNotNull { editionSegments[it.editionSegmentId] }
                    .joinToString("\n\n") { segment ->
                        revisions[segment.id]?.text?.takeIf { it.isNotBlank() } ?: segment.baseText
                    }
            }.filterValues { it.isNotBlank() }
        }

        for (logical in dao.getChapters(bookId)) {
            // The reader deliberately falls back to the original Edition per logical segment.
            // Export must resolve the same effective content, even when a target chapter exists
            // but only some of its segment mappings have been written so far.
            val originalChapter = dao.getEditionChapter(originalEditionId, logical.id)
            val targetChapter = if (editionId == originalEditionId) originalChapter
            else dao.getEditionChapter(editionId, logical.id)
            if (originalChapter == null && targetChapter == null) continue

            val logicalSegments = dao.getLogicalSegments(logical.id)

            val originalText = resolveChapter(originalChapter, logicalSegments)
            val targetText = resolveChapter(targetChapter, logicalSegments)
            val text = logicalSegments.mapNotNull { segment ->
                targetText[segment.id] ?: originalText[segment.id]
            }.joinToString("\n\n")
            action(ExportChapter(
                logical.chapterIndex,
                targetChapter?.title ?: originalChapter?.title ?: logical.canonicalTitle,
                text
            ))
        }
    }

    private fun addChapter(
        zip: ZipOutputStream,
        path: String,
        title: String,
        text: String,
        imageNames: Set<String>
    ) {
        zip.putNextEntry(ZipEntry(path))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
        writeXml(writer, title)
        writer.append("</title><link rel=\"stylesheet\" href=\"style.css\"/></head><body><h1>")
        writeXml(writer, title)
        writer.append("</h1>")
        var paragraphStart = 0
        for (separator in Regex("\\n\\s*\\n").findAll(text)) {
            writeParagraph(writer, text, paragraphStart, separator.range.first, imageNames)
            paragraphStart = separator.range.last + 1
        }
        writeParagraph(writer, text, paragraphStart, text.length, imageNames)
        writer.append("</body></html>")
        writer.flush()
        zip.closeEntry()
    }

    private fun writeParagraph(
        writer: OutputStreamWriter,
        text: String,
        rawStart: Int,
        rawEnd: Int,
        imageNames: Set<String>
    ) {
        var start = rawStart.coerceIn(0, text.length)
        var end = rawEnd.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return

        val imagePrefix = "[IMG:"
        val imageName = if (end - start > imagePrefix.length + 1 &&
            text.regionMatches(start, imagePrefix, 0, imagePrefix.length) &&
            text[end - 1] == ']'
        ) {
            File(text.substring(start + imagePrefix.length, end - 1)).name
        } else {
            null
        }
        val isImage = end - start > imagePrefix.length + 1 &&
            imageName != null && imageName in imageNames
        writer.append("<p>")
        if (isImage) {
            writer.append("<img src=\"images/")
            writeXml(writer, imageName!!)
            writer.append("\" alt=\"illustration\"/>")
        } else {
            writeXml(writer, text, start, end)
        }
        writer.append("</p>")
    }

    private fun writeXml(writer: OutputStreamWriter, value: String) {
        writeXml(writer, value, 0, value.length)
    }

    /** Escapes XML in bounded chunks so one malformed/large paragraph is never copied wholesale. */
    private fun writeXml(writer: OutputStreamWriter, value: String, start: Int, end: Int) {
        val chunk = StringBuilder(4_096)
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                writer.append(chunk)
                chunk.clear()
            }
        }
        for (index in start until end) {
            if (isForbiddenXmlControl(value[index])) {
                chunk.append(' ')
                if (chunk.length >= 4_096) flushChunk()
                continue
            }
            when (value[index]) {
                '&' -> { flushChunk(); writer.append("&amp;") }
                '<' -> { flushChunk(); writer.append("&lt;") }
                '>' -> { flushChunk(); writer.append("&gt;") }
                '"' -> { flushChunk(); writer.append("&quot;") }
                '\'' -> { flushChunk(); writer.append("&apos;") }
                else -> {
                    chunk.append(value[index])
                    if (chunk.length >= 4_096) flushChunk()
                }
            }
        }
        flushChunk()
    }

    private suspend fun writeAtomically(target: File, write: suspend (File) -> Unit): File {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.bak")
        try {
            write(temporary)
            if (target.exists()) check(target.renameTo(backup)) { "Unable to stage previous export" }
            check(temporary.renameTo(target)) { "Unable to commit export" }
            backup.delete()
            return target
        } catch (error: Throwable) {
            temporary.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun add(zip: ZipOutputStream, path: String, text: String) = add(zip, path, text.toByteArray(Charsets.UTF_8))
    private fun add(zip: ZipOutputStream, path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
    private fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(title)}</title><link rel="stylesheet" href="style.css"/></head><body>$body</body></html>"""
    private fun xml(value: String) = value
        .filterNot(::isForbiddenXmlControl)
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun isForbiddenXmlControl(value: Char): Boolean =
        value.code == 0 || value.code == 127 || (value.code < 32 && value != '\t' && value != '\n' && value != '\r')
    private fun safe(value: String) = value.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        .map { if (it.code < 32 || it.code == 127) '_' else it }
        .joinToString("")
        .take(70).ifBlank { "edition" }
    private fun mimeType(ext: String) = when (ext.lowercase(Locale.ROOT)) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> "image/jpeg" }
    private fun isSafeArchiveName(name: String): Boolean = name == File(name).name &&
        name.isNotBlank() && name != "." && name != ".." &&
        name.length <= 100 && name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    private fun lang(language: String) = when { language.contains("zh", true) || language.contains("Chinese", true) -> "zh"; language.contains("ja", true) -> "ja"; language.contains("ko", true) -> "ko"; else -> "en" }
}
