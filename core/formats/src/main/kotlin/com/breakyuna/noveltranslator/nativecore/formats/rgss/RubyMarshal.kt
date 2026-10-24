package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap

/** Classified Ruby Marshal 4.8 failure. Bodies and symbol contents are never put in the message. */
class MarshalException(val reason: Reason, detail: String = "") :
    IllegalArgumentException(
        "Ruby Marshal: $reason" + (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason {
        HEADER,
        TRAILING,
        TYPE,
        UNSUPPORTED,
        LENGTH,
        DEPTH,
        LIMIT,
        LINK,
        SYMBOL,
        ENCODING,
        VALUE,
    }
}

/** Injected limits; the defaults are prototype budgets, not measured Android memory budgets. */
class MarshalLimits(
    val maxBytes: Int = 64 * 1024 * 1024,
    val maxDepth: Int = 128,
    val maxNodes: Int = 1_000_000,
    val maxCollection: Int = 1_000_000,
    val maxStringBytes: Int = 16 * 1024 * 1024,
    val maxSymbols: Int = 200_000,
    val maxObjects: Int = 1_000_000,
) {
    init {
        require(maxBytes > 0)
        require(maxDepth in 1..4_096)
        require(maxNodes > 0)
        require(maxCollection > 0)
        require(maxStringBytes > 0)
        require(maxSymbols > 0)
        require(maxObjects > 0)
    }
}

class MarshalIvar(val name: String, val value: MarshalValue) {
    init {
        require(name.isNotEmpty() && name.length <= 1_024)
        require(name.none { it == '\u0000' || it.isISOControl() })
    }
    override fun toString(): String = "MarshalIvar(name=$name)"
}

/**
 * Base of the bounded Marshal tree. [ivars] is used for the `I` wrapper, and for the ivar list of an
 * `o` object. Object identity is significant: the parser returns the same node for a `@` link and
 * the writer emits a link when it sees the same node twice.
 */
sealed class MarshalValue {
    val ivars: MutableList<MarshalIvar> = ArrayList()

    fun addIvar(name: String, value: MarshalValue): MarshalValue {
        ivars.add(MarshalIvar(name, value))
        return this
    }

    /** Decodes the Ruby encoding ivars emitted by Marshal (`:E` or `:encoding`). */
    fun encodingName(): String? {
        val short = ivars.firstOrNull { it.name == "E" }?.value
        if (short is MarshalBool) return if (short.value) "UTF-8" else "US-ASCII"
        val long = ivars.firstOrNull { it.name == "encoding" }?.value
        if (long is MarshalString) return long.utf8
        return null
    }

    override fun toString(): String = javaClass.simpleName
}

class MarshalNil : MarshalValue()

class MarshalBool(val value: Boolean) : MarshalValue()

/** A Marshal Fixnum. Marshal only emits the compact 32-bit form; larger integers use Bignum. */
class MarshalInt(val value: Int) : MarshalValue()

class MarshalBigInt(val value: BigInteger) : MarshalValue()

class MarshalFloat private constructor(val text: String, val value: Double) : MarshalValue() {
    override fun toString(): String = "MarshalFloat($text)"

    companion object {
        fun ofText(text: String): MarshalFloat {
            if (text.isEmpty() || text.length > 64) throw MarshalException(MarshalException.Reason.VALUE)
            val value = when (text) {
                "inf" -> Double.POSITIVE_INFINITY
                "-inf" -> Double.NEGATIVE_INFINITY
                "nan" -> Double.NaN
                else -> {
                    if (text.any { it !in "0123456789+-.eE" }) {
                        throw MarshalException(MarshalException.Reason.VALUE, "float text")
                    }
                    text.toDoubleOrNull() ?: throw MarshalException(MarshalException.Reason.VALUE, "float text")
                }
            }
            return MarshalFloat(text, value)
        }

        fun of(value: Double): MarshalFloat = ofText(
            when {
                value.isNaN() -> "nan"
                value == Double.POSITIVE_INFINITY -> "inf"
                value == Double.NEGATIVE_INFINITY -> "-inf"
                else -> value.toString()
            },
        )
    }
}

/** Raw Ruby String bytes. Marshal strings are byte strings first; [utf8] is a convenience view. */
class MarshalString private constructor(bytes: ByteArray) : MarshalValue() {
    val bytes: ByteArray = bytes.copyOf()
    val utf8: String? get() = decodeUtf8OrNull(this.bytes)

    init {
        if (this.bytes.size > DEFAULT_MAX_STRING_BYTES) {
            throw MarshalException(MarshalException.Reason.LIMIT, "string bytes")
        }
    }

    override fun toString(): String = "MarshalString(size=${bytes.size})"

    companion object {
        private const val DEFAULT_MAX_STRING_BYTES = 64 * 1024 * 1024

        fun ofBytes(bytes: ByteArray): MarshalString = MarshalString(bytes)

        fun ofUtf8(value: String): MarshalString = MarshalString(value.toByteArray(Charsets.UTF_8))
    }
}

/** Ruby Symbol with its raw bytes retained for a lossless write. */
class MarshalSymbol private constructor(val bytes: ByteArray, val name: String) : MarshalValue() {
    init {
        if (this.bytes.size > 16 * 1024 * 1024) {
            throw MarshalException(MarshalException.Reason.LIMIT, "symbol bytes")
        }
        if ('\u0000' in name) throw MarshalException(MarshalException.Reason.SYMBOL, "NUL")
    }

    override fun toString(): String = "MarshalSymbol(name=$name)"

    companion object {
        fun of(name: String): MarshalSymbol = MarshalSymbol(name.toByteArray(Charsets.UTF_8), name)

        internal fun fromBytes(bytes: ByteArray): MarshalSymbol {
            val name = decodeUtf8OrNull(bytes) ?: decodeShiftJisOrNull(bytes)
            ?: throw MarshalException(MarshalException.Reason.ENCODING, "symbol")
            return MarshalSymbol(bytes, name)
        }
    }
}

class MarshalArray(elements: List<MarshalValue> = emptyList()) : MarshalValue() {
    val elements: MutableList<MarshalValue> = ArrayList(elements)
}

class MarshalPair(val key: MarshalValue, val value: MarshalValue)

/**
 * Ruby Hash. A non-null [defaultValue] selects the `}` (hash-with-default) type; a Ruby nil default
 * is represented by [MarshalNil], not by null.
 */
class MarshalHash(
    entries: List<MarshalPair> = emptyList(),
    initialDefault: MarshalValue? = null,
) : MarshalValue() {
    val entries: MutableList<MarshalPair> = ArrayList(entries)
    var defaultValue: MarshalValue? = initialDefault
}

/** A plain `o` object: class path plus its instance variables in [ivars]. */
class MarshalObject(val className: String) : MarshalValue() {
    init {
        if (className.isEmpty() || className.length > 4_096) {
            throw MarshalException(MarshalException.Reason.VALUE, "class name")
        }
    }
}

/**
 * Marshal 4.8 parser/writer.
 *
 * Supported: nil/true/false, Fixnum, Bignum, Float, String, Symbol, Array, Hash (including a
 * default), plain `o` objects with ivars, the `I` ivar wrapper, symbol links (`;`) and object links
 * (`@`). User-defined `u`, user-marshal `U`, extended/subclass/struct/class/module/data/regexp
 * markers are rejected explicitly because their payload rules are not implemented.
 */
object RubyMarshal {
    fun parse(bytes: ByteArray, limits: MarshalLimits = MarshalLimits()): MarshalValue {
        if (bytes.size > limits.maxBytes) throw MarshalException(MarshalException.Reason.LIMIT, "input bytes")
        if (bytes.size < 2 || bytes[0].toInt() != 4 || bytes[1].toInt() != 8) {
            throw MarshalException(MarshalException.Reason.HEADER)
        }
        return Reader(bytes, limits).read()
    }

    fun serialize(value: MarshalValue, limits: MarshalLimits = MarshalLimits()): ByteArray =
        Writer(limits).write(value)

    private class Reader(private val bytes: ByteArray, private val limits: MarshalLimits) {
        private var position = 0
        private var nodes = 0
        private val objects = ArrayList<MarshalValue>()
        private val symbols = ArrayList<MarshalSymbol>()
        private val symbolIndexes = HashMap<String, Int>()

        fun read(): MarshalValue {
            position = 2
            val value = readValue(0)
            if (position != bytes.size) {
                throw MarshalException(MarshalException.Reason.TRAILING)
            }
            return value
        }

        private fun readValue(depth: Int): MarshalValue {
            if (depth > limits.maxDepth) throw MarshalException(MarshalException.Reason.DEPTH)
            countNode()
            return when (val type = readU8()) {
                0x30 -> MarshalNil()
                0x54 -> MarshalBool(true)
                0x46 -> MarshalBool(false)
                0x69 -> MarshalInt(readLong())
                0x6C -> readBigInt()
                0x3A -> readSymbolBody()
                0x3B -> readSymbolLink()
                0x22 -> readString()
                0x5B -> readArray(depth)
                0x7B -> readHash(depth, false)
                0x7D -> readHash(depth, true)
                0x66 -> readFloat()
                0x6F -> readObject(depth)
                0x49 -> readIvarWrapper(depth)
                0x40 -> readObjectLink()
                else -> throw MarshalException(
                    if (type in UNSUPPORTED_MARSHAL_TYPES) MarshalException.Reason.UNSUPPORTED
                    else MarshalException.Reason.TYPE,
                    "0x" + type.toString(16).padStart(2, '0'),
                )
            }
        }

        private fun readString(): MarshalValue {
            val length = readLength(limits.maxStringBytes)
            val value = MarshalString.ofBytes(readBytesExact(length))
            registerObject(value)
            return value
        }

        private fun readArray(depth: Int): MarshalValue {
            val count = readLength(limits.maxCollection)
            if (count > remaining()) throw MarshalException(MarshalException.Reason.LENGTH, "array")
            val value = MarshalArray()
            registerObject(value)
            for (index in 0 until count) {
                value.elements.add(readValue(depth + 1))
            }
            return value
        }

        private fun readHash(depth: Int, withDefault: Boolean): MarshalValue {
            val count = readLength(limits.maxCollection)
            if (count.toLong() * 2L > remaining().toLong()) {
                throw MarshalException(MarshalException.Reason.LENGTH, "hash")
            }
            val value = MarshalHash()
            registerObject(value)
            for (index in 0 until count) {
                val key = readValue(depth + 1)
                val item = readValue(depth + 1)
                value.entries.add(MarshalPair(key, item))
            }
            if (withDefault) value.defaultValue = readValue(depth + 1)
            return value
        }

        private fun readObject(depth: Int): MarshalValue {
            val className = readSymbolNode(depth + 1).name
            val count = readLength(limits.maxCollection)
            if (count.toLong() * 2L > remaining().toLong()) {
                throw MarshalException(MarshalException.Reason.LENGTH, "object")
            }
            val value = MarshalObject(className)
            registerObject(value)
            for (index in 0 until count) {
                val name = readSymbolNode(depth + 1).name
                if (name.isEmpty()) throw MarshalException(MarshalException.Reason.SYMBOL, "empty ivar")
                value.ivars.add(MarshalIvar(name, readValue(depth + 1)))
            }
            return value
        }

        private fun readFloat(): MarshalValue {
            val length = readLength(limits.maxStringBytes)
            val bytes = readBytesExact(length)
            if (bytes.any { (it.toInt() and 0xFF) >= 0x80 }) {
                throw MarshalException(MarshalException.Reason.ENCODING, "float")
            }
            val value = MarshalFloat.ofText(String(bytes, Charsets.US_ASCII))
            registerObject(value)
            return value
        }

        private fun readBigInt(): MarshalValue {
            val sign = readU8()
            if (sign != 0x2B && sign != 0x2D) {
                throw MarshalException(MarshalException.Reason.VALUE, "bignum sign")
            }
            val words = readLength(limits.maxCollection)
            if (words.toLong() * 2L > remaining().toLong()) {
                throw MarshalException(MarshalException.Reason.LENGTH, "bignum")
            }
            val little = readBytesExact(words * 2)
            val magnitude = if (little.isEmpty()) {
                BigInteger.ZERO
            } else {
                BigInteger(1, little.reversedArray())
            }
            val value = MarshalBigInt(if (sign == 0x2D && magnitude.signum() != 0) magnitude.negate() else magnitude)
            registerObject(value)
            return value
        }

        private fun readIvarWrapper(depth: Int): MarshalValue {
            if (peekU8() == 0x3B) {
                throw MarshalException(MarshalException.Reason.SYMBOL, "symbol link with encoding")
            }
            val value = readValue(depth + 1)
            readIvarsInto(value, depth + 1)
            return value
        }

        private fun readIvarsInto(value: MarshalValue, depth: Int) {
            val count = readLength(limits.maxCollection)
            if (count.toLong() * 2L > remaining().toLong()) {
                throw MarshalException(MarshalException.Reason.LENGTH, "ivars")
            }
            for (index in 0 until count) {
                val name = readSymbolNode(depth + 1).name
                if (name.isEmpty()) throw MarshalException(MarshalException.Reason.SYMBOL, "empty ivar")
                value.ivars.add(MarshalIvar(name, readValue(depth + 1)))
            }
        }

        private fun readSymbolNode(depth: Int): MarshalSymbol {
            if (depth > limits.maxDepth) throw MarshalException(MarshalException.Reason.DEPTH)
            countNode()
            return when (val type = readU8()) {
                0x3A -> readSymbolBody()
                0x3B -> readSymbolLink()
                0x49 -> {
                    if (peekU8() == 0x3B) {
                        throw MarshalException(MarshalException.Reason.SYMBOL, "symbol link with encoding")
                    }
                    val value = readSymbolNode(depth + 1)
                    readIvarsInto(value, depth + 1)
                    value
                }
                else -> throw MarshalException(MarshalException.Reason.SYMBOL, "0x" + type.toString(16))
            }
        }

        private fun readSymbolBody(): MarshalSymbol {
            val length = readLength(limits.maxStringBytes)
            val bytes = readBytesExact(length)
            val key = String(bytes, Charsets.ISO_8859_1)
            val existing = symbolIndexes[key]
            if (existing != null) return symbols[existing]
            if (symbols.size >= limits.maxSymbols) {
                throw MarshalException(MarshalException.Reason.LIMIT, "symbols")
            }
            val symbol = MarshalSymbol.fromBytes(bytes)
            symbolIndexes[key] = symbols.size
            symbols.add(symbol)
            return symbol
        }

        private fun readSymbolLink(): MarshalSymbol {
            val index = readLength(limits.maxSymbols)
            if (index !in symbols.indices) {
                throw MarshalException(MarshalException.Reason.LINK, "symbol $index")
            }
            return symbols[index]
        }

        private fun readObjectLink(): MarshalValue {
            val index = readLength(limits.maxObjects)
            if (index !in objects.indices) {
                throw MarshalException(MarshalException.Reason.LINK, "object $index")
            }
            return objects[index]
        }

        private fun registerObject(value: MarshalValue) {
            if (objects.size >= limits.maxObjects) {
                throw MarshalException(MarshalException.Reason.LIMIT, "objects")
            }
            objects.add(value)
        }

        private fun readLong(): Int {
            val code = readU8().toByte().toInt()
            if (code == 0) return 0
            if (code > 4) return code - 5
            if (code < -4) return code + 5
            val negative = code < 0
            val length = if (negative) -code else code
            if (length !in 1..4) throw MarshalException(MarshalException.Reason.LENGTH, "long")
            var value = if (negative) -1 else 0
            for (index in 0 until length) {
                val byte = readU8()
                val mask = 0xFF shl (8 * index)
                value = value and mask.inv()
                value = value or (byte shl (8 * index))
            }
            return value
        }

        private fun readLength(maximum: Int): Int {
            val value = readLong()
            if (value < 0) throw MarshalException(MarshalException.Reason.LENGTH)
            if (value.toLong() > maximum.toLong()) throw MarshalException(MarshalException.Reason.LIMIT)
            return value
        }

        private fun readBytesExact(count: Int): ByteArray {
            if (count < 0 || count > remaining()) {
                throw MarshalException(MarshalException.Reason.LENGTH, "bytes")
            }
            val result = bytes.copyOfRange(position, position + count)
            position += count
            return result
        }

        private fun readU8(): Int {
            if (position >= bytes.size) throw MarshalException(MarshalException.Reason.LENGTH, "EOF")
            return bytes[position++].toInt() and 0xFF
        }

        private fun peekU8(): Int {
            if (position >= bytes.size) throw MarshalException(MarshalException.Reason.LENGTH, "EOF")
            return bytes[position].toInt() and 0xFF
        }

        private fun remaining(): Int = bytes.size - position

        private fun countNode() {
            nodes += 1
            if (nodes > limits.maxNodes) throw MarshalException(MarshalException.Reason.LIMIT, "nodes")
        }
    }

    private class Writer(private val limits: MarshalLimits) {
        private val output = ByteArrayOutputStream()
        private val objects = IdentityHashMap<MarshalValue, Int>()
        private val symbols = HashMap<String, Int>()
        private var nodes = 0

        fun write(value: MarshalValue): ByteArray {
            writeByte(4)
            writeByte(8)
            writeValue(value, 0)
            if (output.size() > limits.maxBytes) {
                throw MarshalException(MarshalException.Reason.LIMIT, "output bytes")
            }
            return output.toByteArray()
        }

        private fun writeValue(value: MarshalValue, depth: Int) {
            if (depth > limits.maxDepth) throw MarshalException(MarshalException.Reason.DEPTH)
            nodes += 1
            if (nodes > limits.maxNodes) throw MarshalException(MarshalException.Reason.LIMIT, "nodes")
            when (value) {
                is MarshalSymbol -> writeSymbol(value, depth)
                is MarshalNil -> writeImmediate(0x30, value, depth)
                is MarshalBool -> writeImmediate(if (value.value) 0x54 else 0x46, value, depth)
                is MarshalInt -> {
                    if (value.ivars.isEmpty()) {
                        writeByte(0x69)
                        writeLong(value.value.toLong())
                    } else {
                        writeByte(0x49)
                        writeByte(0x69)
                        writeLong(value.value.toLong())
                        writeIvars(value, depth + 1)
                    }
                }
                else -> {
                    val existing = objects[value]
                    if (existing != null) {
                        writeByte(0x40)
                        writeLong(existing.toLong())
                        return
                    }
                    if (objects.size >= limits.maxObjects) {
                        throw MarshalException(MarshalException.Reason.LIMIT, "objects")
                    }
                    objects[value] = objects.size
                    val externalIvars = value.ivars.isNotEmpty() && value !is MarshalObject
                    if (externalIvars) writeByte(0x49)
                    writeBody(value, depth)
                    if (externalIvars) writeIvars(value, depth + 1)
                }
            }
        }

        private fun writeImmediate(type: Int, value: MarshalValue, depth: Int) {
            if (value.ivars.isEmpty()) {
                writeByte(type)
            } else {
                writeByte(0x49)
                writeByte(type)
                writeIvars(value, depth + 1)
            }
        }

        private fun writeBody(value: MarshalValue, depth: Int) {
            when (value) {
                is MarshalString -> {
                    writeByte(0x22)
                    writeBytes(value.bytes)
                }
                is MarshalBigInt -> {
                    writeByte(0x6C)
                    writeBigInt(value.value)
                }
                is MarshalFloat -> {
                    writeByte(0x66)
                    writeBytes(value.text.toByteArray(Charsets.US_ASCII))
                }
                is MarshalArray -> {
                    writeByte(0x5B)
                    writeLong(value.elements.size.toLong())
                    for (element in value.elements) writeValue(element, depth + 1)
                }
                is MarshalHash -> {
                    writeByte(if (value.defaultValue == null) 0x7B else 0x7D)
                    writeLong(value.entries.size.toLong())
                    for (entry in value.entries) {
                        writeValue(entry.key, depth + 1)
                        writeValue(entry.value, depth + 1)
                    }
                    value.defaultValue?.let { writeValue(it, depth + 1) }
                }
                is MarshalObject -> {
                    writeByte(0x6F)
                    writeSymbol(MarshalSymbol.of(value.className), depth + 1)
                    writeLong(value.ivars.size.toLong())
                    for (ivar in value.ivars) {
                        writeSymbol(MarshalSymbol.of(ivar.name), depth + 1)
                        writeValue(ivar.value, depth + 1)
                    }
                }
                else -> throw MarshalException(MarshalException.Reason.VALUE, "body")
            }
        }

        private fun writeIvars(value: MarshalValue, depth: Int) {
            writeLong(value.ivars.size.toLong())
            for (ivar in value.ivars) {
                writeSymbol(MarshalSymbol.of(ivar.name), depth + 1)
                writeValue(ivar.value, depth + 1)
            }
        }

        private fun writeSymbol(symbol: MarshalSymbol, depth: Int) {
            val key = String(symbol.bytes, Charsets.ISO_8859_1)
            val existing = symbols[key]
            if (existing != null) {
                writeByte(0x3B)
                writeLong(existing.toLong())
                return
            }
            if (symbols.size >= limits.maxSymbols) {
                throw MarshalException(MarshalException.Reason.LIMIT, "symbols")
            }
            symbols[key] = symbols.size
            val hasIvars = symbol.ivars.isNotEmpty()
            if (hasIvars) writeByte(0x49)
            writeByte(0x3A)
            writeBytes(symbol.bytes)
            if (hasIvars) writeIvars(symbol, depth + 1)
        }

        private fun writeBigInt(value: BigInteger) {
            val magnitude = value.abs()
            val raw = magnitude.toByteArray()
            val words = (raw.size + 1) / 2
            if (words > limits.maxCollection) {
                throw MarshalException(MarshalException.Reason.LIMIT, "bignum words")
            }
            writeByte(if (value.signum() < 0) 0x2D else 0x2B)
            writeLong(words.toLong())
            var index = raw.size - 1
            for (word in 0 until words) {
                val low = raw[index].toInt() and 0xFF
                val high = if (index - 1 >= 0) raw[index - 1].toInt() and 0xFF else 0
                writeByte(low)
                writeByte(high)
                index -= 2
            }
        }

        private fun writeBytes(bytes: ByteArray) {
            writeLong(bytes.size.toLong())
            writeRaw(bytes)
        }

        private fun writeLong(value: Long) {
            if (value == 0L) {
                writeByte(0)
                return
            }
            if (value in 1L..122L) {
                writeByte((value + 5).toInt())
                return
            }
            if (value in -123L..-1L) {
                writeByte((value - 5).toInt() and 0xFF)
                return
            }
            var current = value
            val buffer = ByteArray(4)
            for (length in 1..4) {
                buffer[length - 1] = (current and 0xFF).toByte()
                current = current shr 8
                if (current == 0L) {
                    writeByte(length)
                    writeRaw(buffer, length)
                    return
                }
                if (current == -1L) {
                    writeByte((-length) and 0xFF)
                    writeRaw(buffer, length)
                    return
                }
            }
            throw MarshalException(MarshalException.Reason.VALUE, "fixnum range")
        }

        private fun writeByte(value: Int) {
            output.write(value and 0xFF)
            if (output.size() > limits.maxBytes) {
                throw MarshalException(MarshalException.Reason.LIMIT, "output bytes")
            }
        }

        private fun writeRaw(bytes: ByteArray, count: Int = bytes.size) {
            output.write(bytes, 0, count)
            if (output.size() > limits.maxBytes) {
                throw MarshalException(MarshalException.Reason.LIMIT, "output bytes")
            }
        }
    }
}

private val UNSUPPORTED_MARSHAL_TYPES = setOf(
    0x75, // u: user-defined _dump
    0x55, // U: user-marshal
    0x65, // e: extended object
    0x43, // C: subclass of a built-in
    0x53, // S: Struct
    0x63, // c: Class
    0x6D, // m: Module
    0x64, // d: Data
    0x2F, // /: Regexp
    0x4D, // M: old module
)

internal fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private val SHIFT_JIS_CHARSET: Charset? = try {
    Charset.forName("Shift_JIS")
} catch (_: Exception) {
    null
}

internal fun decodeShiftJisOrNull(bytes: ByteArray): String? {
    val charset = SHIFT_JIS_CHARSET ?: return null
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
