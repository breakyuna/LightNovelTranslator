package com.breakyuna.noveltranslator.core.exporter

import com.breakyuna.noveltranslator.core.book.BookFileManager
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.SegmentRevisionEntity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports the selected Edition's effective Revision text; TranslationProject is not an export boundary. */
class EditionExporter(private val database: AppDatabase, private val files: BookFileManager) {
    private data class ExportChapter(val index: Int, val title: String, val text: String)

    suspend fun exportTxt(bookId: Long, editionId: Long): File {
        val book = database.bookDao().getBook(bookId) ?: error("Book not found")
        val edition = database.bookDao().getEdition(editionId) ?: error("Edition not found")
        require(edition.bookId == bookId)
        val target = File(files.exportDir(bookId), "${safe(book.title)}_${safe(edition.name)}.txt")
        val chapters = loadChapters(bookId, editionId)
        target.writeText(buildString {
            append("《").append(book.title).append("》\n")
            append(book.author).append(" · ").append(edition.name).append("\n\n")
            chapters.forEach { append(it.title).append("\n\n").append(it.text).append("\n\n\n") }
        }, Charsets.UTF_8)
        return target
    }

    suspend fun exportEpub(bookId: Long, editionId: Long): File {
        val book = database.bookDao().getBook(bookId) ?: error("Book not found")
        val edition = database.bookDao().getEdition(editionId) ?: error("Edition not found")
        require(edition.bookId == bookId)
        val chapters = loadChapters(bookId, editionId)
        val target = File(files.exportDir(bookId), "${safe(book.title)}_${safe(edition.name)}.epub")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
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
            val images = files.sharedImagesDir(bookId).listFiles()?.filter(File::isFile).orEmpty()
            images.forEachIndexed { index, image ->
                manifest.append("<item id=\"img$index\" href=\"images/${xml(image.name)}\" media-type=\"${mimeType(image.extension)}\"/>")
                add(zip, "OEBPS/images/${image.name}", image.readBytes())
            }
            chapters.forEachIndexed { index, chapter ->
                val id = "c${index + 1}"; val href = "$id.xhtml"
                manifest.append("<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"$id\"/>")
                nav.append("<li><a href=\"$href\">${xml(chapter.title)}</a></li>")
                val body = chapter.text.split(Regex("\\n\\s*\\n")).joinToString("\n") { paragraph ->
                    val trimmed = paragraph.trim()
                    if (trimmed.startsWith("[IMG:") && trimmed.endsWith("]")) {
                        "<p><img src=\"images/${xml(trimmed.removePrefix("[IMG:").removeSuffix("]"))}\" alt=\"illustration\"/></p>"
                    } else "<p>${xml(trimmed)}</p>"
                }
                add(zip, "OEBPS/$href", xhtml(chapter.title, "<h1>${xml(chapter.title)}</h1>$body"))
            }
            add(zip, "OEBPS/nav.xhtml", xhtml(book.title, "<nav epub:type=\"toc\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><ol>$nav</ol></nav>"))
            add(zip, "OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">book-$bookId-edition-$editionId</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator><dc:language>${lang(edition.language)}</dc:language></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>""")
        }
        return target
    }

    private suspend fun loadChapters(bookId: Long, editionId: Long): List<ExportChapter> {
        val dao = database.bookDao()
        return dao.getChapters(bookId).mapNotNull { logical ->
            val chapter = dao.getEditionChapter(editionId, logical.id) ?: return@mapNotNull null
            val logicalSegments = dao.getLogicalSegments(logical.id)
            val editionSegments = dao.getEditionSegments(chapter.id).associateBy { it.id }
            val mappings = dao.getMappings(logicalSegments.map { it.id }).filter { it.editionSegmentId in editionSegments }.groupBy { it.logicalSegmentId }
            val revisions = dao.getActiveRevisions(editionSegments.keys.toList()).groupBy { it.editionSegmentId }
                .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<SegmentRevisionEntity> { it.priority }.thenBy { it.createdAt }) }
            val text = logicalSegments.mapNotNull { segment ->
                val parts = mappings[segment.id].orEmpty().sortedBy { it.mappingOrder }.mapNotNull { editionSegments[it.editionSegmentId] }
                parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { revisions[it.id]?.text ?: it.baseText }
            }.joinToString("\n\n")
            ExportChapter(logical.chapterIndex, chapter.title, text)
        }
    }

    private fun add(zip: ZipOutputStream, path: String, text: String) = add(zip, path, text.toByteArray(Charsets.UTF_8))
    private fun add(zip: ZipOutputStream, path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
    private fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(title)}</title><link rel="stylesheet" href="style.css"/></head><body>$body</body></html>"""
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun safe(value: String) = value.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").take(70).ifBlank { "edition" }
    private fun mimeType(ext: String) = when (ext.lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> "image/jpeg" }
    private fun lang(language: String) = when { language.contains("zh", true) || language.contains("Chinese", true) -> "zh"; language.contains("ja", true) -> "ja"; language.contains("ko", true) -> "ko"; else -> "en" }
}
