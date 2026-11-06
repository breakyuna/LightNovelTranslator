package com.breakyuna.noveltranslator.nativeapp

/**
 * Application-level Agent persona preference. This is not a project rule and is never written
 * into ProjectTextPolicy, project files, project backups, prompts-as-project-data or diagnostics.
 *
 * Only plain text is accepted: line breaks, carriage returns and tabs are allowed, while NUL and
 * other ISO control characters are rejected. The stored value is trimmed and capped.
 */
internal object NativeAppPersona {
    const val MAX_CHARS: Int = 4_000

    /** Validates and normalizes a value for explicit save; throws on unsupported input. */
    fun requireValid(raw: String): String {
        require(raw.none { character ->
            character == '\u0000' ||
                (character.isISOControl() && character != '\n' && character != '\r' && character != '\t')
        }) { "Persona contains a control character" }
        val text = raw.trim()
        require(text.length <= MAX_CHARS) { "Persona is too long" }
        requireWellFormedUnicode(text)
        return text
    }

    /** Loads a previously saved value defensively; malformed or invalid preferences read as empty. */
    fun normalize(raw: String?): String =
        runCatching { requireValid(raw.orEmpty()) }.getOrDefault("")

    fun isValid(raw: String): Boolean = runCatching { requireValid(raw) }.isSuccess

    private fun requireWellFormedUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "Unpaired surrogate in persona"
                    }
                    index += 2
                }
                character.isLowSurrogate() -> throw IllegalArgumentException("Unpaired surrogate in persona")
                else -> index++
            }
        }
    }
}
