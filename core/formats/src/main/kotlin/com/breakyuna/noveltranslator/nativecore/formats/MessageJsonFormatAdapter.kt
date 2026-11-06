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
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InvalidMessageJson(val reason: Reason, val row: Int? = null) :
    IllegalArgumentException("Invalid message JSON: $reason${row?.let { " at row $it" } ?: ""}") {
    enum class Reason { STRUCTURE, TEXT, LIMIT, BINDING, STALE }
}

/**
 * LG-compatible KAG-style message JSON adapter. Only array entries carrying a string
 * `message` become units; `name` (string) or `names` (string array) is kept as the name source
 * and the array index is retained in `extraField`. Rows are 1-based ordinals of message-bearing
 * entries, exactly like LG's `items.length` row.
 *
 * Unlike LG's generated-file rebuild this adapter patches the frozen source in place: message
 * and translated-name spans are replaced, while unknown fields, non-message entries, key order,
 * whitespace and line endings stay byte-exact. `writeTranslatedNames` follows LG's shape rules:
 * a scalar `name` stays scalar and a `names` array only has its first string replaced; other
 * slots are retained. A unit without a translation keeps the source message. LG has no
 * bilingual mode for this format, so [FormatWriteOptions.bilingual] is ignored.
 */
class MessageJsonFormatAdapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.MESSAGEJSON

    override fun read(text: String, path: RelativePath): FormatDocument {
        requireMessageInput(text)
        val root = parseMessageRoot(text)
        val units = ArrayList<FormatUnit>()
        var ordinal = 0
        for ((arrayIndex, element) in root.elements.withIndex()) {
            val record = element as? KvJsonObject ?: continue
            val message = record.value("message") as? KvJsonString ?: continue
            ordinal++
            if (ordinal > MAX_MESSAGES) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT, ordinal)
            if (message.value.length > MAX_MESSAGE_CHARS) {
                throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT, ordinal)
            }
            val nameSource = when (val name = record.value("name")) {
                is KvJsonString -> listOf(name.value)
                else -> (record.value("names") as? KvJsonArray)?.elements?.mapNotNull {
                    (it as? KvJsonString)?.value
                }
            }
            for (name in nameSource ?: emptyList()) {
                if (name.length > MAX_NAME_CHARS) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT, ordinal)
                if ('\u0000' in name) throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT, ordinal)
            }
            if ('\u0000' in message.value) throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT, ordinal)
            units.add(FormatUnit(
                row = ordinal,
                source = message.value,
                status = TextItemStatus.NONE,
                nameSource = nameSource,
                extraField = messageExtra(arrayIndex),
            ))
        }
        return FormatDocument(format = format, units = units)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        requireMessageInput(original)
        if (units.size > MAX_MESSAGES) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
        val root = parseMessageRoot(original)
        val entries = ArrayList<MessageEntry>()
        var ordinal = 0
        for ((arrayIndex, element) in root.elements.withIndex()) {
            val record = element as? KvJsonObject ?: continue
            val message = record.value("message") as? KvJsonString ?: continue
            ordinal++
            val names = record.value("names") as? KvJsonArray
            val firstName = (names?.elements?.firstOrNull { it is KvJsonString }) as? KvJsonString
            entries.add(MessageEntry(
                ordinal = ordinal,
                arrayIndex = arrayIndex,
                message = message,
                name = record.value("name") as? KvJsonString,
                firstName = firstName,
            ))
        }
        val byRow = HashMap<Int, MessageEntry>(entries.size)
        for (entry in entries) byRow[entry.ordinal] = entry
        val edits = ArrayList<KvEdit>()
        var projected = original.length.toLong()
        val seen = HashSet<Int>(units.size)
        for (unit in units) {
            if (unit.row < 1) throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
            if (!seen.add(unit.row)) throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
            val entry = byRow[unit.row] ?: throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
            if (unit.source != entry.message.value) {
                throw InvalidMessageJson(InvalidMessageJson.Reason.STALE, unit.row)
            }
            validateMessageExtra(unit, entry)
            if (unit.status == TextItemStatus.PROCESSED && unit.translation != entry.message.value) {
                val replacement = kvJsonQuote(unit.translation)
                projected += replacement.length - (entry.message.end - entry.message.start)
                if (projected > MAX_OUTPUT_CHARS) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
                edits.add(KvEdit(entry.message.start, entry.message.end, replacement))
            }
            if (options.writeTranslatedNames) {
                val translated = unit.nameTranslation?.firstOrNull() ?: ""
                if (translated.isNotEmpty()) {
                    val node = entry.name ?: entry.firstName
                    if (node != null && node.value != translated) {
                        val replacement = kvJsonQuote(translated)
                        projected += replacement.length - (node.end - node.start)
                        if (projected > MAX_OUTPUT_CHARS) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
                        edits.add(KvEdit(node.start, node.end, replacement))
                    }
                }
            }
        }
        edits.sortBy { it.start }
        val output = StringBuilder(original.length)
        var cursor = 0
        for (edit in edits) {
            if (edit.start < cursor || edit.end < edit.start) {
                throw InvalidMessageJson(InvalidMessageJson.Reason.STRUCTURE)
            }
            if (output.length.toLong() + (edit.start - cursor) + edit.replacement.length > MAX_OUTPUT_CHARS) {
                throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
            }
            output.append(original, cursor, edit.start)
            output.append(edit.replacement)
            cursor = edit.end
        }
        output.append(original, cursor, original.length)
        if (output.length > MAX_OUTPUT_CHARS) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
        val result = output.toString()
        try {
            if (TextProjectFingerprint.utf8(result).size > MAX_OUTPUT_BYTES) {
                throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
            }
        } catch (_: com.breakyuna.noveltranslator.nativecore.domain.TextProjectException) {
            throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT)
        }
        return result
    }

    private fun parseMessageRoot(text: String): KvJsonArray {
        val root = try {
            KvJsonLayout.parse(text)
        } catch (_: KvJsonTextError) {
            throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT)
        } catch (_: KvJsonSyntaxError) {
            throw InvalidMessageJson(InvalidMessageJson.Reason.STRUCTURE)
        }
        return root as? KvJsonArray ?: throw InvalidMessageJson(InvalidMessageJson.Reason.STRUCTURE)
    }

    private fun messageExtra(arrayIndex: Int): String = buildJsonObject {
        put("index", arrayIndex)
    }.toString()

    private fun validateMessageExtra(unit: FormatUnit, entry: MessageEntry) {
        val root = try {
            JsonObjectValue.parse(unit.extraField, JsonInputLimits(MAX_EXTRA_CHARS, 8)).json
        } catch (_: IllegalArgumentException) {
            throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
        }
        val value = root["index"] ?: return
        val primitive = value as? JsonPrimitive ?: throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
        if (primitive.isString) throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
        val index = primitive.content.toIntOrNull()
            ?: throw InvalidMessageJson(InvalidMessageJson.Reason.BINDING, unit.row)
        if (index != entry.arrayIndex) throw InvalidMessageJson(InvalidMessageJson.Reason.STALE, unit.row)
    }
}

private const val MAX_JSON_INPUT_CHARS_LOCAL = 8 * 1024 * 1024
private const val MAX_INPUT_BYTES_LOCAL = 8 * 1024 * 1024
private const val MAX_OUTPUT_CHARS = 24 * 1024 * 1024
private const val MAX_OUTPUT_BYTES = 24 * 1024 * 1024
private const val MAX_MESSAGES = 100_000
private const val MAX_EXTRA_CHARS = 65_536
private const val MAX_MESSAGE_CHARS = 65_536
private const val MAX_NAME_CHARS = 4_096

private class MessageEntry(
    val ordinal: Int,
    val arrayIndex: Int,
    val message: KvJsonString,
    val name: KvJsonString?,
    val firstName: KvJsonString?,
)

private fun KvJsonObject.value(name: String): KvJsonNode? =
    members.firstOrNull { it.key == name }?.value

private fun requireMessageInput(text: String) {
    if ('\u0000' in text) throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT)
    if (text.length > MAX_JSON_INPUT_CHARS_LOCAL) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
    val bytes = try {
        TextProjectFingerprint.utf8(text)
    } catch (_: com.breakyuna.noveltranslator.nativecore.domain.TextProjectException) {
        throw InvalidMessageJson(InvalidMessageJson.Reason.TEXT)
    }
    if (bytes.size > MAX_INPUT_BYTES_LOCAL) throw InvalidMessageJson(InvalidMessageJson.Reason.LIMIT)
}
