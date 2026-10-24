package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Classified RGSS archive failure. The message never contains archive bodies; [detail] is only
 * used for structural facts (for example an entry index or field name).
 */
class RgssArchiveException(val reason: Reason, detail: String = "") :
    IllegalArgumentException(
        "RGSS archive: $reason" + (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason {
        HEADER,
        VERSION,
        TRUNCATED,
        PATH,
        DUPLICATE,
        LIMIT,
        OFFSET,
        NAME,
        ENCODING,
        COMPRESSION,
        STRUCTURE,
    }
}

/** Injected bounds; the defaults are prototype limits, not measured device budgets. */
class RgssArchiveLimits(
    val maxArchiveBytes: Int = DEFAULT_MAX_ARCHIVE_BYTES,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxNameBytes: Int = DEFAULT_MAX_NAME_BYTES,
    val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxEntries > 0)
        require(maxNameBytes in 1..MAX_NAME_CEILING)
        require(maxEntryBytes > 0)
        require(maxTotalBytes > 0)
        require(maxOutputBytes > 0)
    }

    companion object {
        const val DEFAULT_MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 200_000
        const val DEFAULT_MAX_NAME_BYTES = 4_096
        const val DEFAULT_MAX_ENTRY_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024 * 1024
        private const val MAX_NAME_CEILING = 1_048_576
    }
}

/**
 * One file inside an RGSSAD/RGSS3A archive. [data] is always the plain, decompressed payload.
 * For v3, [compressed] records whether the on-disk payload was zlib-compressed; v1 never compresses.
 * [nameBytes] keeps the decoded (not encrypted) archive name bytes so rewrites preserve the original
 * encoding and byte length.
 */
class RgssArchiveEntry(
    path: String,
    data: ByteArray,
    val compressed: Boolean = false,
    nameBytes: ByteArray? = null,
) {
    val path: String = canonicalRgssPath(path)
    val data: ByteArray = data.copyOf()
    // RGSS archives conventionally store Windows separators. A new entry uses backslashes while a
    // rewritten entry keeps the exact decoded name bytes supplied by the reader.
    val nameBytes: ByteArray =
        (nameBytes ?: this.path.replace('/', '\\').toByteArray(Charsets.UTF_8)).copyOf()

    init {
        if (this.data.size > RgssArchiveLimits.DEFAULT_MAX_ENTRY_BYTES) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry bytes")
        }
        if (this.nameBytes.isEmpty() || this.nameBytes.size > RgssArchiveLimits.DEFAULT_MAX_NAME_BYTES) {
            throw RgssArchiveException(RgssArchiveException.Reason.NAME, "entry name")
        }
        if (this.nameBytes.any { it == 0.toByte() }) {
            throw RgssArchiveException(RgssArchiveException.Reason.NAME, "NUL in entry name")
        }
    }

    fun withData(value: ByteArray, compressed: Boolean = this.compressed): RgssArchiveEntry =
        RgssArchiveEntry(path, value, compressed, nameBytes)

    override fun toString(): String = "RgssArchiveEntry(path=$path,size=${data.size},compressed=$compressed)"
}

/**
 * Immutable archive model. The writer always re-encrypts from [entries]; it never reuses encrypted
 * bytes from the caller. Version 1 covers both .rgssad (XP) and .rgss2a (VX); version 3 is VX Ace.
 */
class RgssArchive(val version: Int, entries: List<RgssArchiveEntry>) {
    val entries: List<RgssArchiveEntry> = Collections.unmodifiableList(ArrayList(entries))

    init {
        if (version != 1 && version != 3) throw RgssArchiveException(RgssArchiveException.Reason.VERSION)
        if (this.entries.size > RgssArchiveLimits.DEFAULT_MAX_ENTRIES) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry count")
        }
        val seen = HashSet<String>()
        var total = 0L
        for (entry in this.entries) {
            if (!seen.add(entry.path.lowercase())) {
                throw RgssArchiveException(RgssArchiveException.Reason.DUPLICATE, entry.path)
            }
            if (version == 1 && entry.compressed) {
                throw RgssArchiveException(RgssArchiveException.Reason.STRUCTURE, "v1 compression flag")
            }
            total += entry.data.size.toLong()
            if (total > RgssArchiveLimits.DEFAULT_MAX_TOTAL_BYTES) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            }
        }
    }

    fun entry(path: String): RgssArchiveEntry? = entries.firstOrNull { it.path == path }

    fun replaceEntry(path: String, value: RgssArchiveEntry): RgssArchive =
        RgssArchive(version, entries.map { if (it.path == path) value else it })

    override fun toString(): String = "RgssArchive(version=$version,entries=${entries.size})"
}

/**
 * RGSSAD v1 and RGSSAD v3 reader/writer.
 *
 * v1 uses a rolling 32-bit key (initial 0xDEADCAFE). The name length and size fields are XORed with
 * the current key and then the key advances once; each name byte advances the key after use. File
 * payloads use the key as a little-endian dword XOR stream, advancing once per four bytes.
 *
 * v3 stores a base key in the header; the table key is `base * 9 + 3`. Offset, size, per-file data
 * key and name length are XORed with that table key, and name bytes XOR with the table key's four
 * little-endian bytes. File payloads use the per-file key with the same dword stream as v1. A v3
 * payload beginning with zlib's 0x78 0x9C marker is inflated; the original compressed/uncompressed
 * shape is retained on write.
 */
object RgssArchiveCodec {
    private const val HEADER_SIZE = 8
    // 0xDEADCAFE as a signed 32-bit value; both versions use it as the standard seed.
    private const val V1_INITIAL_KEY = -0x21523502
    private const val V3_BASE_SEED = -0x21523502
    private val SHIFT_JIS: Charset? = try {
        Charset.forName("Shift_JIS")
    } catch (_: Exception) {
        null
    }

    fun read(bytes: ByteArray, limits: RgssArchiveLimits = RgssArchiveLimits()): RgssArchive {
        if (bytes.size > limits.maxArchiveBytes) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "archive bytes")
        }
        if (bytes.size < HEADER_SIZE) {
            throw RgssArchiveException(RgssArchiveException.Reason.HEADER, "short header")
        }
        if (bytes[0].toInt() != 0x52 || bytes[1].toInt() != 0x47 || bytes[2].toInt() != 0x53 ||
            bytes[3].toInt() != 0x53 || bytes[4].toInt() != 0x41 || bytes[5].toInt() != 0x44 ||
            bytes[6].toInt() != 0x00
        ) {
            throw RgssArchiveException(RgssArchiveException.Reason.HEADER)
        }
        return when (val version = bytes[7].toInt() and 0xFF) {
            1 -> readV1(bytes, limits)
            3 -> readV3(bytes, limits)
            else -> throw RgssArchiveException(RgssArchiveException.Reason.VERSION, "v$version")
        }
    }

    fun write(archive: RgssArchive, limits: RgssArchiveLimits = RgssArchiveLimits()): ByteArray {
        validateForWrite(archive, limits)
        return when (archive.version) {
            1 -> writeV1(archive, limits)
            3 -> writeV3(archive, limits)
            else -> throw RgssArchiveException(RgssArchiveException.Reason.VERSION)
        }
    }

    private fun readV1(bytes: ByteArray, limits: RgssArchiveLimits): RgssArchive {
        val cursor = Cursor(bytes, HEADER_SIZE)
        var key = V1_INITIAL_KEY
        val entries = ArrayList<RgssArchiveEntry>()
        val names = HashSet<String>()
        var total = 0L
        while (cursor.remaining > 0) {
            if (cursor.remaining < 4) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v1 name length")
            }
            val nameLength = unsigned(cursor.readI32() xor key)
            key = advanceKey(key)
            if (nameLength == 0L) {
                if (cursor.remaining != 0) {
                    throw RgssArchiveException(RgssArchiveException.Reason.STRUCTURE, "bytes after v1 terminator")
                }
                break
            }
            if (nameLength > limits.maxNameBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v1 name length")
            }
            if (nameLength > cursor.remaining.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v1 name")
            }
            val encryptedName = cursor.readBytes(nameLength.toInt())
            val rawName = ByteArray(encryptedName.size)
            for (index in encryptedName.indices) {
                rawName[index] = (encryptedName[index].toInt() xor (key and 0xFF)).toByte()
                key = advanceKey(key)
            }
            if (cursor.remaining < 4) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v1 size")
            }
            val size = unsigned(cursor.readI32() xor key)
            key = advanceKey(key)
            if (size > limits.maxEntryBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v1 entry bytes")
            }
            if (size > cursor.remaining.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v1 payload")
            }
            if (total > limits.maxTotalBytes.toLong() - size) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            }
            val encrypted = cursor.readBytes(size.toInt())
            val plain = cryptData(encrypted, key)
            val path = decodeName(rawName, limits)
            if (!names.add(path.lowercase())) {
                throw RgssArchiveException(RgssArchiveException.Reason.DUPLICATE, path)
            }
            entries.add(RgssArchiveEntry(path, plain, false, rawName))
            total += size
            if (entries.size > limits.maxEntries) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry count")
            }
        }
        return RgssArchive(1, entries)
    }

    private fun readV3(bytes: ByteArray, limits: RgssArchiveLimits): RgssArchive {
        val cursor = Cursor(bytes, HEADER_SIZE)
        if (cursor.remaining < 4) {
            throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v3 base key")
        }
        val base = (cursor.readI32() * 9) + 3
        val records = ArrayList<V3Record>()
        val names = HashSet<String>()
        while (true) {
            if (cursor.remaining < 16) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v3 entry header")
            }
            val offset = unsigned(cursor.readI32() xor base)
            val size = unsigned(cursor.readI32() xor base)
            val dataKey = cursor.readI32() xor base
            val nameLength = unsigned(cursor.readI32() xor base)
            if (offset == 0L) break
            if (size > limits.maxOutputBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v3 stored entry bytes")
            }
            if (nameLength == 0L) {
                throw RgssArchiveException(RgssArchiveException.Reason.NAME, "empty v3 name")
            }
            if (nameLength > limits.maxNameBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v3 name length")
            }
            if (nameLength > cursor.remaining.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED, "v3 name")
            }
            val encryptedName = cursor.readBytes(nameLength.toInt())
            val rawName = ByteArray(encryptedName.size)
            for (index in encryptedName.indices) {
                val shift = 8 * (index % 4)
                rawName[index] = (encryptedName[index].toInt() xor ((base ushr shift) and 0xFF)).toByte()
            }
            val path = decodeName(rawName, limits)
            if (!names.add(path.lowercase())) {
                throw RgssArchiveException(RgssArchiveException.Reason.DUPLICATE, path)
            }
            records.add(V3Record(path, rawName, offset, size, dataKey))
            if (records.size > limits.maxEntries) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry count")
            }
        }

        val tableEnd = cursor.position.toLong()
        val entries = ArrayList<RgssArchiveEntry>(records.size)
        var total = 0L
        for (record in records) {
            if (record.offset < tableEnd || record.offset > bytes.size.toLong() ||
                record.size > bytes.size.toLong() - record.offset
            ) {
                throw RgssArchiveException(RgssArchiveException.Reason.OFFSET, record.path)
            }
            if (total > limits.maxTotalBytes.toLong() - record.size) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            }
            val start = record.offset.toInt()
            val end = (record.offset + record.size).toInt()
            val plainOnDisk = cryptData(bytes.copyOfRange(start, end), record.dataKey)
            val decoded = decodePayload(plainOnDisk, limits, limits.maxTotalBytes.toLong() - total)
            total += decoded.first.size.toLong()
            if (total > limits.maxTotalBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            }
            entries.add(RgssArchiveEntry(record.path, decoded.first, decoded.second, record.rawName))
        }
        return RgssArchive(3, entries)
    }

    private fun writeV1(archive: RgssArchive, limits: RgssArchiveLimits): ByteArray {
        val output = ByteArrayOutputStream(minOf(archive.entries.sumOf { it.data.size }, 1 shl 20))
        writeHeader(output, 1)
        var key = V1_INITIAL_KEY
        for (entry in archive.entries) {
            val name = entry.nameBytes
            writeI32(output, name.size xor key)
            key = advanceKey(key)
            for (byte in name) {
                output.write((byte.toInt() xor (key and 0xFF)) and 0xFF)
                key = advanceKey(key)
            }
            writeI32(output, entry.data.size xor key)
            key = advanceKey(key)
            val encrypted = cryptData(entry.data, key)
            output.write(encrypted, 0, encrypted.size)
            if (output.size() > limits.maxOutputBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "output bytes")
            }
        }
        return output.toByteArray()
    }

    private fun writeV3(archive: RgssArchive, limits: RgssArchiveLimits): ByteArray {
        val payloads = ArrayList<ByteArray>(archive.entries.size)
        var tableSize = HEADER_SIZE + 4L + 16L
        for (entry in archive.entries) {
            val payload = if (entry.compressed) deflate(entry.data, limits) else entry.data
            if (payload.size > limits.maxOutputBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "compressed entry bytes")
            }
            payloads.add(payload)
            tableSize += 16L + entry.nameBytes.size.toLong()
        }
        if (tableSize > limits.maxOutputBytes.toLong() || tableSize > Int.MAX_VALUE.toLong()) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v3 table bytes")
        }
        val output = ByteArrayOutputStream(
            minOf(tableSize + payloads.sumOf { it.size.toLong() }, 1L shl 20).toInt(),
        )
        writeHeader(output, 3)
        writeI32(output, V3_BASE_SEED)
        val base = (V3_BASE_SEED * 9) + 3
        var dataOffset = tableSize
        for (index in archive.entries.indices) {
            val entry = archive.entries[index]
            val payload = payloads[index]
            if (dataOffset > 0xFFFFFFFFL || payload.size.toLong() > 0xFFFFFFFFL) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "v3 offset")
            }
            writeI32(output, dataOffset.toInt() xor base)
            writeI32(output, payload.size xor base)
            // The per-file data key is `base`; storing `base xor base` keeps it encrypted in the table.
            writeI32(output, 0)
            writeI32(output, entry.nameBytes.size xor base)
            for (byteIndex in entry.nameBytes.indices) {
                val shift = 8 * (byteIndex % 4)
                output.write((entry.nameBytes[byteIndex].toInt() xor ((base ushr shift) and 0xFF)) and 0xFF)
            }
            dataOffset += payload.size.toLong()
            if (output.size() > limits.maxOutputBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "output bytes")
            }
        }
        // The reader stops at a zero offset; all four terminator fields are written as encrypted zero.
        writeI32(output, base)
        writeI32(output, base)
        writeI32(output, base)
        writeI32(output, base)
        if (output.size().toLong() != tableSize) {
            throw RgssArchiveException(RgssArchiveException.Reason.STRUCTURE, "v3 table size")
        }
        for (payload in payloads) {
            val encrypted = cryptData(payload, base)
            output.write(encrypted, 0, encrypted.size)
            if (output.size() > limits.maxOutputBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "output bytes")
            }
        }
        return output.toByteArray()
    }

    private fun validateForWrite(archive: RgssArchive, limits: RgssArchiveLimits) {
        if (archive.entries.size > limits.maxEntries) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry count")
        }
        var total = 0L
        for (entry in archive.entries) {
            if (entry.nameBytes.size > limits.maxNameBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "name bytes")
            }
            if (entry.data.size > limits.maxEntryBytes) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry bytes")
            }
            if (archive.version == 1 && entry.compressed) {
                throw RgssArchiveException(RgssArchiveException.Reason.STRUCTURE, "v1 compression flag")
            }
            total += entry.data.size.toLong()
            if (total > limits.maxTotalBytes.toLong()) {
                throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            }
        }
    }

    private fun decodePayload(input: ByteArray, limits: RgssArchiveLimits, remaining: Long): Pair<ByteArray, Boolean> {
        if (input.size >= 2 && (input[0].toInt() and 0xFF) == 0x78 && (input[1].toInt() and 0xFF) == 0x9C) {
            if (remaining <= 0L) throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "total bytes")
            val cap = minOf(limits.maxEntryBytes.toLong(), remaining).toInt()
            return inflate(input, cap) to true
        }
        if (input.size > limits.maxEntryBytes) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "entry bytes")
        }
        return input to false
    }

    private fun inflate(input: ByteArray, maxOutput: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val output = ByteArrayOutputStream(minOf(maxOutput, 8_192).coerceAtLeast(32))
            val buffer = ByteArray(8_192)
            var total = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    // A finished stream can legitimately yield zero output (for example an empty
                    // deflated payload); only an unfinished stream without input is an error.
                    if (inflater.finished()) break
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw RgssArchiveException(RgssArchiveException.Reason.COMPRESSION)
                    }
                    throw RgssArchiveException(RgssArchiveException.Reason.COMPRESSION, "no inflate progress")
                }
                total += count
                if (total > maxOutput) {
                    throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "inflated bytes")
                }
                output.write(buffer, 0, count)
            }
            if (inflater.remaining != 0) {
                throw RgssArchiveException(RgssArchiveException.Reason.STRUCTURE, "trailing zlib bytes")
            }
            return output.toByteArray()
        } catch (failure: RgssArchiveException) {
            throw failure
        } catch (_: DataFormatException) {
            throw RgssArchiveException(RgssArchiveException.Reason.COMPRESSION)
        } finally {
            inflater.end()
        }
    }

    private fun deflate(input: ByteArray, limits: RgssArchiveLimits): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(minOf(input.size / 2, 1 shl 20).coerceAtLeast(64))
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count == 0) {
                    throw RgssArchiveException(RgssArchiveException.Reason.COMPRESSION, "no deflate progress")
                }
                output.write(buffer, 0, count)
                if (output.size() > limits.maxOutputBytes) {
                    throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "compressed bytes")
                }
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun advanceKey(key: Int): Int = (key * 7) + 3

    /** Symmetric RGSS dword stream: four bytes XOR the current key, then advance once. */
    private fun cryptData(input: ByteArray, startKey: Int): ByteArray {
        val output = ByteArray(input.size)
        var key = startKey
        var position = 0
        while (input.size - position >= 4) {
            output[position] = (input[position].toInt() xor (key and 0xFF)).toByte()
            output[position + 1] = (input[position + 1].toInt() xor ((key ushr 8) and 0xFF)).toByte()
            output[position + 2] = (input[position + 2].toInt() xor ((key ushr 16) and 0xFF)).toByte()
            output[position + 3] = (input[position + 3].toInt() xor ((key ushr 24) and 0xFF)).toByte()
            key = advanceKey(key)
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

    private fun decodeName(raw: ByteArray, limits: RgssArchiveLimits): String {
        if (raw.isEmpty()) throw RgssArchiveException(RgssArchiveException.Reason.NAME, "empty name")
        if (raw.any { it == 0.toByte() }) {
            throw RgssArchiveException(RgssArchiveException.Reason.NAME, "NUL in name")
        }
        val text = decodeUtf8(raw) ?: decodeShiftJis(raw)
        ?: throw RgssArchiveException(RgssArchiveException.Reason.ENCODING, "archive name")
        if (text.toByteArray(Charsets.UTF_8).size > limits.maxNameBytes) {
            throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "decoded name")
        }
        return canonicalRgssPath(text)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeShiftJis(bytes: ByteArray): String? {
        val charset = SHIFT_JIS ?: return null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun writeHeader(output: ByteArrayOutputStream, version: Int) {
        output.write(0x52)
        output.write(0x47)
        output.write(0x53)
        output.write(0x53)
        output.write(0x41)
        output.write(0x44)
        output.write(0x00)
        output.write(version and 0xFF)
    }

    private fun writeI32(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun unsigned(value: Int): Long = value.toLong() and 0xFFFFFFFFL

    private class V3Record(
        val path: String,
        val rawName: ByteArray,
        val offset: Long,
        val size: Long,
        val dataKey: Int,
    )

    private class Cursor(private val bytes: ByteArray, var position: Int) {
        val remaining: Int get() = bytes.size - position

        fun readI32(): Int {
            if (remaining < 4) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED)
            }
            val value = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        fun readBytes(count: Int): ByteArray {
            if (count < 0 || count > remaining) {
                throw RgssArchiveException(RgssArchiveException.Reason.TRUNCATED)
            }
            val value = bytes.copyOfRange(position, position + count)
            position += count
            return value
        }
    }
}

/** Canonical portable archive path; rejects traversal, absolute/drive paths and controls. */
internal fun canonicalRgssPath(value: String): String {
    if (value.isEmpty()) throw RgssArchiveException(RgssArchiveException.Reason.PATH, "empty")
    val normalized = value.replace('\\', '/')
    if (normalized.length > RgssArchiveLimits.DEFAULT_MAX_NAME_BYTES) {
        throw RgssArchiveException(RgssArchiveException.Reason.LIMIT, "path")
    }
    if (normalized.startsWith('/') || normalized.endsWith('/')) {
        throw RgssArchiveException(RgssArchiveException.Reason.PATH, "absolute or directory")
    }
    if (normalized.any { it == '\u0000' || it.isISOControl() }) {
        throw RgssArchiveException(RgssArchiveException.Reason.PATH, "control")
    }
    if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) {
        throw RgssArchiveException(RgssArchiveException.Reason.PATH, "drive")
    }
    for (segment in normalized.split('/')) {
        if (segment.isEmpty() || segment == "." || segment == "..") {
            throw RgssArchiveException(RgssArchiveException.Reason.PATH, "segment")
        }
        if (':' in segment) {
            throw RgssArchiveException(RgssArchiveException.Reason.PATH, "colon")
        }
    }
    return normalized
}
