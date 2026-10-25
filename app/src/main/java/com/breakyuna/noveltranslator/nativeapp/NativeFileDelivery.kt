package com.breakyuna.noveltranslator.nativeapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * System hand-off for generated files. Only private cache files that were just produced by an
 * export may enter this helper; project assets and workspace artifacts are never exposed by an
 * arbitrary path from a model or UI field.
 */
internal object NativeFileDelivery {
    const val MAX_FILES = 64

    fun share(context: Context, files: List<File>): Intent? {
        if (files.isEmpty() || files.size > MAX_FILES) return null
        val uris = files.map { file ->
            require(file.isFile) { "Missing share file" }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = files.map(::mimeType).distinct().singleOrNull() ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("LNT exports", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
    }

    fun shareOne(context: Context, file: File): Intent? = share(context, listOf(file))

    private fun mimeType(file: File): String {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json", "jsonl" -> "application/json"
            "csv" -> "text/csv"
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "zip", "lnt" -> "application/zip"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }
}
