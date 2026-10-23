package com.breakyuna.noveltranslator.nativecore.formats

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Strict, bounded text decoding for format adapters. No replacement characters are accepted. */
class FormatTextCodec private constructor(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
) {
    fun encode(value: String): ByteArray {
        if ('\u0000' in value) throw InvalidFormatText(InvalidFormatText.Reason.CONTENT)
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try {
            val buffer = encoder.encode(CharBuffer.wrap(value))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            throw InvalidFormatText(InvalidFormatText.Reason.CONTENT)
        }
        if (body.size > MAX_BYTES) throw InvalidFormatText(InvalidFormatText.Reason.LIMIT)
        return bom + body
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024

        fun decode(bytes: ByteArray): FormatTextCodec {
            if (bytes.size > MAX_BYTES) throw InvalidFormatText(InvalidFormatText.Reason.LIMIT)
            val (charset, bomLength, bom) = when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    Triple(Charsets.UTF_8, 3, bytes.copyOfRange(0, 3))
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    Triple(Charsets.UTF_16LE, 2, bytes.copyOfRange(0, 2))
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    Triple(Charsets.UTF_16BE, 2, bytes.copyOfRange(0, 2))
                else -> Triple(guessWithoutBom(bytes), 0, ByteArray(0))
            }
            val text = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, bomLength, bytes.size - bomLength)).toString()
            } catch (_: CharacterCodingException) {
                throw InvalidFormatText(InvalidFormatText.Reason.ENCODING)
            }
            if ('\u0000' in text) throw InvalidFormatText(InvalidFormatText.Reason.CONTENT)
            return FormatTextCodec(text, charset, bom)
        }

        private fun guessWithoutBom(bytes: ByteArray): Charset {
            // Valid UTF-8 is the safest default. Only fall back to legacy CJK encodings when the
            // byte stream is not valid UTF-8 and the platform charset exists.
            if (validUtf8(bytes)) return Charsets.UTF_8
            for (name in listOf("GB18030", "Shift_JIS")) {
                val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
                if (valid(charset, bytes)) return charset
            }
            throw InvalidFormatText(InvalidFormatText.Reason.ENCODING)
        }

        private fun valid(charset: Charset, bytes: ByteArray): Boolean = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) { false }

        private fun validUtf8(bytes: ByteArray): Boolean = valid(Charsets.UTF_8, bytes)
    }
}

class InvalidFormatText(val reason: Reason) : IllegalArgumentException("Invalid format text: $reason") {
    enum class Reason { ENCODING, CONTENT, LIMIT }
}
