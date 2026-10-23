package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.InvalidTextRule
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextRule
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleValidation

/**
 * A UTF-16 code-unit offset range into the text supplied to [RuleMatchExplainer]. The matched
 * text itself is never part of the explanation.
 */
data class RuleMatchSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end > start && end <= RuleMatchExplainer.MAX_TEXT_CHARS)
    }
}

/**
 * Read-only explanation for one rule. [spans] and [matchCount] describe raw source matches for
 * every rule kind; terms and protection consume the same non-empty, non-overlapping matches that
 * the runtime processor uses, while pre/post replacements describe the matches they would rewrite.
 * [enabled] is reported, but a disabled rule is still explained so the UI can show it before it is
 * turned on.
 */
class RuleMatchExplanation(
    val ruleId: String,
    val kind: TextRuleKind,
    val enabled: Boolean,
    val textLength: Int,
    spans: List<RuleMatchSpan>,
    val truncated: Boolean,
) {
    val spans: List<RuleMatchSpan> = java.util.Collections.unmodifiableList(ArrayList(spans))

    init {
        require(ruleId.isNotBlank() && ruleId.length <= 128 && ruleId.none(Char::isISOControl))
        require(textLength in 0..RuleMatchExplainer.MAX_TEXT_CHARS)
        require(this.spans.size <= RuleMatchExplainer.MAX_MATCHES)
        require(this.spans.all { it.end <= textLength })
        require(this.spans.zipWithNext().all { (left, right) -> left.end <= right.start })
    }

    val matchCount: Int get() = spans.size

    override fun toString(): String =
        "RuleMatchExplanation(rule=$ruleId, kind=$kind, matches=$matchCount, truncated=$truncated)"
}

/**
 * Bounded, read-only matching preview for one project rule.
 *
 * The regex engine is the shared RE2/J matcher, not `java.util.regex`. Matching is non-overlapping
 * and zero-length matches are ignored (they have no text to explain and the runtime processor
 * skips them). At most [MAX_MATCHES] offsets are retained; when [maxMatches] is reached the
 * explanation reports `truncated = true` and never scans further. No IO, no logging, and the
 * supplied text is not retained.
 */
class RuleMatchExplainer {
    fun explain(
        rule: ProjectTextRule,
        text: String,
        maxMatches: Int = DEFAULT_MATCHES,
        checkActive: () -> Unit = {},
    ): RuleMatchExplanation {
        require(maxMatches in 1..MAX_MATCHES)
        if (text.length > MAX_TEXT_CHARS) throw InvalidTextRule(null, InvalidTextRule.Reason.LIMIT)
        checkActive()
        val compiled = TextRuleValidation.compile(rule)
        val matcher = compiled.pattern.matcher(text)
        val spans = ArrayList<RuleMatchSpan>(minOf(maxMatches, 16))
        var truncated = false
        var iterations = 0
        while (true) {
            if (++iterations > MAX_FIND_CALLS) throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
            if ((iterations and 0xFF) == 0) checkActive()
            val found = try {
                matcher.find()
            } catch (_: StackOverflowError) {
                throw InvalidTextRule(rule.id, InvalidTextRule.Reason.LIMIT)
            }
            if (!found) break
            val start = matcher.start()
            val end = matcher.end()
            if (end <= start) continue
            if (spans.size >= maxMatches) {
                truncated = true
                break
            }
            spans.add(RuleMatchSpan(start, end))
        }
        return RuleMatchExplanation(rule.id, rule.kind, rule.enabled, text.length, spans, truncated)
    }

    companion object {
        const val MAX_TEXT_CHARS = 65_536
        const val MAX_MATCHES = 256
        const val DEFAULT_MATCHES = 32

        /** One empty-match find per code unit plus the terminating false result. */
        private const val MAX_FIND_CALLS = MAX_TEXT_CHARS + 2
    }
}
