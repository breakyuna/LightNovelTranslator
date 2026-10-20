package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
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

/**
 * 原生 function calling 的工具声明（OpenAI 兼容形状）。
 * [parametersJson] 是该工具的 JSON Schema 对象文本，由 Agent 工具目录映射而来。
 */
data class LlmToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** 响应里的一个原生工具调用；[argumentsJson] 是字符串化的参数 JSON。 */
data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * 流式（SSE）响应的一个事件。
 *
 * 目前只有 Agent 对话页使用流式：模型生成期间逐段回显，避免"发出去半天没动静"。
 * 翻译管线继续走 [LlmGateway.executeCompletion]——它要拿到完整文本才能解析章节与分句，
 * 且按块重试的语义建立在一次性拿到完整响应之上。
 */
sealed interface LlmStreamChunk {

    /** 一段可见文本增量；按到达顺序拼接即完整答复。 */
    data class TextDelta(val text: String) : LlmStreamChunk

    /** 原生 function calling 的工具调用；参数已按流内 index 合并完成。 */
    data class ToolCalls(val calls: List<LlmToolCall>) : LlmStreamChunk

    /** 流正常结束。[result] 与非流式响应同构，供调用方计费与判定成败。 */
    data class Completed(val result: LlmResult) : LlmStreamChunk

    /** 流失败。[result] 一定 `isSuccess = false`，错误分类与原因可展示。 */
    data class Failed(val result: LlmResult) : LlmStreamChunk
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
    val controlSignal: TranslationControlSignal? = null,
    /** Optional reasoning/thinking effort level (e.g., "none", "low", "medium", "high"). */
    val reasoningEffort: String? = null,
    /**
     * 原生 function calling 工具目录。非空时请求携带 tools 数组（仅 OpenAI 兼容形状的
     * provider 支持）；provider 以 400 拒绝时 LlmClient 自动去掉 tools 重试一次。
     */
    val tools: List<LlmToolSpec>? = null
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

    /**
     * 流式执行一次请求。
     *
     * 默认实现退化为非流式：整段文本作为单个 [LlmStreamChunk.TextDelta] 发出，
     * 因此没有实现 SSE 的 gateway（含各测试替身）与 [executeCompletion] 行为完全一致。
     * 真正的 SSE 由 [LlmClient] 实现，[RetryingLlmGateway] 在其上补重试与调度。
     */
    fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> = flow {
        val result = executeCompletion(request)
        when {
            !result.isSuccess -> emit(LlmStreamChunk.Failed(result))
            result.toolCalls.isNotEmpty() -> {
                if (result.text.isNotBlank()) emit(LlmStreamChunk.TextDelta(result.text))
                emit(LlmStreamChunk.ToolCalls(result.toolCalls))
                emit(LlmStreamChunk.Completed(result))
            }
            result.text.isNotBlank() -> {
                emit(LlmStreamChunk.TextDelta(result.text))
                emit(LlmStreamChunk.Completed(result))
            }
            else -> emit(
                LlmStreamChunk.Failed(
                    result.copy(
                        isSuccess = false,
                        errorCategory = LlmErrorCategory.EMPTY_RESPONSE,
                        errorMessage = "模型返回了空内容"
                    )
                )
            )
        }
    }
}

/** Convenience overload for small agents that do not need request metadata. */
suspend fun LlmGateway.executeCompletion(
    provider: ApiProviderEntity,
    systemPrompt: String,
    userPrompt: String,
    temperature: Float? = null,
    maxTokens: Int? = null,
    operation: String = "TRANSLATION",
    reasoningEffort: String? = null
): LlmResult = executeCompletion(
    LlmRequest(
        provider = provider,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        operation = operation,
        reasoningEffort = reasoningEffort
    )
)

fun interface DelayProvider {
    suspend fun delayFor(milliseconds: Long)
}

/**
 * 会做派发节奏控制的 gateway 实现此接口，让翻译引擎按**实时**额度调节章级并发，
 * 而不是只认工程里那个静态的 `parallelChapters`。
 */
interface DispatchPacing {

    /**
     * [provider] 当前允许的派发并发度；没有接线限流时返回 [fallback]（即工程配置值）。
     * 非挂起：它只是读一个内存快照，不需要为它付出一次调度。
     */
    fun currentDispatchConcurrency(provider: ApiProviderEntity, fallback: Int): Int

    /** 每次翻译 run 启动时调用一次，清掉上一轮遗留的终端 fail-fast，避免误伤改好 Key 后的新一轮。 */
    fun onRunStarted(provider: ApiProviderEntity)
}

/** Signal that prevents retry/continuation code from starting a new paid request after pause. */
class TranslationControlSignal {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    /**
     * Number of provider calls currently inside [withRequestPermit]. Parallel chapter workers
     * each register one call, so the signal must count them instead of serializing them.
     */
    private val inFlight = MutableStateFlow(0)
    /** Serializes the paused/cancelled check together with the in-flight registration. */
    private val registrationGate = Mutex()

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    /**
     * Marks the signal paused and waits for an already-started provider call to finish. This makes
     * the return of pause a hard boundary: no retry can begin after the caller observes it.
     */
    suspend fun requestPause() {
        beginPause()
        awaitDrain()
    }

    /** Marks the signal cancelled and waits until every already-started provider call has finished. */
    suspend fun requestCancel() {
        beginCancel()
        awaitDrain()
    }

    /**
     * Marks the signal paused without waiting for in-flight calls.
     *
     * Callers that must publish a durable state before draining (the engine's pause/cancel) use
     * this plus [awaitDrain] so the UI can show the user's decision while the last provider call
     * is still settling; waiting first kept the durable rows on RUNNING for as long as the
     * provider took, which is indistinguishable from a dead button.
     */
    suspend fun beginPause() {
        registrationGate.withLock { paused.set(true) }
    }

    /** Marks the signal cancelled without waiting for in-flight calls. Pair with [awaitDrain]. */
    suspend fun beginCancel() {
        registrationGate.withLock { cancelled.set(true) }
    }

    fun resume() = paused.set(false)
    fun cancel() = cancelled.set(true)

    /** Checks the signal while registering the physical provider call as in flight. */
    suspend fun <T> withRequestPermit(block: suspend () -> T): T? {
        registrationGate.withLock {
            if (paused.get() || cancelled.get()) return null
            inFlight.value = inFlight.value + 1
        }
        try {
            return block()
        } finally {
            inFlight.value = (inFlight.value - 1).coerceAtLeast(0)
        }
    }

    /** Suspends until no registered provider call is running. */
    suspend fun awaitDrain() {
        inFlight.filter { count -> count <= 0 }.first()
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
    /**
     * Optional request-level scheduler (rate limiting + 429 cooldown + auto concurrency).
     *
     * `null` keeps the historical behavior exactly: no pacing, no cooldown, no adjustments.
     * When present, every physical attempt first waits for [LlmRequestScheduler.awaitDispatchPermit]
     * and then reports its outcome, so a 429 both backs this request off (RetryPolicy) and cools
     * the provider for the following ones (scheduler). The scheduler must serve the provider of
     * the requests this gateway executes; see [LlmRequestSchedulerFactory] for the wiring.
     */
    private val scheduler: LlmRequestScheduler? = null,
    /**
     * Per-request scheduler resolution. Preferred over [scheduler] because one gateway instance
     * serves every provider, while schedulers — and their cooldowns, probing and fail-fast
     * windows — are per provider resource. [scheduler] stays for tests and single-provider setups.
     */
    private val schedulerProvider: ((ApiProviderEntity) -> LlmRequestScheduler?)? = null
) : LlmGateway(), DispatchPacing {

    /** The scheduler that owns [request]'s provider, if pacing is wired at all. */
    private fun schedulerFor(request: LlmRequest): LlmRequestScheduler? =
        schedulerProvider?.invoke(request.provider) ?: scheduler

    override fun currentDispatchConcurrency(provider: ApiProviderEntity, fallback: Int): Int =
        schedulerProvider?.invoke(provider)?.currentConcurrency
            ?: scheduler?.currentConcurrency
            ?: fallback

    override fun onRunStarted(provider: ApiProviderEntity) {
        schedulerProvider?.invoke(provider)?.resetTerminalFailFast()
    }

    override suspend fun executeCompletion(request: LlmRequest): LlmResult {
        val attempts = mutableListOf<LlmAttempt>()
        val signal = request.controlSignal
        val activeScheduler = schedulerFor(request)
        var attemptNumber = 1
        while (true) {
            if (signal?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("Translation cancelled")
            }
            awaitResume(signal)
            // Rate/capacity gate first (may suspend in a 429 cooldown), then the physical call
            // itself goes through the signal's registration gate. A pause racing this loop is
            // settled by withRequestPermit below; a cancel during the cooldown throws here and
            // never reaches the provider.
            val dispatchVersion = try {
                activeScheduler?.awaitDispatchPermit(signal)
            } catch (terminal: SchedulerTerminalException) {
                // Fail fast: the provider already rejected this class of request, so spending
                // another round-trip to rediscover it would only delay the truthful failure.
                return terminal.toLlmResult(request)
            }
            val result = try {
                if (signal == null) {
                    delegate.executeCompletion(request)
                } else {
                    // A pause racing with this loop either waits here or wins the permit check;
                    // neither path can start another physical provider call while paused.
                    signal.withRequestPermit { delegate.executeCompletion(request) } ?: run {
                        // The permit was granted but the gate refused the call. Release it so the
                        // recovery probe it may have created does not strand the other workers.
                        activeScheduler?.abandonDispatchPermit()
                        continue
                    }
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
            // A provider may return useful-looking text with finish_reason=length. Keep it
            // non-successful and non-retryable so callers cannot blindly repeat a paid request;
            // translation workflows may still recover a parseable prefix chapter-by-chapter.
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
            reportToScheduler(activeScheduler, dispatchVersion, normalizedResult)
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

    /**
     * Feeds one settled attempt to the optional scheduler. Successes climb the auto concurrency,
     * 429s halve it and cool the provider down, and cancellations are ignored so a user cancel
     * never punishes the provider. The scheduler verdict is intentionally not acted upon here:
     * [RetryPolicy] remains the only authority on whether another attempt happens.
     */
    private fun reportToScheduler(
        scheduler: LlmRequestScheduler?,
        dispatchVersion: Int?,
        result: LlmResult
    ) {
        if (scheduler == null) return
        val version = dispatchVersion ?: scheduler.concurrencyVersion
        if (result.isSuccess) {
            scheduler.onSuccess(version)
            return
        }
        scheduler.onFailure(
            category = result.errorCategory ?: LlmErrorCategory.UNKNOWN,
            retryAfterMs = result.retryAfterMs,
            dispatchVersion = version
        )
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

    /**
     * 流式执行：取消/暂停边界、调度与 [RetryPolicy] 与 [executeCompletion] 完全一致。
     *
     * 唯一的差别是重试条件：已经向下游转发过文本增量的请求不再重试。重发既会造成重复
     * 计费，也会让用户看到重复内容，这种失败交由调用方按"已收到部分输出"处理。
     */
    override fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> = flow {
        val signal = request.controlSignal
        val activeScheduler = schedulerFor(request)
        val attempts = mutableListOf<LlmAttempt>()
        var attemptNumber = 1
        while (true) {
            if (signal?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("Translation cancelled")
            }
            awaitResume(signal)
            val dispatchVersion = try {
                activeScheduler?.awaitDispatchPermit(signal)
            } catch (terminal: SchedulerTerminalException) {
                emit(LlmStreamChunk.Failed(terminal.toLlmResult(request)))
                return@flow
            }
            var forwardedText = false
            var terminal: LlmStreamChunk? = null
            try {
                delegate.executeStreaming(request).collect { chunk ->
                    when (chunk) {
                        is LlmStreamChunk.TextDelta -> {
                            forwardedText = true
                            emit(chunk)
                        }
                        is LlmStreamChunk.ToolCalls -> emit(chunk)
                        is LlmStreamChunk.Completed, is LlmStreamChunk.Failed -> terminal = chunk
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                terminal = LlmStreamChunk.Failed(streamFailureResult(error, request))
            }
            val settled = when (val chunk = terminal) {
                is LlmStreamChunk.Completed -> chunk.result
                is LlmStreamChunk.Failed -> chunk.result
                else -> LlmResult(
                    text = "",
                    promptTokens = 0,
                    completionTokens = 0,
                    isSuccess = false,
                    errorCategory = LlmErrorCategory.UNKNOWN,
                    errorMessage = "流式响应未给出结束事件",
                    usageSource = UsageSource.UNKNOWN,
                    operation = request.operation
                )
            }
            attempts += LlmAttempt(attemptNumber, settled)
            reportToScheduler(activeScheduler, dispatchVersion, settled)
            val decision = RetryPolicy.decide(settled, attemptNumber)
            if (settled.isSuccess || !decision.retry || forwardedText) {
                val aggregated = settled.copy(
                    promptTokens = attempts.sumOf { it.result.promptTokens },
                    completionTokens = attempts.sumOf { it.result.completionTokens },
                    durationMs = attempts.sumOf { it.result.durationMs },
                    attempts = attempts.toList()
                )
                emit(
                    if (settled.isSuccess) LlmStreamChunk.Completed(aggregated)
                    else LlmStreamChunk.Failed(aggregated)
                )
                return@flow
            }
            waitForRetry(decision.delayMs, signal)
            attemptNumber++
        }
    }

    /** 把流式管道里的异常折算成与非流式一致分类的失败结果，供 [RetryPolicy] 判定是否重试。 */
    private fun streamFailureResult(error: Throwable, request: LlmRequest): LlmResult {
        val category = when (error) {
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException -> LlmErrorCategory.TIMEOUT
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is java.net.SocketException -> LlmErrorCategory.NETWORK_UNAVAILABLE
            else -> LlmErrorCategory.UNKNOWN
        }
        return LlmResult(
            text = "",
            promptTokens = 0,
            completionTokens = 0,
            isSuccess = false,
            errorCategory = category,
            retryable = category == LlmErrorCategory.NETWORK_UNAVAILABLE ||
                category == LlmErrorCategory.TIMEOUT,
            errorMessage = error.localizedMessage?.take(4_096) ?: error.javaClass.simpleName,
            usageSource = UsageSource.UNKNOWN,
            operation = request.operation
        )
    }

    /**
     * fail-fast 结果：没有发起任何 provider 调用，所以不计费、不重试，
     * 但必须带上调度器记录的原始类别，让上层报出真实原因。
     */
    private fun SchedulerTerminalException.toLlmResult(request: LlmRequest) = LlmResult(
        text = "",
        promptTokens = 0,
        completionTokens = 0,
        isSuccess = false,
        errorCategory = category,
        retryable = false,
        errorMessage = message,
        usageSource = UsageSource.UNKNOWN,
        operation = request.operation
    )
}
