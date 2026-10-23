package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.BinaryFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * RPG Maker MV/MZ archive adapter.
 *
 * LG prepares a game directory (or expands an EVB launcher) and then builds a STORE-only ZIP of
 * `data` JSON files, `js/plugins.js` and `js/plugins` scripts for the translation service. This adapter
 * implements that container variant directly: it reads those members with their archive paths as
 * tags and copies every other member (images, audio, core scripts, encrypted media) untouched.
 * EVB expansion, directory walking, media decryption and `System.json` encryption flags remain
 * preparation concerns outside this format core.
 */
class RpgMakerMvMzAdapter : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.RPGMAKER_MV_MZ

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        val archive = ZipContainer.read(bytes)
        val prefix = detectDataRoot(archive) ?: throw FormatContainerException(
            FormatContainerException.Reason.STRUCTURE)
        val collector = UnitCollector()
        val dataFiles = archive.keys.filter { isDataFile(it, prefix) }.sorted()
        val pluginFiles = pluginFiles(archive, prefix)
        for (tag in dataFiles) {
            if (collector.limited) break
            readJsonFile(archive[tag]!!, tag, collector)
        }
        for (tag in pluginFiles) {
            if (collector.limited) break
            readJsFile(archive[tag]!!, tag, collector)
        }
        if (collector.limited) {
            collector.warnings.add(FormatWarning("rpgmaker.unit_limit", "", null,
                "RPG Maker unit limit reached"))
        }
        val engine = when {
            archive.containsKey(prefix + "js/rmmz_core.js") -> "MZ"
            else -> "MV"
        }
        val metadata = buildJsonObject {
            put("v", 1)
            put("data_root", prefix)
            put("data_files", dataFiles.size)
            put("plugin_files", pluginFiles.size)
            put("engine", engine)
        }.toString()
        return FormatDocument(format, collector.units, collector.warnings, engine, metadata)
    }

    override fun write(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray {
        val archive = ZipContainer.read(original)
        val prefix = detectDataRoot(archive) ?: return original
        val groups = LinkedHashMap<String, MutableList<FormatUnit>>()
        for (unit in units) {
            if (!archive.containsKey(unit.tag)) continue
            groups.getOrPut(unit.tag) { ArrayList() }.add(unit)
        }
        if (groups.isEmpty()) return original
        val output = LinkedHashMap<String, ZipMember>(archive)
        var changed = false
        for ((tag, group) in groups) {
            val member = archive[tag] ?: continue
            val rewritten = when {
                isDataFile(tag, prefix) -> writeJsonFile(member.bytes, group, options)
                isPluginFile(tag, prefix) -> writeJsFile(member.bytes, group, options)
                else -> null
            } ?: continue
            if (!rewritten.contentEquals(member.bytes)) {
                output[tag] = member.withBytes(rewritten)
                changed = true
            }
        }
        if (!changed) return original
        return ZipContainer.write(output)
    }

    private fun detectDataRoot(archive: Map<String, ZipMember>): String? {
        val candidates = archive.keys
            .filter { it.endsWith("data/System.json") && !it.endsWith("/") }
            .map { it.removeSuffix("data/System.json") }
            .filter { it.isEmpty() || it.endsWith("/") }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy<String> { if (it.isEmpty()) 0 else if (it == "www/") 1 else 2 }.thenBy { it },
        ).first()
    }

    private fun isDataFile(tag: String, prefix: String): Boolean {
        val dataPrefix = prefix + "data/"
        if (!tag.startsWith(dataPrefix)) return false
        val rest = tag.removePrefix(dataPrefix)
        return rest.isNotEmpty() && '/' !in rest && rest.endsWith(".json", ignoreCase = true)
    }

    private fun isPluginFile(tag: String, prefix: String): Boolean {
        if (tag == prefix + "js/plugins.js") return true
        return tag.startsWith(prefix + "js/plugins/") && tag.endsWith(".js", ignoreCase = true)
    }

    private fun pluginFiles(archive: Map<String, ZipMember>, prefix: String): List<String> {
        val result = ArrayList<String>()
        val entry = prefix + "js/plugins.js"
        if (archive.containsKey(entry)) result.add(entry)
        result.addAll(archive.keys.filter {
            it.startsWith(prefix + "js/plugins/") && it.endsWith(".js", ignoreCase = true) &&
                !it.endsWith("/")
        }.sorted())
        return result.distinct()
    }

    private fun readJsonFile(member: ZipMember, tag: String, collector: UnitCollector) {
        if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            collector.warnings.add(FormatWarning("rpgmaker.file_too_large", safeWarningPath(tag),
                null, "Data file exceeds the text entry bound"))
            return
        }
        val text = decodeUtf8OrNull(member.bytes)
        if (text == null) {
            collector.warnings.add(FormatWarning("rpgmaker.invalid_utf8", safeWarningPath(tag),
                null, "Data file is not valid UTF-8"))
            return
        }
        val jsonText = text.removePrefix("\uFEFF")
        if (jsonNestingTooDeep(jsonText)) {
            collector.warnings.add(FormatWarning("rpgmaker.json_too_deep", safeWarningPath(tag),
                null, "Data file JSON nesting exceeds the bound"))
            return
        }
        val root = try {
            Json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            collector.warnings.add(FormatWarning("rpgmaker.invalid_json", safeWarningPath(tag),
                null, "Data file JSON could not be parsed"))
            return
        }
        val base = tag.substringAfterLast('/').substringBeforeLast('.')
        JsonWalker(tag, base, collector).walk(root)
    }

    private fun readJsFile(member: ZipMember, tag: String, collector: UnitCollector) {
        if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            collector.warnings.add(FormatWarning("rpgmaker.file_too_large", safeWarningPath(tag),
                null, "Plugin file exceeds the text entry bound"))
            return
        }
        val text = decodeUtf8OrNull(member.bytes)
        if (text == null) {
            collector.warnings.add(FormatWarning("rpgmaker.invalid_utf8", safeWarningPath(tag),
                null, "Plugin file is not valid UTF-8"))
            return
        }
        val result = JavaScriptStrings.scan(text)
        if (result.error) {
            collector.warnings.add(FormatWarning("rpgmaker.script_syntax", safeWarningPath(tag),
                null, "JavaScript scanning stopped at an unterminated construct"))
        }
        for (literal in result.literals) {
            if (collector.limited) break
            collector.add(tag, literal.value, TextItemStatus.RULE_SKIPPED,
                jsFileExtra(literal))
        }
    }

    private fun writeJsonFile(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray? {
        if (original.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) return null
        val text = decodeUtf8OrNull(original) ?: return null
        val jsonText = text.removePrefix("\uFEFF")
        if (jsonNestingTooDeep(jsonText)) return null
        var root = try {
            Json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            return null
        }
        val fieldGroups = LinkedHashMap<List<Any>, MutableList<FormatUnit>>()
        val jsFieldGroups = LinkedHashMap<List<Any>, MutableList<FormatUnit>>()
        val blockGroups = LinkedHashMap<BlockKey, MutableList<FormatUnit>>()
        val jsBlockGroups = LinkedHashMap<BlockKey, MutableList<FormatUnit>>()
        for (unit in units) {
            val extra = parseJsonObject(unit.extraField) ?: continue
            when (extraString(extra, "kind")) {
                "field" -> {
                    val path = parseJsonPath(extra["path"]) ?: continue
                    fieldGroups.getOrPut(path) { ArrayList() }.add(unit)
                }
                "jsfield" -> {
                    val path = parseJsonPath(extra["path"]) ?: continue
                    jsFieldGroups.getOrPut(path) { ArrayList() }.add(unit)
                }
                "block" -> {
                    val key = parseBlockKey(extra, "start", "end") ?: continue
                    blockGroups.getOrPut(key) { ArrayList() }.add(unit)
                }
                "jsblock" -> {
                    val key = parseBlockKey(extra, "block_start", "block_end") ?: continue
                    jsBlockGroups.getOrPut(key) { ArrayList() }.add(unit)
                }
            }
        }
        var changed = false

        for ((path, group) in fieldGroups) {
            val current = readAt(root, path) as? JsonPrimitive ?: continue
            if (!current.isString) continue
            val source = current.content
            val matching = group.filter {
                val extra = parseJsonObject(it.extraField)
                extra != null && extraString(extra, "digest") == containerSha256Hex(source)
            }
            val targets = matching.mapNotNull { effectiveTarget(it, options) }.distinct()
            if (targets.size != 1) continue
            val target = targets.single()
            val replacement = if (options.bilingual) source + "\n" + target else target
            if (replacement == source) continue
            val chosen = matching.first { effectiveTarget(it, options) == target }
            root = setAt(root, path, JsonPrimitive(replacement))
            val extra = parseJsonObject(chosen.extraField)
            val mirrors = extra?.get("mirrors") as? JsonArray
            if (mirrors != null) {
                for (mirror in mirrors) {
                    val mirrorPath = parseJsonPath(mirror) ?: continue
                    val value = readAt(root, mirrorPath) as? JsonPrimitive ?: continue
                    if (value.isString && value.content == chosen.source) {
                        root = setAt(root, mirrorPath, JsonPrimitive(replacement))
                    }
                }
            }
            changed = true
        }

        for ((path, group) in jsFieldGroups) {
            val current = readAt(root, path) as? JsonPrimitive ?: continue
            if (!current.isString) continue
            val source = current.content
            val digest = containerSha256Hex(source)
            if (!group.all {
                    val extra = parseJsonObject(it.extraField)
                    extra != null && extraString(extra, "container_digest") == digest
                }
            ) {
                continue
            }
            val replacement = applyJavaScriptReplacements(source, group, options) ?: continue
            if (replacement == source) continue
            root = setAt(root, path, JsonPrimitive(replacement))
            changed = true
        }

        val pendingBlocks = ArrayList<PendingBlock>()
        for ((key, group) in blockGroups) pendingBlocks.add(PendingBlock(key, false, group))
        for ((key, group) in jsBlockGroups) pendingBlocks.add(PendingBlock(key, true, group))
        // Descending replacements keep every earlier block's original indices valid even when a
        // later translation changes the command count.
        for (pending in pendingBlocks.sortedByDescending { it.key.start }) {
            val block = blockText(root, pending.key) ?: continue
            if (pending.javascript) {
                val blockDigest = containerSha256Hex(block.text)
                if (!pending.group.all {
                        val extra = parseJsonObject(it.extraField)
                        extra != null && extraString(extra, "block_digest") == blockDigest
                    }
                ) {
                    continue
                }
                val replacement = applyJavaScriptReplacements(block.text, pending.group, options) ?: continue
                if (replacement == block.text) continue
                root = replaceBlock(root, pending.key, replacement) ?: continue
                changed = true
            } else {
                val matching = pending.group.filter {
                    val extra = parseJsonObject(it.extraField)
                    extra != null && extraString(extra, "digest") == containerSha256Hex(block.text)
                }
                val targets = matching.mapNotNull { effectiveTarget(it, options) }.distinct()
                if (targets.size != 1) continue
                val target = targets.single()
                val replacement = if (options.bilingual) block.text + "\n" + target else target
                if (replacement == block.text) continue
                root = replaceBlock(root, pending.key, replacement) ?: continue
                changed = true
            }
        }

        if (!changed) return original
        return try {
            encodeUtf8(root.toString())
        } catch (_: FormatContainerException) {
            null
        }
    }

    private fun writeJsFile(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray? {
        if (original.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) return null
        val text = decodeUtf8OrNull(original) ?: return null
        val replacements = ArrayList<TextReplacement>()
        for (unit in units) {
            val extra = parseJsonObject(unit.extraField) ?: continue
            if (extraString(extra, "kind") != "js") continue
            val start = extraInt(extra, "start") ?: continue
            val end = extraInt(extra, "end") ?: continue
            if (start < 0 || end <= start || end > text.length) continue
            val raw = text.substring(start, end)
            if (extraString(extra, "digest") != containerSha256Hex(raw)) continue
            val target = effectiveTarget(unit, options) ?: continue
            val quote = extraString(extra, "quote")?.singleOrNull() ?: continue
            val template = (extra["template"] as? JsonPrimitive)?.booleanOrNull ?: false
            val value = if (options.bilingual) unit.source + "\n" + target else target
            val encoded = encodeJavaScriptLiteral(value, quote, template)
            if (encoded == raw) continue
            replacements.add(TextReplacement(start, end, encoded))
        }
        if (replacements.isEmpty()) return original
        val output = applyReplacements(text, replacements)
        return encodeUtf8(output)
    }

    /** Shared literal replacement inside a JavaScript field or event-script block. */
    private fun applyJavaScriptReplacements(
        source: String,
        units: List<FormatUnit>,
        options: FormatWriteOptions,
    ): String? {
        val replacements = ArrayList<TextReplacement>()
        for (unit in units) {
            val extra = parseJsonObject(unit.extraField) ?: continue
            val start = extraInt(extra, "start") ?: continue
            val end = extraInt(extra, "end") ?: continue
            if (start < 0 || end <= start || end > source.length) continue
            val raw = source.substring(start, end)
            if (extraString(extra, "digest") != containerSha256Hex(raw)) continue
            val target = effectiveTarget(unit, options) ?: continue
            val quote = extraString(extra, "quote")?.singleOrNull() ?: continue
            val template = (extra["template"] as? JsonPrimitive)?.booleanOrNull ?: false
            val value = if (options.bilingual) unit.source + "\n" + target else target
            replacements.add(TextReplacement(start, end, encodeJavaScriptLiteral(value, quote, template)))
        }
        if (replacements.isEmpty()) return null
        return applyReplacements(source, replacements)
    }

    private fun applyReplacements(source: String, replacements: List<TextReplacement>): String {
        val sorted = replacements.sortedBy { it.start }
        val accepted = ArrayList<TextReplacement>(sorted.size)
        var cursor = 0
        for (replacement in sorted) {
            if (replacement.start < cursor) continue
            accepted.add(replacement)
            cursor = replacement.end
        }
        if (accepted.isEmpty()) return source
        val builder = StringBuilder(source)
        for (replacement in accepted.asReversed()) {
            builder.replace(replacement.start, replacement.end, replacement.text)
        }
        return builder.toString()
    }

    private fun parseBlockKey(extra: JsonObject, startKey: String, endKey: String): BlockKey? {
        val path = parseJsonPath(extra["path"]) ?: return null
        val start = extraInt(extra, startKey) ?: return null
        val end = extraInt(extra, endKey) ?: return null
        val code = extraInt(extra, "code") ?: return null
        if (start < 0 || end < start) return null
        return BlockKey(path, start, end, code)
    }

    private fun blockText(root: JsonElement, key: BlockKey): BlockText? {
        val list = readAt(root, key.path) as? JsonArray ?: return null
        if (key.start > key.end || key.end > list.size) return null
        val hasHeader = key.code == 401 || key.code == 405
        val headerCode = when (key.code) {
            401 -> 101
            405 -> 105
            else -> key.code
        }
        val headerIndex = if (hasHeader) key.start - 1 else key.start
        val header = list.getOrNull(headerIndex) as? JsonObject ?: return null
        if (intValue(header, "code") != headerCode) return null
        val indent = intValue(header, "indent") ?: return null
        val continuation = when (key.code) {
            401 -> 401
            405 -> 405
            408 -> 408
            655 -> 655
            else -> return null
        }
        val output = StringBuilder()
        for (index in key.start until key.end) {
            val command = list.getOrNull(index) as? JsonObject ?: return null
            val expectedCode = if (index == key.start) key.code else continuation
            if (intValue(command, "code") != expectedCode) return null
            if (intValue(command, "indent") != indent) return null
            val parameter = (command["parameters"] as? JsonArray)?.getOrNull(0) ?: return null
            val value = (parameter as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (index > key.start) output.append('\n')
            output.append(value)
        }
        if (!hasHeader && output.isEmpty()) return null
        val next = list.getOrNull(key.end) as? JsonObject
        if (next != null && intValue(next, "code") == continuation && intValue(next, "indent") == indent) {
            return null
        }
        return BlockText(output.toString(), indent)
    }

    private fun replaceBlock(root: JsonElement, key: BlockKey, text: String): JsonElement? {
        val list = readAt(root, key.path) as? JsonArray ?: return null
        if (key.start > key.end || key.end > list.size) return null
        val block = blockText(root, key) ?: return null
        val continuation = when (key.code) {
            401 -> 401
            405 -> 405
            408 -> 408
            655 -> 655
            else -> return null
        }
        val lines = splitLines(text)
        val commands = ArrayList<JsonElement>(lines.size)
        for ((index, line) in lines.withIndex()) {
            commands.add(buildJsonObject {
                put("code", if (index == 0) key.code else continuation)
                put("indent", block.indent)
                put("parameters", buildJsonArray { add(JsonPrimitive(line)) })
            })
        }
        val replacement = buildJsonArray {
            for (index in 0 until key.start) add(list[index])
            for (command in commands) add(command)
            for (index in key.end until list.size) add(list[index])
        }
        return setAt(root, key.path, replacement)
    }

    private fun readAt(root: JsonElement, path: List<Any>): JsonElement? {
        var current = root
        for (key in path) {
            current = when (key) {
                is Int -> (current as? JsonArray)?.getOrNull(key) ?: return null
                is String -> (current as? JsonObject)?.get(key) ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun setAt(root: JsonElement, path: List<Any>, value: JsonElement, depth: Int = 0): JsonElement {
        if (depth == path.size) return value
        return when (val key = path[depth]) {
            is Int -> {
                val array = root as? JsonArray ?: return root
                if (key !in array.indices) return root
                JsonArray(array.mapIndexed { index, element ->
                    if (index == key) setAt(element, path, value, depth + 1) else element
                })
            }
            is String -> {
                val obj = root as? JsonObject ?: return root
                if (!obj.containsKey(key)) return root
                JsonObject(obj.toMutableMap().apply {
                    put(key, setAt(obj.getValue(key), path, value, depth + 1))
                })
            }
            else -> root
        }
    }

    private fun intValue(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    private fun splitLines(value: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\r' || character == '\n') {
                result.add(value.substring(start, index))
                if (character == '\r' && index + 1 < value.length && value[index + 1] == '\n') index += 1
                start = index + 1
            }
            index += 1
        }
        result.add(value.substring(start))
        return result
    }

    private data class BlockKey(val path: List<Any>, val start: Int, val end: Int, val code: Int)
    private class BlockText(val text: String, val indent: Int)
    private class PendingBlock(val key: BlockKey, val javascript: Boolean, val group: List<FormatUnit>)
    private class TextReplacement(val start: Int, val end: Int, val text: String)
}

/** Bounded unit accumulator shared by all RPG Maker extraction paths. */
private class UnitCollector {
    val units = ArrayList<FormatUnit>()
    val warnings = ArrayList<FormatWarning>()
    var limited = false
    private var sizeWarning = false

    fun add(tag: String, source: String, status: TextItemStatus, extra: String): Boolean {
        if (source.isEmpty()) return false
        if ('\u0000' in source || !isValidUtf8Text(source)) {
            if (!sizeWarning) {
                warnings.add(FormatWarning("rpgmaker.invalid_text", safeWarningPath(tag),
                    null, "A text value is not safe UTF-8 text"))
                sizeWarning = true
            }
            return false
        }
        if (source.length > ContainerLimits.MAX_UNIT_SOURCE_CHARS) {
            if (!sizeWarning) {
                warnings.add(FormatWarning("rpgmaker.text_too_large", safeWarningPath(tag),
                    null, "A text value exceeds the unit bound"))
                sizeWarning = true
            }
            return false
        }
        if (extra.length > 65_536) {
            if (!sizeWarning) {
                warnings.add(FormatWarning("rpgmaker.extra_too_large", safeWarningPath(tag),
                    null, "A unit locator exceeds the metadata bound"))
                sizeWarning = true
            }
            return false
        }
        if (units.size >= ContainerLimits.MAX_UNITS) {
            limited = true
            return false
        }
        units.add(FormatUnit(units.size, source, "", status, tag, extraField = extra))
        return true
    }
}

/** Extracts JSON database fields and RPG Maker event command blocks with persistent paths. */
private class JsonWalker(
    private val tag: String,
    private val fileBase: String,
    private val collector: UnitCollector,
) {
    fun walk(root: JsonElement) {
        walk(root, emptyList(), false)
    }

    private fun walk(element: JsonElement, path: List<Any>, defaultText: Boolean) {
        if (collector.limited || path.size > ContainerLimits.MAX_JSON_PATH) return
        when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return
                val text = element.content
                if (text.isEmpty()) return
                collector.add(tag, text, if (defaultText) TextItemStatus.NONE else TextItemStatus.RULE_SKIPPED,
                    fieldExtra(path, containerSha256Hex(text)))
            }
            is JsonArray -> {
                if (path.lastOrNull() == "list" && element.isNotEmpty() && element.all { isCommand(it) }) {
                    walkCommands(element, path)
                } else {
                    for ((index, child) in element.withIndex()) {
                        if (collector.limited) return
                        walk(child, path + index, defaultText)
                    }
                }
            }
            is JsonObject -> {
                for ((key, child) in element) {
                    if (collector.limited) return
                    if ('\u0000' in key || !isValidUtf8Text(key)) continue
                    walk(child, path + key, defaultText || isDefaultField(path, key))
                }
            }
            else -> Unit
        }
    }

    private fun walkCommands(list: JsonArray, listPath: List<Any>) {
        var index = 0
        while (index < list.size && !collector.limited) {
            val command = list[index] as? JsonObject
            if (command == null) {
                index += 1
                continue
            }
            val code = intValue(command, "code") ?: 0
            val indent = intValue(command, "indent") ?: 0
            val parameters = command["parameters"] as? JsonArray
            val continuation = BLOCK_CONTINUATIONS[code]
            if (continuation != null && parameters != null) {
                val first = if (code == 108 || code == 355) index else index + 1
                var end = index + 1
                while (end < list.size) {
                    val next = list[end] as? JsonObject ?: break
                    if (intValue(next, "code") != continuation || intValue(next, "indent") != indent) break
                    end += 1
                }
                val text = buildString {
                    for (line in first until end) {
                        if (line > first) append('\n')
                        append(parameterZero(list[line]))
                    }
                }
                if (text.isNotEmpty()) {
                    when (code) {
                        101, 105 -> collector.add(tag, text, TextItemStatus.NONE,
                            blockExtra(listPath, first, end, continuation, containerSha256Hex(text)))
                        108 -> collector.add(tag, text, TextItemStatus.RULE_SKIPPED,
                            blockExtra(listPath, first, end, 108, containerSha256Hex(text)))
                        355 -> addScriptBlockUnits(text, listPath, first, end)
                    }
                }
                if (code == 101 || code == 105) {
                    parameters.forEachIndexed { slot, value ->
                        walk(value, listPath + index + "parameters" + slot, false)
                    }
                }
                index = end
                continue
            }
            if (code == 102 && parameters != null) {
                val choices = parameters.getOrNull(0) as? JsonArray
                if (choices != null) {
                    for ((choiceIndex, choice) in choices.withIndex()) {
                        if (choiceIndex >= MAX_CHOICES) break
                        val value = choice as? JsonPrimitive ?: continue
                        if (!value.isString || value.content.isEmpty()) continue
                        val mirrors = choiceMirrors(list, listPath, index, indent, choiceIndex)
                        collector.add(tag, value.content, TextItemStatus.NONE,
                            fieldExtra(listPath + index + "parameters" + 0 + choiceIndex,
                                containerSha256Hex(value.content), mirrors))
                    }
                }
                parameters.forEachIndexed { slot, value ->
                    if (slot != 0) walk(value, listPath + index + "parameters" + slot, false)
                }
                index += 1
                continue
            }
            if (code == 402) {
                index += 1
                continue
            }
            val scriptSlot = when {
                code == 111 && intValue(parameters, 0) == 12 -> 1
                code == 122 && intValue(parameters, 3) == 4 -> 4
                code == 45 -> 0
                else -> null
            }
            if (parameters != null) {
                parameters.forEachIndexed { slot, value ->
                    if (slot == scriptSlot && value is JsonPrimitive && value.isString && value.content.isNotEmpty()) {
                        addScriptFieldUnits(value.content, listPath + index + "parameters" + slot)
                    } else {
                        walk(value, listPath + index + "parameters" + slot,
                            code in ACTOR_TEXT_CODES && slot == 1)
                    }
                }
            }
            index += 1
        }
    }

    private fun addScriptBlockUnits(text: String, listPath: List<Any>, start: Int, end: Int) {
        val result = JavaScriptStrings.scan(text)
        for (literal in result.literals) {
            collector.add(tag, literal.value, TextItemStatus.RULE_SKIPPED,
                jsBlockExtra(listPath, start, end, text, literal))
        }
    }

    private fun addScriptFieldUnits(text: String, path: List<Any>) {
        val result = JavaScriptStrings.scan(text)
        for (literal in result.literals) {
            collector.add(tag, literal.value, TextItemStatus.RULE_SKIPPED,
                jsFieldExtra(path, text, literal))
        }
    }

    private fun choiceMirrors(
        list: JsonArray,
        listPath: List<Any>,
        index: Int,
        indent: Int,
        choiceIndex: Int,
    ): List<List<Any>> {
        val mirrors = ArrayList<List<Any>>()
        var next = index + 1
        while (next < list.size && next - index <= MAX_CHOICE_SCAN) {
            val branch = list[next] as? JsonObject ?: break
            val branchIndent = intValue(branch, "indent") ?: break
            if (branchIndent < indent || (branchIndent == indent && intValue(branch, "code") == 404)) break
            if (branchIndent == indent && intValue(branch, "code") == 402 &&
                intValue(branch["parameters"] as? JsonArray, 0) == choiceIndex
            ) {
                mirrors.add(listPath + next + "parameters" + 1)
                if (mirrors.size >= MAX_MIRRORS) break
            }
            next += 1
        }
        return mirrors
    }

    private fun intValue(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    private fun intValue(array: JsonArray?, index: Int): Int? =
        (array?.getOrNull(index) as? JsonPrimitive)?.intOrNull

    private fun parameterZero(element: JsonElement): String {
        val command = element as? JsonObject ?: return ""
        val parameter = (command["parameters"] as? JsonArray)?.getOrNull(0) as? JsonPrimitive ?: return ""
        return if (parameter.isString) parameter.content else ""
    }

    private fun isCommand(element: JsonElement): Boolean {
        val command = element as? JsonObject ?: return false
        if (intValue(command, "code") == null) return false
        return command["parameters"] is JsonArray
    }

    private fun isDefaultField(path: List<Any>, key: String): Boolean {
        if (path.size == 1 && path[0] is Int && DEFAULT_TEXT_FIELDS[fileBase]?.contains(key) == true) {
            return true
        }
        if (path.isNotEmpty()) return false
        if (fileBase == "System" && key in SYSTEM_TEXT_FIELDS) return true
        return isMapFile(fileBase) && key == "displayName"
    }

    companion object {
        private const val MAX_CHOICES = 1_000
        private const val MAX_CHOICE_SCAN = 10_000
        private const val MAX_MIRRORS = 256
        private val BLOCK_CONTINUATIONS = mapOf(101 to 401, 105 to 405, 108 to 408, 355 to 655)
        private val ACTOR_TEXT_CODES = setOf(320, 324, 325)
        private val DEFAULT_TEXT_FIELDS = mapOf(
            "Actors" to setOf("name", "nickname", "profile"),
            "Classes" to setOf("name"),
            "Skills" to setOf("name", "description", "message1", "message2"),
            "Items" to setOf("name", "description"),
            "Weapons" to setOf("name", "description"),
            "Armors" to setOf("name", "description"),
            "Enemies" to setOf("name"),
            "States" to setOf("name", "message1", "message2", "message3", "message4"),
        )
        private val SYSTEM_TEXT_FIELDS = setOf(
            "gameTitle", "currencyUnit", "elements", "skillTypes", "weaponTypes",
            "armorTypes", "equipTypes", "terms",
        )
        private fun isMapFile(fileBase: String): Boolean =
            fileBase.startsWith("Map") && fileBase.length > 3 && fileBase.drop(3).all { it.isDigit() }
    }
}

private fun fieldExtra(path: List<Any>, digest: String, mirrors: List<List<Any>> = emptyList()): String =
    buildJsonObject {
        put("v", 1)
        put("kind", "field")
        put("path", jsonPathArray(path))
        put("digest", digest)
        if (mirrors.isNotEmpty()) {
            put("mirrors", buildJsonArray { for (mirror in mirrors) add(jsonPathArray(mirror)) })
        }
    }.toString()

private fun blockExtra(path: List<Any>, start: Int, end: Int, code: Int, digest: String): String =
    buildJsonObject {
        put("v", 1)
        put("kind", "block")
        put("path", jsonPathArray(path))
        put("start", start)
        put("end", end)
        put("code", code)
        put("digest", digest)
    }.toString()

private fun jsFileExtra(literal: JavaScriptStrings.Literal): String =
    buildJsonObject {
        put("v", 1)
        put("kind", "js")
        put("start", literal.start)
        put("end", literal.end)
        put("quote", literal.quote.toString())
        put("template", literal.template)
        put("digest", containerSha256Hex(literal.raw))
    }.toString()

private fun jsFieldExtra(path: List<Any>, container: String, literal: JavaScriptStrings.Literal): String =
    buildJsonObject {
        put("v", 1)
        put("kind", "jsfield")
        put("path", jsonPathArray(path))
        put("start", literal.start)
        put("end", literal.end)
        put("quote", literal.quote.toString())
        put("template", literal.template)
        put("digest", containerSha256Hex(literal.raw))
        put("container_digest", containerSha256Hex(container))
    }.toString()

private fun jsBlockExtra(
    path: List<Any>,
    start: Int,
    end: Int,
    block: String,
    literal: JavaScriptStrings.Literal,
): String = buildJsonObject {
    put("v", 1)
    put("kind", "jsblock")
    put("path", jsonPathArray(path))
    put("block_start", start)
    put("block_end", end)
    put("code", 355)
    put("block_digest", containerSha256Hex(block))
    put("start", literal.start)
    put("end", literal.end)
    put("quote", literal.quote.toString())
    put("template", literal.template)
    put("digest", containerSha256Hex(literal.raw))
}.toString()

private fun safeWarningPath(value: String): String =
    if (value.length <= 4_096 && value.none(Char::isISOControl)) value else ""

/** Bounds parser recursion before kotlinx JSON builds a deeply nested tree. */
private fun jsonNestingTooDeep(value: String): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (character in value) {
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                depth += 1
                if (depth > 64) return true
            }
            '}', ']' -> if (depth > 0) depth -= 1
        }
    }
    return false
}

/**
 * Portable JavaScript string scanner. It is deliberately not a full parser: comments, regex
 * literals and simple quoted/template literals are recognized, but interpolation templates are
 * skipped and regex/division disambiguation is heuristic. Every writable literal keeps its exact
 * UTF-16 range so the rest of the script stays untouched.
 */
private object JavaScriptStrings {
    class Literal(
        val start: Int,
        val end: Int,
        val template: Boolean,
        val quote: Char,
        val value: String,
        val raw: String,
    )

    class Result(val literals: List<Literal>, val error: Boolean)

    fun scan(source: String): Result {
        val literals = ArrayList<Literal>()
        var index = 0
        var lastSignificant = '\u0000'
        var lastWord = ""
        while (index < source.length) {
            val character = source[index]
            when {
                character.isWhitespace() -> index += 1
                character == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    val end = indexOfLineEnd(source, index + 2)
                    index = if (end < 0) source.length else end
                }
                character == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    val end = source.indexOf("*/", index + 2)
                    if (end < 0) return Result(literals, true)
                    index = end + 2
                }
                character == '\'' || character == '"' -> {
                    val literal = readQuoted(source, index, character) ?: return Result(literals, true)
                    if ('\u0000' !in literal.value) {
                        literals.add(literal)
                        if (literals.size >= ContainerLimits.MAX_UNITS) return Result(literals, false)
                    }
                    index = literal.end
                    lastSignificant = character
                    lastWord = ""
                }
                character == '`' -> {
                    val literal = readTemplate(source, index)
                    if (literal == null) {
                        val next = skipTemplate(source, index)
                        if (next < 0) return Result(literals, true)
                        index = next
                    } else {
                        if ('\u0000' !in literal.value) {
                            literals.add(literal)
                            if (literals.size >= ContainerLimits.MAX_UNITS) return Result(literals, false)
                        }
                        index = literal.end
                    }
                    lastSignificant = '`'
                    lastWord = ""
                }
                character == '/' -> {
                    if (canStartRegularExpression(lastSignificant, lastWord)) {
                        val end = skipRegularExpression(source, index)
                        if (end < 0) {
                            index += 1
                        } else {
                            index = end
                        }
                    } else {
                        index += 1
                    }
                    lastSignificant = '/'
                    lastWord = ""
                }
                character.isLetterOrDigit() || character == '_' || character == '$' -> {
                    val start = index
                    while (index < source.length &&
                        (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')
                    ) {
                        index += 1
                    }
                    lastWord = source.substring(start, index)
                    lastSignificant = source[index - 1]
                }
                else -> {
                    lastSignificant = character
                    lastWord = ""
                    index += 1
                }
            }
        }
        return Result(literals, false)
    }

    private fun indexOfLineEnd(source: String, start: Int): Int {
        var index = start
        while (index < source.length && source[index] != '\r' && source[index] != '\n') index += 1
        return if (index >= source.length) -1 else index
    }

    private fun canStartRegularExpression(previous: Char, previousWord: String): Boolean {
        if (previousWord in REGEX_PRECEDING_WORDS) return true
        if (previous == '\u0000') return true
        return previous in "([{,;:=!&|?+-*%^~<>"
    }

    private fun readQuoted(source: String, start: Int, quote: Char): Literal? {
        val value = StringBuilder()
        var index = start + 1
        while (index < source.length) {
            val character = source[index]
            when {
                character == quote -> {
                    val end = index + 1
                    return Literal(start, end, false, quote, value.toString(), source.substring(start, end))
                }
                character == '\\' -> {
                    val escape = decodeEscape(source, index) ?: return null
                    value.append(escape.first)
                    index = escape.second
                }
                character == '\r' || character == '\n' -> return null
                else -> {
                    value.append(character)
                    index += 1
                }
            }
        }
        return null
    }

    private fun readTemplate(source: String, start: Int): Literal? {
        val value = StringBuilder()
        var index = start + 1
        while (index < source.length) {
            val character = source[index]
            when {
                character == '`' -> {
                    val end = index + 1
                    return Literal(start, end, true, '`', value.toString(), source.substring(start, end))
                }
                character == '\\' -> {
                    val escape = decodeEscape(source, index) ?: return null
                    value.append(escape.first)
                    index = escape.second
                }
                character == '$' && index + 1 < source.length && source[index + 1] == '{' -> return null
                else -> {
                    value.append(character)
                    index += 1
                }
            }
        }
        return null
    }

    /** Skip an interpolation template without extracting its fragments. */
    private fun skipTemplate(source: String, start: Int): Int {
        var index = start + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '`' -> return index + 1
                else -> index += 1
            }
        }
        return -1
    }

    private fun skipRegularExpression(source: String, start: Int): Int {
        var index = start + 1
        var inClass = false
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '[' -> {
                    inClass = true
                    index += 1
                }
                ']' -> {
                    inClass = false
                    index += 1
                }
                '/' -> {
                    if (!inClass) {
                        index += 1
                        while (index < source.length && (source[index].isLetter() || source[index] == '_')) {
                            index += 1
                        }
                        return index
                    }
                    index += 1
                }
                '\r', '\n' -> return -1
                else -> index += 1
            }
        }
        return -1
    }

    private fun decodeEscape(source: String, start: Int): Pair<CharSequence, Int>? {
        if (start + 1 >= source.length) return null
        val code = source[start + 1]
        val simple = when (code) {
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'b' -> "\b"
            'f' -> "\u000c"
            'v' -> "\u000b"
            '0' -> "\u0000"
            '\\' -> "\\"
            '\'' -> "'"
            '"' -> "\""
            '`' -> "`"
            '$' -> "\$"
            '/' -> "/"
            '\r' -> {
                val next = if (start + 2 < source.length && source[start + 2] == '\n') start + 3 else start + 2
                return "" to next
            }
            '\n' -> return "" to start + 2
            else -> null
        }
        if (simple != null) return simple to start + 2
        if (code == 'x') {
            if (start + 3 >= source.length) return null
            val value = source.substring(start + 2, start + 4).toIntOrNull(16) ?: return null
            return value.toChar().toString() to start + 4
        }
        if (code == 'u') {
            if (start + 2 < source.length && source[start + 2] == '{') {
                val close = source.indexOf('}', start + 3)
                if (close < 0) return null
                val value = source.substring(start + 3, close).toIntOrNull(16) ?: return null
                if (!isValidXmlCodePoint(value)) return null
                return String(Character.toChars(value)) to close + 1
            }
            if (start + 5 >= source.length) return null
            val value = source.substring(start + 2, start + 6).toIntOrNull(16) ?: return null
            return value.toChar().toString() to start + 6
        }
        if (code in '1'..'7') {
            var index = start + 1
            var value = 0
            var digits = 0
            while (index < source.length && digits < 3 && source[index] in '0'..'7') {
                value = value * 8 + (source[index] - '0')
                index += 1
                digits += 1
            }
            return value.toChar().toString() to index
        }
        return code.toString() to start + 2
    }

    private val REGEX_PRECEDING_WORDS = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
        "throw", "case", "do", "else", "yield", "await",
    )
}

private fun encodeJavaScriptLiteral(value: String, quote: Char, template: Boolean): String {
    val output = StringBuilder(value.length + 2)
    output.append(if (template) '`' else quote)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val width = Character.charCount(codePoint)
        when {
            codePoint == '\\'.code -> output.append("\\\\")
            !template && codePoint == quote.code -> {
                output.append('\\')
                output.append(quote)
            }
            template && codePoint == '`'.code -> output.append("\\`")
            template && codePoint == '$'.code && index + width < value.length &&
                value.codePointAt(index + width) == '{'.code -> output.append("\\\$")
            codePoint == '\n'.code -> output.append("\\n")
            codePoint == '\r'.code -> output.append("\\r")
            codePoint == '\t'.code -> output.append("\\t")
            codePoint == '\b'.code -> output.append("\\b")
            codePoint == 0x0c -> output.append("\\f")
            codePoint == 0x2028 -> output.append("\\u2028")
            codePoint == 0x2029 -> output.append("\\u2029")
            codePoint < 0x20 -> output.append("\\u").append(codePoint.toString(16).padStart(4, '0'))
            else -> output.appendCodePoint(codePoint)
        }
        index += width
    }
    output.append(if (template) '`' else quote)
    return output.toString()
}
