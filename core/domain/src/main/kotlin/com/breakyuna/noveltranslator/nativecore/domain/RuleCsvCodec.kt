package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/** UTF-8 CSV interchange with the five explicit quality-rule columns; kind stays a user choice. */
object RuleCsvCodec {
    private val columns = listOf("src", "dst", "info", "regex", "case_sensitive")
    const val MAX_CHARS = LgRuleJsonCodec.MAX_CHARS

    fun decode(text: String, kind: TextRuleKind, current: ProjectTextPolicy = ProjectTextPolicy()): RuleExchange =
        LgRuleJsonCodec.decode(toJson(text), kind, current)

    fun toJson(text: String): String {
        require(text.length <= MAX_CHARS)
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false; var closed = false; var index = 0
        fun finishField() {
            require(fields.size < columns.size)
            fields.add(field.toString()); field.setLength(0); closed = false
        }
        fun finishRecord() {
            finishField()
            require(fields.size == columns.size)
            require(records.size < RuleExchange.MAX_RULES + 1)
            records.add(fields); fields = ArrayList()
        }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(char)
            } else when (char) {
                '"' -> { require(field.isEmpty() && !closed); quoted = true }
                ',' -> finishField()
                '\r', '\n' -> {
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                    finishRecord()
                }
                else -> { require(!closed); field.append(char) }
            }
        }
        require(!quoted)
        if (field.isNotEmpty() || fields.isNotEmpty() || closed) finishRecord()
        require(records.firstOrNull() == columns)
        return JsonArray(records.drop(1).map { row -> buildJsonObject {
            put("src", row[0]); put("dst", row[1]); put("info", row[2])
            for (position in 3..4) {
                val flag = row[position].trim().lowercase(java.util.Locale.ROOT)
                require(flag in setOf("true", "false", ""))
                put(columns[position], flag == "true")
            }
        } }).toString().also { require(it.length <= LgRuleJsonCodec.MAX_CHARS) }
    }

    fun encode(policy: ProjectTextPolicy, kind: TextRuleKind): String {
        // Reuse the JSON export's disabled-rule and size checks.
        LgRuleJsonCodec.encode(policy, kind)
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        return buildString {
            append(columns.joinToString(",")); append("\r\n")
            for (rule in policy.rules.filter { it.kind == kind }) {
                append(listOf(rule.source, rule.target, rule.note, rule.regex.toString(), rule.caseSensitive.toString())
                    .joinToString(",", transform = ::cell))
                append("\r\n")
            }
        }.also { require(it.length <= MAX_CHARS) }
    }
}
