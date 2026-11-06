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
import kotlinx.coroutines.withContext
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
 *
 * On-disk layout (under `files/logs/`): `system_runtime.jsonl` plus the rotated generations
 * `.1` (newest) … `.3` (oldest), each capped at [MAX_FILE_BYTES], so roughly three days of a
 * busy translation run survive a restart. Every write goes through [LogSanitizer] first.
 */
object SystemLogger {
    internal const val JSONL_FILE_NAME = "system_runtime.jsonl"
    /** Newest rotated generation first, matching [ROTATED_SUFFIXES]. */
    internal val ROTATED_SUFFIXES = listOf(".1", ".2", ".3")
    internal const val MAX_MEMORY_LOGS = 600
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    private const val CLEANUP_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    private const val EXPORT_MAX_ENTRIES = 5_000

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
    private var recoveryGeneration = 0L
    private var activeLogFile: File? = null
    private var lastCleanupAt = 0L

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
        synchronized(stateLock) {
            memoryLogs.clear()
            _logsFlow.value = emptyList()
            logFile = null
            activeLogFile = null
            lastCleanupAt = 0L
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
            for (entry in snapshot) {
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

    /**
     * Newest [limit] entries from the persisted JSONL generations, oldest first, merged with any
     * in-memory entry that has not been flushed to disk yet (deduplicated by id).
     *
     * This is the read side of the persistence contract: after a crash and restart the memory
     * buffer is empty, so a UI that only looked at [logsFlow] would show nothing. Runs on
     * [Dispatchers.IO] and never throws — a missing or corrupt file yields an empty list.
     */
    suspend fun recentEntries(limit: Int): List<SystemLogEntry> {
        val requested = limit.coerceAtLeast(0)
        if (requested == 0) return emptyList()
        val persisted = withContext(Dispatchers.IO) { readPersistedTail(requested) }
        if (persisted.isEmpty()) return emptyList()
        val pending = logsFlow.value.filter { live -> persisted.none { it.id == live.id } }
        return (persisted + pending).sortedBy { it.timestamp }.takeLast(requested)
    }

    /**
     * Full text export of the persisted history for the share sheet / clipboard. Unlike
     * [exportLogsAsString] this survives a restart, because it reads the JSONL generations.
     */
    suspend fun exportText(): String {
        val entries = withContext(Dispatchers.IO) { readPersistedTail(EXPORT_MAX_ENTRIES) }
        return buildString {
            appendLine("=== NOVEL TRANSLATOR STUDIO - SYSTEM RUNTIME LOG ===")
            appendLine("Exported At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Persisted Entries: ${entries.size}")
            appendLine("Redaction: API keys, Authorization headers, tokens and book bodies are removed.")
            appendLine()
            entries.forEach { entry ->
                append("[${entry.formattedDate}] [${entry.level}] [${entry.tag}] ")
                if (entry.projectId != null) append("[Proj#${entry.projectId}] ")
                if (entry.chapterIndex != null) append("[Chap#${entry.chapterIndex}] ")
                appendLine(entry.message)
                if (!entry.details.isNullOrBlank()) {
                    appendLine("  >>> Details: ${entry.details}")
                }
            }
        }
    }

    /**
     * Reads the tail of the JSONL generations. Ordering is oldest generation first, so the
     * bounded deque naturally ends up with the newest [limit] entries in chronological order.
     */
    private fun readPersistedTail(limit: Int): List<SystemLogEntry> {
        val directory = logFile?.parentFile ?: return emptyList()
        val deque = ArrayDeque<SystemLogEntry>()
        orderedLogFiles(directory).forEach { file ->
            if (!file.exists()) return@forEach
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val entry = runCatching { adapter.fromJson(line) }.getOrNull() ?: return@forEach
                        deque.addLast(entry)
                        while (deque.size > limit) deque.removeFirst()
                    }
                }
            }
        }
        return deque.toList()
    }

    /** `[.3, .2, .1, active]` — chronological order across the rotation. */
    private fun orderedLogFiles(directory: File): List<File> {
        val active = logFile ?: File(directory, JSONL_FILE_NAME)
        return ROTATED_SUFFIXES.reversed().map { suffix ->
            File(directory, active.name + suffix)
        } + active
    }

    private fun publishAndEnqueue(entry: SystemLogEntry) {
        synchronized(stateLock) {
            memoryLogs.addLast(entry)
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
            if (!target.exists()) target.createNewFile()
            val now = System.currentTimeMillis()
            cleanupExpiredLogs(target, now)

            synchronized(stateLock) {
                // A newer directory command may have been queued while cleanup was running.
                // Never expose the stale file as active, otherwise an intervening log could be
                // appended to the directory that the caller has already replaced. Drain the
                // pending queue while holding the same lock so a concurrent init cannot clear
                // activeLogFile between the generation check and the append.
                if (commandGeneration != recoveryGeneration) return
                activeLogFile = target
                logFile = target
                lastCleanupAt = now
                pendingBeforeInitialization.forEach(::appendEntry)
                pendingBeforeInitialization.clear()
            }
            publishFromPersistenceWorker(
                createEntry(LogLevel.INFO, "SYSTEM", "System logger initialized successfully."),
                persist = true,
                expectedGeneration = commandGeneration
            )
        } catch (error: Exception) {
            synchronized(stateLock) {
                if (commandGeneration != recoveryGeneration) return
                activeLogFile = null
            }
            publishFromPersistenceWorker(
                createEntry(
                    LogLevel.WARN,
                    "SYSTEM",
                    "Failed to initialize log file: ${error.localizedMessage ?: error.javaClass.simpleName}"
                ),
                persist = false,
                expectedGeneration = commandGeneration
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

    private fun publishFromPersistenceWorker(
        entry: SystemLogEntry,
        persist: Boolean,
        expectedGeneration: Long? = null
    ) {
        synchronized(stateLock) {
            if (expectedGeneration != null && expectedGeneration != recoveryGeneration) return
            memoryLogs.addLast(entry)
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
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt >= CLEANUP_INTERVAL_MILLIS) {
            cleanupExpiredLogs(file, now)
            lastCleanupAt = now
        }
        if (file.length() > MAX_FILE_BYTES) rotate(file)
        file.appendText(adapter.toJson(entry) + "\n", Charsets.UTF_8)
    }

    /** Keep the historical log files useful without allowing unbounded growth. */
    private fun cleanupExpiredLogs(currentFile: File, now: Long) {
        val cutoff = now - RETENTION_MILLIS
        pruneJsonlFile(currentFile, cutoff)
        rotatedFiles(currentFile).forEach { rotated ->
            if (rotated.lastModified() < cutoff) {
                rotated.delete()
            } else {
                pruneJsonlFile(rotated, cutoff)
            }
        }
    }

    private fun rotatedFiles(currentFile: File): List<File> {
        val parent = currentFile.parentFile ?: return emptyList()
        return ROTATED_SUFFIXES.map { suffix -> File(parent, currentFile.name + suffix) }
    }

    private fun pruneJsonlFile(file: File, cutoff: Long) {
        if (!file.exists()) return
        val temporary = File(file.parentFile, file.name + ".retaining")
        runCatching {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val entry = runCatching { adapter.fromJson(line) }.getOrNull()
                        if (entry != null && entry.timestamp >= cutoff) {
                            writer.append(line)
                            writer.newLine()
                        }
                    }
                }
            }
            if (!temporary.renameTo(file)) {
                // Keep the original file in place until the replacement path is ready; the
                // copy fallback is for filesystems where renameTo cannot replace an existing file.
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }.onFailure {
            temporary.delete()
        }
    }

    /**
     * Rolls the active file to `.1` and shifts the existing generations up, dropping `.3`.
     * Three generations of [MAX_FILE_BYTES] keep the recent history readable after a restart
     * without letting the directory grow without bound.
     */
    private fun rotate(file: File) {
        val parent = file.parentFile ?: return
        File(parent, file.name + ROTATED_SUFFIXES.last()).takeIf { it.exists() }?.delete()
        for (index in ROTATED_SUFFIXES.size - 1 downTo 1) {
            val from = File(parent, file.name + ROTATED_SUFFIXES[index - 1])
            if (!from.exists()) continue
            val to = File(parent, file.name + ROTATED_SUFFIXES[index])
            if (!from.renameTo(to)) return
        }
        if (!file.renameTo(File(parent, file.name + ROTATED_SUFFIXES.first()))) return
        file.createNewFile()
    }

    private fun clearPersistedLogs() {
        val file = activeLogFile ?: logFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("", Charsets.UTF_8)
            rotatedFiles(file).forEach { rotated ->
                if (rotated.exists()) rotated.delete()
            }
        }
    }

    private fun trimMemoryLogs() {
        while (memoryLogs.size > MAX_MEMORY_LOGS) memoryLogs.removeFirst()
    }

    private fun sanitize(value: String): String = LogSanitizer.sanitize(value)

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
        lastCleanupAt = 0L
        pendingBeforeInitialization.clear()
        synchronized(stateLock) {
            logFile = null
            recoveryGeneration++
            memoryLogs.clear()
            _logsFlow.value = emptyList()
        }
        completion.complete(Unit)
    }
}
