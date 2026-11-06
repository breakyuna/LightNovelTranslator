package com.breakyuna.noveltranslator.core.exporter

/**
 * Rendering options for an Edition export.
 *
 * Defaults reproduce the historical single-language output exactly: the effective text of each
 * logical segment (Revision text, falling back to the original Edition per segment) written once.
 */
data class ExportOptions(
    /**
     * Emit 原文+译文 pairs per logical segment instead of the effective text alone.
     * The reader already has a bilingual mode (`PlatformReaderScreen`); this makes it exportable.
     */
    val bilingual: Boolean = false,
    /**
     * In bilingual mode, place the translation paragraph before the original paragraph.
     * Ignored unless [bilingual] is true.
     */
    val translationFirst: Boolean = true,
    /**
     * In bilingual mode, collapse segments whose translation equals the original into a single
     * paragraph. Mirrors LG's `deduplication_in_bilingual`; untranslated segments always collapse
     * regardless of this flag because the translation is missing, not equal.
     */
    val deduplicateIdentical: Boolean = true,
    /**
     * Embed shared illustrations into EPUB exports. When false, `[IMG:name]` markers stay literal
     * text and no image files are added to the package (the marker handling itself is unchanged).
     */
    val includeImages: Boolean = true,
)
