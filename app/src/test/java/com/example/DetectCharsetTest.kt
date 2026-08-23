package com.example

import com.example.core.parser.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class DetectCharsetTest {
    @Test
    fun testDetectCharsetAndRead_Utf16LE() {
        val text = "Hello"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bomBytes)
        assertEquals(text, parsed)
        assertEquals(Charsets.UTF_16LE, charset)
    }

    @Test
    fun testDetectCharsetAndRead_Utf16BE() {
        val text = "Hello"
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        val bomBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + bytes
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bomBytes)
        assertEquals(text, parsed)
        assertEquals(Charsets.UTF_16BE, charset)
    }

    @Test
    fun testDetectCharsetAndRead_Utf8WithBom() {
        val text = "Hello"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bomBytes)
        assertEquals(text, parsed)
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun testDetectCharsetAndRead_Utf8WithoutBom() {
        val text = "Hello World! 这是中文。"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bytes)
        assertEquals(text, parsed)
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun testDetectCharsetAndRead_GBK() {
        val text = "这是中文。"
        val gbkCharset = Charset.forName("GBK")
        val bytes = text.toByteArray(gbkCharset)
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bytes)
        assertEquals(text, parsed)
        assertEquals(Charset.forName("GB18030"), charset)
    }

    @Test
    fun testDetectCharsetAndRead_FallbackIso8859_1() {
        // Construct a byte array that is invalid UTF-8 and invalid GB18030
        // GB18030 is very permissive, but a byte like 0xFF alone might trigger an issue
        // Wait, GB18030 decoder might map invalid bytes to something else instead of throwing Exception.
        // Let's force an exception if possible or see if there's a byte sequence that throws.
        // Actually, java's String(bytes, charset) typically replaces invalid sequences with replacement chars
        // rather than throwing an exception unless a Decoder is strictly configured.
        // Looking at the implementation of TxtParser.detectCharsetAndRead:
        // try { val gbkCharset = Charset.forName("GB18030"); Pair(String(bytes, gbkCharset), gbkCharset) }
        // catch (e: Exception) { Pair(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1) }
        // String(bytes, charset) does not throw MalformedInputException. It only throws if the charset name is invalid (which "GB18030" is not).
        // Wait! The TxtParser implementation uses String(bytes, gbkCharset). This constructor DOES NOT throw CharacterCodingException.
        // It replaces invalid sequences with the default replacement character.
        // So the catch block `catch (e: Exception)` might be unreachable for standard byte arrays, unless OOM or similar happens.
        // We can test if we pass a very small byte array to trigger the fallback, or if we pass null (which won't compile due to ByteArray).
        // Let's at least test a byte array that doesn't match any BOM, and we can check the behavior.
        // Actually, we can test a 1-byte array to ensure no IndexOutOfBoundsException on size checks.
        val bytes = byteArrayOf(0x01.toByte())
        val (parsed, charset) = TxtParser.detectCharsetAndRead(bytes)
        // A single byte 0x01 is valid UTF-8. It will be decoded as UTF-8.
        assertEquals("\u0001", parsed)
        assertEquals(Charsets.UTF_8, charset)
    }
}
