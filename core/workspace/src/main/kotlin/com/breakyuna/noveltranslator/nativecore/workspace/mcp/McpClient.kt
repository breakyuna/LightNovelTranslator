package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.requireWellFormedUnicode
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Narrow tool-call boundary used by [McpSearchProvider]; [McpClient] is the production owner. */
interface McpToolInvoker {
    suspend fun callTool(
        toolName: String,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolResult
}

/**
 * Result of a successful MCP initialize handshake. [instructions] is external server text,
 * not a product instruction. [hasToolsCapability] must be true before tools/list or tools/call.
 */
class McpSessionInfo internal constructor(
    val protocolVersion: String,
    val serverName: String,
    val serverVersion: String,
    val instructions: String?,
    val hasToolsCapability: Boolean,
    val sessionId: String?,
) {
    init {
        require(protocolVersion.isNotEmpty()) { "Invalid MCP protocol version" }
        require(serverName.isNotEmpty()) { "Invalid MCP server name" }
        require(serverVersion.isNotEmpty()) { "Invalid MCP server version" }
    }

    override fun toString(): String =
        "McpSessionInfo(protocol=$protocolVersion, server=$serverName/$serverVersion, " +
            "tools=$hasToolsCapability, session=${sessionId != null})"
}

/**
 * MCP client for one configured endpoint. The handshake is initialized once, tools/list
 * follows cursors up to [McpServerConfig.maxTools], and tools/call never retries. JSON-RPC
 * ids come from one process-unique sequence; every response id is checked.
 */
class McpClient(
    private val config: McpServerConfig,
    private val transport: McpTransport,
) : McpToolInvoker {
    private val requestIds = AtomicLong(0)
    private val stateLock = Mutex()

    @Volatile
    private var session: McpSessionInfo? = null

    suspend fun initialize(cancellation: WorkspaceCancellation = WorkspaceCancellation()): McpSessionInfo =
        initialize(cancellation, System.currentTimeMillis() + config.timeoutMillis)

    suspend fun initialize(cancellation: WorkspaceCancellation, deadlineAtMillis: Long): McpSessionInfo {
        requireUsable(cancellation, deadlineAtMillis)
        return stateLock.withLock {
            session ?: initializeLocked(cancellation, deadlineAtMillis).also { session = it }
        }
    }

    suspend fun listTools(cancellation: WorkspaceCancellation = WorkspaceCancellation()): McpToolCatalog =
        listTools(cancellation, System.currentTimeMillis() + config.timeoutMillis)

    suspend fun listTools(cancellation: WorkspaceCancellation, deadlineAtMillis: Long): McpToolCatalog {
        requireUsable(cancellation, deadlineAtMillis)
        return stateLock.withLock { listToolsLocked(cancellation, deadlineAtMillis) }
    }

    suspend fun callTool(
        tool: McpToolDescriptor,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
    ): McpToolResult = callTool(tool, arguments, cancellation, System.currentTimeMillis() + config.timeoutMillis)

    suspend fun callTool(
        tool: McpToolDescriptor,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolResult {
        requireUsable(cancellation, deadlineAtMillis)
        if (tool.serverId != config.id || tool.externalName !in config.allowedTools) {
            throw McpException(McpFailureKind.TOOL_NOT_ALLOWED)
        }
        return callTool(tool.externalName, arguments, cancellation, deadlineAtMillis)
    }

    suspend fun callTool(
        toolName: String,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
    ): McpToolResult = callTool(toolName, arguments, cancellation, System.currentTimeMillis() + config.timeoutMillis)

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolResult {
        requireUsable(cancellation, deadlineAtMillis)
        if (toolName !in config.allowedTools) throw McpException(McpFailureKind.TOOL_NOT_ALLOWED)
        val current = session ?: throw McpException(McpFailureKind.NOT_INITIALIZED)
        if (!current.hasToolsCapability) throw McpException(McpFailureKind.CAPABILITY_MISSING)

        val id = nextId()
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments.json)
        }
        val response = send(
            body = McpJsonRpc.request(id, "tools/call", params),
            sessionId = current.sessionId,
            protocolVersion = current.protocolVersion,
            cancellation = cancellation,
            deadlineAtMillis = deadlineAtMillis,
            expectsResponse = true,
        )
        return parseToolResult(McpJsonRpc.parse(response, id), toolName)
    }

    private suspend fun initializeLocked(
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpSessionInfo {
        val id = nextId()
        val params = buildJsonObject {
            put("protocolVersion", config.protocolVersion ?: McpProtocolVersions.PREFERRED)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "LightNovelTranslator")
                put("version", "native")
            })
        }
        val response = send(
            body = McpJsonRpc.request(id, "initialize", params),
            sessionId = null,
            protocolVersion = null,
            cancellation = cancellation,
            deadlineAtMillis = deadlineAtMillis,
            expectsResponse = true,
        )
        val info = parseInitializeResult(McpJsonRpc.parse(response, id), response.sessionId)
        // MCP requires the initialized notification after a successful handshake; it has no id
        // and must not be retried. A failure leaves the client uninitialized.
        send(
            body = McpJsonRpc.notification("notifications/initialized", buildJsonObject { }),
            sessionId = info.sessionId,
            protocolVersion = info.protocolVersion,
            cancellation = cancellation,
            deadlineAtMillis = deadlineAtMillis,
            expectsResponse = false,
        )
        return info
    }

    private suspend fun listToolsLocked(
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolCatalog {
        val current = session ?: throw McpException(McpFailureKind.NOT_INITIALIZED)
        if (!current.hasToolsCapability) throw McpException(McpFailureKind.CAPABILITY_MISSING)

        val collected = LinkedHashMap<String, McpToolDescriptor>()
        val seenCursors = LinkedHashSet<String>()
        var cursor: String? = null
        var truncated = false
        var pages = 0
        while (true) {
            if (++pages > McpLimits.MAX_TOOL_PAGES) throw McpException(McpFailureKind.PROTOCOL)
            val id = nextId()
            val nextCursor = cursor
            val params = if (nextCursor == null) {
                buildJsonObject { }
            } else {
                buildJsonObject { put("cursor", nextCursor) }
            }
            val response = send(
                body = McpJsonRpc.request(id, "tools/list", params),
                sessionId = current.sessionId,
                protocolVersion = current.protocolVersion,
                cancellation = cancellation,
                deadlineAtMillis = deadlineAtMillis,
                expectsResponse = true,
            )
            val page = parseToolsPage(McpJsonRpc.parse(response, id))
            for (tool in page.tools) {
                if (collected.size >= config.maxTools) {
                    truncated = true
                    break
                }
                if (collected.containsKey(tool.modelName)) throw McpException(McpFailureKind.NAME_CONFLICT)
                collected[tool.modelName] = tool
            }
            if (truncated) break
            val next = page.nextCursor ?: break
            if (!seenCursors.add(next)) throw McpException(McpFailureKind.PROTOCOL)
            cursor = next
            if (collected.size >= config.maxTools) {
                truncated = true
                break
            }
        }
        return McpToolCatalog(config.id, collected.values.toList(), truncated)
    }

    private suspend fun send(
        body: String,
        sessionId: String?,
        protocolVersion: String?,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
        expectsResponse: Boolean,
    ): McpTransportResponse {
        if (!config.enabled) throw McpException(McpFailureKind.DISABLED)
        if (cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        currentCoroutineContext().ensureActive()
        if (deadlineAtMillis <= System.currentTimeMillis()) throw McpException(McpFailureKind.TIMEOUT)
        val response = try {
            transport.send(
                McpTransportRequest(
                    body = body,
                    sessionId = sessionId,
                    protocolVersion = protocolVersion,
                    deadlineAtMillis = deadlineAtMillis,
                    cancellation = cancellation,
                    expectsResponse = expectsResponse,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: McpException) {
            throw failure
        } catch (_: Exception) {
            throw McpException(McpFailureKind.INTERNAL)
        }
        if (cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        if (response.body.length > McpLimits.MAX_RESPONSE_CHARS) {
            throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        }
        if (System.currentTimeMillis() > deadlineAtMillis) throw McpException(McpFailureKind.TIMEOUT)
        return response
    }

    private suspend fun requireUsable(cancellation: WorkspaceCancellation, deadlineAtMillis: Long) {
        if (!config.enabled) throw McpException(McpFailureKind.DISABLED)
        if (cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        currentCoroutineContext().ensureActive()
        if (deadlineAtMillis <= System.currentTimeMillis()) throw McpException(McpFailureKind.TIMEOUT)
    }

    private fun nextId(): String = "lnt-" + requestIds.incrementAndGet()

    private fun parseInitializeResult(result: JsonObject, sessionId: String?): McpSessionInfo {
        if (result.keys.any { it !in INIT_KEYS }) throwProtocol()
        val version = stringValue(result["protocolVersion"])
        if (version.length > McpLimits.MAX_PROTOCOL_VERSION_CHARS ||
            !McpProtocolVersions.isSupported(version)
        ) {
            throw McpException(McpFailureKind.UNSUPPORTED_PROTOCOL)
        }
        if (config.protocolVersion != null && config.protocolVersion != version) {
            throw McpException(McpFailureKind.UNSUPPORTED_PROTOCOL)
        }
        val capabilities = result["capabilities"] as? JsonObject ?: throwProtocol()
        val toolsElement = capabilities["tools"]
        val hasTools = toolsElement != null && toolsElement != JsonNull
        if (hasTools) {
            val tools = toolsElement as? JsonObject ?: throwProtocol()
            val changed = tools["listChanged"]
            if (changed != null && changed != JsonNull) booleanValue(changed)
        }
        val serverInfo = result["serverInfo"] as? JsonObject ?: throwProtocol()
        val serverName = McpText.sanitize(
            stringValue(serverInfo["name"]),
            McpLimits.MAX_SERVER_NAME_CHARS,
            allowNewlines = false,
        )
        val serverVersion = McpText.sanitize(stringValue(serverInfo["version"]), 128, allowNewlines = false)
        if (serverName.isEmpty() || serverVersion.isEmpty()) throwProtocol()
        val instructions = result["instructions"]
            ?.takeUnless { it == JsonNull }
            ?.let { McpText.sanitize(stringValue(it), McpLimits.MAX_INSTRUCTIONS_CHARS, allowNewlines = true) }
        return McpSessionInfo(version, serverName, serverVersion, instructions, hasTools, sessionId)
    }

    private fun parseToolsPage(result: JsonObject): ToolsPage {
        if (result.keys.any { it !in TOOLS_LIST_KEYS }) throwProtocol()
        val toolsElement = result["tools"] as? JsonArray ?: throwProtocol()
        if (toolsElement.size > McpLimits.MAX_TOOLS) throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        val tools = ArrayList<McpToolDescriptor>()
        for (element in toolsElement) {
            val tool = element as? JsonObject ?: throwProtocol()
            val name = stringValue(tool["name"])
            if (name !in config.allowedTools) continue
            tools += parseTool(tool, name)
        }
        val cursorElement = result["nextCursor"]
        val cursor = if (cursorElement == null || cursorElement == JsonNull) {
            null
        } else {
            val value = stringValue(cursorElement)
            if (value.isEmpty() || value.length > McpLimits.MAX_CURSOR_CHARS ||
                value.any { it.isISOControl() || it.isWhitespace() }
            ) {
                throwProtocol()
            }
            value
        }
        return ToolsPage(tools, cursor)
    }

    private fun parseTool(value: JsonObject, name: String): McpToolDescriptor {
        if (value.keys.any { it !in TOOL_KEYS }) throwProtocol()
        val description = value["description"]
            ?.takeUnless { it == JsonNull }
            ?.let { stringValue(it) }
            ?: ""
        val schema = value["inputSchema"] as? JsonObject ?: throwProtocol()
        val outputSchema = value["outputSchema"]
        if (outputSchema != null && outputSchema != JsonNull && outputSchema !is JsonObject) throwProtocol()
        val annotations = value["annotations"]
        val readOnly = if (annotations == null || annotations == JsonNull) {
            false
        } else {
            val objectValue = annotations as? JsonObject ?: throwProtocol()
            val hint = objectValue["readOnlyHint"]
            if (hint == null || hint == JsonNull) false else booleanValue(hint)
        }
        return try {
            McpToolDescriptor(
                serverId = config.id,
                name = name,
                description = description,
                inputSchema = jsonObjectValue(schema, McpLimits.MAX_SCHEMA_CHARS),
                readOnly = readOnly,
            )
        } catch (_: IllegalArgumentException) {
            throwProtocol()
        }
    }

    private fun parseToolResult(result: JsonObject, toolName: String): McpToolResult {
        if (result.keys.any { it !in TOOL_RESULT_KEYS }) throwProtocol()
        val contentElement = result["content"] as? JsonArray ?: throwProtocol()
        if (contentElement.size > McpLimits.MAX_TOOL_CONTENT_ITEMS) {
            throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        }
        val content = ArrayList<McpContent>()
        var textChars = 0L
        for (element in contentElement) {
            val item = element as? JsonObject ?: throwProtocol()
            val type = stringValue(item["type"])
            when (type) {
                "text" -> {
                    val text = stringValue(item["text"])
                    if (text.length > McpLimits.MAX_TOOL_TEXT_CHARS || text.contains('\u0000') ||
                        runCatching { requireWellFormedUnicode(text) }.isFailure
                    ) {
                        throwProtocol()
                    }
                    textChars += text.length.toLong()
                    if (textChars > McpLimits.MAX_TOOL_RESULT_CHARS.toLong()) {
                        throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
                    }
                    content += McpContent.Text(text)
                }
                "image", "audio" -> {
                    stringValue(item["data"])
                    stringValue(item["mimeType"])
                    content += unsupported(type)
                }
                "resource" -> {
                    item["resource"] as? JsonObject ?: throwProtocol()
                    content += unsupported(type)
                }
                "resource_link" -> {
                    stringValue(item["uri"])
                    content += unsupported(type)
                }
                else -> content += unsupported(type)
            }
        }
        val errorElement = result["isError"]
        val isError = if (errorElement == null || errorElement == JsonNull) {
            false
        } else {
            booleanValue(errorElement)
        }
        val structured = result["structuredContent"]
            ?.takeUnless { it == JsonNull }
            ?.let {
                val objectValue = it as? JsonObject ?: throwProtocol()
                jsonObjectValue(objectValue, McpLimits.MAX_SCHEMA_CHARS)
            }
        return McpToolResult(config.id, toolName, isError, content, structured)
    }

    private fun unsupported(type: String): McpContent.Unsupported {
        if (type.isEmpty() || type.length > 64 || type.any { it.isISOControl() }) throwProtocol()
        return McpContent.Unsupported(type)
    }

    private fun jsonObjectValue(value: JsonObject, maxChars: Int): JsonObjectValue {
        return try {
            JsonObjectValue.parse(value.toString(), JsonInputLimits(maxChars, McpLimits.MAX_JSON_DEPTH))
        } catch (invalid: InvalidJsonPayload) {
            throw McpException(
                if (invalid.reason == InvalidJsonPayload.Reason.SIZE) {
                    McpFailureKind.CONTENT_TOO_LARGE
                } else {
                    McpFailureKind.PROTOCOL
                },
            )
        } catch (_: Exception) {
            throwProtocol()
        }
    }

    private fun stringValue(value: JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: throwProtocol()
        if (!primitive.isString) throwProtocol()
        return primitive.content
    }

    private fun booleanValue(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: throwProtocol()
        if (primitive.isString) throwProtocol()
        return primitive.booleanOrNull ?: throwProtocol()
    }

    private fun throwProtocol(): Nothing = throw McpException(McpFailureKind.PROTOCOL)

    private class ToolsPage(val tools: List<McpToolDescriptor>, val nextCursor: String?)

    private companion object {
        val INIT_KEYS = setOf(
            "protocolVersion",
            "capabilities",
            "serverInfo",
            "instructions",
            "_meta",
        )
        val TOOLS_LIST_KEYS = setOf("tools", "nextCursor", "_meta", "ttlMs", "cacheScope")
        val TOOL_KEYS = setOf(
            "name",
            "title",
            "description",
            "inputSchema",
            "outputSchema",
            "annotations",
            "_meta",
            "icons",
            "execution",
        )
        val TOOL_RESULT_KEYS = setOf("content", "isError", "structuredContent", "_meta")
    }
}
