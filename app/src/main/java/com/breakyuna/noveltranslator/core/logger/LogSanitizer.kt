package com.breakyuna.noveltranslator.core.logger

/**
 * Single redaction boundary for everything this app persists: the runtime JSONL log, the
 * crash records and any text export built from them.
 *
 * AGENTS.md §5 forbids writing API keys, Authorization headers, signing secrets, novel
 * bodies or prompt/response payloads to logs, exceptions or debug data. Callers must not
 * hand raw provider text to [SystemLogger] or [com.breakyuna.noveltranslator.core.crash.CrashReporter]
 * without passing it through here first.
 */
internal object LogSanitizer {
    private val AUTHORIZATION = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;}]+")
    private val X_GOOG_API_KEY = Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)[^\\s,;}]+")
    private val LABELLED_SECRET =
        Regex("(?i)(api[_-]?key|token|secret|password|passphrase|credential)\\s*[:=]\\s*[^\\s,;}]+")
    /** Provider tokens that carry no label at all (OpenAI sk-..., Google AIza..., Anthropic sk-ant-...). */
    private val BARE_TOKEN = Regex("\\b(?:sk|rk|pk)-[A-Za-z0-9_\\-]{12,}\\b")
    private val GOOGLE_KEY = Regex("\\bAIza[0-9A-Za-z_\\-]{20,}\\b")
    /** Novel bodies and prompt/response payloads must never be persisted. */
    private val CONTENT_BLOCK =
        Regex("(?s)\\b(?:Original|Translation|Prompt|Response|Source|Target)\\s*:\\s*.{0,4000}")

    const val REDACTED = "[REDACTED]"

    /**
     * Redacts secrets and book content from [value]. Never throws: a failure here must not
     * take down the caller (the crash handler in particular), so the input is returned as is
     * when a pattern blows up.
     */
    fun sanitize(value: String): String = runCatching {
        value
            .replace(AUTHORIZATION, "$1$REDACTED")
            .replace(X_GOOG_API_KEY, "$1$REDACTED")
            .replace(LABELLED_SECRET, "$1=$REDACTED")
            .replace(BARE_TOKEN, REDACTED)
            .replace(GOOGLE_KEY, REDACTED)
            .replace(CONTENT_BLOCK, "[CONTENT $REDACTED]")
    }.getOrDefault(value)

    /** Collapses a multi-line value into one line so single-line record fields stay parseable. */
    fun singleLine(value: String): String = sanitize(value)
        .replace("\r", " ")
        .replace("\n", " ⏎ ")
        .take(MAX_SINGLE_LINE_CHARS)

    /** Hard cap for any persisted text block; the tail of a stack trace is the interesting part. */
    fun bounded(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars) + "\n…[truncated ${value.length - maxChars} chars]"

    const val MAX_SINGLE_LINE_CHARS = 2_000
}
