package com.breakyuna.noveltranslator.core.ondevice

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizerOptions

/** ML Kit GenAI 三个依赖均要求 Android API 26+（Manifest 已用 tools:overrideLibrary 放行 minSdk 24）。 */
internal const val ON_DEVICE_GENAI_MIN_API_LEVEL = 26

internal object OnDeviceDeviceSupport {
    const val MIN_API_LEVEL = ON_DEVICE_GENAI_MIN_API_LEVEL

    const val API_LEVEL_REASON =
        "端侧 AI 需要 Android 8.0（API 26）及以上系统，当前设备系统版本过低。"

    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API_LEVEL
}

/**
 * 端侧 GenAI 能力探测结果（纯数据，UI 可直接绑定）。
 *
 * 探测时机：官方文档要求先调用 checkStatus / checkFeatureStatus 再展示相关 UI，
 * 避免用户看到 AICore 初始化类错误。
 * https://developers.google.com/ml-kit/genai/proofreading/android（Common setup issues）
 */
data class OnDeviceGenAiStatus(
    val apiLevel: Int,
    @FeatureStatus val promptStatus: Int,
    @FeatureStatus val summarizationStatus: Int,
    @FeatureStatus val proofreadingStatus: Int,
    val baseModelName: String?,
    /** 面向用户的中文不可用原因；全部可用时为 null。 */
    val unsupportedReason: String?
) {
    val apiLevelSupported: Boolean
        get() = apiLevel >= OnDeviceDeviceSupport.MIN_API_LEVEL

    val promptAvailable: Boolean
        get() = apiLevelSupported && promptStatus == FeatureStatus.AVAILABLE

    val summarizationAvailable: Boolean
        get() = apiLevelSupported && summarizationStatus == FeatureStatus.AVAILABLE

    val proofreadingAvailable: Boolean
        get() = apiLevelSupported && proofreadingStatus == FeatureStatus.AVAILABLE

    /** 是否有任一能力已就绪（用于设置页总开关）。 */
    val anyCapabilityAvailable: Boolean
        get() = promptAvailable || summarizationAvailable || proofreadingAvailable

    /** 是否有能力正在下载。 */
    val anyDownloading: Boolean
        get() = apiLevelSupported && (
            promptStatus == FeatureStatus.DOWNLOADING ||
                summarizationStatus == FeatureStatus.DOWNLOADING ||
                proofreadingStatus == FeatureStatus.DOWNLOADING
            )

    /** 端侧摘要永远不支持中文（Summarization 仅 EN/JA/KO），调用方需回退云端。 */
    val chineseSummarizationSupported: Boolean
        get() = OnDeviceLanguageSupport.CHINESE in OnDeviceLanguageSupport.SUMMARY_LANGUAGE_CODES
}

/**
 * 探测端侧 GenAI 三项能力状态。
 *
 * 注意：
 * - 探测本身不触发下载；DOWNLOADABLE 时由具体能力在首次推理前下载。
 * - 每次探测创建临时客户端并立即 close，避免与长生命周期客户端互相干扰。
 * - AICore 刚初始化（新设备/重置）时 checkStatus 可能抛异常，按不可用处理并给出中文原因。
 */
suspend fun probeOnDeviceGenAi(context: Context): OnDeviceGenAiStatus {
    val apiLevel = Build.VERSION.SDK_INT
    if (!OnDeviceDeviceSupport.isApiSupported()) {
        return OnDeviceGenAiStatus(
            apiLevel = apiLevel,
            promptStatus = FeatureStatus.UNAVAILABLE,
            summarizationStatus = FeatureStatus.UNAVAILABLE,
            proofreadingStatus = FeatureStatus.UNAVAILABLE,
            baseModelName = null,
            unsupportedReason = OnDeviceDeviceSupport.API_LEVEL_REASON
        )
    }

    val promptClient = Generation.getClient()
    val summarizationClient = Summarization.getClient(
        SummarizerOptions.builder(context)
            .setInputType(SummarizerOptions.InputType.ARTICLE)
            .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
            .setLanguage(SummarizerOptions.Language.ENGLISH)
            .build()
    )
    val proofreadingClient = Proofreading.getClient(
        ProofreaderOptions.builder(context)
            .setInputType(ProofreaderOptions.InputType.KEYBOARD)
            .setLanguage(ProofreaderOptions.Language.ENGLISH)
            .build()
    )

    // 探测以 ENGLISH 配置为准：官方文档说明可用性取决于设备已下载的模型配置，
    // 运行时以 checkFeatureStatus() 为权威（每种语言配置可能不同）。
    val promptStatus = runCatching { promptClient.checkStatus() }
        .getOrElse { FeatureStatus.UNAVAILABLE }
    val summarizationStatus = runCatching {
        summarizationClient.checkFeatureStatus().awaitOnDevice()
    }.getOrElse { FeatureStatus.UNAVAILABLE }
    val proofreadingStatus = runCatching {
        proofreadingClient.checkFeatureStatus().awaitOnDevice()
    }.getOrElse { FeatureStatus.UNAVAILABLE }
    val baseModelName = runCatching { promptClient.baseModelName }.getOrNull()

    runCatching { promptClient.close() }
    runCatching { summarizationClient.close() }
    runCatching { proofreadingClient.close() }

    return OnDeviceGenAiStatus(
        apiLevel = apiLevel,
        promptStatus = promptStatus,
        summarizationStatus = summarizationStatus,
        proofreadingStatus = proofreadingStatus,
        baseModelName = baseModelName,
        unsupportedReason = buildUnsupportedReason(
            apiLevelSupported = true,
            promptStatus = promptStatus,
            summarizationStatus = summarizationStatus,
            proofreadingStatus = proofreadingStatus
        )
    )
}

/** 汇总面向用户的中文不可用原因。 */
internal fun buildUnsupportedReason(
    apiLevelSupported: Boolean,
    @FeatureStatus promptStatus: Int,
    @FeatureStatus summarizationStatus: Int,
    @FeatureStatus proofreadingStatus: Int
): String? {
    if (!apiLevelSupported) return OnDeviceDeviceSupport.API_LEVEL_REASON
    if (
        promptStatus == FeatureStatus.AVAILABLE &&
        summarizationStatus == FeatureStatus.AVAILABLE &&
        proofreadingStatus == FeatureStatus.AVAILABLE
    ) {
        return null
    }
    val reasons = mutableListOf<String>()
    if (promptStatus == FeatureStatus.DOWNLOADABLE || promptStatus == FeatureStatus.DOWNLOADING ||
        summarizationStatus == FeatureStatus.DOWNLOADABLE || summarizationStatus == FeatureStatus.DOWNLOADING ||
        proofreadingStatus == FeatureStatus.DOWNLOADABLE || proofreadingStatus == FeatureStatus.DOWNLOADING
    ) {
        reasons.add("端侧模型下载中，下载完成后即可使用")
    }
    if (
        promptStatus == FeatureStatus.UNAVAILABLE ||
        summarizationStatus == FeatureStatus.UNAVAILABLE ||
        proofreadingStatus == FeatureStatus.UNAVAILABLE
    ) {
        reasons.add("部分能力不可用：设备可能不在支持列表、启动器已解锁，或 AICore 尚未完成初始化")
    }
    reasons.add("端侧摘要仅支持英语/日语/韩语，校对不支持中文；中文场景将回退云端")
    return reasons.joinToString("；")
}
