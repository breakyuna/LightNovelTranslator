package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelStopReason
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.ToolArguments
import com.breakyuna.noveltranslator.nativecore.domain.ToolScalar
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire protocols supported by the native model adapter. The enum is deliberately
 * independent of provider display names: one provider may speak several protocols
 * and one protocol is spoken by many compatible servers.
 */
enum class ModelProtocol {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    GOOGLE_GEMINI,
    ANTHROPIC_MESSAGES,
    SAKURA_LLM,
}

enum class ModelMessageRole { USER, ASSISTANT, TOOL }

/**
 * Provider tool-call identity and the exact argument string returned by the
 * provider. The string is preserved verbatim for Chat Completions replay; it is
 * validated as a bounded JSON object but never re-encoded here.
 */
class ModelToolCall(id: String, name: String, argumentsJson: String) {
    val id: String = id
    val name: String = name
    val argumentsJson: String = argumentsJson

    init {
        require(id.isNotBlank() && id.length <= MAX_TOOL_ID_CHARS && id.none(Char::isISOControl))
        requireToolName(name)
        require(argumentsJson.length <= MAX_TOOL_ARGUMENT_CHARS)
        require('\u0000' !in argumentsJson)
        requireValidUtf8(argumentsJson)
        try {
            JsonObjectValue.parse(argumentsJson, JsonInputLimits(MAX_TOOL_ARGUMENT_CHARS, 32))
        } catch (_: InvalidJsonPayload) {
            throw IllegalArgumentException("Invalid tool arguments")
        }
    }

    override fun toString(): String = "ModelToolCall(id=$id, name=$name, arguments=redacted)"
}

class ModelToolSpec(name: String, description: String, parameters: JsonObject) {
    val name: String = name
    val description: String = description
    val parameters: JsonObject = JsonObject(LinkedHashMap(parameters))

    init {
        requireToolName(name)
        require(description.length <= MAX_TOOL_DESCRIPTION_CHARS && '\u0000' !in description)
        requireValidUtf8(description)
    }

    override fun toString(): String = "ModelToolSpec(name=$name, parameters=redacted)"
}

/**
 * One neutral conversation message. System text is carried separately by
 * [ModelProtocolRequest] because Anthropic and Gemini use protocol-specific system
 * channels. Tool results require both the provider call id and the tool name:
 * Gemini identifies results by name, while OpenAI and Anthropic use the id.
 */
class ModelProtocolMessage(
    val role: ModelMessageRole,
    text: String,
    toolCalls: List<ModelToolCall> = emptyList(),
    toolCallId: String? = null,
    toolName: String? = null,
) {
    val text: String = text
    val toolCalls: List<ModelToolCall> = Collections.unmodifiableList(ArrayList(toolCalls))
    val toolCallId: String? = toolCallId
    val toolName: String? = toolName

    init {
        require(text.length <= MAX_TEXT_CHARS && '\u0000' !in text)
        requireValidUtf8(text)
        when (role) {
            ModelMessageRole.USER -> {
                require(text.isNotBlank() && this.toolCalls.isEmpty() && toolCallId == null && toolName == null)
            }
            ModelMessageRole.ASSISTANT -> {
                require((text.isNotEmpty() || this.toolCalls.isNotEmpty()) && toolCallId == null && toolName == null)
                require(this.toolCalls.map { it.id }.toSet().size == this.toolCalls.size)
            }
            ModelMessageRole.TOOL -> {
                require(this.toolCalls.isEmpty() && toolCallId != null && toolCallId.isNotBlank())
                val callId = requireNotNull(toolCallId)
                require(callId.length <= MAX_TOOL_ID_CHARS && callId.none(Char::isISOControl))
                requireToolName(requireNotNull(toolName))
            }
        }
    }

    override fun toString(): String =
        "ModelProtocolMessage(role=$role, text=redacted, toolCalls=${toolCalls.size}, toolCallId=$toolCallId, toolName=$toolName)"
}

/**
 * A bounded protocol-neutral request. No HTTP, credentials, endpoint, retries or
 * scheduling live here; the transport owns those concerns.
 */
class ModelProtocolRequest(
    val model: String,
    system: String?,
    messages: List<ModelProtocolMessage>,
    tools: List<ModelToolSpec> = emptyList(),
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
) {
    val system: String? = system?.takeIf { it.isNotBlank() }
    val messages: List<ModelProtocolMessage> = Collections.unmodifiableList(ArrayList(messages))
    val tools: List<ModelToolSpec> = Collections.unmodifiableList(ArrayList(tools))

    init {
        require(model.isNotBlank() && model.length <= MAX_MODEL_CHARS && model.none(Char::isISOControl))
        requireValidUtf8(model)
        this.system?.let {
            require(it.length <= MAX_SYSTEM_CHARS && '\u0000' !in it)
            requireValidUtf8(it)
        }
        require(this.messages.isNotEmpty())
        require(this.messages.first().role == ModelMessageRole.USER)
        require(this.messages.any { it.role == ModelMessageRole.USER })
        require(this.tools.map { it.name }.toSet().size == this.tools.size)
        val usesTools = this.messages.any { it.role == ModelMessageRole.TOOL || it.toolCalls.isNotEmpty() }
        require(!usesTools || this.tools.isNotEmpty()) { "Tool history requires tool definitions" }
        require(maxOutputTokens == null || maxOutputTokens in 1..1_000_000)
        require(temperature == null || (temperature.isFinite() && temperature in 0.0..2.0))

        val callNames = mutableMapOf<String, String>()
        for (message in this.messages) {
            when (message.role) {
                ModelMessageRole.USER -> Unit
                ModelMessageRole.ASSISTANT -> message.toolCalls.forEach { call ->
                    require(callNames.put(call.id, call.name) == null) { "Duplicate tool call id" }
                }
                ModelMessageRole.TOOL -> {
                    require(callNames[requireNotNull(message.toolCallId)] == message.toolName) { "Unmatched tool result" }
                }
            }
        }
    }

    override fun toString(): String =
        "ModelProtocolRequest(model=$model, messages=${messages.size}, tools=${tools.size}, maxOutputTokens=$maxOutputTokens, system=redacted)"
}

/** Parsed response. [toString] deliberately never exposes model text or arguments. */
class ModelProtocolResponse(
    text: String,
    thinking: String,
    toolCalls: List<ModelToolCall>,
    val usage: RequestUsage,
    val stopReason: ModelStopReason,
) {
    val text: String = text
    val thinking: String = thinking
    val toolCalls: List<ModelToolCall> = Collections.unmodifiableList(ArrayList(toolCalls))

    init {
        require(text.length <= MAX_TEXT_CHARS && thinking.length <= MAX_TEXT_CHARS)
        require(text.none { it == '\u0000' } && thinking.none { it == '\u0000' })
        require(this.toolCalls.size <= MAX_TOOL_CALLS)
        require(stopReason == ModelStopReason.COMPLETE || stopReason == ModelStopReason.TOOL_CALLS)
        require(text.isNotEmpty() || this.toolCalls.isNotEmpty())
    }

    override fun toString(): String =
        "ModelProtocolResponse(text=redacted, thinking=redacted, toolCalls=${toolCalls.size}, usage=$usage, stopReason=$stopReason)"
}

/** Pure codec. Implementations must not perform IO. */
interface ModelProtocolCodec {
    fun request(request: ModelProtocolRequest): String

    fun response(body: String): ModelProtocolResponse
}

/** Selects a codec without guessing from a model name or an endpoint. */
object ModelProtocols {
    fun codec(protocol: ModelProtocol): ModelProtocolCodec = when (protocol) {
        ModelProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCodec
        ModelProtocol.OPENAI_RESPONSES -> OpenAiResponsesCodec
        ModelProtocol.GOOGLE_GEMINI -> GoogleGeminiCodec
        ModelProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesCodec
        ModelProtocol.SAKURA_LLM -> SakuraLlmCodec
    }

    fun request(protocol: ModelProtocol, request: ModelProtocolRequest): String =
        codec(protocol).request(request)

    fun response(protocol: ModelProtocol, body: String): ModelProtocolResponse =
        codec(protocol).response(body)
}

private const val MAX_BODY_CHARS = 2 * 1024 * 1024
private const val MAX_TOOL_ARGUMENT_CHARS = 512 * 1024
private const val MAX_TOOL_ID_CHARS = 256
private const val MAX_TOOL_CALLS = 8
private const val MAX_TOOL_DESCRIPTION_CHARS = 32 * 1024
private const val MAX_TEXT_CHARS = 1_500_000
private const val MAX_MODEL_CHARS = 200
private const val MAX_SYSTEM_CHARS = 128 * 1024
private const val MAX_CONTENT_PARTS = 64
private const val ANTHROPIC_FALLBACK_MAX_TOKENS = 64_000

private fun requireValidUtf8(value: String) {
    try {
        TextProjectFingerprint.utf8(value)
    } catch (_: TextProjectException) {
        throw IllegalArgumentException("Invalid UTF-8 text")
    }
}

private fun requireToolName(value: String) {
    require(value.isNotBlank() && value.length <= 64)
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' })
}

private fun fail(kind: ModelFailureKind): Nothing = throw AgentModelFailure(kind)

internal abstract class BoundedProtocolCodec : ModelProtocolCodec {
    protected abstract fun encodeRequest(request: ModelProtocolRequest): JsonObject

    protected abstract fun decodeResponse(root: JsonObject): ModelProtocolResponse

    final override fun request(request: ModelProtocolRequest): String {
        val body = try {
            encodeRequest(request).toString()
        } catch (classified: AgentModelFailure) {
            throw classified
        } catch (_: Exception) {
            fail(ModelFailureKind.CONTENT)
        }
        if (body.length > MAX_BODY_CHARS) fail(ModelFailureKind.CONTEXT_OVERFLOW)
        val bytes = try {
            TextProjectFingerprint.utf8(body)
        } catch (_: TextProjectException) {
            fail(ModelFailureKind.CONTENT)
        }
        if (bytes.size > MAX_BODY_CHARS) fail(ModelFailureKind.CONTEXT_OVERFLOW)
        return body
    }

    final override fun response(body: String): ModelProtocolResponse {
        if (body.length > MAX_BODY_CHARS) fail(ModelFailureKind.CONTENT)
        val bytes = try {
            TextProjectFingerprint.utf8(body)
        } catch (_: TextProjectException) {
            fail(ModelFailureKind.PARSING)
        }
        if (bytes.size > MAX_BODY_CHARS) fail(ModelFailureKind.CONTENT)
        val root = try {
            JsonObjectValue.parse(body, JsonInputLimits(MAX_BODY_CHARS, 64)).json
        } catch (_: InvalidJsonPayload) {
            fail(ModelFailureKind.PARSING)
        }
        if (root["error"] != null && root["error"] != JsonNull) fail(ModelFailureKind.CONTENT)
        return try {
            decodeResponse(root)
        } catch (classified: AgentModelFailure) {
            throw classified
        } catch (_: Exception) {
            fail(ModelFailureKind.PARSING)
        }
    }
}

private object OpenAiChatCodec : BoundedProtocolCodec() {
    override fun encodeRequest(request: ModelProtocolRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", false)
        put("messages", buildJsonArray {
            request.system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
            request.messages.forEach { add(chatMessage(it)) }
        })
        if (request.tools.isNotEmpty()) put("tools", chatTools(request.tools))
        request.maxOutputTokens?.let { put("max_tokens", it) }
        request.temperature?.let { put("temperature", it) }
    }

    override fun decodeResponse(root: JsonObject): ModelProtocolResponse =
        decodeChat(root, allowTools = true)
}

private object SakuraLlmCodec : BoundedProtocolCodec() {
    override fun encodeRequest(request: ModelProtocolRequest): JsonObject {
        // SakuraLLM is a pure text translation model; do not silently drop tool history.
        val usesTools = request.tools.isNotEmpty() ||
            request.messages.any { it.role == ModelMessageRole.TOOL || it.toolCalls.isNotEmpty() }
        if (usesTools) fail(ModelFailureKind.CONTENT)
        return buildJsonObject {
            put("model", request.model)
            put("stream", false)
            put("messages", buildJsonArray {
                request.system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                request.messages.forEach { add(chatMessage(it)) }
            })
            request.maxOutputTokens?.let { put("max_tokens", it) }
            request.temperature?.let { put("temperature", it) }
        }
    }

    override fun decodeResponse(root: JsonObject): ModelProtocolResponse =
        decodeChat(root, allowTools = false)
}

private fun chatMessage(message: ModelProtocolMessage): JsonObject = buildJsonObject {
    when (message.role) {
        ModelMessageRole.USER -> {
            put("role", "user")
            put("content", message.text)
        }
        ModelMessageRole.ASSISTANT -> {
            put("role", "assistant")
            put("content", message.text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
            if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        })
                    })
                }
            })
        }
        ModelMessageRole.TOOL -> {
            put("role", "tool")
            put("tool_call_id", requireNotNull(message.toolCallId))
            put("content", message.text)
        }
    }
}

private fun chatTools(tools: List<ModelToolSpec>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            })
        })
    }
}

private fun decodeChat(root: JsonObject, allowTools: Boolean): ModelProtocolResponse {
    val choices = root.requiredArray("choices")
    if (choices.size != 1) fail(ModelFailureKind.PARSING)
    val choice = choices.single() as? JsonObject ?: fail(ModelFailureKind.PARSING)
    val finish = choice.optionalString("finish_reason") ?: fail(ModelFailureKind.PARSING)
    val stop = when (finish) {
        "stop" -> ModelStopReason.COMPLETE
        "tool_calls" -> ModelStopReason.TOOL_CALLS
        "length", "max_tokens" -> fail(ModelFailureKind.TRUNCATED)
        "content_filter" -> fail(ModelFailureKind.CONTENT_FILTERED)
        else -> fail(ModelFailureKind.PARSING)
    }
    val message = choice.requiredObject("message")
    if (message.optionalString("refusal")?.isNotBlank() == true) fail(ModelFailureKind.CONTENT_FILTERED)
    val text = when (val content = message["content"]) {
        null, JsonNull -> ""
        is JsonPrimitive -> {
            if (!content.isString) fail(ModelFailureKind.PARSING)
            content.content
        }
        else -> fail(ModelFailureKind.PARSING)
    }
    val thinking = message.optionalString("reasoning_content")
        ?: message.optionalString("reasoning")
        ?: ""
    val calls = parseOpenAiToolCalls(message["tool_calls"])
    if (!allowTools && calls.isNotEmpty()) fail(ModelFailureKind.PARSING)
    if (stop == ModelStopReason.TOOL_CALLS && calls.isEmpty()) fail(ModelFailureKind.PARSING)
    if (stop == ModelStopReason.COMPLETE && calls.isNotEmpty()) fail(ModelFailureKind.PARSING)
    requireBoundedText(text, thinking)
    if (stop == ModelStopReason.COMPLETE && text.isEmpty() && calls.isEmpty()) fail(ModelFailureKind.EMPTY_RESPONSE)
    return ModelProtocolResponse(text, thinking, calls, chatUsage(root), stop)
}

private fun parseOpenAiToolCalls(value: JsonElement?): List<ModelToolCall> {
    if (value == null || value == JsonNull) return emptyList()
    val array = value as? JsonArray ?: fail(ModelFailureKind.PARSING)
    if (array.size > MAX_TOOL_CALLS) fail(ModelFailureKind.CONTENT)
    val ids = mutableSetOf<String>()
    return array.map { element ->
        val objectValue = element as? JsonObject ?: fail(ModelFailureKind.PARSING)
        if (objectValue.requiredString("type") != "function") fail(ModelFailureKind.PARSING)
        val id = objectValue.requiredString("id")
        if (!ids.add(id)) fail(ModelFailureKind.PARSING)
        val function = objectValue.requiredObject("function")
        ModelToolCall(id, function.requiredString("name"), function.requiredString("arguments"))
    }
}

private fun chatUsage(root: JsonObject): RequestUsage {
    val usage = root.optionalObject("usage")
    val prompt = usage?.optionalObject("prompt_tokens_details")
    val completion = usage?.optionalObject("completion_tokens_details")
    return RequestUsage(
        metric(usage?.get("prompt_tokens")),
        metric(prompt?.get("cached_tokens")?.takeUnless { it == JsonNull } ?: usage?.get("prompt_cache_hit_tokens")),
        unknownMetric(),
        metric(completion?.get("reasoning_tokens")),
        metric(usage?.get("completion_tokens")),
    )
}

private object OpenAiResponsesCodec : BoundedProtocolCodec() {
    override fun encodeRequest(request: ModelProtocolRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", false)
        put("store", false)
        request.system?.let { put("instructions", it) }
        put("input", buildJsonArray { request.messages.forEach { message -> responsesInput(message).forEach { add(it) } } })
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
            request.tools.forEach { tool ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                })
            }
        })
        request.maxOutputTokens?.let { put("max_output_tokens", it) }
        request.temperature?.let { put("temperature", it) }
    }

    override fun decodeResponse(root: JsonObject): ModelProtocolResponse {
        val status = root.optionalString("status") ?: "completed"
        when (status) {
            "completed" -> Unit
            "incomplete" -> {
                val reason = root.optionalObject("incomplete_details")?.optionalString("reason")
                when (reason) {
                    "max_output_tokens" -> fail(ModelFailureKind.TRUNCATED)
                    "content_filter" -> fail(ModelFailureKind.CONTENT_FILTERED)
                    else -> fail(ModelFailureKind.CONTENT)
                }
            }
            "failed", "cancelled" -> fail(ModelFailureKind.CONTENT)
            else -> fail(ModelFailureKind.PARSING)
        }
        val output = root.requiredArray("output")
        if (output.size > MAX_CONTENT_PARTS) fail(ModelFailureKind.CONTENT)
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ModelToolCall>()
        val callIds = mutableSetOf<String>()
        for (element in output) {
            val item = element as? JsonObject ?: fail(ModelFailureKind.PARSING)
            when (item.requiredString("type")) {
                "message" -> {
                    if (item.optionalString("role") != "assistant") fail(ModelFailureKind.PARSING)
                    val content = item.requiredArray("content")
                    if (content.size > MAX_CONTENT_PARTS) fail(ModelFailureKind.CONTENT)
                    for (partElement in content) {
                        val part = partElement as? JsonObject ?: fail(ModelFailureKind.PARSING)
                        when (part.requiredString("type")) {
                            "output_text" -> appendBounded(text, part.requiredString("text"))
                            "refusal" -> fail(ModelFailureKind.CONTENT_FILTERED)
                            else -> fail(ModelFailureKind.PARSING)
                        }
                    }
                }
                "reasoning" -> {
                    val summary = item["summary"]
                    if (summary != null && summary != JsonNull) {
                        val entries = summary as? JsonArray ?: fail(ModelFailureKind.PARSING)
                        if (entries.size > MAX_CONTENT_PARTS) fail(ModelFailureKind.CONTENT)
                        for (entryElement in entries) {
                            val entry = entryElement as? JsonObject ?: fail(ModelFailureKind.PARSING)
                            appendBounded(thinking, entry.requiredString("text"))
                        }
                    }
                }
                "function_call" -> {
                    if (calls.size >= MAX_TOOL_CALLS) fail(ModelFailureKind.CONTENT)
                    val id = item.requiredString("call_id")
                    if (!callIds.add(id)) fail(ModelFailureKind.PARSING)
                    calls.add(ModelToolCall(id, item.requiredString("name"), item.requiredString("arguments")))
                }
                else -> fail(ModelFailureKind.PARSING)
            }
        }
        requireBoundedText(text.toString(), thinking.toString())
        if (calls.isNotEmpty()) {
            return ModelProtocolResponse(text.toString(), thinking.toString(), calls, responsesUsage(root), ModelStopReason.TOOL_CALLS)
        }
        if (text.isEmpty()) fail(ModelFailureKind.EMPTY_RESPONSE)
        return ModelProtocolResponse(text.toString(), thinking.toString(), emptyList(), responsesUsage(root), ModelStopReason.COMPLETE)
    }
}

private fun responsesInput(message: ModelProtocolMessage): List<JsonObject> = when (message.role) {
    ModelMessageRole.USER -> listOf(buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", message.text) }) })
    })
    ModelMessageRole.ASSISTANT -> buildList<JsonObject> {
        if (message.text.isNotEmpty()) add(buildJsonObject {
            put("role", "assistant")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", message.text) }) })
        })
        message.toolCalls.forEach { call ->
            add(buildJsonObject {
                put("type", "function_call")
                put("call_id", call.id)
                put("name", call.name)
                put("arguments", call.argumentsJson)
            })
        }
    }
    ModelMessageRole.TOOL -> listOf(buildJsonObject {
        put("type", "function_call_output")
        put("call_id", requireNotNull(message.toolCallId))
        put("output", message.text)
    })
}

private fun responsesUsage(root: JsonObject): RequestUsage {
    val usage = root.optionalObject("usage")
    val input = usage?.optionalObject("input_tokens_details")
    val output = usage?.optionalObject("output_tokens_details")
    return RequestUsage(
        metric(usage?.get("input_tokens")),
        metric(input?.get("cached_tokens")),
        metric(input?.get("cache_write_tokens")),
        metric(output?.get("reasoning_tokens")),
        metric(usage?.get("output_tokens")),
    )
}

private object GoogleGeminiCodec : BoundedProtocolCodec() {
    override fun encodeRequest(request: ModelProtocolRequest): JsonObject = buildJsonObject {
        request.system?.let { system ->
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
        }
        put("contents", buildJsonArray { request.messages.forEach { add(geminiContent(it)) } })
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    }
                })
            })
        })
        if (request.maxOutputTokens != null || request.temperature != null) {
            put("generationConfig", buildJsonObject {
                request.maxOutputTokens?.let { put("maxOutputTokens", it) }
                request.temperature?.let { put("temperature", it) }
            })
        }
    }

    override fun decodeResponse(root: JsonObject): ModelProtocolResponse {
        val feedback = root.optionalObject("promptFeedback")
        val blockReason = feedback?.optionalString("blockReason")
        if (blockReason != null && blockReason != "BLOCK_REASON_UNSPECIFIED") fail(ModelFailureKind.CONTENT_FILTERED)
        val candidates = root.requiredArray("candidates")
        if (candidates.size > 1) fail(ModelFailureKind.PARSING)
        if (candidates.isEmpty()) fail(ModelFailureKind.EMPTY_RESPONSE)
        val candidate = candidates.single() as? JsonObject ?: fail(ModelFailureKind.PARSING)
        when (candidate.optionalString("finishReason")?.uppercase()) {
            null, "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
            "MAX_TOKENS" -> fail(ModelFailureKind.TRUNCATED)
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
                fail(ModelFailureKind.CONTENT_FILTERED)
            "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> fail(ModelFailureKind.PARSING)
            "OTHER", "LANGUAGE" -> fail(ModelFailureKind.CONTENT)
            else -> fail(ModelFailureKind.PARSING)
        }
        val content = candidate.optionalObject("content")
        val parts = content?.get("parts") ?: JsonNull
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ModelToolCall>()
        val callIds = mutableSetOf<String>()
        if (parts != JsonNull) {
            val partArray = parts as? JsonArray ?: fail(ModelFailureKind.PARSING)
            if (partArray.size > MAX_CONTENT_PARTS) fail(ModelFailureKind.CONTENT)
            partArray.forEachIndexed { index, element ->
                val part = element as? JsonObject ?: fail(ModelFailureKind.PARSING)
                val thought = part["thought"]?.let {
                    if (it == JsonNull) false else {
                        val primitive = it as? JsonPrimitive ?: fail(ModelFailureKind.PARSING)
                        if (primitive.isString) fail(ModelFailureKind.PARSING)
                        primitive.content.toBooleanStrictOrNull() ?: fail(ModelFailureKind.PARSING)
                    }
                } ?: false
                val partText = part.optionalString("text")
                val functionCall = part["functionCall"]
                if (partText != null) appendBounded(if (thought) thinking else text, partText)
                if (functionCall != null && functionCall != JsonNull) {
                    val call = functionCall as? JsonObject ?: fail(ModelFailureKind.PARSING)
                    if (calls.size >= MAX_TOOL_CALLS) fail(ModelFailureKind.CONTENT)
                    val name = call.requiredString("name")
                    val args = call["args"]?.let {
                        if (it == JsonNull) "{}" else (it as? JsonObject ?: fail(ModelFailureKind.PARSING)).toString()
                    } ?: "{}"
                    // Gemini normally has no provider call id; synthesize a response-local
                    // correlation id. Tool-result replay still uses the tool name.
                    val id = call.optionalString("id")?.takeIf { it.isNotBlank() } ?: "gemini-call-$index"
                    if (!callIds.add(id)) fail(ModelFailureKind.PARSING)
                    calls.add(ModelToolCall(id, name, args))
                }
                if (partText == null && (functionCall == null || functionCall == JsonNull)) fail(ModelFailureKind.PARSING)
            }
        }
        requireBoundedText(text.toString(), thinking.toString())
        val stop = if (calls.isNotEmpty()) ModelStopReason.TOOL_CALLS else ModelStopReason.COMPLETE
        if (stop == ModelStopReason.COMPLETE && text.isEmpty()) fail(ModelFailureKind.EMPTY_RESPONSE)
        return ModelProtocolResponse(text.toString(), thinking.toString(), calls, geminiUsage(root), stop)
    }
}

private fun geminiContent(message: ModelProtocolMessage): JsonObject = buildJsonObject {
    when (message.role) {
        ModelMessageRole.USER -> {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", message.text) }) })
        }
        ModelMessageRole.ASSISTANT -> {
            put("role", "model")
            put("parts", buildJsonArray {
                if (message.text.isNotEmpty()) add(buildJsonObject { put("text", message.text) })
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("functionCall", buildJsonObject {
                            put("name", call.name)
                            put("args", JsonObjectValue.parse(call.argumentsJson, JsonInputLimits(MAX_TOOL_ARGUMENT_CHARS, 32)).json)
                        })
                    })
                }
            })
        }
        ModelMessageRole.TOOL -> {
            put("role", "user")
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", requireNotNull(message.toolName))
                        put("response", buildJsonObject { put("content", message.text) })
                    })
                })
            })
        }
    }
}

private fun geminiUsage(root: JsonObject): RequestUsage {
    val usage = root.optionalObject("usageMetadata")
    return RequestUsage(
        metric(usage?.get("promptTokenCount")),
        metric(usage?.get("cachedContentTokenCount")),
        unknownMetric(),
        metric(usage?.get("thoughtsTokenCount")),
        metric(usage?.get("candidatesTokenCount")),
    )
}

private object AnthropicMessagesCodec : BoundedProtocolCodec() {
    override fun encodeRequest(request: ModelProtocolRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxOutputTokens ?: ANTHROPIC_FALLBACK_MAX_TOKENS)
        request.system?.let { put("system", it) }
        put("messages", anthropicMessages(request.messages))
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
            request.tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                })
            }
        })
        request.temperature?.let {
            if (it > 1.0) fail(ModelFailureKind.CONTENT)
            put("temperature", it)
        }
    }

    override fun decodeResponse(root: JsonObject): ModelProtocolResponse {
        if (root.optionalString("type") == "error") fail(ModelFailureKind.CONTENT)
        val stopReason = root.requiredString("stop_reason")
        val stop = when (stopReason) {
            "end_turn", "stop_sequence" -> ModelStopReason.COMPLETE
            "tool_use" -> ModelStopReason.TOOL_CALLS
            "max_tokens" -> fail(ModelFailureKind.TRUNCATED)
            "model_context_window_exceeded" -> fail(ModelFailureKind.CONTEXT_OVERFLOW)
            "refusal" -> fail(ModelFailureKind.CONTENT_FILTERED)
            else -> fail(ModelFailureKind.PARSING)
        }
        val content = root.requiredArray("content")
        if (content.size > MAX_CONTENT_PARTS) fail(ModelFailureKind.CONTENT)
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ModelToolCall>()
        val callIds = mutableSetOf<String>()
        for (element in content) {
            val part = element as? JsonObject ?: fail(ModelFailureKind.PARSING)
            when (part.requiredString("type")) {
                "text" -> appendBounded(text, part.requiredString("text"))
                "thinking" -> appendBounded(thinking, part.requiredString("thinking"))
                "redacted_thinking" -> Unit
                "tool_use" -> {
                    if (calls.size >= MAX_TOOL_CALLS) fail(ModelFailureKind.CONTENT)
                    val id = part.requiredString("id")
                    if (!callIds.add(id)) fail(ModelFailureKind.PARSING)
                    val input = part.requiredObject("input")
                    calls.add(ModelToolCall(id, part.requiredString("name"), input.toString()))
                }
                else -> fail(ModelFailureKind.PARSING)
            }
        }
        requireBoundedText(text.toString(), thinking.toString())
        if (stop == ModelStopReason.TOOL_CALLS && calls.isEmpty()) fail(ModelFailureKind.PARSING)
        if (stop == ModelStopReason.COMPLETE && calls.isNotEmpty()) fail(ModelFailureKind.PARSING)
        if (stop == ModelStopReason.COMPLETE && text.isEmpty()) fail(ModelFailureKind.EMPTY_RESPONSE)
        return ModelProtocolResponse(text.toString(), thinking.toString(), calls, anthropicUsage(root), stop)
    }
}

/** Anthropic requires all results for one assistant turn in a single user message. */
private fun anthropicMessages(messages: List<ModelProtocolMessage>): JsonArray = buildJsonArray {
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message.role != ModelMessageRole.TOOL) {
            add(anthropicMessage(message))
            index += 1
            continue
        }
        val results = buildJsonArray {
            while (index < messages.size && messages[index].role == ModelMessageRole.TOOL) {
                val result = messages[index]
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", requireNotNull(result.toolCallId))
                    put("content", result.text)
                })
                index += 1
            }
        }
        add(buildJsonObject { put("role", "user"); put("content", results) })
    }
}

private fun anthropicMessage(message: ModelProtocolMessage): JsonObject = buildJsonObject {
    when (message.role) {
        ModelMessageRole.USER -> {
            put("role", "user")
            put("content", message.text)
        }
        ModelMessageRole.ASSISTANT -> {
            put("role", "assistant")
            put("content", buildJsonArray {
                if (message.text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", message.text) })
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", JsonObjectValue.parse(call.argumentsJson, JsonInputLimits(MAX_TOOL_ARGUMENT_CHARS, 32)).json)
                    })
                }
            })
        }
        ModelMessageRole.TOOL -> {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", requireNotNull(message.toolCallId))
                    put("content", message.text)
                })
            })
        }
    }
}

private fun anthropicUsage(root: JsonObject): RequestUsage {
    val usage = root.optionalObject("usage")
    return RequestUsage(
        metric(usage?.get("input_tokens")),
        metric(usage?.get("cache_read_input_tokens")),
        metric(usage?.get("cache_creation_input_tokens")),
        unknownMetric(),
        metric(usage?.get("output_tokens")),
    )
}

private fun unknownMetric(): TokenMeasurement = TokenMeasurement(null, UsageSource.UNKNOWN)

private fun metric(value: JsonElement?): TokenMeasurement {
    if (value == null || value == JsonNull) return unknownMetric()
    val primitive = value as? JsonPrimitive ?: fail(ModelFailureKind.PARSING)
    if (primitive.isString) fail(ModelFailureKind.PARSING)
    val tokens = ToolArguments.long(ToolScalar.NumberToken(primitive.content), "usage", 0, Long.MAX_VALUE)
    return TokenMeasurement(tokens, UsageSource.ACTUAL)
}

private fun appendBounded(target: StringBuilder, value: String) {
    if (target.length + value.length > MAX_TEXT_CHARS) fail(ModelFailureKind.CONTENT)
    if ('\u0000' in value) fail(ModelFailureKind.CONTENT)
    target.append(value)
}

private fun requireBoundedText(text: String, thinking: String) {
    if (text.length > MAX_TEXT_CHARS || thinking.length > MAX_TEXT_CHARS) fail(ModelFailureKind.CONTENT)
    try {
        TextProjectFingerprint.utf8(text)
        TextProjectFingerprint.utf8(thinking)
    } catch (_: TextProjectException) {
        fail(ModelFailureKind.CONTENT)
    }
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    val value = this[key] ?: fail(ModelFailureKind.PARSING)
    if (value == JsonNull) fail(ModelFailureKind.PARSING)
    return value as? JsonObject ?: fail(ModelFailureKind.PARSING)
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    return value as? JsonObject ?: fail(ModelFailureKind.PARSING)
}

private fun JsonObject.requiredArray(key: String): JsonArray {
    val value = this[key] ?: fail(ModelFailureKind.PARSING)
    if (value == JsonNull) fail(ModelFailureKind.PARSING)
    return value as? JsonArray ?: fail(ModelFailureKind.PARSING)
}

private fun JsonObject.requiredString(key: String): String =
    optionalString(key) ?: fail(ModelFailureKind.PARSING)

private fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: fail(ModelFailureKind.PARSING)
    if (!primitive.isString) fail(ModelFailureKind.PARSING)
    return primitive.content
}
