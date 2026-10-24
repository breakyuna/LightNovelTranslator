package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Test-only, independently written archive fixtures. The v1/v3 encryption here is intentionally
 * separate from production code so a matching bug in reader and writer cannot hide a format error.
 */
internal object RgssFixtures {
    const val V1_KEY: Int = -0x21523502 // 0xDEADCAFE as a signed 32-bit value

    class Entry(
        val name: String,
        val data: ByteArray,
        val compressed: Boolean = false,
    )

    fun v1(entries: List<Entry>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(0x52)
        output.write(0x47)
        output.write(0x53)
        output.write(0x53)
        output.write(0x41)
        output.write(0x44)
        output.write(0x00)
        output.write(0x01)
        var key = V1_KEY
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            writeI32(output, name.size xor key)
            key = (key * 7) + 3
            for (byte in name) {
                output.write((byte.toInt() xor (key and 0xFF)) and 0xFF)
                key = (key * 7) + 3
            }
            writeI32(output, entry.data.size xor key)
            key = (key * 7) + 3
            val encrypted = crypt(entry.data, key)
            output.write(encrypted, 0, encrypted.size)
        }
        return output.toByteArray()
    }

    fun v3(entries: List<Entry>, seed: Int = V1_KEY): ByteArray {
        val base = (seed * 9) + 3
        val payloads = entries.map { if (it.compressed) deflate(it.data) else it.data }
        var tableSize = 12 + 16
        for (entry in entries) tableSize += 16 + entry.name.toByteArray(Charsets.UTF_8).size
        val output = ByteArrayOutputStream()
        output.write(0x52)
        output.write(0x47)
        output.write(0x53)
        output.write(0x53)
        output.write(0x41)
        output.write(0x44)
        output.write(0x00)
        output.write(0x03)
        writeI32(output, seed)
        var dataOffset = tableSize
        for (index in entries.indices) {
            val entry = entries[index]
            val payload = payloads[index]
            val name = entry.name.toByteArray(Charsets.UTF_8)
            writeI32(output, dataOffset xor base)
            writeI32(output, payload.size xor base)
            // Per-file key is `base`; the stored field is 0 because the table XORs with base.
            writeI32(output, 0)
            writeI32(output, name.size xor base)
            for (byteIndex in name.indices) {
                val shift = 8 * (byteIndex % 4)
                output.write((name[byteIndex].toInt() xor ((base ushr shift) and 0xFF)) and 0xFF)
            }
            dataOffset += payload.size
        }
        writeI32(output, base)
        writeI32(output, base)
        writeI32(output, base)
        writeI32(output, base)
        for (payload in payloads) {
            val encrypted = crypt(payload, base)
            output.write(encrypted, 0, encrypted.size)
        }
        return output.toByteArray()
    }

    fun crypt(input: ByteArray, startKey: Int): ByteArray {
        val output = ByteArray(input.size)
        var key = startKey
        var position = 0
        while (input.size - position >= 4) {
            output[position] = (input[position].toInt() xor (key and 0xFF)).toByte()
            output[position + 1] = (input[position + 1].toInt() xor ((key ushr 8) and 0xFF)).toByte()
            output[position + 2] = (input[position + 2].toInt() xor ((key ushr 16) and 0xFF)).toByte()
            output[position + 3] = (input[position + 3].toInt() xor ((key ushr 24) and 0xFF)).toByte()
            key = (key * 7) + 3
            position += 4
        }
        var shift = 0
        while (position < input.size) {
            output[position] = (input[position].toInt() xor ((key ushr shift) and 0xFF)).toByte()
            position += 1
            shift += 8
        }
        return output
    }

    fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun writeI32(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
