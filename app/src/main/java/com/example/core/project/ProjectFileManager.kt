package com.example.core.project

import android.content.Context
import java.io.File

class ProjectFileManager(private val context: Context) {

    class ChapterFileTransaction internal constructor(
        internal val stagingRoot: File,
        internal val stagingChapters: File,
        internal val stagingTranslations: File,
        internal val liveChapters: File,
        internal val liveTranslations: File,
        internal val backupChapters: File,
        internal val backupTranslations: File
    ) {
        internal var stagingChaptersActivated: Boolean = false
        internal var stagingTranslationsActivated: Boolean = false
    }

    fun getProjectDir(projectId: Long): File {
        val dir = File(context.filesDir, "projects/proj_$projectId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRawDir(projectId: Long): File {
        val dir = File(getProjectDir(projectId), "raw")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getChaptersDir(projectId: Long): File {
        val dir = File(getProjectDir(projectId), "chapters")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTranslationsDir(projectId: Long): File {
        val dir = File(getProjectDir(projectId), "translations")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getImagesDir(projectId: Long): File {
        val dir = File(getProjectDir(projectId), "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExportsDir(projectId: Long): File {
        val dir = File(context.filesDir, "exports/proj_$projectId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun sanitizeChapterFileName(chapterIndex: Int, title: String, isTranslated: Boolean = false): String {
        val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .take(45)
            .trimEnd('_', ' ')
        val prefix = String.format("%04d", chapterIndex)
        return if (cleanTitle.isNotBlank()) {
            if (isTranslated) "${prefix}_${cleanTitle}_translated.txt" else "${prefix}_${cleanTitle}.txt"
        } else {
            if (isTranslated) "trans_${prefix}.txt" else "chap_${prefix}.txt"
        }
    }

    fun saveOriginalChapter(projectId: Long, chapterIndex: Int, content: String, title: String = ""): String {
        val fileName = sanitizeChapterFileName(chapterIndex, title, isTranslated = false)
        val file = File(getChaptersDir(projectId), File(fileName).name)
        file.writeText(content, Charsets.UTF_8)
        return fileName
    }

    fun beginChapterFileTransaction(projectId: Long): ChapterFileTransaction {
        val projectDir = getProjectDir(projectId)
        val suffix = "${System.currentTimeMillis()}_${Thread.currentThread().id}"
        val stagingRoot = File(projectDir, ".chapter_staging_$suffix")
        val stagingChapters = File(stagingRoot, "chapters")
        val stagingTranslations = File(stagingRoot, "translations")
        check(stagingRoot.mkdirs() && stagingChapters.mkdirs() && stagingTranslations.mkdirs()) {
            "Unable to create temporary chapter workspace"
        }
        return ChapterFileTransaction(
            stagingRoot = stagingRoot,
            stagingChapters = stagingChapters,
            stagingTranslations = stagingTranslations,
            liveChapters = File(projectDir, "chapters"),
            liveTranslations = File(projectDir, "translations"),
            backupChapters = File(projectDir, ".chapters_backup_$suffix"),
            backupTranslations = File(projectDir, ".translations_backup_$suffix")
        )
    }

    fun saveOriginalChapter(transaction: ChapterFileTransaction, chapterIndex: Int, content: String, title: String = ""): String {
        val fileName = sanitizeChapterFileName(chapterIndex, title, isTranslated = false)
        File(transaction.stagingChapters, File(fileName).name).writeText(content, Charsets.UTF_8)
        return fileName
    }

    fun commitChapterFileTransaction(transaction: ChapterFileTransaction) {
        try {
            moveDirectory(transaction.liveChapters, transaction.backupChapters)
            moveDirectory(transaction.liveTranslations, transaction.backupTranslations)
            moveDirectory(transaction.stagingChapters, transaction.liveChapters)
            transaction.stagingChaptersActivated = true
            moveDirectory(transaction.stagingTranslations, transaction.liveTranslations)
            transaction.stagingTranslationsActivated = true
        } catch (error: Exception) {
            rollbackChapterFileTransaction(transaction)
            throw error
        }
    }

    fun finalizeChapterFileTransaction(transaction: ChapterFileTransaction) {
        transaction.backupChapters.deleteRecursively()
        transaction.backupTranslations.deleteRecursively()
        transaction.stagingRoot.deleteRecursively()
    }

    fun rollbackChapterFileTransaction(transaction: ChapterFileTransaction) {
        if (transaction.stagingChaptersActivated) transaction.liveChapters.deleteRecursively()
        if (transaction.stagingTranslationsActivated) transaction.liveTranslations.deleteRecursively()
        if (transaction.backupChapters.exists()) moveDirectory(transaction.backupChapters, transaction.liveChapters)
        if (transaction.backupTranslations.exists()) moveDirectory(transaction.backupTranslations, transaction.liveTranslations)
        transaction.stagingRoot.deleteRecursively()
        transaction.stagingChaptersActivated = false
        transaction.stagingTranslationsActivated = false
    }

    private fun moveDirectory(source: File, target: File) {
        if (!source.exists()) {
            target.mkdirs()
            return
        }
        check(!target.exists()) { "Target directory already exists: ${target.name}" }
        check(source.renameTo(target)) { "Unable to move ${source.name}" }
    }

    fun readOriginalChapter(projectId: Long, fileName: String): String {
        val file = File(getChaptersDir(projectId), File(fileName).name)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun saveTranslatedChapter(projectId: Long, chapterIndex: Int, content: String, title: String = ""): String {
        val fileName = sanitizeChapterFileName(chapterIndex, title, isTranslated = true)
        val file = File(getTranslationsDir(projectId), File(fileName).name)
        file.writeText(content, Charsets.UTF_8)
        return fileName
    }

    fun readTranslatedChapter(projectId: Long, fileName: String): String {
        val file = File(getTranslationsDir(projectId), File(fileName).name)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun saveRawFile(projectId: Long, originalFileName: String, bytes: ByteArray): File {
        val safeName = File(originalFileName).name
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .take(100)
            .ifBlank { "imported_novel.txt" }
        val file = File(getRawDir(projectId), safeName)
        file.writeBytes(bytes)
        return file
    }

    fun deleteProjectFiles(projectId: Long) {
        val dir = getProjectDir(projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val exportsDir = File(context.filesDir, "exports/proj_$projectId")
        if (exportsDir.exists()) exportsDir.deleteRecursively()
    }

}
