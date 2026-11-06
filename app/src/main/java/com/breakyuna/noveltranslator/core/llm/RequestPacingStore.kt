package com.breakyuna.noveltranslator.core.llm

/**
 * 用户为单个 provider 显式配置的派发额度。0 = 未配置（走启发式自动档）。
 *
 * 语义完全对标 LinguaGacha `resolve_request_limits`：任一字段非零即"固定额度"，
 * 两个都为 0 才是"自动探测"。这样用户既能一键回到自动，也不会被半配置的状态搞糊涂。
 */
data class RequestPacingOverride(
    val rpmLimit: Int = 0,
    val concurrencyLimit: Int = 0
) {
    /** 把非法值收敛回合法区间；UI 与存储层共用，避免两边各写一套钳制逻辑。 */
    fun sanitized(): RequestPacingOverride = RequestPacingOverride(
        rpmLimit = rpmLimit.coerceIn(0, MAX_PACING_RPM),
        concurrencyLimit = concurrencyLimit.coerceIn(0, MAX_PACING_CONCURRENCY)
    )

    companion object {
        /** 派发节奏上限：10 次/秒。再高对手机端网络与计费都没有意义。 */
        const val MAX_PACING_RPM = 600

        /** 并发上限，与 [AUTO_CONCURRENCY_MAX] 保持一致。 */
        const val MAX_PACING_CONCURRENCY = 16
    }
}

/**
 * [RequestPacingOverride] 的持久化接口，按 provider id 存取。
 *
 * 刻意做成接口而不是直接依赖 Room：额度是"运维旋钮"而不是 provider 身份，
 * 改它不该触发一次数据库迁移；core/llm 也因此不需要认识任何 Android 类型。
 * App 层提供 SharedPreferences 实现（见 data/prefs）。
 */
interface RequestPacingStore {
    fun get(providerId: Long): RequestPacingOverride

    fun put(providerId: Long, override: RequestPacingOverride)
}
