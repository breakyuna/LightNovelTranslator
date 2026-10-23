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
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure-text Ren'Py `translate` script adapter.
 *
 * Ported from LG's renpy lexer/parser/extractor/matcher/writer/compat pipeline:
 * - `translate <lang> strings:` blocks pair `old` templates with the following `new` line.
 * - `translate <lang> <label>:` blocks pair comment templates (`# e "text"`) with target
 *   statements using the same whitespace-normalized/LCS matching as LG.
 * - Only dialogue/string/name slots become units. Labels, Python blocks, comments and
 *   non-target lines are never rewritten. Within a matched LABEL target line the writer
 *   restores trailing structure from the template comment, exactly as LG's writer does.
 * - A changed target slot is emitted as a normal double-quoted Ren'Py literal with the same
 *   escaping rules as LG. An unchanged slot keeps its original raw slice, so a document whose
 *   selected unit values and name policy are unchanged is returned byte-for-byte.
 * - Locator metadata mirrors LG's AST extra shape (`block`, `pair`, `slots`, `digest`), with
 *   SHA-256 instead of SHA-1 because the adapter never has to interoperate with LG's in-memory
 *   digests. Legacy/missing current metadata is rebuilt by re-extracting the original and
 *   matching row + source + name; a stale current locator fails closed instead of guessing.
 *
 * Scope boundary: a string that physically spans lines or uses triple quotes is scanned,
 * validated and preserved exactly, but is not offered as a unit. This covers LG's single-line
 * double-quoted extraction without risking a rewrite that would move trailing code between
 * lines. Single-quoted literals are scanned/preserved but not extracted (LG scans only double
 * quotes). Python blocks are preserved and never extracted. Name slots follow
 * [FormatWriteOptions.writeTranslatedNames]: false writes the source name, true writes a
 * non-empty translated name with source fallback; the contract default is false.
 * Unlike LG's writer, a non-PROCESSED dialogue/string unit never substitutes its source into
 * an empty target: untranslated text stays literal. `options.bilingual` is rejected because a
 * game script has no lossless bilingual form.
 */
class RenpyFormatAdapter : TextFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.RENPY

    override fun read(text: String, path: RelativePath): FormatDocument {
        TransRenpyFormatBounds.requireReadable(text, path)
        val parsed = parseRenpyScript(text)
        val units = collectRenpyUnits(parsed, path.value)
        val metadata = buildJsonObject {
            put("format", "renpy")
            put("version", 1)
            put("blocks", parsed.blocks.size)
            put("label_blocks", parsed.blocks.count { it.kind == RenpyBlockKind.LABEL })
            put("strings_blocks", parsed.blocks.count { it.kind == RenpyBlockKind.STRINGS })
            put("python_blocks", parsed.blocks.count { it.kind == RenpyBlockKind.PYTHON })
            put("multiline_strings", parsed.multilineStrings)
            put("unsupported_statements", parsed.unsupportedStrings)
            put("unit_count", units.size)
        }.toString()
        val warnings = if (parsed.multilineStrings > 0 || parsed.unsupportedStrings > 0) {
            listOf(
                FormatWarning(
                    code = "RENPY_UNSUPPORTED_STRING",
                    path = path.value,
                    message = "Multiline or triple-quoted strings are preserved but not extracted",
                ),
            )
        } else {
            emptyList()
        }
        return FormatDocument(format, units, warnings, "renpy", metadata)
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

        // Escape expansion is bounded before any target line is rebuilt: a control character
        // can become six characters in a normal double-quoted Ren'Py literal.
        var projectedChars = original.length.toLong()
        for (unit in units) {
            TransRenpyFormatBounds.requireUnitText(unit.source, unit.row)
            TransRenpyFormatBounds.requireUnitText(unit.translation, unit.row)
            unit.nameSource?.forEach { name ->
                TransRenpyFormatBounds.requireUnitText(name, unit.row)
                projectedChars += renpyEscapedLength(name) + 2L
            }
            unit.nameTranslation?.forEach { name ->
                TransRenpyFormatBounds.requireUnitText(name, unit.row)
                projectedChars += renpyEscapedLength(name) + 2L
            }
            if (unit.status == TextItemStatus.PROCESSED) {
                projectedChars += renpyEscapedLength(unit.translation) + 2L
            }
            if (projectedChars > TransRenpyFormatBounds.MAX_OUTPUT_CHARS) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, unit.row)
            }
        }

        val parsed = parseRenpyScript(original)
        val edits = ArrayList<RenpyLineEdit>(units.size)
        val seenTargets = HashSet<Int>()
        var canonicalByRow: Map<Int, List<FormatUnit>>? = null
        fun canonical(): Map<Int, List<FormatUnit>> {
            val cached = canonicalByRow
            if (cached != null) return cached
            val built = collectRenpyUnits(parsed, "").groupBy { it.row }
            canonicalByRow = built
            return built
        }

        for (unit in units) {
            val locator = resolveRenpyLocator(unit, parsed) { canonical() }
            val replacements = LinkedHashMap<Int, String>()
            for (slot in locator.slots) {
                val next = when (slot.role) {
                    RenpySlotRole.NAME -> resolveRenpyName(unit, options)
                    RenpySlotRole.DIALOGUE,
                    RenpySlotRole.STRING -> if (unit.status == TextItemStatus.PROCESSED) {
                        unit.translation
                    } else {
                        null
                    }
                } ?: continue
                TransRenpyFormatBounds.requireUnitText(next, unit.row)
                replacements[slot.litIndex] = next
            }
            if (replacements.isEmpty()) continue
            var changed = false
            for ((index, value) in replacements) {
                if (locator.target.literals[index].value != value) {
                    changed = true
                    break
                }
            }
            if (!changed) continue
            val replaced = replaceRenpyLiterals(locator.baseCode, locator.baseLiterals, replacements)
            val line = parsed.lines[locator.target.lineIndex]
            if (!seenTargets.add(locator.target.row)) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.DUPLICATE_LOCATOR, unit.row)
            }
            edits.add(RenpyLineEdit(line.start, line.contentEnd, locator.target.indent + replaced))
        }
        if (edits.isEmpty()) return original
        return applyRenpyEdits(original, edits)
    }
}

private const val RENPY_MAX_LINES = 250_000
private const val RENPY_MAX_MATCH_CELLS = 2_000_000L
private const val HEX_DIGITS = "0123456789abcdef"

private enum class RenpyBlockKind { LABEL, STRINGS, PYTHON, OTHER }
private enum class RenpyStatementKind { TEMPLATE, TARGET, META, BLANK, OTHER }
private enum class RenpySlotRole { DIALOGUE, NAME, STRING }

private class RenpyLine(
    val start: Int,
    val contentEnd: Int,
    val end: Int,
    val row: Int,
)

private class RenpyLiteral(
    val startCol: Int,
    val endCol: Int,
    val raw: Boolean,
    val value: String,
)

private class RenpyLiteralScan(
    val literals: List<RenpyLiteral>,
    val unsupported: Boolean,
)

private class RenpyStatement(
    val lineIndex: Int,
    val row: Int,
    val raw: String,
    val indent: String,
    val code: String,
    val kind: RenpyStatementKind,
    val blockKind: RenpyBlockKind,
    val literals: List<RenpyLiteral>,
    val strictKey: String,
    val relaxedKey: String,
    val hasUnsupported: Boolean,
)

private class RenpyBlock(
    val headerRow: Int,
    val endRow: Int,
    val lang: String,
    val label: String,
    val kind: RenpyBlockKind,
    val statements: List<RenpyStatement>,
) {
    val byRow: Map<Int, RenpyStatement> = statements.associateBy { it.row }
}

private class RenpyHeader(
    val lang: String,
    val label: String,
    val kind: RenpyBlockKind,
)

private class RenpyStringScan(
    val covered: BooleanArray,
    val multilineStrings: Int,
)

private class RenpyParsed(
    val lines: List<RenpyLine>,
    val blocks: List<RenpyBlock>,
    val multilineStrings: Int,
    val unsupportedStrings: Int,
) {
    val blockByHeader: Map<Int, RenpyBlock> = blocks.associateBy { it.headerRow }
}

private data class RenpySlot(
    val role: RenpySlotRole,
    val litIndex: Int,
)

private class RenpyLocator(
    val target: RenpyStatement,
    val slots: List<RenpySlot>,
    val baseCode: String,
    val baseLiterals: List<RenpyLiteral>,
)

private class RenpyLineEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
)

private class RenpySignature(
    val stringCount: Int,
    val strictKey: String,
    val relaxedKey: String,
)

private class RenpyCandidate(
    val start: Int,
    val end: Int,
    val priority: Int,
)

private class RenpyRange(
    val start: Int,
    val end: Int,
)

private fun parseRenpyScript(text: String): RenpyParsed {
    val lines = splitRenpyLines(text)
    val stringScan = scanRenpyStrings(text, lines)
    val blocks = ArrayList<RenpyBlock>()
    var unsupportedStatements = 0
    var index = 0
    while (index < lines.size) {
        if (stringScan.covered[index]) {
            index++
            continue
        }
        val header = parseRenpyTranslateHeader(
            text.substring(lines[index].start, lines[index].contentEnd),
        )
        if (header == null) {
            index++
            continue
        }
        val headerIndex = index
        var next = index + 1
        val statements = ArrayList<RenpyStatement>()
        while (next < lines.size) {
            if (!stringScan.covered[next]) {
                val candidate = parseRenpyTranslateHeader(
                    text.substring(lines[next].start, lines[next].contentEnd),
                )
                if (candidate != null) break
                val statement = parseRenpyStatement(text, lines[next], header.kind)
                statements.add(statement)
                if (statement.hasUnsupported) unsupportedStatements++
            }
            next++
        }
        val endRow = if (next == headerIndex + 1) headerIndex + 1 else next
        blocks.add(
            RenpyBlock(
                headerRow = headerIndex + 1,
                endRow = endRow,
                lang = header.lang,
                label = header.label,
                kind = header.kind,
                statements = statements,
            ),
        )
        index = next
    }
    return RenpyParsed(
        lines = lines,
        blocks = blocks,
        multilineStrings = stringScan.multilineStrings,
        unsupportedStrings = unsupportedStatements,
    )
}

private fun splitRenpyLines(text: String): List<RenpyLine> {
    val lines = ArrayList<RenpyLine>()
    var start = 0
    var index = 0
    while (index < text.length) {
        val current = text[index]
        val endingLength = when {
            current == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> 2
            isRenpySplitLineBreak(current) -> 1
            else -> 0
        }
        if (endingLength == 0) {
            index++
            continue
        }
        if (lines.size >= RENPY_MAX_LINES) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, lines.size + 1)
        }
        lines.add(RenpyLine(start, index, index + endingLength, lines.size + 1))
        index += endingLength
        start = index
    }
    if (start < text.length) {
        if (lines.size >= RENPY_MAX_LINES) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, lines.size + 1)
        }
        lines.add(RenpyLine(start, text.length, text.length, lines.size + 1))
    }
    return lines
}

/**
 * Validates every code string in the file and marks physical lines that continue a multiline
 * literal. Comment lines are skipped because a template comment may legitimately contain an
 * unmatched quote; only actual code needs the unterminated-string failure.
 */
private fun scanRenpyStrings(text: String, lines: List<RenpyLine>): RenpyStringScan {
    val covered = BooleanArray(lines.size)
    var multilineStrings = 0
    var lineIndex = 0
    while (lineIndex < lines.size) {
        if (covered[lineIndex]) {
            lineIndex++
            continue
        }
        val line = lines[lineIndex]
        var index = line.start
        if (index < line.contentEnd && text[index] == '\uFEFF') index++
        index += leadingIndentLength(text, index, line.contentEnd)
        if (index >= line.contentEnd || text[index] == '#') {
            lineIndex++
            continue
        }
        var nextLine = lineIndex
        while (index < line.contentEnd) {
            val current = text[index]
            if (current == '#') break
            if (current != '"' && current != '\'') {
                index++
                continue
            }
            val close = findRenpyStringEnd(text, index, current)
                ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING, line.row)
            if (close > line.contentEnd) {
                val endLine = lineIndexForOffset(lines, close - 1)
                var mark = lineIndex + 1
                while (mark <= endLine) {
                    covered[mark] = true
                    mark++
                }
                multilineStrings++
                nextLine = endLine
                break
            }
            index = close
        }
        lineIndex = nextLine + 1
    }
    return RenpyStringScan(covered, multilineStrings)
}

private fun lineIndexForOffset(lines: List<RenpyLine>, offset: Int): Int {
    var low = 0
    var high = lines.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val line = lines[mid]
        when {
            offset < line.start -> high = mid - 1
            offset >= line.end -> low = mid + 1
            else -> return mid
        }
    }
    return 0
}

private fun parseRenpyStatement(
    text: String,
    line: RenpyLine,
    blockKind: RenpyBlockKind,
): RenpyStatement {
    val raw = text.substring(line.start, line.contentEnd)
    if (raw.all { isRenpySpace(it) }) {
        return RenpyStatement(
            lineIndex = line.row - 1,
            row = line.row,
            raw = raw,
            indent = "",
            code = "",
            kind = RenpyStatementKind.BLANK,
            blockKind = blockKind,
            literals = emptyList(),
            strictKey = "",
            relaxedKey = "",
            hasUnsupported = false,
        )
    }
    val indentEnd = leadingIndentLength(raw)
    val indent = raw.substring(0, indentEnd)
    val rest = raw.substring(indentEnd)
    val comment = stripRenpyCommentPrefix(rest)
    val code: String
    val kind: RenpyStatementKind
    if (comment.isComment) {
        code = comment.content
        kind = if (isRenpyMetaComment(comment.content)) {
            RenpyStatementKind.META
        } else {
            RenpyStatementKind.TEMPLATE
        }
    } else if (blockKind == RenpyBlockKind.STRINGS && rest.startsWith("old ")) {
        code = rest
        kind = RenpyStatementKind.TEMPLATE
    } else if (blockKind == RenpyBlockKind.STRINGS && rest.startsWith("new ")) {
        code = rest
        kind = RenpyStatementKind.TARGET
    } else {
        code = rest
        kind = RenpyStatementKind.TARGET
    }
    val literalScan = scanRenpyLineLiterals(code, line.row, stopAtComment = !comment.isComment)
    val strictKey = buildRenpySkeleton(code, literalScan.literals)
    val relaxedKey = if (blockKind == RenpyBlockKind.LABEL) {
        normalizeRenpyWhitespace(normalizeRenpySpeakerToken(strictKey))
    } else {
        strictKey
    }
    return RenpyStatement(
        lineIndex = line.row - 1,
        row = line.row,
        raw = raw,
        indent = indent,
        code = code,
        kind = kind,
        blockKind = blockKind,
        literals = literalScan.literals,
        strictKey = strictKey,
        relaxedKey = relaxedKey,
        hasUnsupported = literalScan.unsupported,
    )
}

private fun scanRenpyLineLiterals(
    code: String,
    row: Int,
    stopAtComment: Boolean,
): RenpyLiteralScan {
    val literals = ArrayList<RenpyLiteral>()
    var unsupported = false
    var index = 0
    while (index < code.length) {
        val current = code[index]
        if (stopAtComment && current == '#') break
        if (current != '"' && current != '\'') {
            index++
            continue
        }
        val quote = current
        val raw = isRenpyRawPrefix(code, index)
        val triple = index + 2 < code.length &&
            code[index + 1] == quote && code[index + 2] == quote
        val close = findRenpyStringEnd(code, index, quote)
        if (close == null) {
            unsupported = true
            break
        }
        if (triple) {
            unsupported = true
            index = close
            continue
        }
        if (quote == '\'') {
            index = close
            continue
        }
        val startCol = if (raw) index - 1 else index
        val inner = code.substring(index + 1, close - 1)
        val value = if (raw) inner else unescapeRenpyString(inner, row)
        if (value.length > TransRenpyFormatBounds.MAX_UNIT_CHARS) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, row)
        }
        if (value.indexOf('\u0000') >= 0) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_TEXT, row)
        }
        TransRenpyFormatBounds.strictUtf8(value)
        literals.add(RenpyLiteral(startCol, close, raw, value))
        index = close
    }
    return RenpyLiteralScan(literals, unsupported)
}

private fun findRenpyStringEnd(text: String, quoteStart: Int, quote: Char): Int? {
    val triple = quoteStart + 2 < text.length &&
        text[quoteStart + 1] == quote && text[quoteStart + 2] == quote
    var index = quoteStart + if (triple) 3 else 1
    while (index < text.length) {
        val current = text[index]
        if (current == '\\') {
            if (index + 1 >= text.length) return null
            index += if (text[index + 1] == '\r' &&
                index + 2 < text.length && text[index + 2] == '\n'
            ) {
                3
            } else {
                2
            }
            continue
        }
        if (triple) {
            if (current == quote && index + 2 < text.length &&
                text[index + 1] == quote && text[index + 2] == quote
            ) {
                return index + 3
            }
            index++
            continue
        }
        if (current == quote) return index + 1
        if (quote == '\'' && isRenpySplitLineBreak(current)) return null
        index++
    }
    return null
}

private fun isRenpyRawPrefix(code: String, quoteIndex: Int): Boolean {
    if (quoteIndex == 0) return false
    val prefix = code[quoteIndex - 1]
    if (prefix != 'r' && prefix != 'R') return false
    return quoteIndex - 1 == 0 || !isRenpyIdentifierPart(code[quoteIndex - 2])
}

private fun parseRenpyTranslateHeader(line: String): RenpyHeader? {
    val candidate = line.trim { it == '\uFEFF' || it.isWhitespace() }
    if (!candidate.startsWith("translate")) return null
    var index = 9
    if (index >= candidate.length || !candidate[index].isWhitespace()) return null
    while (index < candidate.length && candidate[index].isWhitespace()) index++
    val langStart = index
    while (index < candidate.length && isRenpyAsciiWord(candidate[index])) index++
    if (index == langStart) return null
    val lang = candidate.substring(langStart, index)
    if (index >= candidate.length || !candidate[index].isWhitespace()) return null
    while (index < candidate.length && candidate[index].isWhitespace()) index++
    val labelStart = index
    while (index < candidate.length && isRenpyAsciiWord(candidate[index])) index++
    if (index == labelStart) return null
    val label = candidate.substring(labelStart, index)
    while (index < candidate.length && candidate[index].isWhitespace()) index++
    if (index >= candidate.length || candidate[index] != ':') return null
    index++
    while (index < candidate.length && candidate[index].isWhitespace()) index++
    if (index != candidate.length) return null
    return RenpyHeader(lang, label, classifyRenpyBlock(label))
}

private fun classifyRenpyBlock(label: String): RenpyBlockKind = when (label) {
    "strings" -> RenpyBlockKind.STRINGS
    "python" -> RenpyBlockKind.PYTHON
    else -> RenpyBlockKind.LABEL
}

private class RenpyComment(val isComment: Boolean, val content: String)

private fun stripRenpyCommentPrefix(text: String): RenpyComment {
    if (!text.startsWith("#")) return RenpyComment(false, text)
    val content = if (text.length > 1 && text[1] == ' ') text.substring(2) else text.substring(1)
    return RenpyComment(true, content)
}

private fun isRenpyMetaComment(content: String): Boolean {
    val stripped = content.trim()
    if (stripped.startsWith("TODO:")) return true
    if (!stripped.startsWith("game/")) return false
    val colon = stripped.lastIndexOf(':')
    if (colon <= 5) return false
    val suffix = stripped.substring(colon + 1).trimEnd()
    return suffix.isNotEmpty() && suffix.all { it in '0'..'9' }
}

private fun buildRenpySkeleton(code: String, literals: List<RenpyLiteral>): String {
    if (literals.isEmpty()) return normalizeRenpyWhitespace(code)
    val result = StringBuilder(code.length + literals.size * 4)
    var cursor = 0
    for (literal in literals) {
        result.append(code, cursor, literal.startCol)
        if (literal.raw) result.append("r\"{}\"") else result.append("\"{}\"")
        cursor = literal.endCol
    }
    result.append(code, cursor, code.length)
    return normalizeRenpyWhitespace(result.toString())
}

private fun normalizeRenpyWhitespace(text: String): String {
    val result = StringBuilder(text.length)
    var pendingSpace = false
    for (current in text) {
        if (isRenpySpace(current)) {
            if (result.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) {
                result.append(' ')
                pendingSpace = false
            }
            result.append(current)
        }
    }
    return result.toString()
}

private fun normalizeRenpySpeakerToken(code: String): String {
    val trimmed = code.trimStart()
    if (trimmed.startsWith("\"") || trimmed.startsWith("r\"")) return code
    var index = 0
    while (index < code.length && isRenpySpace(code[index])) index++
    val start = index
    if (index >= code.length || !(isRenpyAsciiLetter(code[index]) || code[index] == '_')) {
        return code
    }
    index++
    while (index < code.length &&
        (isRenpyAsciiLetter(code[index]) || isRenpyAsciiDigit(code[index]) || code[index] == '_')
    ) {
        index++
    }
    return code.substring(0, start) + "<SPEAKER>" + code.substring(index)
}

private fun collectRenpyUnits(parsed: RenpyParsed, tag: String): List<FormatUnit> {
    val units = ArrayList<FormatUnit>()
    for (block in parsed.blocks) {
        if (block.kind != RenpyBlockKind.LABEL && block.kind != RenpyBlockKind.STRINGS) continue
        val mapping = if (block.kind == RenpyBlockKind.STRINGS) {
            pairRenpyOldNew(block)
        } else {
            matchRenpyTemplates(block)
        }
        if (mapping.isEmpty()) continue
        for ((templateRow, targetRow) in mapping) {
            val template = block.byRow[templateRow] ?: continue
            val target = block.byRow[targetRow] ?: continue
            val unit = buildRenpyUnit(block, template, target, tag) ?: continue
            if (units.size >= TransRenpyFormatBounds.MAX_UNITS) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, template.row)
            }
            units.add(unit)
        }
    }
    return units
}

private fun buildRenpyUnit(
    block: RenpyBlock,
    template: RenpyStatement,
    target: RenpyStatement,
    tag: String,
): FormatUnit? {
    if (template.hasUnsupported || target.hasUnsupported) return null
    val slots = selectRenpySlots(block, template)
    if (slots.isEmpty()) return null
    val nameSlot = slots.firstOrNull { it.role == RenpySlotRole.NAME }
    val textSlot = slots.firstOrNull {
        it.role == RenpySlotRole.DIALOGUE || it.role == RenpySlotRole.STRING
    } ?: return null
    if (textSlot.litIndex < 0 ||
        textSlot.litIndex >= template.literals.size ||
        textSlot.litIndex >= target.literals.size
    ) {
        return null
    }
    if (nameSlot != null &&
        (nameSlot.litIndex < 0 ||
            nameSlot.litIndex >= template.literals.size ||
            nameSlot.litIndex >= target.literals.size)
    ) {
        return null
    }
    val source = template.literals.getOrNull(textSlot.litIndex)?.value ?: return null
    if (source.isEmpty()) return null
    val translation = target.literals.getOrNull(textSlot.litIndex)?.value ?: ""
    val nameSource = nameSlot?.let { template.literals.getOrNull(it.litIndex)?.value }
    val targetName = nameSlot?.let { target.literals.getOrNull(it.litIndex)?.value } ?: ""
    val nameTranslation = if (targetName.isEmpty() || targetName == nameSource) null else targetName
    TransRenpyFormatBounds.requireUnitText(source, template.row)
    TransRenpyFormatBounds.requireUnitText(translation, template.row)
    if ((nameSource?.length ?: 0) > 4_096 || (nameTranslation?.length ?: 0) > 4_096) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, template.row)
    }
    nameSource?.let { TransRenpyFormatBounds.requireUnitText(it, template.row) }
    nameTranslation?.let { TransRenpyFormatBounds.requireUnitText(it, template.row) }
    val extra = buildRenpyExtra(block, template, target, slots)
    if (extra.length > TransRenpyFormatBounds.MAX_EXTRA_CHARS) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, template.row)
    }
    try {
        JsonObjectValue.parse(extra, JsonInputLimits(TransRenpyFormatBounds.MAX_EXTRA_CHARS, 32))
    } catch (failure: InvalidJsonPayload) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED, template.row)
    }
    val status = if ((translation.isNotEmpty() && source != translation) || nameTranslation != null) {
        TextItemStatus.PROCESSED
    } else {
        TextItemStatus.NONE
    }
    return FormatUnit(
        row = template.row,
        source = source,
        translation = translation,
        status = status,
        tag = tag,
        nameSource = nameSource?.let { listOf(it) },
        nameTranslation = nameTranslation?.let { listOf(it) },
        extraField = extra,
    )
}

private fun selectRenpySlots(block: RenpyBlock, statement: RenpyStatement): List<RenpySlot> {
    if (statement.hasUnsupported || statement.literals.isEmpty()) return emptyList()
    return when (block.kind) {
        RenpyBlockKind.STRINGS -> selectRenpyStringsSlots(statement)
        RenpyBlockKind.LABEL -> selectRenpyLabelSlots(statement)
        else -> emptyList()
    }
}

private fun selectRenpyStringsSlots(statement: RenpyStatement): List<RenpySlot> {
    if (!statement.code.trim().startsWith("old ")) return emptyList()
    val first = statement.literals.firstOrNull() ?: return emptyList()
    if (looksLikeRenpyResourceReference(first.value) || !isRenpyTranslatableText(first.value)) {
        return emptyList()
    }
    return listOf(RenpySlot(RenpySlotRole.STRING, 0))
}

private fun selectRenpyLabelSlots(statement: RenpyStatement): List<RenpySlot> {
    var nameIndex = findRenpyCharacterNameIndex(statement)
    val dialogueGroup = findRenpyDialogueGroup(statement, nameIndex)
    if (dialogueGroup.isEmpty()) return emptyList()
    val dialogueIndex = dialogueGroup.last()
    if (nameIndex == null && dialogueGroup.size >= 2) {
        nameIndex = dialogueGroup[dialogueGroup.size - 2]
    }
    val dialogueValue = statement.literals.getOrNull(dialogueIndex)?.value ?: return emptyList()
    if (looksLikeRenpyResourceReference(dialogueValue) || !isRenpyTranslatableText(dialogueValue)) {
        return emptyList()
    }
    val slots = ArrayList<RenpySlot>(2)
    if (nameIndex != null) {
        val nameValue = statement.literals.getOrNull(nameIndex)?.value ?: ""
        if (!looksLikeRenpyResourceReference(nameValue) && isRenpyTranslatableText(nameValue)) {
            slots.add(RenpySlot(RenpySlotRole.NAME, nameIndex))
        }
    }
    slots.add(RenpySlot(RenpySlotRole.DIALOGUE, dialogueIndex))
    return slots
}

private fun findRenpyCharacterNameIndex(statement: RenpyStatement): Int? {
    val stripped = statement.code.trimStart()
    if (!stripped.startsWith("Character(")) return null
    val open = statement.code.indexOf('(')
    val close = findRenpyMatchingParen(statement, open) ?: return null
    val index = statement.literals.indexOfFirst { it.startCol > open && it.startCol < close }
    return if (index >= 0) index else null
}

private fun findRenpyMatchingParen(statement: RenpyStatement, open: Int): Int? {
    if (open < 0) return null
    var literalIndex = 0
    var depth = 0
    var index = open
    while (index < statement.code.length) {
        val literal = statement.literals.getOrNull(literalIndex)
        if (literal != null && index == literal.startCol) {
            index = literal.endCol
            literalIndex++
            continue
        }
        when (statement.code[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

private fun findRenpyDialogueGroup(statement: RenpyStatement, nameIndex: Int?): List<Int> {
    if (statement.literals.isEmpty()) return emptyList()
    val startCol = if (nameIndex == null) {
        0
    } else {
        val open = statement.code.indexOf('(')
        val close = findRenpyMatchingParen(statement, open) ?: return emptyList()
        close + 1
    }
    var startIndex = -1
    for (index in statement.literals.indices) {
        if (statement.literals[index].startCol >= startCol) {
            startIndex = index
            break
        }
    }
    if (startIndex < 0) return emptyList()
    val group = ArrayList<Int>()
    group.add(startIndex)
    var index = startIndex + 1
    while (index < statement.literals.size) {
        val previous = statement.literals[index - 1]
        val current = statement.literals[index]
        if (statement.code.substring(previous.endCol, current.startCol).trim().isNotEmpty()) break
        group.add(index)
        index++
    }
    return group
}

private fun pairRenpyOldNew(block: RenpyBlock): Map<Int, Int> {
    val mapping = LinkedHashMap<Int, Int>()
    var pendingOld: Int? = null
    for (statement in block.statements) {
        val code = statement.code.trim()
        if (statement.kind == RenpyStatementKind.TEMPLATE && code.startsWith("old ")) {
            pendingOld = statement.row
            continue
        }
        if (statement.kind == RenpyStatementKind.TARGET && code.startsWith("new ")) {
            val oldRow = pendingOld ?: continue
            mapping[oldRow] = statement.row
            pendingOld = null
        }
    }
    return mapping
}

private fun matchRenpyTemplates(block: RenpyBlock): Map<Int, Int> {
    val templates = block.statements.filter {
        it.kind == RenpyStatementKind.TEMPLATE && it.strictKey.isNotEmpty() && !it.hasUnsupported
    }
    val targets = block.statements.filter {
        it.kind == RenpyStatementKind.TARGET && it.strictKey.isNotEmpty() && !it.hasUnsupported
    }
    if (templates.isEmpty() || targets.isEmpty()) return emptyMap()
    if (templates.size.toLong() * targets.size.toLong() > RENPY_MAX_MATCH_CELLS) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.LIMIT, block.headerRow)
    }
    val templateSignatures = templates.map { renpySignature(it) }
    val targetSignatures = targets.map { renpySignature(it) }
    val dp = Array(templates.size + 1) { IntArray(targets.size + 1) }
    for (templateIndex in templates.size - 1 downTo 0) {
        for (targetIndex in targets.size - 1 downTo 0) {
            dp[templateIndex][targetIndex] = if (renpyStatementsEqual(
                    templates[templateIndex],
                    targets[targetIndex],
                    templateSignatures[templateIndex],
                    targetSignatures[targetIndex],
                )
            ) {
                dp[templateIndex + 1][targetIndex + 1] + 1
            } else {
                maxOf(dp[templateIndex + 1][targetIndex], dp[templateIndex][targetIndex + 1])
            }
        }
    }
    val mapping = LinkedHashMap<Int, Int>()
    var templateIndex = 0
    var targetIndex = 0
    while (templateIndex < templates.size && targetIndex < targets.size) {
        if (renpyStatementsEqual(
                templates[templateIndex],
                targets[targetIndex],
                templateSignatures[templateIndex],
                targetSignatures[targetIndex],
            )
        ) {
            mapping[templates[templateIndex].row] = targets[targetIndex].row
            templateIndex++
            targetIndex++
        } else if (dp[templateIndex + 1][targetIndex] >= dp[templateIndex][targetIndex + 1]) {
            templateIndex++
        } else {
            targetIndex++
        }
    }
    return mapping
}

private fun renpySignature(statement: RenpyStatement): RenpySignature {
    if (statement.blockKind != RenpyBlockKind.LABEL || statement.literals.isEmpty()) {
        return RenpySignature(statement.literals.size, statement.strictKey, statement.relaxedKey)
    }
    val matchEnd = findRenpyLabelMatchEnd(statement)
    if (matchEnd >= statement.code.length) {
        return RenpySignature(statement.literals.size, statement.strictKey, statement.relaxedKey)
    }
    val matchedCode = statement.code.substring(0, matchEnd)
    val matchedLiterals = statement.literals.filter { it.endCol <= matchEnd }
    val strictKey = buildRenpySkeleton(matchedCode, matchedLiterals)
    return RenpySignature(
        stringCount = matchedLiterals.size,
        strictKey = strictKey,
        relaxedKey = normalizeRenpyWhitespace(normalizeRenpySpeakerToken(strictKey)),
    )
}

private fun findRenpyLabelMatchEnd(statement: RenpyStatement): Int {
    val nameIndex = findRenpyCharacterNameIndex(statement)
    val dialogueGroup = findRenpyDialogueGroup(statement, nameIndex)
    val dialogueIndex = dialogueGroup.lastOrNull() ?: return statement.code.length
    return statement.literals.getOrNull(dialogueIndex)?.endCol ?: statement.code.length
}

private fun renpyStatementsEqual(
    template: RenpyStatement,
    target: RenpyStatement,
    templateSignature: RenpySignature,
    targetSignature: RenpySignature,
): Boolean {
    if (templateSignature.stringCount != targetSignature.stringCount) return false
    if (templateSignature.strictKey == targetSignature.strictKey) return true
    if (!renpySpeakersCompatible(template, target)) return false
    if (templateSignature.relaxedKey == targetSignature.relaxedKey) return true
    if (templateSignature.strictKey == dropRenpyNormalizedSpeaker(targetSignature.relaxedKey)) {
        return true
    }
    return dropRenpyNormalizedSpeaker(templateSignature.relaxedKey) == targetSignature.strictKey
}

private fun renpySpeakersCompatible(template: RenpyStatement, target: RenpyStatement): Boolean {
    val templateSpeaker = renpySpeakerToken(template)
    val targetSpeaker = renpySpeakerToken(target)
    if (templateSpeaker == null && targetSpeaker == null) return true
    return templateSpeaker == targetSpeaker
}

private fun renpySpeakerToken(statement: RenpyStatement): String? {
    val stripped = statement.code.trimStart()
    if (stripped.startsWith("\"") || stripped.startsWith("Character(")) return null
    var index = 0
    while (index < statement.code.length && isRenpySpace(statement.code[index])) index++
    if (index >= statement.code.length ||
        !(isRenpyAsciiLetter(statement.code[index]) || statement.code[index] == '_')
    ) {
        return null
    }
    val start = index
    index++
    while (index < statement.code.length &&
        (isRenpyAsciiLetter(statement.code[index]) ||
            isRenpyAsciiDigit(statement.code[index]) || statement.code[index] == '_')
    ) {
        index++
    }
    return statement.code.substring(start, index)
}

private fun dropRenpyNormalizedSpeaker(key: String): String =
    if (key.startsWith("<SPEAKER> ")) key.substring("<SPEAKER> ".length) else key

private fun resolveRenpyLocator(
    unit: FormatUnit,
    parsed: RenpyParsed,
    canonical: () -> Map<Int, List<FormatUnit>>,
): RenpyLocator {
    val renpy = parseRenpyExtra(unit.extraField)
    if (renpy != null && isCurrentRenpyExtra(renpy)) {
        return verifyRenpyLocator(unit, parsed, renpy)
    }
    val candidates = canonical()[unit.row].orEmpty().filter { candidate ->
        candidate.source == unit.source &&
            (unit.nameSource == null || candidate.nameSource == unit.nameSource)
    }
    if (candidates.size != 1) {
        throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    }
    val canonicalExtra = parseRenpyExtra(candidates[0].extraField)
        ?: throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR, unit.row)
    return verifyRenpyLocator(unit, parsed, canonicalExtra)
}

private fun parseRenpyExtra(extra: String): JsonObject? = try {
    JsonObjectValue.parse(
        extra,
        JsonInputLimits(TransRenpyFormatBounds.MAX_EXTRA_CHARS, 32),
    ).json["renpy"] as? JsonObject
} catch (_: InvalidJsonPayload) {
    null
}

private fun isCurrentRenpyExtra(renpy: JsonObject): Boolean {
    if (intRenpyField(renpy, "v") != 1) return false
    val block = renpy["block"] as? JsonObject ?: return false
    val pair = renpy["pair"] as? JsonObject ?: return false
    val digest = renpy["digest"] as? JsonObject ?: return false
    if (renpy["slots"] !is JsonArray) return false
    return stringRenpyField(block, "lang") != null &&
        stringRenpyField(block, "label") != null &&
        stringRenpyField(block, "kind") != null &&
        intRenpyField(block, "header_line") != null &&
        intRenpyField(pair, "template_line") != null &&
        intRenpyField(pair, "target_line") != null &&
        stringRenpyField(digest, "template_raw_sha256") != null &&
        stringRenpyField(digest, "template_raw_rstrip_sha256") != null &&
        stringRenpyField(digest, "target_skeleton_sha256") != null &&
        intRenpyField(digest, "target_string_count") != null
}

private fun verifyRenpyLocator(
    unit: FormatUnit,
    parsed: RenpyParsed,
    renpy: JsonObject,
): RenpyLocator {
    if (intRenpyField(renpy, "v") != 1) staleRenpyLocator(unit)
    val blockJson = renpy["block"] as? JsonObject ?: staleRenpyLocator(unit)
    val pairJson = renpy["pair"] as? JsonObject ?: staleRenpyLocator(unit)
    val digestJson = renpy["digest"] as? JsonObject ?: staleRenpyLocator(unit)
    val slotsJson = renpy["slots"] as? JsonArray ?: staleRenpyLocator(unit)
    val headerLine = intRenpyField(blockJson, "header_line") ?: staleRenpyLocator(unit)
    val lang = stringRenpyField(blockJson, "lang") ?: staleRenpyLocator(unit)
    val label = stringRenpyField(blockJson, "label") ?: staleRenpyLocator(unit)
    val kindName = stringRenpyField(blockJson, "kind") ?: staleRenpyLocator(unit)
    val kind = when (kindName) {
        "LABEL" -> RenpyBlockKind.LABEL
        "STRINGS" -> RenpyBlockKind.STRINGS
        else -> staleRenpyLocator(unit)
    }
    val templateLine = intRenpyField(pairJson, "template_line") ?: staleRenpyLocator(unit)
    val targetLine = intRenpyField(pairJson, "target_line") ?: staleRenpyLocator(unit)
    val templateSha = stringRenpyField(digestJson, "template_raw_sha256") ?: staleRenpyLocator(unit)
    val rstripSha = stringRenpyField(digestJson, "template_raw_rstrip_sha256") ?: staleRenpyLocator(unit)
    val skeletonSha = stringRenpyField(digestJson, "target_skeleton_sha256") ?: staleRenpyLocator(unit)
    val targetCount = intRenpyField(digestJson, "target_string_count") ?: staleRenpyLocator(unit)

    val block = parsed.blockByHeader[headerLine] ?: staleRenpyLocator(unit)
    if (block.lang != lang || block.label != label || block.kind != kind) staleRenpyLocator(unit)
    if (templateLine <= block.headerRow || templateLine > block.endRow ||
        targetLine <= block.headerRow || targetLine > block.endRow
    ) {
        staleRenpyLocator(unit)
    }
    val template = block.byRow[templateLine] ?: staleRenpyLocator(unit)
    val target = block.byRow[targetLine] ?: staleRenpyLocator(unit)
    if (template.hasUnsupported || target.hasUnsupported) staleRenpyLocator(unit)
    if (kind == RenpyBlockKind.STRINGS) {
        if (template.kind != RenpyStatementKind.TEMPLATE ||
            !template.code.trim().startsWith("old ")
        ) {
            staleRenpyLocator(unit)
        }
        if (target.kind != RenpyStatementKind.TARGET ||
            !target.code.trim().startsWith("new ")
        ) {
            staleRenpyLocator(unit)
        }
    } else {
        if (template.kind != RenpyStatementKind.TEMPLATE || !isRenpyCommentLine(template.raw)) {
            staleRenpyLocator(unit)
        }
        if (target.kind != RenpyStatementKind.TARGET) staleRenpyLocator(unit)
    }
    if (unit.row != 0 && unit.row != templateLine) staleRenpyLocator(unit)
    if (sha256Hex(template.raw) != templateSha) staleRenpyLocator(unit)
    if (sha256Hex(template.raw.trimEnd()) != rstripSha) staleRenpyLocator(unit)
    if (sha256Hex(buildRenpySkeleton(target.code, target.literals)) != skeletonSha) {
        staleRenpyLocator(unit)
    }
    if (target.literals.size != targetCount) staleRenpyLocator(unit)

    val slots = ArrayList<RenpySlot>(slotsJson.size)
    for (element in slotsJson) {
        val slot = element as? JsonObject ?: staleRenpyLocator(unit)
        val role = when (stringRenpyField(slot, "role")) {
            "NAME" -> RenpySlotRole.NAME
            "DIALOGUE" -> RenpySlotRole.DIALOGUE
            "STRING" -> RenpySlotRole.STRING
            else -> staleRenpyLocator(unit)
        }
        val litIndex = intRenpyField(slot, "lit_index") ?: staleRenpyLocator(unit)
        slots.add(RenpySlot(role, litIndex))
    }
    if (slots.isEmpty()) staleRenpyLocator(unit)
    if (slots.map { it.litIndex }.toSet().size != slots.size) staleRenpyLocator(unit)
    if (kind == RenpyBlockKind.STRINGS) {
        if (slots.any { it.role != RenpySlotRole.STRING }) staleRenpyLocator(unit)
    } else if (slots.any { it.role == RenpySlotRole.STRING }) {
        staleRenpyLocator(unit)
    }
    val textSlot = slots.firstOrNull {
        it.role == RenpySlotRole.DIALOGUE || it.role == RenpySlotRole.STRING
    } ?: staleRenpyLocator(unit)
    if (slots.count { it.role == RenpySlotRole.DIALOGUE || it.role == RenpySlotRole.STRING } != 1) {
        staleRenpyLocator(unit)
    }
    if (slots.count { it.role == RenpySlotRole.NAME } > 1) staleRenpyLocator(unit)
    if (textSlot.litIndex < 0 || textSlot.litIndex >= template.literals.size) {
        staleRenpyLocator(unit)
    }
    if (template.literals[textSlot.litIndex].value != unit.source) staleRenpyLocator(unit)
    val nameSlot = slots.firstOrNull { it.role == RenpySlotRole.NAME }
    if (nameSlot != null) {
        val sourceName = unit.nameSource?.firstOrNull()
        if (sourceName != null) {
            if (nameSlot.litIndex < 0 || nameSlot.litIndex >= template.literals.size) {
                staleRenpyLocator(unit)
            }
            if (template.literals[nameSlot.litIndex].value != sourceName) staleRenpyLocator(unit)
        }
    }

    val baseCode = if (kind == RenpyBlockKind.STRINGS) target.code else template.code
    val baseScan = scanRenpyLineLiterals(
        baseCode,
        template.row,
        stopAtComment = kind == RenpyBlockKind.STRINGS,
    )
    if (baseScan.unsupported) staleRenpyLocator(unit)
    val baseLiterals = baseScan.literals
    for (slot in slots) {
        if (slot.litIndex < 0 ||
            slot.litIndex >= target.literals.size ||
            slot.litIndex >= baseLiterals.size
        ) {
            staleRenpyLocator(unit)
        }
    }
    return RenpyLocator(target, slots, baseCode, baseLiterals)
}

private fun staleRenpyLocator(unit: FormatUnit): Nothing =
    throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.STALE_LOCATOR, unit.row)

private fun intRenpyField(value: JsonObject, key: String): Int? {
    val primitive = value[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}

private fun stringRenpyField(value: JsonObject, key: String): String? {
    val primitive = value[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

private fun isRenpyCommentLine(raw: String): Boolean {
    val indent = leadingIndentLength(raw)
    return indent < raw.length && raw[indent] == '#'
}

private fun resolveRenpyName(unit: FormatUnit, options: FormatWriteOptions): String? {
    val source = unit.nameSource?.firstOrNull().orEmpty()
    if (!options.writeTranslatedNames) return if (source.isEmpty()) null else source
    val translated = unit.nameTranslation?.firstOrNull().orEmpty()
    val resolved = if (translated.isNotEmpty()) translated else source
    return if (resolved.isEmpty()) null else resolved
}

private fun replaceRenpyLiterals(
    baseCode: String,
    literals: List<RenpyLiteral>,
    replacements: Map<Int, String>,
): String {
    val result = StringBuilder(baseCode.length + 16)
    var cursor = 0
    for ((index, literal) in literals.withIndex()) {
        result.append(baseCode, cursor, literal.startCol)
        val replacement = replacements[index]
        if (replacement == null || replacement == literal.value) {
            result.append(baseCode, literal.startCol, literal.endCol)
        } else {
            result.append('"')
            appendRenpyEscaped(result, replacement)
            result.append('"')
        }
        cursor = literal.endCol
    }
    result.append(baseCode, cursor, baseCode.length)
    return result.toString()
}

/** Output length of [appendRenpyEscaped] plus the two surrounding quotes are added by callers. */
private fun renpyEscapedLength(text: String): Long {
    var length = 0L
    var index = 0
    while (index < text.length) {
        val current = text[index]
        when {
            current == '\\' || current == '"' || current == '\n' -> {
                length += 2L
                index++
            }
            current == ' ' -> {
                var end = index + 1
                while (end < text.length && text[end] == ' ') end++
                length += 1L + (end - index - 1).toLong() * 2L
                index = end
            }
            current.isISOControl() || current == '\u2028' || current == '\u2029' -> {
                length += 6L
                index++
            }
            else -> {
                length += 1L
                index++
            }
        }
    }
    return length
}

private fun appendRenpyEscaped(result: StringBuilder, text: String) {
    var index = 0
    while (index < text.length) {
        val current = text[index]
        when {
            current == '\\' -> result.append("\\\\")
            current == '"' -> result.append("\\\"")
            current == '\n' -> result.append("\\n")
            current == ' ' -> {
                var end = index + 1
                while (end < text.length && text[end] == ' ') end++
                result.append(' ')
                var repeat = index + 1
                while (repeat < end) {
                    result.append("\\ ")
                    repeat++
                }
                index = end
                continue
            }
            current.isISOControl() || current == '\u2028' || current == '\u2029' -> {
                result.append("\\u")
                result.append(current.toInt().toString(16).padStart(4, '0'))
            }
            else -> result.append(current)
        }
        index++
    }
}

private fun applyRenpyEdits(original: String, edits: List<RenpyLineEdit>): String {
    val sorted = edits.sortedBy { it.start }
    val result = StringBuilder(original.length + 32)
    var cursor = 0
    for (edit in sorted) {
        if (edit.start < cursor || edit.end < edit.start || edit.end > original.length) {
            throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.MALFORMED)
        }
        result.append(original, cursor, edit.start)
        result.append(edit.replacement)
        cursor = edit.end
    }
    result.append(original, cursor, original.length)
    val output = result.toString()
    TransRenpyFormatBounds.requireOutput(output)
    return output
}

private fun buildRenpyExtra(
    block: RenpyBlock,
    template: RenpyStatement,
    target: RenpyStatement,
    slots: List<RenpySlot>,
): String = buildJsonObject {
    put(
        "renpy",
        buildJsonObject {
            put("v", 1)
            put(
                "block",
                buildJsonObject {
                    put("lang", block.lang)
                    put("label", block.label)
                    put("kind", block.kind.name)
                    put("header_line", block.headerRow)
                },
            )
            put(
                "pair",
                buildJsonObject {
                    put("template_line", template.row)
                    put("target_line", target.row)
                },
            )
            put(
                "slots",
                JsonArray(
                    slots.map { slot ->
                        buildJsonObject {
                            put("role", slot.role.name)
                            put("lit_index", slot.litIndex)
                        }
                    },
                ),
            )
            put(
                "digest",
                buildJsonObject {
                    put("template_raw_sha256", sha256Hex(template.raw))
                    put("template_raw_rstrip_sha256", sha256Hex(template.raw.trimEnd()))
                    put("target_skeleton_sha256", sha256Hex(target.strictKey))
                    put("target_string_count", target.literals.size)
                },
            )
        },
    )
}.toString()

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(TransRenpyFormatBounds.strictUtf8(value))
    val result = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val number = byte.toInt() and 255
        result.append(HEX_DIGITS[number ushr 4])
        result.append(HEX_DIGITS[number and 15])
    }
    return result.toString()
}

private fun unescapeRenpyString(raw: String, row: Int): String {
    val collapsed = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val current = raw[index]
        if (current == ' ' || current == '\n') {
            var end = index + 1
            while (end < raw.length && (raw[end] == ' ' || raw[end] == '\n')) end++
            collapsed.append(' ')
            index = end
            continue
        }
        collapsed.append(current)
        index++
    }
    val result = StringBuilder(collapsed.length)
    index = 0
    while (index < collapsed.length) {
        val current = collapsed[index]
        if (current != '\\' || index + 1 >= collapsed.length) {
            result.append(current)
            index++
            continue
        }
        val next = collapsed[index + 1]
        if (next == 'u') {
            var end = index + 2
            while (end < collapsed.length && end < index + 6 && isHexDigit(collapsed[end])) end++
            if (end == index + 2) {
                throw InvalidTransRenpyFormat(InvalidTransRenpyFormat.Reason.INVALID_ESCAPE, row)
            }
            val code = collapsed.substring(index + 2, end).toInt(16)
            result.append(code.toChar())
            index = end
            continue
        }
        val mapped = when (next) {
            'n' -> "\n"
            '{' -> "{{"
            '[' -> "[["
            '%' -> "%%"
            else -> next.toString()
        }
        result.append(mapped)
        index += 2
    }
    return result.toString()
}

private fun isRenpyTranslatableText(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (isRenpyPlaceholderOnly(trimmed)) return false
    if (trimmed.startsWith("{#")) return true
    if (trimmed.startsWith("{image=", ignoreCase = true)) return true
    return stripRenpyTags(text).trim().isNotEmpty()
}

private fun isRenpyPlaceholderOnly(trimmed: String): Boolean {
    if (trimmed.length < 3 || trimmed[0] != '[' || trimmed[trimmed.length - 1] != ']') return false
    for (index in 1 until trimmed.length - 1) {
        if (trimmed[index] == ']') return false
    }
    return true
}

private fun stripRenpyTags(text: String): String {
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val open = text[index]
        if (open == '{' || open == '[') {
            val close = if (open == '{') '}' else ']'
            var scan = index + 1
            while (scan < text.length && text[scan] != open && text[scan] != close) scan++
            if (scan < text.length && text[scan] == close) {
                index = scan + 1
                continue
            }
        }
        result.append(open)
        index++
    }
    return result.toString()
}

private fun looksLikeRenpyResourceReference(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    // A pure resource reference is a short token. This also keeps the path/URI patterns away
    // from a long dialogue body, where backtracking would be wasted work.
    if (trimmed.length > 4_096) return false
    return removeRenpyResourceReferences(trimmed).trim().isEmpty()
}

private fun removeRenpyResourceReferences(text: String): String {
    val ranges = collectRenpyResourceRanges(text)
    if (ranges.isEmpty()) return text
    val result = StringBuilder(text.length)
    var cursor = 0
    for (range in ranges) {
        if (range.start < cursor) continue
        result.append(text, cursor, range.start)
        cursor = range.end
    }
    result.append(text, cursor, text.length)
    return result.toString()
}

private fun collectRenpyResourceRanges(text: String): List<RenpyRange> {
    val candidates = ArrayList<RenpyCandidate>()
    collectRenpyPattern(text, RENPY_DATA_URI, 0, candidates)
    collectRenpyPattern(text, RENPY_URI, 1, candidates)
    collectRenpyPattern(text, RENPY_RESOURCE_PATH, 2, candidates)
    candidates.sortWith(
        compareBy<RenpyCandidate> { it.start }
            .thenBy { it.priority }
            .thenByDescending { it.end },
    )
    val ranges = ArrayList<RenpyRange>()
    var cursor = 0
    for (candidate in candidates) {
        if (candidate.start < cursor || candidate.end <= candidate.start) continue
        ranges.add(RenpyRange(candidate.start, candidate.end))
        cursor = candidate.end
    }
    return ranges
}

private fun collectRenpyPattern(
    text: String,
    pattern: Regex,
    priority: Int,
    output: MutableList<RenpyCandidate>,
) {
    for (match in pattern.findAll(text)) {
        val value = match.value
        if (value.isEmpty()) continue
        val start = match.range.first
        val end = trimRenpyReferenceEnd(text, start, match.range.last + 1)
        if (end > start) output.add(RenpyCandidate(start, end, priority))
    }
}

private fun trimRenpyReferenceEnd(text: String, start: Int, initialEnd: Int): Int {
    var end = initialEnd
    while (end > start) {
        val last = text[end - 1]
        if (last in RENPY_SIMPLE_TRAILING_PUNCTUATION) {
            end--
            continue
        }
        val opening = RENPY_CLOSING_TO_OPENING[last]
        if (opening == null) break
        if (countRenpyChar(text, start, end, last) <= countRenpyChar(text, start, end, opening)) break
        end--
    }
    return end
}

private fun countRenpyChar(text: String, start: Int, end: Int, character: Char): Int {
    var count = 0
    for (index in start until end) if (text[index] == character) count++
    return count
}

private fun leadingIndentLength(raw: String): Int {
    var index = 0
    while (index < raw.length && (raw[index] == ' ' || raw[index] == '\t')) index++
    return index
}

private fun leadingIndentLength(text: String, start: Int, end: Int): Int {
    var index = start
    while (index < end && (text[index] == ' ' || text[index] == '\t')) index++
    return index - start
}

private fun isRenpySpace(character: Char): Boolean =
    character.isWhitespace() || character == '\u00A0' || character == '\uFEFF'

private fun isRenpySplitLineBreak(character: Char): Boolean = when (character) {
    '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u0085',
    '\u2028', '\u2029' -> true
    else -> false
}

private fun isRenpyAsciiLetter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z'

private fun isRenpyAsciiDigit(character: Char): Boolean = character in '0'..'9'

private fun isRenpyAsciiWord(character: Char): Boolean =
    isRenpyAsciiLetter(character) || isRenpyAsciiDigit(character) || character == '_'

private fun isRenpyIdentifierPart(character: Char): Boolean =
    character == '_' || character.isLetterOrDigit()

private fun isHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

private val RENPY_SIMPLE_TRAILING_PUNCTUATION = setOf(
    '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？', '、',
)

private val RENPY_CLOSING_TO_OPENING = mapOf(
    ')' to '(', ']' to '[', '}' to '{',
    '）' to '（', '】' to '【', '」' to '「', '』' to '『',
)

private val RENPY_RESOURCE_EXTENSIONS = listOf(
    "mp3", "wav", "ogg", "mid", "flac", "opus", "m4a", "aac",
    "png", "jpg", "jpeg", "gif", "psd", "webp", "heif", "heic", "bmp", "svg", "ico", "avif",
    "avi", "mp4", "webm", "mkv", "mov",
    "ttf", "otf", "woff", "woff2",
    "txt", "json", "sav", "mps", "xml", "yaml", "yml", "csv", "bin",
    "md", "pdf", "html", "htm", "xhtml", "xhtm", "doc", "docx", "xls", "xlsx",
    "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub",
    "7z", "gz", "rar", "zip", "tar",
)

private val RENPY_DATA_URI = Regex(
    "\\bdata:[a-z][a-z\\d.+-]*/[a-z\\d.+-]+" +
        "(?:;[a-z\\d!#\\$&^_.+-]+(?:=[a-z\\d!#\\$&^_.+%-]*)?)*;base64,[a-z\\d+/=_-]+",
    RegexOption.IGNORE_CASE,
)

private val RENPY_URI = Regex(
    "\\b[a-z][a-z\\d+.-]*://[^\\s<>\"'\\x60，。；：！？、（）【】「」『』]+",
    RegexOption.IGNORE_CASE,
)

private val RENPY_RESOURCE_PATH = Regex(
    "[\\p{L}\\p{M}\\p{N}_@%+~.-]+(?:[\\\\/][\\p{L}\\p{M}\\p{N}_@%+~.-]+)*\\.(?:" +
        RENPY_RESOURCE_EXTENSIONS.joinToString("|") +
        ")(?![\\p{L}\\p{M}\\p{N}_-])(?:[?#][^\\s<>\"'\\x60，。；：！？、（）【】「」『』]+)?",
    RegexOption.IGNORE_CASE,
)
