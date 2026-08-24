package com.breakyuna.noveltranslator.core.logger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

data class SystemLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String,
    val message: String,
    val details: String? = null,
    val projectId: Long? = null,
    val chapterIndex: Int? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object SystemLogger {
    private const val MAX_MEMORY_LOGS = 600
    private const val LOG_FILE_NAME = "system_runtime.log"

    private val memoryLogs = ConcurrentLinkedDeque<SystemLogEntry>()
    private val _logsFlow = MutableStateFlow<List<SystemLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<SystemLogEntry>> = _logsFlow.asStateFlow()

    private var logFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        try {
            val logDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
            logFile = File(logDir, LOG_FILE_NAME)
            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
            }
            log(LogLevel.INFO, "SYSTEM", "System logger initialized successfully.")
        } catch (e: Exception) {
            log(LogLevel.WARN, "SYSTEM", "Failed to initialize log file: ${e.localizedMessage}")
        }
    }

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        details: String? = null,
        projectId: Long? = null,
        chapterIndex: Int? = null
    ) {
        val entry = SystemLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            details = details,
            projectId = projectId,
            chapterIndex = chapterIndex
        )

        memoryLogs.addFirst(entry)
        while (memoryLogs.size > MAX_MEMORY_LOGS) {
            memoryLogs.pollLast()
        }
        _logsFlow.value = memoryLogs.toList()

        // Append to file asynchronously
        scope.launch {
            try {
                logFile?.let { file ->
                    val line = buildString {
                        append("[${entry.formattedDate}] [${entry.level}] [${entry.tag}] ")
                        if (entry.projectId != null) append("[Proj#${entry.projectId}] ")
                        if (entry.chapterIndex != null) append("[Chap#${entry.chapterIndex}] ")
                        append(entry.message)
                        if (!entry.details.isNullOrBlank()) {
                            append("\n  Details: ")
                            append(entry.details.replace("\n", "\n  "))
                        }
                        append("\n")
                    }
                    file.appendText(line, Charsets.UTF_8)
                    // If file exceeds 5MB, rotate or prune
                    if (file.length() > 5 * 1024 * 1024) {
                        pruneLogFile(file)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun info(tag: String, message: String, details: String? = null, projectId: Long? = null, chapterIndex: Int? = null) =
        log(LogLevel.INFO, tag, message, details, projectId, chapterIndex)

    fun warn(tag: String, message: String, details: String? = null, projectId: Long? = null, chapterIndex: Int? = null) =
        log(LogLevel.WARN, tag, message, details, projectId, chapterIndex)

    fun error(tag: String, message: String, details: String? = null, projectId: Long? = null, chapterIndex: Int? = null) =
        log(LogLevel.ERROR, tag, message, details, projectId, chapterIndex)

    fun debug(tag: String, message: String, details: String? = null, projectId: Long? = null, chapterIndex: Int? = null) =
        log(LogLevel.DEBUG, tag, message, details, projectId, chapterIndex)

    fun clearLogs() {
        memoryLogs.clear()
        _logsFlow.value = emptyList()
        scope.launch {
            try {
                logFile?.writeText("", Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        log(LogLevel.INFO, "SYSTEM", "System logs cleared by user.")
    }

    fun getLogFile(): File? = logFile

    fun exportLogsAsString(): String {
        return buildString {
            append("=== NOVEL TRANSLATOR STUDIO - SYSTEM RUNTIME LOGS ===\n")
            append("Exported At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            append("Total In-Memory Entries: ${memoryLogs.size}\n\n")
            val list = memoryLogs.toList().reversed()
            for (entry in list) {
                append("[${entry.formattedDate}] [${entry.level}] [${entry.tag}] ")
                if (entry.projectId != null) append("[Proj#${entry.projectId}] ")
                if (entry.chapterIndex != null) append("[Chap#${entry.chapterIndex}] ")
                append(entry.message)
                if (!entry.details.isNullOrBlank()) {
                    append("\n  >>> Details: ")
                    append(entry.details)
                }
                append("\n")
            }
        }
    }

    private fun pruneLogFile(file: File) {
        try {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size > 2000) {
                val retained = lines.takeLast(1000)
                file.writeText(retained.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }
}
