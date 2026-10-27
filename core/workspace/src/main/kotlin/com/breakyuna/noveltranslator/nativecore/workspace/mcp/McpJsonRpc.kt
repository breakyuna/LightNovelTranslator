package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface McpJsonRpcMessage {
    class Response(val id: String, val result: JsonObject) : McpJsonRpcMessage

    class RpcError(val id: String, val code: Int) : McpJsonRpcMessage

    class Notification(val method: String) : McpJsonRpcMessage
}

/**
 * Strict JSON-RPC 2.0 framing for MCP. Responses are objects only: batches are rejected,
 * unknown top-level members are rejected, the echoed id must match, and server error text is
 * validated for shape but never retained or surfaced.
 */
internal object McpJsonRpc {
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val METHOD = Regex("[A-Za-z][A-Za-z0-9/_.-]{0,63}")
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    private val RESPONSE_KEYS = setOf("jsonrpc", "id", "result", "error")
    private val NOTIFICATION_KEYS = setOf("jsonrpc", "method", "params")
    private val ERROR_KEYS = setOf("code", "message", "data")

    fun request(id: String, method: String, params: JsonObject): String {
        require(ID.matches(id)) { "Invalid JSON-RPC id" }
        require(METHOD.matches(method)) { "Invalid JSON-RPC method" }
        val encoded = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        if (encoded.length > McpLimits.MAX_REQUEST_BYTES) throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        return encoded
    }

    fun notification(method: String, params: JsonObject): String {
        require(METHOD.matches(method)) { "Invalid JSON-RPC method" }
        val encoded = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params.isNotEmpty()) put("params", params)
        }.toString()
        if (encoded.length > McpLimits.MAX_REQUEST_BYTES) throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        return encoded
    }

    fun parse(response: McpTransportResponse, expectedId: String): JsonObject {
        if (response.body.length > McpLimits.MAX_RESPONSE_CHARS) {
            throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        }
        val media = response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when {
            media.isNullOrEmpty() || media == "application/json" || media.endsWith("+json") ->
                parseResponse(response.body, expectedId)
            media == "text/event-stream" -> parseEventStream(response.body, expectedId)
            else -> throw McpException(McpFailureKind.UNSUPPORTED_CONTENT)
        }
    }

    fun parseResponse(text: String, expectedId: String): JsonObject {
        val message = parseMessage(text)
        return when (message) {
            is McpJsonRpcMessage.Response -> {
                if (message.id != expectedId) throw McpException(McpFailureKind.PROTOCOL)
                message.result
            }
            is McpJsonRpcMessage.RpcError -> {
                if (message.id != expectedId) throw McpException(McpFailureKind.PROTOCOL)
                throw McpException(McpFailureKind.RPC_ERROR, rpcCode = message.code)
            }
            is McpJsonRpcMessage.Notification -> throw McpException(McpFailureKind.PROTOCOL)
        }
    }

    fun parseMessage(text: String): McpJsonRpcMessage {
        val root = parseObject(text)
        if (requireString(root, "jsonrpc") != "2.0") throwProtocol()
        if (root.containsKey("method")) {
            if (root.containsKey("id")) throwProtocol() // Server request: this client supports no server requests.
            if (root.keys.any { it !in NOTIFICATION_KEYS }) throwProtocol()
            val method = requireString(root, "method")
            if (!METHOD.matches(method)) throwProtocol()
            val params = root["params"]
            if (params != null && params !is JsonObject) throwProtocol()
            return McpJsonRpcMessage.Notification(method)
        }

        if (root.keys.any { it !in RESPONSE_KEYS }) throwProtocol()
        val id = requireId(root)
        val hasResult = root.containsKey("result")
        val hasError = root.containsKey("error")
        if (hasResult == hasError) throwProtocol()
        if (hasResult) {
            val result = root["result"] as? JsonObject ?: throwProtocol()
            return McpJsonRpcMessage.Response(id, result)
        }
        val error = root["error"] as? JsonObject ?: throwProtocol()
        if (error.keys.any { it !in ERROR_KEYS }) throwProtocol()
        val code = integer(error["code"])
        val message = requireString(error, "message")
        if (message.isEmpty() || message.length > 1_024 || message.contains('\u0000')) throwProtocol()
        return McpJsonRpcMessage.RpcError(id, code)
    }

    private fun parseEventStream(text: String, expectedId: String): JsonObject {
        val events = McpSse.parse(text)
        for (event in events) {
            if (event.type == "ping" || event.type == "heartbeat") continue
            if (event.type.isNotEmpty() && event.type != "message" && event.type != "error") {
                throwProtocol()
            }
            if (event.data.isBlank()) continue
            when (val message = parseMessage(event.data)) {
                is McpJsonRpcMessage.Response -> {
                    if (message.id != expectedId) throwProtocol()
                    return message.result
                }
                is McpJsonRpcMessage.RpcError -> {
                    if (message.id != expectedId) throwProtocol()
                    throw McpException(McpFailureKind.RPC_ERROR, rpcCode = message.code)
                }
                is McpJsonRpcMessage.Notification -> Unit
            }
        }
        throwProtocol()
    }

    private fun parseObject(text: String): JsonObject {
        if (text.length > McpLimits.MAX_RESPONSE_CHARS) {
            throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        }
        var trimmed = text.trim()
        if (trimmed.startsWith('\uFEFF')) trimmed = trimmed.substring(1).trim()
        if (trimmed.isEmpty()) throwProtocol()
        if (trimmed.startsWith('[')) throw McpException(McpFailureKind.BATCH_NOT_SUPPORTED)
        val parsed = try {
            JsonObjectValue.parse(
                trimmed,
                JsonInputLimits(McpLimits.MAX_RESPONSE_CHARS, McpLimits.MAX_JSON_DEPTH),
            )
        } catch (invalid: InvalidJsonPayload) {
            throw McpException(
                if (invalid.reason == InvalidJsonPayload.Reason.SIZE) {
                    McpFailureKind.CONTENT_TOO_LARGE
                } else {
                    McpFailureKind.PROTOCOL
                },
            )
        } catch (_: Exception) {
            throw McpException(McpFailureKind.PROTOCOL)
        }
        return parsed.json
    }

    private fun requireString(value: JsonObject, key: String): String {
        val primitive = value[key] as? JsonPrimitive ?: throwProtocol()
        if (!primitive.isString) throwProtocol()
        return primitive.content
    }

    private fun requireId(value: JsonObject): String {
        val id = requireString(value, "id")
        if (!ID.matches(id)) throwProtocol()
        return id
    }

    private fun integer(value: kotlinx.serialization.json.JsonElement?): Int {
        val primitive = value as? JsonPrimitive ?: throwProtocol()
        if (primitive.isString || !INTEGER.matches(primitive.content)) throwProtocol()
        val parsed = primitive.content.toLongOrNull() ?: throwProtocol()
        if (parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throwProtocol()
        return parsed.toInt()
    }

    private fun throwProtocol(): Nothing = throw McpException(McpFailureKind.PROTOCOL)
}

internal class McpSseEvent(val type: String, val data: String)

/**
 * Minimal SSE framing for JSON-RPC over `text/event-stream`. Only data/event fields matter;
 * comments, id and retry are ignored, and the final unterminated event is dispatched at EOF
 * because the response body is already complete. Event data and count are bounded.
 */
internal object McpSse {
    private const val MAX_EVENTS = 128
    private const val MAX_EVENT_CHARS = 1024 * 1024

    fun parse(body: String): List<McpSseEvent> {
        if (body.length > McpLimits.MAX_RESPONSE_CHARS) throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        val events = ArrayList<McpSseEvent>()
        val data = StringBuilder()
        var type = ""
        var hasData = false
        var index = if (body.startsWith('\uFEFF')) 1 else 0

        fun dispatch() {
            if (!hasData) {
                data.setLength(0)
                type = ""
                return
            }
            if (events.size >= MAX_EVENTS) throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
            events += McpSseEvent(type, data.toString().removeSuffix("\n"))
            data.setLength(0)
            type = ""
            hasData = false
        }

        while (index < body.length) {
            val start = index
            while (index < body.length && body[index] != '\r' && body[index] != '\n') index += 1
            val line = body.substring(start, index)
            if (index < body.length) {
                index += if (body[index] == '\r' && index + 1 < body.length && body[index + 1] == '\n') 2 else 1
            }
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(':')) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "data" -> {
                    if (data.length.toLong() + value.length.toLong() + 1L > MAX_EVENT_CHARS.toLong()) {
                        throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
                    }
                    data.append(value).append('\n')
                    hasData = true
                }
                "event" -> {
                    if (value.length > 64 || value.any { it.isISOControl() }) {
                        throw McpException(McpFailureKind.PROTOCOL)
                    }
                    type = value
                }
            }
        }
        dispatch()
        return events
    }
}
