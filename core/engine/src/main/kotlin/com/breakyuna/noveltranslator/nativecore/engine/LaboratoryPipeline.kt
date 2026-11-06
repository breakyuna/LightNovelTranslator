package com.breakyuna.noveltranslator.nativecore.engine

import java.util.Locale
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind

/**
 * Port of the LG conservative ruby cleaner plus the optional bracketed rules.
 * Only call [cleanConservative] when the source format is unknown: aggressive
 * parentheses/pipe rules can destroy WOLF, RPG Maker or Ren'Py control syntax.
 */
object RubyCleaner {
    private data class Rule(val pattern: Regex, val replacement: (MatchResult) -> String)

    private val CONSERVATIVE = listOf(
        Rule(Regex("""(?:\\r|\r)\[(.+?),.+?]""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""(?:\\r|\r)b\[(.+?),.+?]""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""\[r_.+?]\[ch_(.+?)]""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""\[ch_(.+?)]""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""<ruby\s*=\s*.*?>(.*?)</ruby>""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""<ruby>.*?<rb>(.*?)</rb>.*?</ruby>""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""<ruby[^>]*>(.*?)<rt[^>]*>.*?</rt>.*?</ruby>""", RegexOption.IGNORE_CASE)) { it.groupValues[1] },
        Rule(Regex("""\[ruby text\s*=\s*.*?]""", RegexOption.IGNORE_CASE)) { "" },
    )

    private val AGGRESSIVE = listOf(
        Rule(Regex("""\((.+)/.+\)""")) { it.groupValues[1] },
        Rule(Regex("""\[(.+)/.+]""")) { it.groupValues[1] },
        Rule(Regex("""\|(.+?)\[.+?]""")) { it.groupValues[1] },
    )

    private val AGGRESSIVE_EXCLUDED = setOf("WOLF", "RPGMAKER", "RENPY")

    fun cleanConservative(text: String): String {
        var result = text
        for (rule in CONSERVATIVE) result = rule.pattern.replace(result, rule.replacement)
        return result
    }

    fun clean(text: String, textType: String = "NONE"): String {
        var result = cleanConservative(text)
        if (textType.trim().uppercase(Locale.ROOT) !in AGGRESSIVE_EXCLUDED) {
            for (rule in AGGRESSIVE) result = rule.pattern.replace(result, rule.replacement)
        }
        return result
    }
}

/**
 * A real prompt consumer for the laboratory switch. The instruction is appended
 * once per request and must stay inside the existing project-instruction bound.
 */
object LaboratoryPromptEnhancement {
    const val MAX_PROJECT_INSTRUCTIONS = 16_384
    const val INSTRUCTION =
        "Before translating, resolve ambiguous pronouns and omitted subjects from the supplied context. " +
            "Keep names, honorifics, placeholders and formatting stable. Do not explain the translation."

    fun apply(prompt: String): String {
        val normalized = prompt.trimEnd()
        val combined = if (normalized.isEmpty()) INSTRUCTION else "$normalized\n\n$INSTRUCTION"
        if (combined.length > MAX_PROJECT_INSTRUCTIONS) {
            throw AgentModelFailure(ModelFailureKind.CONTENT)
        }
        return combined
    }
}
