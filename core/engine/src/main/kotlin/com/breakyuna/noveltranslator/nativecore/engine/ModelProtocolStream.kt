package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.InputStream
import kotlinx.serialization.json.*

/** Preview deltas never authorize tools. Only a terminal, bounded response reaches the codec. */
internal class ModelProtocolStream(private val protocol: ModelProtocol) {
    suspend fun read(input: InputStream, emit: suspend (AgentModelEvent) -> Unit): String {
        return try { readBounded(input, emit) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (classified: AgentModelFailure) { throw classified }
        catch (io: java.io.IOException) { throw io }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    private suspend fun readBounded(input: InputStream, emit: suspend (AgentModelEvent) -> Unit): String {
        if (protocol == ModelProtocol.SAKURA_LLM || protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            return CompatibleChatStream().read(input, emit)
        var frames = 0
        var terminal: JsonObject? = null
        var message: JsonObject? = null
        val blocks = sortedMapOf<Int, JsonObject>()
        val fragments = mutableMapOf<Int, StringBuilder>()
        val closed = mutableSetOf<Int>()
        val usage = linkedMapOf<String, JsonElement>()
        val geminiParts = mutableListOf<JsonElement>()
        var geminiCandidate: JsonObject? = null
        var geminiRoot: JsonObject? = null
        var geminiFinished = false
        var toolCount = 0
        fun fail(kind: ModelFailureKind = ModelFailureKind.PARSING): Nothing = throw AgentModelFailure(kind)
        fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
        fun JsonObject.index(): Int {
            val value = this["index"] as? JsonPrimitive ?: fail()
            if (value.isString) fail()
            return ToolArguments.long(ToolScalar.NumberToken(value.content), "index", 0, 255).toInt()
        }
        readModelStreamEvents(input) { data, event ->
            if (++frames > 32_768) fail(ModelFailureKind.CONTENT)
            if (event == "ping" || event == "heartbeat") return@readModelStreamEvents true
            val root = JsonObjectValue.parse(data, JsonInputLimits(512 * 1024, 48)).json
            if (root["error"]?.let { it != JsonNull } == true || event == "error") fail(ModelFailureKind.SERVER)
            when (protocol) {
                ModelProtocol.OPENAI_RESPONSES -> {
                    when (val type = root.string("type")) {
                        "response.output_text.delta" -> emit(AgentModelEvent.TextDelta(root.string("delta")))
                        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                            emit(AgentModelEvent.ThinkingDelta(root.string("delta")))
                        "response.output_item.added" -> {
                            val item = root["item"] as? JsonObject ?: fail()
                            if ((item["type"] as? JsonPrimitive)?.content == "function_call") {
                                if (toolCount >= 8) fail(ModelFailureKind.CONTENT)
                                emit(AgentModelEvent.ToolCallDelta(toolCount++))
                            }
                        }
                        "response.completed", "response.incomplete", "response.failed", "response.cancelled" -> {
                            terminal = root["response"] as? JsonObject ?: fail()
                            return@readModelStreamEvents false
                        }
                        "error" -> fail(ModelFailureKind.SERVER)
                        else -> if (!type.startsWith("response.")) fail()
                    }
                }
                ModelProtocol.ANTHROPIC_MESSAGES -> when (root.string("type")) {
                    "message_start" -> {
                        if (message != null) fail()
                        message = root["message"] as? JsonObject ?: fail()
                        (message!!["usage"] as? JsonObject)?.let { usage.putAll(it) }
                    }
                    "content_block_start" -> {
                        if (message == null) fail()
                        val index = root.index()
                        if (index != blocks.size || index in blocks) fail()
                        val block = root["content_block"] as? JsonObject ?: fail()
                        blocks[index] = block
                        when (block.string("type")) {
                            "text" -> block.string("text").takeIf(String::isNotEmpty)?.let { emit(AgentModelEvent.TextDelta(it)) }
                            "thinking" -> block.string("thinking").takeIf(String::isNotEmpty)?.let { emit(AgentModelEvent.ThinkingDelta(it)) }
                            "tool_use" -> { if (toolCount >= 8) fail(ModelFailureKind.CONTENT); emit(AgentModelEvent.ToolCallDelta(toolCount++)) }
                            "redacted_thinking" -> Unit
                            else -> fail()
                        }
                    }
                    "content_block_delta" -> {
                        val index = root.index()
                        val block = blocks[index] ?: fail()
                        if (index in closed) fail()
                        val delta = root["delta"] as? JsonObject ?: fail()
                        val type = delta.string("type")
                        val (field, value) = when (type) {
                            "text_delta" -> "text" to delta.string("text")
                            "thinking_delta" -> "thinking" to delta.string("thinking")
                            "input_json_delta" -> "input" to delta.string("partial_json")
                            "signature_delta" -> "signature" to delta.string("signature")
                            else -> fail()
                        }
                        if (field == "input") {
                            if (block.string("type") != "tool_use") fail()
                            val buffer = fragments.getOrPut(index) { StringBuilder() }
                            if (buffer.length.toLong() + value.length > 128 * 1024) fail(ModelFailureKind.CONTENT)
                            buffer.append(value)
                        } else {
                            val expected = if (field == "signature") "thinking" else field
                            if (block.string("type") != expected) fail()
                            val previous = (block[field] as? JsonPrimitive)?.content.orEmpty()
                            if (previous.length.toLong() + value.length > 256 * 1024) fail(ModelFailureKind.CONTENT)
                            blocks[index] = JsonObject(block + (field to JsonPrimitive(previous + value)))
                        }
                        if (type == "text_delta") emit(AgentModelEvent.TextDelta(value))
                        if (type == "thinking_delta") emit(AgentModelEvent.ThinkingDelta(value))
                    }
                    "content_block_stop" -> {
                        val index = root.index()
                        if (index !in blocks || !closed.add(index)) fail()
                        fragments[index]?.let { json ->
                            val arguments = JsonObjectValue.parse(json.toString(), JsonInputLimits(128 * 1024, 32)).json
                            blocks[index] = JsonObject(blocks.getValue(index) + ("input" to arguments))
                        }
                    }
                    "message_delta" -> {
                        val previous = message ?: fail()
                        if (closed.size != blocks.size) fail()
                        message = JsonObject(previous + (root["delta"] as? JsonObject ?: fail()))
                        (root["usage"] as? JsonObject)?.let { usage.putAll(it) }
                    }
                    "message_stop" -> {
                        val previous = message ?: fail()
                        if (closed.size != blocks.size || previous["stop_reason"] == null || previous["stop_reason"] == JsonNull)
                            fail(ModelFailureKind.TRUNCATED)
                        terminal = JsonObject(previous + mapOf("content" to JsonArray(blocks.values.toList()), "usage" to JsonObject(usage)))
                        return@readModelStreamEvents false
                    }
                    "ping" -> Unit
                    else -> fail()
                }
                ModelProtocol.GOOGLE_GEMINI -> {
                    geminiRoot = root
                    (root["usageMetadata"] as? JsonObject)?.let { usage.putAll(it) }
                    val candidates = root["candidates"] as? JsonArray
                    if (candidates != null && candidates.isNotEmpty()) {
                        if (candidates.size != 1) fail()
                        val candidate = candidates.single() as? JsonObject ?: fail()
                        val content = candidate["content"] as? JsonObject
                        val parts = content?.get("parts") as? JsonArray
                        if (geminiFinished && !parts.isNullOrEmpty()) fail()
                        parts.orEmpty().forEach { element ->
                            val part = element as? JsonObject ?: fail()
                            // Join adjacent text fragments to keep the canonical part count bounded.
                            val previous = geminiParts.lastOrNull() as? JsonObject
                            val value = (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            if (value != null && previous?.get("text") is JsonPrimitive &&
                                previous["thought"] == part["thought"] && part.keys.all { it == "text" || it == "thought" }) {
                                val combined = previous.getValue("text").jsonPrimitive.content + value
                                if (combined.length > 256 * 1024) fail(ModelFailureKind.CONTENT)
                                geminiParts[geminiParts.lastIndex] = JsonObject(previous + ("text" to JsonPrimitive(combined)))
                            } else {
                                if (geminiParts.size >= 256) fail(ModelFailureKind.CONTENT)
                                geminiParts.add(part)
                            }
                            if (value != null) {
                                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) emit(AgentModelEvent.ThinkingDelta(value))
                                else emit(AgentModelEvent.TextDelta(value))
                            }
                            if (part["functionCall"] is JsonObject) {
                                if (toolCount >= 8) fail(ModelFailureKind.CONTENT)
                                emit(AgentModelEvent.ToolCallDelta(toolCount++))
                            }
                        }
                        geminiCandidate = JsonObject((geminiCandidate ?: JsonObject(emptyMap())) + candidate)
                        candidate["finishReason"]?.let { reason ->
                            val finish = (reason as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
                            if (finish.isNotBlank() && finish != "FINISH_REASON_UNSPECIFIED") geminiFinished = true
                        }
                    }
                    if (root["promptFeedback"] != null && candidates.isNullOrEmpty()) {
                        // The codec classifies blocked prompts; no partial output is accepted.
                        terminal = root
                    }
                }
                else -> fail()
            }
            true
        }
        if (protocol == ModelProtocol.GOOGLE_GEMINI && geminiFinished) {
            val candidate = geminiCandidate ?: throw AgentModelFailure(ModelFailureKind.TRUNCATED)
            terminal = JsonObject((geminiRoot ?: JsonObject(emptyMap())) + mapOf(
                "candidates" to JsonArray(listOf(JsonObject(candidate + ("content" to buildJsonObject {
                    put("role", "model"); put("parts", JsonArray(geminiParts))
                })))), "usageMetadata" to JsonObject(usage)))
        }
        return terminal?.toString() ?: throw AgentModelFailure(ModelFailureKind.TRUNCATED)
    }
}
