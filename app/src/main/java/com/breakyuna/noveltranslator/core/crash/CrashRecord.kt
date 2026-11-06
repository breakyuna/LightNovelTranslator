package com.breakyuna.noveltranslator.core.crash

import com.breakyuna.noveltranslator.core.logger.LogSanitizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One persisted crash.
 *
 * Written by [CrashReporter] on the crashing thread *before* the process dies, so the record
 * survives the restart that otherwise wipes the in-memory
 * [com.breakyuna.noveltranslator.core.logger.SystemLogger].
 *
 * Everything user or provider related is passed through [LogSanitizer] first: no API key,
 * Authorization header, `sk-` token or novel body ever reaches disk.
 */
data class CrashRecord(
    val id: String,
    val timestamp: Long,
    val threadName: String,
    val exceptionClass: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val androidSdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val sessionSummary: String,
    val recentLogs: String,
    val fatal: Boolean
) {
    val formattedTimestamp: String
        get() = TIMESTAMP_FORMAT.format(Date(timestamp))

    /** One-line headline for lists / snackbars. */
    fun headline(): String =
        "$formattedTimestamp · $exceptionClass" + if (exceptionMessage.isBlank()) "" else ": $exceptionMessage"

    /** Full human readable report, used by the log screen and by the share sheet. */
    fun formattedText(): String = buildString {
        appendLine("=== LNT CRASH REPORT ===")
        appendLine("Time: $formattedTimestamp")
        appendLine("Thread: $threadName")
        appendLine("Exception: $exceptionClass")
        if (exceptionMessage.isNotBlank()) appendLine("Message: $exceptionMessage")
        appendLine("Device: $deviceManufacturer $deviceModel (Android $androidRelease, API $androidSdkInt)")
        appendLine("App: $appVersionName ($appVersionCode)")
        appendLine("Fatal: $fatal")
        if (sessionSummary.isNotBlank()) {
            appendLine()
            appendLine("--- Session ---")
            appendLine(sessionSummary.trimEnd())
        }
        if (recentLogs.isNotBlank()) {
            appendLine()
            appendLine("--- Recent logs (redacted) ---")
            appendLine(recentLogs.trimEnd())
        }
        appendLine()
        appendLine("--- Stack trace ---")
        appendLine(stackTrace.trimEnd())
    }

    /**
     * On-disk format. The header is strictly `key=value` single lines (newlines collapsed by
     * [LogSanitizer.singleLine]) and the three free-text blocks are fenced by markers, so the
     * file stays both human readable and parseable by [parse].
     */
    fun encode(): String = buildString {
        appendLine(HEADER)
        appendLine("id=${LogSanitizer.singleLine(id)}")
        appendLine("timestamp=$timestamp")
        appendLine("thread=${LogSanitizer.singleLine(threadName)}")
        appendLine("exception=${LogSanitizer.singleLine(exceptionClass)}")
        appendLine("message=${LogSanitizer.singleLine(exceptionMessage)}")
        appendLine("device=${LogSanitizer.singleLine("$deviceManufacturer $deviceModel")}")
        appendLine("android=${LogSanitizer.singleLine("$androidRelease (API $androidSdkInt)")}")
        appendLine("app=${LogSanitizer.singleLine("$appVersionName ($appVersionCode)")}")
        appendLine("fatal=$fatal")
        appendLine(SESSION_MARKER)
        appendLine(sessionSummary.trimEnd())
        appendLine(LOGS_MARKER)
        appendLine(recentLogs.trimEnd())
        appendLine(STACK_MARKER)
        appendLine(stackTrace.trimEnd())
    }

    companion object {
        const val HEADER = "### LNT-CRASH-RECORD v1"
        const val SESSION_MARKER = "--- SESSION ---"
        const val LOGS_MARKER = "--- LOGS ---"
        const val STACK_MARKER = "--- STACK ---"

        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /** Parses a file written by [encode]; returns null for anything that is not a record. */
        fun parse(text: String): CrashRecord? {
            if (!text.startsWith(HEADER)) return null

            val headerEnd = markerIndexes(text).minOrNull() ?: text.length
            val fields = text.substring(0, headerEnd).lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()

            fun block(marker: String): String {
                val start = text.indexOf("\n$marker")
                if (start < 0) return ""
                val contentStart = start + marker.length + 1
                val end = markerIndexes(text, from = contentStart).minOrNull() ?: text.length
                return text.substring(contentStart, end).trim('\n')
            }

            val device = fields["device"].orEmpty()
            val android = fields["android"].orEmpty()
            val app = fields["app"].orEmpty()
            return CrashRecord(
                id = fields["id"].orEmpty().ifBlank { "crash-${fields["timestamp"]}" },
                timestamp = fields["timestamp"]?.toLongOrNull() ?: 0L,
                threadName = fields["thread"].orEmpty(),
                exceptionClass = fields["exception"].orEmpty(),
                exceptionMessage = fields["message"].orEmpty(),
                stackTrace = block(STACK_MARKER),
                deviceManufacturer = device.substringBefore(' ', "").ifBlank { device },
                deviceModel = device.substringAfter(' ', "").ifBlank { device },
                androidRelease = android.substringBefore(" (API", "").ifBlank { android },
                androidSdkInt = android.substringAfter("API ", "").substringBefore(')').trim().toIntOrNull() ?: 0,
                appVersionName = app.substringBeforeLast(" (", "").ifBlank { app },
                appVersionCode = app.substringAfterLast('(').removeSuffix(")").toLongOrNull() ?: -1L,
                sessionSummary = block(SESSION_MARKER),
                recentLogs = block(LOGS_MARKER),
                fatal = fields["fatal"]?.toBoolean() ?: true
            )
        }

        private fun markerIndexes(text: String, from: Int = 0): List<Int> =
            listOf(SESSION_MARKER, LOGS_MARKER, STACK_MARKER).mapNotNull { marker ->
                text.indexOf("\n$marker", from).takeIf { it >= 0 }
            }
    }
}
