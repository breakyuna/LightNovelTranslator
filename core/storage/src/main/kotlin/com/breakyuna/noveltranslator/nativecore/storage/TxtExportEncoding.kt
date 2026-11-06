package com.breakyuna.noveltranslator.nativecore.storage

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Best-effort preservation of a TXT asset's original charset/BOM and line separator for the
 * whole-project translation export. If the translated text cannot be represented in the original
 * charset, the caller retries the complete export as UTF-8; a partially encoded file is never
 * published. This is an export projection, not a project format upgrade.
 */
internal class TxtExportEncoding(
    val charset: Charset,
    val bom: ByteArray,
    val newline: String,
) {
    fun encode(text: String): ByteArray {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(body.remaining()).also { body.get(it) }
    }

    fun fallbackUtf8(): TxtExportEncoding = TxtExportEncoding(
        Charsets.UTF_8,
        if (bom.isEmpty()) ByteArray(0) else byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
        newline,
    )

    companion object {
        fun detect(prefix: ByteArray): TxtExportEncoding {
            val (charset, bom, offset) = when {
                prefix.size >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() &&
                    prefix[2] == 0xBF.toByte() -> Triple(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), 3)
                prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte() ->
                    Triple(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), 2)
                prefix.size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte() ->
                    Triple(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()), 2)
                validUtf8Prefix(prefix) -> Triple(Charsets.UTF_8, ByteArray(0), 0)
                else -> {
                    val legacy = listOf("GB18030", "Shift_JIS")
                        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
                        .firstOrNull { valid(it, prefix) }
                    Triple(legacy ?: Charsets.UTF_8, ByteArray(0), 0)
                }
            }
            val text = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prefix, offset, prefix.size - offset)).toString()
            } catch (_: CharacterCodingException) {
                ""
            }
            val newline = when {
                text.contains("\r\n") -> "\r\n"
                text.contains('\r') -> "\r"
                else -> "\n"
            }
            return TxtExportEncoding(charset, bom, newline)
        }

        /** A bounded read may cut the final code point; do not misclassify that as legacy text. */
        private fun validUtf8Prefix(bytes: ByteArray): Boolean {
            if (valid(Charsets.UTF_8, bytes)) return true
            for (drop in 1..3) {
                if (bytes.size > drop && valid(Charsets.UTF_8, bytes.copyOfRange(0, bytes.size - drop))) return true
            }
            return false
        }

        private fun valid(charset: Charset, bytes: ByteArray): Boolean = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) { false }
    }
}
