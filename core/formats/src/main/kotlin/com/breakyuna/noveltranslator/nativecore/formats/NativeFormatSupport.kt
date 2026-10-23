package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint

/**
 * Explicit safety bounds for the pure text format adapters. They are not measured device
 * budgets; they only keep a malformed asset from allocating unbounded memory or producing an
 * unbounded output before the caller can reject it.
 */
internal object TransRenpyFormatBounds {
    const val MAX_INPUT_CHARS = 8 * 1024 * 1024
    const val MAX_UNITS = 100_000
    const val MAX_UNIT_CHARS = 65_536
    const val MAX_EXTRA_CHARS = 65_536
    const val MAX_OUTPUT_CHARS = 32 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
    const val MAX_PATH_CHARS = 4_096
    const val MAX_JSON_DEPTH = 64

    fun requireReadable(text: String, path: RelativePath) {
        if (path.value.length > MAX_PATH_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
        strictUtf8(path.value)
        requireReadable(text)
    }

    fun requireReadable(text: String) {
        if (text.length > MAX_INPUT_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
        if (text.indexOf('\u0000') >= 0) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_TEXT)
        }
        val bytes = strictUtf8(text)
        if (bytes.size > MAX_INPUT_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
    }

    fun requireUnitText(value: String, row: Int) {
        if (value.length > MAX_UNIT_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, row)
        }
        if (value.indexOf('\u0000') >= 0) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_TEXT, row)
        }
        strictUtf8(value)
    }

    fun requireOutput(text: String) {
        if (text.length > MAX_OUTPUT_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
        if (text.indexOf('\u0000') >= 0) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_TEXT)
        }
        val bytes = strictUtf8(text)
        if (bytes.size > MAX_OUTPUT_BYTES) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
    }

    /** Strict UTF-8 encoding rejects isolated surrogates instead of silently replacing them. */
    fun strictUtf8(value: String): ByteArray = try {
        TextProjectFingerprint.utf8(value)
    } catch (_: Exception) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_TEXT)
    }
}

/** One classified adapter failure. Bodies are deliberately not part of the message. */
class InvalidTransRenpyFormat(
    val reason: Reason,
    val line: Int? = null,
) : IllegalArgumentException(
    "Invalid format document: $reason" + (if (line == null) "" else " (line $line)"),
) {
    enum class Reason {
        INVALID_TEXT,
        LIMIT,
        MALFORMED,
        MISSING_LOCATOR,
        STALE_LOCATOR,
        DUPLICATE_LOCATOR,
        UNSUPPORTED_OPTION,
        UNTERMINATED_STRING,
        INVALID_ESCAPE,
    }
}
