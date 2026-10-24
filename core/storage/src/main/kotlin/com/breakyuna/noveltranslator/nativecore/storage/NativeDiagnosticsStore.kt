package com.breakyuna.noveltranslator.nativecore.storage

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NativeDiagnosticLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Lightweight projection used by list pages. It never carries the optional diagnostic
 * detail; call [NativeDiagnosticsStore.read] with the stable [id] for that.
 */
class NativeDiagnosticSummary(
    val id: String,
    val timestampMillis: Long,
    val sequence: Long,
    val level: NativeDiagnosticLevel,
    val category: String,
    val message: String,
    val hasDetail: Boolean,
) {
    init {
        require(id.isNotEmpty())
        require(timestampMillis >= 0)
        require(sequence >= 1)
        require(category.isNotEmpty())
    }

    override fun toString() =
        "NativeDiagnosticSummary(id=$id, level=$level, category=$category, detail=$hasDetail)"
}

/** Full record returned only for an explicit id lookup or export. */
class NativeDiagnosticRecord(
    val id: String,
    val timestampMillis: Long,
    val sequence: Long,
    val level: NativeDiagnosticLevel,
    val category: String,
    val message: String,
    val detail: String?,
) {
    init {
        require(id.isNotEmpty())
        require(timestampMillis >= 0)
        require(sequence >= 1)
        require(category.isNotEmpty())
    }

    fun summary(): NativeDiagnosticSummary =
        NativeDiagnosticSummary(id, timestampMillis, sequence, level, category, message, detail != null)

    override fun toString() =
        "NativeDiagnosticRecord(id=$id, level=$level, category=$category, detail=${detail != null})"
}

/**
 * Stable, typed page cursor. [token] is the only value a UI needs to keep in saved state;
 * it is invalidated by [NativeDiagnosticsStore.clear] or a replaced diagnostics directory.
 */
class NativeDiagnosticCursor(val storeId: String, val beforeSequence: Long) {
    init {
        require(storeId.length in 1..64 && storeId.none(Char::isISOControl))
        require(beforeSequence >= 1)
    }

    val token: String get() = "$storeId:$beforeSequence"

    override fun toString() = "NativeDiagnosticCursor(beforeSequence=$beforeSequence)"

    companion object {
        fun parse(token: String): NativeDiagnosticCursor {
            require(token.length <= 96 && token.none(Char::isISOControl)) { "Invalid diagnostics cursor" }
            val separator = token.lastIndexOf(':')
            require(separator > 0 && separator < token.lastIndex) { "Invalid diagnostics cursor" }
            val storeId = token.substring(0, separator)
            val sequence = token.substring(separator + 1).toLongOrNull()
                ?: throw IllegalArgumentException("Invalid diagnostics cursor")
            return NativeDiagnosticCursor(storeId, sequence)
        }
    }
}

class NativeDiagnosticPage(
    entries: List<NativeDiagnosticSummary>,
    val nextCursor: NativeDiagnosticCursor?,
) {
    val entries: List<NativeDiagnosticSummary> = java.util.Collections.unmodifiableList(ArrayList(entries))
    init { require(this.entries.size <= NativeDiagnosticsLimits.MAX_PAGE_SIZE) }
}

class NativeDiagnosticsExport(val recordCount: Int, val bytes: Long) {
    init { require(recordCount >= 0 && bytes >= 0) }
    override fun toString() = "NativeDiagnosticsExport(records=$recordCount, bytes=$bytes)"
}

class NativeDiagnosticsLimits(
    val maxFileBytes: Int = 512 * 1024,
    val maxFiles: Int = 4,
    val maxMessageChars: Int = 512,
    val maxDetailChars: Int = 4_096,
) {
    init {
        require(maxFileBytes in 256..(16 * 1024 * 1024))
        require(maxFiles in 2..16)
        require(maxMessageChars in 32..8_192)
        require(maxDetailChars in 0..(64 * 1024))
    }

    internal val maxLineChars: Int
        get() = ((maxMessageChars.toLong() + maxDetailChars.toLong()) * 6L + 1_024L).toInt()

    companion object {
        const val MAX_PAGE_SIZE = 200
    }
}

/**
 * Append-only, bounded JSONL diagnostics owned by the application data directory.
 *
 * Layout: `diagnostics.jsonl` plus `.1` (newest rotated) … `.{maxFiles - 1}`. A record is
 * written with one append and a trailing newline; startup/append repairs a trailing partial
 * line left by an interrupted process. Rotation is size based and keeps at most
 * [NativeDiagnosticsLimits.maxFiles] files. Appends and reads share one process lock, so
 * concurrent callers cannot interleave bytes or observe a half-rotated directory.
 *
 * Security boundary: every message and detail passes [NativeDiagnosticRedaction] before it
 * reaches disk. Detail text is dropped unless [includeDetails] is enabled, which is the
 * default. This store must never be handed prompts, model response bodies, source text or
 * credentials; redaction is a final safety net, not permission to log that material.
 *
 * All methods perform blocking file IO on [Dispatchers.IO]. [export] flushes but does not
 * close the caller-owned stream.
 */
class NativeDiagnosticsStore(
    private val directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val limits: NativeDiagnosticsLimits = NativeDiagnosticsLimits(),
    private val includeDetails: Boolean = false,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private var nextSequence = 0L
    private var storeId: String? = null

    suspend fun append(
        level: NativeDiagnosticLevel,
        category: String,
        message: String,
        detail: String? = null,
    ): NativeDiagnosticSummary = withContext(Dispatchers.IO) {
        val safeCategory = normalizeCategory(category)
        val safeMessage = NativeDiagnosticRedaction.singleLine(message, limits.maxMessageChars)
        require(safeMessage.isNotBlank()) { "Diagnostic message is empty" }
        val safeDetail = if (includeDetails && detail != null) {
            NativeDiagnosticRedaction.detail(detail, limits.maxDetailChars)
        } else {
            null
        }
        synchronized(PROCESS_LOCK) {
            ensureDirectory()
            ensureStoreId()
            repairActiveFile()
            val record = NativeDiagnosticRecord(
                id = normalizeId(idGenerator()),
                timestampMillis = clock().coerceAtLeast(0),
                sequence = allocateSequence(),
                level = level,
                category = safeCategory,
                message = safeMessage,
                detail = safeDetail,
            )
            appendRecord(record)
            record.summary()
        }
    }

    /** Newest-first page. A foreign, stale or cleared cursor is rejected, never guessed. */
    suspend fun query(limit: Int = 50, cursor: NativeDiagnosticCursor? = null): NativeDiagnosticPage =
        withContext(Dispatchers.IO) {
            require(limit in 1..NativeDiagnosticsLimits.MAX_PAGE_SIZE) { "Invalid page size" }
            synchronized(PROCESS_LOCK) {
                    ensureDirectory()
                val currentStoreId = ensureStoreId()
                require(cursor == null || cursor.storeId == currentStoreId) { "Stale diagnostics cursor" }
                val records = readAllRecords().sortedByDescending { it.sequence }
                val remaining = if (cursor == null) records else records.filter { it.sequence < cursor.beforeSequence }
                val visible = remaining.take(limit).map { it.summary() }
                val next = if (remaining.size > limit && visible.isNotEmpty()) {
                    NativeDiagnosticCursor(currentStoreId, visible.last().sequence)
                } else {
                    null
                }
                NativeDiagnosticPage(visible, next)
            }
        }

    suspend fun read(id: String): NativeDiagnosticRecord? = withContext(Dispatchers.IO) {
        val safeId = normalizeId(id)
        synchronized(PROCESS_LOCK) {
            ensureDirectory()
            ensureStoreId()
            repairActiveFile()
            readAllRecords().firstOrNull { it.id == safeId }
        }
    }

    /** Writes every complete record oldest first and flushes the caller-owned stream. */
    suspend fun export(output: OutputStream): NativeDiagnosticsExport = withContext(Dispatchers.IO) {
        synchronized(PROCESS_LOCK) {
            ensureDirectory()
            ensureStoreId()
            repairActiveFile()
            val buffered = BufferedOutputStream(output)
            var count = 0
            var bytes = 0L
            allFilesOldestFirst().forEach fileLoop@{ file ->
                if (!file.isFile) return@fileLoop
                readLines(file).forEach lineLoop@{ line ->
                            val record = NativeDiagnosticCodec.decode(line, limits) ?: return@lineLoop
                    val encoded = NativeDiagnosticCodec.encode(record).toByteArray(Charsets.UTF_8)
                    buffered.write(encoded)
                    buffered.write('\n'.code)
                    count++
                    bytes = Math.addExact(bytes, encoded.size.toLong() + 1)
                }
            }
            buffered.flush()
            NativeDiagnosticsExport(count, bytes)
        }
    }

    /**
     * Removes every generation and rotates the store id so a cursor issued before the clear
     * can never address newly appended records.
     */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(PROCESS_LOCK) {
            ensureDirectory()
            // Rotate the store id first so an interrupted clear can never make an old cursor
            // address records from the new history.
            writeStoreId(UUID.randomUUID().toString())
            rotatedFiles().forEach { file -> if (file.exists()) require(file.delete()) }
            FileOutputStream(activeFile(), false).use { output -> output.fd.sync() }
            nextSequence = 0L
        }
    }

    private fun appendRecord(record: NativeDiagnosticRecord) {
        val active = activeFile()
        val encoded = NativeDiagnosticCodec.encode(record).toByteArray(Charsets.UTF_8)
        if (active.isFile && active.length() > 0 && active.length() + encoded.size + 1L > limits.maxFileBytes) {
            rotate()
        }
        FileOutputStream(active, true).use { output ->
            output.write(encoded)
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
    }

    /**
     * `.1` is newest. The oldest generation is dropped, then each retained generation moves
     * one slot older. renameTo is used first; the copy fallback exists for filesystems where
     * rename cannot replace an existing file. A crash during rotation leaves fewer readable
     * generations, never a live file with foreign bytes appended.
     */
    private fun rotate() {
        val active = activeFile()
        for (index in limits.maxFiles - 1 downTo 2) {
            val from = File(directory, ACTIVE_NAME + "." + (index - 1))
            val to = File(directory, ACTIVE_NAME + "." + index)
            if (!from.isFile) continue
            if (to.exists()) require(to.delete())
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                require(from.delete())
            }
        }
        val newest = File(directory, ACTIVE_NAME + ".1")
        if (newest.exists()) require(newest.delete())
        if (active.isFile && !active.renameTo(newest)) {
            active.copyTo(newest, overwrite = true)
            require(active.delete())
        }
        if (!active.exists()) require(active.createNewFile())
    }

    /**
     * A JSONL append that was interrupted may leave a non-newline-terminated suffix. Truncate
     * it before the next append so a partial object can never be fused with a new record.
     */
    private fun repairActiveFile() {
        val active = activeFile()
        if (!active.isFile || active.length() == 0L) return
        RandomAccessFile(active, "rw").use { file ->
            val length = file.length()
            file.seek(length - 1)
            if (file.read() == '\n'.code) return
            val earliest = maxOf(0L, length - maxOf(limits.maxFileBytes.toLong() * 2L, limits.maxLineChars.toLong() * 2L))
            var cursor = length - 1
            var newline = -1L
            val buffer = ByteArray(8 * 1024)
            while (cursor >= earliest && newline < 0) {
                val chunk = minOf(buffer.size.toLong(), cursor - earliest + 1L).toInt()
                val start = cursor - chunk + 1L
                file.seek(start)
                file.readFully(buffer, 0, chunk)
                for (index in chunk - 1 downTo 0) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        newline = start + index
                        break
                    }
                }
                cursor = start - 1L
            }
            file.setLength(if (newline >= 0) newline + 1L else 0L)
        }
    }

    private fun allocateSequence(): Long {
        if (nextSequence < 1L) {
            val maximum = readAllRecords().maxOfOrNull { it.sequence } ?: 0L
            nextSequence = Math.addExact(maximum, 1L)
        }
        val sequence = nextSequence
        nextSequence = Math.addExact(nextSequence, 1L)
        return sequence
    }

    private fun readAllRecords(): List<NativeDiagnosticRecord> {
        val records = ArrayList<NativeDiagnosticRecord>()
        val ids = HashSet<String>()
        allFilesOldestFirst().forEach fileLoop@{ file ->
            if (!file.isFile) return@fileLoop
            readLines(file).forEach lineLoop@{ line ->
                val record = NativeDiagnosticCodec.decode(line, limits) ?: return@lineLoop
                if (ids.add(record.id)) records.add(record)
            }
        }
        return records
    }

    private fun readLines(file: File): List<String> {
        if (file.length() > limits.maxFileBytes.toLong() * 4L + limits.maxLineChars.toLong()) {
            // A foreign writer made the file larger than this store can have produced. Do not
            // allocate it unboundedly; the active file is repaired separately.
            return emptyList()
        }
        val lines = ArrayList<String>()
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank() && line.length <= limits.maxLineChars) lines.add(line)
            }
        }
        return lines
    }

    private fun normalizeCategory(value: String): String {
        val trimmed = value.trim()
        require(trimmed.length in 1..64 && trimmed.all { it.isLetterOrDigit() || it in "._-" }) {
            "Invalid diagnostics category"
        }
        return NativeDiagnosticRedaction.singleLine(trimmed, 64)
    }

    private fun normalizeId(value: String): String {
        require(value.length in 1..64 && value.none(Char::isISOControl)) { "Invalid diagnostic id" }
        return value
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create diagnostics directory" }
    }

    private fun ensureStoreId(): String {
        storeId?.let { return it }
        val existing = File(directory, STORE_ID_NAME)
        if (existing.isFile) {
            val text = runCatching { existing.readTextBounded(MAX_STORE_ID_BYTES) }.getOrNull()?.trim()
            if (text != null && validStoreId(text)) {
                storeId = text
                return text
            }
        }
        val next = UUID.randomUUID().toString()
        writeStoreId(next)
        return next
    }

    private fun writeStoreId(value: String) {
        require(validStoreId(value))
        val target = File(directory, STORE_ID_NAME)
        val temporary = File(directory, STORE_ID_NAME + ".tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (target.exists()) require(target.delete())
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            require(temporary.delete())
        }
        storeId = value
    }

    private fun validStoreId(value: String): Boolean =
        value.length in 1..64 && value.none(Char::isISOControl) && value.all { it.isLetterOrDigit() || it == '-' }

    private fun allFilesOldestFirst(): List<File> =
        rotatedFiles().asReversed().filter { it.isFile } + activeFile()

    private fun rotatedFiles(): List<File> =
        (1 until limits.maxFiles).map { File(directory, ACTIVE_NAME + "." + it) }

    private fun activeFile(): File = File(directory, ACTIVE_NAME)

    private fun File.readTextBounded(limit: Int): String {
        val bytes = ByteArray(limit + 1)
        FileInputStream(this).use { input ->
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= limit) { "Diagnostics metadata too large" }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString()
        }
    }

    private companion object {
        const val ACTIVE_NAME = "diagnostics.jsonl"
        const val STORE_ID_NAME = "diagnostics.store-id"
        const val MAX_STORE_ID_BYTES = 128
        val PROCESS_LOCK = Any()
    }
}

private object NativeDiagnosticCodec {
    private const val FORMAT = "lnt-diagnostics"
    private const val VERSION = 1L
    private val KEYS = setOf("format", "version", "id", "seq", "time", "level", "category", "message")
    private val KEYS_WITH_DETAIL = KEYS + "detail"

    fun encode(record: NativeDiagnosticRecord): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("id", record.id)
        put("seq", record.sequence)
        put("time", record.timestampMillis)
        put("level", record.level.name)
        put("category", record.category)
        put("message", record.message)
        record.detail?.let { put("detail", it) }
    }.toString()

    fun decode(line: String, limits: NativeDiagnosticsLimits): NativeDiagnosticRecord? {
        if (line.isBlank() || line.length > limits.maxLineChars) return null
        val root = try {
            JsonObjectValue.parse(line, JsonInputLimits(limits.maxLineChars, 4)).json
        } catch (_: Exception) {
            return null
        }
        if (root.keys != KEYS && root.keys != KEYS_WITH_DETAIL) return null
        if (string(root, "format") != FORMAT || integer(root, "version") != VERSION) return null
        val id = string(root, "id") ?: return null
        if (id.length !in 1..64 || id.any(Char::isISOControl)) return null
        val sequence = integer(root, "seq") ?: return null
        val time = integer(root, "time") ?: return null
        if (sequence < 1 || time < 0) return null
        val level = NativeDiagnosticLevel.entries.firstOrNull { it.name == string(root, "level") } ?: return null
        val category = string(root, "category") ?: return null
        if (category.length !in 1..64 || category.isEmpty()) return null
        val message = string(root, "message") ?: return null
        if (message.length > limits.maxMessageChars) return null
        val detail = if (root.containsKey("detail")) {
            val value = string(root, "detail") ?: return null
            if (value.length > limits.maxDetailChars) return null
            value
        } else {
            null
        }
        return NativeDiagnosticRecord(id, time, sequence, level, category, message, detail)
    }

    private fun string(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(root: JsonObject, key: String): Long? {
        val value = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: return null
        return value
    }
}
