package com.breakyuna.noveltranslator.core.logger

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

data class SystemLogEntry(
    val id: String = StableLogId.create(),
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

/**
 * Process-wide system logger.
 *
 * Memory publication is serialized by [stateLock]. Persistence commands are enqueued while
 * holding the same lock and consumed by one IO worker, so memory snapshots and JSONL append
 * order cannot overtake each other under concurrent callers.
 */
object SystemLogger {
    internal const val JSONL_FILE_NAME = "system_runtime.jsonl"
    internal const val LEGACY_FILE_NAME = "system_runtime.log"
    private const val ROTATED_FILE_SUFFIX = ".1"
    private const val LEGACY_BACKUP_SUFFIX = ".legacy"
    internal const val MAX_MEMORY_LOGS = 600
    private const val MAX_FILE_BYTES = 5L * 1024L * 1024L

    private sealed interface PersistenceCommand {
        data class Initialize(val directory: File, val recoveryGeneration: Long) : PersistenceCommand
        data class Append(val entry: SystemLogEntry) : PersistenceCommand
        data object Clear : PersistenceCommand
        data class Barrier(val completion: CompletableDeferred<Unit>) : PersistenceCommand
        data class ResetForTests(val completion: CompletableDeferred<Unit>) : PersistenceCommand
    }

    private val adapter: JsonAdapter<SystemLogEntry> = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(SystemLogEntry::class.java)
        .serializeNulls()

    private val stateLock = Any()
    private val memoryLogs = ArrayDeque<SystemLogEntry>()
    private val _logsFlow = MutableStateFlow<List<SystemLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<SystemLogEntry>> = _logsFlow.asStateFlow()

    private val persistenceCommands = Channel<PersistenceCommand>(Channel.UNLIMITED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingBeforeInitialization = mutableListOf<SystemLogEntry>()

    @Volatile
    private var logFile: File? = null
    private var initializedDirectoryPath: String? = null
    private var recoveryGeneration = 0L
    private var activeLogFile: File? = null

    init {
        persistenceScope.launch {
            for (command in persistenceCommands) {
                when (command) {
                    is PersistenceCommand.Initialize -> initializeStorage(
                        command.directory,
                        command.recoveryGeneration
                    )
                    is PersistenceCommand.Append -> appendOrBuffer(command.entry)
                    PersistenceCommand.Clear -> clearPersistedLogs()
                    is PersistenceCommand.Barrier -> command.completion.complete(Unit)
                    is PersistenceCommand.ResetForTests -> resetStateForTests(command.completion)
                }
            }
        }
    }

    fun init(context: Context) {
        initDirectory(File(context.filesDir, "logs"))
    }

    internal fun initDirectory(directory: File) {
        val normalizedPath = directory.absoluteFile.normalize().path
        synchronized(stateLock) {
            if (initializedDirectoryPath == normalizedPath) return
            initializedDirectoryPath = normalizedPath
            memoryLogs.clear()
            _logsFlow.value = emptyList()
            logFile = null
            recoveryGeneration++
            persistenceCommands.trySend(
                PersistenceCommand.Initialize(directory, recoveryGeneration)
            )
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
        val entry = createEntry(level, tag, message, details, projectId, chapterIndex)
        publishAndEnqueue(entry)
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
        synchronized(stateLock) {
            memoryLogs.clear()
            _logsFlow.value = emptyList()
            recoveryGeneration++
            persistenceCommands.trySend(PersistenceCommand.Clear)
        }
        info("SYSTEM", "System logs cleared by user.")
    }

    fun getLogFile(): File? = logFile

    fun exportLogsAsString(): String {
        val snapshot = synchronized(stateLock) { memoryLogs.toList() }
        return buildString {
            append("=== NOVEL TRANSLATOR STUDIO - SYSTEM RUNTIME LOGS ===\n")
            append("Exported At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            append("Total In-Memory Entries: ${snapshot.size}\n\n")
            for (entry in snapshot.asReversed()) {
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

    private fun publishAndEnqueue(entry: SystemLogEntry) {
        synchronized(stateLock) {
            memoryLogs.addFirst(entry)
            trimMemoryLogs()
            _logsFlow.value = memoryLogs.toList()
            persistenceCommands.trySend(PersistenceCommand.Append(entry))
        }
    }

    private fun initializeStorage(directory: File, commandGeneration: Long) {
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Unable to create log directory: ${directory.path}")
            }
            val target = File(directory, JSONL_FILE_NAME)
            val legacy = File(directory, LEGACY_FILE_NAME)
            if (!target.exists() && legacy.exists()) migrateLegacyFile(legacy, target)
            if (!target.exists()) target.createNewFile()

            activeLogFile = target
            logFile = target
            val recovered = loadRecentJsonlEntries(target)
            val recoveryStillCurrent = synchronized(stateLock) {
                commandGeneration == recoveryGeneration
            }
            if (recoveryStillCurrent) mergeRecoveredEntries(recovered)

            if (pendingBeforeInitialization.isNotEmpty()) {
                val pending = pendingBeforeInitialization.toList()
                pendingBeforeInitialization.clear()
                if (recoveryStillCurrent) {
                    mergeRecoveredEntries(pending)
                    pending.forEach(::appendEntry)
                }
            }
            if (recoveryStillCurrent) {
                publishFromPersistenceWorker(
                    createEntry(LogLevel.INFO, "SYSTEM", "System logger initialized successfully."),
                    persist = true
                )
            }
        } catch (error: Exception) {
            activeLogFile = null
            publishFromPersistenceWorker(
                createEntry(
                    LogLevel.WARN,
                    "SYSTEM",
                    "Failed to initialize log file: ${error.localizedMessage ?: error.javaClass.simpleName}"
                ),
                persist = false
            )
        }
    }

    private fun createEntry(
        level: LogLevel,
        tag: String,
        message: String,
        details: String? = null,
        projectId: Long? = null,
        chapterIndex: Int? = null
    ): SystemLogEntry = SystemLogEntry(
        level = level,
        tag = sanitize(tag),
        message = sanitize(message),
        details = details?.let(::sanitize),
        projectId = projectId,
        chapterIndex = chapterIndex
    )

    private fun publishFromPersistenceWorker(entry: SystemLogEntry, persist: Boolean) {
        synchronized(stateLock) {
            memoryLogs.addFirst(entry)
            trimMemoryLogs()
            _logsFlow.value = memoryLogs.toList()
        }
        if (persist) runCatching { appendEntry(entry) }
    }

    private fun appendOrBuffer(entry: SystemLogEntry) {
        if (activeLogFile == null) {
            pendingBeforeInitialization += entry
        } else {
            runCatching { appendEntry(entry) }
        }
    }

    private fun appendEntry(entry: SystemLogEntry) {
        val file = activeLogFile ?: return
        if (file.length() > MAX_FILE_BYTES) rotate(file)
        file.appendText(adapter.toJson(entry) + "\n", Charsets.UTF_8)
    }

    private fun rotate(file: File) {
        val rotated = File(file.parentFile, file.name + ROTATED_FILE_SUFFIX)
        if (rotated.exists() && !rotated.delete()) return
        if (!file.renameTo(rotated)) return
        file.createNewFile()
    }

    private fun clearPersistedLogs() {
        val file = activeLogFile ?: logFile ?: return
        runCatching {
            listOf(
                file,
                File(file.parentFile, file.name + ROTATED_FILE_SUFFIX),
                File(file.parentFile, LEGACY_FILE_NAME),
                File(file.parentFile, LEGACY_FILE_NAME + LEGACY_BACKUP_SUFFIX)
            ).forEach { candidate ->
                if (candidate == file) {
                    candidate.parentFile?.mkdirs()
                    candidate.writeText("", Charsets.UTF_8)
                } else if (candidate.exists()) {
                    candidate.delete()
                }
            }
        }
    }

    private fun mergeRecoveredEntries(recoveredChronological: List<SystemLogEntry>) {
        synchronized(stateLock) {
            val currentIds = memoryLogs.mapTo(hashSetOf()) { it.id }
            recoveredChronological.asReversed().forEach { entry ->
                if (currentIds.add(entry.id)) memoryLogs.addLast(entry)
            }
            trimMemoryLogs()
            _logsFlow.value = memoryLogs.toList()
        }
    }

    private fun loadRecentJsonlEntries(currentFile: File): List<SystemLogEntry> {
        val recent = ArrayDeque<SystemLogEntry>()
        val rotated = File(currentFile.parentFile, currentFile.name + ROTATED_FILE_SUFFIX)
        listOf(rotated, currentFile).forEach { file ->
            if (!file.exists()) return@forEach
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    if (line.isBlank()) return@lineLoop
                    val entry = runCatching { adapter.fromJson(line) }.getOrNull() ?: return@lineLoop
                    if (entry.id.isBlank()) return@lineLoop
                    recent.addLast(entry)
                    while (recent.size > MAX_MEMORY_LOGS) recent.removeFirst()
                }
            }
        }
        return recent.toList()
    }

    private fun migrateLegacyFile(legacy: File, target: File) {
        val migrated = parseLegacyEntries(legacy.readLines(Charsets.UTF_8))
        val temporary = File(target.parentFile, target.name + ".migrating")
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            migrated.forEach { entry ->
                writer.append(adapter.toJson(entry))
                writer.newLine()
            }
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Unable to replace incomplete JSONL log")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        val backup = File(legacy.parentFile, legacy.name + LEGACY_BACKUP_SUFFIX)
        if (backup.exists()) backup.delete()
        legacy.renameTo(backup)
    }

    internal fun parseLegacyEntries(lines: List<String>): List<SystemLogEntry> {
        data class Draft(
            val rawTimestamp: String,
            val level: LogLevel,
            val tag: String,
            val projectId: Long?,
            val chapterIndex: Int?,
            val messageLines: MutableList<String>,
            val detailLines: MutableList<String>,
            val ordinal: Int
        )

        val header = Regex("^\\[(.+?)] \\[(INFO|WARN|ERROR|DEBUG)] \\[(.+?)] (.*)$")
        val projectPrefix = Regex("^\\[Proj#(-?\\d+)]\\s*")
        val chapterPrefix = Regex("^\\[Chap#(-?\\d+)]\\s*")
        val timestampParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            isLenient = false
        }
        val result = mutableListOf<SystemLogEntry>()
        var draft: Draft? = null
        var inDetails = false
        var ordinal = 0

        fun flush() {
            val current = draft ?: return
            val message = sanitize(current.messageLines.joinToString("\n"))
            val details = current.detailLines.joinToString("\n").takeIf { it.isNotBlank() }?.let(::sanitize)
            val canonical = listOf(
                current.ordinal.toString(),
                current.rawTimestamp,
                current.level.name,
                current.tag,
                current.projectId?.toString().orEmpty(),
                current.chapterIndex?.toString().orEmpty(),
                message,
                details.orEmpty()
            ).joinToString("\u001f")
            val timestamp = runCatching { timestampParser.parse(current.rawTimestamp)?.time }
                .getOrNull() ?: 0L
            result += SystemLogEntry(
                id = StableLogId.fromLegacyRecord(canonical),
                timestamp = timestamp,
                level = current.level,
                tag = sanitize(current.tag),
                message = message,
                details = details,
                projectId = current.projectId,
                chapterIndex = current.chapterIndex
            )
            draft = null
            inDetails = false
        }

        lines.forEach { line ->
            val match = header.find(line)
            if (match != null) {
                flush()
                var remainder = match.groupValues[4]
                val projectMatch = projectPrefix.find(remainder)
                val projectId = projectMatch?.groupValues?.get(1)?.toLongOrNull()
                if (projectMatch != null) remainder = remainder.removeRange(projectMatch.range).trimStart()
                val chapterMatch = chapterPrefix.find(remainder)
                val chapterIndex = chapterMatch?.groupValues?.get(1)?.toIntOrNull()
                if (chapterMatch != null) remainder = remainder.removeRange(chapterMatch.range).trimStart()
                draft = Draft(
                    rawTimestamp = match.groupValues[1],
                    level = runCatching { LogLevel.valueOf(match.groupValues[2]) }.getOrDefault(LogLevel.INFO),
                    tag = match.groupValues[3],
                    projectId = projectId,
                    chapterIndex = chapterIndex,
                    messageLines = mutableListOf(remainder),
                    detailLines = mutableListOf(),
                    ordinal = ordinal++
                )
            } else {
                val current = draft ?: return@forEach
                when {
                    line.startsWith("  Details: ") -> {
                        inDetails = true
                        current.detailLines += line.removePrefix("  Details: ")
                    }
                    inDetails && line.startsWith("  ") -> current.detailLines += line.removePrefix("  ")
                    inDetails -> current.detailLines += line
                    else -> current.messageLines += line
                }
            }
        }
        flush()
        return result
    }

    private fun trimMemoryLogs() {
        while (memoryLogs.size > MAX_MEMORY_LOGS) memoryLogs.removeLast()
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,}]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)[^\\s,}]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,}]+"), "\$1=[REDACTED]")
        .replace(Regex("(?s)\\b(?:Original|Translation|Prompt|Response)\\s*:\\s*.{0,4000}"), "[CONTENT REDACTED]")

    internal suspend fun awaitIdleForTests() {
        val completion = CompletableDeferred<Unit>()
        persistenceCommands.send(PersistenceCommand.Barrier(completion))
        completion.await()
    }

    internal suspend fun resetForTests() {
        val completion = CompletableDeferred<Unit>()
        persistenceCommands.send(PersistenceCommand.ResetForTests(completion))
        completion.await()
    }

    private fun resetStateForTests(completion: CompletableDeferred<Unit>) {
        activeLogFile = null
        pendingBeforeInitialization.clear()
        synchronized(stateLock) {
            initializedDirectoryPath = null
            logFile = null
            recoveryGeneration++
            memoryLogs.clear()
            _logsFlow.value = emptyList()
        }
        completion.complete(Unit)
    }
}
