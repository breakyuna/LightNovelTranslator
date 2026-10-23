package com.breakyuna.noveltranslator.nativecore.domain

/** Literal, nonoverlapping replacement; no regex, dollar or backslash expansion. */
data class TextReplacementOptions(val find: String, val replacement: String, val caseSensitive: Boolean = true) {
    init {
        require(find.isNotEmpty() && find.length <= 256 && replacement.length <= 4096)
        for (text in listOf(find, replacement)) { require('\u0000' !in text); TextProjectFingerprint.utf8(text) }
    }
    override fun toString(): String = "TextReplacementOptions(redacted)"
}

class TextReplacementPreview(val options: TextReplacementOptions, val originalFingerprint: ObjectFingerprint,
    val text: String, val occurrences: Int, val changed: Boolean) {
    init { require(text.length <= 65_536 && occurrences in 0..65_536) }
    override fun toString(): String = "TextReplacementPreview(occurrences=$occurrences, changed=$changed)"
}
