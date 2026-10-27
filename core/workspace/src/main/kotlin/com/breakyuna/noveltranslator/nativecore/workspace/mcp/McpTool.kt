package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.requireWellFormedUnicode
import java.security.MessageDigest
import java.util.Collections

/**
 * Deterministic model-facing names for external MCP tools. The model contract allows only
 * `[A-Za-z0-9_-]{1,64}`; external characters outside that set become `_`. Long names end in
 * a SHA-256 suffix so truncation cannot silently merge two tools. Two external names that
 * still collide after sanitizing are rejected by [McpClient] rather than registered twice.
 */
object McpToolNames {
    private val MODEL_NAME = Regex("[A-Za-z0-9_-]{1,64}")

    fun modelName(serverId: String, externalName: String): String {
        val base = "mcp_${sanitize(serverId)}_${sanitize(externalName)}"
        if (base.length <= McpLimits.MAX_MODEL_TOOL_NAME_CHARS) return base
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverId\u0000$externalName".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
            .take(10)
        return base.take(McpLimits.MAX_MODEL_TOOL_NAME_CHARS - 11) + "_" + digest
    }

    internal fun isValidModelName(value: String): Boolean = MODEL_NAME.matches(value)

    private fun sanitize(value: String): String {
        val builder = StringBuilder(value.length + 1)
        for (character in value) {
            val allowed = character in 'A'..'Z' || character in 'a'..'z' ||
                character in '0'..'9' || character == '-' || character == '_'
            builder.append(if (allowed) character else '_')
        }
        return if (builder.isEmpty()) "_" else builder.toString()
    }
}

/**
 * One discovered MCP tool. [externalName] is the server's name and is used on the wire;
 * [modelName] is the bounded stable name a model may see. [description] and [inputSchema]
 * are external data and must never be treated as product instructions. [readOnly] comes
 * from `annotations.readOnlyHint` and defaults to false when absent.
 */
class McpToolDescriptor(
    val serverId: String,
    name: String,
    description: String,
    val inputSchema: JsonObjectValue,
    val readOnly: Boolean,
) {
    val name: String = name
    val externalName: String get() = name
    val description: String = McpText.sanitize(
        description,
        McpLimits.MAX_TOOL_DESCRIPTION_CHARS,
        allowNewlines = true,
    )
    val modelName: String = McpToolNames.modelName(serverId, name)

    init {
        require(serverId.isNotEmpty() && serverId.length <= McpLimits.MAX_SERVER_ID_CHARS) {
            "Invalid MCP server id"
        }
        require(serverId.none { it.isISOControl() || it.isWhitespace() }) { "Invalid MCP server id" }
        require(validExternalName(name)) { "Invalid MCP tool name" }
        require(McpToolNames.isValidModelName(modelName)) { "Invalid model tool name" }
    }

    /** No description or schema is rendered; both are untrusted external content. */
    override fun toString(): String =
        "McpToolDescriptor(server=$serverId, external=$name, model=$modelName, readOnly=$readOnly)"

    private companion object {
        fun validExternalName(value: String): Boolean =
            value.length in 1..McpLimits.MAX_TOOL_NAME_CHARS &&
                value.none { it.isISOControl() || it.isWhitespace() } &&
                runCatching { requireWellFormedUnicode(value) }.isSuccess
    }
}

/** One bounded tools/list result. [truncated] means the server had more tools than allowed. */
class McpToolCatalog(
    val serverId: String,
    tools: List<McpToolDescriptor>,
    val truncated: Boolean,
) {
    val tools: List<McpToolDescriptor> = Collections.unmodifiableList(ArrayList(tools))

    init {
        require(serverId.isNotEmpty()) { "Invalid MCP server id" }
        require(this.tools.size <= McpLimits.MAX_TOOLS) { "Too many MCP tools" }
        require(this.tools.all { it.serverId == serverId }) { "Mixed MCP servers" }
        require(this.tools.map { it.modelName }.toSet().size == this.tools.size) {
            "Duplicate model tool name"
        }
    }

    val size: Int get() = tools.size

    override fun toString(): String =
        "McpToolCatalog(server=$serverId, tools=${tools.size}, truncated=$truncated)"
}

/** Bounded tools/call content. Result text is external data, never an instruction source. */
sealed interface McpContent {
    class Text(text: String) : McpContent {
        val text: String = text

        init {
            require(this.text.length <= McpLimits.MAX_TOOL_TEXT_CHARS) { "MCP text content too large" }
            require(this.text.none { it == '\u0000' }) { "Invalid MCP text content" }
            require(runCatching { requireWellFormedUnicode(this.text) }.isSuccess) {
                "Invalid MCP text content"
            }
        }

        override fun toString(): String = "McpContent.Text(chars=${text.length})"
    }

    /** An image, audio, resource or future content type that this search slice does not decode. */
    class Unsupported(val type: String) : McpContent {
        init {
            require(type.length in 1..64 && type.none { it.isISOControl() }) {
                "Invalid MCP content type"
            }
        }

        override fun toString(): String = "McpContent.Unsupported(type=$type)"
    }
}

/**
 * One tools/call result. [isError] is a tool-level failure reported by the server; transport
 * and JSON-RPC failures are exceptions instead. Content remains bounded and untrusted.
 */
class McpToolResult(
    val serverId: String,
    val toolName: String,
    val isError: Boolean,
    content: List<McpContent>,
    val structuredContent: JsonObjectValue?,
) {
    val content: List<McpContent> = Collections.unmodifiableList(ArrayList(content))

    init {
        require(serverId.isNotEmpty()) { "Invalid MCP server id" }
        require(toolName.isNotEmpty() && toolName.length <= McpLimits.MAX_TOOL_NAME_CHARS) {
            "Invalid MCP tool name"
        }
        require(this.content.size <= McpLimits.MAX_TOOL_CONTENT_ITEMS) { "Too many MCP content items" }
    }

    override fun toString(): String =
        "McpToolResult(server=$serverId, tool=$toolName, items=${content.size}, isError=$isError)"
}
