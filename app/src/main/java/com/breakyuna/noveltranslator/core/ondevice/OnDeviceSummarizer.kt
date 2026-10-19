package com.breakyuna.noveltranslator.core.ondevice

import android.content.Context
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 章节 → 摘要结果。喂 StoryMemory 用；中文或不支持语言必须回退云端 ContextEngine。 */
sealed interface OnDeviceSummarizeResult {
    data class Success(
        val summary: String,
        val chunkCount: Int
    ) : OnDeviceSummarizeResult

    /** 语言不在端侧白名单（含中文）或输入为空；附带回退建议。 */
    data class Unsupported(
        val reason: String,
        val fallbackSuggestion: String
    ) : OnDeviceSummarizeResult

    data class Failure(
        val error: OnDeviceAssistError,
        val cause: Throwable?
    ) : OnDeviceSummarizeResult
}

/**
 * 端侧章节摘要（ML Kit GenAI Summarization，Gemini Nano / AICore）。
 *
 * API 依据（2026-10 核实）：
 * - Summarization.getClient(SummarizerOptions)，InputType.ARTICLE + OutputType.ONE_BULLET；
 *   语言仅 EN/JA/KO；输入 < 4000 tokens，ARTICLE 还要求 > 400 字符；
 *   https://developers.google.com/ml-kit/genai/summarization/android
 * - 输入超限官方建议：优先摘要前 4000 tokens / setLongInputAutoTruncationEnabled / 分段摘要；
 *   这里采用"分段摘要再合并"，避免丢内容。
 * - 仅支持前台推理：后台（含前台服务）返回 BACKGROUND_USE_BLOCKED。
 * - 用完必须 close()。
 */
class OnDeviceSummarizer(private val appContext: Context) {

    private val mutex = Mutex()
    private var summarizer: Summarizer? = null
    private var cachedLanguage: OnDeviceSummaryLanguage? = null

    /**
     * 生成章节摘要。
     *
     * @param text 章节正文
     * @param language 章节语言代码（"en"/"ja"/"ko" 可用；"zh" 返回 [OnDeviceSummarizeResult.Unsupported]）
     * @param maxInputTokens 单次推理输入上限，默认 [OnDeviceTokenBudget.SAFE_INPUT_TOKENS]
     * @param onProgress 0f..1f 流式进度（下载 → 分段推理 → 合并）
     * @param context 调用方 CoroutineContext，用于协作取消；内部分段边界与每次推理前都会检查
     */
    suspend fun summarize(
        text: String,
        language: String,
        maxInputTokens: Int = OnDeviceTokenBudget.SAFE_INPUT_TOKENS,
        onProgress: (Float) -> Unit = {},
        context: CoroutineContext = EmptyCoroutineContext
    ): OnDeviceSummarizeResult {
        context.ensureActive()
        if (text.isBlank()) {
            return OnDeviceSummarizeResult.Unsupported(
                reason = "章节内容为空，无法生成端侧摘要。",
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_SUMMARY_FALLBACK_ADVICE
            )
        }
        val summaryLanguage = OnDeviceLanguageSupport.summaryLanguage(language)
            ?: return OnDeviceSummarizeResult.Unsupported(
                reason = if (OnDeviceLanguageSupport.isChinese(language)) {
                    "端侧摘要不支持中文（仅支持英语、日语、韩语）。"
                } else {
                    "端侧摘要不支持语言「$language」（仅支持英语、日语、韩语）。"
                },
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_SUMMARY_FALLBACK_ADVICE
            )
        if (!OnDeviceDeviceSupport.isApiSupported()) {
            return OnDeviceSummarizeResult.Failure(OnDeviceAssistError.UNSUPPORTED_DEVICE, null)
        }

        val chunks = OnDeviceTokenBudget.splitForTokenLimit(text, maxInputTokens)
        if (chunks.isEmpty()) {
            return OnDeviceSummarizeResult.Unsupported(
                reason = "章节内容为空，无法生成端侧摘要。",
                fallbackSuggestion = OnDeviceLanguageSupport.CHINESE_SUMMARY_FALLBACK_ADVICE
            )
        }
        onProgress(0f)
        return try {
            val client = clientFor(summaryLanguage)
            val status = ensureFeatureReady(
                checkStatus = { client.checkFeatureStatus().awaitOnDevice() },
                startDownload = client::downloadFeature
            )
            featureStatusToError(status)?.let { statusError ->
                // DOWNLOADING 不阻断：官方文档说明下载中发起推理会在下载完成后自动继续。
                if (statusError != OnDeviceAssistError.DOWNLOADING) {
                    return OnDeviceSummarizeResult.Failure(statusError, null)
                }
            }
            onProgress(0.05f)
            val summaries = ArrayList<String>(chunks.size)
            chunks.forEachIndexed { index, chunk ->
                context.ensureActive()
                currentCoroutineContext().ensureActive()
                val summary = runStreamingInference(
                    client = client,
                    chunk = chunk,
                    onPartial = { partialLength ->
                        val fraction = (partialLength.toFloat() / chunk.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val base = index.toFloat() / chunks.size
                        val step = 1f / chunks.size
                        onProgress(0.05f + 0.9f * (base + step * fraction))
                    }
                )
                summaries.add(summary)
                onProgress(0.05f + 0.9f * (index + 1) / chunks.size)
            }
            val merged = mergeSummaries(summaries, maxInputTokens, client)
            onProgress(1f)
            OnDeviceSummarizeResult.Success(merged, chunks.size)
        } catch (t: Throwable) {
            OnDeviceSummarizeResult.Failure(classifyOnDeviceFailure(t), t)
        }
    }

    /**
     * 分段摘要的合并：单段直接用；多段拼接后若仍超限则再摘要一次（深度 1，防无限递归）。
     */
    private suspend fun mergeSummaries(
        summaries: List<String>,
        maxInputTokens: Int,
        client: Summarizer
    ): String {
        if (summaries.size <= 1) return summaries.firstOrNull().orEmpty()
        val joined = summaries.joinToString(separator = "\n") { it.trim() }.trim()
        if (OnDeviceTokenBudget.estimateTokens(joined) <= maxInputTokens) return joined
        val request = SummarizationRequest.builder(joined).build()
        return client.runInference(request).awaitOnDevice().summary.trim()
    }

    /**
     * 流式推理：用 StreamingCallback 回调局部文本驱动进度，最终以 future 的完整结果返回。
     */
    private suspend fun runStreamingInference(
        client: Summarizer,
        chunk: String,
        onPartial: (Int) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val callback = object : StreamingCallback {
            override fun onNewText(newText: String) {
                if (continuation.isActive) onPartial(newText.length)
            }
        }
        val future = try {
            client.runInference(SummarizationRequest.builder(chunk).build(), callback)
        } catch (t: Throwable) {
            continuation.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        future.addListener(
            {
                try {
                    continuation.resume(future.get().summary)
                } catch (e: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            },
            { command -> command.run() }
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    private suspend fun clientFor(language: OnDeviceSummaryLanguage): Summarizer = mutex.withLock {
        val cached = summarizer
        if (cached != null && cachedLanguage == language) return@withLock cached
        runCatching { cached?.close() }
        val created = Summarization.getClient(
            SummarizerOptions.builder(appContext)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
                .setLanguage(language.toMlKitLanguage())
                .build()
        )
        summarizer = created
        cachedLanguage = language
        created
    }

    /** 释放客户端；页面销毁 / ViewModel onCleared 时调用。 */
    suspend fun close() {
        mutex.withLock {
            runCatching { summarizer?.close() }
            summarizer = null
            cachedLanguage = null
        }
    }
}

internal fun OnDeviceSummaryLanguage.toMlKitLanguage(): Int = when (this) {
    OnDeviceSummaryLanguage.ENGLISH -> SummarizerOptions.Language.ENGLISH
    OnDeviceSummaryLanguage.JAPANESE -> SummarizerOptions.Language.JAPANESE
    OnDeviceSummaryLanguage.KOREAN -> SummarizerOptions.Language.KOREAN
}
