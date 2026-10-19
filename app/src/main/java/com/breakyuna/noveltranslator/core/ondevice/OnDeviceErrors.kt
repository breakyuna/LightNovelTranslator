package com.breakyuna.noveltranslator.core.ondevice

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * 端侧 GenAI（ML Kit / AICore）统一错误分类。
 *
 * 覆盖官方文档列出的硬限制：
 * - 仅前台可用：后台（含前台服务）返回 [GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED]。
 *   来源：https://developers.google.com/ml-kit/genai
 * - AICore 每 app 推理配额 + 电池配额：BUSY / PER_APP_BATTERY_USE_QUOTA_EXCEEDED，需指数退避。
 * - 设备不支持（不在白名单 / 解锁 bootloader / AICore 未就绪）：NOT_AVAILABLE 等。
 */
enum class OnDeviceAssistError {
    /** 设备或系统层面不可用（API < 26、不在设备白名单、解锁 bootloader、AICore 不兼容、需系统更新）。 */
    UNSUPPORTED_DEVICE,

    /** 特性存在但当前不可用（AICore 尚未拉到配置、模型未下载且不可下载）。 */
    UNAVAILABLE,

    /** 模型正在下载中；下载完成后推理会自动继续。 */
    DOWNLOADING,

    /** AICore 每 app 配额 / 电池配额超限，需要退避后重试。 */
    QUOTA_BUSY,

    /** 应用处于后台（含前台服务），AICore 拒绝推理；必须等用户回到前台。 */
    BACKGROUND_BLOCKED,

    /** 其他失败（请求过大/过小、生成错误、未知异常等）。 */
    FAILED;

    /** 是否值得按指数退避重试。认证类/不可用类错误不重试。 */
    val isRetryable: Boolean
        get() = this == QUOTA_BUSY || this == DOWNLOADING
}

/**
 * 将 [GenAiException.errorCode] 映射为统一错误分类。
 *
 * 错误码常量来自 genai-common 1.0.0-beta4（已用 javap 核对实际产物）：
 * UNKNOWN=0, REQUEST_PROCESSING_ERROR=4, CANCELLED=7, NOT_AVAILABLE=8, BUSY=9,
 * RESPONSE_PROCESSING_ERROR=11, REQUEST_TOO_LARGE=12, REQUEST_TOO_SMALL=-100,
 * RESPONSE_GENERATION_ERROR=15, NOT_SUPPORTED=16, PER_APP_BATTERY_USE_QUOTA_EXCEEDED=27,
 * BACKGROUND_USE_BLOCKED=30, NOT_ENOUGH_DISK_SPACE=501, NEEDS_SYSTEM_UPDATE=604,
 * AICORE_INCOMPATIBLE=-101。
 */
fun classifyGenAiError(errorCode: Int): OnDeviceAssistError = when (errorCode) {
    GenAiException.ErrorCode.BUSY,
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> OnDeviceAssistError.QUOTA_BUSY
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> OnDeviceAssistError.BACKGROUND_BLOCKED
    GenAiException.ErrorCode.NOT_AVAILABLE,
    GenAiException.ErrorCode.NOT_SUPPORTED,
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> OnDeviceAssistError.UNSUPPORTED_DEVICE
    else -> OnDeviceAssistError.FAILED
}

/** 任意异常 → 统一错误分类；非 [GenAiException] 一律按 FAILED 处理（不盲目重试）。 */
fun classifyOnDeviceFailure(t: Throwable): OnDeviceAssistError = when (t) {
    is GenAiException -> classifyGenAiError(t.errorCode)
    else -> OnDeviceAssistError.FAILED
}

/**
 * 将 [FeatureStatus] 映射为错误分类；AVAILABLE 返回 null。
 *
 * DOWNLOADABLE / DOWNLOADING 统一视作 DOWNLOADING：官方文档说明未下载时首次推理会自动触发下载，
 * 因此调用方可以继续发起推理（见 summarization / proofreading 文档的 "Common setup issues"）。
 */
fun featureStatusToError(@FeatureStatus status: Int): OnDeviceAssistError? = when (status) {
    FeatureStatus.AVAILABLE -> null
    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> OnDeviceAssistError.DOWNLOADING
    else -> OnDeviceAssistError.UNAVAILABLE
}

/** 指数退避：1s / 2s / 4s / 8s 封顶。attempt 从 1 开始计第几次重试。 */
object OnDeviceBackoff {
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 8_000L

    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) { delay = min(delay * 2, MAX_DELAY_MS) }
        return delay
    }
}

/**
 * 带统一错误分类与指数退避的重试封装。
 *
 * - 仅对 [OnDeviceAssistError.isRetryable]（QUOTA_BUSY / DOWNLOADING）重试；
 * - 协程取消（[CancellationException]）立即上抛，绝不重试、绝不再发起请求；
 * - 非瞬时错误（UNSUPPORTED_DEVICE / UNAVAILABLE / BACKGROUND_BLOCKED / FAILED）立即上抛。
 */
suspend fun <T> withOnDeviceRetry(
    maxAttempts: Int = 4,
    onError: (OnDeviceAssistError, Throwable) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            val error = classifyOnDeviceFailure(t)
            onError(error, t)
            if (!error.isRetryable || attempt >= maxAttempts) throw t
            delay(OnDeviceBackoff.delayForAttempt(attempt))
            attempt++
        }
    }
}

/**
 * 端侧辅助调用包装：失败返回 null（调用方回退云端），协程取消原样上抛。
 * 供门面的 Prompt 轻任务使用；推理类入口走各自的 Result 密封接口，不吞错误。
 */
suspend fun <T> runOnDeviceAssistCatching(block: suspend () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (t: Throwable) {
    null
}
