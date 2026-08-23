package com.example.core.llm

import com.example.BuildConfig
import com.example.data.model.ApiProviderEntity
import com.example.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun executeCompletion(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): LlmResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            when (provider.providerType) {
                ProviderType.ANTHROPIC_CLAUDE -> {
                    callAnthropic(provider, systemPrompt, userPrompt, temperature, maxTokens, startTime)
                }
                ProviderType.GEMINI_DIRECT -> {
                    callGemini(provider, systemPrompt, userPrompt, temperature, maxTokens, startTime)
                }
                else -> {
                    // OpenAI Compatible (DeepSeek, SiliconFlow, OpenRouter, DashScope, Moonshot, Zhipu, Ollama, etc.)
                    callOpenAiCompatible(provider, systemPrompt, userPrompt, temperature, maxTokens, startTime)
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorMessage = e.localizedMessage ?: "Unknown network/LLM error: ${e.javaClass.simpleName}",
                durationMs = duration
            )
        }
    }

    private fun callOpenAiCompatible(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startTime: Long
    ): LlmResult {
        var base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = if (base.endsWith("/chat/completions")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }

        val jsonBody = JSONObject().apply {
            put("model", provider.selectedModel.ifBlank { "gpt-4o-mini" })
            put("temperature", temperature ?: provider.temperature)
            if (maxTokens != null && maxTokens > 0) {
                put("max_tokens", maxTokens)
            }

            val messages = JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            put("messages", messages)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody(jsonMediaType))

        val apiKey = provider.apiKey.trim()
        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        // Custom headers if defined
        try {
            if (provider.customHeadersJson.isNotBlank() && provider.customHeadersJson != "{}") {
                val headersJson = JSONObject(provider.customHeadersJson)
                val keys = headersJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    requestBuilder.header(key, headersJson.getString(key))
                }
            }
        } catch (e: Exception) {
            // Ignore custom header parse error
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val duration = System.currentTimeMillis() - startTime
        val respBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorDetails = try {
                val errObj = JSONObject(respBody)
                errObj.optJSONObject("error")?.optString("message") ?: respBody
            } catch (e: Exception) {
                respBody
            }
            return LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorMessage = "HTTP ${response.code}: $errorDetails",
                durationMs = duration
            )
        }

        val responseJson = JSONObject(respBody)
        val choices = responseJson.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)
        val content = firstChoice?.optJSONObject("message")?.optString("content") ?: ""
        val finishReason = firstChoice?.optString("finish_reason", "")?.ifBlank { null }
        val isTruncated = finishReason.equals("length", ignoreCase = true)

        val usage = responseJson.optJSONObject("usage")
        val promptTokens = usage?.optLong("prompt_tokens") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usage?.optLong("completion_tokens") ?: TokenCalculator.estimateTokens(content)

        return LlmResult(
            text = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = finishReason,
            isTruncated = isTruncated,
            durationMs = duration
        )
    }

    private fun callAnthropic(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startTime: Long
    ): LlmResult {
        var base = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = if (base.endsWith("/messages")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/messages"
        } else {
            "$base/v1/messages"
        }

        val effectiveMaxTokens = maxTokens ?: minOf(4096, provider.maxContextTokens / 2)
        val jsonBody = JSONObject().apply {
            put("model", provider.selectedModel.ifBlank { "claude-3-5-haiku-20241022" })
            put("max_tokens", effectiveMaxTokens)
            put("temperature", temperature ?: provider.temperature)

            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            put("messages", messages)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("x-api-key", provider.apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))

        val response = client.newCall(requestBuilder.build()).execute()
        val duration = System.currentTimeMillis() - startTime
        val respBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorDetails = try {
                val errObj = JSONObject(respBody)
                errObj.optJSONObject("error")?.optString("message") ?: respBody
            } catch (e: Exception) {
                respBody
            }
            return LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorMessage = "Anthropic HTTP ${response.code}: $errorDetails",
                durationMs = duration
            )
        }

        val responseJson = JSONObject(respBody)
        val contentArray = responseJson.optJSONArray("content")
        val contentText = contentArray?.optJSONObject(0)?.optString("text") ?: ""
        val stopReason = responseJson.optString("stop_reason", "")?.ifBlank { null }
        val isTruncated = stopReason.equals("max_tokens", ignoreCase = true)

        val usage = responseJson.optJSONObject("usage")
        val promptTokens = usage?.optLong("input_tokens") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usage?.optLong("output_tokens") ?: TokenCalculator.estimateTokens(contentText)

        return LlmResult(
            text = contentText,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = stopReason,
            isTruncated = isTruncated,
            durationMs = duration
        )
    }

    private fun callGemini(
        provider: ApiProviderEntity,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float?,
        maxTokens: Int?,
        startTime: Long
    ): LlmResult {
        val model = provider.selectedModel.ifBlank { "gemini-2.5-flash" }
        val effectiveApiKey = if (provider.apiKey.isNotBlank()) {
            provider.apiKey.trim()
        } else {
            try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$effectiveApiKey"

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", userPrompt))
                    }
                    put("parts", parts)
                })
            }
            put("contents", contents)

            if (systemPrompt.isNotBlank()) {
                val sysInstruction = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    }
                    put("parts", parts)
                }
                put("systemInstruction", sysInstruction)
            }

            val genConfig = JSONObject().apply {
                put("temperature", temperature ?: provider.temperature)
                if (maxTokens != null && maxTokens > 0) {
                    put("maxOutputTokens", maxTokens)
                }
            }
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val duration = System.currentTimeMillis() - startTime
        val respBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorDetails = try {
                val errObj = JSONObject(respBody)
                errObj.optJSONObject("error")?.optString("message") ?: respBody
            } catch (e: Exception) {
                respBody
            }
            return LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorMessage = "Gemini HTTP ${response.code}: $errorDetails",
                durationMs = duration
            )
        }

        val responseJson = JSONObject(respBody)
        val candidates = responseJson.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val content = firstCandidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val text = parts?.optJSONObject(0)?.optString("text") ?: ""
        val finishReason = firstCandidate?.optString("finishReason", "")?.ifBlank { null }
        val isTruncated = finishReason.equals("MAX_TOKENS", ignoreCase = true)

        val usageMetadata = responseJson.optJSONObject("usageMetadata")
        val promptTokens = usageMetadata?.optLong("promptTokenCount") ?: TokenCalculator.estimateTokens(systemPrompt + userPrompt)
        val completionTokens = usageMetadata?.optLong("candidatesTokenCount") ?: TokenCalculator.estimateTokens(text)

        return LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = finishReason,
            isTruncated = isTruncated,
            durationMs = duration
        )
    }
}
