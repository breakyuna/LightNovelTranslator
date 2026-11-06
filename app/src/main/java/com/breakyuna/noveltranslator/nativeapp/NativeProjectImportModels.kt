package com.breakyuna.noveltranslator.nativeapp

import java.text.Normalizer
import java.util.Locale

/**
 * Pure planning rules for creating a project from selected TXT/EPUB files.
 *
 * The model only receives a display name, the reported byte size, an opaque source id and an
 * optional encoding label. It never opens, reads, fingerprints or uploads a file, so browser
 * credentials or file contents cannot reach it. Validation is fail-closed: any issue means the
 * whole selection cannot be submitted, and the caller is expected to create a new project rather
 * than replace an existing one.
 */

internal const val NATIVE_IMPORT_MAX_ASSET_BYTES: Long = 64L * 1024L * 1024L
private const val NATIVE_IMPORT_MAX_FILE_NAME_BYTES = 180
private const val NATIVE_IMPORT_MAX_EXTENSION_BYTES = 16
private const val NATIVE_IMPORT_MAX_ENCODING_CHARS = 40

private val NATIVE_IMPORT_ILLEGAL = Regex("""[\u0000-\u001f\u007f-\u009f/\\:*?"<>|]""")
private val NATIVE_IMPORT_WHITESPACE = Regex("""\s+""")
private val NATIVE_IMPORT_RESERVED_NAMES = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

internal enum class NativeImportFormat { TXT, EPUB }

/** The UI supplies an opaque source id (for example a SAF URI) and display metadata only. */
internal data class NativeImportCandidate(
    val sourceId: String,
    val fileName: String,
    val sizeBytes: Long,
    val encoding: String? = null,
)

internal enum class NativeImportIssueCode {
    UNSUPPORTED_EXTENSION,
    EMPTY_FILE,
    FILE_TOO_LARGE,
    DUPLICATE_SOURCE,
    DUPLICATE_NAME,
    PROJECT_NAME_CONFLICT,
}

internal data class NativeImportIssue(
    val sourceId: String,
    val fileName: String,
    val code: NativeImportIssueCode,
)

internal data class NativeImportFile(
    val sourceId: String,
    val fileName: String,
    val format: NativeImportFormat,
    val sizeBytes: Long,
    val order: Int,
    val encoding: String? = null,
)

/**
 * How the first accepted file seeds the new project. An EPUB becomes the first asset; a TXT
 * starts the project from text. Later files are additional assets in selection order.
 */
internal enum class NativeProjectSeed { EPUB_FIRST_ASSET, TXT_TEXT }

internal data class NativeProjectImportReview(
    val files: List<NativeImportFile> = emptyList(),
    val issues: List<NativeImportIssue> = emptyList(),
    val projectName: String? = null,
    val seed: NativeProjectSeed? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val canCreate: Boolean
        get() = files.isNotEmpty() && issues.isEmpty() && projectName != null && seed != null
}

internal fun detectNativeImportFormat(rawName: String): NativeImportFormat? {
    val leaf = rawName.substringAfterLast('/').substringAfterLast('\\')
    val normalized = Normalizer.normalize(leaf, Normalizer.Form.NFKC).trim(' ', '.')
    val dot = normalized.lastIndexOf('.')
    if (dot <= 0 || dot == normalized.lastIndex) return null
    return when (normalized.substring(dot + 1).lowercase(Locale.ROOT)) {
        "txt" -> NativeImportFormat.TXT
        "epub" -> NativeImportFormat.EPUB
        else -> null
    }
}

/**
 * Produces one portable file name from an untrusted display name. The result contains no path
 * separators or control characters, is NFKC-normalized, reserves the complete extension inside a
 * UTF-8 byte budget and never splits a surrogate pair. The format check stays separate so a
 * truncated unsupported extension can never accidentally become TXT or EPUB.
 */
internal fun sanitizeNativeImportFileName(rawName: String): String {
    val leaf = rawName.substringAfterLast('/').substringAfterLast('\\')
    val normalized = Normalizer.normalize(leaf, Normalizer.Form.NFKC)
        .replace(NATIVE_IMPORT_ILLEGAL, "_")
        .replace(NATIVE_IMPORT_WHITESPACE, " ")
        .trim(' ', '.')
    val dot = normalized.lastIndexOf('.')
    val rawExtension = if (dot > 0 && dot < normalized.lastIndex) {
        normalized.substring(dot + 1).lowercase(Locale.ROOT)
    } else {
        ""
    }
    val recognized = detectNativeImportFormat(rawName)
    val suffix = when {
        recognized != null -> "." + recognized.extension()
        rawExtension.isNotEmpty() -> "." + truncateUtf8(rawExtension, NATIVE_IMPORT_MAX_EXTENSION_BYTES)
        else -> ""
    }
    var base = if (dot > 0) normalized.substring(0, dot) else normalized
    base = base.trim(' ', '.')
    if (base.isEmpty()) base = "asset"
    if (base.substringBefore('.').uppercase(Locale.ROOT) in NATIVE_IMPORT_RESERVED_NAMES) {
        base = "_$base"
    }
    val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
    val budget = (NATIVE_IMPORT_MAX_FILE_NAME_BYTES - suffixBytes).coerceAtLeast(1)
    base = truncateUtf8(base, budget).trim(' ', '.')
    if (base.isEmpty()) base = "asset"
    return base + suffix
}

/**
 * Reviews one selection in order. [existingProjectNames] is required so the caller must pass the
 * current project registry; a matching name becomes an issue rather than an overwrite request.
 */
internal fun reviewNativeProjectImport(
    candidates: List<NativeImportCandidate>,
    existingProjectNames: Set<String>,
): NativeProjectImportReview {
    val files = ArrayList<NativeImportFile>()
    val issues = ArrayList<NativeImportIssue>()
    val usedSources = HashSet<String>()
    val usedNames = HashSet<String>()

    for (candidate in candidates) {
        val safeName = sanitizeNativeImportFileName(candidate.fileName)
        val format = detectNativeImportFormat(candidate.fileName)
        val basicIssue = when {
            format == null -> NativeImportIssueCode.UNSUPPORTED_EXTENSION
            candidate.sizeBytes <= 0L -> NativeImportIssueCode.EMPTY_FILE
            candidate.sizeBytes > NATIVE_IMPORT_MAX_ASSET_BYTES -> NativeImportIssueCode.FILE_TOO_LARGE
            else -> null
        }
        if (basicIssue != null) {
            issues += NativeImportIssue(candidate.sourceId, safeName, basicIssue)
            continue
        }
        val duplicateIssue = when {
            !usedSources.add(candidate.sourceId) -> NativeImportIssueCode.DUPLICATE_SOURCE
            !usedNames.add(safeName.lowercase(Locale.ROOT)) -> NativeImportIssueCode.DUPLICATE_NAME
            else -> null
        }
        if (duplicateIssue != null) {
            issues += NativeImportIssue(candidate.sourceId, safeName, duplicateIssue)
            continue
        }
        files += NativeImportFile(
            sourceId = candidate.sourceId,
            fileName = safeName,
            format = requireNotNull(format),
            sizeBytes = candidate.sizeBytes,
            order = files.size,
            encoding = cleanEncodingLabel(candidate.encoding),
        )
    }

    val first = files.firstOrNull()
    val projectName = first?.fileName?.substringBeforeLast('.')?.trim(' ', '.')
        ?.let { truncateUtf8(it, NATIVE_IMPORT_MAX_FILE_NAME_BYTES).trim(' ', '.') }
        ?.takeIf { it.isNotEmpty() }
    val normalizedExisting = existingProjectNames.asSequence()
        .map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
        .filter { it.isNotEmpty() }
        .map { it.lowercase(Locale.ROOT) }
        .toSet()
    if (first != null && projectName != null && projectName.lowercase(Locale.ROOT) in normalizedExisting) {
        // The caller must pass the project registry names. A collision blocks creation instead of
        // overwriting or silently picking another project.
        issues += NativeImportIssue(first.sourceId, projectName, NativeImportIssueCode.PROJECT_NAME_CONFLICT)
    }
    val seed = when (first?.format) {
        NativeImportFormat.EPUB -> NativeProjectSeed.EPUB_FIRST_ASSET
        NativeImportFormat.TXT -> NativeProjectSeed.TXT_TEXT
        null -> null
    }
    return NativeProjectImportReview(files.toList(), issues.toList(), projectName, seed)
}

internal fun NativeImportFormat.extension(): String = when (this) {
    NativeImportFormat.TXT -> "txt"
    NativeImportFormat.EPUB -> "epub"
}

private fun cleanEncodingLabel(value: String?): String? = value
    ?.filterNot { it.isISOControl() }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { truncateUtf8(it, NATIVE_IMPORT_MAX_ENCODING_CHARS) }

private fun truncateUtf8(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    val result = StringBuilder()
    var used = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val chars = Character.charCount(codePoint)
        val piece = String(Character.toChars(codePoint))
        val bytes = piece.toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        result.append(piece)
        used += bytes
        index += chars
    }
    return result.toString()
}
