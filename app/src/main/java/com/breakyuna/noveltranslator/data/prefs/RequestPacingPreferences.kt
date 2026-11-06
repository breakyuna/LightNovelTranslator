package com.breakyuna.noveltranslator.data.prefs

import android.content.SharedPreferences
import com.breakyuna.noveltranslator.core.llm.RequestPacingOverride
import com.breakyuna.noveltranslator.core.llm.RequestPacingStore

/**
 * SharedPreferences 实现的 [RequestPacingStore]，与 App 其他开关同库（`novel_translator_prefs`）。
 *
 * 为什么不在 Room：额度是运维旋钮而不是 provider 身份，改它不该触发一次数据库迁移；
 * 而且 core/llm 只依赖 [RequestPacingStore] 接口，不需要认识 Android 类型。
 * 存储的是"每 provider 覆盖值"，0 表示未配置（走启发式自动档），删除 provider 时一并清掉。
 */
class RequestPacingPreferences(private val prefs: SharedPreferences) : RequestPacingStore {

    override fun get(providerId: Long): RequestPacingOverride = RequestPacingOverride(
        rpmLimit = prefs.getInt(keyRpm(providerId), 0),
        concurrencyLimit = prefs.getInt(keyConcurrency(providerId), 0)
    ).sanitized()

    override fun put(providerId: Long, override: RequestPacingOverride) {
        val sanitized = override.sanitized()
        prefs.edit()
            .putInt(keyRpm(providerId), sanitized.rpmLimit)
            .putInt(keyConcurrency(providerId), sanitized.concurrencyLimit)
            .apply()
    }

    /** 删除 provider 时调用：覆盖值失去依附，避免留下无人读取的孤儿键。 */
    fun clear(providerId: Long) {
        prefs.edit()
            .remove(keyRpm(providerId))
            .remove(keyConcurrency(providerId))
            .apply()
    }

    private fun keyRpm(providerId: Long) = "pacing_${providerId}_rpm"

    private fun keyConcurrency(providerId: Long) = "pacing_${providerId}_concurrency"
}
