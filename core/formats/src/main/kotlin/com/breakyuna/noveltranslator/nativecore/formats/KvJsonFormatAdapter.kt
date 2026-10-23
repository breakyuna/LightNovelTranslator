package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InvalidKvJson(val reason: Reason, val row: Int? = null) :
    IllegalArgumentException("Invalid key/value JSON: $reason${row?.let { " at row $it" } ?: ""}") {
    enum class Reason { STRUCTURE, TEXT, LIMIT, BINDING, STALE }
}

/**
 * LG-compatible key/value JSON adapter. Every top-level string member becomes one unit:
 * `source` is the key and `translation` is the current value (an existing translation).
 *
 * The writer patches only the raw value span of `PROCESSED` units. Other values, numeric
 * literals, nesting, key order, indentation, line endings and unrelated members keep their
 * original bytes. A unit is located by its 1-based string-ordinal `row` and verified against
 * the source key plus the recorded value hash; a stale or foreign unit fails instead of
 * overwriting a different member. Untouched and empty values stay byte-identical (LG's
 * fallback that substitutes a key for an empty value is deliberately not applied here).
 * LG's KVJSON format has no bilingual mode, so [FormatWriteOptions.bilingual] is ignored.
 */
class KvJsonFormatAdapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.KVJSON

    override fun read(text: String, path: RelativePath): FormatDocument {
        requireKvInput(text)
        val root = parseKvRoot(text, requireObject = true) as KvJsonObject
        val units = ArrayList<FormatUnit>()
        var ordinal = 0
        for (member in root.members) {
            val value = (member.value as? KvJsonString)?.value ?: continue
            ordinal++
            if (ordinal > MAX_UNITS) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT, ordinal)
            if (member.key.length > MAX_UNIT_CHARS || value.length > MAX_UNIT_CHARS) {
                throw InvalidKvJson(InvalidKvJson.Reason.LIMIT, ordinal)
            }
            if ('\u0000' in member.key || '\u0000' in value) {
                throw InvalidKvJson(InvalidKvJson.Reason.TEXT, ordinal)
            }
            val status = when {
                member.key.isEmpty() -> TextItemStatus.RULE_SKIPPED
                value.isNotEmpty() && value != member.key -> TextItemStatus.PROCESSED
                else -> TextItemStatus.NONE
            }
            units.add(FormatUnit(
                row = ordinal,
                source = member.key,
                translation = value,
                status = status,
                extraField = kvExtra(ordinal, value),
            ))
        }
        return FormatDocument(format = format, units = units)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        requireKvInput(original)
        if (units.size > MAX_UNITS) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
        val root = parseKvRoot(original, requireObject = true) as KvJsonObject
        val strings = ArrayList<KvJsonStringMember>()
        var ordinal = 0
        for (member in root.members) {
            val value = member.value as? KvJsonString ?: continue
            ordinal++
            strings.add(KvJsonStringMember(ordinal, member.key, value.value, value.start, value.end))
        }
        val byRow = HashMap<Int, KvJsonStringMember>(strings.size)
        for (member in strings) byRow[member.ordinal] = member
        val edits = ArrayList<KvEdit>()
        var projected = original.length.toLong()
        val seen = HashSet<Int>(units.size)
        for (unit in units) {
            if (unit.row < 1) throw InvalidKvJson(InvalidKvJson.Reason.BINDING, unit.row)
            if (!seen.add(unit.row)) throw InvalidKvJson(InvalidKvJson.Reason.BINDING, unit.row)
            val member = byRow[unit.row] ?: throw InvalidKvJson(InvalidKvJson.Reason.BINDING, unit.row)
            if (member.key != unit.source) throw InvalidKvJson(InvalidKvJson.Reason.STALE, unit.row)
            val extra = kvExtraFields(unit)
            val recordedIndex = kvExtraInt(extra, "index", unit.row)
            if (recordedIndex != null && recordedIndex != unit.row) {
                throw InvalidKvJson(InvalidKvJson.Reason.STALE, unit.row)
            }
            val recordedHash = kvExtraString(extra, "value_sha256", unit.row)
            if (recordedHash != null && recordedHash != kvValueHash(member.value)) {
                throw InvalidKvJson(InvalidKvJson.Reason.STALE, unit.row)
            }
            if (unit.status == TextItemStatus.PROCESSED && unit.translation != member.value) {
                val replacement = kvJsonQuote(unit.translation)
                projected += replacement.length - (member.end - member.start)
                if (projected > MAX_OUTPUT_CHARS) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
                edits.add(KvEdit(member.start, member.end, replacement))
            }
        }
        edits.sortBy { it.start }
        val output = StringBuilder(original.length)
        var cursor = 0
        for (edit in edits) {
            if (edit.start < cursor || edit.end < edit.start) {
                throw InvalidKvJson(InvalidKvJson.Reason.STRUCTURE)
            }
            if (output.length.toLong() + (edit.start - cursor) + edit.replacement.length > MAX_OUTPUT_CHARS) {
                throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
            }
            output.append(original, cursor, edit.start)
            output.append(edit.replacement)
            cursor = edit.end
        }
        output.append(original, cursor, original.length)
        return finishKvOutput(output)
    }

    private fun parseKvRoot(text: String, requireObject: Boolean): KvJsonNode {
        val root = try {
            KvJsonLayout.parse(text)
        } catch (_: KvJsonTextError) {
            throw InvalidKvJson(InvalidKvJson.Reason.TEXT)
        } catch (_: KvJsonSyntaxError) {
            throw InvalidKvJson(InvalidKvJson.Reason.STRUCTURE)
        }
        if (requireObject && root !is KvJsonObject) throw InvalidKvJson(InvalidKvJson.Reason.STRUCTURE)
        return root
    }

    private fun kvExtra(ordinal: Int, value: String): String = buildJsonObject {
        put("index", ordinal)
        put("value_sha256", kvValueHash(value))
    }.toString()

    private fun kvExtraFields(unit: FormatUnit): kotlinx.serialization.json.JsonObject = try {
        JsonObjectValue.parse(unit.extraField, JsonInputLimits(MAX_EXTRA_CHARS, 8)).json
    } catch (_: IllegalArgumentException) {
        throw InvalidKvJson(InvalidKvJson.Reason.BINDING, unit.row)
    }

    private fun kvExtraInt(
        root: kotlinx.serialization.json.JsonObject,
        name: String,
        row: Int,
    ): Int? {
        val value = root[name] ?: return null
        val primitive = value as? JsonPrimitive ?: throw InvalidKvJson(InvalidKvJson.Reason.BINDING, row)
        if (primitive.isString) throw InvalidKvJson(InvalidKvJson.Reason.BINDING, row)
        return primitive.content.toIntOrNull() ?: throw InvalidKvJson(InvalidKvJson.Reason.BINDING, row)
    }

    private fun kvExtraString(
        root: kotlinx.serialization.json.JsonObject,
        name: String,
        row: Int,
    ): String? {
        val value = root[name] ?: return null
        val primitive = value as? JsonPrimitive ?: throw InvalidKvJson(InvalidKvJson.Reason.BINDING, row)
        if (!primitive.isString) throw InvalidKvJson(InvalidKvJson.Reason.BINDING, row)
        return primitive.content
    }

    private fun finishKvOutput(output: StringBuilder): String {
        if (output.length > MAX_OUTPUT_CHARS) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
        val text = output.toString()
        if (kvBytes(text).size > MAX_OUTPUT_BYTES) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
        return text
    }
}

private const val MAX_JSON_INPUT_CHARS = 8 * 1024 * 1024
private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_OUTPUT_CHARS = 24 * 1024 * 1024
private const val MAX_OUTPUT_BYTES = 24 * 1024 * 1024
private const val MAX_UNITS = 100_000
private const val MAX_EXTRA_CHARS = 65_536
private const val MAX_UNIT_CHARS = 65_536
private const val MAX_JSON_DEPTH = 64
private const val MAX_JSON_NODES = 500_000

private fun requireKvInput(text: String) {
    if ('\u0000' in text) throw InvalidKvJson(InvalidKvJson.Reason.TEXT)
    if (text.length > MAX_JSON_INPUT_CHARS) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
    if (kvBytes(text).size > MAX_INPUT_BYTES) throw InvalidKvJson(InvalidKvJson.Reason.LIMIT)
}

private fun kvBytes(text: String): ByteArray = try {
    TextProjectFingerprint.utf8(text)
} catch (_: TextProjectException) {
    throw InvalidKvJson(InvalidKvJson.Reason.TEXT)
}

private fun kvValueHash(value: String): String =
    TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(value))

private class KvJsonStringMember(
    val ordinal: Int,
    val key: String,
    val value: String,
    val start: Int,
    val end: Int,
)

internal class KvEdit(val start: Int, val end: Int, val replacement: String)

/** Strict, bounded, span-preserving JSON scanner shared with the message JSON adapter. */
internal sealed class KvJsonNode {
    abstract val start: Int
    abstract val end: Int
}

internal class KvJsonString(
    override val start: Int,
    override val end: Int,
    val value: String,
) : KvJsonNode()

/** Number, boolean or null literal. */
internal class KvJsonScalar(
    override val start: Int,
    override val end: Int,
) : KvJsonNode()

internal class KvJsonMember(val key: String, val value: KvJsonNode)

internal class KvJsonObject(
    override val start: Int,
    override val end: Int,
    val members: List<KvJsonMember>,
) : KvJsonNode()

internal class KvJsonArray(
    override val start: Int,
    override val end: Int,
    val elements: List<KvJsonNode>,
) : KvJsonNode()

internal class KvJsonSyntaxError(message: String) : IllegalArgumentException(message)

internal class KvJsonTextError : IllegalArgumentException("text")

internal object KvJsonLayout {
    fun parse(text: String): KvJsonNode = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0
        private var nodes = 0

        fun parse(): KvJsonNode {
            if (text.isNotEmpty() && text[0] == '\uFEFF') index = 1
            skipWhitespace()
            if (index >= text.length) fail()
            val parsed = value(0)
            skipWhitespace()
            if (index != text.length) fail()
            return parsed
        }

        private fun value(depth: Int): KvJsonNode {
            nodes++
            if (nodes > MAX_JSON_NODES) fail()
            if (depth > MAX_JSON_DEPTH) fail()
            skipWhitespace()
            val character = text.getOrNull(index) ?: fail()
            return when {
                character == '{' -> objectValue(depth)
                character == '[' -> arrayValue(depth)
                character == '"' -> stringValue()
                character == 't' -> literal("true")
                character == 'f' -> literal("false")
                character == 'n' -> literal("null")
                character == '-' || character.isDigit() -> numberValue()
                else -> fail()
            }
        }

        private fun objectValue(depth: Int): KvJsonObject {
            val start = index
            index++
            skipWhitespace()
            if (text.getOrNull(index) == '}') {
                index++
                return KvJsonObject(start, index, emptyList())
            }
            val members = ArrayList<KvJsonMember>()
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                if (text.getOrNull(index) != '"') fail()
                val key = stringValue().value
                if (!keys.add(key)) fail()
                skipWhitespace()
                if (text.getOrNull(index) != ':') fail()
                index++
                val memberValue = value(depth + 1)
                members.add(KvJsonMember(key, memberValue))
                skipWhitespace()
                when (text.getOrNull(index)) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return KvJsonObject(start, index, members)
                    }
                    else -> fail()
                }
            }
        }

        private fun arrayValue(depth: Int): KvJsonArray {
            val start = index
            index++
            skipWhitespace()
            if (text.getOrNull(index) == ']') {
                index++
                return KvJsonArray(start, index, emptyList())
            }
            val elements = ArrayList<KvJsonNode>()
            while (true) {
                elements.add(value(depth + 1))
                skipWhitespace()
                when (text.getOrNull(index)) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return KvJsonArray(start, index, elements)
                    }
                    else -> fail()
                }
            }
        }

        private fun stringValue(): KvJsonString {
            nodes++
            if (nodes > MAX_JSON_NODES) fail()
            val start = index
            if (text.getOrNull(index) != '"') fail()
            index++
            val builder = StringBuilder()
            while (true) {
                val character = text.getOrNull(index) ?: fail()
                when {
                    character == '"' -> {
                        index++
                        val value = builder.toString()
                        if ('\u0000' in value) throw KvJsonTextError()
                        try {
                            TextProjectFingerprint.utf8(value)
                        } catch (_: TextProjectException) {
                            throw KvJsonTextError()
                        }
                        return KvJsonString(start, index, value)
                    }
                    character == '\\' -> {
                        index++
                        when (text.getOrNull(index) ?: fail()) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) fail()
                                val hex = text.substring(index + 1, index + 5)
                                var code = 0
                                for (digit in hex) {
                                    val digitValue = when (digit) {
                                        in '0'..'9' -> digit - '0'
                                        in 'a'..'f' -> digit - 'a' + 10
                                        in 'A'..'F' -> digit - 'A' + 10
                                        else -> fail()
                                    }
                                    code = code * 16 + digitValue
                                }
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> fail()
                        }
                        index++
                    }
                    character < ' ' -> fail()
                    else -> {
                        builder.append(character)
                        index++
                    }
                }
            }
        }

        private fun numberValue(): KvJsonScalar {
            val start = index
            if (text.getOrNull(index) == '-') index++
            val first = text.getOrNull(index) ?: fail()
            if (first == '0') {
                index++
            } else {
                if (!first.isDigit()) fail()
                while (text.getOrNull(index)?.isDigit() == true) index++
            }
            if (text.getOrNull(index) == '.') {
                index++
                if (text.getOrNull(index)?.isDigit() != true) fail()
                while (text.getOrNull(index)?.isDigit() == true) index++
            }
            if (text.getOrNull(index) == 'e' || text.getOrNull(index) == 'E') {
                index++
                if (text.getOrNull(index) == '+' || text.getOrNull(index) == '-') index++
                if (text.getOrNull(index)?.isDigit() != true) fail()
                while (text.getOrNull(index)?.isDigit() == true) index++
            }
            return KvJsonScalar(start, index)
        }

        private fun literal(word: String): KvJsonScalar {
            if (!text.startsWith(word, index)) fail()
            val start = index
            index += word.length
            val next = text.getOrNull(index)
            if (next != null && next != ' ' && next != '\t' && next != '\r' && next != '\n' &&
                next != ',' && next != ']' && next != '}') fail()
            return KvJsonScalar(start, index)
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\r', '\n' -> index++
                    else -> return
                }
            }
        }

        private fun fail(): Nothing = throw KvJsonSyntaxError("syntax")
    }
}

/** JSON string literal for in-place patching; leaves valid non-ASCII characters unescaped. */
internal fun kvJsonQuote(value: String): String {
    val output = StringBuilder(value.length + 2)
    output.append('"')
    for (character in value) {
        when (character) {
            '"' -> output.append("\\\"")
            '\\' -> output.append("\\\\")
            '\b' -> output.append("\\b")
            '\u000C' -> output.append("\\f")
            '\n' -> output.append("\\n")
            '\r' -> output.append("\\r")
            '\t' -> output.append("\\t")
            else -> {
                if (character < ' ') {
                    output.append("\\u")
                    val code = character.code
                    output.append(HEX[(code shr 12) and 0xF])
                    output.append(HEX[(code shr 8) and 0xF])
                    output.append(HEX[(code shr 4) and 0xF])
                    output.append(HEX[code and 0xF])
                } else {
                    output.append(character)
                }
            }
        }
    }
    output.append('"')
    return output.toString()
}

private const val HEX = "0123456789abcdef"
