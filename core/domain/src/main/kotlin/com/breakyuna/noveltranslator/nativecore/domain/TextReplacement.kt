package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

enum class TextReplacementTarget { TRANSLATION, NAMES, BOTH }

/** Literal mode never expands replacement text; regex mode uses JavaScript dollar substitutions. */
data class TextReplacementOptions(val find: String, val replacement: String, val caseSensitive: Boolean = true,
    val regex: Boolean = false, val target: TextReplacementTarget = TextReplacementTarget.TRANSLATION) {
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

/** One item's preview; null name arrays stay null and source-name slots are never changed. */
class ItemReplacementPreview(val translation: String, val names: List<String>?, val changed: Boolean)

object ItemTextReplacement {
    fun commandDigest(command: ManualTextEdit, names: List<String>): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("version", "manual-body-names-v1")
            put("body_command", TextProjectFingerprint.command(command))
            put("name_translation", JsonArray(names.map { JsonPrimitive(it) }))
        }.toString()))

    fun preview(translation: String, names: List<String>?, options: TextReplacementOptions,
                checkActive: () -> Unit = {}): ItemReplacementPreview {
        val body = if (options.target != TextReplacementTarget.NAMES)
            LiteralTextReplacement.preview(translation, options, checkActive).text else translation
        val replacedNames = if (options.target != TextReplacementTarget.TRANSLATION) names?.map {
            checkActive()
            LiteralTextReplacement.preview(it, options, checkActive).text
        } else names
        if (replacedNames != null) {
            require(replacedNames.size <= ManualNameEdit.MAX_NAMES)
            require(replacedNames.sumOf { it.length.toLong() } <= ManualNameEdit.MAX_TOTAL_NAME_CHARS)
            require(replacedNames.all { it.length <= ManualNameEdit.MAX_NAME_CHARS })
        }
        return ItemReplacementPreview(body, replacedNames, body != translation || replacedNames != names)
    }
}
