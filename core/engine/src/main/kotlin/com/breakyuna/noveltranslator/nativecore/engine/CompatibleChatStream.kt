package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Single-choice Chat Completions SSE. Frames are previews; only finish() produces a complete receipt. */
internal class CompatibleChatStream {
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private class Tool {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
    private val tools = sortedMapOf<Int, Tool>()
    private var semanticChars = 0L
    private var finishReason: String? = null
    private var usage: JsonObject? = null
    private var frames = 0

    suspend fun read(input: InputStream, emit: suspend (AgentModelEvent) -> Unit): String {
        try {
            readModelStreamEvents(input) { data, event ->
                currentCoroutineContext().ensureActive()
                if (data.trim() == "[DONE]") false
                else {
                    if (++frames > 32_768) fail(ModelFailureKind.CONTENT)
                    if (event != "ping" && event != "heartbeat") accept(data, event, emit)
                    true
                }
            }
            return finish()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (classified: AgentModelFailure) { throw classified }
        catch (error: java.io.IOException) { throw error }
        catch (_: Exception) { fail(ModelFailureKind.PARSING) }
    }

    private suspend fun accept(data: String, event: String, emit: suspend (AgentModelEvent) -> Unit) {
        val root = JsonObjectValue.parse(data, JsonInputLimits(512 * 1024, 48)).json
        if (event == "error" || root["error"]?.let { it != JsonNull } == true) fail(ModelFailureKind.SERVER)
        if (event.isNotEmpty() && event != "message") fail(ModelFailureKind.PARSING)
        root["usage"]?.takeUnless { it == JsonNull }?.let { usage = it.jsonObject }
        val choices = root["choices"] as? JsonArray ?: fail(ModelFailureKind.PARSING)
        if (choices.isEmpty()) {
            if (root["usage"] == null || root["usage"] == JsonNull) fail(ModelFailureKind.PARSING)
            return
        }
        if (choices.size != 1) fail(ModelFailureKind.PARSING)
        val choice = choices.single().jsonObject
        if (integer(choice["index"], "choice", 0, 0) != 0L) fail(ModelFailureKind.PARSING)
        if (finishReason != null) fail(ModelFailureKind.PARSING)
        val delta = choice["delta"]?.takeUnless { it == JsonNull }?.jsonObject ?: buildJsonObject {}
        delta["role"]?.takeUnless { it == JsonNull }?.let { if (string(it) != "assistant") fail(ModelFailureKind.PARSING) }
        if (delta["function_call"]?.let { it != JsonNull } == true) fail(ModelFailureKind.CONTENT)
        delta["content"]?.takeUnless { it == JsonNull }?.let {
            val value = string(it); append(text, value)
            if (value.isNotEmpty()) emit(AgentModelEvent.TextDelta(value))
        }
        delta["reasoning_content"]?.takeUnless { it == JsonNull }?.let {
            val value = string(it); append(thinking, value)
            if (value.isNotEmpty()) emit(AgentModelEvent.ThinkingDelta(value))
        }
        delta["tool_calls"]?.takeUnless { it == JsonNull }?.jsonArray?.forEach { element ->
            val part = element.jsonObject
            val index = integer(part["index"], "tool", 0, 7).toInt()
            val tool = tools.getOrPut(index) { Tool() }
            part["type"]?.takeUnless { it == JsonNull }?.let { if (string(it) != "function") fail(ModelFailureKind.PARSING) }
            part["id"]?.takeUnless { it == JsonNull }?.let {
                val value = string(it)
                // Some compatible servers repeat the complete ID on every fragment.
                if (tool.id.toString() != value) append(tool.id, value)
                if (tool.id.length > 512) fail(ModelFailureKind.CONTENT)
            }
            part["function"]?.takeUnless { it == JsonNull }?.jsonObject?.let { function ->
                function["name"]?.takeUnless { it == JsonNull }?.let {
                    val value = string(it)
                    // Accept actual name fragments, but ignore a repeated complete name just like IDs.
                    if (tool.name.toString() != value) append(tool.name, value)
                }
                function["arguments"]?.takeUnless { it == JsonNull }?.let { append(tool.arguments, string(it)) }
                if (tool.name.length > 200 || tool.arguments.length > 128 * 1024) fail(ModelFailureKind.CONTENT)
            }
            emit(AgentModelEvent.ToolCallDelta(index))
        }
        choice["finish_reason"]?.takeUnless { it == JsonNull }?.let {
            val reason = string(it)
            if (reason !in setOf("stop", "tool_calls", "length", "content_filter")) fail(ModelFailureKind.PARSING)
            finishReason = reason
        }
    }

    private fun finish(): String {
        val reason = finishReason ?: fail(ModelFailureKind.TRUNCATED)
        if (reason == "tool_calls" && (tools.isEmpty() || tools.keys.toList() != (0 until tools.size).toList())) fail(ModelFailureKind.PARSING)
        return buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("finish_reason", reason)
                put("message", buildJsonObject {
                    put("content", text.toString()); put("reasoning_content", thinking.toString())
                    // Truncated/filtered receipts retain known text/usage, never partial executable tools.
                    if (tools.isNotEmpty() && reason !in setOf("length", "content_filter")) put("tool_calls", JsonArray(tools.values.map { tool ->
                        if (tool.id.isBlank() || tool.name.isBlank()) fail(ModelFailureKind.PARSING)
                        buildJsonObject {
                            put("id", tool.id.toString()); put("type", "function")
                            put("function", buildJsonObject { put("name", tool.name.toString()); put("arguments", tool.arguments.toString()) })
                        }
                    }))
                })
            })))
            usage?.let { put("usage", it) }
        }.toString()
    }

    private fun append(builder: StringBuilder, value: String) {
        semanticChars += value.length
        if (semanticChars > 256 * 1024) fail(ModelFailureKind.CONTENT)
        builder.append(value)
    }
    private fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(ModelFailureKind.PARSING)
    private fun integer(value: JsonElement?, name: String, min: Long, max: Long): Long {
        val primitive = value as? JsonPrimitive ?: fail(ModelFailureKind.PARSING)
        if (primitive.isString) fail(ModelFailureKind.PARSING)
        return ToolArguments.long(ToolScalar.NumberToken(primitive.content), name, min, max)
    }
    private fun fail(kind: ModelFailureKind): Nothing = throw AgentModelFailure(kind)
}

/** SSE framing: UTF-8, optional BOM, LF/CRLF/CR, multiline data, comments and bounded fields. No reconnect. */
internal suspend fun readModelStreamEvents(input: InputStream, event: suspend (String, String) -> Boolean) {
    val bounded = object : InputStream() {
        var bytes = 0L
        override fun read(): Int = input.read().also { if (it >= 0) count(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length).also { if (it > 0) count(it) }
        fun count(size: Int) {
            bytes += size
            if (bytes > 8L * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
        }
    }
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    val reader = InputStreamReader(bounded, decoder)
    val line = StringBuilder()
    val data = StringBuilder()
    var type = ""
    var hasData = false
    var skipLf = false
    var first = true
    suspend fun consumeLine(): Boolean {
        if (line.isEmpty()) {
            val keepReading = if (hasData) event(data.toString().removeSuffix("\n"), type) else true
            data.setLength(0); hasData = false; type = ""
            return keepReading
        }
        if (line[0] != ':') {
            val raw = line.toString()
            val colon = raw.indexOf(':')
            val field = if (colon < 0) raw else raw.substring(0, colon)
            val value = if (colon < 0) "" else raw.substring(colon + 1).removePrefix(" ")
            when (field) {
                "data" -> {
                    if (data.length.toLong() + value.length + 1 > 512L * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
                    data.append(value).append('\n'); hasData = true
                }
                "event" -> type = value
            }
        }
        line.setLength(0)
        return true
    }
    val buffer = CharArray(4096)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = try { reader.read(buffer) }
            catch (_: java.nio.charset.CharacterCodingException) { throw AgentModelFailure(ModelFailureKind.PARSING) }
        if (count < 0) break
        for (index in 0 until count) {
            val char = buffer[index]
            if (first) { first = false; if (char == '\uFEFF') continue }
            if (skipLf) { skipLf = false; if (char == '\n') continue }
            if (char == '\r' || char == '\n') {
                if (!consumeLine()) return
                skipLf = char == '\r'
            } else {
                if (line.length >= 512 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
                line.append(char)
            }
        }
    }
    // An event without its terminating blank line is not dispatched (SSE specification).
}
