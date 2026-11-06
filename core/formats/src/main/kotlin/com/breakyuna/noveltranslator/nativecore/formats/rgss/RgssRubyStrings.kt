package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Classified Ruby-source scanning/binding failure. [offset] is a UTF-16 index when known. */
class RubySourceException(val reason: Reason, val offset: Int = -1, detail: String = "") :
    IllegalArgumentException(
        "Ruby source: $reason" +
            (if (offset < 0) "" else " at $offset") +
            (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason {
        UNTERMINATED_STRING,
        UNTERMINATED_COMMENT,
        INTERPOLATION,
        HEREDOC,
        REGEX,
        PERCENT_LITERAL,
        ESCAPE,
        ENCODING,
        LIMIT,
        BINDING,
    }
}

/**
 * One ordinary Ruby single/double-quoted string literal. [start] is the opening quote and [end] is
 * exclusive (just past the closing quote); both are UTF-16 indices into the scanned source. [raw]
 * includes both quotes and keeps the original escapes so a writer can bind to the exact slot.
 */
class RubyStringLiteral internal constructor(
    val start: Int,
    val end: Int,
    val quote: Char,
    val value: String,
    val raw: String,
) {
    init {
        require(start >= 0 && end > start && (quote == '\'' || quote == '"'))
    }
    override fun toString(): String = "RubyStringLiteral(start=$start,end=$end,quote=$quote)"
}

class RubyScanResult internal constructor(
    literals: List<RubyStringLiteral>,
    val skippedQuotedSymbols: Int,
) {
    val literals: List<RubyStringLiteral> = java.util.Collections.unmodifiableList(ArrayList(literals))
    init { require(this.literals.size <= 100_000) }
}

/** One validated literal rewrite. An empty [digest] disables the raw-digest binding check. */
class RubyReplacement(
    val start: Int,
    val end: Int,
    val quote: Char,
    val source: String,
    val translation: String,
    val digest: String = "",
) {
    init {
        require(start >= 0 && end > start && (quote == '\'' || quote == '"'))
        require(digest.isEmpty() || digest.length == 64)
    }
}

private const val MAX_SCAN_CHARS = 8 * 1024 * 1024
private const val MAX_LITERAL_CHARS = 65_536
private const val MAX_SCAN_LITERALS = 100_000
private val HEX = "0123456789abcdef".toCharArray()
private val EXPR_BEGIN_WORDS = setOf(
    "return", "yield", "super", "break", "next", "if", "unless", "while", "until",
    "when", "case", "in", "do", "then", "else", "elsif", "begin", "rescue", "ensure",
    "and", "or", "not", "def", "class", "module", "alias", "undef",
)
private val OPERATOR_CHARS = "=+-*&|^!~?<>".toSet()

private fun isIdentifierStart(character: Char): Boolean = character == '_' || character.isLetter()

private fun isIdentifierPart(character: Char): Boolean = character == '_' || character.isLetterOrDigit()

private fun isUnicodeSeparator(character: Char): Boolean =
    character == ' ' || character == '\t' || character == '\n' || character == '\r'

private fun hexValue(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> -1
}

private fun hex(value: Int, digits: Int): String {
    val text = value.toString(16)
    if (text.length >= digits) return text
    val output = StringBuilder(digits)
    repeat(digits - text.length) { output.append('0') }
    output.append(text)
    return output.toString()
}

private fun validateUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (codePoint in 0xD800..0xDFFF) {
            throw RubySourceException(RubySourceException.Reason.ENCODING, index, "surrogate")
        }
        index += Character.charCount(codePoint)
    }
}

private fun validateUnicodeAt(value: String, offset: Int) {
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (codePoint in 0xD800..0xDFFF) {
            throw RubySourceException(RubySourceException.Reason.ENCODING, offset, "surrogate")
        }
        index += Character.charCount(codePoint)
    }
}

/**
 * Conservative Ruby source string scanner. It is deliberately not a Ruby parser: it understands
 * comments, ordinary quoted strings (including common escapes), and enough token context to avoid
 * treating a regex/division slash or a percent literal as a string. Constructs whose byte/character
 * interpretation is not certain are rejected instead of rewritten: heredocs, interpolation, percent
 * literals, regex literals, unsupported escapes, and unterminated strings/comments. Quoted symbols
 * (`:"..."`) are skipped because they are identifiers, not display text.
 */
object RgssRubyStrings {
    fun scan(source: String): RubyScanResult {
        if (source.length > MAX_SCAN_CHARS) {
            throw RubySourceException(RubySourceException.Reason.LIMIT, -1, "source")
        }
        return Scanner(source).scan()
    }

    fun rewrite(source: String, replacements: List<RubyReplacement>): String {
        if (replacements.isEmpty()) return source
        val literals = scan(source).literals.associateBy { it.start }
        val accepted = ArrayList<Pair<RubyReplacement, RubyStringLiteral>>(replacements.size)
        var lastEnd = -1
        for (replacement in replacements.sortedBy { it.start }) {
            val literal = literals[replacement.start]
                ?: throw RubySourceException(RubySourceException.Reason.BINDING, replacement.start, "slot")
            if (literal.end != replacement.end || literal.quote != replacement.quote) {
                throw RubySourceException(RubySourceException.Reason.BINDING, replacement.start, "range")
            }
            if (literal.value != replacement.source) {
                throw RubySourceException(RubySourceException.Reason.BINDING, replacement.start, "source")
            }
            if (replacement.digest.isNotEmpty() && sha256Hex(literal.raw) != replacement.digest) {
                throw RubySourceException(RubySourceException.Reason.BINDING, replacement.start, "digest")
            }
            if (replacement.start < lastEnd) {
                throw RubySourceException(RubySourceException.Reason.BINDING, replacement.start, "overlap")
            }
            if (replacement.translation == replacement.source) continue
            accepted.add(replacement to literal)
            lastEnd = replacement.end
        }
        if (accepted.isEmpty()) return source
        val builder = StringBuilder(source)
        for ((replacement, literal) in accepted.asReversed()) {
            builder.replace(literal.start, literal.end, encodeLiteral(replacement.translation, literal.quote))
        }
        return builder.toString()
    }

    /**
     * Encode a replacement as a complete Ruby quoted literal. Double-quoted output uses canonical
     * escapes and escapes `#` before `{`, `@` or `$` so translated text cannot become interpolation.
     * Single-quoted output only escapes `\\` and `'`; other characters stay literal, as Ruby defines.
     */
    fun encodeLiteral(value: String, quote: Char): String {
        if (quote != '\'' && quote != '"') throw RubySourceException(RubySourceException.Reason.ESCAPE, -1, "quote")
        if ('\u0000' in value) throw RubySourceException(RubySourceException.Reason.ESCAPE, -1, "NUL")
        validateUnicode(value)
        val output = StringBuilder(value.length + 2)
        output.append(quote)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (codePoint in 0xD800..0xDFFF) {
                throw RubySourceException(RubySourceException.Reason.ENCODING, index, "surrogate")
            }
            if (quote == '\'') {
                when (codePoint) {
                    0x5C -> output.append("\\\\")
                    0x27 -> output.append("\\'")
                    else -> output.appendCodePoint(codePoint)
                }
            } else {
                when (codePoint) {
                    0x5C -> output.append("\\\\")
                    0x22 -> output.append("\\\"")
                    0x0A -> output.append("\\n")
                    0x0D -> output.append("\\r")
                    0x09 -> output.append("\\t")
                    0x08 -> output.append("\\b")
                    0x0C -> output.append("\\f")
                    0x0B -> output.append("\\v")
                    0x07 -> output.append("\\a")
                    0x1B -> output.append("\\e")
                    in 0x01..0x1F, 0x7F -> output.append("\\x").append(hex(codePoint, 2))
                    in 0x80..0x9F -> output.append("\\u").append(hex(codePoint, 4))
                    0x23 -> {
                        val next = if (index + width < value.length) value.codePointAt(index + width) else -1
                        if (next == 0x7B || next == 0x40 || next == 0x24) output.append("\\#") else output.append('#')
                    }
                    else -> output.appendCodePoint(codePoint)
                }
            }
            index += width
        }
        output.append(quote)
        return output.toString()
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val text = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            text.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return text.toString()
    }

    private class Scanner(private val source: String) {
        private val literals = ArrayList<RubyStringLiteral>()
        private var index = 0
        private var exprBegin = true
        private var lineStart = true
        private var skippedSymbols = 0

        fun scan(): RubyScanResult {
            while (index < source.length) {
                if (literals.size >= MAX_SCAN_LITERALS) {
                    throw RubySourceException(RubySourceException.Reason.LIMIT, index, "literals")
                }
                val character = source[index]
                when {
                    character == '\n' -> {
                        index += 1
                        lineStart = true
                        exprBegin = true
                    }
                    character == '\r' -> {
                        index += 1
                        if (index < source.length && source[index] == '\n') index += 1
                        lineStart = true
                        exprBegin = true
                    }
                    character == ' ' || character == '\t' || character == '\u000B' || character == '\u000C' -> {
                        index += 1
                    }
                    lineStart && startsWithWord("=begin", index) -> {
                        index = skipBlockComment(index)
                        lineStart = true
                        exprBegin = true
                    }
                    lineStart && startsWithWord("__END__", index) -> {
                        index = source.length
                    }
                    character == '#' -> {
                        index = skipLineComment(index)
                    }
                    character == '\'' || character == '"' -> {
                        val symbol = isQuotedSymbol(index)
                        val parsed = readQuoted(index, character)
                        if (symbol) {
                            skippedSymbols += 1
                        } else {
                            literals.add(parsed.literal)
                        }
                        index = parsed.end
                        lineStart = false
                        exprBegin = false
                    }
                    character == '`' -> {
                        index = skipBacktick(index)
                        lineStart = false
                        exprBegin = false
                    }
                    character == '/' -> {
                        handleSlash()
                    }
                    character == '%' -> {
                        handlePercent()
                    }
                    character == '<' && index + 1 < source.length && source[index + 1] == '<' -> {
                        handleHeredoc()
                    }
                    character == ':' -> {
                        handleColon()
                    }
                    character == '?' -> {
                        handleQuestion()
                    }
                    isIdentifierStart(character) || character == '@' || character == '$' -> {
                        val (word, end) = readWord(index)
                        index = end
                        exprBegin = word in EXPR_BEGIN_WORDS
                        lineStart = false
                    }
                    character.isDigit() -> {
                        index = readNumber(index)
                        lineStart = false
                        exprBegin = false
                    }
                    character == '(' || character == '[' || character == '{' -> {
                        index += 1
                        exprBegin = true
                        lineStart = false
                    }
                    character == ')' || character == ']' || character == '}' -> {
                        index += 1
                        exprBegin = false
                        lineStart = false
                    }
                    character == '.' -> {
                        index += 1
                        exprBegin = true
                        lineStart = false
                    }
                    character == ',' || character == ';' -> {
                        index += 1
                        exprBegin = true
                        lineStart = false
                    }
                    else -> {
                        index += 1
                        exprBegin = character in OPERATOR_CHARS
                        lineStart = false
                    }
                }
            }
            return RubyScanResult(literals, skippedSymbols)
        }

        private fun handleSlash() {
            if (index + 1 < source.length && source[index + 1] == '=') {
                index += 2
                exprBegin = true
                return
            }
            val previous = if (index > 0) source[index - 1] else ' '
            val next = if (index + 1 < source.length) source[index + 1] else ' '
            val looksLikeRegex = exprBegin ||
                (previous.isWhitespace() && !next.isWhitespace() && next != '=')
            if (looksLikeRegex) {
                throw RubySourceException(RubySourceException.Reason.REGEX, index)
            }
            index += 1
            exprBegin = true
        }

        private fun handlePercent() {
            val first = source.getOrNull(index + 1)
            if (first != null && first in "qQwWiIrsx") {
                val delimiter = source.getOrNull(index + 2)
                if (delimiter != null && !delimiter.isLetterOrDigit() && !delimiter.isWhitespace() && delimiter != '=') {
                    throw RubySourceException(RubySourceException.Reason.PERCENT_LITERAL, index)
                }
            }
            if (first != null && first in "([{<|!/" && exprBegin) {
                throw RubySourceException(RubySourceException.Reason.PERCENT_LITERAL, index)
            }
            index += 1
            exprBegin = true
        }

        private fun handleHeredoc() {
            var cursor = index + 2
            var explicit = false
            when (source.getOrNull(cursor)) {
                '~', '-' -> {
                    explicit = true
                    cursor += 1
                }
                '\'', '"', '`' -> explicit = true
            }
            if (explicit) throw RubySourceException(RubySourceException.Reason.HEREDOC, index)
            val marker = source.getOrNull(cursor)
            if (marker != null && (marker == '_' || marker.isLetter())) {
                val previous = if (index > 0) source[index - 1] else ' '
                if (exprBegin || (previous.isWhitespace() && marker.isUpperCase())) {
                    throw RubySourceException(RubySourceException.Reason.HEREDOC, index)
                }
            }
            index += 2
            exprBegin = true
        }

        private fun handleColon() {
            if (index + 1 < source.length && source[index + 1] == ':') {
                index += 2
                exprBegin = true
                return
            }
            val next = source.getOrNull(index + 1)
            if (next != null && (next == '\'' || next == '"')) {
                val parsed = readQuoted(index + 1, next)
                skippedSymbols += 1
                index = parsed.end
                exprBegin = false
                lineStart = false
                return
            }
            if (next != null && (isIdentifierStart(next) || next == '@' || next == '$')) {
                var cursor = index + 2
                while (cursor < source.length && isIdentifierPart(source[cursor])) cursor += 1
                if (cursor < source.length && (source[cursor] == '?' || source[cursor] == '!')) cursor += 1
                index = cursor
                exprBegin = false
                lineStart = false
                return
            }
            index += 1
            exprBegin = true
        }

        private fun handleQuestion() {
            val next = source.getOrNull(index + 1)
            if (next != null && !next.isWhitespace()) {
                if (next == '\\' && index + 2 < source.length) {
                    index += 3
                } else {
                    index += 2
                }
                exprBegin = false
                lineStart = false
                return
            }
            index += 1
            exprBegin = true
        }

        private fun readQuoted(start: Int, quote: Char): Parsed {
            var cursor = start + 1
            val bytes = if (quote == '"') ByteArrayOutputStream() else null
            val chars = if (quote == '\'') StringBuilder() else null
            while (cursor < source.length) {
                val character = source[cursor]
                if (character == quote) {
                    val value = if (quote == '"') {
                        decodeUtf8OrNull(bytes!!.toByteArray())
                            ?: throw RubySourceException(RubySourceException.Reason.ENCODING, cursor, "literal")
                    } else {
                        chars!!.toString()
                    }
                    validateLiteral(value, cursor)
                    return Parsed(
                        RubyStringLiteral(start, cursor + 1, quote, value, source.substring(start, cursor + 1)),
                        cursor + 1,
                    )
                }
                when {
                    quote == '\'' && character == '\\' -> {
                        if (cursor + 1 >= source.length) {
                            throw RubySourceException(RubySourceException.Reason.UNTERMINATED_STRING, start)
                        }
                        val escaped = source[cursor + 1]
                        when (escaped) {
                            '\\' -> chars!!.append('\\')
                            '\'' -> chars!!.append('\'')
                            else -> {
                                chars!!.append('\\')
                                chars!!.append(escaped)
                            }
                        }
                        cursor += 2
                    }
                    quote == '"' && character == '\\' -> {
                        cursor = decodeDoubleEscape(cursor, bytes!!)
                    }
                    quote == '"' && character == '#' -> {
                        val next = source.getOrNull(cursor + 1)
                        if (next == '{' || next == '@' || next == '$') {
                            throw RubySourceException(RubySourceException.Reason.INTERPOLATION, cursor)
                        }
                        bytes!!.write(character.code)
                        cursor += 1
                    }
                    quote == '"' -> {
                        cursor += writeUtf8(bytes!!, source, cursor)
                    }
                    else -> {
                        if (Character.isHighSurrogate(character) && cursor + 1 < source.length &&
                            Character.isLowSurrogate(source[cursor + 1])
                        ) {
                            chars!!.append(character).append(source[cursor + 1])
                            cursor += 2
                        } else {
                            chars!!.append(character)
                            cursor += 1
                        }
                    }
                }
            }
            throw RubySourceException(RubySourceException.Reason.UNTERMINATED_STRING, start)
        }

        private fun decodeDoubleEscape(slash: Int, output: ByteArrayOutputStream): Int {
            if (slash + 1 >= source.length) {
                throw RubySourceException(RubySourceException.Reason.UNTERMINATED_STRING, slash)
            }
            val escaped = source[slash + 1]
            when (escaped) {
                'n' -> { output.write(0x0A); return slash + 2 }
                'r' -> { output.write(0x0D); return slash + 2 }
                't' -> { output.write(0x09); return slash + 2 }
                'b' -> { output.write(0x08); return slash + 2 }
                'f' -> { output.write(0x0C); return slash + 2 }
                'v' -> { output.write(0x0B); return slash + 2 }
                'a' -> { output.write(0x07); return slash + 2 }
                'e' -> { output.write(0x1B); return slash + 2 }
                's' -> { output.write(0x20); return slash + 2 }
                '\\', '"', '\'', '#' -> { output.write(escaped.code); return slash + 2 }
                '\n' -> return slash + 2
                '\r' -> return if (slash + 2 < source.length && source[slash + 2] == '\n') slash + 3 else slash + 2
                'x' -> {
                    if (slash + 3 >= source.length) {
                        throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "hex")
                    }
                    val high = hexValue(source[slash + 2])
                    val low = hexValue(source[slash + 3])
                    if (high < 0 || low < 0) {
                        throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "hex")
                    }
                    output.write((high shl 4) or low)
                    return slash + 4
                }
                'u' -> return decodeUnicodeEscape(slash, output)
                in '0'..'7' -> {
                    var cursor = slash + 1
                    var value = 0
                    var digits = 0
                    while (cursor < source.length && digits < 3 && source[cursor] in '0'..'7') {
                        value = value * 8 + (source[cursor] - '0')
                        cursor += 1
                        digits += 1
                    }
                    if (value > 0xFF) {
                        throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "octal")
                    }
                    output.write(value)
                    return cursor
                }
                'c', 'C', 'M' -> throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "control")
                else -> {
                    if (escaped.isLetter()) {
                        throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unknown")
                    }
                    output.write(escaped.code)
                    return slash + 2
                }
            }
        }

        private fun decodeUnicodeEscape(slash: Int, output: ByteArrayOutputStream): Int {
            var cursor = slash + 2
            if (cursor < source.length && source[cursor] == '{') {
                val close = source.indexOf('}', cursor + 1)
                if (close < 0) throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unicode")
                var tokenStart = cursor + 1
                while (tokenStart < close) {
                    while (tokenStart < close && isUnicodeSeparator(source[tokenStart])) tokenStart += 1
                    if (tokenStart >= close) break
                    var tokenEnd = tokenStart
                    while (tokenEnd < close && !isUnicodeSeparator(source[tokenEnd])) tokenEnd += 1
                    val token = source.substring(tokenStart, tokenEnd)
                    if (token.isEmpty() || token.length > 6) {
                        throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unicode")
                    }
                    val codePoint = token.toIntOrNull(16)
                        ?: throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unicode")
                    writeCodePoint(output, codePoint, slash)
                    tokenStart = tokenEnd
                }
                return close + 1
            }
            if (cursor + 4 > source.length) {
                throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unicode")
            }
            val codePoint = source.substring(cursor, cursor + 4).toIntOrNull(16)
                ?: throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "unicode")
            if (codePoint in 0xD800..0xDBFF) {
                if (cursor + 10 <= source.length && source[cursor + 4] == '\\' && source[cursor + 5] == 'u') {
                    val low = source.substring(cursor + 6, cursor + 10).toIntOrNull(16)
                    if (low != null && low in 0xDC00..0xDFFF) {
                        val combined = 0x10000 + ((codePoint - 0xD800) shl 10) + (low - 0xDC00)
                        writeCodePoint(output, combined, slash)
                        return cursor + 10
                    }
                }
                throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "surrogate")
            }
            if (codePoint in 0xDC00..0xDFFF) {
                throw RubySourceException(RubySourceException.Reason.ESCAPE, slash, "surrogate")
            }
            writeCodePoint(output, codePoint, slash)
            return cursor + 4
        }

        private fun writeCodePoint(output: ByteArrayOutputStream, codePoint: Int, offset: Int) {
            if (codePoint < 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) {
                throw RubySourceException(RubySourceException.Reason.ESCAPE, offset, "code point")
            }
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            output.write(bytes, 0, bytes.size)
        }

        private fun writeUtf8(output: ByteArrayOutputStream, text: String, start: Int): Int {
            val codePoint = text.codePointAt(start)
            val width = Character.charCount(codePoint)
            if (codePoint in 0xD800..0xDFFF) {
                throw RubySourceException(RubySourceException.Reason.ENCODING, start, "surrogate")
            }
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            output.write(bytes, 0, bytes.size)
            return width
        }

        private fun validateLiteral(value: String, offset: Int) {
            if (value.length > MAX_LITERAL_CHARS) {
                throw RubySourceException(RubySourceException.Reason.LIMIT, offset, "literal")
            }
            if ('\u0000' in value) {
                throw RubySourceException(RubySourceException.Reason.ESCAPE, offset, "NUL")
            }
            validateUnicodeAt(value, offset)
        }

        private fun skipBlockComment(start: Int): Int {
            var cursor = start
            while (cursor < source.length) {
                while (cursor < source.length && source[cursor] != '\n' && source[cursor] != '\r') cursor += 1
                if (cursor >= source.length) break
                cursor = consumeNewline(cursor)
                val lineStart = cursor
                var lineEnd = cursor
                while (lineEnd < source.length && source[lineEnd] != '\n' && source[lineEnd] != '\r') lineEnd += 1
                val line = source.substring(lineStart, lineEnd).trimStart()
                if (line == "=end" || line.startsWith("=end ") || line.startsWith("=end\t")) {
                    return lineEnd
                }
            }
            throw RubySourceException(RubySourceException.Reason.UNTERMINATED_COMMENT, start)
        }

        private fun skipLineComment(start: Int): Int {
            var cursor = start + 1
            while (cursor < source.length && source[cursor] != '\n' && source[cursor] != '\r') cursor += 1
            return cursor
        }

        private fun skipBacktick(start: Int): Int {
            var cursor = start + 1
            while (cursor < source.length) {
                when (source[cursor]) {
                    '\\' -> cursor += 2
                    '`' -> return cursor + 1
                    else -> cursor += 1
                }
            }
            throw RubySourceException(RubySourceException.Reason.UNTERMINATED_STRING, start, "backtick")
        }

        private fun readNumber(start: Int): Int {
            var cursor = start
            if (source[cursor] == '0' && cursor + 1 < source.length && source[cursor + 1] in "xXbBoO") {
                cursor += 2
                while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) {
                    cursor += 1
                }
                return cursor
            }
            while (cursor < source.length && (source[cursor].isDigit() || source[cursor] == '_')) cursor += 1
            if (cursor < source.length && source[cursor] == '.' &&
                !(cursor + 1 < source.length && source[cursor + 1] == '.')
            ) {
                cursor += 1
                while (cursor < source.length && (source[cursor].isDigit() || source[cursor] == '_')) cursor += 1
            }
            if (cursor < source.length && (source[cursor] == 'e' || source[cursor] == 'E')) {
                cursor += 1
                if (cursor < source.length && (source[cursor] == '+' || source[cursor] == '-')) cursor += 1
                while (cursor < source.length && (source[cursor].isDigit() || source[cursor] == '_')) cursor += 1
            }
            if (cursor < source.length && (source[cursor] == 'r' || source[cursor] == 'i')) cursor += 1
            return cursor
        }

        private fun readWord(start: Int): Pair<String, Int> {
            var cursor = start
            if (source[cursor] == '$') {
                cursor += 1
                if (cursor < source.length && !isIdentifierPart(source[cursor]) &&
                    source[cursor] != '_' && source[cursor] != '\\'
                ) {
                    // Ruby special globals such as $" and $' must not become string literals.
                    cursor += 1
                } else {
                    while (cursor < source.length && isIdentifierPart(source[cursor])) cursor += 1
                }
                return source.substring(start, cursor) to cursor
            }
            if (source[cursor] == '@') {
                cursor += 1
                if (cursor < source.length && source[cursor] == '@') cursor += 1
            }
            while (cursor < source.length && isIdentifierPart(source[cursor])) cursor += 1
            if (cursor < source.length && (source[cursor] == '?' || source[cursor] == '!')) cursor += 1
            return source.substring(start, cursor) to cursor
        }

        private fun isQuotedSymbol(at: Int): Boolean =
            at > 0 && source[at - 1] == ':' && !(at > 1 && source[at - 2] == ':')

        private fun startsWithWord(word: String, at: Int): Boolean {
            if (!source.startsWith(word, at)) return false
            val after = at + word.length
            return after >= source.length || !isIdentifierPart(source[after])
        }

        private fun consumeNewline(at: Int): Int {
            if (source[at] == '\r' && at + 1 < source.length && source[at + 1] == '\n') return at + 2
            return at + 1
        }
    }

    private class Parsed(val literal: RubyStringLiteral, val end: Int)
}
