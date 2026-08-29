package com.breakyuna.noveltranslator.core.book

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/** Book-centric authoritative storage. Runtime-derived context belongs in cacheDir only. */
class BookFileManager(private val context: Context) {
    fun bookDir(bookId: Long) = ensured(File(context.filesDir, "books/book_$bookId"))
    fun coverDir(bookId: Long) = ensured(File(bookDir(bookId), "cover"))
    fun sourceDir(bookId: Long) = ensured(File(bookDir(bookId), "source"))
    fun sharedImagesDir(bookId: Long) = ensured(File(bookDir(bookId), "shared/images"))
    fun editionChaptersDir(bookId: Long, editionId: Long) = ensured(editionChaptersPath(bookId, editionId))
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
        val fileName = "%04d_%s.txt".format(Locale.US, chapterIndex, safeName(title).take(50).ifBlank { "chapter" })
        atomicWrite(File(editionChaptersDir(bookId, editionId), fileName)) { it.write(text.toByteArray(Charsets.UTF_8)) }
        return fileName
    }

    /** Writes a new immutable chapter file for a translation revision. */
    fun saveEditionChapterVersion(bookId: Long, editionId: Long, chapterIndex: Int, title: String, text: String): String {
        val fileName = "%04d_%s_%s.txt".format(
            Locale.US,
            chapterIndex,
            safeName(title).take(40).ifBlank { "chapter" },
            UUID.randomUUID().toString()
        )
        atomicWrite(File(editionChaptersDir(bookId, editionId), fileName)) { it.write(text.toByteArray(Charsets.UTF_8)) }
        return fileName
    }

    fun deleteEditionChapterFile(bookId: Long, editionId: Long, fileName: String?) {
        fileName?.let { File(editionChaptersDir(bookId, editionId), File(it).name).delete() }
    }

    fun deleteEdition(bookId: Long, editionId: Long) {
        File(bookDir(bookId), "editions/edition_$editionId").deleteRecursively()
    }

    /** Creates a sibling staging directory for a full Edition rebuild. */
    fun createEditionChapterStagingDir(bookId: Long, editionId: Long): File {
        val parent = File(bookDir(bookId), "editions/edition_$editionId").apply { mkdirs() }
        val staging = File(parent, ".chapters-staging-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Unable to create chapter staging directory" }
        return staging
    }

    fun saveStagedEditionChapter(stagingDir: File, chapterIndex: Int, title: String, text: String): String {
        val fileName = "%04d_%s.txt".format(Locale.US, chapterIndex, safeName(title).take(50).ifBlank { "chapter" })
        atomicWrite(File(stagingDir, fileName)) { it.write(text.toByteArray(Charsets.UTF_8)) }
        return fileName
    }

    /** Swaps a completed staging directory into place and returns the old directory for rollback. */
    fun swapEditionChapterDirectory(bookId: Long, editionId: Long, stagingDir: File): File? {
        val target = editionChaptersPath(bookId, editionId)
        val backup = File(target.parentFile, ".chapters-backup-${UUID.randomUUID()}")
        val hadTarget = target.exists()
        if (hadTarget) check(target.renameTo(backup)) { "Unable to stage previous chapter directory" }
        try {
            check(stagingDir.renameTo(target)) { "Unable to activate chapter staging directory" }
            return backup.takeIf { hadTarget }
        } catch (error: Throwable) {
            if (!target.exists() && hadTarget) backup.renameTo(target)
            throw error
        }
    }

    fun finalizeEditionChapterSwap(backupDir: File?) {
        backupDir?.deleteRecursively()
    }

    fun rollbackEditionChapterSwap(bookId: Long, editionId: Long, backupDir: File?) {
        val target = editionChaptersPath(bookId, editionId)
        target.deleteRecursively()
        if (backupDir != null && backupDir.exists()) {
            check(backupDir.renameTo(target)) { "Unable to restore previous chapter directory" }
        }
    }

    fun saveCover(bookId: Long, originalName: String, input: java.io.InputStream): File {
        val extension = File(originalName).extension.lowercase(Locale.ROOT)
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(coverDir(bookId), "cover.$extension")
        atomicWrite(target) { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                require(total <= MAX_COVER_BYTES) { "Cover image exceeds the 10 MB limit" }
                output.write(buffer, 0, count)
            }
        }
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
        .map { if (it.code < 32 || it.code == 127) '_' else it }
        .joinToString("")
        .trim().take(100)

    private fun ensured(file: File): File = file.apply { if (!exists()) mkdirs() }

    private fun editionChaptersPath(bookId: Long, editionId: Long): File =
        File(bookDir(bookId), "editions/edition_$editionId/chapters")

    companion object {
        const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L
        const val MAX_COVER_BYTES = 10L * 1024L * 1024L
    }
}
