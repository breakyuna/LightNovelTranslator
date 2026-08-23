package com.example.core.project

import android.content.Context
import java.io.File

class ProjectFileManager(private val context: Context) {

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
        val dir = File(getProjectDir(projectId), "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveOriginalChapter(projectId: Long, chapterIndex: Int, content: String): String {
        val fileName = "chap_${String.format("%04d", chapterIndex)}.txt"
        val file = File(getChaptersDir(projectId), fileName)
        file.writeText(content, Charsets.UTF_8)
        return fileName
    }

    fun readOriginalChapter(projectId: Long, fileName: String): String {
        val file = File(getChaptersDir(projectId), fileName)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun saveTranslatedChapter(projectId: Long, chapterIndex: Int, content: String): String {
        val fileName = "trans_${String.format("%04d", chapterIndex)}.txt"
        val file = File(getTranslationsDir(projectId), fileName)
        file.writeText(content, Charsets.UTF_8)
        return fileName
    }

    fun readTranslatedChapter(projectId: Long, fileName: String): String {
        val file = File(getTranslationsDir(projectId), fileName)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun saveRawFile(projectId: Long, originalFileName: String, bytes: ByteArray): File {
        val file = File(getRawDir(projectId), originalFileName)
        file.writeBytes(bytes)
        return file
    }

    fun deleteProjectFiles(projectId: Long) {
        val dir = getProjectDir(projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
