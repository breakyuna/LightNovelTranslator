package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 自动并发的起步值与上下界，对标 LinguaGacha `request-rate.ts` 的
 * `AUTO_CONCURRENCY_INITIAL / MIN / MAX`。数值由产品规则固定，不由 provider 配置覆盖。
 */
const val AUTO_CONCURRENCY_INITIAL = 4
const val AUTO_CONCURRENCY_MIN = 1
const val AUTO_CONCURRENCY_MAX = 16

private const val ONE_MINUTE_MS = 60_000L
private const val ONE_SECOND_MS = 1_000L

/** 云端 provider 的启发式 RPM 天花板（每分钟 60 次 ≈ 每秒 1 次）。 */
private const val CLOUD_RPM_LIMIT = 60

/** 本地 Ollama 没有账号 RPM 配额，瓶颈在显存/吞吐，因此抬高并发起步值。 */
private const val LOCAL_CONCURRENCY_LIMIT = 8

/**
 * 一次派发前需要满足的限流额度。
 *
 * @property autoConcurrency 是否允许按请求结果自动升降并发（429 减半、成功加一）。
 * @property concurrencyLimit 并发起步值；RPM 模式下同时作为令牌桶容量。
 * @property rpmLimit 每分钟派发上限；0 表示不使用 RPM 间隔派发。
 */
data class RequestLimits(
    val autoConcurrency: Boolean,
    val concurrencyLimit: Int,
    val rpmLimit: Int
)

/**
 * 从 [ApiProviderEntity] 已携带的信息派生限流额度（model = `selectedModel`，
 * endpoint = `baseUrl`）。
 *
 * 当前 Entity 还没有 rpm/concurrency 字段，因此这里给的是启发式默认值而不是运维显式配置：
 * 云端 provider 用 RPM 60 作为安全天花板（翻译请求本身通常 10s 以上，该上限不会成为吞吐瓶颈，
 * 但能在快速失败/重试风暴时兜底），并发从 [AUTO_CONCURRENCY_INITIAL] 起步；本地 Ollama
 * 走高并发、无 RPM。两者都保留 `autoConcurrency = true`，因为启发式值是起点而非显式配额。
 *
 * 后续把这些参数落进 provider 设置（Room Entity + 设置页）时，本函数应改为读取显式字段并按
 * LinguaGacha 语义裁决：显式 concurrency/rpm 任一非零 → 固定额度（`autoConcurrency = false`）；
 * 两者都为 0 → 自动探测（`autoConcurrency = true`，额度取 [AUTO_CONCURRENCY_INITIAL]）。
 * 本期不做持久化，为避免与并行修改 data/ 的 agent 冲突。
 */
fun resolveRequestLimits(provider: ApiProviderEntity): RequestLimits =
    if (provider.providerType == ProviderType.OLLAMA_LOCAL) {
        RequestLimits(
            autoConcurrency = true,
            concurrencyLimit = LOCAL_CONCURRENCY_LIMIT,
            rpmLimit = 0
        )
    } else {
        RequestLimits(
            autoConcurrency = true,
            concurrencyLimit = AUTO_CONCURRENCY_INITIAL,
            rpmLimit = CLOUD_RPM_LIMIT
        )
    }

/**
 * 单个 provider 资源的速率时钟，对标 LinguaGacha `TranslationRequestRate`。
 *
 * 两种派发模式互斥：
 * - RPM 模式（[rpmLimit] > 0）：`permit_interval = 60000 / rpm`，每次派发把下一次资格推到
 *   `now + interval`，严格匀速；
 * - 隐藏 RPS 令牌桶（rpm = 0）：容量与补给速率都等于当前并发，冷启动可突发一个并发量的请求，
 *   之后按并发速率补给。
 *
 * 并发升降时先按旧速率结算余额再切换额度：升档不赠送令牌，降档裁剪已有余额，避免降档后
 * 残留的突发额度把已经减半的并发瞬间打满。
 *
 * 实例可被多个翻译任务共享（见 [RequestRatePool]），因此所有可变状态都用内部锁保护。
 *
 * @param clock 时间源，测试注入虚拟时钟；生产用 `System::currentTimeMillis`。
 */
class RequestRate(
    rpsLimit: Int,
    rpmLimit: Int,
    val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    /** RPM 派发间隔；0 表示改用隐藏 RPS 令牌桶。 */
    private val rpmPermitIntervalMs: Long = if (rpmLimit > 0) ONE_MINUTE_MS / rpmLimit else 0L

    private var rpsLimit: Int = max(1, rpsLimit)

    /** 隐藏 RPS 令牌桶余额，冷启动填满一个并发量。 */
    private var hiddenRpsTokens: Double = max(1, rpsLimit).toDouble()

    /** 余额已结算到的时间，调整速率前必须按旧值先结算。 */
    private var hiddenRpsRefilledAt: Long = clock()

    /** RPM 模式下下一次派发资格时刻；null 表示尚未派发过（冷启动立即放行）。 */
    private var nextRpmPermitAt: Long? = null

    /** 当前 RPS 额度（= 当前并发），只读快照。 */
    val currentRpsLimit: Int
        get() = synchronized(lock) { rpsLimit }

    /**
     * 切换到新额度。先按旧速率结算余额，再裁剪到新额度：升档不赠送令牌，降档裁剪已有余额。
     *
     * @param at 结算时刻，测试可传虚拟时间；默认取 [clock]。
     */
    fun setRpsLimit(limit: Int, at: Long = clock()) {
        synchronized(lock) {
            refillHiddenRpsTokensLocked(at)
            rpsLimit = max(1, limit)
            hiddenRpsTokens = min(hiddenRpsTokens, rpsLimit.toDouble())
        }
    }

    /**
     * 距离下一次派发资格的等待时间；查询不消耗资格。
     *
     * @param at 查询时刻，由调用方传入以便测试使用虚拟时间。
     */
    fun getDispatchPermitDelayMs(at: Long): Long = synchronized(lock) {
        if (rpmPermitIntervalMs > 0L) {
            val next = nextRpmPermitAt
            if (next == null) 0L else max(0L, next - at)
        } else {
            refillHiddenRpsTokensLocked(at)
            if (hiddenRpsTokens >= 1.0) {
                0L
            } else {
                ceil((1.0 - hiddenRpsTokens) / rpsLimit * ONE_SECOND_MS).toLong()
            }
        }
    }

    /** 消耗一次派发许可：RPM 模式推进下一次时间点，否则扣减一枚令牌。 */
    fun consumeDispatchPermit(at: Long) {
        synchronized(lock) {
            if (rpmPermitIntervalMs > 0L) {
                nextRpmPermitAt = at + rpmPermitIntervalMs
            } else {
                refillHiddenRpsTokensLocked(at)
                hiddenRpsTokens = max(0.0, hiddenRpsTokens - 1.0)
            }
        }
    }

    private fun refillHiddenRpsTokensLocked(at: Long) {
        val elapsedMs = max(0L, at - hiddenRpsRefilledAt)
        if (elapsedMs <= 0L) return
        hiddenRpsTokens = min(
            rpsLimit.toDouble(),
            hiddenRpsTokens + elapsedMs.toDouble() / ONE_SECOND_MS * rpsLimit
        )
        hiddenRpsRefilledAt = at
    }
}

/**
 * 按 provider 资源复用的速率池，对标 LinguaGacha `RequestRatePool`。
 *
 * 键由 provider id + endpoint + model + 额度归一化而成，**同一资源跨翻译任务复用同一个
 * [RequestRate] 实例**，因此 RPM 的"下一次派发时刻"和令牌桶余额在任务之间连续，不会因为
 * 新任务开始而重置节奏。键只做内存归一化，绝不含 API Key。
 *
 * endpoint 归一化去掉尾斜杠并统一小写，避免同一 provider 因 URL 书写差异分裂成两个时钟。
 */
class RequestRatePool(private val clock: () -> Long = System::currentTimeMillis) {

    private val rates = ConcurrentHashMap<String, RequestRate>()

    /**
     * 归一化键：provider id + endpoint + model + 额度。额度参与键，
     * 让显式限流配置（后续落到 provider 设置）变化时自动换用新时钟。
     */
    fun keyFor(provider: ApiProviderEntity): String {
        val limits = resolveRequestLimits(provider)
        return listOf(
            provider.id.toString(),
            normalizeEndpoint(provider.baseUrl),
            provider.selectedModel.trim(),
            limits.autoConcurrency.toString(),
            limits.concurrencyLimit.toString(),
            limits.rpmLimit.toString()
        ).joinToString("|")
    }

    /** 同一资源和容量复用时钟，配置变化才重新建立启动节奏。 */
    fun resolve(provider: ApiProviderEntity): RequestRate {
        val key = keyFor(provider)
        return rates.getOrPut(key) {
            val limits = resolveRequestLimits(provider)
            RequestRate(
                rpsLimit = limits.concurrencyLimit,
                rpmLimit = limits.rpmLimit,
                clock = clock
            )
        }
    }
}

private fun normalizeEndpoint(endpoint: String): String =
    endpoint.trim().trimEnd('/').lowercase()
