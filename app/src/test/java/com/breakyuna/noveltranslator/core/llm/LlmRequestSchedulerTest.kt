package com.breakyuna.noveltranslator.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRequestSchedulerTest {

    private fun limits(
        auto: Boolean = true,
        concurrency: Int = AUTO_CONCURRENCY_INITIAL,
        rpm: Int = 0
    ) = RequestLimits(autoConcurrency = auto, concurrencyLimit = concurrency, rpmLimit = rpm)

    /** 状态机测试用：可前进的挂钟。 */
    private class FakeClock(var nowMs: Long = 0L)

    private fun newScheduler(
        auto: Boolean = true,
        concurrency: Int = AUTO_CONCURRENCY_INITIAL,
        rpm: Int = 0,
        clock: FakeClock = FakeClock()
    ): Pair<LlmRequestScheduler, FakeClock> {
        val rate = RequestRate(rpsLimit = concurrency, rpmLimit = rpm, clock = { clock.nowMs })
        return LlmRequestScheduler(limits(auto, concurrency, rpm), rate) to clock
    }

    @Test
    fun rateLimitHalvesConcurrencyBumpsVersionAndStartsCooldown() {
        val (scheduler, clock) = newScheduler(concurrency = 4)
        clock.nowMs = 1_000L

        val verdict = scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)

        assertEquals(SchedulerFailureVerdict.TRANSIENT, verdict)
        assertEquals(2, scheduler.currentConcurrency)
        assertEquals(1, scheduler.concurrencyVersion)
        // 无 Retry-After 时按第一级阶梯 15s 冷却
        assertEquals(16_000L, scheduler.cooldownUntilMs)
        assertEquals(1, scheduler.state.value.consecutiveFailures)
    }

    @Test
    fun retryAfterExtendsTheCooldownButNeverShortensIt() {
        val (scheduler, clock) = newScheduler(concurrency = 4)

        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT, retryAfterMs = 45_000)
        // 服务端截止时间优先于阶梯
        assertEquals(45_000L, scheduler.cooldownUntilMs)
        assertEquals(2, scheduler.currentConcurrency)

        clock.nowMs = 45_000L
        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT, retryAfterMs = 5_000)
        // 阶梯升到 30s；更短的 retryAfter 不能把冷却提前
        assertEquals(75_000L, scheduler.cooldownUntilMs)
        assertEquals(1, scheduler.currentConcurrency)
        assertEquals(2, scheduler.concurrencyVersion)
    }

    @Test
    fun staleRequestsFromBeforeTheDowngradeDoNotClimbBack() {
        val (scheduler, _) = newScheduler(concurrency = 4)
        val staleVersion = scheduler.concurrencyVersion

        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)
        // 降档前已在途的旧请求成功：如实计数，但不把额度升回去
        scheduler.onSuccess(staleVersion)
        assertEquals(2, scheduler.currentConcurrency)
        assertEquals(1, scheduler.concurrencyVersion)

        scheduler.onSuccess()
        assertEquals(3, scheduler.currentConcurrency)
    }

    @Test
    fun successesClimbTheConcurrencyUpToTheMaximum() {
        val (scheduler, _) = newScheduler(concurrency = 4)
        repeat(AUTO_CONCURRENCY_MAX - AUTO_CONCURRENCY_INITIAL + 5) { scheduler.onSuccess() }
        assertEquals(AUTO_CONCURRENCY_MAX, scheduler.currentConcurrency)
        assertEquals(
            AUTO_CONCURRENCY_MAX - AUTO_CONCURRENCY_INITIAL + 5,
            scheduler.state.value.consecutiveSuccesses
        )
    }

    @Test
    fun transientFailuresEscalateTheCooldownLadderWithoutHalving() {
        val (scheduler, clock) = newScheduler(concurrency = 4)

        scheduler.onFailure(LlmErrorCategory.SERVER_ERROR)
        assertEquals(15_000L, scheduler.cooldownUntilMs)
        assertEquals(4, scheduler.currentConcurrency)

        clock.nowMs = 15_000L
        scheduler.onFailure(LlmErrorCategory.NETWORK_UNAVAILABLE)
        assertEquals(45_000L, scheduler.cooldownUntilMs)

        clock.nowMs = 45_000L
        scheduler.onFailure(LlmErrorCategory.TIMEOUT)
        assertEquals(105_000L, scheduler.cooldownUntilMs)

        // 第三级之后保持 60s 封顶
        clock.nowMs = 105_000L
        scheduler.onFailure(LlmErrorCategory.TIMEOUT)
        assertEquals(165_000L, scheduler.cooldownUntilMs)
        assertEquals(4, scheduler.currentConcurrency)
    }

    @Test
    fun repeatedRateLimitsNeverDropBelowTheMinimum() {
        val (scheduler, clock) = newScheduler(concurrency = 4)
        repeat(10) {
            scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)
            clock.nowMs = scheduler.cooldownUntilMs
        }
        assertEquals(AUTO_CONCURRENCY_MIN, scheduler.currentConcurrency)
        assertEquals(10, scheduler.concurrencyVersion)
    }

    @Test
    fun fixedQuotaKeepsItsConcurrencyButStillCoolsDown() {
        val (scheduler, _) = newScheduler(auto = false, concurrency = 3)

        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)
        assertEquals(3, scheduler.currentConcurrency)
        // 固定额度不升版本，也不参与升降
        assertEquals(0, scheduler.concurrencyVersion)
        assertEquals(15_000L, scheduler.cooldownUntilMs)

        scheduler.onSuccess()
        assertEquals(3, scheduler.currentConcurrency)
    }

    @Test
    fun nonRetryableFailuresReturnTerminalAndKeepTheState() {
        val (scheduler, _) = newScheduler()
        val before = scheduler.state.value

        assertEquals(
            SchedulerFailureVerdict.TERMINAL,
            scheduler.onFailure(LlmErrorCategory.AUTHENTICATION)
        )
        assertEquals(
            SchedulerFailureVerdict.TERMINAL,
            scheduler.onFailure(LlmErrorCategory.CONTEXT_OVERFLOW)
        )
        assertEquals(
            SchedulerFailureVerdict.TERMINAL,
            scheduler.onFailure(LlmErrorCategory.TRUNCATED_OUTPUT)
        )
        assertEquals(before, scheduler.state.value)
    }

    @Test
    fun clientSideFailuresDoNotPunishTheProvider() {
        val (scheduler, _) = newScheduler()

        assertEquals(SchedulerFailureVerdict.TRANSIENT, scheduler.onFailure(LlmErrorCategory.PARSE_ERROR))
        assertEquals(SchedulerFailureVerdict.TRANSIENT, scheduler.onFailure(LlmErrorCategory.EMPTY_RESPONSE))
        assertEquals(
            SchedulerFailureVerdict.TRANSIENT,
            scheduler.onFailure(LlmErrorCategory.QUALITY_REJECTED)
        )
        assertEquals(0L, scheduler.cooldownUntilMs)
        assertEquals(AUTO_CONCURRENCY_INITIAL, scheduler.currentConcurrency)
        assertEquals(0, scheduler.state.value.consecutiveFailures)
    }

    @Test
    fun cancellationIsIgnoredSoItNeverPunishesTheProvider() {
        val (scheduler, _) = newScheduler()
        assertEquals(SchedulerFailureVerdict.IGNORED, scheduler.onFailure(LlmErrorCategory.CANCELLED))
        assertEquals(0L, scheduler.cooldownUntilMs)
        assertEquals(0, scheduler.state.value.consecutiveFailures)
    }

    @Test
    fun concurrencyAdjustmentsAreSyncedIntoTheRateLimit() {
        val clock = FakeClock()
        val rate = RequestRate(rpsLimit = 4, rpmLimit = 0, clock = { clock.nowMs })
        val scheduler = LlmRequestScheduler(limits(), rate)

        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)
        assertEquals(2, rate.currentRpsLimit)
        scheduler.onSuccess()
        assertEquals(3, rate.currentRpsLimit)
    }

    @Test
    fun awaitDispatchPermitWaitsOutTheCooldownThenGrants() = runTest {
        val scheduler = LlmRequestScheduler(
            limits(),
            RequestRate(rpsLimit = 4, rpmLimit = 0, clock = { currentTime })
        )
        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT, retryAfterMs = 45_000)

        val permit = async { scheduler.awaitDispatchPermit(null) }
        runCurrent()
        assertTrue(permit.isActive)

        advanceTimeBy(44_000L)
        runCurrent()
        assertTrue(permit.isActive)

        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(permit.isCompleted)
        // 放行时携带降档后的版本，供结果结算做隔离
        assertEquals(1, permit.await())
    }

    @Test
    fun signalCancelDuringCooldownThrowsBeforeAnyDispatch() = runTest {
        val scheduler = LlmRequestScheduler(
            limits(),
            RequestRate(rpsLimit = 4, rpmLimit = 0, clock = { currentTime })
        )
        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)
        val signal = TranslationControlSignal()

        val permit = async { scheduler.awaitDispatchPermit(signal) }
        runCurrent()
        assertTrue(permit.isActive)

        signal.cancel()
        advanceTimeBy(300L)
        runCurrent()
        val error = runCatching { permit.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)

        // 取消没有消耗许可：后续请求仍要等完整个冷却
        val next = async { scheduler.awaitDispatchPermit(null) }
        advanceUntilIdle()
        assertEquals(1, next.await())
        assertTrue(currentTime >= 15_000L)
    }

    @Test
    fun coroutineCancelDuringCooldownDoesNotLeakThePermit() = runTest {
        val scheduler = LlmRequestScheduler(
            limits(),
            RequestRate(rpsLimit = 4, rpmLimit = 0, clock = { currentTime })
        )
        scheduler.onFailure(LlmErrorCategory.RATE_LIMIT)

        val permit = async { scheduler.awaitDispatchPermit(null) }
        runCurrent()
        assertTrue(permit.isActive)
        permit.cancel()

        val next = async { scheduler.awaitDispatchPermit(null) }
        advanceUntilIdle()
        assertEquals(1, next.await())
        assertFalse(scheduler.state.value.concurrency == 0)
    }

    @Test
    fun rpmModePacesPermitsInVirtualTime() = runTest {
        val scheduler = LlmRequestScheduler(
            limits(concurrency = 4, rpm = 60),
            RequestRate(rpsLimit = 4, rpmLimit = 60, clock = { currentTime })
        )

        val first = async { scheduler.awaitDispatchPermit(null) }
        // 冷启动立即放行
        assertEquals(0, first.await())

        val second = async { scheduler.awaitDispatchPermit(null) }
        runCurrent()
        assertTrue(second.isActive)
        advanceTimeBy(999L)
        runCurrent()
        assertTrue(second.isActive)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(0, second.await())
    }

    @Test
    fun tokenBucketAllowsOneColdStartBurstThenPaces() = runTest {
        val scheduler = LlmRequestScheduler(
            limits(concurrency = 4),
            RequestRate(rpsLimit = 4, rpmLimit = 0, clock = { currentTime })
        )
        // 冷启动最多连发一个并发量
        repeat(4) { scheduler.awaitDispatchPermit(null) }

        val fifth = async { scheduler.awaitDispatchPermit(null) }
        runCurrent()
        assertTrue(fifth.isActive)
        advanceTimeBy(300L)
        runCurrent()
        assertTrue(fifth.isCompleted)
        assertEquals(0, fifth.await())
    }
}
