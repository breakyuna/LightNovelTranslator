package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
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

class InvalidMarkdownV2(val reason: Reason, val line: Int? = null) :
    IllegalArgumentException("Invalid Markdown v2: $reason${line?.let { " at line $it" } ?: ""}") {
    enum class Reason { STRUCTURE, TEXT, LIMIT, BINDING, STALE }
}

/**
 * LG-compatible Markdown v2 adapter. Without an mdast dependency the document is split into
 * physical line blocks: fenced code, front matter, thematic breaks and HTML blocks keep their
 * source and are exported verbatim, while prose blocks (including list-item content after the
 * marker) are translatable. [FormatUnit.row] is the 1-based first source line, and
 * `extraField` keeps the exact `before`/`after` layout bytes around every block.
 *
 * The writer re-parses the frozen source and binds units by `row` plus source equality. It
 * never rebuilds a block from list position and never reformats untouched bytes. LG's MDV2
 * format has no bilingual mode, so [FormatWriteOptions.bilingual] is deliberately ignored.
 */
class MarkdownV2Adapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.MD_V2

    override fun read(text: String, path: RelativePath): FormatDocument {
        requireMarkdownInput(text)
        val blocks = parseMarkdownBlocks(text)
        if (blocks.isEmpty()) {
            // Mirror LG: an empty/whitespace-only document still yields one skipped unit whose
            // layout holds the complete source, so the asset can be exported byte-exactly.
            val extra = markdownExtra(text, "")
            return document(listOf(FormatUnit(
                row = 1,
                source = "",
                status = TextItemStatus.RULE_SKIPPED,
                extraField = extra,
            )))
        }
        if (blocks.size > MAX_UNITS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        val after = text.substring(blocks.last().end)
        val units = blocks.mapIndexed { index, block ->
            val tail = if (index == blocks.lastIndex) after else ""
            FormatUnit(
                row = block.row,
                source = block.source,
                status = if (block.skipped) TextItemStatus.RULE_SKIPPED else TextItemStatus.NONE,
                extraField = markdownExtra(block.before, tail),
            )
        }
        return document(units)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        requireMarkdownInput(original)
        if (units.size > MAX_UNITS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        val blocks = parseMarkdownBlocks(original)
        val output = StringBuilder(original.length)
        if (blocks.isEmpty()) {
            if (units.size > 1) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING)
            val unit = units.firstOrNull() ?: return original
            if (unit.row != 1 || unit.source.isNotEmpty()) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            val (before, after) = markdownLayout(unit)
            if (before != null && before != original) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            if (after != null && after.isNotEmpty()) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            return original
        }
        val byRow = HashMap<Int, MarkdownBlock>(blocks.size)
        for (block in blocks) {
            if (byRow.put(block.row, block) != null) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STRUCTURE, block.row)
        }
        val bodies = Array(blocks.size) { blocks[it].source }
        val order = HashMap<Int, Int>(blocks.size)
        blocks.forEachIndexed { index, block -> order[block.row] = index }
        val seen = HashSet<Int>(units.size)
        for (unit in units) {
            if (!seen.add(unit.row)) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            val block = byRow[unit.row] ?: throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            if (unit.source != block.source) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            val (before, after) = markdownLayout(unit)
            if (before != null && before != block.before) {
                throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            }
            if (after != null && after != block.after) {
                throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            }
            if (unit.status == TextItemStatus.PROCESSED && !block.skipped) {
                bodies[order.getValue(unit.row)] = unit.translation
            }
        }
        for (index in blocks.indices) {
            appendBounded(output, blocks[index].before)
            appendBounded(output, bodies[index])
        }
        appendBounded(output, original.substring(blocks.last().end))
        return finishMarkdown(output)
    }

    private fun document(units: List<FormatUnit>) = FormatDocument(
        format = format,
        units = units,
        warnings = emptyList<FormatWarning>(),
    )

    private fun finishMarkdown(output: StringBuilder): String {
        if (output.length > MAX_OUTPUT_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        val text = output.toString()
        if (markdownBytes(text).size > MAX_OUTPUT_BYTES) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        return text
    }

    private fun appendBounded(output: StringBuilder, value: String) {
        if (output.length.toLong() + value.length > MAX_OUTPUT_CHARS) {
            throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        }
        output.append(value)
    }

    private fun markdownExtra(before: String, after: String): String {
        val extra = buildJsonObject {
            put("markdown", buildJsonObject {
                put("before", before)
                put("after", after)
            })
        }.toString()
        if (extra.length > MAX_EXTRA_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        return extra
    }

    /** Tolerates a missing layout object but rejects a present, malformed or non-string field. */
    private fun markdownLayout(unit: FormatUnit): Pair<String?, String?> {
        val root = try {
            JsonObjectValue.parse(unit.extraField, JsonInputLimits(MAX_EXTRA_CHARS, 8)).json
        } catch (_: IllegalArgumentException) {
            throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
        }
        val layout = root["markdown"] as? JsonObject
        fun field(name: String): String? {
            val value = layout?.get(name) ?: root[name] ?: return null
            val primitive = value as? JsonPrimitive ?: throw InvalidMarkdownV2(
                InvalidMarkdownV2.Reason.BINDING, unit.row)
            if (!primitive.isString) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            return primitive.content
        }
        return field("before") to field("after")
    }
}

private const val MAX_INPUT_CHARS = 8 * 1024 * 1024
private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_OUTPUT_CHARS = 24 * 1024 * 1024
private const val MAX_OUTPUT_BYTES = 24 * 1024 * 1024
private const val MAX_UNITS = 100_000
private const val MAX_EXTRA_CHARS = 65_536
private const val MAX_UNIT_CHARS = 65_536
private const val MAX_LINES = 400_000

private fun requireMarkdownInput(text: String) {
    if ('\u0000' in text) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT)
    if (text.length > MAX_INPUT_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
    if (markdownBytes(text).size > MAX_INPUT_BYTES) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
}

private fun markdownBytes(text: String): ByteArray = try {
    TextProjectFingerprint.utf8(text)
} catch (_: TextProjectException) {
    throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT)
}

private class MarkdownLine(
    val start: Int,
    val end: Int,
    val row: Int,
    val text: String,
    val ending: String,
)

private class MarkdownBlock(
    val start: Int,
    val end: Int,
    val row: Int,
    val source: String,
    val before: String,
    var after: String,
    val skipped: Boolean,
)

private class MarkdownFence(val marker: Char, val length: Int)

private fun markdownLines(text: String): List<MarkdownLine> {
    val lines = ArrayList<MarkdownLine>()
    var start = 0
    while (start < text.length) {
        if (lines.size >= MAX_LINES) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT)
        var end = start
        while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
        val ending = when {
            end == text.length -> ""
            text[end] == '\r' && text.getOrNull(end + 1) == '\n' -> "\r\n"
            else -> text[end].toString()
        }
        lines.add(MarkdownLine(start, end, lines.size + 1, text.substring(start, end), ending))
        start = end + ending.length
    }
    return lines
}

/** A pragmatic CommonMark block scanner; the exact byte layout always comes from source offsets. */
private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = markdownLines(text)
    if (lines.isEmpty()) return emptyList()
    val blocks = ArrayList<MarkdownBlock>()
    var cursor = 0
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.text.isBlank()) {
            index++
            continue
        }
        if (index == 0) {
            val marker = markdownFrontMatterMarker(line.text)
            if (marker != null) {
                val close = findFrontMatterEnd(lines, 1, marker)
                if (close >= 0) {
                    val bom = if (line.text.startsWith('\uFEFF')) 1 else 0
                    val start = line.start + bom
                    val end = lines[close].end
                    blocks.add(block(text, start, end, line.row, cursor, true))
                    cursor = end
                    index = close + 1
                    continue
                }
            }
        }
        val firstText = if (index == 0 && line.text.startsWith('\uFEFF')) line.text.substring(1) else line.text
        val fence = markdownFence(firstText)
        if (fence != null) {
            var close = index + 1
            while (close < lines.size && !isMarkdownFenceClose(lines[close].text, fence)) close++
            val last = if (close < lines.size) close else lines.size - 1
            val end = lines[last].end
            blocks.add(block(text, line.start, end, line.row, cursor, true))
            cursor = end
            index = last + 1
            continue
        }
        if (isMarkdownHtmlStart(firstText)) {
            var next = index + 1
            while (next < lines.size && lines[next].text.isNotBlank()) next++
            val end = lines[next - 1].end
            blocks.add(block(text, line.start, end, line.row, cursor, true))
            cursor = end
            index = next
            continue
        }
        if (isMarkdownThematicBreak(firstText)) {
            blocks.add(block(text, line.start, line.end, line.row, cursor, true))
            cursor = line.end
            index++
            continue
        }
        val prefix = markdownContainerPrefix(firstText)
        val contentStart = line.start + (line.text.length - firstText.length) + prefix
        if (contentStart >= line.end) {
            // A bare list marker/quote marker has no paragraph node in mdast; leave its bytes to
            // the next block's `before` (or to the document tail).
            index++
            continue
        }
        var next = index + 1
        while (next < lines.size) {
            val candidate = lines[next]
            if (candidate.text.isBlank()) break
            if (markdownFence(candidate.text) != null) break
            if (isMarkdownHtmlStart(candidate.text)) break
            if (isMarkdownAtxHeading(candidate.text)) break
            if (isMarkdownThematicBreak(candidate.text)) break
            if (markdownContainerPrefix(candidate.text) > 0) break
            next++
        }
        val end = lines[next - 1].end
        val sourceLength = end - contentStart
        if (sourceLength > MAX_UNIT_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT, line.row)
        val source = text.substring(contentStart, end)
        val indented = firstText.startsWith('\t') || firstText.startsWith("    ")
        val skipped = indented || isMarkdownDefinition(source) || !hasMarkdownText(source)
        blocks.add(block(text, contentStart, end, line.row, cursor, skipped))
        cursor = end
        index = next
    }
    if (blocks.isNotEmpty()) blocks.last().after = text.substring(cursor)
    return blocks
}

private fun block(text: String, start: Int, end: Int, row: Int, cursor: Int, skipped: Boolean): MarkdownBlock {
    val before = text.substring(cursor, start)
    if (before.length > MAX_EXTRA_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT, row)
    if (end - start > MAX_UNIT_CHARS) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.LIMIT, row)
    return MarkdownBlock(start, end, row, text.substring(start, end), before, "", skipped)
}

private fun markdownFrontMatterMarker(line: String): String? {
    val value = if (line.startsWith('\uFEFF')) line.substring(1) else line
    return when (value) {
        "---" -> "---"
        "+++" -> "+++"
        else -> null
    }
}

private fun findFrontMatterEnd(lines: List<MarkdownLine>, from: Int, marker: String): Int {
    for (index in from until lines.size) {
        val value = lines[index].text.trimEnd(' ', '\t')
        if (value == marker || (marker == "---" && value == "...")) return index
    }
    return -1
}

private fun markdownFence(line: String): MarkdownFence? {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    if (index >= line.length) return null
    val marker = line[index]
    if (marker != '`' && marker != '~') return null
    var end = index
    while (end < line.length && line[end] == marker) end++
    val length = end - index
    if (length < 3) return null
    if (marker == '`' && line.indexOf('`', end) >= 0) return null
    return MarkdownFence(marker, length)
}

private fun isMarkdownFenceClose(line: String, fence: MarkdownFence): Boolean {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    var end = index
    while (end < line.length && line[end] == fence.marker) end++
    if (end - index < fence.length) return false
    while (end < line.length) {
        if (line[end] != ' ' && line[end] != '\t') return false
        end++
    }
    return true
}

private fun isMarkdownHtmlStart(line: String): Boolean {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    if (index >= line.length || line[index] != '<') return false
    val tail = line.substring(index)
    if (tail.startsWith("<http://") || tail.startsWith("<https://") || tail.startsWith("<mailto:")) {
        return false
    }
    val next = line.getOrNull(index + 1) ?: return false
    return next == '!' || next == '?' || next == '/' || next.isLetter()
}

private fun isMarkdownAtxHeading(line: String): Boolean {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    var count = 0
    while (index < line.length && line[index] == '#' && count < 7) {
        index++
        count++
    }
    if (count !in 1..6) return false
    return index == line.length || line[index] == ' ' || line[index] == '\t'
}

private fun isMarkdownThematicBreak(line: String): Boolean {
    val value = line.trim()
    if (value.length < 3) return false
    val marker = value[0]
    if (marker != '-' && marker != '*' && marker != '_') return false
    var count = 0
    for (character in value) {
        if (character == marker) count++
        else if (character != ' ' && character != '\t') return false
    }
    return count >= 3
}

/**
 * Returns the byte length of a leading container marker (list bullet/number or quote marker),
 * so the marker stays in `before` and never enters the translatable body. Zero means prose,
 * a heading or a skipped construct that carries no container prefix.
 */
private fun markdownContainerPrefix(line: String): Int {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    if (index >= line.length) return 0
    if (line[index] == '>') {
        var cursor = index
        while (cursor < line.length && line[cursor] == '>') {
            cursor++
            if (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
        }
        return cursor
    }
    if (line[index] == '-' || line[index] == '+' || line[index] == '*') {
        val markerEnd = index + 1
        if (markerEnd == line.length || line[markerEnd] == ' ' || line[markerEnd] == '\t') {
            var cursor = markerEnd
            while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
            return cursor
        }
        return 0
    }
    if (line[index].isDigit()) {
        var cursor = index
        while (cursor < line.length && line[cursor].isDigit() && cursor - index < 9) cursor++
        if (cursor < line.length && (line[cursor] == '.' || line[cursor] == ')')) {
            val markerEnd = cursor + 1
            if (markerEnd == line.length || line[markerEnd] == ' ' || line[markerEnd] == '\t') {
                var after = markerEnd
                while (after < line.length && (line[after] == ' ' || line[after] == '\t')) after++
                return after
            }
        }
    }
    return 0
}

/** Link reference definitions are structural nodes in LG and are never translated. */
private fun isMarkdownDefinition(source: String): Boolean {
    val firstLine = source.substringBefore('\n').substringBefore('\r')
    val open = firstLine.indexOf('[')
    if (open != 0) return false
    val close = firstLine.indexOf(']', open + 1)
    if (close <= open + 1) return false
    return close + 1 < firstLine.length && firstLine[close + 1] == ':'
}

/**
 * Conservative visible-text probe matching mdast's "contains a text node" rule closely enough:
 * inline code, link destinations and HTML tags are ignored, image alt text counts, entities are
 * decoded, and any remaining non-syntax character (including emoji) is treated as text.
 */
private fun hasMarkdownText(source: String): Boolean {
    var index = 0
    while (index < source.length) {
        val character = source[index]
        when {
            character == '`' -> {
                var run = 0
                while (index + run < source.length && source[index + run] == '`') run++
                val closing = source.indexOf("`".repeat(run), index + run)
                if (closing >= 0) index = closing + run else index += run
            }
            character == '\\' -> index += 2
            character == '<' -> {
                val next = source.getOrNull(index + 1)
                if (next == '!' || next == '?' || next == '/' || (next != null && next.isLetter())) {
                    if (source.startsWith("<http://", index) || source.startsWith("<https://", index) ||
                        source.startsWith("<mailto:", index)) {
                        index++
                    } else {
                        val close = source.indexOf('>', index + 1)
                        if (close >= 0) index = close + 1 else index++
                    }
                } else {
                    index++
                }
            }
            character == ']' && source.getOrNull(index + 1) == '(' -> {
                var depth = 1
                var cursor = index + 2
                while (cursor < source.length && depth > 0) {
                    when (source[cursor]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    cursor++
                }
                index = cursor
            }
            character == '&' -> {
                val semi = source.indexOf(';', index + 1)
                if (semi in (index + 2)..(index + 12)) {
                    val name = source.substring(index + 1, semi)
                    if (name in MARKDOWN_ENTITIES) {
                        if (name != "nbsp" && name != "lrm" && name != "rlm") return true
                        index = semi + 1
                    } else {
                        index++
                    }
                } else {
                    index++
                }
            }
            character.isWhitespace() || character == '\u00A0' -> index++
            character !in MARKDOWN_SYNTAX_CHARS -> return true
            else -> index++
        }
    }
    return false
}

private const val MARKDOWN_SYNTAX_CHARS = "#*_~`|[]()>-+=\\:.!"

private val MARKDOWN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos", "nbsp", "lrm", "rlm")
