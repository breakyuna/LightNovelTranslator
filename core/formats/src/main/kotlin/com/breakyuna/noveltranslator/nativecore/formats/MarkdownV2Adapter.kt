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
 * Inline constructs are protected conservatively before a block becomes a unit: code spans,
 * link destinations, image syntax, inline HTML tags, autolinks, backslash escapes and emphasis
 * delimiter runs are replaced by stable placeholder tokens in [FormatUnit.source]. Only the
 * surrounding natural text, link label and image alt text remain visible to the model. The
 * writer re-derives the same placeholders from the frozen original and restores the exact
 * protected bytes when applying a translation. A structural construct that starts but never
 * closes (code span, link/image tail, tag or autolink), or a block that would contain no
 * translatable text after masking, is kept verbatim and marked skipped instead of guessing.
 * Missing, duplicated or foreign placeholders in a translation fail closed rather than
 * corrupting the document.
 *
 * The writer re-parses the frozen source and binds units by `row` plus source equality (the
 * masked source for new units, or the raw source for legacy units written before inline
 * protection). It never rebuilds a block from list position and never reformats untouched
 * bytes. LG's MDV2 format has no bilingual mode, so [FormatWriteOptions.bilingual] is
 * deliberately ignored.
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
            val resolved = resolveMarkdownBlock(block)
            FormatUnit(
                row = block.row,
                source = resolved.source,
                status = if (resolved.skipped) TextItemStatus.RULE_SKIPPED else TextItemStatus.NONE,
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
        val layouts = HashMap<Int, MarkdownResolvedBlock>(units.size)
        val bodies = Array(blocks.size) { blocks[it].source }
        val order = HashMap<Int, Int>(blocks.size)
        blocks.forEachIndexed { index, block -> order[block.row] = index }
        val seen = HashSet<Int>(units.size)
        for (unit in units) {
            if (!seen.add(unit.row)) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            val block = byRow[unit.row] ?: throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.BINDING, unit.row)
            val index = order.getValue(unit.row)
            val layout = layouts.getOrPut(index) { resolveMarkdownBlock(block) }
            val usesMaskedSource = layout.source != block.source
            if (unit.source != layout.source && unit.source != block.source) {
                throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            }
            val (before, after) = markdownLayout(unit)
            if (before != null && before != block.before) {
                throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            }
            if (after != null && after != block.after) {
                throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.STALE, unit.row)
            }
            if (unit.status == TextItemStatus.PROCESSED && !layout.skipped) {
                // A legacy unit whose source is still the raw block gets its raw translation
                // back; only the masked source owns placeholder restoration.
                bodies[index] = if (usesMaskedSource && unit.source == layout.source) {
                    restoreMarkdownInline(unit.translation, layout.inline, unit.row)
                } else {
                    unit.translation
                }
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

private const val MARKDOWN_UNCLOSED = -2
private const val MARKDOWN_NOT_CONSTRUCT = -1
private const val MAX_INLINE_TOKENS = 4_096
private const val MAX_INLINE_NESTING = 32
private const val MARKDOWN_INLINE_SCAN_BUDGET = 2_000_000
private const val MARKDOWN_TOKEN_CLOSE = '\u27E7'

/**
 * Shared work budget for one block. Pathological nested brackets or unclosed tags fail closed
 * instead of turning inline protection into quadratic scanning.
 */
private class MarkdownInlineBudget {
    private var remaining = MARKDOWN_INLINE_SCAN_BUDGET
    var exhausted: Boolean = false
        private set

    fun charge(amount: Int = 1): Boolean {
        if (exhausted) return false
        remaining -= amount
        if (remaining < 0) {
            exhausted = true
            return false
        }
        return true
    }
}

/**
 * A block after inline protection. [source] is the masked text for a translatable block and
 * the raw text for a skipped block; [inline] is only present when a translation can be
 * restored from placeholders.
 */
private class MarkdownResolvedBlock(
    val source: String,
    val skipped: Boolean,
    val inline: MarkdownInlineLayout?,
)

private class MarkdownInlineLayout(
    val text: String,
    val rawSpans: List<String>,
    val prefix: String,
)

private class MarkdownInlineSpan(val start: Int, val end: Int)

private fun resolveMarkdownBlock(block: MarkdownBlock): MarkdownResolvedBlock {
    if (block.skipped) return MarkdownResolvedBlock(block.source, true, null)
    val inline = maskMarkdownInline(block.source)
        ?: return MarkdownResolvedBlock(block.source, true, null)
    if (!hasMarkdownTranslatableText(inline)) {
        return MarkdownResolvedBlock(block.source, true, null)
    }
    return MarkdownResolvedBlock(inline.text, false, inline)
}

/**
 * Replaces every protected inline span with a stable token. Returns null when a recognised
 * opener is not closed or a protection limit is exceeded; the caller then keeps the whole
 * block verbatim instead of guessing.
 */
private fun maskMarkdownInline(source: String): MarkdownInlineLayout? {
    val spans = collectMarkdownInlineSpans(source) ?: return null
    if (spans.isEmpty()) return MarkdownInlineLayout(source, emptyList(), "")
    if (spans.size > MAX_INLINE_TOKENS) return null
    val prefix = markdownTokenPrefix(source)
    val text = StringBuilder(source.length)
    val rawSpans = ArrayList<String>(spans.size)
    var cursor = 0
    for ((tokenIndex, span) in spans.withIndex()) {
        if (span.start < cursor || span.end <= span.start || span.end > source.length) {
            return null
        }
        text.append(source, cursor, span.start)
        text.append(prefix).append(tokenIndex).append(MARKDOWN_TOKEN_CLOSE)
        rawSpans.add(source.substring(span.start, span.end))
        cursor = span.end
    }
    text.append(source, cursor, source.length)
    if (text.length > MAX_UNIT_CHARS) return null
    return MarkdownInlineLayout(text.toString(), rawSpans, prefix)
}

/** Tokens are deterministic per block; a source containing the base prefix gets a longer one. */
private fun markdownTokenPrefix(source: String): String {
    var prefix = "\u27E6LNT_MD_"
    while (source.contains(prefix)) prefix += "X"
    return prefix
}

private fun collectMarkdownInlineSpans(source: String): List<MarkdownInlineSpan>? {
    val budget = MarkdownInlineBudget()
    val spans = collectMarkdownInlineHardSpans(source, 0, source.length, budget, 0)
        ?: return null

    // Emphasis/strikethrough delimiters are protected individually rather than as a whole
    // span: the enclosed natural text remains translatable, while the exact delimiter run
    // returns on write even when the source pair is not well formed.
    val hard = BooleanArray(source.length)
    for (span in spans) {
        for (position in span.start until span.end) hard[position] = true
    }
    var cursor = 0
    while (cursor < source.length) {
        if (hard[cursor]) {
            cursor++
            continue
        }
        val marker = source[cursor]
        if (marker != '*' && marker != '_' && marker != '~') {
            cursor++
            continue
        }
        var end = cursor + 1
        while (end < source.length && source[end] == marker) end++
        val runLength = end - cursor
        if (marker == '~' && runLength < 2) {
            cursor = end
            continue
        }
        if (markdownDelimiterRun(source, cursor, end, marker)) {
            spans.add(MarkdownInlineSpan(cursor, end))
        }
        cursor = end
    }
    spans.sortWith(compareBy<MarkdownInlineSpan> { it.start }.thenBy { it.end })
    var previousEnd = 0
    for (span in spans) {
        if (span.start < previousEnd) return null
        previousEnd = span.end
    }
    return spans
}

/**
 * Collects protected syntax in [start] until [end]. Link labels and image alt text are scanned
 * recursively so code spans, escapes and tags inside them are protected too; nesting is capped
 * and every scan shares the block budget.
 */
private fun collectMarkdownInlineHardSpans(
    source: String,
    start: Int,
    end: Int,
    budget: MarkdownInlineBudget,
    depth: Int,
): MutableList<MarkdownInlineSpan>? {
    if (depth > MAX_INLINE_NESTING) return null
    val spans = ArrayList<MarkdownInlineSpan>()
    var index = start
    while (index < end) {
        if (!budget.charge()) return null
        when (source[index]) {
            '\\' -> {
                if (index + 1 < end) {
                    spans.add(MarkdownInlineSpan(index, index + 2))
                    index += 2
                } else {
                    index++
                }
            }
            '`' -> {
                var run = 1
                while (index + run < end && source[index + run] == '`') run++
                val close = findMarkdownCodeSpanEnd(source, index, run, budget)
                if (close < 0 || close > end) return null
                spans.add(MarkdownInlineSpan(index, close))
                index = close
            }
            '!' -> {
                if (index + 1 >= end || source[index + 1] != '[') {
                    index++
                    continue
                }
                val close = findMarkdownBracketClose(source, index + 1, budget)
                if (close < 0 || close >= end) return null
                val tail = markdownLinkTailEnd(source, close + 1, budget)
                if (tail == MARKDOWN_UNCLOSED) return null
                spans.add(MarkdownInlineSpan(index, index + 2))
                if (tail == MARKDOWN_NOT_CONSTRUCT) {
                    spans.add(MarkdownInlineSpan(close, close + 1))
                    val nested = collectMarkdownInlineHardSpans(
                        source, index + 2, close, budget, depth + 1,
                    ) ?: return null
                    spans.addAll(nested)
                    index = close + 1
                } else {
                    if (tail > end) return null
                    spans.add(MarkdownInlineSpan(close, tail))
                    val nested = collectMarkdownInlineHardSpans(
                        source, index + 2, close, budget, depth + 1,
                    ) ?: return null
                    spans.addAll(nested)
                    index = tail
                }
            }
            '[' -> {
                val close = findMarkdownBracketClose(source, index, budget)
                if (close < 0 || close >= end) {
                    if (close >= end || budget.exhausted) return null
                    // A standalone bracket is literal text. Protect only the marker byte so a
                    // translation cannot silently rewrite it, while the surrounding prose stays
                    // translatable.
                    spans.add(MarkdownInlineSpan(index, index + 1))
                    index++
                    continue
                }
                val tail = markdownLinkTailEnd(source, close + 1, budget)
                if (tail == MARKDOWN_UNCLOSED) return null
                spans.add(MarkdownInlineSpan(index, index + 1))
                if (tail == MARKDOWN_NOT_CONSTRUCT) {
                    spans.add(MarkdownInlineSpan(close, close + 1))
                    val nested = collectMarkdownInlineHardSpans(
                        source, index + 1, close, budget, depth + 1,
                    ) ?: return null
                    spans.addAll(nested)
                    index = close + 1
                } else {
                    if (tail > end) return null
                    spans.add(MarkdownInlineSpan(close, tail))
                    val nested = collectMarkdownInlineHardSpans(
                        source, index + 1, close, budget, depth + 1,
                    ) ?: return null
                    spans.addAll(nested)
                    index = tail
                }
            }
            '<' -> {
                val angleEnd = markdownAngleEnd(source, index, budget)
                if (angleEnd == MARKDOWN_UNCLOSED) return null
                if (angleEnd == MARKDOWN_NOT_CONSTRUCT) {
                    if (budget.exhausted) return null
                    index++
                } else {
                    if (angleEnd > end) return null
                    spans.add(MarkdownInlineSpan(index, angleEnd))
                    index = angleEnd
                }
            }
            else -> index++
        }
    }
    return spans
}

private fun findMarkdownCodeSpanEnd(
    source: String,
    start: Int,
    runLength: Int,
    budget: MarkdownInlineBudget,
): Int {
    var index = start + runLength
    while (index < source.length) {
        if (!budget.charge()) return -1
        if (source[index] != '`') {
            index++
            continue
        }
        var run = 1
        while (index + run < source.length && source[index + run] == '`') run++
        if (run == runLength) return index + run
        index += run
    }
    return -1
}

/** Returns the index of the `]` matching the `[` at [open], or -1 when it never closes. */
private fun findMarkdownBracketClose(
    source: String,
    open: Int,
    budget: MarkdownInlineBudget,
): Int {
    var depth = 0
    var index = open
    while (index < source.length) {
        if (!budget.charge()) return -1
        when (source[index]) {
            '\\' -> index += 2
            '`' -> {
                var run = 1
                while (index + run < source.length && source[index + run] == '`') run++
                val close = findMarkdownCodeSpanEnd(source, index, run, budget)
                index = if (close >= 0) close else index + run
            }
            '[' -> {
                depth++
                index++
            }
            ']' -> {
                depth--
                if (depth == 0) return index
                index++
            }
            else -> index++
        }
    }
    return -1
}

/**
 * Scans the part after a closing `]`. Returns the exclusive end of the protected tail,
 * [MARKDOWN_NOT_CONSTRUCT] for a literal bracket, or [MARKDOWN_UNCLOSED] for a malformed
 * link/image tail.
 */
private fun markdownLinkTailEnd(source: String, from: Int, budget: MarkdownInlineBudget): Int {
    if (from >= source.length) return MARKDOWN_NOT_CONSTRUCT
    return when (source[from]) {
        '(' -> {
            val end = findMarkdownDestinationEnd(source, from, budget)
            if (end < 0) MARKDOWN_UNCLOSED else end
        }
        '[' -> {
            val close = findMarkdownBracketClose(source, from, budget)
            if (close < 0) MARKDOWN_UNCLOSED else close + 1
        }
        else -> MARKDOWN_NOT_CONSTRUCT
    }
}

private fun findMarkdownDestinationEnd(
    source: String,
    open: Int,
    budget: MarkdownInlineBudget,
): Int {
    var index = open + 1
    var depth = 1
    var inAngle = false
    while (index < source.length) {
        if (!budget.charge()) return -1
        val current = source[index]
        if (current == '\\') {
            index += if (index + 1 < source.length) 2 else 1
            continue
        }
        if (inAngle) {
            if (current == '>') inAngle = false
            index++
            continue
        }
        when {
            current == '<' && index == open + 1 -> {
                inAngle = true
                index++
            }
            current == '(' -> {
                depth++
                index++
            }
            current == ')' -> {
                depth--
                if (depth == 0) return index + 1
                index++
            }
            (current == '"' || current == '\'') && markdownTitleQuoteStart(source, index) -> {
                val close = findMarkdownTitleEnd(source, index, current, budget)
                if (close < 0) return -1
                index = close
            }
            else -> index++
        }
    }
    return -1
}

private fun markdownTitleQuoteStart(source: String, index: Int): Boolean {
    if (index == 0) return false
    val previous = source[index - 1]
    return previous == ' ' || previous == '\t' || previous == '\n' || previous == '\r'
}

private fun findMarkdownTitleEnd(
    source: String,
    start: Int,
    quote: Char,
    budget: MarkdownInlineBudget,
): Int {
    var index = start + 1
    while (index < source.length) {
        if (!budget.charge()) return -1
        if (source[index] == '\\') {
            index += if (index + 1 < source.length) 2 else 1
            continue
        }
        if (source[index] == quote) return index + 1
        index++
    }
    return -1
}

private fun markdownAngleEnd(source: String, start: Int, budget: MarkdownInlineBudget): Int {
    if (start + 1 >= source.length) return MARKDOWN_NOT_CONSTRUCT
    val next = source[start + 1]
    if (next == '!') {
        if (source.startsWith("<!--", start)) {
            val close = source.indexOf("-->", start + 4)
            return if (close < 0) MARKDOWN_UNCLOSED else close + 3
        }
        if (source.startsWith("<![CDATA[", start)) {
            val close = source.indexOf("]]>", start + 9)
            return if (close < 0) MARKDOWN_UNCLOSED else close + 3
        }
        val close = source.indexOf('>', start + 2)
        return if (close < 0) MARKDOWN_UNCLOSED else close + 1
    }
    if (next == '?') {
        val close = source.indexOf("?>", start + 2)
        return if (close < 0) MARKDOWN_UNCLOSED else close + 2
    }
    if (next == '/') {
        if (start + 2 >= source.length || !source[start + 2].isLetter()) {
            return MARKDOWN_NOT_CONSTRUCT
        }
        return scanMarkdownTagEnd(source, start + 2, budget)
    }
    if (next.isLetter()) {
        val gt = source.indexOf('>', start + 1)
        if (gt >= 0 && !budget.charge(gt - start)) return MARKDOWN_UNCLOSED
        if (gt >= 0 && isMarkdownAutolinkBody(source, start + 1, gt, budget)) return gt + 1
        var nameEnd = start + 1
        while (nameEnd < source.length &&
            (source[nameEnd].isLetterOrDigit() || source[nameEnd] == '-')
        ) {
            if (!budget.charge()) return MARKDOWN_UNCLOSED
            nameEnd++
        }
        if (nameEnd == start + 1) return MARKDOWN_NOT_CONSTRUCT
        val after = source.getOrNull(nameEnd)
        if (after != null && after != ' ' && after != '\t' && after != '\n' &&
            after != '\r' && after != '/' && after != '>'
        ) {
            if (gt < 0 && isMarkdownAutolinkPrefix(source, start, budget)) return MARKDOWN_UNCLOSED
            return MARKDOWN_NOT_CONSTRUCT
        }
        return scanMarkdownTagEnd(source, nameEnd, budget)
    }
    return MARKDOWN_NOT_CONSTRUCT
}

private fun scanMarkdownTagEnd(source: String, from: Int, budget: MarkdownInlineBudget): Int {
    var index = from
    var quote: Char? = null
    while (index < source.length) {
        if (!budget.charge()) return MARKDOWN_UNCLOSED
        val current = source[index]
        if (quote != null) {
            if (current == quote) quote = null
            index++
            continue
        }
        when (current) {
            '"', '\'' -> quote = current
            '>' -> return index + 1
        }
        index++
    }
    return MARKDOWN_UNCLOSED
}

private fun isMarkdownAutolinkBody(
    source: String,
    start: Int,
    end: Int,
    budget: MarkdownInlineBudget,
): Boolean {
    if (end <= start) return false
    for (index in start until end) {
        if (!budget.charge()) return false
        val current = source[index]
        if (current.isWhitespace() || current == '<' || current == '>') return false
    }
    val body = source.substring(start, end)
    if (body.startsWith("mailto:", ignoreCase = true)) return true
    val colon = body.indexOf(':')
    if (colon < 0) return body.count { it == '@' } == 1
    if (colon < 2 || colon > 32 || !body[0].isLetter()) return false
    for (index in 1 until colon) {
        if (!budget.charge()) return false
        val current = body[index]
        if (!(current.isLetterOrDigit() || current == '+' || current == '.' || current == '-')) {
            return false
        }
    }
    return true
}

/** Detects an autolink opener even when its closing `>` is missing. */
private fun isMarkdownAutolinkPrefix(
    source: String,
    start: Int,
    budget: MarkdownInlineBudget,
): Boolean {
    var end = start + 1
    while (end < source.length) {
        if (!budget.charge()) return false
        val current = source[end]
        if (current.isWhitespace() || current == '<' || current == '>') break
        end++
    }
    if (end <= start + 1) return false
    val body = source.substring(start + 1, end)
    if (body.startsWith("mailto:", ignoreCase = true)) return true
    if (body.count { it == '@' } == 1) return true
    val colon = body.indexOf(':')
    if (colon < 2 || colon > 32 || !body[0].isLetter()) return false
    for (index in 1 until colon) {
        if (!budget.charge()) return false
        val current = body[index]
        if (!(current.isLetterOrDigit() || current == '+' || current == '.' || current == '-')) {
            return false
        }
    }
    return true
}

private fun markdownDelimiterRun(source: String, start: Int, end: Int, marker: Char): Boolean {
    val before = if (start == 0) null else source[start - 1]
    val after = if (end >= source.length) null else source[end]
    if (marker == '_' && (markdownNonAsciiLetter(before) || markdownNonAsciiLetter(after))) {
        // CJK and other non-ASCII scripts have no CommonMark word boundary to anchor the
        // intraword-underscore exception; protecting the run keeps the marker intact.
        return true
    }
    val beforeWhitespace = before == null || before.isWhitespace()
    val afterWhitespace = after == null || after.isWhitespace()
    val beforePunctuation = before != null && markdownPunctuation(before)
    val afterPunctuation = after != null && markdownPunctuation(after)
    val leftFlanking = !afterWhitespace && (!afterPunctuation || beforeWhitespace || beforePunctuation)
    val rightFlanking = !beforeWhitespace && (!beforePunctuation || afterWhitespace || afterPunctuation)
    return if (marker == '_') {
        (leftFlanking && (!rightFlanking || beforePunctuation)) ||
            (rightFlanking && (!leftFlanking || afterPunctuation))
    } else {
        leftFlanking || rightFlanking
    }
}

private fun markdownNonAsciiLetter(character: Char?): Boolean =
    character != null && character.isLetter() && character.code > 0x7F

private fun markdownPunctuation(character: Char): Boolean =
    !character.isWhitespace() && !character.isLetterOrDigit()

private fun hasMarkdownTranslatableText(layout: MarkdownInlineLayout): Boolean {
    if (layout.rawSpans.isEmpty()) return hasMarkdownText(layout.text)
    val probe = StringBuilder(layout.text.length)
    var cursor = 0
    while (true) {
        val start = layout.text.indexOf(layout.prefix, cursor)
        if (start < 0) {
            probe.append(layout.text, cursor, layout.text.length)
            break
        }
        probe.append(layout.text, cursor, start)
        probe.append(' ')
        val close = layout.text.indexOf(MARKDOWN_TOKEN_CLOSE, start + layout.prefix.length)
        if (close < 0) return false
        cursor = close + 1
    }
    return hasMarkdownText(probe.toString())
}

/**
 * Restores every protected span exactly once. A translation that drops, duplicates or invents
 * a token is rejected instead of silently producing broken Markdown.
 */
private fun restoreMarkdownInline(
    text: String,
    layout: MarkdownInlineLayout?,
    row: Int,
): String {
    if (layout == null || layout.rawSpans.isEmpty()) return text
    val result = StringBuilder(text.length)
    val seen = BooleanArray(layout.rawSpans.size)
    var cursor = 0
    while (true) {
        val start = text.indexOf(layout.prefix, cursor)
        if (start < 0) break
        result.append(text, cursor, start)
        var end = start + layout.prefix.length
        val digitsStart = end
        while (end < text.length && text[end].isDigit()) end++
        if (end == digitsStart || end >= text.length || text[end] != MARKDOWN_TOKEN_CLOSE) {
            throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT, row)
        }
        val tokenIndex = text.substring(digitsStart, end).toIntOrNull()
            ?: throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT, row)
        if (tokenIndex !in seen.indices || seen[tokenIndex]) {
            throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT, row)
        }
        seen[tokenIndex] = true
        result.append(layout.rawSpans[tokenIndex])
        cursor = end + 1
    }
    result.append(text, cursor, text.length)
    if (seen.any { !it }) throw InvalidMarkdownV2(InvalidMarkdownV2.Reason.TEXT, row)
    return result.toString()
}
