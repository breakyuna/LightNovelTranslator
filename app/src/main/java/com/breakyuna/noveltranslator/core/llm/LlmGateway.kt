package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

enum class LlmErrorCategory {
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    RATE_LIMIT,
    SERVER_ERROR,
    AUTHENTICATION,
    INVALID_REQUEST,
    CONTEXT_OVERFLOW,
    CONTENT_FILTER,
    EMPTY_RESPONSE,
    TRUNCATED_OUTPUT,
    PARSE_ERROR,
    QUALITY_REJECTED,
    CANCELLED,
    LOCAL_STORAGE,
    UNKNOWN
}

enum class UsageSource {
    ACTUAL,
    ESTIMATED,
    UNKNOWN
}

data class LlmRequest(
    val provider: ApiProviderEntity,
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val operation: String = "TRANSLATION",
    val promptCacheHint: PromptCacheHint? = null
)

data class PromptCacheHint(
    val fingerprint: String,
    val remoteCacheId: String?,
    val stablePrefixTokens: Long,
    val expiresAt: Long?
)

data class LlmAttempt(
    val attemptNumber: Int,
    val result: LlmResult
)

/** A single provider call. It must not contain retry or business workflow logic. */
interface LlmGateway {
    suspend fun executeCompletion(request: LlmRequest): LlmResult
    suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>>
}

/** Compatibility helper for existing agents while callers migrate to LlmRequest. */
suspend fun LlmGateway.executeCompletion(
    provider: ApiProviderEntity,
    systemPrompt: String,
    userPrompt: String,
    temperature: Float? = null,
    maxTokens: Int? = null,
    operation: String = "TRANSLATION"
): LlmResult = executeCompletion(
    LlmRequest(provider, systemPrompt, userPrompt, temperature, maxTokens, operation)
)

fun interface DelayProvider {
    suspend fun delayFor(milliseconds: Long)
}

/** Shared signal that prevents retry/continuation code from starting a new paid request after pause. */
class TranslationControlSignal {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    fun requestPause() = paused.set(true)
    fun resume() = paused.set(false)
    fun cancel() = cancelled.set(true)
    fun reset() {
        paused.set(false)
        cancelled.set(false)
    }
}

object CoroutineDelayProvider : DelayProvider {
    override suspend fun delayFor(milliseconds: Long) = delay(milliseconds)
}

data class RetryDecision(
    val retry: Boolean,
    val maxAttempts: Int,
    val delayMs: Long
)

object RetryPolicy {
    fun decide(result: LlmResult, attemptNumber: Int): RetryDecision {
        val category = result.errorCategory ?: LlmErrorCategory.UNKNOWN
        val maxAttempts = when (category) {
            LlmErrorCategory.RATE_LIMIT,
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            LlmErrorCategory.TIMEOUT -> 5
            LlmErrorCategory.SERVER_ERROR -> 4
            LlmErrorCategory.EMPTY_RESPONSE,
            LlmErrorCategory.PARSE_ERROR -> 3
            // The caller must shrink/rechunk; retrying the identical oversized request only wastes calls.
            LlmErrorCategory.CONTEXT_OVERFLOW -> 1
            LlmErrorCategory.QUALITY_REJECTED -> 2
            else -> 1
        }
        if (!result.retryable || attemptNumber >= maxAttempts) {
            return RetryDecision(false, maxAttempts, 0)
        }
        val retryAfter = result.retryAfterMs
        val exponential = min(60_000L, 1_000L * (1L shl (attemptNumber - 1).coerceAtMost(5)))
        val jitter = if (retryAfter == null) Random.nextLong(0L, 251L) else 0L
        return RetryDecision(true, maxAttempts, retryAfter ?: exponential + jitter)
    }
}

/** Retry wrapper with injectable waiting, suitable for unit tests with no real sleep. */
class RetryingLlmGateway(
    private val delegate: LlmGateway,
    private val delayProvider: DelayProvider = CoroutineDelayProvider,
    private val controlSignal: TranslationControlSignal? = null
) : LlmGateway {
    override suspend fun executeCompletion(request: LlmRequest): LlmResult {
        val attempts = mutableListOf<LlmAttempt>()
        var attemptNumber = 1
        while (true) {
            val result = try {
                delegate.executeCompletion(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                LlmResult(
                    text = "",
                    promptTokens = 0,
                    completionTokens = 0,
                    isSuccess = false,
                    errorCategory = LlmErrorCategory.UNKNOWN,
                    errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
                    usageSource = UsageSource.UNKNOWN,
                    operation = request.operation
                )
            }
            val normalizedResult = result.copy(operation = request.operation)
            attempts += LlmAttempt(attemptNumber, normalizedResult)
            val decision = RetryPolicy.decide(normalizedResult, attemptNumber)
            if (normalizedResult.isSuccess || !decision.retry) {
                return normalizedResult.copy(
                    promptTokens = attempts.sumOf { it.result.promptTokens },
                    completionTokens = attempts.sumOf { it.result.completionTokens },
                    durationMs = attempts.sumOf { it.result.durationMs },
                    attempts = attempts.toList()
                )
            }
            if (!waitForRetry(decision.delayMs)) {
                return normalizedResult.copy(
                    promptTokens = attempts.sumOf { it.result.promptTokens },
                    completionTokens = attempts.sumOf { it.result.completionTokens },
                    durationMs = attempts.sumOf { it.result.durationMs },
                    attempts = attempts.toList()
                )
            }
            attemptNumber++
        }
    }

    private suspend fun waitForRetry(delayMs: Long): Boolean {
        if (controlSignal == null) {
            delayProvider.delayFor(delayMs)
            return true
        }
        var remaining = delayMs
        while (remaining > 0) {
            if (controlSignal.isCancelled) throw kotlinx.coroutines.CancellationException("Translation cancelled")
            if (controlSignal.isPaused) return false
            val slice = min(250L, remaining)
            delayProvider.delayFor(slice)
            remaining -= slice
        }
        return !controlSignal.isPaused
    }

    override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
        delegate.fetchAvailableModels(provider)
}
