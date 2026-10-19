package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * 单 Key 的冷却阶梯，对标 LinguaGacha `KEY_COOLDOWN_STEPS_MS`：连续故障轮次决定退避时长，
 * 同一波并发失败按轮次只计一次。LNT 单 Key 场景为主，故只有一级阶梯。
 */
private val KEY_COOLDOWN_STEPS_MS = longArrayOf(15_000L, 30_000L, 60_000L)

/** 冷却/速率等待的轮询切片，与 [RetryingLlmGateway] 的暂停轮询保持一致。 */
private const val PERMIT_POLL_SLICE_MS = 250L

/**
 * [LlmRequestScheduler.onFailure] 的裁决，供上层感知失败性质。
 *
 * 注意：是否重试始终由既有 [RetryPolicy] 依据 [LlmResult] 决定，本枚举不替代它，
 * 只是让"限流状态被惩罚了 / 原样放过 / 终止"对调用方可见。
 */
enum class SchedulerFailureVerdict {
    /** 瞬时失败：已降温或保持原状，调用方按 RetryPolicy 继续。 */
    TRANSIENT,

    /** 不可重试错误：调度器不改变限流状态，由 RetryPolicy 终止重试。 */
    TERMINAL,

    /** 取消：不惩罚 provider，调度器无任何状态变化。 */
    IGNORED
}

/** 调度器可观测快照，供 UI / 调试读取当前并发与冷却截止时刻。 */
data class LlmSchedulerState(
    val concurrency: Int,
    val concurrencyVersion: Int,
    val cooldownUntilMs: Long,
    val consecutiveSuccesses: Int,
    val consecutiveFailures: Int
)

/**
 * 请求级限流调度器，对标 LinguaGacha `TranslationRequestScheduler` 的额度与冷却部分。
 *
 * 职责边界（与 [TranslationControlSignal] 互不重叠）：
 * - 本调度器只回答"**何时可以发**"：429 冷却、RPM/RPS 派发节奏、并发升降档；
 * - [TranslationControlSignal] 只回答"**该不该现在发**"：暂停 / 取消边界与在途计数，
 *   由 [RetryingLlmGateway] 在调用本调度器之前先行处理。
 *
 * 状态机（单 Key）：
 * - `onSuccess`：连续成功 +1、失败轮次清零；自动模式下并发 +1（封顶 [AUTO_CONCURRENCY_MAX]）；
 * - `onFailure(RATE_LIMIT)`：并发减半（不低于 [AUTO_CONCURRENCY_MIN]）、`concurrencyVersion++`
 *   （隔离降档前已在途的旧请求，它们的成功/失败不再参与升降）、冷却
 *   `max(阶梯, retryAfterMs)`；服务端 `Retry-After` 只能延长不能缩短冷却；
 * - `onFailure(NETWORK/TIMEOUT/SERVER_ERROR)`：只按阶梯冷却，不降并发；
 * - `onFailure(其余可重试类)`：不动限流状态（客户端侧归一/解析问题不惩罚 provider 配额）；
 * - `onFailure(不可重试类 / CANCELLED)`：见 [SchedulerFailureVerdict]。
 *
 * 多 Key 扩展点：LinguaGacha 为每个 API Key 维护 `ready / draining / cooling / probing`
 * 四态与轮换偏移，冷却按 Key 独立计时。LNT 单 Key 场景下等价于"整个 provider 端点一把
 * 钥匙"，因此冷却是端点级的。后续若要支持多 Key 轮换：在此处引入按 Key 分解的
 * `availableAt` 与轮换偏移，[awaitDispatchPermit] 在放行前选一把可用 Key 即可；
 * Key 明文必须留在 [LlmClient] 边界内，调度器只持有冷却状态，不持有密钥。
 *
 * @param limits 限流额度快照（是否自动升降、并发起步值、RPM）。
 * @param rate 速率时钟；默认按 [limits] 新建，生产环境经 [LlmRequestSchedulerFactory]
 *   传入跨任务复用的实例。其时钟同时作为本类的冷却时钟，两者必须一致。
 */
class LlmRequestScheduler(
    private val limits: RequestLimits,
    private val rate: RequestRate = RequestRate(
        rpsLimit = limits.concurrencyLimit,
        rpmLimit = limits.rpmLimit
    )
) {
    init {
        require(limits.concurrencyLimit >= AUTO_CONCURRENCY_MIN) {
            "concurrencyLimit must be >= $AUTO_CONCURRENCY_MIN"
        }
    }

    private val lock = Any()

    private val _state = MutableStateFlow(
        LlmSchedulerState(
            concurrency = limits.concurrencyLimit,
            concurrencyVersion = 0,
            cooldownUntilMs = 0L,
            consecutiveSuccesses = 0,
            consecutiveFailures = 0
        )
    )

    /** 可观测快照；并发、版本、冷却截止时刻都从这里读取。 */
    val state: StateFlow<LlmSchedulerState> = _state.asStateFlow()

    /** 当前并发额度，引擎可据此 sizing 并行 worker 数。 */
    val currentConcurrency: Int get() = _state.value.concurrency

    /** 并发版本，每次有效 429 递增，用于隔离降档前已在途的旧请求。 */
    val concurrencyVersion: Int get() = _state.value.concurrencyVersion

    /** 冷却截止时刻（时钟同 [RequestRate.clock]）；0 表示未在冷却。 */
    val cooldownUntilMs: Long get() = _state.value.cooldownUntilMs

    /**
     * 挂起直到允许发起一次物理请求，返回放行时刻的并发版本
     * （调用方须把它回传给 [onSuccess] / [onFailure] 做版本隔离）。
     *
     * 冷却中则挂起到冷却结束；RPM/RPS 未到派发时刻则按差额挂起。两者都按
     * [PERMIT_POLL_SLICE_MS] 切片等待，因此 [TranslationControlSignal] 的取消能及时
     * 打断等待——取消时抛出 [CancellationException]，不会放行、更不会发起请求。
     */
    suspend fun awaitDispatchPermit(signal: TranslationControlSignal? = null): Int {
        while (true) {
            throwIfCancelled(signal)
            val cooldownRemaining = synchronized(lock) {
                _state.value.cooldownUntilMs - rate.clock()
            }
            if (cooldownRemaining > 0L) {
                waitOut(cooldownRemaining, signal)
                continue
            }
            var grantedVersion = -1
            var rateDelayMs = 0L
            // 检查与消耗在同一临界区内完成，避免并发 worker 同时读到"可放行"而超发。
            synchronized(lock) {
                val at = rate.clock()
                val delayMs = rate.getDispatchPermitDelayMs(at)
                if (delayMs <= 0L) {
                    rate.consumeDispatchPermit(at)
                    grantedVersion = _state.value.concurrencyVersion
                } else {
                    rateDelayMs = delayMs
                }
            }
            if (grantedVersion >= 0) return grantedVersion
            waitOut(rateDelayMs, signal)
        }
    }

    /**
     * 一次物理请求成功：自动模式下并发 +1（封顶 [AUTO_CONCURRENCY_MAX]），
     * 失败轮次清零。版本过期（期间发生过 429 降档）的旧请求仍然如实计入成功
     * （provider 确实应答了，限流阶梯应当重置），但不把额度升回去。
     *
     * @param dispatchVersion [awaitDispatchPermit] 返回的版本。
     */
    fun onSuccess(dispatchVersion: Int = _state.value.concurrencyVersion) {
        synchronized(lock) {
            val current = _state.value
            val concurrency = if (limits.autoConcurrency && dispatchVersion == current.concurrencyVersion) {
                min(AUTO_CONCURRENCY_MAX, current.concurrency + 1).also { rate.setRpsLimit(it) }
            } else {
                current.concurrency
            }
            _state.value = LlmSchedulerState(
                concurrency = concurrency,
                concurrencyVersion = current.concurrencyVersion,
                cooldownUntilMs = current.cooldownUntilMs,
                consecutiveSuccesses = current.consecutiveSuccesses + 1,
                consecutiveFailures = 0
            )
        }
    }

    /**
     * 一次物理请求失败。返回 [SchedulerFailureVerdict] 供上层感知；
     * 是否重试仍由 [RetryPolicy] 决定。
     *
     * @param category 失败分类（[LlmResult.errorCategory]）。
     * @param retryAfterMs 服务端 `Retry-After`；只能延长不能缩短冷却。
     * @param dispatchVersion [awaitDispatchPermit] 返回的版本；过期则不参与并发升降，
     *   但 429 冷却仍然生效（限流是事实，与版本无关）。
     */
    fun onFailure(
        category: LlmErrorCategory,
        retryAfterMs: Long? = null,
        dispatchVersion: Int = _state.value.concurrencyVersion
    ): SchedulerFailureVerdict {
        if (category == LlmErrorCategory.CANCELLED) return SchedulerFailureVerdict.IGNORED
        val coolsDown = when (category) {
            LlmErrorCategory.RATE_LIMIT,
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            LlmErrorCategory.TIMEOUT,
            LlmErrorCategory.SERVER_ERROR -> true
            // 空响应 / 解析失败 / QA 拒绝是客户端侧归一或提示词问题，不惩罚 provider 配额。
            LlmErrorCategory.EMPTY_RESPONSE,
            LlmErrorCategory.PARSE_ERROR,
            LlmErrorCategory.QUALITY_REJECTED -> false
            else -> return SchedulerFailureVerdict.TERMINAL
        }
        if (!coolsDown) return SchedulerFailureVerdict.TRANSIENT
        synchronized(lock) {
            val current = _state.value
            val at = rate.clock()
            val failures = current.consecutiveFailures + 1
            val stepMs = KEY_COOLDOWN_STEPS_MS[min(failures, KEY_COOLDOWN_STEPS_MS.size) - 1]
            // 429 的服务端截止时间只能延长等待；其余瞬时错误只按阶梯退避。
            val waitMs = if (category == LlmErrorCategory.RATE_LIMIT) {
                max(stepMs, retryAfterMs ?: 0L)
            } else {
                stepMs
            }
            var concurrency = current.concurrency
            var version = current.concurrencyVersion
            if (
                category == LlmErrorCategory.RATE_LIMIT &&
                limits.autoConcurrency &&
                dispatchVersion == current.concurrencyVersion
            ) {
                concurrency = max(AUTO_CONCURRENCY_MIN, concurrency / 2)
                rate.setRpsLimit(concurrency)
                // 升版本隔离降档前已在途的旧请求：它们的结算不再把额度升回去。
                version += 1
            }
            _state.value = LlmSchedulerState(
                concurrency = concurrency,
                concurrencyVersion = version,
                cooldownUntilMs = max(current.cooldownUntilMs, at + waitMs),
                consecutiveSuccesses = 0,
                consecutiveFailures = failures
            )
        }
        return SchedulerFailureVerdict.TRANSIENT
    }

    private suspend fun waitOut(totalMs: Long, signal: TranslationControlSignal?) {
        var remaining = totalMs
        while (remaining > 0L) {
            throwIfCancelled(signal)
            val slice = min(PERMIT_POLL_SLICE_MS, remaining)
            delay(slice)
            remaining -= slice
        }
    }

    private fun throwIfCancelled(signal: TranslationControlSignal?) {
        if (signal?.isCancelled == true) {
            throw CancellationException("Translation cancelled")
        }
    }
}

/**
 * 按 provider 复用 [LlmRequestScheduler] 的工厂。
 *
 * 键与 [RequestRatePool.keyFor] 一致，因此换 provider / 改 endpoint / 改模型都会得到
 * 新的调度器与新的速率时钟；同一 provider 的多次翻译任务复用同一调度器，
 * 让并发升降与冷却状态跨任务保留（用户可见的收益：连续跑第二本书时不会被重置回起步额度）。
 */
class LlmRequestSchedulerFactory(
    private val ratePool: RequestRatePool = RequestRatePool()
) {
    private val schedulers = ConcurrentHashMap<String, LlmRequestScheduler>()

    fun schedulerFor(provider: ApiProviderEntity): LlmRequestScheduler =
        schedulers.getOrPut(ratePool.keyFor(provider)) {
            LlmRequestScheduler(
                limits = resolveRequestLimits(provider),
                rate = ratePool.resolve(provider)
            )
        }
}
