package com.breakyuna.noveltranslator.nativecore.workspace.mcp

/**
 * Absolute bounds for one MCP endpoint connection. They are protocol safety limits, not
 * measured device budgets: every caller may request less, never more.
 */
object McpLimits {
    /** HTTP response body ceiling; the same class of limit as the model HTTP transport. */
    const val MAX_RESPONSE_BYTES: Long = 2L * 1024 * 1024
    const val MAX_RESPONSE_CHARS: Int = 2 * 1024 * 1024

    /** A tools/call argument tree is small compared with a model request. */
    const val MAX_REQUEST_BYTES: Int = 1 * 1024 * 1024
    const val MAX_JSON_DEPTH: Int = 64

    const val MAX_TOOLS: Int = 256
    const val DEFAULT_MAX_TOOLS: Int = 64
    const val MAX_TOOL_PAGES: Int = 64
    const val MAX_CURSOR_CHARS: Int = 1024
    const val MAX_TOOL_NAME_CHARS: Int = 128
    const val MAX_MODEL_TOOL_NAME_CHARS: Int = 64
    const val MAX_TOOL_DESCRIPTION_CHARS: Int = 4_096
    const val MAX_SCHEMA_CHARS: Int = 128 * 1024
    const val MAX_TOOL_CONTENT_ITEMS: Int = 64
    const val MAX_TOOL_TEXT_CHARS: Int = 512 * 1024
    const val MAX_TOOL_RESULT_CHARS: Int = 2 * 1024 * 1024

    const val MAX_SESSION_ID_CHARS: Int = 256
    const val MAX_PROTOCOL_VERSION_CHARS: Int = 32
    const val MAX_INSTRUCTIONS_CHARS: Int = 32 * 1024
    const val MAX_SERVER_ID_CHARS: Int = 64
    const val MAX_SERVER_NAME_CHARS: Int = 128
    const val MAX_URL_CHARS: Int = 2_048
    const val MAX_CONFIG_CHARS: Int = 128 * 1024

    const val MIN_TIMEOUT_MILLIS: Long = 1_000
    const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
    const val MAX_RETRY_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000
}

/**
 * Protocol revisions this client understands. The preferred value is deliberately the
 * widely deployed revision; a newer supported revision is accepted when a server selects
 * it during initialization. Unknown revisions fail closed instead of being guessed.
 */
object McpProtocolVersions {
    const val PREFERRED: String = "2025-06-18"

    val SUPPORTED: Set<String> = setOf(
        "2025-11-25",
        "2025-06-18",
        "2025-03-26",
        "2024-11-05",
    )

    fun isSupported(version: String): Boolean = version in SUPPORTED
}
