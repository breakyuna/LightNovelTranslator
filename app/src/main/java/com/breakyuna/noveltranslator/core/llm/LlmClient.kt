package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.CancellationException
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
import java.util.Locale
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
    val durationMs: Long = 0,
    val httpStatus: Int? = null,
    val errorCategory: LlmErrorCategory? = null,
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
    val requestId: String? = null,
    val usageSource: UsageSource = UsageSource.UNKNOWN,
    val operation: String = "TRANSLATION",
    val attempts: List<LlmAttempt> = emptyList()
)

class LlmClient : LlmGateway {
    private data class HttpPayload(
        val code: Int,
        val body: String,
        val retryAfterMs: Long? = null,
        val requestId: String? = null
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> {
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
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models"
        val builder = Request.Builder().url(endpoint).get().header("x-goog-api-key", apiKey)
        applyCustomHeaders(builder, provider)
        val request = builder.build()
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
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .get()
        applyCustomHeaders(requestBuilder, provider)
        val payload = performRequest(requestBuilder.build())
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
        validateEndpoint(endpoint, provider.providerType)
        val requestBuilder = Request.Builder().url(endpoint).get()
        applyCustomHeaders(requestBuilder, provider)
        val payload = performRequest(requestBuilder.build())
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
        applyCustomHeaders(builder, provider)
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

    override suspend fun executeCompletion(request: LlmRequest): LlmResult = executeCompletion(
        provider = request.provider,
        systemPrompt = request.systemPrompt,
        userPrompt = request.userPrompt,
        temperature = request.temperature,
        maxTokens = request.maxTokens,
        operation = request.operation,
        reasoningEffort = request.reasoningEffort
    ).copy(operation = request.operation)

    /** Executes exactly one HTTP request. Retries belong to RetryingLlmGateway. */
    suspend fun executeCompletion(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float? = null,
        maxTokens: Int? = null,
        operation: String = "TRANSLATION",
        reasoningEffort: String? = null
    ): LlmResult {
        val startedAt = System.currentTimeMillis()
        val estimatedInputTokens = TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val promptChars = systemPrompt.length + userPrompt.length
        val model = provider.selectedModel.ifBlank { "default" }

        SystemLogger.info(
            tag = "LLM_API",
            message = "📤 发送请求: ${provider.name}/$model [$operation] | 预估输入Token: $estimatedInputTokens (提示词: ${promptChars}字)"
        )

        val result = try {
            when (provider.providerType) {
                ProviderType.ANTHROPIC_CLAUDE -> callAnthropic(
                    provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt, reasoningEffort
                )
                ProviderType.GEMINI_DIRECT -> callGemini(
                    provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt, reasoningEffort
                )
                else -> callOpenAiCompatible(
                    provider, systemPrompt, userPrompt, temperature, maxTokens, startedAt, reasoningEffort
                )
            }.copy(operation = operation)
        } catch (cancelled: CancellationException) {
            val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            SystemLogger.warn(
                tag = "LLM_API",
                message = "⏹️ 请求已取消: ${provider.name}/$model [$operation] | 耗时: ${duration}ms"
            )
            throw cancelled
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            val errorCategory = classifyException(e)
            val errorMsg = safeErrorMessage(e)
            SystemLogger.error(
                tag = "LLM_API",
                message = "📥 请求异常: ${provider.name}/$model [$operation] | 耗时: ${duration}ms | 错误: $errorCategory ($errorMsg)"
            )
            return LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorCategory = errorCategory,
                retryable = isRetryableCategory(errorCategory),
                errorMessage = errorMsg,
                usageSource = UsageSource.UNKNOWN,
                durationMs = duration,
                operation = operation
            )
        }

        val duration = result.durationMs.coerceAtLeast(1)
        val durationSec = duration / 1000.0
        val outSpeed = String.format(Locale.US, "%.1f", result.completionTokens / durationSec)
        val totalTokens = result.promptTokens + result.completionTokens
        val totalSpeed = String.format(Locale.US, "%.1f", totalTokens / durationSec)

        if (result.isSuccess) {
            SystemLogger.info(
                tag = "LLM_API",
                message = "📥 收到响应: ${provider.name}/$model [$operation] | 成功 | 耗时: ${duration}ms (${String.format(Locale.US, "%.2f", durationSec)}s) | Token: 输入=${result.promptTokens}, 输出=${result.completionTokens}, 总计=$totalTokens | 速率: 输出 ${outSpeed} t/s (总计 ${totalSpeed} t/s)"
            )
        } else {
            val reason = result.errorMessage ?: result.errorCategory?.name ?: "UNKNOWN_ERROR"
            SystemLogger.warn(
                tag = "LLM_API",
                message = "📥 请求未完成: ${provider.name}/$model [$operation] | 耗时: ${duration}ms (${String.format(Locale.US, "%.2f", durationSec)}s) | 状态: ${result.errorCategory ?: "FAILED"} ($reason) | Token: 输入=${result.promptTokens}, 输出=${result.completionTokens}"
            )
        }

        return result
    }

    private fun isRetryableCategory(category: LlmErrorCategory): Boolean = when (category) {
        LlmErrorCategory.NETWORK_UNAVAILABLE,
        LlmErrorCategory.TIMEOUT,
        LlmErrorCategory.RATE_LIMIT,
        LlmErrorCategory.SERVER_ERROR,
        LlmErrorCategory.EMPTY_RESPONSE,
        LlmErrorCategory.PARSE_ERROR,
        LlmErrorCategory.CONTEXT_OVERFLOW -> true
        else -> false
    }

    private fun safeErrorMessage(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Unable to resolve provider host"
        is java.net.SocketTimeoutException -> "Provider request timed out"
        is java.io.IOException -> error.localizedMessage?.take(MAX_ERROR_CHARS) ?: "Network I/O failure"
        else -> error.localizedMessage?.take(MAX_ERROR_CHARS) ?: error.javaClass.simpleName
    }

    private fun classifyException(error: Throwable): LlmErrorCategory = when (error) {
        is kotlinx.coroutines.CancellationException -> LlmErrorCategory.CANCELLED
        is java.net.SocketTimeoutException,
        is java.io.InterruptedIOException -> LlmErrorCategory.TIMEOUT
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.SocketException -> LlmErrorCategory.NETWORK_UNAVAILABLE
        is org.json.JSONException -> LlmErrorCategory.PARSE_ERROR
        is IllegalArgumentException -> LlmErrorCategory.INVALID_REQUEST
        else -> LlmErrorCategory.UNKNOWN
    }

    private suspend fun callOpenAiCompatible(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long,
        reasoningEffort: String? = null
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
        val isReasoningModel = officialReasoningModel ||
            model.contains("o1", ignoreCase = true) ||
            model.contains("o3", ignoreCase = true) ||
            model.contains("o4", ignoreCase = true) ||
            model.contains("r1", ignoreCase = true) ||
            model.contains("reasoner", ignoreCase = true) ||
            model.contains("reasoning", ignoreCase = true)
        val body = JSONObject().apply {
            put("model", model)
            if (!officialReasoningModel) put("temperature", temperature ?: provider.temperature)
            if (maxTokens != null && maxTokens > 0) {
                put(if (officialReasoningModel) "max_completion_tokens" else "max_tokens", maxTokens)
            }
            if (!reasoningEffort.isNullOrBlank() && isReasoningModel) {
                val effortNormalized = reasoningEffort.lowercase(Locale.ROOT)
                put("reasoning_effort", effortNormalized)
            }
            put("messages", JSONArray().apply {
                if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody(jsonMediaType))
        provider.apiKey.trim().takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        applyCustomHeaders(builder, provider)
        var payload = performRequest(builder.build())
        // Fallback: if provider rejects reasoning_effort or max_completion_tokens with HTTP 400, retry once with standard payload
        if (payload.code == 400 && (body.has("reasoning_effort") || body.has("max_completion_tokens"))) {
            val retryBody = JSONObject(body.toString()).apply {
                remove("reasoning_effort")
                if (has("max_completion_tokens")) {
                    val tokens = optInt("max_completion_tokens")
                    remove("max_completion_tokens")
                    put("max_tokens", tokens)
                }
            }
            val retryRequest = Request.Builder().url(endpoint).post(retryBody.toString().toRequestBody(jsonMediaType))
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let { retryRequest.header("Authorization", "Bearer $it") }
            applyCustomHeaders(retryRequest, provider)
            val retryPayload = performRequest(retryRequest.build())
            if (retryPayload.isSuccessful || retryPayload.code != 400) {
                payload = retryPayload
            }
        }
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
            errorCategory = if (finishReason.equals("length", true)) LlmErrorCategory.TRUNCATED_OUTPUT else null,
            usageSource = if (usage != null) UsageSource.ACTUAL else UsageSource.ESTIMATED,
            requestId = payload.requestId ?: json.optString("id").ifBlank { null },
            durationMs = duration
        )
    }

    private suspend fun callAnthropic(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long,
        reasoningEffort: String? = null
    ): LlmResult {
        val base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = when {
            base.endsWith("/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
        validateEndpoint(endpoint, provider.providerType)
        require(provider.apiKey.isNotBlank()) { "Anthropic API key is required" }
        val model = provider.selectedModel.ifBlank { "claude-haiku-4-5-20251001" }
        val isClaude37Plus = model.contains("claude-3-7", ignoreCase = true) ||
            model.contains("claude-4", ignoreCase = true)
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens ?: minOf(16_384, provider.maxContextTokens / 2))
            put("temperature", temperature ?: provider.temperature)
            if (!reasoningEffort.isNullOrBlank() && isClaude37Plus) {
                val effortNormalized = reasoningEffort.lowercase(Locale.ROOT)
                if (effortNormalized == "none" || effortNormalized == "disabled") {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        }
        val requestBuilder = Request.Builder().url(endpoint)
            .header("x-api-key", provider.apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType))
        applyCustomHeaders(requestBuilder, provider)
        var payload = performRequest(requestBuilder.build())
        if (payload.code == 400 && body.has("thinking")) {
            val retryBody = JSONObject(body.toString()).apply { remove("thinking") }
            val retryRequest = Request.Builder().url(endpoint)
                .header("x-api-key", provider.apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .post(retryBody.toString().toRequestBody(jsonMediaType))
            applyCustomHeaders(retryRequest, provider)
            val retryPayload = performRequest(retryRequest.build())
            if (retryPayload.isSuccessful || retryPayload.code != 400) {
                payload = retryPayload
            }
        }
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
            errorCategory = if (finishReason.equals("max_tokens", true)) LlmErrorCategory.TRUNCATED_OUTPUT else null,
            usageSource = if (usage != null) UsageSource.ACTUAL else UsageSource.ESTIMATED,
            requestId = payload.requestId ?: json.optString("id").ifBlank { null },
            durationMs = duration
        )
    }

    private suspend fun callGemini(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startedAt: Long,
        reasoningEffort: String? = null
    ): LlmResult {
        val model = provider.selectedModel.ifBlank { "gemini-2.5-flash" }
        val apiKey = provider.apiKey.trim()
        require(apiKey.isNotBlank()) { "Gemini API key is required" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val supportsThinking = model.contains("2.5", ignoreCase = true) ||
            model.contains("2.0", ignoreCase = true) ||
            model.contains("thinking", ignoreCase = true) ||
            model.contains("gemini-3", ignoreCase = true)
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))))
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature ?: provider.temperature)
                if (maxTokens != null && maxTokens > 0) put("maxOutputTokens", maxTokens)
                if (!reasoningEffort.isNullOrBlank() && supportsThinking) {
                    val effortNormalized = reasoningEffort.lowercase(Locale.ROOT)
                    if (effortNormalized == "none" || effortNormalized == "disabled") {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    } else if (effortNormalized == "low" || effortNormalized == "minimal") {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 1024))
                    } else if (effortNormalized == "high" || effortNormalized == "max") {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", 8192))
                    }
                }
            })
        }
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(jsonMediaType))
        applyCustomHeaders(requestBuilder, provider)
        var payload = performRequest(requestBuilder.build())
        if (payload.code == 400 && body.optJSONObject("generationConfig")?.has("thinkingConfig") == true) {
            val retryBody = JSONObject(body.toString()).apply {
                optJSONObject("generationConfig")?.remove("thinkingConfig")
            }
            val retryRequest = Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .post(retryBody.toString().toRequestBody(jsonMediaType))
            applyCustomHeaders(retryRequest, provider)
            val retryPayload = performRequest(retryRequest.build())
            if (retryPayload.isSuccessful || retryPayload.code != 400) {
                payload = retryPayload
            }
        }
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
            errorCategory = if (finishReason.equals("MAX_TOKENS", true)) LlmErrorCategory.TRUNCATED_OUTPUT else null,
            usageSource = if (usage != null) UsageSource.ACTUAL else UsageSource.ESTIMATED,
            requestId = payload.requestId,
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
                    try {
                        val responseBody = readResponseBodyLimited(it)
                        if (continuation.isActive) continuation.resume(
                            HttpPayload(
                                code = it.code,
                                body = responseBody,
                                retryAfterMs = parseRetryAfter(it.header("Retry-After")),
                                requestId = it.header("x-request-id") ?: it.header("request-id")
                            )
                        )
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
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
        httpStatus = payload.code,
        errorCategory = classifyHttpStatus(payload.code, payload.body),
        retryable = isRetryableCategory(classifyHttpStatus(payload.code, payload.body)),
        retryAfterMs = payload.retryAfterMs,
        requestId = payload.requestId,
        usageSource = UsageSource.UNKNOWN,
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
        errorCategory = when {
            finishReason.equals("MAX_TOKENS", true) || finishReason.equals("length", true) -> LlmErrorCategory.TRUNCATED_OUTPUT
            finishReason.equals("SAFETY", true) || finishReason.equals("content_filter", true) -> LlmErrorCategory.CONTENT_FILTER
            else -> LlmErrorCategory.EMPTY_RESPONSE
        },
        retryable = !finishReason.equals("SAFETY", true) && !finishReason.equals("content_filter", true),
        usageSource = if (promptTokens > 0 || completionTokens > 0) UsageSource.ESTIMATED else UsageSource.UNKNOWN,
        errorMessage = "$provider returned no text${finishReason?.let { " (finish reason: $it)" }.orEmpty()}",
        durationMs = duration
    )

    private fun classifyHttpStatus(code: Int, body: String): LlmErrorCategory = when {
        code == 401 || code == 403 -> LlmErrorCategory.AUTHENTICATION
        body.contains("safety", ignoreCase = true) || body.contains("content_filter", ignoreCase = true) -> LlmErrorCategory.CONTENT_FILTER
        code == 408 -> LlmErrorCategory.TIMEOUT
        code == 429 -> LlmErrorCategory.RATE_LIMIT
        code in 500..599 -> LlmErrorCategory.SERVER_ERROR
        code == 413 || (code in 400..499 && body.contains("context", ignoreCase = true)) -> LlmErrorCategory.CONTEXT_OVERFLOW
        code in 400..499 -> LlmErrorCategory.INVALID_REQUEST
        else -> LlmErrorCategory.UNKNOWN
    }

    private fun parseRetryAfter(value: String?): Long? = value?.trim()?.let {
        it.toLongOrNull()?.times(1_000L)
            ?: runCatching {
                val date = java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z",
                    java.util.Locale.US
                ).parse(it)
                ((date?.time ?: System.currentTimeMillis()) - System.currentTimeMillis()).coerceAtLeast(0L)
            }.getOrNull()
    }

    private fun applyCustomHeaders(builder: Request.Builder, provider: ApiProviderEntity) {
        val custom = runCatching { JSONObject(provider.customHeadersJson.ifBlank { "{}" }) }
            .getOrElse { throw IllegalArgumentException("Custom headers must be valid JSON") }
        custom.keys().forEach { key ->
            require(key.isNotBlank() && key.length <= 128 && key.none { it == '\r' || it == '\n' }) {
                "Invalid custom header name"
            }
            val value = custom.opt(key)
            require(value is String && value.length <= 4_096 && value.none { it == '\r' || it == '\n' }) {
                "Custom header values must be single-line strings"
            }
            builder.header(key, value)
        }
    }

    private fun extractError(body: String): String {
        val parsed = runCatching {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message") ?: obj.optString("message")
        }.getOrNull().orEmpty()
        return redactSensitive(parsed.ifBlank { body }).take(MAX_ERROR_CHARS)
    }

    private fun redactSensitive(value: String): String = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,}]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)[^\\s,}]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,}]+"), "\$1=[REDACTED]")

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
        val value = host.trim('[', ']').lowercase(Locale.ROOT)
        if (value == "localhost" || value == "::1" || value.endsWith(".local")) return true
        val octets = value.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    companion object {
        private const val MAX_ERROR_CHARS = 4096
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
