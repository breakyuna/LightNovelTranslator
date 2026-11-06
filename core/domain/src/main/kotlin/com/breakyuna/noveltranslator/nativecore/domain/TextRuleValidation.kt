package com.breakyuna.noveltranslator.nativecore.domain

import com.google.re2j.Pattern
import com.google.re2j.Matcher
import com.google.re2j.PatternSyntaxException

class InvalidTextRule(val ruleId: String?, val reason: Reason) : IllegalArgumentException("Invalid text rule: $reason") {
    enum class Reason { PATTERN, LIMIT, PROTECTION }
}

class CompiledTextRule(val rule: ProjectTextRule, val pattern: Pattern, private val namedIndexes: Map<String, Int>) {
    /** LG backslash replacement syntax; $1 stays literal and unmatched captures become empty. */
    fun replacement(match: Matcher): String {
        if (!rule.regex) return rule.target
        fun group(index: Int?) = if (index != null && index in 1..match.groupCount()) match.group(index).orEmpty() else ""
        val text = rule.target
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] != '\\' || i + 1 == text.length) { out.append(text[i++]); continue }
            val next = text[i + 1]
            when {
                next == '\\' -> { out.append('\\'); i += 2 }
                next == 'n' || next == 'r' || next == 't' -> {
                    out.append(when (next) { 'n' -> '\n'; 'r' -> '\r'; else -> '\t' }); i += 2
                }
                next in '1'..'9' -> {
                    var end = i + 2
                    if (end < text.length && text[end] in '0'..'9') end++
                    out.append(group(text.substring(i + 1, end).toIntOrNull())); i = end
                }
                next == 'g' && text.getOrNull(i + 2) == '<' && text.indexOf('>', i + 3) > i + 3 -> {
                    val end = text.indexOf('>', i + 3)
                    val name = text.substring(i + 3, end)
                    out.append(group(name.toIntOrNull() ?: namedIndexes[name])); i = end + 1
                }
                else -> { out.append('\\'); i++ }
            }
            if (out.length > 65_536) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
        }
        return out.toString()
    }
}

object TextRuleValidation {
    fun validate(policy: ProjectTextPolicy, checkActive: () -> Unit = {}) {
        ProjectTextPolicyCodec.encode(policy)
        var program = 0
        for (rule in policy.rules) {
            checkActive()
            val compiled = compile(rule)
            if (rule.enabled) program += compiled.pattern.programSize()
            if (program > 65_536) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
        }
    }

    fun compile(rule: ProjectTextRule): CompiledTextRule {
        if (rule.regex) {
            // Bound structural depth before compilation. This policy dialect excludes COMMENTS
            // and Java-style Unicode inline flags; unsupported syntax is rejected, never reinterpreted.
            val text = rule.source
            var i = 0; var classes = 0; var depth = 0; var quoted = false
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    if (c == '\\' && text.getOrNull(i + 1) == 'E') { quoted = false; i += 2 } else i++
                    continue
                }
                if (c == '\\') { if (text.getOrNull(i + 1) == 'Q') quoted = true; i += 2; continue }
                if (c == '[') { classes++; i++; continue }
                if (c == ']' && classes > 0) { classes--; i++; continue }
                if (classes > 0) { i++; continue }
                if (c == '(') {
                    if (++depth > 32) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
                    if (text.getOrNull(i + 1) == '?') {
                        var end = i + 2
                        while (end < text.length && text[end] in "idmsuxU-") end++
                        if (text.substring(i + 2, end).any { it == 'x' || it == 'U' })
                            throw InvalidTextRule(rule.id, InvalidTextRule.Reason.PATTERN)
                    }
                } else if (c == ')') depth--
                i++
            }
        }
        val flags = if (rule.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val pattern = try { Pattern.compile(if (rule.regex) rule.source else Pattern.quote(rule.source), flags) }
            catch (_: PatternSyntaxException) { throw InvalidTextRule(rule.id, InvalidTextRule.Reason.PATTERN) }
            catch (_: IllegalArgumentException) { throw InvalidTextRule(rule.id, InvalidTextRule.Reason.PATTERN) }
            catch (_: StackOverflowError) { throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT) }
        if (pattern.programSize() > 8192) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
        if (pattern.groupCount() > 128) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
        // RE2/J supplies the name-to-ordinal map. Matching still uses group(Int), not the
        // platform Matcher.start(String)/group(String) APIs that require Android API 26.
        val names: Map<String, Int> = if (pattern.groupCount() == 0) emptyMap() else try { pattern.namedGroups() }
            catch (_: NullPointerException) { emptyMap() }
        return CompiledTextRule(rule, pattern, names)
    }
}
