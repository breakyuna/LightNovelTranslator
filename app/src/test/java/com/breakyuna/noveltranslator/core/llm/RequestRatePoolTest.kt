package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRatePoolTest {

    private fun rateProvider(
        id: Long = 1L,
        type: ProviderType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl: String = "https://api.example.invalid/v1",
        model: String = "fake-model"
    ) = ApiProviderEntity(
        id = id,
        name = "fake",
        providerType = type,
        baseUrl = baseUrl,
        apiKey = "not-real",
        selectedModel = model
    )

    @Test
    fun rpmPermitIntervalPacesDispatchPrecisely() {
        // 60000 / 60 = 1000ms；冷启动立即放行，之后严格匀速。
        val rate = RequestRate(rpsLimit = 4, rpmLimit = 60)
        assertEquals(0L, rate.getDispatchPermitDelayMs(0L))
        rate.consumeDispatchPermit(0L)
        assertEquals(1_000L, rate.getDispatchPermitDelayMs(0L))
        assertEquals(600L, rate.getDispatchPermitDelayMs(400L))
        assertEquals(0L, rate.getDispatchPermitDelayMs(1_000L))
        rate.consumeDispatchPermit(1_000L)
        assertEquals(1_000L, rate.getDispatchPermitDelayMs(1_000L))

        // 60000 / 120 = 500ms
        val faster = RequestRate(rpsLimit = 4, rpmLimit = 120)
        faster.consumeDispatchPermit(0L)
        assertEquals(500L, faster.getDispatchPermitDelayMs(0L))
    }

    @Test
    fun tokenBucketRefillsOverTimeAndCapsAtCapacity() {
        // 显式传入 at，因此时钟固定为 0 即可保持确定
        val rate = RequestRate(rpsLimit = 2, rpmLimit = 0, clock = { 0L })
        assertEquals(0L, rate.getDispatchPermitDelayMs(0L))
        rate.consumeDispatchPermit(0L)
        rate.consumeDispatchPermit(0L)
        // 桶空：需要 1/2 秒补回一枚
        assertEquals(500L, rate.getDispatchPermitDelayMs(0L))
        // 500ms 按 rps=2 补给一枚，可以立即派发
        assertEquals(0L, rate.getDispatchPermitDelayMs(500L))
        // 长时间闲置也只补满到容量 2，不会溢出
        rate.consumeDispatchPermit(500L)
        assertEquals(0L, rate.getDispatchPermitDelayMs(10_000L))
        rate.consumeDispatchPermit(10_000L)
        rate.consumeDispatchPermit(10_000L)
        assertEquals(500L, rate.getDispatchPermitDelayMs(10_000L))
    }

    @Test
    fun loweringTheLimitSettlesBeforeClippingTheBalance() {
        val rate = RequestRate(rpsLimit = 2, rpmLimit = 0, clock = { 0L })
        rate.consumeDispatchPermit(0L)
        rate.consumeDispatchPermit(0L)
        // 降档到 1：先按旧速率结算（elapsed=0，余额 0），再裁剪到 min(0, 1)
        rate.setRpsLimit(1, at = 0L)
        assertEquals(1_000L, rate.getDispatchPermitDelayMs(0L))
    }

    @Test
    fun raisingTheLimitDoesNotGiftTokens() {
        val rate = RequestRate(rpsLimit = 1, rpmLimit = 0, clock = { 0L })
        rate.consumeDispatchPermit(0L)
        // 升档不赠送令牌：余额仍是 0，只是补给变快（1/4 秒一枚）
        rate.setRpsLimit(4, at = 0L)
        assertEquals(250L, rate.getDispatchPermitDelayMs(0L))
    }

    @Test
    fun poolReusesOneRateClockPerNormalizedKey() {
        val pool = RequestRatePool()
        val first = pool.resolve(rateProvider())
        // 同一 provider 复用同一时钟
        assertSame(first, pool.resolve(rateProvider()))
        // 尾斜杠与大小写不影响归一化
        assertSame(first, pool.resolve(rateProvider(baseUrl = "https://API.example.invalid/V1/")))
        // 换模型 / 换 provider 才换时钟
        assertNotSame(first, pool.resolve(rateProvider(model = "other-model")))
        assertNotSame(first, pool.resolve(rateProvider(id = 2L)))
    }

    @Test
    fun poolKeepsDispatchPaceAcrossResolves() {
        val pool = RequestRatePool()
        val provider = rateProvider()
        // 模拟上一个任务消耗了一次 RPM 资格
        pool.resolve(provider).consumeDispatchPermit(0L)
        // 新任务解析到同一时钟：仍要等满间隔，不会重置节奏
        assertEquals(1_000L, pool.resolve(provider).getDispatchPermitDelayMs(0L))
    }

    @Test
    fun heuristicLimitsDistinguishCloudAndLocal() {
        val cloud = resolveRequestLimits(rateProvider())
        assertTrue(cloud.autoConcurrency)
        assertEquals(AUTO_CONCURRENCY_INITIAL, cloud.concurrencyLimit)
        assertEquals(60, cloud.rpmLimit)

        val local = resolveRequestLimits(rateProvider(type = ProviderType.OLLAMA_LOCAL))
        assertTrue(local.autoConcurrency)
        assertEquals(8, local.concurrencyLimit)
        assertEquals(0, local.rpmLimit)
    }

    @Test
    fun localProviderUsesTheTokenBucketInsteadOfRpm() {
        val pool = RequestRatePool()
        val rate = pool.resolve(rateProvider(type = ProviderType.OLLAMA_LOCAL))
        // 并发 8 → 令牌桶容量 8，冷启动可连发 8 个
        repeat(8) { rate.consumeDispatchPermit(0L) }
        assertEquals(125L, rate.getDispatchPermitDelayMs(0L))
    }
}
