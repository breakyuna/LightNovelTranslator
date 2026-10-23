package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Bounded streaming Chat Completions transport; only called through the shared gateway. */
class CompatibleAgentModel(private val settings: NativeModelSettingsPort, private val runtime: RuntimePort,
    private val transport: CompatibleHttpTransport = CompatibleHttpTransport()) : AgentModelPort {
    override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
        try {
            currentCoroutineContext().ensureActive()
            val config = settings.resolve(request.configuration)
            val secret = settings.credential(request.configuration)
            val providerIds = request.history.filterIsInstance<AgentMessage.Assistant>().flatMap { it.parts }
                .filterIsInstance<AssistantPart.ToolCall>().associate { it.call.id to requireNotNull(it.call.providerCallId) }
            val body = buildJsonObject {
                put("model", config.model); put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                put("messages", JsonArray(request.history.map { message(it, providerIds) }))
                if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map {
                    buildJsonObject { put("type", "function"); put("function", buildJsonObject {
                        put("name", it.name); put("description", it.description); put("parameters", it.inputSchema.json)
                    }) }
                }))
            }.toString()
            if (TextProjectFingerprint.utf8(body).size > 2 * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
            val http = transport.request(NativeModelEndpoint.chat(config.endpoint), secret).header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            currentCoroutineContext().ensureActive()
            val active = runtime.snapshot().activeRun
            if (active == null || active.identity != request.run || active.phase != RuntimePhase.RUNNING) throw CancellationException("Agent request stopped")
            // A completed provider result must survive prompt cancellation at the IO return boundary.
            // AgentRequestBoundary persists it before checking Stop; partial streams never set this slot.
            val received = AtomicReference<String?>(null)
            val payload = try {
                transport.read(http, streaming = true) { input, contentType ->
                    val result = if (contentType == "application/json" || contentType?.endsWith("+json") == true) transport.readUtf8(input)
                        else CompatibleChatStream().read(input, emit)
                    received.set(result)
                    result
                }
            } catch (failure: Exception) { received.get() ?: throw failure }
            return try { parse(payload) }
            catch (classified: AgentModelFailure) { throw classified }
            catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.UNKNOWN) }
    }
    private fun message(value: AgentMessage, providerIds: Map<ToolCallId, String>): JsonObject = buildJsonObject {
        when (value) {
            is AgentMessage.System -> { put("role", "system"); put("content", value.text) }
            is AgentMessage.User -> {
                if (value.parts.any { it !is UserPart.Text }) throw AgentModelFailure(ModelFailureKind.CONTENT)
                put("role", "user"); put("content", value.parts.joinToString("\n") { (it as UserPart.Text).text })
            }
            is AgentMessage.Assistant -> {
                if (value.continuation != null || value.parts.filterIsInstance<AssistantPart.Thinking>().any { it.continuation != null })
                    throw AgentModelFailure(ModelFailureKind.CONTENT)
                put("role", "assistant")
                val text = value.parts.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
                put("content", text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                val thinking = value.parts.filterIsInstance<AssistantPart.Thinking>().mapNotNull { it.text }.joinToString("")
                if (thinking.isNotEmpty()) put("reasoning_content", thinking)
                val calls = value.parts.filterIsInstance<AssistantPart.ToolCall>()
                if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls.map { part -> buildJsonObject {
                    put("id", part.call.providerCallId ?: throw AgentModelFailure(ModelFailureKind.PARSING)); put("type", "function")
                    put("function", buildJsonObject { put("name", part.call.name); put("arguments", part.call.arguments.encode()) })
                } }))
            }
            is AgentMessage.ToolResult -> {
                put("role", "tool"); put("tool_call_id", providerIds[value.callId] ?: throw AgentModelFailure(ModelFailureKind.PARSING)); put("content", value.result.encode())
            }
        }
    }
    private fun parse(body: String): AgentModelResponse {
        val root = JsonObjectValue.parse(body, JsonInputLimits(2 * 1024 * 1024, 64)).json
        val choices = root.getValue("choices").jsonArray
        if (choices.size != 1) throw AgentModelFailure(ModelFailureKind.PARSING)
        val choice = choices.single().jsonObject
        val stop = when (choice.getValue("finish_reason").jsonPrimitive.content) {
            "stop" -> ModelStopReason.COMPLETE
            "tool_calls" -> ModelStopReason.TOOL_CALLS
            "length" -> ModelStopReason.TRUNCATED
            "content_filter" -> ModelStopReason.CONTENT_FILTERED
            else -> throw AgentModelFailure(ModelFailureKind.PARSING)
        }
        val value = choice.getValue("message").jsonObject
        val parts = mutableListOf<AssistantPart>()
        value["content"]?.takeUnless { it == JsonNull }?.let { parts.add(AssistantPart.Text(it.jsonPrimitive.content)) }
        value["reasoning_content"]?.takeUnless { it == JsonNull }?.let { parts.add(AssistantPart.Thinking(it.jsonPrimitive.content, null)) }
        val calls = value["tool_calls"]?.jsonArray.orEmpty()
        if (calls.size > 8) throw AgentModelFailure(ModelFailureKind.PARSING)
        val providerIds = mutableSetOf<String>()
        for (element in if (stop == ModelStopReason.TRUNCATED || stop == ModelStopReason.CONTENT_FILTERED) emptyList() else calls) {
            val call = element.jsonObject
            require(call.getValue("type").jsonPrimitive.content == "function")
            val id = call.getValue("id").jsonPrimitive.content
            require(id.isNotBlank() && providerIds.add(id))
            val function = call.getValue("function").jsonObject
            parts.add(AssistantPart.ToolCall(AgentToolCall(ToolCallId(UUID.randomUUID().toString()),
                function.getValue("name").jsonPrimitive.content,
                JsonObjectValue.parse(function.getValue("arguments").jsonPrimitive.content, JsonInputLimits(512 * 1024, 32)), id)))
        }
        val usage = root["usage"]?.takeUnless { it == JsonNull }?.jsonObject
        fun metric(value: JsonElement?): TokenMeasurement {
            if (value == null || value == JsonNull) return TokenMeasurement(null, UsageSource.UNKNOWN)
            val number = value.jsonPrimitive
            if (number.isString) throw AgentModelFailure(ModelFailureKind.PARSING)
            return TokenMeasurement(ToolArguments.long(ToolScalar.NumberToken(number.content), "usage", 0, Long.MAX_VALUE), UsageSource.ACTUAL)
        }
        val promptDetails = usage?.get("prompt_tokens_details")?.takeUnless { it == JsonNull }?.jsonObject
        val completionDetails = usage?.get("completion_tokens_details")?.takeUnless { it == JsonNull }?.jsonObject
        val message = AgentMessage.Assistant(MessageId(UUID.randomUUID().toString()), parts)
        if (AgentMessageCodec.encode(message).length > 512 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
        return AgentModelResponse(message,
            RequestUsage(metric(usage?.get("prompt_tokens")), metric(promptDetails?.get("cached_tokens") ?: usage?.get("prompt_cache_hit_tokens")),
                metric(null), metric(completionDetails?.get("reasoning_tokens")), metric(usage?.get("completion_tokens"))), stop)
    }
}
