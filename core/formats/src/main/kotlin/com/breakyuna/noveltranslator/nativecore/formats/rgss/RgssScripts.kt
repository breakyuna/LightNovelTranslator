package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Classified Scripts.rxdata failure. */
class RgssScriptsException(val reason: Reason, detail: String = "") :
    IllegalArgumentException(
        "RGSS scripts: $reason" + (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason {
        STRUCTURE,
        LIMIT,
        COMPRESSION,
        ENCODING,
        ID,
        NAME,
        MARSHAL,
    }
}

/** Injected limits; the defaults are prototype bounds. */
class RgssScriptsLimits(
    val maxScripts: Int = 10_000,
    val maxCodeBytes: Int = 8 * 1024 * 1024,
    val maxTotalCodeBytes: Int = 64 * 1024 * 1024,
    val maxNameBytes: Int = 4_096,
    val maxMarshalBytes: Int = 64 * 1024 * 1024,
) {
    init {
        require(maxScripts > 0)
        require(maxCodeBytes > 0)
        require(maxTotalCodeBytes > 0)
        require(maxNameBytes > 0)
        require(maxMarshalBytes > 0)
    }
}

/**
 * Strict code-text decoding. Scripts.rxdata normally stores UTF-8 Ruby source, but older projects
 * may use Shift_JIS; both are tried strictly (no replacement characters). The original charset and
 * UTF-8 BOM are retained so a rewritten script is encoded back the same way.
 */
internal class RgssTextCodec private constructor(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
) {
    companion object {
        private val SHIFT_JIS: Charset? = try {
            Charset.forName("Shift_JIS")
        } catch (_: Exception) {
            null
        }

        fun decode(bytes: ByteArray, maxBytes: Int): RgssTextCodec {
            if (bytes.size > maxBytes) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "code bytes")
            }
            val first = if (bytes.isEmpty()) 0 else bytes[0].toInt() and 0xFF
            val second = if (bytes.size < 2) 0 else bytes[1].toInt() and 0xFF
            val third = if (bytes.size < 3) 0 else bytes[2].toInt() and 0xFF
            val (charset, bomLength) = when {
                bytes.size >= 3 && first == 0xEF && second == 0xBB && third == 0xBF ->
                    Charsets.UTF_8 to 3
                bytes.size >= 2 && (first == 0xFF || first == 0xFE) ->
                    throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "UTF-16 scripts")
                else -> Charsets.UTF_8 to 0
            }
            val bom = if (bomLength == 0) ByteArray(0) else bytes.copyOfRange(0, bomLength)
            var decodedCharset = charset
            var text = decodeStrict(charset, bytes, bomLength)
            if (text == null && bomLength == 0) {
                val shiftJis = SHIFT_JIS
                if (shiftJis != null) {
                    text = decodeStrict(shiftJis, bytes, 0)
                    if (text != null) decodedCharset = shiftJis
                }
            }
            if (text == null) {
                throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "script text")
            }
            if ('\u0000' in text) {
                throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "NUL")
            }
            return RgssTextCodec(text, decodedCharset, bom)
        }

        fun encodeValue(value: String, charset: Charset, bom: ByteArray, maxBytes: Int): ByteArray {
            if ('\u0000' in value) {
                throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "NUL")
            }
            val body = try {
                val buffer = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (_: CharacterCodingException) {
                throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "encode")
            }
            if (body.size.toLong() + bom.size.toLong() > maxBytes.toLong()) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "encoded code bytes")
            }
            return bom + body
        }

        private fun decodeStrict(charset: Charset, bytes: ByteArray, offset: Int): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

class RgssScript private constructor(
    val id: Int,
    nameBytes: ByteArray,
    val name: String,
    val code: String,
    internal val charset: Charset,
    internal val bom: ByteArray,
    private val originalCodeBytes: ByteArray?,
    private val codeModified: Boolean,
) {
    val nameBytes: ByteArray = nameBytes.copyOf()

    init {
        if (id < 0) throw RgssScriptsException(RgssScriptsException.Reason.ID, "negative")
        if (this.nameBytes.size > 4_096) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "name bytes")
        }
        if (name.any { it == '\u0000' || it.isISOControl() }) {
            throw RgssScriptsException(RgssScriptsException.Reason.NAME, "control")
        }
        if (code.length > 8 * 1024 * 1024) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "code chars")
        }
        if ('\u0000' in code) {
            throw RgssScriptsException(RgssScriptsException.Reason.ENCODING, "NUL")
        }
    }

    fun withCode(value: String): RgssScript =
        if (value == code) {
            this
        } else {
            RgssScript(id, nameBytes, name, value, charset, bom, null, true)
        }

    internal fun encodeCode(maxBytes: Int): ByteArray {
        val original = originalCodeBytes
        if (!codeModified && original != null) {
            if (original.size > maxBytes) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "code bytes")
            }
            return original.copyOf()
        }
        return RgssTextCodec.encodeValue(code, charset, bom, maxBytes)
    }

    override fun toString(): String = "RgssScript(id=$id,name=$name,codeChars=${code.length})"

    companion object {
        fun utf8(id: Int, name: String, code: String): RgssScript =
            RgssScript(
                id,
                name.toByteArray(Charsets.UTF_8),
                name,
                code,
                Charsets.UTF_8,
                ByteArray(0),
                null,
                true,
            )

        internal fun create(
            id: Int,
            nameBytes: ByteArray,
            name: String,
            code: String,
            charset: Charset,
            bom: ByteArray,
            originalCodeBytes: ByteArray?,
        ): RgssScript = RgssScript(
            id,
            nameBytes,
            name,
            code,
            charset,
            bom,
            originalCodeBytes?.copyOf(),
            false,
        )
    }
}

class RgssScriptsArchive(scripts: List<RgssScript>) {
    val scripts: List<RgssScript> = Collections.unmodifiableList(ArrayList(scripts))

    init {
        if (this.scripts.size > 10_000) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "script count")
        }
        val ids = HashSet<Int>()
        var total = 0L
        for (script in this.scripts) {
            if (!ids.add(script.id)) {
                throw RgssScriptsException(RgssScriptsException.Reason.ID, "duplicate ${script.id}")
            }
            total += script.code.length.toLong()
            if (total > 64L * 1024L * 1024L) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "total code")
            }
        }
    }

    fun script(id: Int): RgssScript? = scripts.firstOrNull { it.id == id }

    override fun toString(): String = "RgssScriptsArchive(scripts=${scripts.size})"
}

/**
 * Reads and writes the RGSS `Scripts.rxdata` / `Scripts.rvdata` / `Scripts.rvdata2` shape:
 * a Marshal Array of `[id, name, zlibDeflatedCode]` Arrays. Each code string is inflated with a
 * strict bound, then decoded as UTF-8 or Shift_JIS. Writing always emits zlib-deflated code, which
 * is what the RGSS script loader expects.
 */
object RgssScriptsCodec {
    fun read(bytes: ByteArray, limits: RgssScriptsLimits = RgssScriptsLimits()): RgssScriptsArchive {
        if (bytes.size > limits.maxMarshalBytes) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "marshal bytes")
        }
        val marshalLimits = MarshalLimits(
            maxBytes = limits.maxMarshalBytes,
            maxDepth = 64,
            maxNodes = 1_000_000,
            maxCollection = maxOf(limits.maxScripts, 100_000),
            maxStringBytes = maxOf(limits.maxCodeBytes + 65_536, limits.maxNameBytes),
            maxSymbols = 10_000,
            maxObjects = 1_000_000,
        )
        val root = try {
            RubyMarshal.parse(bytes, marshalLimits)
        } catch (failure: MarshalException) {
            throw RgssScriptsException(RgssScriptsException.Reason.MARSHAL, failure.reason.name)
        }
        if (root !is MarshalArray) {
            throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "root")
        }
        if (root.elements.size > limits.maxScripts) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "script count")
        }
        val scripts = ArrayList<RgssScript>(root.elements.size)
        val ids = HashSet<Int>()
        var total = 0L
        for (element in root.elements) {
            if (element !is MarshalArray || element.elements.size != 3) {
                throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "script entry")
            }
            val idNode = element.elements[0] as? MarshalInt
                ?: throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "id")
            val nameNode = element.elements[1] as? MarshalString
                ?: throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "name")
            val codeNode = element.elements[2] as? MarshalString
                ?: throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "code")
            val id = idNode.value
            if (!ids.add(id)) {
                throw RgssScriptsException(RgssScriptsException.Reason.ID, "duplicate $id")
            }
            if (nameNode.bytes.size > limits.maxNameBytes) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "name bytes")
            }
            val nameCodec = RgssTextCodec.decode(nameNode.bytes, limits.maxNameBytes)
            val codeBytes = codeNode.bytes
            val plain = if (isZlib(codeBytes)) {
                inflate(codeBytes, limits.maxCodeBytes)
            } else {
                if (codeBytes.size > limits.maxCodeBytes) {
                    throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "code bytes")
                }
                codeBytes
            }
            total += plain.size.toLong()
            if (total > limits.maxTotalCodeBytes.toLong()) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "total code")
            }
            val codeCodec = RgssTextCodec.decode(plain, limits.maxCodeBytes)
            scripts.add(
                RgssScript.create(
                    id,
                    nameNode.bytes,
                    nameCodec.text,
                    codeCodec.text,
                    codeCodec.charset,
                    codeCodec.bom,
                    plain,
                ),
            )
        }
        return RgssScriptsArchive(scripts)
    }

    fun write(archive: RgssScriptsArchive, limits: RgssScriptsLimits = RgssScriptsLimits()): ByteArray {
        if (archive.scripts.size > limits.maxScripts) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "script count")
        }
        val root = MarshalArray()
        var total = 0L
        for (script in archive.scripts) {
            if (script.nameBytes.size > limits.maxNameBytes) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "name bytes")
            }
            val plain = script.encodeCode(limits.maxCodeBytes)
            total += plain.size.toLong()
            if (total > limits.maxTotalCodeBytes.toLong()) {
                throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "total code")
            }
            val compressed = deflate(plain, limits.maxCodeBytes + 65_536)
            root.elements.add(
                MarshalArray(
                    listOf(
                        MarshalInt(script.id),
                        MarshalString.ofBytes(script.nameBytes),
                        MarshalString.ofBytes(compressed),
                    ),
                ),
            )
        }
        val marshalLimits = MarshalLimits(
            maxBytes = limits.maxMarshalBytes,
            maxDepth = 64,
            maxNodes = 1_000_000,
            maxCollection = maxOf(limits.maxScripts, 100_000),
            maxStringBytes = maxOf(limits.maxCodeBytes + 65_536, limits.maxNameBytes),
            maxSymbols = 10_000,
            maxObjects = 1_000_000,
        )
        val bytes = try {
            RubyMarshal.serialize(root, marshalLimits)
        } catch (failure: MarshalException) {
            throw RgssScriptsException(RgssScriptsException.Reason.MARSHAL, failure.reason.name)
        }
        if (bytes.size > limits.maxMarshalBytes) {
            throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "marshal bytes")
        }
        return bytes
    }

    private fun isZlib(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val cmf = bytes[0].toInt() and 0xFF
        val flg = bytes[1].toInt() and 0xFF
        return (cmf and 0x0F) == 8 && (cmf ushr 4) <= 7 && ((cmf shl 8) or flg) % 31 == 0
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
                    // deflated script); only an unfinished stream without input is an error.
                    if (inflater.finished()) break
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw RgssScriptsException(RgssScriptsException.Reason.COMPRESSION)
                    }
                    throw RgssScriptsException(RgssScriptsException.Reason.COMPRESSION, "no progress")
                }
                total += count
                if (total > maxOutput) {
                    throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "inflated code")
                }
                output.write(buffer, 0, count)
            }
            if (inflater.remaining != 0) {
                throw RgssScriptsException(RgssScriptsException.Reason.STRUCTURE, "trailing zlib bytes")
            }
            return output.toByteArray()
        } catch (failure: RgssScriptsException) {
            throw failure
        } catch (_: DataFormatException) {
            throw RgssScriptsException(RgssScriptsException.Reason.COMPRESSION)
        } finally {
            inflater.end()
        }
    }

    private fun deflate(input: ByteArray, maxOutput: Int): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(minOf(input.size / 2, 1 shl 20).coerceAtLeast(64))
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count == 0) {
                    throw RgssScriptsException(RgssScriptsException.Reason.COMPRESSION, "no progress")
                }
                output.write(buffer, 0, count)
                if (output.size() > maxOutput) {
                    throw RgssScriptsException(RgssScriptsException.Reason.LIMIT, "compressed code")
                }
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
