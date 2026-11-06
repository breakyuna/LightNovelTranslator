package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.RelativePath

/**
 * Explicit safety bounds for the PDF adapter. They are prototype limits, not measured device
 * budgets; they only keep a malformed or hostile asset from allocating unbounded memory or
 * producing an output that the asset service would reject afterwards.
 *
 * Input and output byte bounds mirror [ProjectFormatService.MAX_INPUT_BYTES] and
 * [ProjectFormatService.MAX_OUTPUT_BYTES] so a PDF that passes this adapter never fails only
 * because the generic service rejects the same bytes.
 */
internal object PdfFormatBounds {
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 128 * 1024 * 1024
    const val MAX_PAGES = 5_000
    const val MAX_OUTPUT_PAGES = 50_000
    const val MAX_CONTINUATION_PAGES = 256
    const val MAX_UNITS = 100_000
    const val MAX_UNIT_CHARS = 65_536
    const val MAX_TOTAL_TEXT_CHARS = 32 * 1024 * 1024
    const val MAX_FONT_BYTES = 64 * 1024 * 1024
    const val MAX_PATH_CHARS = 4_096

    /** PDF readers tolerate junk before the header; the spec bounds the search to the first KiB. */
    private const val HEADER_SCAN = 1_024

    fun checkInput(bytes: ByteArray) {
        checkInputSize(bytes.size)
        if (!hasHeader(bytes)) throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
    }

    fun checkInputSize(size: Int) {
        if (size <= 0) throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        if (size > MAX_INPUT_BYTES) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
    }

    fun checkPath(path: RelativePath) {
        if (path.value.length > MAX_PATH_CHARS) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
    }

    fun checkPageCount(pages: Int) {
        if (pages <= 0) throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        if (pages > MAX_PAGES) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
    }

    fun checkPageText(length: Int, page: Int) {
        if (length > MAX_UNIT_CHARS) throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
    }

    fun checkEncrypted(encrypted: Boolean) {
        if (encrypted) throw PdfFormatException(PdfFormatException.Reason.ENCRYPTED)
    }

    fun checkOutputSize(size: Int) {
        if (size > MAX_OUTPUT_BYTES) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
    }

    fun checkOutputPages(pages: Int) {
        if (pages > MAX_OUTPUT_PAGES) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
    }

    /** True when the canonical `%PDF-` marker starts within the first [HEADER_SCAN] bytes. */
    fun hasHeader(bytes: ByteArray): Boolean {
        val lastStart = minOf(bytes.size, HEADER_SCAN) - 5
        var index = 0
        while (index <= lastStart) {
            if (bytes[index] == 0x25.toByte() &&
                bytes[index + 1] == 0x50.toByte() &&
                bytes[index + 2] == 0x44.toByte() &&
                bytes[index + 3] == 0x46.toByte() &&
                bytes[index + 4] == 0x2D.toByte()
            ) {
                return true
            }
            index += 1
        }
        return false
    }
}
