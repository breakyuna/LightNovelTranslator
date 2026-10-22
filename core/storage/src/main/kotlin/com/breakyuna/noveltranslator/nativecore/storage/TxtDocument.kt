package com.breakyuna.noveltranslator.nativecore.storage

import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectLimits
import com.breakyuna.noveltranslator.nativecore.domain.TxtEncoding
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Explicit encoding with BOM detection; never guesses a lossy replacement decoding. */
internal object TxtDocument {
    fun lines(bytes: ByteArray, encoding: TxtEncoding, limits: TextProjectLimits): List<String> {
        if (bytes.size > limits.maxSourceBytes) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val bom = when {
            bytes.starts(0xef, 0xbb, 0xbf) -> TxtEncoding.UTF8 to 3
            bytes.starts(0xff, 0xfe, 0, 0) || bytes.starts(0, 0, 0xfe, 0xff) ->
                fail(TextProjectException.Reason.INVALID_TEXT) // UTF-32 is not supported by this slice.
            bytes.starts(0xff, 0xfe) -> TxtEncoding.UTF16LE to 2
            bytes.starts(0xfe, 0xff) -> TxtEncoding.UTF16BE to 2
            else -> encoding to 0
        }
        val text = try {
            Charset.forName(bom.first.charsetName).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, bom.second, bytes.size - bom.second)).toString()
        } catch (_: CharacterCodingException) {
            fail(TextProjectException.Reason.INVALID_TEXT)
        } catch (_: IllegalArgumentException) {
            fail(TextProjectException.Reason.INVALID_TEXT)
        }
        val result = ArrayList<String>()
        fun append(start: Int, end: Int) {
            if (end - start > limits.maxItemChars || result.size >= limits.maxItems) {
                fail(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            result.add(text.substring(start, end))
        }
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] in "\n\u000b\u000c\r\u001c\u001d\u001e\u0085\u2028\u2029") {
                append(start, index)
                if (text[index] == '\r' && text.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
            index++
        }
        // Matches LG splitlines: empty input has zero items; no synthetic final empty item.
        if (start < text.length) append(start, text.length)
        return result
    }

    private fun ByteArray.starts(vararg values: Int): Boolean = size >= values.size &&
        values.indices.all { (this[it].toInt() and 255) == values[it] }

    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)
}
