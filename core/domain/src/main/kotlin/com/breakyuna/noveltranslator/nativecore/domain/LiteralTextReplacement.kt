package com.breakyuna.noveltranslator.nativecore.domain

/** Shared preview/commit implementation. Regex behavior is enabled only by the explicit option. */
object LiteralTextReplacement {
    fun preview(text: String, options: TextReplacementOptions, checkActive: () -> Unit = {}): TextReplacementPreview = try {
        prepare(text, options, checkActive)
    } catch (failure: InvalidTextRule) {
        if (failure.reason == InvalidTextRule.Reason.LIMIT) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        throw failure
    }
    private class ReplacementBudget(private val checkActive: () -> Unit) {
        private var steps = 0L
        fun charge(amount: Int = 1) {
            steps += amount
            if (steps > 4_000_000) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            if (amount > 256 || steps % 256 == 0L) checkActive()
        }
    }
    private class ReplacementSequence(private val value: String, private val budget: ReplacementBudget,
        private val start: Int = 0, private val end: Int = value.length) : CharSequence {
        override val length: Int get() { budget.charge(); return end - start }
        override fun get(index: Int): Char { budget.charge(); require(index in 0 until end - start); return value[start + index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= end - start)
            return ReplacementSequence(value, budget, start + startIndex, start + endIndex)
        }
        override fun toString(): String { budget.charge(end - start); return value.substring(start, end) }
    }
    private fun prepare(text: String, options: TextReplacementOptions, checkActive: () -> Unit): TextReplacementPreview {
        require(text.length <= 65_536 && '\u0000' !in text)
        TextProjectFingerprint.utf8(text); checkActive()
        val original = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(text)))
        val budget = ReplacementBudget(checkActive)
        val pattern = options.pattern
        val named: Map<String, Int> = if (options.regex && pattern.groupCount() > 0) try { pattern.namedGroups() }
            catch (_: NullPointerException) { emptyMap<String, Int>() } else emptyMap()
        val matcher = pattern.matcher(ReplacementSequence(text, budget))
        val output = StringBuilder(); var cursor = 0; var count = 0
        fun append(value: String) {
            if (output.length.toLong() + value.length > 65_536)
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            output.append(value)
        }
        while (matcher.find()) {
            checkActive()
            append(text.substring(cursor, matcher.start()))
            if (options.regex) JavascriptReplacement.append(options.replacement, text, matcher, named, checkActive, ::append)
            else append(options.replacement)
            cursor = matcher.end(); count++
        }
        append(text.substring(cursor)); checkActive()
        val result = output.toString()
        TextProjectFingerprint.utf8(result)
        return TextReplacementPreview(options, original, result, count, result != text)
    }
}
