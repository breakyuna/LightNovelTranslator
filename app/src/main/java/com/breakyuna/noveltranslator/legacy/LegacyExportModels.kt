package com.breakyuna.noveltranslator.legacy

import java.io.File
import java.io.IOException
import java.io.Writer
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Options for a read-only legacy export.
 *
 * [maxFileBytes] and [maxTotalBytes] are hard limits. A payload file that exceeds
 * [maxFileBytes] is skipped and reported in the manifest; exceeding [maxTotalBytes] or
 * [maxEntries] aborts the package instead of publishing a partial export.
 */
data class LegacyExportOptions(
    val maxFileBytes: Long = 64L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
    val maxEntries: Int = 100_000,
    val includeTxt: Boolean = true,
    val includeEpub: Boolean = true,
    val bilingual: Boolean = false,
    val translationFirst: Boolean = true,
    val deduplicateIdentical: Boolean = true,
) {
    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }
}

enum class LegacyExportPhase {
    PREPARING,
    BOOK,
    EDITION,
    CHAT,
    FINALIZING,
    COMPLETED,
}

/** Best-effort progress notification; callbacks run on the export coroutine. */
data class LegacyExportProgress(
    val phase: LegacyExportPhase,
    val completed: Int,
    val total: Int,
    val bookId: Long? = null,
    val editionId: Long? = null,
    val path: String? = null,
)

/**
 * One recoverable failure. Book metadata, other books/editions and the global files are still
 * exported when a single item fails. Messages are redacted and never contain stack traces.
 */
data class LegacyExportFailure(
    val scope: String,
    val bookId: Long? = null,
    val editionId: Long? = null,
    val path: String? = null,
    val message: String,
)

data class LegacyExportFile(
    val path: String,
    val kind: String,
    val bytes: Long,
    val sha256: String,
    val entries: Int,
)

data class LegacyExportSourceInfo(
    val fileName: String,
    val schemaVersion: Int,
    val bytes: Long,
    val sha256: String,
)

data class LegacyExportManifest(
    val schema: String = "lnt-legacy-export",
    val version: Int = 1,
    val generatedAt: String,
    val generatedAtEpochMillis: Long,
    val sourceDatabase: LegacyExportSourceInfo,
    val books: Int,
    val editions: Int,
    val files: List<LegacyExportFile>,
    val failures: List<LegacyExportFailure>,
    val warnings: List<String>,
    val credentialsExcluded: Boolean = true,
    val migration: Boolean = false,
    val readOnly: Boolean = true,
) {
    val entryCount: Long get() = files.sumOf { it.entries.toLong() }
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

data class LegacyExportResult(
    val outputDirectory: File,
    val manifest: LegacyExportManifest,
    val manifestFile: File,
) {
    val failures: List<LegacyExportFailure> get() = manifest.failures
}

/** Global budget violation: no package is published. */
class LegacyExportLimitException(message: String) : IOException(message)

internal object LegacyExportTime {
    fun isoUtc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }
}

/**
 * Removes credential-shaped values before user-visible text reaches the package.
 * The API key table is never copied; this is the second line of defence for prompts,
 * chat messages, project style guides and stored provider receipts.
 */
internal object LegacyRedaction {
    private val replacements: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""enc:v1:[A-Za-z0-9+/=]+""") to { _: MatchResult -> "[REDACTED]" },
        Regex("""(?i)\bsk-[A-Za-z0-9_-]{8,}\b""") to { _: MatchResult -> "[REDACTED]" },
        Regex("""(?i)\bAIza[0-9A-Za-z_-]{20,}\b""") to { _: MatchResult -> "[REDACTED]" },
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""") to { _: MatchResult -> "Bearer [REDACTED]" },
        Regex(
            """(?i)\b(api[_-]?key|access[_-]?token|secret|password)\b\s*[:=]\s*("[^"]*"|'[^']*'|[^\s,;}]+)"""
        ) to { match: MatchResult -> "${match.groupValues[1]}=[REDACTED]" },
    )

    fun redact(value: String): String {
        var result = value
        for ((pattern, replacement) in replacements) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}

/**
 * File-name and relative-path hygiene. Names are sanitized per path segment, normalized with
 * NFKC, bounded by UTF-8 byte length (not UTF-16 characters), and reserved Windows/SAF names
 * are prefixed so the package stays portable.
 */
internal object LegacyExportPaths {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")
    private val WHITESPACE = Regex("""\s+""")
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    fun safeSegment(value: String, fallback: String = "item", maxBytes: Int = 64): String {
        require(maxBytes >= 8) { "maxBytes is too small" }
        var safe = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(ILLEGAL, "_")
            .replace(WHITESPACE, " ")
            .trim(' ', '.')
        if (safe.isBlank() || safe == "." || safe == "..") safe = fallback
        if (safe.substringBefore('.').uppercase(Locale.ROOT) in RESERVED) safe = "_$safe"
        safe = truncateUtf8(safe, maxBytes).trim(' ', '.')
        return safe.ifBlank { fallback }
    }

    /**
     * Reserves [desired] against [used] (case-insensitive, including names that SAF providers
     * may map to the same name) and appends `_2`, `_3`, ... on collision.
     */
    fun uniqueName(
        desired: String,
        extension: String,
        used: MutableSet<String>,
        maxBytes: Int = 96,
    ): String {
        val normalizedExtension = if (extension.isBlank() || extension.startsWith(".")) {
            extension
        } else {
            ".$extension"
        }
        val extensionBytes = normalizedExtension.toByteArray(Charsets.UTF_8).size
        require(maxBytes > extensionBytes + 8) { "maxBytes is too small for the extension" }
        val base = desired.ifBlank { "item" }
        var counter = 1
        while (true) {
            val suffix = if (counter == 1) "" else "_$counter"
            val budget = maxBytes - extensionBytes - suffix.toByteArray(Charsets.UTF_8).size
            val candidate = truncateUtf8(base, budget).ifBlank { "item" } + suffix + normalizedExtension
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            counter++
            check(counter < 100_000) { "Unable to allocate a unique file name" }
        }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + pieceBytes > maxBytes) break
            builder.append(piece)
            used += pieceBytes
            index += chars
        }
        return builder.toString()
    }
}

/**
 * Small streaming JSON writer used so large chat/glossary files are never materialized as one
 * giant string. It is deliberately dependency-free and API 24 compatible.
 */
internal class LegacyJsonWriter(private val out: Writer) {
    private val kinds = ArrayList<Char>()
    private val counts = ArrayList<Int>()
    private var pendingName = false
    private var rootWritten = false

    fun beginObject() = begin('{')
    fun endObject() = end('{')
    fun beginArray() = begin('[')
    fun endArray() = end('[')

    fun name(name: String) {
        check(kinds.lastOrNull() == '{') { "JSON name outside an object" }
        check(!pendingName) { "JSON name already pending" }
        if (counts.last() > 0) out.write(','.code)
        writeQuoted(name)
        out.write(':'.code)
        pendingName = true
    }

    fun value(value: String?) {
        beforeValue()
        if (value == null) out.write("null") else writeQuoted(value)
    }

    fun value(value: Boolean) {
        beforeValue()
        out.write(if (value) "true" else "false")
    }

    fun value(value: Long) {
        beforeValue()
        out.write(value.toString())
    }

    fun value(value: Int) = value(value.toLong())

    fun value(value: Double) {
        require(value.isFinite()) { "JSON cannot represent a non-finite number" }
        beforeValue()
        out.write(value.toString())
    }

    fun nullValue() {
        beforeValue()
        out.write("null")
    }

    fun field(name: String, value: String?) {
        name(name)
        value(value)
    }

    fun field(name: String, value: Boolean) {
        name(name)
        value(value)
    }

    fun field(name: String, value: Long) {
        name(name)
        value(value)
    }

    fun field(name: String, value: Int) {
        name(name)
        value(value)
    }

    fun field(name: String, value: Double) {
        name(name)
        value(value)
    }

    fun nullField(name: String) {
        name(name)
        nullValue()
    }

    fun flush() = out.flush()

    private fun begin(kind: Char) {
        beforeValue()
        out.write(kind.code)
        kinds += kind
        counts += 0
    }

    private fun end(kind: Char) {
        check(kinds.isNotEmpty() && kinds.last() == kind) { "Mismatched JSON container" }
        check(!pendingName) { "JSON object has a name without a value" }
        kinds.removeAt(kinds.lastIndex)
        counts.removeAt(counts.lastIndex)
        out.write(if (kind == '{') '}'.code else ']'.code)
    }

    private fun beforeValue() {
        if (kinds.isEmpty()) {
            check(!rootWritten) { "JSON already has a root value" }
            rootWritten = true
            return
        }
        val kind = kinds.last()
        if (kind == '{') {
            check(pendingName) { "JSON object value requires a name" }
            pendingName = false
        } else {
            if (counts.last() > 0) out.write(','.code)
        }
        counts[counts.lastIndex] = counts.last() + 1
    }

    private fun writeQuoted(value: String) {
        out.write('"'.code)
        for (char in value) {
            when (char) {
                '"' -> out.write("\\\"")
                '\\' -> out.write("\\\\")
                '\b' -> out.write("\\b")
                '\u000C' -> out.write("\\f")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                else -> {
                    if (char.code < 0x20) {
                        out.write("\\u")
                        out.write(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.write(char.code)
                    }
                }
            }
        }
        out.write('"'.code)
    }
}
