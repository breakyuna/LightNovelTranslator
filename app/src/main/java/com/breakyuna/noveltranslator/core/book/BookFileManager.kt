package com.breakyuna.noveltranslator.core.book

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Book-centric authoritative storage. Runtime-derived context belongs in cacheDir only. */
class BookFileManager(private val context: Context) {
    fun bookDir(bookId: Long) = ensured(File(context.filesDir, "books/book_$bookId"))
    fun coverDir(bookId: Long) = ensured(File(bookDir(bookId), "cover"))
    fun sourceDir(bookId: Long) = ensured(File(bookDir(bookId), "source"))
    fun sharedImagesDir(bookId: Long) = ensured(File(bookDir(bookId), "shared/images"))
    fun editionChaptersDir(bookId: Long, editionId: Long) = ensured(File(bookDir(bookId), "editions/edition_$editionId/chapters"))
    fun workspaceDir(bookId: Long) = ensured(File(bookDir(bookId), "workspace"))
    fun cacheDir(bookId: Long) = ensured(File(context.cacheDir, "books/book_$bookId"))
    fun exportDir(bookId: Long) = ensured(File(context.filesDir, "exports/book_$bookId"))

    fun saveImportedSource(bookId: Long, originalName: String, input: java.io.InputStream, maxBytes: Long): File {
        val target = File(sourceDir(bookId), safeName(originalName).ifBlank { "imported_novel.txt" })
        atomicWrite(target) { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "File exceeds the 100 MB import limit" }
                output.write(buffer, 0, count)
            }
        }
        return target
    }

    fun saveImportedSource(bookId: Long, originalName: String, bytes: ByteArray, maxBytes: Long): File {
        require(bytes.size.toLong() <= maxBytes) { "File exceeds the 100 MB import limit" }
        return saveImportedSource(bookId, originalName, bytes.inputStream(), maxBytes)
    }

    fun saveEditionChapter(bookId: Long, editionId: Long, chapterIndex: Int, title: String, text: String): String {
        val fileName = "%04d_%s.txt".format(chapterIndex, safeName(title).take(50).ifBlank { "chapter" })
        atomicWrite(File(editionChaptersDir(bookId, editionId), fileName)) { it.write(text.toByteArray(Charsets.UTF_8)) }
        return fileName
    }

    fun saveCover(bookId: Long, originalName: String, input: java.io.InputStream): File {
        val extension = File(originalName).extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(coverDir(bookId), "cover.$extension")
        atomicWrite(target) { output -> input.copyTo(output) }
        coverDir(bookId).listFiles()?.filter { it != target }?.forEach(File::delete)
        return target
    }

    fun readEditionChapter(bookId: Long, editionId: Long, fileName: String): String {
        val file = File(editionChaptersDir(bookId, editionId), File(fileName).name)
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
    }

    fun deleteBook(bookId: Long) {
        File(context.filesDir, "books/book_$bookId").deleteRecursively()
        File(context.cacheDir, "books/book_$bookId").deleteRecursively()
        File(context.filesDir, "exports/book_$bookId").deleteRecursively()
    }

    private fun atomicWrite(target: File, block: (FileOutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        val backup = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.bak")
        try {
            FileOutputStream(temp).use { output ->
                block(output)
                output.flush()
                output.fd.sync()
            }
            if (target.exists()) check(target.renameTo(backup)) { "Unable to stage previous ${target.name}" }
            check(temp.renameTo(target)) { "Unable to commit ${target.name}" }
            backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun safeName(name: String): String = File(name).name
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        .trim().take(100)

    private fun ensured(file: File): File = file.apply { if (!exists()) mkdirs() }
}
