package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LlmResult(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val isSuccess: Boolean,
    val finishReason: String? = null,
    val isTruncated: Boolean = false,
    val errorMessage: String? = null,
    val durationMs: Long = 0
)

class LlmClient {
    private data class HttpPayload(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> {
        val startedAt = System.currentTimeMillis()
        SystemLogger.info("LLM_API", "Fetching model list from provider: ${provider.name} (${provider.providerType})")
        return try {
            val models = when (provider.providerType) {
                ProviderType.GEMINI_DIRECT -> fetchGeminiModels(provider)
                ProviderType.ANTHROPIC_CLAUDE -> fetchAnthropicModels(provider)
                ProviderType.OLLAMA_LOCAL -> fetchOllamaModels(provider)
                else -> fetchOpenAiCompatibleModels(provider)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (models.isNotEmpty()) {
                SystemLogger.info("LLM_API", "Successfully fetched ${models.size} models from ${provider.name} in ${elapsed}ms: ${models.take(5).joinToString(", ")}${if (models.size > 5) "..." else ""}")
                Result.success(models)
            } else {
                val msg = "No models found in response from ${provider.name}"
                SystemLogger.warn("LLM_API", msg)
                Result.failure(Exception(msg))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val errorMsg = "Failed to fetch models from ${provider.name}: ${e.localizedMessage}"
            SystemLogger.error("LLM_API", errorMsg, details = e.stackTraceToString())
            Result.failure(Exception(errorMsg, e))
        }
    }

    private suspend fun fetchGeminiModels(provider: ApiProviderEntity): List<String> {
        val apiKey = provider.apiKey.trim()
        require(apiKey.isNotBlank()) { "Gemini API key is required to fetch models" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val request = Request.Builder().url(endpoint).get().build()
        val payload = performRequest(request)
        if (!payload.isSuccessful) {
            throw IOException("HTTP ${payload.code}: ${extractError(payload.body)}")
        }
        val json = JSONObject(payload.body)
        val modelsArray = json.optJSONArray("models") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until modelsArray.length()) {
            val m = modelsArray.optJSONObject(i) ?: continue
            val name = m.optString("name").removePrefix("models/")
            val supportedMethods = m.optJSONArray("supportedGenerationMethods")
            val isGenerative = supportedMethods != null && (0 until supportedMethods.length()).any { idx ->
                supportedMethods.optString(idx).contains("generateContent", ignoreCase = true)
            }
            if (isGenerative || name.contains("gemini", ignoreCase = true)) {
                list.add(name)
            }
        }
        return list.sorted()
    }

    private suspend fun fetchAnthropicModels(provider: ApiProviderEntity): List<String> {
        val apiKey = provider.apiKey.trim()
        require(apiKey.isNotBlank()) { "Anthropic API key is required to fetch models" }
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        validateEndpoint(endpoint, provider.providerType)
        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        val payload = performRequest(request)
        if (!payload.isSuccessful) {
            throw IOException("HTTP ${payload.code}: ${extractError(payload.body)}")
        }
        val json = JSONObject(payload.body)
        val data = json.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) list.add(id)
        }
        return list.sorted()
    }

    private suspend fun fetchOllamaModels(provider: ApiProviderEntity): List<String> {
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = "$base/api/tags"
        val request = Request.Builder().url(endpoint).get().build()
        val payload = performRequest(request)
        if (payload.isSuccessful) {
            val json = JSONObject(payload.body)
            val models = json.optJSONArray("models")
            if (models != null) {
                val list = mutableListOf<String>()
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val name = m.optString("name")
                    if (name.isNotBlank()) list.add(name)
                }
                if (list.isNotEmpty()) return list.sorted()
            }
        }
        // Fallback to openai compatible
        return fetchOpenAiCompatibleModels(provider)
    }

    private suspend fun fetchOpenAiCompatibleModels(provider: ApiProviderEntity): List<String> {
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = when {
            base.endsWith("/models") -> base
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
        validateEndpoint(endpoint, provider.providerType)
        val builder = Request.Builder().url(endpoint).get()
        provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        runCatching {
            val custom = JSONObject(provider.customHeadersJson)
            custom.keys().forEach { key -> builder.header(key, custom.getString(key)) }
        }
        val payload = performRequest(builder.build())
        if (!payload.isSuccessful) {
            throw IOException("HTTP ${payload.code}: ${extractError(payload.body)}")
        }
        val list = mutableListOf<String>()
        val bodyStr = payload.body.trim()
        if (bodyStr.startsWith("[")) {
            val arr = JSONArray(bodyStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val id = obj?.optString("id") ?: obj?.optString("name") ?: arr.optString(i)
                if (id.isNotBlank()) list.add(id)
            }
        } else {
            val json = JSONObject(bodyStr)
            val data = json.optJSONArray("data") ?: json.optJSONArray("models")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i)
                    val id = item?.optString("id") ?: item?.optString("name")
                    if (!id.isNullOrBlank()) list.add(id)
                }
            }
        }
        return list.distinct().sorted()
    }

    suspend fun executeCompletion(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): LlmResult {
        val startedAt = System.currentTimeMillis()
        var lastResult: LlmResult? = null
        var previousPromptTokens = 0L
        var previousCompletionTokens = 0L
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = try {
                when (provider.providerType) {
                    ProviderType.ANTHROPIC_CLAUDE -> callAnthropic(
                        provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt
                    )
                    ProviderType.GEMINI_DIRECT -> callGemini(
                        provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt
                    )
                    else -> callOpenAiCompatible(
                        provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LlmResult(
                    text = "", promptTokens = 0, completionTokens = 0, isSuccess = false,
                    errorMessage = e.localizedMessage ?: "Network/LLM error: ${e.javaClass.simpleName}",
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
            if (result.isSuccess || !isRetryable(result) || attempt == MAX_ATTEMPTS - 1) {
                return result.copy(
                    promptTokens = previousPromptTokens + result.promptTokens,
                    completionTokens = previousCompletionTokens + result.completionTokens
                )
            }
            previousPromptTokens += result.promptTokens
            previousCompletionTokens += result.completionTokens
            lastResult = result
            delay(600L * (1L shl attempt))
        }
        return lastResult ?: LlmResult("", 0, 0, false, errorMessage = "Request failed")
    }

    private fun isRetryable(result: LlmResult): Boolean {
        val message = result.errorMessage.orEmpty()
        return message.contains("HTTP 408") || message.contains("HTTP 429") ||
            Regex("HTTP 5\\d\\d").containsMatchIn(message) ||
            message.contains("timeout", true) || message.contains("connection", true) ||
            message.contains("stream", true)
    }

    private suspend fun callOpenAiCompatible(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long
    ): LlmResult {
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
        validateEndpoint(endpoint, provider.providerType)
        val model = provider.selectedModel.ifBlank { "gpt-4o-mini" }
        val officialReasoningModel = URI(endpoint).host.equals("api.openai.com", true) &&
            (model.startsWith("o") || model.startsWith("gpt-5"))
        val body = JSONObject().apply {
            put("model", model)
            if (!officialReasoningModel) put("temperature", temperature ?: provider.temperature)
            if (maxTokens != null && maxTokens > 0) {
                put(if (officialReasoningModel) "max_completion_tokens" else "max_tokens", maxTokens)
            }
            put("messages", JSONArray().apply {
                if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody(jsonMediaType))
        provider.apiKey.trim().takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        runCatching {
            val custom = JSONObject(provider.customHeadersJson)
            custom.keys().forEach { key -> builder.header(key, custom.getString(key)) }
        }
        val payload = performRequest(builder.build())
        val duration = System.currentTimeMillis() - startedAt
        if (!payload.isSuccessful) return httpFailure(payload, duration)
        val json = JSONObject(payload.body)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
        val finishReason = choice?.optString("finish_reason", "")?.ifBlank { null }
        val usage = json.optJSONObject("usage")
        val promptTokens = usage?.optLong("prompt_tokens") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usage?.optLong("completion_tokens") ?: TokenCalculator.estimateTokens(content)
        if (content.isBlank()) return emptyFailure("Provider", finishReason, duration, promptTokens, completionTokens)
        return LlmResult(
            text = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = finishReason,
            isTruncated = finishReason.equals("length", true),
            durationMs = duration
        )
    }

    private suspend fun callAnthropic(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long
    ): LlmResult {
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = when {
            base.endsWith("/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
        validateEndpoint(endpoint, provider.providerType)
        require(provider.apiKey.isNotBlank()) { "Anthropic API key is required" }
        val body = JSONObject().apply {
            put("model", provider.selectedModel.ifBlank { "claude-haiku-4-5-20251001" })
            put("max_tokens", maxTokens ?: minOf(16_384, provider.maxContextTokens / 2))
            put("temperature", temperature ?: provider.temperature)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        }
        val request = Request.Builder().url(endpoint)
            .header("x-api-key", provider.apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType)).build()
        val payload = performRequest(request)
        val duration = System.currentTimeMillis() - startedAt
        if (!payload.isSuccessful) return httpFailure(payload, duration)
        val json = JSONObject(payload.body)
        val blocks = json.optJSONArray("content")
        val content = buildString {
            if (blocks != null) for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        val finishReason = json.optString("stop_reason", "").ifBlank { null }
        val usage = json.optJSONObject("usage")
        val promptTokens = usage?.optLong("input_tokens") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usage?.optLong("output_tokens") ?: TokenCalculator.estimateTokens(content)
        if (content.isBlank()) return emptyFailure("Anthropic", finishReason, duration, promptTokens, completionTokens)
        return LlmResult(
            text = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = finishReason,
            isTruncated = finishReason.equals("max_tokens", true),
            durationMs = duration
        )
    }

    private suspend fun callGemini(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long
    ): LlmResult {
        val model = provider.selectedModel.ifBlank { "gemini-2.5-flash" }
        val apiKey = provider.apiKey.trim()
        require(apiKey.isNotBlank()) { "Gemini API key is required" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))))
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature ?: provider.temperature)
                if (maxTokens != null && maxTokens > 0) put("maxOutputTokens", maxTokens)
            })
        }
        val payload = performRequest(
            Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        )
        val duration = System.currentTimeMillis() - startedAt
        if (!payload.isSuccessful) return httpFailure(payload, duration)
        val json = JSONObject(payload.body)
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val content = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        val finishReason = candidate?.optString("finishReason", "")?.ifBlank { null }
        val usage = json.optJSONObject("usageMetadata")
        val promptTokens = usage?.optLong("promptTokenCount") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usage?.optLong("candidatesTokenCount") ?: TokenCalculator.estimateTokens(content)
        if (content.isBlank()) return emptyFailure("Gemini", finishReason, duration, promptTokens, completionTokens)
        return LlmResult(
            text = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = finishReason,
            isTruncated = finishReason.equals("MAX_TOKENS", true),
            durationMs = duration
        )
    }

    private suspend fun performRequest(request: Request): HttpPayload = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = readResponseBodyLimited(it)
                    if (continuation.isActive) continuation.resume(HttpPayload(it.code, responseBody))
                }
            }
        })
    }

    private fun readResponseBodyLimited(response: Response): String {
        val body = response.body ?: return ""
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var truncated = false
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = MAX_RESPONSE_BYTES - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val accepted = minOf(count, remaining)
                output.write(buffer, 0, accepted)
                if (accepted < count) {
                    truncated = true
                    break
                }
            }
        }
        return output.toString(Charsets.UTF_8.name()) + if (truncated) "\n[response body truncated]" else ""
    }

    private fun httpFailure(payload: HttpPayload, duration: Long) = LlmResult(
        text = "", promptTokens = 0, completionTokens = 0, isSuccess = false,
        errorMessage = "HTTP ${payload.code}: ${extractError(payload.body)}", durationMs = duration
    )

    private fun emptyFailure(
        provider: String,
        finishReason: String?,
        duration: Long,
        promptTokens: Long,
        completionTokens: Long
    ) = LlmResult(
        text = "", promptTokens = promptTokens, completionTokens = completionTokens, isSuccess = false,
        finishReason = finishReason,
        errorMessage = "$provider returned no text${finishReason?.let { " (finish reason: $it)" }.orEmpty()}",
        durationMs = duration
    )

    private fun extractError(body: String): String {
        val parsed = runCatching {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message") ?: obj.optString("message")
        }.getOrNull().orEmpty()
        return parsed.ifBlank { body }.take(MAX_ERROR_CHARS)
    }

    private fun validateEndpoint(endpoint: String, providerType: ProviderType) {
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("Invalid API endpoint") }
        require(!uri.host.isNullOrBlank()) { "API endpoint must include a valid host" }
        if (uri.scheme.equals("https", true)) return
        val allowedLocalHttp = providerType == ProviderType.OLLAMA_LOCAL &&
            uri.scheme.equals("http", true) && isPrivateHost(uri.host)
        require(allowedLocalHttp) {
            "HTTPS is required; HTTP is allowed only for Ollama on localhost or a private LAN address"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val value = host.trim('[', ']').lowercase()
        if (value == "localhost" || value == "::1" || value.endsWith(".local")) return true
        val octets = value.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_CHARS = 4096
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
