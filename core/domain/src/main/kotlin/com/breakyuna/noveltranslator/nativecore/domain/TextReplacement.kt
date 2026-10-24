package com.breakyuna.noveltranslator.nativecore.domain

/** Literal mode never expands replacement text; regex mode uses JavaScript dollar substitutions. */
data class TextReplacementOptions(val find: String, val replacement: String, val caseSensitive: Boolean = true,
    val regex: Boolean = false) {
    internal val pattern: com.google.re2j.Pattern
    init {
        require(find.isNotEmpty() && find.length <= 256 && replacement.length <= 4096)
        for (text in listOf(find, replacement)) { require('\u0000' !in text); TextProjectFingerprint.utf8(text) }
        pattern = if (regex) TextRuleValidation.compile(ProjectTextRule("replacement", TextRuleKind.POST_REPLACE,
            find, regex = true, caseSensitive = caseSensitive)).pattern
        else com.google.re2j.Pattern.compile(com.google.re2j.Pattern.quote(find),
            if (caseSensitive) 0 else com.google.re2j.Pattern.CASE_INSENSITIVE)
    }
    override fun toString(): String = "TextReplacementOptions(redacted)"
}

class TextReplacementPreview(val options: TextReplacementOptions, val originalFingerprint: ObjectFingerprint,
    val text: String, val occurrences: Int, val changed: Boolean) {
    init { require(text.length <= 65_536 && occurrences in 0..65_537) }
    override fun toString(): String = "TextReplacementPreview(occurrences=$occurrences, changed=$changed)"
}
