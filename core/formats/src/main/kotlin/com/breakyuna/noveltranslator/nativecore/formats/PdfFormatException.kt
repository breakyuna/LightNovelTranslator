package com.breakyuna.noveltranslator.nativecore.formats

/**
 * Classified PDF adapter failure. The message only carries the reason and an optional page number;
 * page text, font data and file names are never part of it.
 */
class PdfFormatException(
    val reason: Reason,
    val page: Int? = null,
) : IllegalArgumentException(
    "PDF format: $reason" + (if (page == null) "" else " (page $page)"),
) {
    enum class Reason {
        /** The input is unreadable, truncated or does not carry a PDF header. */
        CORRUPT,

        /** The input is password protected; the adapter never guesses a password. */
        ENCRYPTED,

        /** PDFBox-Android assets were not initialized through [PdfBoxRuntime.initialize]. */
        RESOURCES,

        /** A size, page, character, continuation or output budget was exceeded. */
        LIMIT,

        /** A unit does not bind to exactly one page of the frozen original. */
        BINDING,

        /** Two units claim the same page. */
        DUPLICATE,

        /** A page is marked translate but has no final translation. */
        MISSING_TRANSLATION,

        /** Every page was omitted; an empty PDF is not a valid export. */
        EMPTY_OUTPUT,

        /** Page text could not be extracted from an otherwise loadable document. */
        EXTRACT,

        /** An original page could not be rasterized or encoded as a bounded PNG. */
        RENDER,

        /** No Unicode-capable font could represent the replacement text. */
        FONT,

        /**
         * A page-changing write would detach document structure the page-copy writer cannot
         * rebuild, currently interactive form fields and embedded files. The caller must leave the
         * original asset untouched instead of exporting a silently broken catalog.
         */
        UNSUPPORTED,
    }
}
