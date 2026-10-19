package com.breakyuna.noveltranslator.core.glossary

/**
 * Minimal JSON value tree with source line numbers.
 *
 * Line numbers exist so import errors can point the user at the offending entry
 * (`行号 + 原因`), which Moshi's `JsonReader` path/location strings do not provide.
 */
internal sealed class JsonNode {
    abstract val line: Int

    class JsonObject(override val line: Int, val fields: Map<String, JsonNode>) : JsonNode()
    class JsonArray(override val line: Int, val items: List<JsonNode>) : JsonNode()
    class JsonString(override val line: Int, val value: String) : JsonNode()
    class JsonNumber(override val line: Int, val raw: String) : JsonNode()
    class JsonBoolean(override val line: Int, val value: Boolean) : JsonNode()
    class JsonNull(override val line: Int) : JsonNode()
}

/** One parse failure with the 1-based line it occurred on. */
internal data class JsonError(val line: Int, val message: String)

/**
 * Tolerant JSON reader used by the glossary file import.
 *
 * Differences from a strict parser, all chosen so a single bad entry never loses the rest of the
 * file (mirrors LG's "单条错误不影响其他条目" import behavior):
 * - array elements that fail to parse are skipped and reported, then parsing resynchronizes at the
 *   next element boundary;
 * - trailing commas in arrays/objects are accepted;
 * - `true`/`false`/`null` are matched case-insensitively;
 * - unknown string escapes keep the escaped character literally.
 */
internal class LenientJson(private val text: String) {

    private var pos = 0
    private var line = 1
    private val errors = mutableListOf<JsonError>()

    /** Parses the whole document; a null value means the root itself was unusable. */
    fun parseDocument(): Pair<JsonNode?, List<JsonError>> {
        errors.clear()
        skipWhitespace()
        val root = try {
            parseValue()
        } catch (error: JsonSyntaxException) {
            errors += JsonError(error.line, error.message)
            null
        }
        if (root != null) {
            skipWhitespace()
            if (pos < text.length) {
                errors += JsonError(line, "JSON 根节点之后存在多余内容（第 $line 行）")
            }
        }
        return root to errors.toList()
    }

    private fun parseValue(): JsonNode {
        skipWhitespace()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString(line, parseString())
            't', 'T' -> parseLiteral("true", JsonBoolean(line, true))
            'f', 'F' -> parseLiteral("false", JsonBoolean(line, false))
            'n', 'N' -> parseLiteral("null", JsonNull(line))
            else -> {
                if (ch == '-' || ch == '+' || ch.isDigit()) parseNumber()
                else fail("无法识别的 JSON 值（第 $line 行）")
            }
        }
    }

    private fun parseObject(): JsonNode {
        val startLine = line
        expect('{')
        val fields = LinkedHashMap<String, JsonNode>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonObject(startLine, fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("对象键必须是字符串（第 $line 行）")
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (peek() == '}') { // 容忍尾逗号
                        pos++
                        return JsonObject(startLine, fields)
                    }
                }
                '}' -> {
                    pos++
                    return JsonObject(startLine, fields)
                }
                else -> fail("对象中缺少 ',' 或 '}'（第 $line 行）")
            }
        }
    }

    private fun parseArray(): JsonNode {
        val startLine = line
        expect('[')
        val items = mutableListOf<JsonNode>()
        while (true) {
            skipWhitespace()
            if (peek() == ']') { // 同时覆盖空数组与尾逗号
                pos++
                break
            }
            val element = try {
                parseValue()
            } catch (error: JsonSyntaxException) {
                errors += JsonError(error.line, error.message)
                if (!resyncArrayElement()) break
                continue
            }
            items += element
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    break
                }
                else -> {
                    errors += JsonError(line, "数组元素之间缺少 ','（第 $line 行）")
                    if (!resyncArrayElement()) break
                }
            }
        }
        return JsonArray(startLine, items)
    }

    /**
     * After a failed element, scans forward to the next element boundary at the current nesting
     * depth. Returns false when the array itself ended (its `]` was consumed). A `}` at depth 0 is
     * treated as debris from the failed element, not as the array terminator.
     */
    private fun resyncArrayElement(): Boolean {
        var depth = 0
        var inString = false
        while (pos < text.length) {
            val ch = text[pos]
            if (inString) {
                if (ch == '\\') {
                    pos++
                } else if (ch == '"') {
                    inString = false
                }
                if (pos < text.length && text[pos] == '\n') line++
                pos++
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    if (depth == 0) {
                        if (ch == ']') return false // 数组闭合
                        // 深度 0 的 '}' 是失败元素残留的右花括号：跳过，继续寻找分隔符
                    } else {
                        depth--
                    }
                }
                ',' -> if (depth == 0) {
                    pos++
                    return true
                }
                '\n' -> line++
            }
            pos++
        }
        return false
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            when (ch) {
                '"' -> {
                    pos++
                    return value.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= text.length) fail("字符串转义意外结束（第 $line 行）")
                    when (val escaped = text[pos]) {
                        '"' -> value.append('"')
                        '\\' -> value.append('\\')
                        '/' -> value.append('/')
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000C')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (pos + 4 >= text.length) fail("\\u 转义不完整（第 $line 行）")
                            val hex = text.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: fail("\\u 转义无效：$hex（第 $line 行）")
                            value.append(code.toChar())
                            pos += 4
                        }
                        else -> value.append(escaped) // 宽容处理未知转义
                    }
                    pos++
                }
                '\n' -> fail("字符串未闭合（第 $line 行）")
                else -> {
                    value.append(ch)
                    pos++
                }
            }
        }
        fail("字符串未闭合（第 $line 行）")
    }

    private fun parseNumber(): JsonNode {
        val start = pos
        if (peek() == '-' || peek() == '+') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        if (pos == start) fail("数字解析失败（第 $line 行）")
        val raw = text.substring(start, pos)
        if (raw.toDoubleOrNull() == null) fail("无效数字：$raw（第 $line 行）")
        return JsonNumber(line, raw)
    }

    private fun parseLiteral(literal: String, node: JsonNode): JsonNode {
        if (!text.regionMatches(pos, literal, 0, literal.length, ignoreCase = true)) {
            fail("无法识别的 JSON 值（第 $line 行）")
        }
        pos += literal.length
        return node
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) {
            if (text[pos] == '\n') line++
            pos++
        }
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun expect(ch: Char) {
        if (peek() != ch) fail("期望 '$ch'（第 $line 行）")
        pos++
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(line, message)

    private class JsonSyntaxException(val line: Int, message: String) : Exception(message)

    companion object {
        /**
         * Extracts JSON from a Markdown code fence when present; models and users frequently paste
         * ```` ```json ... ``` ```` blocks (often with prose around them). Returns the original
         * text when there is no fence, and everything after the opening fence when it is unclosed.
         */
        fun stripCodeFence(text: String): String {
            val fenceStart = text.indexOf("```")
            if (fenceStart < 0) return text
            val firstLineEnd = text.indexOf('\n', fenceStart)
            if (firstLineEnd < 0) return ""
            val afterFence = text.substring(firstLineEnd + 1)
            val closing = afterFence.indexOf("```")
            return if (closing >= 0) afterFence.substring(0, closing) else afterFence
        }
    }
}
