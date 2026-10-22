package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Bounded non-streaming Chat Completions transport; only called through the shared gateway. */
class CompatibleAgentModel(private val settings: NativeModelSettingsPort, private val runtime: RuntimePort) : AgentModelPort {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .followSslRedirects(false).callTimeout(90, TimeUnit.SECONDS).build()
    override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
        try {
            currentCoroutineContext().ensureActive()
            val config = settings.resolve(request.configuration)
            val secret = settings.credential(request.configuration)
            val providerIds = request.history.filterIsInstance<AgentMessage.Assistant>().flatMap { it.parts }
                .filterIsInstance<AssistantPart.ToolCall>().associate { it.call.id to requireNotNull(it.call.providerCallId) }
            val body = buildJsonObject {
                put("model", config.model); put("stream", false)
                put("messages", JsonArray(request.history.map { message(it, providerIds) }))
                if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map {
                    buildJsonObject { put("type", "function"); put("function", buildJsonObject {
                        put("name", it.name); put("description", it.description); put("parameters", it.inputSchema.json)
                    }) }
                }))
            }.toString()
            if (TextProjectFingerprint.utf8(body).size > 2 * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
            val http = Request.Builder().url(config.endpoint + "/chat/completions")
                .header("Authorization", "Bearer $secret").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            currentCoroutineContext().ensureActive()
            val active = runtime.snapshot().activeRun
            if (active == null || active.identity != request.run || active.phase != RuntimePhase.RUNNING) throw CancellationException("Agent request stopped")
            val payload = execute(http)
            return try { parse(payload) }
            catch (classified: AgentModelFailure) { throw classified }
            catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.UNKNOWN) }
    }
    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(AgentModelFailure(
                    if (error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException) ModelFailureKind.TIMEOUT else ModelFailureKind.NETWORK)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result: Result<String> = try { response.use {
                    if (!it.isSuccessful) throw AgentModelFailure(when (it.code) {
                        401, 403 -> ModelFailureKind.AUTHENTICATION
                        408, 504 -> ModelFailureKind.TIMEOUT
                        429 -> ModelFailureKind.RATE_LIMIT
                        in 500..599 -> ModelFailureKind.SERVER
                        else -> ModelFailureKind.CONTENT
                    })
                    val output = ByteArrayOutputStream()
                    val stream = it.body?.byteStream() ?: throw AgentModelFailure(ModelFailureKind.EMPTY_RESPONSE)
                    stream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (!continuation.isActive) throw CancellationException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size().toLong() + read > 2L * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
                            output.write(buffer, 0, read)
                        }
                    }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    Result.success(decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString())
                } } catch (failure: Exception) {
                    Result.failure(if (failure is AgentModelFailure || failure is CancellationException) failure else AgentModelFailure(ModelFailureKind.NETWORK))
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
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
        val value = choice.getValue("message").jsonObject
        val parts = mutableListOf<AssistantPart>()
        value["content"]?.takeUnless { it == JsonNull }?.let { parts.add(AssistantPart.Text(it.jsonPrimitive.content)) }
        value["reasoning_content"]?.takeUnless { it == JsonNull }?.let { parts.add(AssistantPart.Thinking(it.jsonPrimitive.content, null)) }
        val calls = value["tool_calls"]?.jsonArray.orEmpty()
        if (calls.size > 8) throw AgentModelFailure(ModelFailureKind.PARSING)
        val providerIds = mutableSetOf<String>()
        for (element in calls) {
            val call = element.jsonObject
            require(call.getValue("type").jsonPrimitive.content == "function")
            val id = call.getValue("id").jsonPrimitive.content
            require(id.isNotBlank() && providerIds.add(id))
            val function = call.getValue("function").jsonObject
            parts.add(AssistantPart.ToolCall(AgentToolCall(ToolCallId(UUID.randomUUID().toString()),
                function.getValue("name").jsonPrimitive.content,
                JsonObjectValue.parse(function.getValue("arguments").jsonPrimitive.content, JsonInputLimits(512 * 1024, 32)), id)))
        }
        val stop = when (choice.getValue("finish_reason").jsonPrimitive.content) {
            "stop" -> ModelStopReason.COMPLETE
            "tool_calls" -> ModelStopReason.TOOL_CALLS
            "length" -> ModelStopReason.TRUNCATED
            "content_filter" -> ModelStopReason.CONTENT_FILTERED
            else -> throw AgentModelFailure(ModelFailureKind.PARSING)
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
        return AgentModelResponse(AgentMessage.Assistant(MessageId(UUID.randomUUID().toString()), parts),
            RequestUsage(metric(usage?.get("prompt_tokens")), metric(promptDetails?.get("cached_tokens") ?: usage?.get("prompt_cache_hit_tokens")),
                metric(null), metric(completionDetails?.get("reasoning_tokens")), metric(usage?.get("completion_tokens"))), stop)
    }
}
