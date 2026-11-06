package com.breakyuna.noveltranslator.core.ondevice

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.mapNotNull

/**
 * Prompt 请求参数（轻任务用：术语抽取辅助、章节标题翻译）。
 *
 * 全部参数经 [sanitized] 钳制：
 * - maxOutputTokens 封顶 [MAX_OUTPUT_TOKENS]（1024）：官方要求避免 >4K tokens 的长输出场景，
 *   轻任务不需要长输出。https://developers.google.com/ml-kit/genai/prompt/android/get-started
 * - temperature 钳制 0..2；seed 仅允许非负；candidateCount 钳制 1..4（重复响应会被自动去除）。
 */
data class OnDevicePromptConfig(
    val temperature: Float? = null,
    val seed: Int? = null,
    val topK: Int? = null,
    val candidateCount: Int? = null,
    val maxOutputTokens: Int? = null
) {
    fun sanitized(): OnDevicePromptConfig = copy(
        temperature = temperature?.coerceIn(0f, 2f),
        seed = seed?.takeIf { it >= 0 },
        topK = topK?.takeIf { it >= 1 },
        candidateCount = candidateCount?.coerceIn(1, MAX_CANDIDATES),
        maxOutputTokens = maxOutputTokens?.coerceIn(1, MAX_OUTPUT_TOKENS)
    )

    companion object {
        /** Prompt 官方输入上限：必须 < 4000 tokens。 */
        const val MAX_INPUT_TOKENS = OnDeviceTokenBudget.MAX_INPUT_TOKENS

        /** 轻任务输出上限（远低于官方建议的 4K，避免长输出拖慢前台交互）。 */
        const val MAX_OUTPUT_TOKENS = 1024

        const val MAX_CANDIDATES = 4
    }
}

/**
 * 通用 Prompt 封装（ML Kit GenAI Prompt API，Gemini Nano / AICore）。
 *
 * API 依据（2026-10 核实 + javap 核对产物签名）：
 * - Generation.getClient() → GenerativeModel；suspend checkStatus()（AVAILABLE/DOWNLOADABLE/
 *   DOWNLOADING/UNAVAILABLE）；download(): Flow<DownloadStatus>；generateContent(request) /
 *   generateContentStream(request): Flow<GenerateContentResponse>；countTokens(request)；
 *   warmup()；close()。
 *   https://developers.google.com/ml-kit/genai/prompt/android/get-started
 * - 输入必须 < 4000 tokens，用 countTokens 预检；避免 >4K tokens 输出。
 * - 仅前台可用（后台返回 BACKGROUND_USE_BLOCKED）；AICore 每 app 配额 + 电池配额（BUSY 退避）。
 */
class OnDevicePromptClient {

    private val model: GenerativeModel = Generation.getClient()

    /** 当前 Gemini Nano 状态；调用任何推理前必须先检查。 */
    suspend fun checkStatus(): Int = model.checkStatus()

    /** 触发模型下载并流式返回进度；仅在 DOWNLOADABLE 时调用。 */
    fun download(): Flow<DownloadStatus> = model.download()

    /** 预热：把 Gemini Nano 载入内存，降低首次推理延迟。 */
    suspend fun warmup() {
        model.warmup()
    }

    /** 官方 token 计数（Prompt API 独有；摘要/校对没有计数 API）。 */
    suspend fun countTokens(prompt: String): Int {
        val request = buildRequest(prompt, OnDevicePromptConfig())
        return model.countTokens(request).totalTokens
    }

    /** countTokens 预检：true 表示可发起推理。 */
    suspend fun isWithinInputLimit(prompt: String): Boolean =
        countTokens(prompt) <= OnDevicePromptConfig.MAX_INPUT_TOKENS

    /**
     * 非流式生成（内部走流式收集，便于取消与进度）。
     *
     * @param context 调用方 CoroutineContext，用于协作取消
     */
    suspend fun generate(
        prompt: String,
        config: OnDevicePromptConfig = OnDevicePromptConfig(),
        context: CoroutineContext = EmptyCoroutineContext
    ): String {
        context.ensureActive()
        val builder = StringBuilder()
        generateStream(prompt, config).collect { chunk ->
            currentCoroutineContext().ensureActive()
            builder.append(chunk)
        }
        return builder.toString()
    }

    /** 流式生成：逐段返回文本；Flow 收集方取消时通过 cancellable() 传播。 */
    fun generateStream(
        prompt: String,
        config: OnDevicePromptConfig = OnDevicePromptConfig()
    ): Flow<String> = model.generateContentStream(buildRequest(prompt, config.sanitized()))
        .cancellable()
        .mapNotNull { response -> response.candidates.firstOrNull()?.text }
        .mapNotNull { text -> text.takeIf { it.isNotEmpty() } }

    /** 释放客户端。 */
    fun close() {
        model.close()
    }

    /**
     * Gemini Nano 基础模型名（如 nano-v3）。
     *
     * 注意：ML Kit 的 GenerativeModel 以 Kotlin 编译，但 `getBaseModelName` 带 getter 式命名，
     * Kotlin 侧保留原方法名（不像 `checkStatus` 那样去前缀），因此调用写作 `getBaseModelName()`。
     * 不可用时返回 null，由探测层决定如何展示。
     */
    suspend fun baseModelName(): String? = runCatching { model.getBaseModelName() }.getOrNull()

    private fun buildRequest(prompt: String, config: OnDevicePromptConfig): GenerateContentRequest =
        GenerateContentRequest.Builder(TextPart(prompt)).apply {
            // Kotlin 属性语法（官方 DSL 写法）；底层是 Java setter(java.lang.Float/Integer)。
            config.temperature?.let { temperature = it }
            config.seed?.let { seed = it }
            config.topK?.let { topK = it }
            config.candidateCount?.let { candidateCount = it }
            config.maxOutputTokens?.let { maxOutputTokens = it }
        }.build()
}
