package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedGateway(private val script: ArrayDeque<LlmResult>) : LlmGateway {
    var calls: Int = 0
        private set

    override suspend fun executeCompletion(request: LlmRequest): LlmResult {
        calls++
        return script.removeFirst()
    }

    override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
        Result.success(emptyList())
}

private class RecordingDelayProvider : DelayProvider {
    val waits = mutableListOf<Long>()
    override suspend fun delayFor(milliseconds: Long) {
        waits += milliseconds
    }
}

private fun schedulerProvider() = ApiProviderEntity(
    name = "fake",
    providerType = ProviderType.OPENAI_COMPATIBLE,
    baseUrl = "https://example.invalid/v1",
    apiKey = "not-real",
    selectedModel = "fake-model"
)

private fun rateLimited(retryAfterMs: Long? = null) = LlmResult(
    text = "",
    promptTokens = 2,
    completionTokens = 0,
    isSuccess = false,
    errorCategory = LlmErrorCategory.RATE_LIMIT,
    retryable = true,
    retryAfterMs = retryAfterMs,
    errorMessage = "429"
)

private fun transient(category: LlmErrorCategory) = LlmResult(
    text = "",
    promptTokens = 2,
    completionTokens = 0,
    isSuccess = false,
    errorCategory = category,
    retryable = true,
    errorMessage = category.name
)

class RetryingLlmGatewaySchedulerTest {

    /**
     * 构造绑定虚拟时钟的调度器。[clock] 必须由 `runTest` 内部传入
     * （`TestScope.currentTime` 是扩展属性，成员函数体内不可见）。
     */
    private fun schedulerFor(
        concurrency: Int = AUTO_CONCURRENCY_INITIAL,
        rpm: Int = 0,
        clock: () -> Long = { 0L }
    ): LlmRequestScheduler = LlmRequestScheduler(
        limits = RequestLimits(autoConcurrency = true, concurrencyLimit = concurrency, rpmLimit = rpm),
        rate = RequestRate(rpsLimit = concurrency, rpmLimit = rpm, clock = clock)
    )

    @Test
    fun withoutSchedulerRetriesAreNotPacedAtAll() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(listOf(rateLimited(), LlmResult("ok", 1, 1, true)))
        )
        val waits = RecordingDelayProvider()
        val result = RetryingLlmGateway(fake, waits)
            .executeCompletion(LlmRequest(schedulerProvider(), "", ""))

        assertEquals(2, fake.calls)
        assertTrue(result.isSuccess)
        // 重试退避仍然走 DelayProvider，但没有调度器就没有任何速率/冷却等待
        assertEquals(1, waits.waits.size)
        assertEquals(0L, currentTime)
    }

    @Test
    fun cooldownGatesTheRetryUntilTheProviderMayBeCalledAgain() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(listOf(rateLimited(), LlmResult("ok", 1, 1, true)))
        )
        val scheduler = schedulerFor(concurrency = 4, clock = { currentTime })
        val gateway = RetryingLlmGateway(fake, RecordingDelayProvider(), scheduler)

        val result = async {
            gateway.executeCompletion(LlmRequest(schedulerProvider(), "", ""))
        }
        runCurrent()
        // 第一次物理请求立即发出，429 让调度器降并发并进入冷却
        assertEquals(1, fake.calls)
        assertEquals(2, scheduler.currentConcurrency)
        assertEquals(1, scheduler.concurrencyVersion)
        assertTrue(result.isActive)

        // 冷却结束前不会发起第二次付费请求
        advanceTimeBy(14_000L)
        runCurrent()
        assertEquals(1, fake.calls)
        assertTrue(result.isActive)

        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(2, fake.calls)
        assertTrue(result.await().isSuccess)
        // 成功一次后升档
        assertEquals(3, scheduler.currentConcurrency)
    }

    @Test
    fun everyPhysicalAttemptIsReportedToTheScheduler() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(listOf(transient(LlmErrorCategory.NETWORK_UNAVAILABLE), LlmResult("ok", 1, 1, true)))
        )
        val scheduler = schedulerFor(concurrency = 4, clock = { currentTime })
        val gateway = RetryingLlmGateway(fake, RecordingDelayProvider(), scheduler)

        val result = async {
            gateway.executeCompletion(LlmRequest(schedulerProvider(), "", ""))
        }
        advanceUntilIdle()

        assertTrue(result.await().isSuccess)
        assertEquals(2, result.await().attempts.size)
        // 瞬时失败只冷却不降并发，成功一次后 +1
        assertEquals(5, scheduler.currentConcurrency)
        assertEquals(1, scheduler.state.value.consecutiveSuccesses)
        assertEquals(0, scheduler.state.value.consecutiveFailures)
    }

    @Test
    fun cancelDuringCooldownStopsBeforeAnotherPhysicalRequest() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(listOf(rateLimited(), LlmResult("must not run", 1, 1, true)))
        )
        val scheduler = schedulerFor(concurrency = 4, clock = { currentTime })
        val signal = TranslationControlSignal()
        val gateway = RetryingLlmGateway(fake, RecordingDelayProvider(), scheduler)

        val result = async {
            gateway.executeCompletion(
                LlmRequest(schedulerProvider(), "", "", controlSignal = signal)
            )
        }
        runCurrent()
        assertEquals(1, fake.calls)

        // 冷却中取消：不得再发起任何物理请求
        signal.cancel()
        advanceTimeBy(20_000L)
        runCurrent()
        val error = runCatching { result.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, fake.calls)
        // 取消不惩罚 provider：冷却仍按原计划结束，状态未被取消污染
        assertEquals(2, scheduler.currentConcurrency)
        assertEquals(15_000L, scheduler.cooldownUntilMs)
    }

    @Test
    fun serverRetryAfterExtendsTheCooldownThroughTheGateway() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(listOf(rateLimited(retryAfterMs = 30_000), LlmResult("ok", 1, 1, true)))
        )
        val scheduler = schedulerFor(concurrency = 4, clock = { currentTime })
        val gateway = RetryingLlmGateway(fake, RecordingDelayProvider(), scheduler)

        val result = async {
            gateway.executeCompletion(LlmRequest(schedulerProvider(), "", ""))
        }
        runCurrent()
        assertEquals(1, fake.calls)
        // 服务端 Retry-After 30s 覆盖 15s 阶梯
        assertEquals(30_000L, scheduler.cooldownUntilMs)

        advanceTimeBy(29_000L)
        runCurrent()
        assertEquals(1, fake.calls)
        assertTrue(result.isActive)

        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(2, fake.calls)
        assertTrue(result.await().isSuccess)
    }

    @Test
    fun truncationIsReportedAsTerminalAndNeverAdjusted() = runTest {
        val fake = ScriptedGateway(
            ArrayDeque(
                listOf(
                    LlmResult(
                        text = "partial",
                        promptTokens = 1,
                        completionTokens = 2,
                        isSuccess = true,
                        isTruncated = true,
                        finishReason = "length"
                    )
                )
            )
        )
        val scheduler = schedulerFor(concurrency = 4, clock = { currentTime })
        val result = RetryingLlmGateway(fake, RecordingDelayProvider(), scheduler)
            .executeCompletion(LlmRequest(schedulerProvider(), "", ""))

        assertEquals(1, fake.calls)
        assertTrue(!result.isSuccess)
        assertEquals(LlmErrorCategory.TRUNCATED_OUTPUT, result.errorCategory)
        // 截断由上层修复流程处理，调度器保持原状
        assertEquals(4, scheduler.currentConcurrency)
        assertEquals(0, scheduler.concurrencyVersion)
        assertEquals(0L, scheduler.cooldownUntilMs)
    }
}
