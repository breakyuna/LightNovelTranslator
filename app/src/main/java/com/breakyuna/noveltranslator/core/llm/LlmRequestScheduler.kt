package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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
 * 等待一次恢复探测结论的上限。故意远大于任何一次真实的 provider 调用：
 * 它是"探测协程异常死亡"的保险，不是正常路径的节奏。超时后按普通派发放行（fail-open），
 * 绝不让一个幽灵探测把其他工人永久 park 住。
 */
private const val PROBE_VERDICT_TIMEOUT_MS = 300_000L

/**
 * 终端故障（认证/无效请求等，重试必然同样失败）的 fail-fast 窗口。
 *
 * 窗口存在的唯一目的是刹住"同一波并发请求排队重复撞同一个错误"：没有它，5 个并行工人会各自
 * 花一整轮 HTTP 往返去发现同一件事。窗口很短，且任何一次成功都会立即清除，因此用户改好 Key
 * 后最多只被误伤十几秒；引擎每次启动翻译前也会显式清除（[LlmRequestScheduler.resetTerminalFailFast]）。
 */
private const val TERMINAL_FAIL_FAST_MS = 15_000L

/**
 * [LlmRequestScheduler.awaitDispatchPermit] 在终端故障窗口内抛出，用于 fail-fast。
 *
 * 携带原始错误类别，让上层报出真实原因（"认证失败"）而不是一个笼统的未知错误。
 * 注意**不能**继承 [CancellationException]：那是协程取消信号，会被取消处理逻辑原样吞掉。
 */
class SchedulerTerminalException(val category: LlmErrorCategory, message: String) : Exception(message)

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

    /**
     * 恢复探测的结论位：非空表示"有一个探测请求在飞"，其他请求必须等它出结果。
     * 只允许在 [lock] 内读写；由第一个结算的 attempt（无论成败）完成并置空。
     */
    private var probeVerdict: CompletableDeferred<Unit>? = null

    /** 终端故障 fail-fast 的截止时刻；0 表示未启用。只允许在 [lock] 内读写。 */
    private var terminalFailFastUntilMs = 0L

    /** 触发 fail-fast 的终端类别，供 [throwIfTerminal] 报出真实原因。 */
    private var terminalCategory: LlmErrorCategory = LlmErrorCategory.UNKNOWN

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
     *
     * 冷却刚刚结束时会进入 **probing（探测）**：只有第一个请求被放行，用来试探 provider
     * 是否已经消气；其余请求挂在 [awaitProbeVerdict] 上等它的结论。探测成功则冷却被清除、
     * 全员立即放行；探测失败则冷却按阶梯重新武装、再等下一轮。这是 LinguaGacha
     * `ready/draining/cooling/probing` 四态在单 Key 下的等价物——没有它，冷却到点的那一刻
     * 所有工人会一起冲出去，把刚刚罚过我们的 provider 再撞一次。
     */
    suspend fun awaitDispatchPermit(signal: TranslationControlSignal? = null): Int {
        while (true) {
            throwIfCancelled(signal)
            throwIfTerminal()
            // 别人正在探测恢复：等结论，别去叠第二枪。
            awaitProbeVerdict()
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
                // 刚刚被罚过、且冷却已结束：这一个请求就是恢复探测。
                if (grantedVersion >= 0 && _state.value.consecutiveFailures > 0 && probeVerdict == null) {
                    probeVerdict = CompletableDeferred()
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
                // provider 应答了，冷却就是过期的。但只认当前世代的应答：否则一个 429 之前
                // 就在途的成功，会把新一轮 429 刚刚武装起来的惩罚擦掉。
                cooldownUntilMs = if (dispatchVersion == current.concurrencyVersion) 0L else current.cooldownUntilMs,
                consecutiveSuccesses = current.consecutiveSuccesses + 1,
                consecutiveFailures = 0
            )
            settleProbeLocked()
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
        if (category == LlmErrorCategory.CANCELLED) {
            // 用户取消：绝不惩罚 provider，也不能让其他工人挂在永远不会结算的探测上。
            synchronized(lock) { settleProbeLocked() }
            return SchedulerFailureVerdict.IGNORED
        }
        val coolsDown = when (category) {
            LlmErrorCategory.RATE_LIMIT,
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            LlmErrorCategory.TIMEOUT,
            LlmErrorCategory.SERVER_ERROR -> true
            // 空响应 / 解析失败 / QA 拒绝是客户端侧归一或提示词问题，不惩罚 provider 配额。
            LlmErrorCategory.EMPTY_RESPONSE,
            LlmErrorCategory.PARSE_ERROR,
            LlmErrorCategory.QUALITY_REJECTED -> false
            else -> {
                // 终端错误：此后每个请求都会以同样的原因失败。开一个很短的 fail-fast 窗口，
                // 让已经在排队的工人立刻失败，而不是各自再花一轮 HTTP 去发现同一件事。
                synchronized(lock) {
                    settleProbeLocked()
                    terminalCategory = category
                    terminalFailFastUntilMs = max(terminalFailFastUntilMs, rate.clock() + TERMINAL_FAIL_FAST_MS)
                }
                return SchedulerFailureVerdict.TERMINAL
            }
        }
        if (!coolsDown) {
            synchronized(lock) { settleProbeLocked() }
            return SchedulerFailureVerdict.TRANSIENT
        }
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
            settleProbeLocked()
        }
        return SchedulerFailureVerdict.TRANSIENT
    }

    /**
     * 释放一个"已放行但调用没有真正发起"的许可（暂停/取消门拒绝了它）。
     *
     * 不调用这个接口的话，为它创建的恢复探测永远不会有人结算，其他工人会一直挂在
     * [awaitProbeVerdict] 上直到超时。失败结论按 fail-open 处理：等待者重新循环，
     * 该派发派发、该继续冷却继续冷却。
     */
    fun abandonDispatchPermit() {
        synchronized(lock) { settleProbeLocked() }
    }

    /** 清除终端故障 fail-fast 窗口。每次翻译 run 启动时调用，避免误伤改好 Key 后的新一轮。 */
    fun resetTerminalFailFast() {
        synchronized(lock) {
            terminalFailFastUntilMs = 0L
            terminalCategory = LlmErrorCategory.UNKNOWN
        }
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

    /**
     * 等在途恢复探测的结论。超时（探测协程异常死亡）按 fail-open 放行，并清掉这个幽灵探测，
     * 绝不让后续请求被一个永远不来的结论永久 park。
     */
    private suspend fun awaitProbeVerdict() {
        val pending = synchronized(lock) { probeVerdict } ?: return
        withTimeoutOrNull(PROBE_VERDICT_TIMEOUT_MS) { pending.await() }
            ?: synchronized(lock) { if (probeVerdict === pending) probeVerdict = null }
    }

    /** 完成并清空探测结论位。调用方必须持有 [lock]。 */
    private fun settleProbeLocked() {
        probeVerdict?.complete(Unit)
        probeVerdict = null
    }

    private fun throwIfCancelled(signal: TranslationControlSignal?) {
        if (signal?.isCancelled == true) {
            throw CancellationException("Translation cancelled")
        }
    }

    /** 终端故障窗口内直接失败，省掉一轮必然同样失败的 HTTP 往返。 */
    private fun throwIfTerminal() {
        synchronized(lock) {
            if (terminalFailFastUntilMs > rate.clock()) {
                throw SchedulerTerminalException(
                    category = terminalCategory,
                    message = "Provider 刚刚返回了不可重试的错误（$terminalCategory），已在 ${TERMINAL_FAIL_FAST_MS / 1000} 秒内快速失败；请检查 Provider 配置后重试"
                )
            }
        }
    }
}

/**
 * 按 provider 复用 [LlmRequestScheduler] 的工厂。
 *
 * 键与 [RequestRatePool.keyFor] 一致，因此换 provider / 改 endpoint / 改模型都会得到
 * 新的调度器与新的速率时钟；同一 provider 的多次翻译任务复用同一调度器，
 * 让并发升降与冷却状态跨任务保留（用户可见的收益：连续跑第二本书时不会被重置回起步额度）。
 *
 * @param pacing 用户显式额度来源；null 表示全部走启发式自动档。
 */
class LlmRequestSchedulerFactory(
    private val ratePool: RequestRatePool = RequestRatePool(),
    private val pacing: RequestPacingStore? = null
) {
    private val schedulers = ConcurrentHashMap<String, LlmRequestScheduler>()

    fun schedulerFor(provider: ApiProviderEntity): LlmRequestScheduler =
        schedulers.getOrPut(ratePool.keyFor(provider)) {
            LlmRequestScheduler(
                limits = resolveRequestLimits(
                    provider,
                    pacing?.get(provider.id) ?: RequestPacingOverride()
                ),
                rate = ratePool.resolve(provider)
            )
        }
}
