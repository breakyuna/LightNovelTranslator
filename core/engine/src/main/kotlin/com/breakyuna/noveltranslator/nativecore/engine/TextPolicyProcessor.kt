package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import com.google.re2j.Matcher

/** Compiled once per run; every matcher has a shared, cancellable work budget per item. */
class TextPolicyProcessor(policy: ProjectTextPolicy, private val checkActive: () -> Unit = {}) {
    private val compiled = buildList {
        var program = 0
        for (rule in policy.rules.filter { it.enabled }) {
            checkActive()
            val value = TextRuleValidation.compile(rule)
            program += value.pattern.programSize()
            if (program > 65_536) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
            add(value)
        }
    }

    class Prepared internal constructor(val text: String, val terms: List<TranslationTerm>,
        internal val prefix: String, internal val protected: Map<String, String>, internal val budget: RuleBudget) {
        override fun toString(): String = "PreparedRuleText(redacted)"
    }
    internal class RuleBudget(private val checkActive: () -> Unit) {
        private var steps = 0L
        fun charge(amount: Int = 1) {
            steps += amount
            if (steps > 4_000_000) throw InvalidTextRule(null, InvalidTextRule.Reason.LIMIT)
            if (amount > 256 || steps % 256 == 0L) checkActive()
        }
    }
    internal class BoundedSequence(private val value: String, private val budget: RuleBudget,
        private val start: Int = 0, private val end: Int = value.length) : CharSequence {
        override val length: Int get() { budget.charge(); return end - start }
        override fun get(index: Int): Char { budget.charge(); require(index in 0 until end - start); return value[start + index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= end - start)
            return BoundedSequence(value, budget, start + startIndex, start + endIndex)
        }
        override fun toString(): String { budget.charge(end - start); return value.substring(start, end) }
    }
    private fun matcher(rule: CompiledTextRule, value: String, budget: RuleBudget): Matcher =
        rule.pattern.matcher(BoundedSequence(value, budget))
    private fun find(matcher: Matcher, rule: CompiledTextRule): Boolean = try { matcher.find() }
        catch (_: StackOverflowError) { throw InvalidTextRule(rule.rule.id, InvalidTextRule.Reason.LIMIT) }

    fun prepare(source: String): Prepared = prepare(source, allowBlank = false)

    internal fun prepareProofreading(source: String): Prepared = prepare(source, allowBlank = true)

    private fun prepare(source: String, allowBlank: Boolean): Prepared {
        checkActive()
        if (source.length > 65_536) throw InvalidTextRule(null, InvalidTextRule.Reason.LIMIT)
        val budget = RuleBudget(checkActive)
        val spans = protectionSpans(source, budget)
        val terms = mutableListOf<TranslationTerm>()
        for (rule in compiled.filter { it.rule.kind == TextRuleKind.TERM }) {
            checkActive()
            if (find(matcher(rule, source, budget), rule))
                terms.add(TranslationTerm(rule.rule.source, rule.rule.target, rule.rule.note))
        }
        val prefix = "[LNT_" + UUID.randomUUID().toString().replace("-", "") + "_"
        check(!source.contains(prefix))
        val protected = LinkedHashMap<String, String>()
        val output = StringBuilder()
        var cursor = 0
        for (span in spans) {
            append(output, replace(source.substring(cursor, span.start), TextRuleKind.PRE_REPLACE, budget))
            val token = prefix + protected.size + "]"
            protected[token] = source.substring(span.start, span.end)
            append(output, token)
            cursor = span.end
        }
        append(output, replace(source.substring(cursor), TextRuleKind.PRE_REPLACE, budget))
        if (!allowBlank && output.isBlank()) throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
        return Prepared(output.toString(), java.util.Collections.unmodifiableList(terms), prefix,
            java.util.Collections.unmodifiableMap(protected), budget)
    }

    internal data class Preserved(val line: Int, val value: String) {
        override fun toString(): String = "Preserved(redacted)"
    }
    internal class Analysis(val natural: String, val preserved: List<Preserved>) {
        override fun toString(): String = "TextAnalysis(redacted)"
    }
    private data class Span(val start: Int, val end: Int, val priority: Int)

    private fun protectionSpans(text: String, budget: RuleBudget): List<Span> {
        val spans = mutableListOf<Span>()
        for ((priority, rule) in compiled.withIndex()) {
            checkActive()
            if (rule.rule.kind != TextRuleKind.PROTECT) continue
            val match = matcher(rule, text, budget)
            while (find(match, rule)) {
                if (match.end() > match.start()) spans.add(Span(match.start(), match.end(), priority))
                if (spans.size > 4096) throw InvalidTextRule(rule.rule.id, InvalidTextRule.Reason.LIMIT)
            }
        }
        var cursor = 0
        return spans.sortedWith(compareBy<Span> { it.start }.thenBy { it.priority }.thenByDescending { it.end })
            .filter { span -> if (span.start < cursor) false else { cursor = span.end; true } }
    }

    /** Source uses the same protected/pre-replaced text as transport; translation is never replaced. */
    internal fun proofreadingSource(prepared: Prepared): Analysis {
        val spans = mutableListOf<Pair<IntRange, String>>()
        var cursor = 0
        while (true) {
            val start = prepared.text.indexOf(prepared.prefix, cursor)
            if (start < 0) break
            val end = prepared.text.indexOf(']', start)
            val token = prepared.text.substring(start, end + 1)
            spans.add((start..end) to checkNotNull(prepared.protected[token]))
            cursor = end + 1
        }
        return analyze(prepared.text, spans)
    }

    internal fun proofreadingTranslation(text: String, budget: RuleBudget): Analysis = analyze(text,
        protectionSpans(text, budget).map { (it.start until it.end) to text.substring(it.start, it.end) })

    private fun analyze(text: String, spans: List<Pair<IntRange, String>>): Analysis {
        val natural = StringBuilder()
        val preserved = mutableListOf<Preserved>()
        var cursor = 0; var line = 0; var previous = '\u0000'
        fun consume(value: String): Int {
            var count = 0
            for (c in value) {
                if (c == '\r' || c == '\n' && previous != '\r') count++
                previous = c
            }
            line += count
            return count
        }
        for ((range, value) in spans) {
            checkActive()
            val before = text.substring(cursor, range.first)
            natural.append(before)
            consume(before)
            if (value.isNotBlank()) preserved.add(Preserved(line, value))
            // Keep original line identity for multi-line preserved text too.
            val newlines = consume(value)
            repeat(newlines) { natural.append('\n') }
            cursor = range.last + 1
        }
        natural.append(text.substring(cursor))
        return Analysis(natural.toString(), preserved)
    }

    /** Replacements cannot rewrite protected control tokens or cross their boundaries. */
    fun finish(prepared: Prepared, translated: String): String {
        checkActive()
        if (translated.length > 65_536) throw InvalidTextRule(null, InvalidTextRule.Reason.LIMIT)
        val seen = mutableSetOf<String>()
        val output = StringBuilder()
        var cursor = 0
        while (true) {
            val start = translated.indexOf(prepared.prefix, cursor)
            if (start < 0) break
            val end = translated.indexOf(']', start)
            if (end < 0) throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
            val token = translated.substring(start, end + 1)
            val original = prepared.protected[token] ?: throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
            if (!seen.add(token)) throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
            append(output, replace(translated.substring(cursor, start), TextRuleKind.POST_REPLACE, prepared.budget))
            append(output, original)
            cursor = end + 1
        }
        if (seen.size != prepared.protected.size) throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
        append(output, replace(translated.substring(cursor), TextRuleKind.POST_REPLACE, prepared.budget))
        val result = output.toString()
        if ('\u0000' in result) throw InvalidTextRule(null, InvalidTextRule.Reason.PROTECTION)
        TextProjectFingerprint.utf8(result)
        return result
    }

    private fun replace(text: String, kind: TextRuleKind, budget: RuleBudget): String {
        var value = text
        for (rule in compiled.filter { it.rule.kind == kind }) {
            checkActive()
            val match = matcher(rule, value, budget)
            val output = StringBuilder()
            var cursor = 0; var matches = 0
            while (find(match, rule)) {
                if (++matches > 65_537) throw InvalidTextRule(rule.rule.id, InvalidTextRule.Reason.LIMIT)
                append(output, value.substring(cursor, match.start()))
                append(output, rule.replacement(match))
                cursor = match.end()
            }
            append(output, value.substring(cursor))
            value = output.toString()
        }
        return value
    }
    private fun append(output: StringBuilder, text: String) {
        if (output.length.toLong() + text.length > 65_536) throw InvalidTextRule(null, InvalidTextRule.Reason.LIMIT)
        output.append(text)
    }
}
