package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LG `.trans` reader/writer over the immutable kotlinx JSON tree.
 *
 * The adapter owns only text. It does not discover, zip, copy or rename assets, and it has no
 * Android or model dependency. Reading preserves every unknown project/file/row field because
 * the frozen original is never rewritten; writing copies the parsed tree, patches only rows
 * addressed by `extra_field.trans_ref`, and serializes the result. Unknown values keep their
 * original JSON type (including the original number lexeme).
 *
 * Ported pure-text LG behavior: indexOriginal/indexTranslation defaults, empty-source and
 * `aqua` skip rules, red/blue/`gold` filtering, RPG Maker path/address filters, WOLF
 * whitelist/blacklist/blocked-database-text filters, and mixed-partition parameter generation.
 * Not ported here: LG's file grouping / asset IO, its generic project rules and
 * text-resource-reference filters (they run outside the engine processor in LG), and engine
 * branches other than `wolf`/`wolfrpg` and the RPG Maker names below (they fall back to the
 * same NONE processor as LG). Targeting rows may also update LG's `gold` tag and mixed-partition
 * `parameters` exactly where the processors require it; all other fields and rows stay literal.
 * Changed output is compact JSON (values and key order are preserved, formatting is not);
 * a no-op write returns the original text. A leading UTF-8 BOM is accepted and preserved on
 * changed output. `options.bilingual` is rejected because a `.trans` target cell has no
 * lossless bilingual representation; name/size options have no TRANS slots.
 * Negative/fractional/string indices fall back to 0/1 like LG; indices above 10,000 and
 * targeted row indices above 200,000 are rejected as explicit memory bounds.
 */
class TransFormatAdapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.TRANS

    override fun read(text: String, path: RelativePath): FormatDocument {
        TransRenpyFormatBounds.requireReadable(text, path)
        val parsed = parseTransContainer(text)
        val processor = TransProcessor.create(parsed.gameEngine, parsed.files)
        val units = ArrayList<FormatUnit>()
        val warnings = ArrayList<FormatWarning>()
        for ((fileKey, entryValue) in parsed.files) {
            if (!validTransFileKey(fileKey)) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
            }
            val entry = entryValue as? JsonObject
            if (entry == null) {
                addTransWarning(warnings, path, "TRANS_FILE_ENTRY", "Skipped a non-object file entry")
                continue
            }
            val dataValue = entry["data"]
            if (dataValue != null && dataValue !is JsonArray) {
                addTransWarning(warnings, path, "TRANS_DATA", "Skipped a non-array data field")
                continue
            }
            val data = dataValue as? JsonArray ?: continue
            val tags = entry["tags"] as? JsonArray
            val context = entry["context"] as? JsonArray
            val parameters = entry["parameters"] as? JsonArray
            for ((rowIndex, rowValue) in data.withIndex()) {
                if (units.size >= TransRenpyFormatBounds.MAX_UNITS) {
                    throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, rowIndex)
                }
                val dataRow = rowValue as? JsonArray
                val source = dataRow.stringAt(parsed.indexOriginal)
                val translation = dataRow.stringAt(parsed.indexTranslation)
                val tag = stringArray(tags?.getOrNull(rowIndex))
                val contextRow = stringArray(context?.getOrNull(rowIndex))
                val parameter = recordArray(parameters?.getOrNull(rowIndex))
                for (value in tag) requireTransTagText(value, units.size)
                for (value in contextRow) requireTransTagText(value, units.size)
                for (value in parameter) validateTransParameter(value, units.size)
                val checked = processor.check(fileKey, source, translation, tag, contextRow)
                TransRenpyFormatBounds.requireUnitText(checked.src, units.size)
                TransRenpyFormatBounds.requireUnitText(checked.dst, units.size)
                val extra = buildTransExtra(
                    tag = checked.tag,
                    context = contextRow,
                    parameter = parameter,
                    fileKey = fileKey,
                    rowIndex = rowIndex,
                    engine = parsed.gameEngine,
                )
                if (extra.length > TransRenpyFormatBounds.MAX_EXTRA_CHARS) {
                    throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, units.size)
                }
                validateTransExtra(extra, units.size)
                units.add(
                    FormatUnit(
                        row = units.size,
                        source = checked.src,
                        translation = checked.dst,
                        status = checked.status,
                        tag = fileKey,
                        extraField = extra,
                        skipInternalFilter = checked.skipInternalFilter,
                    ),
                )
            }
        }
        val metadata = buildJsonObject {
            put("format", "trans")
            put("version", 1)
            put("game_engine", parsed.gameEngine)
            put("index_original", parsed.indexOriginal)
            put("index_translation", parsed.indexTranslation)
            put("file_count", parsed.files.size)
            put("unit_count", units.size)
        }.toString()
        return FormatDocument(format, units, warnings, parsed.gameEngine, metadata)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        TransRenpyFormatBounds.requireReadable(original)
        if (options.bilingual) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.UNSUPPORTED_OPTION)
        }
        if (units.size > TransRenpyFormatBounds.MAX_UNITS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
        }
        if (units.isEmpty()) return original

        val hasBom = original.startsWith('\uFEFF')
        val parsed = parseTransContainer(original)
        val processor = TransProcessor.create(parsed.gameEngine, parsed.files)
        val targets = ArrayList<TransPatchTarget>(units.size)
        val seen = HashSet<TransRef>()
        var projectedChars = original.length.toLong()
        for (unit in units) {
            TransRenpyFormatBounds.requireUnitText(unit.source, unit.row)
            TransRenpyFormatBounds.requireUnitText(unit.translation, unit.row)
            val reference = readTransRef(unit)
            if (unit.tag.isNotEmpty() && unit.tag != reference.fileKey) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
            }
            if (!seen.add(reference)) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.DUPLICATE_LOCATOR, unit.row)
            }
            val entry = parsed.files[reference.fileKey] as? JsonObject
                ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
            val data = entry["data"] as? JsonArray
                ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
            if (reference.rowIndex >= data.size) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
            }
            val dataRow = data[reference.rowIndex] as? JsonArray
            if (dataRow.stringAt(parsed.indexOriginal) != unit.source) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.STALE_LOCATOR, unit.row)
            }
            if (unit.status == TextItemStatus.PROCESSED) {
                if (dataRow == null) {
                    throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED, unit.row)
                }
                projectedChars += jsonEscapedLength(unit.translation) + 2L
                if (projectedChars > TransRenpyFormatBounds.MAX_OUTPUT_CHARS) {
                    throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, unit.row)
                }
            }
            targets.add(
                TransPatchTarget(
                    fileKey = reference.fileKey,
                    rowIndex = reference.rowIndex,
                    source = unit.source,
                    translation = unit.translation,
                    status = unit.status,
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val mutableRoot = toMutableJson(parsed.root) as? MutableMap<String, Any?>
            ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
        @Suppress("UNCHECKED_CAST")
        val mutableProject = mutableRoot["project"] as? MutableMap<String, Any?>
            ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
        @Suppress("UNCHECKED_CAST")
        val mutableFiles = mutableProject["files"] as? MutableMap<String, Any?>
            ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)

        val paddingBudget = TransPaddingBudget(500_000L)
        var changed = false
        for (target in targets) {
            @Suppress("UNCHECKED_CAST")
            val entryMap = mutableFiles[target.fileKey] as? MutableMap<String, Any?>
                ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED, target.rowIndex)
            val tagRow = mutableStringRow(entryMap["tags"], target.rowIndex)
            val contextRow = mutableStringRow(entryMap["context"], target.rowIndex)
            val parameterRow = mutableRowValue(entryMap["parameters"], target.rowIndex)
            val effect = deriveTransFilterEffect(
                block = processor.filter(target.source, target.fileKey, tagRow, contextRow),
                tag = tagRow,
                parameter = parameterRow,
            )
            if (effect.tag != tagRow) {
                putTransRow(
                    container = entryMap,
                    field = "tags",
                    row = target.rowIndex,
                    value = JsonArray(effect.tag.map { JsonPrimitive(it) }),
                    empty = JsonArray(emptyList<JsonElement>()),
                    budget = paddingBudget,
                )
                changed = true
            }
            if (effect.isMixedPartition) {
                val generated = processor.generateParameters(
                    src = target.source,
                    context = contextRow,
                    parameter = parameterRow,
                    block = effect.block,
                )
                val parametersChanged = putTransRow(
                    container = entryMap,
                    field = "parameters",
                    row = target.rowIndex,
                    value = JsonArray(generated.map { toJsonElement(it) }),
                    empty = JsonNull,
                    budget = paddingBudget,
                )
                if (parametersChanged) changed = true
            }
            if (target.status == TextItemStatus.PROCESSED) {
                @Suppress("UNCHECKED_CAST")
                val data = entryMap["data"] as? MutableList<Any?>
                    ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED, target.rowIndex)
                val rowValue = data.getOrNull(target.rowIndex)
                @Suppress("UNCHECKED_CAST")
                val row = rowValue as? MutableList<Any?>
                    ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED, target.rowIndex)
                if (row.size <= parsed.indexTranslation) {
                    paddingBudget.consume(
                        parsed.indexTranslation + 1 - row.size,
                        target.rowIndex,
                    )
                    while (row.size <= parsed.indexTranslation) row.add(EMPTY_JSON_STRING)
                }
                val next = JsonPrimitive(target.translation)
                if (row[parsed.indexTranslation] != next) {
                    row[parsed.indexTranslation] = next
                    changed = true
                }
            }
        }
        if (!changed) return original
        val serialized = toJsonElement(mutableRoot).toString()
        val output = if (hasBom) "\uFEFF$serialized" else serialized
        TransRenpyFormatBounds.requireOutput(output)
        return output
    }
}

private const val TRANS_MAX_INDEX = 10_000
private const val TRANS_MAX_ROW_INDEX = 200_000
private const val TRANS_MAX_TAG_CHARS = 4_096
private const val TRANS_MAX_WARNINGS = 100

private class ParsedTransContainer(
    val root: JsonObject,
    val files: JsonObject,
    val indexOriginal: Int,
    val indexTranslation: Int,
    val gameEngine: String,
)

private data class TransRef(val fileKey: String, val rowIndex: Int)

private class TransPatchTarget(
    val fileKey: String,
    val rowIndex: Int,
    val source: String,
    val translation: String,
    val status: TextItemStatus,
)

/** Caps newly allocated row slots across tag, parameter and data padding in one write. */
private class TransPaddingBudget(private var remaining: Long) {
    fun consume(count: Int, row: Int) {
        if (count <= 0) return
        if (count.toLong() > remaining) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, row)
        }
        remaining -= count.toLong()
    }
}

private val EMPTY_JSON_STRING = JsonPrimitive("")

private class TransCheckResult(
    val src: String,
    val dst: String,
    val tag: List<String>,
    val status: TextItemStatus,
    val skipInternalFilter: Boolean,
)

private class TransFilterEffect(
    val block: List<Boolean>,
    val tag: List<String>,
    val status: TextItemStatus,
    val isMixedPartition: Boolean,
)

private fun parseTransContainer(text: String): ParsedTransContainer {
    val jsonText = if (text.startsWith('\uFEFF')) text.substring(1) else text
    val root = try {
        JsonObjectValue.parse(
            jsonText,
            JsonInputLimits(TransRenpyFormatBounds.MAX_INPUT_CHARS, TransRenpyFormatBounds.MAX_JSON_DEPTH),
        ).json
    } catch (failure: InvalidJsonPayload) {
        throw InvalidTransRenpyFormat(
            when (failure.reason) {
                InvalidJsonPayload.Reason.SIZE,
                InvalidJsonPayload.Reason.DEPTH -> InvalidTransRenpyFormat.Reason.LIMIT
                else -> InvalidTransRenpyFormat.Reason.MALFORMED
            },
        )
    }
    val project = root["project"] as? JsonObject
        ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
    val files = when (val value = project["files"]) {
        null -> JsonObject(emptyMap<String, JsonElement>())
        is JsonObject -> value
        else -> throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
    }
    return ParsedTransContainer(
        root = root,
        files = files,
        indexOriginal = readTransIndex(project["indexOriginal"], 0),
        indexTranslation = readTransIndex(project["indexTranslation"], 1),
        gameEngine = readTransEngine(project),
    )
}

private fun readTransIndex(value: JsonElement?, fallback: Int): Int {
    val primitive = value as? JsonPrimitive ?: return fallback
    if (primitive.isString) return fallback
    val decimal = try {
        BigDecimal(primitive.content)
    } catch (_: NumberFormatException) {
        return fallback
    }
    if (decimal.signum() < 0) return fallback
    if (decimal.signum() == 0) return 0
    if (decimal.stripTrailingZeros().scale() > 0) return fallback
    if (decimal > BigDecimal.valueOf(TRANS_MAX_INDEX.toLong())) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT)
    }
    return try {
        decimal.intValueExact()
    } catch (_: ArithmeticException) {
        fallback
    }
}

private fun readTransEngine(project: JsonObject): String {
    val value = project["gameEngine"] ?: return ""
    if (value !is JsonPrimitive || !value.isString) return ""
    val engine = value.content
    if (engine.length > 64 || engine.any { it.isISOControl() }) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
    }
    TransRenpyFormatBounds.strictUtf8(engine)
    return engine
}

private fun validTransFileKey(value: String): Boolean {
    if (value.length > TransRenpyFormatBounds.MAX_PATH_CHARS || value.any { it.isISOControl() }) {
        return false
    }
    return try {
        TransRenpyFormatBounds.strictUtf8(value)
        true
    } catch (_: InvalidTransRenpyFormat) {
        false
    }
}

private fun requireTransTagText(value: String, row: Int) {
    if (value.length > TRANS_MAX_TAG_CHARS) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, row)
    }
    TransRenpyFormatBounds.requireUnitText(value, row)
}

private fun validateTransParameter(element: JsonElement?, row: Int) {
    when (element) {
        is JsonObject -> for ((key, value) in element) {
            TransRenpyFormatBounds.requireUnitText(key, row)
            validateTransParameter(value, row)
        }
        is JsonArray -> for (value in element) validateTransParameter(value, row)
        is JsonPrimitive -> if (element.isString) {
            TransRenpyFormatBounds.requireUnitText(element.content, row)
        }
        else -> Unit
    }
}

private fun addTransWarning(
    warnings: MutableList<FormatWarning>,
    path: RelativePath,
    code: String,
    message: String,
) {
    if (warnings.size >= TRANS_MAX_WARNINGS) return
    warnings.add(FormatWarning(code, path.value, null, message))
}

private fun stringArray(value: JsonElement?): List<String> {
    val array = value as? JsonArray ?: return emptyList()
    val result = ArrayList<String>(array.size)
    for (element in array) {
        if (element is JsonPrimitive && element.isString) result.add(element.content)
    }
    return result
}

private fun recordArray(value: JsonElement?): List<JsonObject> {
    val array = value as? JsonArray ?: return emptyList()
    return array.filterIsInstance<JsonObject>()
}

private fun buildTransExtra(
    tag: List<String>,
    context: List<String>,
    parameter: List<JsonObject>,
    fileKey: String,
    rowIndex: Int,
    engine: String,
): String = buildJsonObject {
    put("tag", JsonArray(tag.map { JsonPrimitive(it) }))
    put("context", JsonArray(context.map { JsonPrimitive(it) }))
    put("parameter", JsonArray(parameter))
    put(
        "trans_ref",
        buildJsonObject {
            put("file_key", fileKey)
            put("row_index", rowIndex)
        },
    )
    put("engine", engine)
}.toString()

private fun validateTransExtra(extra: String, row: Int) {
    try {
        JsonObjectValue.parse(extra, JsonInputLimits(TransRenpyFormatBounds.MAX_EXTRA_CHARS, 32))
    } catch (failure: InvalidJsonPayload) {
        throw InvalidTransRenpyFormat(
            if (failure.reason == InvalidJsonPayload.Reason.SIZE ||
                failure.reason == InvalidJsonPayload.Reason.DEPTH
            ) {
                InvalidTransRenpyFormat.Reason.LIMIT
            } else {
                InvalidTransRenpyFormat.Reason.MALFORMED
            },
            row,
        )
    }
}

/** Conservative JSON string-body length for the kotlinx printer; normal text stays 1:1. */
private fun jsonEscapedLength(text: String): Long {
    var length = 0L
    for (current in text) {
        length += when {
            current == '"' || current == '\\' -> 2L
            current.isISOControl() || current == '\u2028' || current == '\u2029' -> 6L
            else -> 1L
        }
    }
    return length
}

private fun readTransRef(unit: FormatUnit): TransRef {
    val extra = try {
        JsonObjectValue.parse(
            unit.extraField,
            JsonInputLimits(TransRenpyFormatBounds.MAX_EXTRA_CHARS, 32),
        ).json
    } catch (_: InvalidJsonPayload) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    val reference = extra["trans_ref"] as? JsonObject
        ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    val fileKeyValue = reference["file_key"] as? JsonPrimitive
        ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    if (!fileKeyValue.isString || !validTransFileKey(fileKeyValue.content)) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    val rowValue = reference["row_index"] as? JsonPrimitive
        ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    if (rowValue.isString) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    val decimal = try {
        BigDecimal(rowValue.content)
    } catch (_: NumberFormatException) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    if (decimal.signum() < 0) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    if (decimal.signum() == 0) return TransRef(fileKeyValue.content, 0)
    if (decimal.stripTrailingZeros().scale() > 0) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    if (decimal > BigDecimal.valueOf(TRANS_MAX_ROW_INDEX.toLong())) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, unit.row)
    }
    val rowIndex = try {
        decimal.intValueExact()
    } catch (_: ArithmeticException) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    if (rowIndex < 0) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    return TransRef(fileKeyValue.content, rowIndex)
}

private fun JsonArray?.stringAt(index: Int): String {
    val value = this?.getOrNull(index) ?: return ""
    val primitive = value as? JsonPrimitive ?: return ""
    return if (primitive.isString) primitive.content else ""
}

/** Deep mutable copy. Only maps/lists become mutable containers; primitives stay untouched. */
private fun toMutableJson(element: JsonElement): Any? = when (element) {
    is JsonObject -> LinkedHashMap<String, Any?>(element.size).also { map ->
        for ((key, value) in element) map[key] = toMutableJson(value)
    }
    is JsonArray -> ArrayList<Any?>(element.size).also { list ->
        for (value in element) list.add(toMutableJson(value))
    }
    else -> element
}

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(
        value.entries.associateTo(LinkedHashMap<String, JsonElement>()) { (key, element) ->
            (key as String) to toJsonElement(element)
        },
    )
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is JsonElement -> value
    else -> throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
}

private fun mutableStringRow(value: Any?, row: Int): List<String> {
    val rows = value as? List<*> ?: return emptyList()
    val rowValue = rows.getOrNull(row) as? List<*> ?: return emptyList()
    val result = ArrayList<String>(rowValue.size)
    for (element in rowValue) {
        if (element is JsonPrimitive && element.isString) result.add(element.content)
    }
    return result
}

private fun mutableRowValue(value: Any?, row: Int): Any? =
    (value as? List<*>)?.getOrNull(row)

/**
 * Ensures an optional row array exists, pads with [empty] and writes [value]. Returns whether
 * the stored tree changed. Replacing a non-array field with an array is a change.
 */
private fun putTransRow(
    container: MutableMap<String, Any?>,
    field: String,
    row: Int,
    value: JsonElement,
    empty: JsonElement,
    budget: TransPaddingBudget,
): Boolean {
    val existing = container[field]
    val list = existing as? MutableList<Any?>
    var changed = existing != null && list == null
    val target = list ?: ArrayList<Any?>().also { container[field] = it }
    if (target.size <= row) budget.consume(row + 1 - target.size, row)
    while (target.size <= row) {
        target.add(empty)
        changed = true
    }
    if (target[row] != value) {
        target[row] = value
        changed = true
    }
    return changed
}

private fun hasColorBlockTag(tag: List<String>): Boolean =
    tag.any { it == "red" || it == "blue" }

private fun deriveTransFilterEffect(
    block: List<Boolean>,
    tag: List<String>,
    parameter: Any?,
): TransFilterEffect {
    val normalized = if (block.isEmpty()) listOf(false) else block
    val hasBlocked = normalized.any { it }
    val hasUnblocked = normalized.any { !it }
    val nextTag = when {
        hasBlocked -> if (tag.any { it == "red" || it == "blue" || it == "gold" }) {
            tag
        } else {
            tag + "gold"
        }
        tag.contains("gold") && !tag.any { it == "red" || it == "blue" } -> {
            tag.filter { it != "gold" }
        }
        else -> tag
    }
    return TransFilterEffect(
        block = normalized,
        tag = nextTag,
        status = if (hasUnblocked) TextItemStatus.NONE else TextItemStatus.RULE_SKIPPED,
        isMixedPartition = hasBlocked && hasUnblocked && canGenerateTransPartitionParameter(parameter),
    )
}

private fun canGenerateTransPartitionParameter(parameter: Any?): Boolean {
    val values = parameter as? List<*> ?: emptyList<Any?>()
    val hasPartition = values.any { value ->
        isJsonRecord(value) && (hasKey(value, "contextStr") || hasKey(value, "translation"))
    }
    val hasSpan = values.any { value ->
        isJsonRecord(value) &&
            (hasKey(value, "start") || hasKey(value, "end") ||
                hasKey(value, "enclosure") || hasKey(value, "lineIndent"))
    }
    return hasPartition || !hasSpan
}

private fun isJsonRecord(value: Any?): Boolean = value is Map<*, *>

private fun hasKey(value: Any?, key: String): Boolean =
    (value as? Map<*, *>)?.containsKey(key) == true

private enum class TransEngine { NONE, RPGMAKER, WOLF }

private class TransProcessor private constructor(
    private val engine: TransEngine,
    private val blockedText: Set<String>,
) {
    fun check(
        fileKey: String,
        src: String,
        dst: String,
        tag: List<String>,
        context: List<String>,
    ): TransCheckResult {
        if (src.isEmpty()) {
            return TransCheckResult(src, dst, tag, TextItemStatus.RULE_SKIPPED, false)
        }
        if (tag.any { it == "aqua" }) {
            return TransCheckResult(src, dst, tag, TextItemStatus.NONE, true)
        }
        if (dst.isNotEmpty() && src != dst) {
            return TransCheckResult(src, dst, tag, TextItemStatus.PROCESSED, false)
        }
        val effect = deriveTransFilterEffect(filter(src, fileKey, tag, context), tag, null)
        return TransCheckResult(src, dst, effect.tag, effect.status, false)
    }

    fun filter(
        src: String,
        fileKey: String,
        tag: List<String>,
        context: List<String>,
    ): List<Boolean> {
        val length = if (context.isEmpty()) 1 else context.size
        return when (engine) {
            TransEngine.NONE -> List(length) { hasColorBlockTag(tag) }
            TransEngine.RPGMAKER -> {
                if (fileKey.endsWith(".js", ignoreCase = true)) {
                    List(length) { true }
                } else if (context.isEmpty()) {
                    listOf(hasColorBlockTag(tag))
                } else {
                    context.map { address ->
                        if (hasColorBlockTag(tag)) true else rpgMakerAddressBlocked(address)
                    }
                }
            }
            TransEngine.WOLF -> {
                if (context.isEmpty()) {
                    listOf(hasColorBlockTag(tag))
                } else {
                    context.map { address ->
                        when {
                            WOLF_WHITELIST.any { it.containsMatchIn(address) } -> false
                            WOLF_BLACKLIST.any { it.containsMatchIn(address) } -> true
                            hasColorBlockTag(tag) -> true
                            address.startsWith("common/", ignoreCase = true) -> true
                            WOLF_DATABASE_VALUE.containsMatchIn(address) && src in blockedText -> true
                            else -> false
                        }
                    }
                }
            }
        }
    }

    fun generateParameters(
        src: String,
        context: List<String>,
        parameter: Any?,
        block: List<Boolean>,
    ): List<Any?> {
        if (block.isEmpty() || block.all { it } || block.none { it }) {
            return transParameterObjects(parameter)
        }
        if (!canGenerateTransPartitionParameter(parameter)) {
            return transParameterObjects(parameter)
        }
        val values = parameter as? List<*> ?: emptyList<Any?>()
        val result = ArrayList<Any?>(maxOf(values.size, block.size))
        for (value in values) {
            val copy = LinkedHashMap<String, Any?>()
            if (value is Map<*, *>) {
                for ((key, element) in value) copy[key as String] = element
            }
            result.add(copy)
        }
        for (index in block.indices) {
            while (result.size <= index) result.add(LinkedHashMap<String, Any?>())
            @Suppress("UNCHECKED_CAST")
            val entry = result[index] as? MutableMap<String, Any?>
                ?: LinkedHashMap<String, Any?>().also { result[index] = it }
            entry["contextStr"] = context.getOrNull(index) ?: ""
            entry["translation"] = if (block[index]) src else ""
        }
        return result
    }

    companion object {
        private val RPG_MAKER_ENGINES = setOf(
            "2k", "2k3", "rmjdb", "rmxp", "rmvx", "rmvxace", "rmmv", "rmmz",
        )

        fun create(gameEngine: String, files: JsonObject): TransProcessor {
            val normalized = gameEngine.lowercase()
            val engine = when {
                normalized == "wolf" || normalized == "wolfrpg" -> TransEngine.WOLF
                normalized in RPG_MAKER_ENGINES -> TransEngine.RPGMAKER
                else -> TransEngine.NONE
            }
            return TransProcessor(
                engine = engine,
                blockedText = if (engine == TransEngine.WOLF) collectWolfBlockedText(files) else emptySet(),
            )
        }
    }
}

private fun transParameterObjects(parameter: Any?): List<Any?> {
    val values = parameter as? List<*> ?: return emptyList()
    val result = ArrayList<Any?>(values.size)
    for (value in values) {
        if (isJsonRecord(value)) result.add(value)
    }
    return result
}

private val RPG_MAKER_ADDRESS_PATTERNS = listOf(
    Regex("/events/\\d+/name", RegexOption.IGNORE_CASE),
    Regex("Tilesets/\\d+/name", RegexOption.IGNORE_CASE),
    Regex("MapInfos/\\d+/name", RegexOption.IGNORE_CASE),
    Regex("Animations/\\d+/name", RegexOption.IGNORE_CASE),
    Regex("CommonEvents/\\d+/name", RegexOption.IGNORE_CASE),
)

private fun rpgMakerAddressBlocked(address: String): Boolean {
    if (address.contains("MZ Plugin Command", ignoreCase = true) &&
        !address.contains("text", ignoreCase = true)
    ) {
        return true
    }
    if (address.contains("filename", ignoreCase = true)) return true
    return RPG_MAKER_ADDRESS_PATTERNS.any { it.containsMatchIn(address) }
}

private val WOLF_WHITELIST = listOf(
    Regex("/Database/stringArgs/0\$", RegexOption.IGNORE_CASE),
    Regex("/CommonEvent/stringArgs/\\d*[1-9]\\d*\$", RegexOption.IGNORE_CASE),
    Regex("/CommonEventByName/stringArgs/\\d*[1-9]\\d*\$", RegexOption.IGNORE_CASE),
    Regex("/Message/stringArgs/\\d+\$", RegexOption.IGNORE_CASE),
    Regex("/Picture/stringArgs/\\d+\$", RegexOption.IGNORE_CASE),
    Regex("/Choices/stringArgs/\\d+\$", RegexOption.IGNORE_CASE),
    Regex("/SetString/stringArgs/\\d+\$", RegexOption.IGNORE_CASE),
    Regex("/StringCondition/stringArgs/\\d+\$", RegexOption.IGNORE_CASE),
)

private val WOLF_BLACKLIST = listOf(
    Regex("/Database/stringArgs/\\d*[1-9]\\d*\$", RegexOption.IGNORE_CASE),
    Regex("/CommonEvent/stringArgs/0\$", RegexOption.IGNORE_CASE),
    Regex("/CommonEventByName/stringArgs/0\$", RegexOption.IGNORE_CASE),
    Regex("/name\$", RegexOption.IGNORE_CASE),
    Regex("/description\$", RegexOption.IGNORE_CASE),
    Regex("/Comment/stringArgs/", RegexOption.IGNORE_CASE),
    Regex("/DebugMessage/stringArgs/", RegexOption.IGNORE_CASE),
)

private val WOLF_DATABASE_VALUE = Regex(
    "DataBase\\.json/types/\\d+/data/\\d+/data/\\d+/value",
    RegexOption.IGNORE_CASE,
)

private val WOLF_DATABASE_NONZERO = Regex(
    "/Database/stringArgs/\\d*[1-9]\\d*\$",
    RegexOption.IGNORE_CASE,
)

private fun collectWolfBlockedText(files: JsonObject): Set<String> {
    val blocked = HashSet<String>()
    for (entryValue in files.values) {
        val entry = entryValue as? JsonObject ?: continue
        val data = entry["data"] as? JsonArray ?: continue
        val context = entry["context"] as? JsonArray ?: continue
        val length = maxOf(data.size, context.size)
        for (index in 0 until length) {
            val dataItems = stringArray(data.getOrNull(index))
            if (dataItems.isEmpty()) continue
            val contextText = stringArray(context.getOrNull(index)).joinToString("\n")
            if (WOLF_DATABASE_NONZERO.containsMatchIn(contextText)) {
                val value = dataItems[0]
                if (value.isNotEmpty()) blocked.add(value)
            }
        }
    }
    return blocked
}
