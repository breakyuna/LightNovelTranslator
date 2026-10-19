package com.breakyuna.noveltranslator.core.exporter

import java.util.Locale

/**
 * One logical segment's resolved texts. Either side may be null when the Edition has no mapping
 * for that logical position; blank strings are treated as missing.
 */
internal data class ExportSegment(
    val original: String?,
    val translation: String?,
) {
    /** Text the single-language export writes for this segment (translation, else original). */
    val effective: String
        get() = translation?.takeIf { it.isNotBlank() } ?: original?.takeIf { it.isNotBlank() } ?: ""

    /** True when a translation exists but the export still falls back to the original. */
    val isFallback: Boolean
        get() = effective.isNotBlank() && (translation == null || translation.isBlank())

    val isEmpty: Boolean
        get() = effective.isBlank()
}

/** One logical chapter of an export, before any rendering decisions. */
internal data class ExportChapterData(
    val index: Int,
    val title: String,
    val segments: List<ExportSegment>,
)

/** A rendered paragraph group; [isOriginal] marks the 原文 side of a bilingual pair. */
internal data class ExportBlock(
    val text: String,
    val isOriginal: Boolean,
)

/**
 * Pure rendering/statistics helpers shared by the TXT/EPUB writers and the precheck.
 *
 * Everything here is deterministic and IO-free so the export semantics (bilingual interleaving,
 * dedup, fallback, precheck counters, name safety) can be unit-tested without a database.
 * The helpers moved out of `EditionExporter` keep their original behavior verbatim.
 */
internal object ExportContent {

    private val PARAGRAPH_SEPARATOR = Regex("\\n\\s*\\n")
    private val IMAGE_MARKER = Regex("\\[IMG:([^\\]\\r\\n]*)\\]")

    /** Paragraph blocks of [chapter] under [options]; empty segments never produce a block. */
    fun chapterBlocks(chapter: ExportChapterData, options: ExportOptions): List<ExportBlock> {
        if (!options.bilingual) return chapter.segments.mapNotNull { segment ->
            val text = segment.effective
            if (text.isBlank()) null else ExportBlock(text, isOriginal = false)
        }
        val blocks = mutableListOf<ExportBlock>()
        for (segment in chapter.segments) {
            val translation = segment.translation?.takeIf { it.isNotBlank() }
            val original = segment.original?.takeIf { it.isNotBlank() }
            if (translation == null) {
                // 译文缺失：只输出原文（回退），不构成双语对
                if (original != null) blocks += ExportBlock(original, isOriginal = false)
                continue
            }
            if (original == null) {
                blocks += ExportBlock(translation, isOriginal = false)
                continue
            }
            if (options.deduplicateIdentical && translation == original) {
                blocks += ExportBlock(translation, isOriginal = false)
                continue
            }
            if (options.translationFirst) {
                blocks += ExportBlock(translation, isOriginal = false)
                blocks += ExportBlock(original, isOriginal = true)
            } else {
                blocks += ExportBlock(original, isOriginal = true)
                blocks += ExportBlock(translation, isOriginal = false)
            }
        }
        return blocks
    }

    /** Splits [text] on blank lines exactly like the EPUB writer's paragraph loop. */
    fun splitParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val paragraphs = mutableListOf<String>()
        var start = 0
        for (separator in PARAGRAPH_SEPARATOR.findAll(text)) {
            paragraphs += text.substring(start, separator.range.first).trim()
            start = separator.range.last + 1
        }
        paragraphs += text.substring(start).trim()
        return paragraphs.filter { it.isNotEmpty() }
    }

    /** Names referenced by `[IMG:name]` markers, normalized to a bare file name. */
    fun imageMarkerNames(text: String): List<String> =
        IMAGE_MARKER.findAll(text).map { java.io.File(it.groupValues[1].trim()).name }.toList()

    /** Statistics for a planned export; [availableImageNames] is the shared-images whitelist. */
    fun precheck(chapters: List<ExportChapterData>, availableImageNames: Set<String>): ExportPrecheckReport {
        var totalChapters = 0
        var totalSegments = 0
        var fallbackSegments = 0
        var emptySegments = 0
        var imageMarkers = 0
        var missingImageMarkers = 0
        var estimatedCharacters = 0
        for (chapter in chapters) {
            var chapterHasContent = false
            for (segment in chapter.segments) {
                totalSegments++
                if (segment.isFallback) fallbackSegments++
                if (segment.isEmpty) {
                    emptySegments++
                    continue
                }
                chapterHasContent = true
                val text = segment.effective
                estimatedCharacters += text.length
                val names = imageMarkerNames(text)
                imageMarkers += names.size
                missingImageMarkers += names.count { it.isBlank() || it !in availableImageNames }
            }
            if (chapterHasContent) totalChapters++
        }
        return ExportPrecheckReport(
            totalChapters = totalChapters,
            totalSegments = totalSegments,
            fallbackSegments = fallbackSegments,
            emptySegments = emptySegments,
            imageMarkers = imageMarkers,
            missingImageMarkers = missingImageMarkers,
            estimatedCharacters = estimatedCharacters,
        )
    }

    /** Moved verbatim from `EditionExporter.safe`: strips path/hostile characters from file names. */
    fun safeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        .map { if (it.code < 32 || it.code == 127) '_' else it }
        .joinToString("")
        .take(70).ifBlank { "edition" }

    /** Moved verbatim from `EditionExporter.xml`: string-level XML escaping. */
    fun escapeXml(value: String): String = value
        .filterNot(::isForbiddenXmlControl)
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** Moved verbatim from `EditionExporter.isForbiddenXmlControl`. */
    fun isForbiddenXmlControl(value: Char): Boolean =
        value.code == 0 || value.code == 127 || (value.code < 32 && value != '\t' && value != '\n' && value != '\r')

    /** Moved verbatim from `EditionExporter.isSafeArchiveName`: image/zip entry whitelist. */
    fun isSafeArchiveName(name: String): Boolean = name == java.io.File(name).name &&
        name.isNotBlank() && name != "." && name != ".." &&
        name.length <= 100 && name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    /** Moved verbatim from `EditionExporter.mimeType`. */
    fun mimeTypeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> "image/jpeg"
    }

    /** Moved verbatim from `EditionExporter.lang`. */
    fun languageCode(language: String): String = when {
        language.contains("zh", true) || language.contains("Chinese", true) -> "zh"
        language.contains("ja", true) -> "ja"
        language.contains("ko", true) -> "ko"
        else -> "en"
    }
}
