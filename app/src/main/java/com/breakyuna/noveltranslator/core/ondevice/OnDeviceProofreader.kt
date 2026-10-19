package com.breakyuna.noveltranslator.core.ondevice

import android.content.Context
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一处校对变更。注意：beta 版 ProofreadingSuggestion 只给修正后文本，无偏移信息。 */
data class ProofreadChange(
    val originalText: String,
    val suggestedText: String
)

/** 译文校对结果。用户在前台主动触发；中文目标语言不可用。 */
sealed interface OnDeviceProofreadResult {
    data class Success(
        val correctedText: String,
        val changes: List<ProofreadChange>,
        val chunkCount: Int
    ) : OnDeviceProofreadResult

    data class Unsupported(
        val reason: String,
        val fallbackSuggestion: String
    ) : OnDeviceProofreadResult

    data class Failure(
        val error: OnDeviceAssistError,
        val cause: Throwable?
    ) : OnDeviceProofreadResult
}

/**
 * 端侧译文校对（ML Kit GenAI Proofreading，Gemini Nano / AICore）。
 *
 * API 依据（2026-10-16 抓取官方文档 + javap 核对产物签名）：
 * - ProofreaderOptions.builder(context).setInputType(KEYBOARD|VOICE).setLanguage(...).build()；
 *   Proofreading.getClient(options)；checkFeatureStatus() / downloadFeature(DownloadCallback)；
 *   ProofreadingRequest.builder(text).build()；runInference(request) → ProofreadingResult.results
 *   （多条建议按置信度降序，取第一条为最佳）；runInference(request, StreamingCallback) 流式；
 *   close() 释放。
 *   https://developers.google.com/ml-kit/genai/proofreading/android
 * - 语言仅 EN/JA/DE/FR/IT/ES/KO；输入应 < 256 tokens（远低于摘要的 4000）。
 * - 仅前台可用（后台返回 BACKGROUND_USE_BLOCKED）；不支持解锁 bootloader 的设备。
 */
class OnDeviceProofreader(private val appContext: Context) {

    private val mutex = Mutex()
    private var proofreader: Proofreader? = null
    private var cachedLanguage: OnDeviceProofreadLanguage? = null

    /**
     * 校对文本。
     *
     * @param text 待校对译文
     * @param language 译文语言代码（EN/JA/DE/FR/IT/ES/KO 可用；"zh" 返回 Unsupported）
     * @param maxInputTokens 单次推理输入上限，默认 [OnDeviceTokenBudget.PROOFREAD_SAFE_TOKENS]
     * @param context 调用方 CoroutineContext，用于协作取消
     */
    suspend fun proofread(
        text: String,
        language: String,
        maxInputTokens: Int = OnDeviceTokenBudget.PROOFREAD_SAFE_TOKENS,
        context: CoroutineContext = EmptyCoroutineContext
    ): OnDeviceProofreadResult {
        context.ensureActive()
        if (text.isBlank()) {
            return OnDeviceProofreadResult.Unsupported(
                reason = "待校对文本为空。",
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_PROOFREAD_FALLBACK_ADVICE
            )
        }
        val proofreadLanguage = OnDeviceLanguageSupport.proofreadLanguage(language)
            ?: return OnDeviceProofreadResult.Unsupported(
                reason = if (OnDeviceLanguageSupport.isChinese(language)) {
                    "端侧校对暂不支持中文。"
                } else {
                    "端侧校对不支持语言「$language」。"
                },
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_PROOFREAD_FALLBACK_ADVICE
            )
        if (!OnDeviceDeviceSupport.isApiSupported()) {
            return OnDeviceProofreadResult.Failure(OnDeviceAssistError.UNSUPPORTED_DEVICE, null)
        }

        val chunks = OnDeviceTokenBudget.splitForTokenLimit(text, maxInputTokens)
        if (chunks.isEmpty()) {
            return OnDeviceProofreadResult.Unsupported(
                reason = "待校对文本为空。",
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_PROOFREAD_FALLBACK_ADVICE
            )
        }
        return try {
            val client = clientFor(proofreadLanguage)
            val status = ensureFeatureReady(
                checkStatus = { client.checkFeatureStatus().awaitOnDevice() },
                startDownload = client::downloadFeature
            )
            featureStatusToError(status)?.let { statusError ->
                if (statusError != OnDeviceAssistError.DOWNLOADING) {
                    return OnDeviceProofreadResult.Failure(statusError, null)
                }
            }
            val changes = mutableListOf<ProofreadChange>()
            val corrected = StringBuilder()
            chunks.forEachIndexed { index, chunk ->
                context.ensureActive()
                currentCoroutineContext().ensureActive()
                val suggestions = runInference(client, chunk)
                val best = suggestions.firstOrNull()
                if (best != null && best.text != chunk) {
                    changes.add(ProofreadChange(originalText = chunk, suggestedText = best.text))
                }
                corrected.append(best?.text ?: chunk)
                if (index != chunks.lastIndex) corrected.append('\n')
            }
            OnDeviceProofreadResult.Success(
                correctedText = corrected.toString(),
                changes = changes,
                chunkCount = chunks.size
            )
        } catch (t: Throwable) {
            OnDeviceProofreadResult.Failure(classifyOnDeviceFailure(t), t)
        }
    }

    /** 非流式推理；results 已按置信度降序（官方文档）。 */
    private suspend fun runInference(
        client: Proofreader,
        chunk: String
    ): List<com.google.mlkit.genai.proofreading.ProofreadingSuggestion> {
        val request = ProofreadingRequest.builder(chunk).build()
        return suspendCancellableCoroutine { continuation ->
            val future = try {
                client.runInference(request)
            } catch (t: Throwable) {
                continuation.resumeWithException(t)
                return@suspendCancellableCoroutine
            }
            future.addListener(
                {
                    try {
                        val value = future.get()
                        if (value == null) {
                            continuation.resumeWithException(
                                IllegalStateException("ML Kit Proofreading returned a null result")
                            )
                        } else {
                            // getResults() 的真实返回类型是实现 List 的内部类，转成标准 List 再交出。
                            continuation.resume(value.results?.toList().orEmpty())
                        }
                    } catch (e: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
                { command -> command.run() }
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }

    private suspend fun clientFor(language: OnDeviceProofreadLanguage): Proofreader = mutex.withLock {
        val cached = proofreader
        if (cached != null && cachedLanguage == language) return@withLock cached
        runCatching { cached?.close() }
        val created = Proofreading.getClient(
            ProofreaderOptions.builder(appContext)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(language.toMlKitLanguage())
                .build()
        )
        proofreader = created
        cachedLanguage = language
        created
    }

    /** 释放客户端；页面销毁 / ViewModel onCleared 时调用。 */
    suspend fun close() {
        mutex.withLock {
            runCatching { proofreader?.close() }
            proofreader = null
            cachedLanguage = null
        }
    }
}

internal fun OnDeviceProofreadLanguage.toMlKitLanguage(): Int = when (this) {
    OnDeviceProofreadLanguage.ENGLISH -> ProofreaderOptions.Language.ENGLISH
    OnDeviceProofreadLanguage.JAPANESE -> ProofreaderOptions.Language.JAPANESE
    OnDeviceProofreadLanguage.GERMAN -> ProofreaderOptions.Language.GERMAN
    OnDeviceProofreadLanguage.FRENCH -> ProofreaderOptions.Language.FRENCH
    OnDeviceProofreadLanguage.ITALIAN -> ProofreaderOptions.Language.ITALIAN
    OnDeviceProofreadLanguage.SPANISH -> ProofreaderOptions.Language.SPANISH
    OnDeviceProofreadLanguage.KOREAN -> ProofreaderOptions.Language.KOREAN
}
