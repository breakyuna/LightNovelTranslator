package com.breakyuna.noveltranslator.core.translation

/**
 * Text that must survive a model round trip byte-for-byte. The model sees a short opaque marker;
 * the original value is restored locally after decoding and is then verified by deterministic QA.
 */
data class ProtectedToken(val marker: String, val original: String)

data class ProtectedText(val masked: String, val tokens: List<ProtectedToken>)

object TranslationTextProtection {
    private val tokenPattern = Regex(
        """\[IMG:[^\]\r\n]+\]|\{\{[^{}\r\n]+\}\}|\$\{[^{}\r\n]+\}|</?[A-Za-z][^>\r\n]{0,80}>|&(?:[A-Za-z][A-Za-z0-9]+|#\d+|#x[0-9A-Fa-f]+);""",
        RegexOption.IGNORE_CASE
    )

    fun protect(text: String): ProtectedText {
        val matches = tokenPattern.findAll(text).toList()
        if (matches.isEmpty()) return ProtectedText(text, emptyList())

        val tokens = matches.mapIndexed { index, match ->
            ProtectedToken(
                marker = "__LNT_PROTECTED_${index}__",
                original = match.value
            )
        }
        val masked = buildString(text.length) {
            var cursor = 0
            matches.forEachIndexed { index, match ->
                append(text.substring(cursor, match.range.first))
                append(tokens[index].marker)
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
        return ProtectedText(masked, tokens)
    }

    /**
     * Restore only markers belonging to this segment. Unknown or duplicated markers are kept so
     * QA can report a structural mismatch instead of silently accepting model output.
     */
    fun restore(text: String, tokens: List<ProtectedToken>): String =
        tokens.fold(text) { current, token -> current.replace(token.marker, token.original) }

    fun tokenValues(text: String): List<String> = tokenPattern.findAll(text).map { it.value }.toList()
}
