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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InvalidVttFormat(val reason: Reason, val line: Int? = null) :
    IllegalArgumentException("Invalid WebVTT: $reason${line?.let { " at line $it" } ?: ""}") {
    enum class Reason { STRUCTURE, TEXT, LIMIT, BINDING, STALE }
}

/**
 * WebVTT adapter ported from LG's cue/tag model. Cue identifiers, timing lines, settings,
 * NOTE/STYLE/REGION blocks and all inter-cue bytes are kept literally. Inline `b/i/u/c/v/lang/
 * ruby/rt` tags are parsed into a tree, translated text slots are bound by their raw source
 * offset recorded in `extraField` (a unique row/source pair is used when the locator is absent)
 * and rebuilt cues preserve each tag's original open/close spelling. Inline cue timestamps are
 * validated and removed from rebuilt text (LG behavior).
 *
 * Untouched cues are copied as raw source slices, so reading and writing without a completed
 * translation is byte-exact even when a cue uses entities or omitted `v` closing tags.
 * Bilingual output uses [FormatWriteOptions.bilingual] and [FormatWriteOptions.deduplicateSame];
 * omitted closing tags are made explicit at the source/target join.
 */
class VttFormatAdapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.VTT

    override fun read(text: String, path: RelativePath): FormatDocument {
        requireVttInput(text)
        val document = parseVttDocument(text)
        val units = ArrayList<FormatUnit>()
        for (slot in document.slots) {
            if (slot.text.isBlank()) continue
            if (units.size >= MAX_UNITS) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT, slot.row)
            if (slot.text.length > MAX_UNIT_CHARS) {
                throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT, slot.row)
            }
            units.add(FormatUnit(
                row = slot.row,
                source = slot.text,
                extraField = slotExtra(slot.offset),
            ))
        }
        return FormatDocument(format = format, units = units)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        requireVttInput(original)
        if (units.size > MAX_UNITS) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        val document = parseVttDocument(original)
        val slots = HashMap<Int, VttSlot>(document.slots.size)
        for (slot in document.slots) slots[slot.offset] = slot
        val byRowAndSource = HashMap<Pair<Int, String>, MutableList<VttSlot>>()
        for (slot in document.slots) {
            byRowAndSource.getOrPut(slot.row to slot.text) { ArrayList(1) }.add(slot)
        }
        val targets = HashMap<Int, String>()
        val seen = HashSet<Int>(units.size)
        for (unit in units) {
            if (unit.row < 1) throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
            val recordedOffset = vttOffset(unit)
            val slot = if (recordedOffset != null) {
                slots[recordedOffset] ?: throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
            } else {
                // A unit without the optional locator may still bind by its stable row and
                // source, but only when that pair identifies exactly one text slot.
                val matches = byRowAndSource[unit.row to unit.source]
                    ?: throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
                if (matches.size != 1) throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
                matches[0]
            }
            if (!seen.add(slot.offset)) throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
            if (slot.text != unit.source || slot.row != unit.row) {
                throw InvalidVttFormat(InvalidVttFormat.Reason.STALE, unit.row)
            }
            if (unit.status == TextItemStatus.PROCESSED) {
                val target = normalizeVttLineBreaks(unit.translation)
                if ("-->" in target) throw InvalidVttFormat(InvalidVttFormat.Reason.TEXT, unit.row)
                targets[offset] = target
            }
        }
        val defaultEnding = document.lines.firstOrNull { it.ending.isNotEmpty() }?.ending ?: "\n"
        val duplicateSame = options.bilingual && !options.deduplicateSame
        val output = StringBuilder(original.length)
        var cursor = 0
        for (cue in document.cues) {
            if (cue.start < cursor) throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, cue.row)
            appendVtt(output, original, cursor, cue.start)
            val changed = vttCueChanged(cue.nodes, targets)
            if (!changed && !duplicateSame) {
                appendVtt(output, original, cue.start, cue.end)
            } else {
                val ending = cue.ending.ifEmpty { defaultEnding }
                val bodies = renderVttNodes(cue.nodes, targets, ending, false, options.targetLanguage)
                val sourceText = bodies.joinToString("") { it.sourceText }
                val targetText = bodies.joinToString("") { it.targetText }
                val sourceBody = bodies.joinToString("") { it.source }
                val targetBody = bodies.joinToString("") { it.target }
                val keepTarget = sourceText.trim().isEmpty() || targetText.trim().isNotEmpty()
                if (options.bilingual) {
                    val combine = targetText.trim().isNotEmpty() &&
                        !(options.deduplicateSame && sourceText == targetText)
                    val body = if (combine) {
                        val closed = renderVttNodes(cue.nodes, targets, ending, true, options.targetLanguage)
                        closed.joinToString("") { it.source } + ending +
                            closed.joinToString("") { it.target }
                    } else {
                        sourceBody
                    }
                    requireVttBody(body, cue.row)
                    appendVtt(output, cue.header)
                    appendVtt(output, body)
                    appendVtt(output, cue.ending)
                } else {
                    requireVttBody(targetBody, cue.row)
                    if (keepTarget) {
                        appendVtt(output, cue.header)
                        appendVtt(output, targetBody)
                        appendVtt(output, cue.ending)
                    }
                }
            }
            cursor = cue.end
        }
        appendVtt(output, original, cursor, original.length)
        if (output.length > MAX_OUTPUT_CHARS) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        val text = output.toString()
        if (vttBytes(text).size > MAX_OUTPUT_BYTES) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        return text
    }

    private fun slotExtra(offset: Int): String = buildJsonObject {
        put("subtitle", buildJsonObject { put("offset", offset) })
    }.toString()

    private fun vttOffset(unit: FormatUnit): Int? {
        val root = try {
            JsonObjectValue.parse(unit.extraField, JsonInputLimits(MAX_EXTRA_CHARS, 8)).json
        } catch (_: IllegalArgumentException) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
        }
        val subtitle = root["subtitle"] as? JsonObject
        val value = subtitle?.get("offset") ?: root["offset"] ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
        if (primitive.isString) throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
        val offset = primitive.content.toIntOrNull()
            ?: throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
        if (offset < 0) throw InvalidVttFormat(InvalidVttFormat.Reason.BINDING, unit.row)
        return offset
    }

    private fun appendVtt(output: StringBuilder, value: String) {
        if (output.length.toLong() + value.length > MAX_OUTPUT_CHARS) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        }
        output.append(value)
    }

    private fun appendVtt(output: StringBuilder, text: String, start: Int, end: Int) {
        if (end < start) throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE)
        if (output.length.toLong() + (end - start) > MAX_OUTPUT_CHARS) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        }
        output.append(text, start, end)
    }
}

private const val MAX_INPUT_CHARS = 8 * 1024 * 1024
private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_OUTPUT_CHARS = 24 * 1024 * 1024
private const val MAX_OUTPUT_BYTES = 24 * 1024 * 1024
private const val MAX_UNITS = 100_000
private const val MAX_CUES = 100_000
private const val MAX_EXTRA_CHARS = 65_536
private const val MAX_UNIT_CHARS = 65_536
private const val MAX_LINES = 400_000
private const val MAX_TAG_DEPTH = 256

private fun requireVttInput(text: String) {
    if ('\u0000' in text) throw InvalidVttFormat(InvalidVttFormat.Reason.TEXT)
    if (text.length > MAX_INPUT_CHARS) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
    if (vttBytes(text).size > MAX_INPUT_BYTES) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
}

private fun vttBytes(text: String): ByteArray = try {
    TextProjectFingerprint.utf8(text)
} catch (_: TextProjectException) {
    throw InvalidVttFormat(InvalidVttFormat.Reason.TEXT)
}

private class VttLine(val start: Int, val row: Int, val text: String, val ending: String)

private sealed class VttNode

private class VttTextNode(val offset: Int, val text: String) : VttNode()

private class VttTagNode(
    val name: String,
    val open: String,
    var close: String,
    val children: MutableList<VttNode>,
) : VttNode()

private class VttSlot(val offset: Int, val row: Int, val text: String)

private class VttCue(
    val start: Int,
    val end: Int,
    val row: Int,
    val header: String,
    val ending: String,
    val nodes: List<VttNode>,
)

private class VttTiming(val startMillis: Long, val endMillis: Long)

private class VttDocument(val lines: List<VttLine>, val cues: List<VttCue>, val slots: List<VttSlot>)

private class VttTagToken(
    val closing: Boolean,
    val name: String,
    val classes: String,
    val annotation: String?,
)

private class VttBody(
    val source: String,
    val target: String,
    val sourceText: String,
    val targetText: String,
    val changed: Boolean,
)

private fun readVttLines(text: String): List<VttLine> {
    val lines = ArrayList<VttLine>()
    var start = 0
    while (start < text.length) {
        if (lines.size >= MAX_LINES) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT)
        var end = start
        while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
        val ending = when {
            end == text.length -> ""
            text[end] == '\r' && text.getOrNull(end + 1) == '\n' -> "\r\n"
            else -> text[end].toString()
        }
        lines.add(VttLine(start, lines.size + 1, text.substring(start, end), ending))
        start = end + ending.length
    }
    return lines
}

private fun parseVttDocument(text: String): VttDocument {
    val lines = readVttLines(text)
    val first = lines.firstOrNull() ?: throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, 1)
    val header = if (first.text.startsWith('\uFEFF')) first.text.substring(1) else first.text
    if ("-->" in header) throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, 1)
    if (header != "WEBVTT" && !header.startsWith("WEBVTT ") && !header.startsWith("WEBVTT\t")) {
        throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, 1)
    }
    var index = 1
    while (index < lines.size && lines[index].text.trim().isNotEmpty()) {
        if ("-->" in lines[index].text) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, lines[index].row)
        }
        index++
    }
    val cues = ArrayList<VttCue>()
    val slots = ArrayList<VttSlot>()
    while (index < lines.size) {
        if (lines[index].text.trim().isEmpty()) {
            index++
            continue
        }
        val blockStart = index
        while (index < lines.size && lines[index].text.trim().isNotEmpty()) index++
        val blockSize = index - blockStart
        val firstLine = lines[blockStart]
        if (isVttNote(firstLine.text)) {
            for (line in blockStart until index) {
                if ("-->" in lines[line].text) {
                    throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, lines[line].row)
                }
            }
            continue
        }
        if (isVttStructureHeader(firstLine.text)) {
            if (cues.isNotEmpty()) {
                throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, firstLine.row)
            }
            for (line in blockStart until index) {
                if ("-->" in lines[line].text) {
                    throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, lines[line].row)
                }
            }
            continue
        }
        val timingIndex = if ("-->" in firstLine.text) 0 else 1
        if (timingIndex >= blockSize) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, firstLine.row)
        }
        val timingLine = lines[blockStart + timingIndex]
        parseVttTiming(timingLine.text)
            ?: throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, timingLine.row)
        val last = lines[blockStart + blockSize - 1]
        val payloadStart = timingLine.start + timingLine.text.length + timingLine.ending.length
        val payloadEnd = if (timingIndex + 1 == blockSize) {
            payloadStart
        } else {
            last.start + last.text.length
        }
        if (payloadEnd < payloadStart || payloadEnd > text.length) {
            throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, firstLine.row)
        }
        val nodes = parseVttPayload(text, payloadStart, payloadEnd, timingLine.row)
        collectVttSlots(nodes, slots, lines)
        if (cues.size >= MAX_CUES) throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT, firstLine.row)
        cues.add(VttCue(
            start = firstLine.start,
            end = last.start + last.text.length + last.ending.length,
            row = firstLine.row,
            header = text.substring(firstLine.start, payloadStart),
            ending = if (timingIndex + 1 == blockSize) "" else last.ending,
            nodes = nodes,
        ))
    }
    return VttDocument(lines, cues, slots)
}

private fun isVttNote(text: String): Boolean =
    text.startsWith("NOTE") && (text.length == 4 || text[4] == ' ' || text[4] == '\t')

private fun isVttStructureHeader(text: String): Boolean {
    val value = text.trimEnd(' ', '\t')
    return value == "STYLE" || value == "REGION"
}

private fun collectVttSlots(nodes: List<VttNode>, slots: MutableList<VttSlot>, lines: List<VttLine>) {
    for (node in nodes) {
        when (node) {
            is VttTextNode -> slots.add(VttSlot(node.offset, vttLineRow(lines, node.offset), node.text))
            is VttTagNode -> if (node.name != "rt") collectVttSlots(node.children, slots, lines)
        }
    }
}

private fun vttLineRow(lines: List<VttLine>, offset: Int): Int {
    var low = 0
    var high = lines.size - 1
    var row = 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].start <= offset) {
            row = lines[middle].row
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return row
}

private fun parseVttTiming(text: String): VttTiming? {
    val arrow = text.indexOf("-->")
    if (arrow < 0) return null
    if (text.indexOf("-->", arrow + 3) >= 0) return null
    var leftEnd = arrow
    while (leftEnd > 0 && (text[leftEnd - 1] == ' ' || text[leftEnd - 1] == '\t')) leftEnd--
    if (leftEnd == arrow) return null
    val startToken = text.substring(0, leftEnd)
    var rightStart = arrow + 3
    if (rightStart >= text.length) return null
    if (text[rightStart] != ' ' && text[rightStart] != '\t') return null
    while (rightStart < text.length && (text[rightStart] == ' ' || text[rightStart] == '\t')) rightStart++
    var rightEnd = rightStart
    while (rightEnd < text.length && text[rightEnd] != ' ' && text[rightEnd] != '\t') rightEnd++
    val endToken = text.substring(rightStart, rightEnd)
    val start = parseVttTimestamp(startToken) ?: return null
    val end = parseVttTimestamp(endToken) ?: return null
    if (end <= start) return null
    return VttTiming(start, end)
}

private fun timestampShape(value: String): Boolean {
    val dot = value.lastIndexOf('.')
    if (dot < 0 || value.length - dot - 1 != 3) return false
    val time = value.substring(0, dot)
    if (time.isEmpty()) return false
    val parts = time.split(':')
    if (parts.size !in 2..3) return false
    for (part in parts) {
        if (part.isEmpty() || part.any { !it.isDigit() }) return false
    }
    if (parts[parts.size - 1].length != 2) return false
    if (parts[parts.size - 2].length != 2) return false
    if (parts.size == 3 && parts[0].length < 2) return false
    return true
}

private fun parseVttTimestamp(value: String): Long? {
    if (!timestampShape(value)) return null
    val dot = value.lastIndexOf('.')
    val millis = value.substring(dot + 1).toIntOrNull() ?: return null
    val parts = value.substring(0, dot).split(':')
    val seconds = parts[parts.size - 1].toIntOrNull() ?: return null
    val minutes = parts[parts.size - 2].toIntOrNull() ?: return null
    if (seconds >= 60 || minutes >= 60) return null
    var hours = 0L
    if (parts.size == 3) {
        hours = parts[0].toLongOrNull() ?: return null
        if (hours > 10_000) return null
    }
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
}

private fun parseVttPayload(text: String, start: Int, end: Int, row: Int): List<VttNode> {
    if (text.indexOf("-->", start) in start until end) {
        throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
    }
    val root = VttTagNode("", "", "", ArrayList())
    val stack = ArrayList<VttTagNode>()
    stack.add(root)
    val buffer = StringBuilder()
    var bufferStart = start
    fun flush() {
        if (buffer.isEmpty()) return
        stack[stack.size - 1].children.add(VttTextNode(bufferStart, decodeVttText(buffer.toString())))
        buffer.setLength(0)
    }
    var cursor = start
    var index = start
    while (index < end) {
        if (text[index] != '<') {
            index++
            continue
        }
        var close = index + 1
        while (close < end && text[close] != '>' && text[close] != '\r' && text[close] != '\n') close++
        if (close >= end || text[close] != '>') {
            index++
            continue
        }
        val raw = text.substring(index, close + 1)
        buffer.append(text, cursor, index)
        val inner = raw.substring(1, raw.length - 1)
        if (timestampShape(inner)) {
            if (parseVttTimestamp(inner) == null) {
                throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
            }
            // Inline timestamps are control data, not translatable text; LG drops them too.
        } else {
            val token = parseVttTag(raw)
            if (token == null) {
                buffer.append(raw)
            } else {
                flush()
                if (token.closing) {
                    var top = stack[stack.size - 1]
                    if (top.name == "rt" && token.name == "ruby") {
                        stack.removeAt(stack.size - 1)
                        top = stack[stack.size - 1]
                    }
                    if (stack.size == 1 || top.name != token.name ||
                        token.classes.isNotEmpty() || token.annotation != null) {
                        throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
                    }
                    stack.removeAt(stack.size - 1)
                    top.close = raw
                } else {
                    if (token.name == "v" || token.name == "lang") {
                        if (token.annotation == null || token.annotation.trim().isEmpty()) {
                            throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
                        }
                    } else if (token.annotation != null && token.annotation.trim().isNotEmpty()) {
                        throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
                    }
                    if (token.name == "rt" && stack[stack.size - 1].name == "rt") {
                        stack.removeAt(stack.size - 1)
                    }
                    if (token.name == "rt" && stack[stack.size - 1].name != "ruby") {
                        throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
                    }
                    if (stack.size > MAX_TAG_DEPTH) {
                        throw InvalidVttFormat(InvalidVttFormat.Reason.LIMIT, row)
                    }
                    val node = VttTagNode(token.name, raw, "", ArrayList())
                    stack[stack.size - 1].children.add(node)
                    stack.add(node)
                }
                bufferStart = close + 1
            }
        }
        cursor = close + 1
        index = close + 1
    }
    buffer.append(text, cursor, end)
    flush()
    for (depth in 1 until stack.size) {
        if (stack[depth].name != "v") throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
    }
    return root.children
}

private fun parseVttTag(raw: String): VttTagToken? {
    if (raw.length < 3 || raw[0] != '<' || raw[raw.length - 1] != '>') return null
    val inner = raw.substring(1, raw.length - 1)
    if (inner.isEmpty()) return null
    val closing = inner[0] == '/'
    var index = if (closing) 1 else 0
    if (index >= inner.length || !inner[index].isLetter()) return null
    val nameStart = index
    while (index < inner.length && inner[index].isLetter()) index++
    val name = inner.substring(nameStart, index)
    if (name !in VTT_TAG_NAMES) return null
    if (closing) {
        if (index != inner.length) return null
        return VttTagToken(true, name, "", null)
    }
    val classesStart = index
    while (index < inner.length && inner[index] == '.') {
        index++
        val classStart = index
        while (index < inner.length && inner[index] != ' ' && inner[index] != '\t' &&
            inner[index] != '.' && inner[index] != '<' && inner[index] != '>') index++
        if (index == classStart) return null
    }
    val classes = inner.substring(classesStart, index)
    var annotation: String? = null
    if (index < inner.length) {
        if (inner[index] != ' ' && inner[index] != '\t') return null
        while (index < inner.length && (inner[index] == ' ' || inner[index] == '\t')) index++
        val annotationStart = index
        while (index < inner.length && inner[index] != '<' && inner[index] != '>') index++
        if (index != inner.length) return null
        annotation = inner.substring(annotationStart)
    }
    return VttTagToken(false, name, classes, annotation)
}

private val VTT_TAG_NAMES = setOf("b", "i", "u", "c", "v", "lang", "ruby", "rt")

private fun decodeVttText(raw: String): String {
    val output = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val character = raw[index]
        if (character == '&') {
            val semi = raw.indexOf(';', index + 1)
            if (semi in (index + 2)..(index + 12)) {
                val decoded = when (raw.substring(index + 1, semi)) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "nbsp" -> "\u00A0"
                    "lrm" -> "\u200E"
                    "rlm" -> "\u200F"
                    else -> null
                }
                if (decoded != null) {
                    output.append(decoded)
                    index = semi + 1
                    continue
                }
            }
        }
        output.append(character)
        index++
    }
    return normalizeVttLineBreaks(output.toString())
}

private fun normalizeVttLineBreaks(text: String): String {
    if ('\r' !in text) return text
    val output = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\r') {
            output.append('\n')
            if (text.getOrNull(index + 1) == '\n') index++
        } else {
            output.append(character)
        }
        index++
    }
    return output.toString()
}

private fun escapeVttText(text: String): String {
    var needsEscape = false
    for (character in text) {
        if (character == '&' || character == '<' || character == '>' || character == '\u00A0') {
            needsEscape = true
            break
        }
    }
    if (!needsEscape) return text
    val output = StringBuilder(text.length + 8)
    for (character in text) {
        when (character) {
            '&' -> output.append("&amp;")
            '<' -> output.append("&lt;")
            '>' -> output.append("&gt;")
            '\u00A0' -> output.append("&nbsp;")
            else -> output.append(character)
        }
    }
    return output.toString()
}

private fun vttCueChanged(nodes: List<VttNode>, targets: Map<Int, String>): Boolean {
    for (node in nodes) {
        when (node) {
            is VttTextNode -> if (targets[node.offset] != null && targets[node.offset] != node.text) return true
            is VttTagNode -> if (node.name != "rt" && vttCueChanged(node.children, targets)) return true
        }
    }
    return false
}

private fun renderVttNodes(
    nodes: List<VttNode>,
    targets: Map<Int, String>,
    ending: String,
    closeSpans: Boolean,
    targetLanguage: String,
): List<VttBody> = nodes.map { node ->
    when (node) {
        is VttTextNode -> {
            val target = targets[node.offset] ?: node.text
            VttBody(
                source = escapeVttText(node.text).replace("\n", ending),
                target = escapeVttText(target).replace("\n", ending),
                sourceText = node.text,
                targetText = target,
                changed = target != node.text,
            )
        }
        is VttTagNode -> {
            val children = renderVttNodes(node.children, targets, ending, closeSpans, targetLanguage)
            val changed = node.name != "rt" && children.any { it.changed }
            val close = node.close.ifEmpty { if (closeSpans) "</${node.name}>" else "" }
            val source = node.open + children.joinToString("") { it.source } + close
            val targetChildren = if (node.name == "ruby" && changed) {
                children.filterIndexed { index, _ ->
                    (node.children[index] as? VttTagNode)?.name != "rt"
                }
            } else {
                children
            }
            val targetBody = targetChildren.joinToString("") { it.target }
            val open = if (node.name == "lang" && changed) {
                retargetLang(node.open, targetLanguage)
            } else {
                node.open
            }
            val target = if (node.name == "ruby" && changed) targetBody else open + targetBody + close
            VttBody(
                source = source,
                target = target,
                sourceText = if (node.name == "rt") "" else children.joinToString("") { it.sourceText },
                targetText = if (node.name == "rt") "" else children.joinToString("") { it.targetText },
                changed = changed,
            )
        }
    }
}

private fun retargetLang(open: String, targetLanguage: String): String {
    if (targetLanguage.isEmpty() || !targetLanguage.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        return open
    }
    val close = open.lastIndexOf('>')
    if (close < 0) return open
    var index = close - 1
    while (index >= 0 && open[index] != ' ' && open[index] != '\t') index--
    if (index < 0) return open
    var start = index
    while (start > 0 && (open[start - 1] == ' ' || open[start - 1] == '\t')) start--
    return open.substring(0, start) + " " + targetLanguage.lowercase() + ">"
}

private fun requireVttBody(body: String, row: Int) {
    val trimmed = body.trimEnd()
    if (body.trim().isEmpty()) return
    val normalized = normalizeVttLineBreaks(trimmed)
    var start = 0
    var index = 0
    while (index <= normalized.length) {
        if (index == normalized.length || normalized[index] == '\n') {
            if (normalized.substring(start, index).trim().isEmpty()) {
                throw InvalidVttFormat(InvalidVttFormat.Reason.STRUCTURE, row)
            }
            start = index + 1
        }
        index++
    }
}
