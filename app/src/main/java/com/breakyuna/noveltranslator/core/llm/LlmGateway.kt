package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val promptCacheHint: PromptCacheHint? = null,
    /** Optional per-request gate used by the translation scheduler. */
    val controlSignal: TranslationControlSignal? = null
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

/** Convenience overload for small agents that do not need request metadata. */
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

/** Signal that prevents retry/continuation code from starting a new paid request after pause. */
class TranslationControlSignal {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    /** Serializes the complete provider call, not just the preflight check. */
    private val requestGate = Mutex()

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    /**
     * Marks the signal paused and waits for an already-started provider call to finish. This makes
     * the return of pause a hard boundary: no retry can begin after the caller observes it.
     */
    suspend fun requestPause() {
        paused.set(true)
        requestGate.withLock { }
    }

    /** Marks the signal cancelled and waits until an already-started provider call has finished. */
    suspend fun requestCancel() {
        cancelled.set(true)
        requestGate.withLock { }
    }

    fun resume() = paused.set(false)
    fun cancel() = cancelled.set(true)

    /** Checks the signal while holding the same gate as the whole physical provider call. */
    suspend fun <T> withRequestPermit(block: suspend () -> T): T? = requestGate.withLock {
        if (paused.get() || cancelled.get()) null else block()
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
    private val delayProvider: DelayProvider = CoroutineDelayProvider
) : LlmGateway {
    override suspend fun executeCompletion(request: LlmRequest): LlmResult {
        val attempts = mutableListOf<LlmAttempt>()
        val signal = request.controlSignal
        var attemptNumber = 1
        while (true) {
            if (signal?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("Translation cancelled")
            }
            awaitResume(signal)
            val result = try {
                if (signal == null) {
                    delegate.executeCompletion(request)
                } else {
                    // A pause racing with this loop either waits here or wins the permit check;
                    // neither path can start another physical provider call while paused.
                    signal.withRequestPermit { delegate.executeCompletion(request) }
                        ?: continue
                }
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
            // A provider may return useful-looking text with finish_reason=length. Treat it as a
            // failed response here so every workflow path (draft, repair, chunk and review) gets
            // the same no-commit behavior instead of relying on individual parsers.
            val normalizedResult = result.copy(
                operation = request.operation,
                isSuccess = result.isSuccess && !result.isTruncated,
                errorCategory = if (result.isTruncated) LlmErrorCategory.TRUNCATED_OUTPUT else result.errorCategory,
                retryable = result.retryable && !result.isTruncated,
                errorMessage = if (result.isTruncated && result.errorMessage.isNullOrBlank()) {
                    "Provider response was truncated (${result.finishReason ?: "length"})"
                } else {
                    result.errorMessage
                }
            )
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
            waitForRetry(decision.delayMs, signal)
            attemptNumber++
        }
    }

    private suspend fun awaitResume(signal: TranslationControlSignal?) {
        if (signal == null) return
        while (signal.isPaused) {
            if (signal.isCancelled) throw kotlinx.coroutines.CancellationException("Translation cancelled")
            delayProvider.delayFor(250L)
        }
        if (signal.isCancelled) throw kotlinx.coroutines.CancellationException("Translation cancelled")
    }

    private suspend fun waitForRetry(delayMs: Long, signal: TranslationControlSignal?) {
        if (signal == null) {
            delayProvider.delayFor(delayMs)
            return
        }
        var remaining = delayMs
        while (remaining > 0) {
            if (signal.isCancelled) throw kotlinx.coroutines.CancellationException("Translation cancelled")
            awaitResume(signal)
            val slice = min(250L, remaining)
            delayProvider.delayFor(slice)
            remaining -= slice
        }
        awaitResume(signal)
    }

    override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
        delegate.fetchAvailableModels(provider)
}
