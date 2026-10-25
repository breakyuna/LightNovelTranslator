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
    private val transport: CompatibleHttpTransport = CompatibleHttpTransport(),
    private val keys: ModelKeyPool = ModelKeyPool(settings),
    private val usage: UsageLedgerRecorder? = null,
    /** Exact provider facts; EMPTY intentionally rejects tool-bearing non-chat requests. */
    private val capabilities: CapabilityCatalog = CapabilityCatalog.EMPTY,
    /** Saved per-profile facts override the static fallback for this immutable revision. */
    private val profiles: suspend (ModelConfigurationRef) -> NativeModelProfile? = { null }) : AgentModelPort {
    override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
        var reference: ModelConfigurationRef? = null
        var selection: KeySelection? = null
        try {
            currentCoroutineContext().ensureActive()
            val config = settings.resolve(request.configuration)
            reference = config.reference
            val profile = profiles(request.configuration)
            selection = keys.select(request.configuration)
            val protocol = config.protocol.toModelProtocol()
            if (protocol != ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
                val result = completeProtocol(request, config, profile, protocol, selection?.credential.orEmpty(), emit)
                withContext(NonCancellable) {
                    keys.success(config.reference, selection)
                    runCatching { usage?.record(UsageRequestRecord(request.run, request.attemptId,
                        UsageModelRef(config.reference, config.model), result.usage, result.stopReason)) }
                }
                return result
            }
            val secret = selection?.credential.orEmpty()
            if (request.tools.isNotEmpty() && profile?.capabilities?.tools == false)
                throw AgentModelFailure(ModelFailureKind.CONTENT)
            val providerIds = request.history.filterIsInstance<AgentMessage.Assistant>().flatMap { it.parts }
                .filterIsInstance<AssistantPart.ToolCall>().associate { it.call.id to requireNotNull(it.call.providerCallId) }
            val body = applyProfileRequestOverrides(buildJsonObject {
                put("model", config.model); put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                put("messages", JsonArray(request.history.map { message(it, providerIds) }))
                if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map {
                    buildJsonObject { put("type", "function"); put("function", buildJsonObject {
                        put("name", it.name); put("description", it.description); put("parameters", it.inputSchema.json)
                    }) }
                }))
            }.toString(), profile, protocol)
            if (TextProjectFingerprint.utf8(body).size > 2 * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
            val http = transport.request(NativeModelEndpoint.chat(config.endpoint), secret).apply {
                header("Accept", "text/event-stream")
                profile?.extraHeaders?.forEach { (name, value) -> header(name, value) }
            }.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
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
            val parsed = try { parse(payload) }
            catch (classified: AgentModelFailure) { throw classified }
            catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
            withContext(NonCancellable) {
                keys.success(config.reference, selection)
                runCatching { usage?.record(UsageRequestRecord(request.run, request.attemptId,
                    UsageModelRef(config.reference, config.model), parsed.usage, parsed.stopReason)) }
            }
            return parsed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (local: InvalidModelNetworkSettings) { throw local }
        catch (local: ModelNetworkSettingsUnavailable) { throw local }
        catch (classified: AgentModelFailure) {
            reference?.let { keys.failure(it, selection, classified.kind, classified.retryAfterMillis) }
            throw classified
        }
        catch (_: Exception) {
            reference?.let { keys.failure(it, selection, ModelFailureKind.UNKNOWN) }
            throw AgentModelFailure(ModelFailureKind.UNKNOWN)
        }
    }
    /**
     * Non-chat protocols use the shared protocol transport. The current slice is
     * non-streaming: the complete receipt is emitted as preview deltas after the
     * response has passed the same bounded validation, so a partial provider
     * stream can never become a persisted assistant message.
     */
    private suspend fun completeProtocol(request: AgentModelRequest, config: NativeModelSettings,
        profile: NativeModelProfile?, protocol: ModelProtocol, secret: String,
        emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
        val system = request.history.filterIsInstance<AgentMessage.System>()
            .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
        val conversation = request.history.filterNot { it is AgentMessage.System }
        val providerIds = mutableMapOf<ToolCallId, String>()
        for (message in conversation) {
            if (message !is AgentMessage.Assistant) continue
            if (message.continuation != null ||
                message.parts.filterIsInstance<AssistantPart.Thinking>().any { it.continuation != null })
                throw AgentModelFailure(ModelFailureKind.CONTENT)
            message.parts.filterIsInstance<AssistantPart.ToolCall>().forEach { part ->
                providerIds[part.call.id] = part.call.providerCallId
                    ?: throw AgentModelFailure(ModelFailureKind.PARSING)
            }
        }
        val messages = conversation.map { value ->
            when (value) {
                is AgentMessage.User -> {
                    if (value.parts.any { it !is UserPart.Text }) throw AgentModelFailure(ModelFailureKind.CONTENT)
                    val text = value.parts.joinToString("\n") { (it as UserPart.Text).text }
                    if (text.isBlank()) throw AgentModelFailure(ModelFailureKind.CONTENT)
                    ModelProtocolMessage(ModelMessageRole.USER, text)
                }
                is AgentMessage.Assistant -> {
                    val text = value.parts.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
                    val calls = value.parts.filterIsInstance<AssistantPart.ToolCall>().map { part ->
                        ModelToolCall(part.call.providerCallId ?: throw AgentModelFailure(ModelFailureKind.PARSING),
                            part.call.name, part.call.arguments.encode())
                    }
                    if (text.isEmpty() && calls.isEmpty()) throw AgentModelFailure(ModelFailureKind.CONTENT)
                    ModelProtocolMessage(ModelMessageRole.ASSISTANT, text, calls)
                }
                is AgentMessage.ToolResult -> ModelProtocolMessage(ModelMessageRole.TOOL, value.result.encode(),
                    toolCallId = providerIds[value.callId] ?: throw AgentModelFailure(ModelFailureKind.PARSING),
                    toolName = value.toolName)
                is AgentMessage.System -> throw AgentModelFailure(ModelFailureKind.CONTENT)
            }
        }
        val tools = request.tools.map { ModelToolSpec(it.name, it.description, it.inputSchema.json) }
        val protocolRequest = try {
            ModelProtocolRequest(config.model, system, messages, tools)
        } catch (_: IllegalArgumentException) { throw AgentModelFailure(ModelFailureKind.CONTENT) }
        val response = try {
            ModelProtocolTransport(transport, capabilities)
                .complete(protocol, config, secret, protocolRequest,
                    CapabilityRequirement(tools = tools.isNotEmpty()), profile)
        } catch (failure: UnsupportedModelCapability) {
            throw AgentModelFailure(ModelFailureKind.CONTENT)
        }
        val parts = mutableListOf<AssistantPart>()
        if (response.thinking.isNotEmpty()) parts.add(AssistantPart.Thinking(response.thinking, null))
        if (response.text.isNotEmpty()) parts.add(AssistantPart.Text(response.text))
        response.toolCalls.forEach { call ->
            parts.add(AssistantPart.ToolCall(AgentToolCall(ToolCallId(UUID.randomUUID().toString()), call.name,
                try { JsonObjectValue.parse(call.argumentsJson, JsonInputLimits(512 * 1024, 32)) }
                catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }, call.id)))
        }
        val message = AgentMessage.Assistant(MessageId(UUID.randomUUID().toString()), parts)
        if (AgentMessageCodec.encode(message).length > 512 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
        if (response.thinking.isNotEmpty()) emit(AgentModelEvent.ThinkingDelta(response.thinking))
        if (response.text.isNotEmpty()) emit(AgentModelEvent.TextDelta(response.text))
        response.toolCalls.indices.forEach { emit(AgentModelEvent.ToolCallDelta(it)) }
        return AgentModelResponse(message, response.usage, response.stopReason)
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
