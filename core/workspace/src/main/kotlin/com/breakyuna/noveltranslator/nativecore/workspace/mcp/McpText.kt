package com.breakyuna.noveltranslator.nativecore.workspace.mcp

/** Bounded, encoding-safe cleanup of external MCP text before it enters a model-facing value. */
internal object McpText {
    fun sanitize(value: String, maxChars: Int, allowNewlines: Boolean): String {
        if (maxChars <= 0) return ""
        val builder = StringBuilder(minOf(value.length, maxChars))
        var index = 0
        while (index < value.length && builder.length < maxChars) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == 0 || codePoint in 0xD800..0xDFFF) continue
            val piece = when {
                codePoint == '\n'.code -> if (allowNewlines) "\n" else " "
                codePoint == '\r'.code -> if (allowNewlines) "\n" else " "
                codePoint == '\t'.code -> if (allowNewlines) "\t" else " "
                Character.isISOControl(codePoint) -> " "
                else -> String(Character.toChars(codePoint))
            }
            if (builder.length + piece.length > maxChars) break
            builder.append(piece)
        }
        return builder.toString().trim()
    }

    /** Returns the longest valid UTF-8 prefix within [maxBytes] and whether anything was cut. */
    fun truncateUtf8(value: String, maxBytes: Long): Pair<String, Boolean> {
        if (maxBytes <= 0) return "" to value.isNotEmpty()
        val builder = StringBuilder()
        var bytes = 0L
        var index = 0
        var truncated = false
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint in 0xD800..0xDFFF) {
                truncated = true
                continue
            }
            val piece = String(Character.toChars(codePoint))
            val size = piece.toByteArray(Charsets.UTF_8).size.toLong()
            if (bytes + size > maxBytes) {
                truncated = true
                break
            }
            builder.append(piece)
            bytes += size
        }
        return builder.toString() to truncated
    }
}
