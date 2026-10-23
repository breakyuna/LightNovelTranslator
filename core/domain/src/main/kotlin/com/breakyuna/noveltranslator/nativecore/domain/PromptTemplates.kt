package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Locale

/**
 * Canonical, already-lowercased BCP-47-like language tag. Use [of] to normalize user input.
 * The tag is metadata, not prose, and is safe to place in diagnostics.
 */
@JvmInline
value class PromptLanguageTag(val value: String) {
    init {
        require(value.isNotEmpty() && value.length <= 35)
        require(value == value.lowercase(Locale.ROOT))
        val parts = value.split('-')
        require(parts[0].length in 2..8 && parts[0].all { it in 'a'..'z' })
        require(parts.drop(1).all { part ->
            part.length in 1..8 && part.all { it in 'a'..'z' || it in '0'..'9' }
        })
    }

    companion object {
        fun of(raw: String): PromptLanguageTag = PromptLanguageTag(raw.trim().lowercase(Locale.ROOT))
    }
}

class PromptTemplateException(val reason: Reason, val variable: String? = null) :
    IllegalArgumentException(
        "Prompt template: $reason" + (if (variable == null) "" else " ($variable)"),
    ) {
    enum class Reason { MALFORMED_PLACEHOLDER, INVALID_VARIABLE, MISSING_VARIABLE, LIMIT }
}

/**
 * Native prompt template. [body] uses `{{variable}}` placeholders with lower-case ASCII names.
 * Rendering is a single pass: substituted values are never re-scanned as template syntax.
 *
 * There is deliberately no network access and no body/variable logging. [toString] is redacted;
 * exception messages contain only a reason and (for missing/unknown variables) the variable name.
 */
class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    languages: List<PromptLanguageTag> = emptyList(),
    val order: Int = 0,
    val body: String,
) {
    val languages: List<PromptLanguageTag> =
        java.util.Collections.unmodifiableList(ArrayList(languages))
    val placeholders: List<String> = java.util.Collections.unmodifiableList(ArrayList(parsePlaceholders(body)))

    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARS && id.none(Char::isISOControl))
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none(Char::isISOControl))
        require(description.length <= MAX_DESCRIPTION_CHARS && '\u0000' !in description)
        require(body.length <= MAX_BODY_CHARS && '\u0000' !in body)
        require(order in 0..MAX_ORDER)
        require(this.languages.size <= MAX_LANGUAGE_TAGS)
        require(this.languages.distinct().size == this.languages.size)
        for (text in listOf(id, name, description, body)) TextProjectFingerprint.utf8(text)
    }

    fun appliesTo(language: PromptLanguageTag): Boolean =
        languages.isEmpty() || language in languages

    /**
     * Substitutes all placeholders. Every placeholder must have a value; extra provided
     * variables are ignored but still bounded and validated. [checkActive] is invoked during
     * long rendering so a caller can cancel promptly.
     */
    fun render(variables: Map<String, String> = emptyMap(), checkActive: () -> Unit = {}): String {
        if (variables.size > MAX_VARIABLES) throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
        var total = 0L
        for ((name, value) in variables) {
            if (!VARIABLE_NAME.matches(name)) throw PromptTemplateException(PromptTemplateException.Reason.INVALID_VARIABLE, name)
            if (value.length > MAX_VARIABLE_CHARS || '\u0000' in value) {
                throw PromptTemplateException(PromptTemplateException.Reason.INVALID_VARIABLE, name)
            }
            TextProjectFingerprint.utf8(value)
            total += value.length
            if (total > MAX_TOTAL_VARIABLE_CHARS) throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
        }
        checkActive()
        val output = StringBuilder(minOf(body.length, MAX_RENDERED_CHARS))
        var cursor = 0
        while (cursor < body.length) {
            if ((cursor and 0xFFF) == 0) checkActive()
            val open = body.indexOf("{{", cursor)
            if (open < 0) {
                appendBody(output, cursor, body.length)
                break
            }
            appendBody(output, cursor, open)
            val close = body.indexOf("}}", open + 2)
            if (close < 0) throw PromptTemplateException(PromptTemplateException.Reason.MALFORMED_PLACEHOLDER)
            val variable = body.substring(open + 2, close)
            val value = variables[variable]
                ?: throw PromptTemplateException(PromptTemplateException.Reason.MISSING_VARIABLE, variable)
            appendValue(output, value)
            cursor = close + 2
        }
        return output.toString()
    }

    private fun appendBody(output: StringBuilder, start: Int, end: Int) {
        if (output.length.toLong() + (end - start) > MAX_RENDERED_CHARS) {
            throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
        }
        output.append(body, start, end)
    }

    private fun appendValue(output: StringBuilder, value: String) {
        if (output.length.toLong() + value.length > MAX_RENDERED_CHARS) {
            throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
        }
        output.append(value)
    }

    override fun toString(): String = "PromptTemplate(redacted)"

    companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_NAME_CHARS = 128
        const val MAX_DESCRIPTION_CHARS = 1_024
        const val MAX_BODY_CHARS = 16_384
        const val MAX_RENDERED_CHARS = 65_536
        const val MAX_VARIABLES = 32
        const val MAX_VARIABLE_CHARS = 4_096
        const val MAX_TOTAL_VARIABLE_CHARS = 32_768
        const val MAX_LANGUAGE_TAGS = 16
        const val MAX_ORDER = 1_000_000
    }
}

/** Deterministically ordered template collection; [enabledFor] is the runtime selection view. */
class PromptTemplateSet(templates: List<PromptTemplate>) {
    val templates: List<PromptTemplate> =
        java.util.Collections.unmodifiableList(ArrayList(templates))

    init {
        require(this.templates.size <= MAX_TEMPLATES)
        require(this.templates.map { it.id }.distinct().size == this.templates.size)
    }

    fun ordered(): List<PromptTemplate> =
        templates.sortedWith(compareBy<PromptTemplate> { it.order }.thenBy { it.id })

    fun enabledFor(language: PromptLanguageTag): List<PromptTemplate> =
        ordered().filter { it.enabled && it.appliesTo(language) }

    override fun toString(): String = "PromptTemplateSet(size=${templates.size})"

    companion object {
        const val MAX_TEMPLATES = 64
    }
}

private val VARIABLE_NAME = Regex("[a-z][a-z0-9_]{0,31}")

/** Parse and validate placeholders once; malformed syntax is rejected at construction. */
private fun parsePlaceholders(body: String): List<String> {
    if (body.length > PromptTemplate.MAX_BODY_CHARS) throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
    val names = LinkedHashSet<String>()
    var cursor = 0
    while (true) {
        val open = body.indexOf("{{", cursor)
        if (open < 0) break
        val close = body.indexOf("}}", open + 2)
        if (close < 0) throw PromptTemplateException(PromptTemplateException.Reason.MALFORMED_PLACEHOLDER)
        val name = body.substring(open + 2, close)
        if (!VARIABLE_NAME.matches(name)) {
            throw PromptTemplateException(PromptTemplateException.Reason.MALFORMED_PLACEHOLDER)
        }
        names.add(name)
        if (names.size > PromptTemplate.MAX_VARIABLES) {
            throw PromptTemplateException(PromptTemplateException.Reason.LIMIT)
        }
        cursor = close + 2
    }
    return names.toList()
}
