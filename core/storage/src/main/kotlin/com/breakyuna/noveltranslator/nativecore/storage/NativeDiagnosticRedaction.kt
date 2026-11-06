package com.breakyuna.noveltranslator.nativecore.storage

/**
 * Final redaction boundary for [NativeDiagnosticsStore]. Callers must not treat this as
 * permission to hand prompts, response bodies, source text or credentials to diagnostics:
 * the store rejects those by design and only stores bounded operational facts.
 *
 * Redaction never throws. A failing pattern returns the (already bounded) input unchanged,
 * because losing a diagnostic is preferable to crashing the writer.
 */
internal object NativeDiagnosticRedaction {
    const val REDACTED = "[REDACTED]"

    private val AUTHORIZATION =
        Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic|token)\\s+)[^\\s,;}]+")
    private val X_GOOG_API_KEY = Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)[^\\s,;}]+")
    private val LABELLED_SECRET = Regex(
        "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|secret|" +
            "password|passphrase|credential)\\s*[:=]\\s*)[^\\s,;}]+",
    )
    /** Provider tokens with no label at all (OpenAI sk-..., Anthropic sk-ant-..., Google AIza...). */
    private val BARE_TOKEN = Regex("\\b(?:sk|rk|pk)-[A-Za-z0-9_\\-]{12,}\\b")
    private val GOOGLE_KEY = Regex("\\bAIza[0-9A-Za-z_\\-]{20,}\\b")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\b")
    private val PRIVATE_KEY = Regex(
        "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----",
    )
    /** Prompt/response/source labels are removed even if a caller accidentally passes them. */
    private val CONTENT_BLOCK =
        Regex("(?s)\\b(?:Original|Translation|Prompt|Response|Source|Target)\\s*:\\s*.{0,4000}")

    fun singleLine(value: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val bounded = boundInput(value, maxChars)
        return bound(sanitize(bounded).replace('\r', ' ').replace('\n', ' '), maxChars)
    }

    fun detail(value: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        return bound(sanitize(boundInput(value, maxChars)), maxChars)
    }

    private fun sanitize(value: String): String = runCatching {
        value
            .replace(PRIVATE_KEY) { REDACTED }
            .replace(AUTHORIZATION) { match -> match.groupValues[1] + REDACTED }
            .replace(X_GOOG_API_KEY) { match -> match.groupValues[1] + REDACTED }
            .replace(LABELLED_SECRET) { match -> match.groupValues[1] + REDACTED }
            .replace(BARE_TOKEN) { REDACTED }
            .replace(GOOGLE_KEY) { REDACTED }
            .replace(JWT) { REDACTED }
            .replace(CONTENT_BLOCK) { "[CONTENT $REDACTED]" }
    }.getOrDefault(value)

    private fun boundInput(value: String, maxChars: Int): String {
        val limit = maxChars.toLong() * 4L + 64L
        return if (value.length <= limit) value else value.substring(0, limit.toInt())
    }

    private fun bound(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 1) return value.take(maxChars)
        return value.take(maxChars - 1) + "…"
    }
}
