package com.breakyuna.noveltranslator.core.ondevice

import android.content.Context
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 端侧辅助能力端口（集成接缝，由后续集成 agent 接入引擎 / 设置页）。
 *
 * 约定：
 * - 两个方法都只面向"前台交互场景"：用户正在看书/正在编辑译文时主动触发。
 * - 返回 null 表示端侧不可用或失败，调用方回退云端 ContextEngine / 云端校对。
 * - 不在后台调用：AICore 在前台服务中也返回 BACKGROUND_USE_BLOCKED。
 */
interface OnDeviceAssistPort {
    /** 章节 → 记忆摘要（StoryMemory）。中文或不支持语言返回 null（回退云端）。 */
    suspend fun summarizeChapterForMemory(chapterText: String, language: String): String?

    /** 译文校对。返回校对后文本；不可用返回 null。 */
    suspend fun proofreadTranslation(text: String): String?
}

/**
 * 端侧 GenAI 门面：状态探测 + 摘要 + 校对 + Prompt 轻任务 + 统一错误分类与退避。
 *
 * 硬限制（官方文档，2026-10 核实）：
 * 1. 仅 app 前台可推理；前台服务中也返回 BACKGROUND_USE_BLOCKED。
 * 2. AICore 每 app 推理配额 + 电池配额：BUSY / PER_APP_BATTERY_USE_QUOTA_EXCEEDED 需指数退避
 *    （1s/2s/4s/8s 封顶，见 [OnDeviceBackoff]）。
 * 3. 不支持已解锁 bootloader 的设备；设备白名单（Pixel 9/10/11、三星 S25/S26 等）。
 * 4. AICore 初始化需要时间：新设备/重置后可能数分钟到数小时，必须先 checkStatus。
 * 5. 语言：摘要仅 EN/JA/KO；校对仅 EN/JA/DE/FR/IT/ES/KO；中文一律回退云端。
 * 6. 输入：Prompt/摘要 < 4000 tokens；校对 < 256 tokens。
 *
 * 所有公开挂起函数接受 CoroutineContext 用于协作取消；流式 API 返回 Flow，随收集方取消。
 */
class OnDeviceAssistGateway(
    private val appContext: Context,
    /** 端口方法 proofreadTranslation 使用的默认译文语言（集成时传入工程目标语言）。 */
    private val defaultTargetLanguage: String = "en"
) : OnDeviceAssistPort {

    /** Prompt 轻任务客户端（术语抽取辅助、标题翻译）。长生命周期，[shutdown] 时释放。 */
    val promptClient = OnDevicePromptClient()

    private val summarizer = OnDeviceSummarizer(appContext)
    private val proofreader = OnDeviceProofreader(appContext)

    /** 能力探测（设置页开关 / 首次进入阅读器时调用）。 */
    suspend fun probeStatus(): OnDeviceGenAiStatus = probeOnDeviceGenAi(appContext)

    override suspend fun summarizeChapterForMemory(chapterText: String, language: String): String? =
        summarizeChapterDetailed(
            chapterText = chapterText,
            language = language,
            context = EmptyCoroutineContext
        ).let { result -> (result as? OnDeviceSummarizeResult.Success)?.summary }

    /** 带进度与完整分类的摘要入口。 */
    suspend fun summarizeChapterDetailed(
        chapterText: String,
        language: String,
        maxInputTokens: Int = OnDeviceTokenBudget.SAFE_INPUT_TOKENS,
        onProgress: (Float) -> Unit = {},
        context: CoroutineContext = EmptyCoroutineContext
    ): OnDeviceSummarizeResult = summarizer.summarize(
        text = chapterText,
        language = language,
        maxInputTokens = maxInputTokens,
        onProgress = onProgress,
        context = context
    )

    override suspend fun proofreadTranslation(text: String): String? =
        proofreadDetailed(text = text, language = defaultTargetLanguage, context = EmptyCoroutineContext)
            .let { result -> (result as? OnDeviceProofreadResult.Success)?.correctedText }

    /** 带完整分类的校对入口（含变更说明）。 */
    suspend fun proofreadDetailed(
        text: String,
        language: String = defaultTargetLanguage,
        maxInputTokens: Int = OnDeviceTokenBudget.PROOFREAD_SAFE_TOKENS,
        context: CoroutineContext = EmptyCoroutineContext
    ): OnDeviceProofreadResult = proofreader.proofread(
        text = text,
        language = language,
        maxInputTokens = maxInputTokens,
        context = context
    )

    /**
     * 术语抽取辅助（Prompt 轻任务）：从章节原文抽取候选术语，辅助云端术语扫描。
     *
     * 提示词模板见 assets/ondevice/prompts/term_extraction_{zh,en}.txt；
     * 低温度 + 固定 seed 保证结果稳定可复现。
     */
    suspend fun extractTerminologyAssist(
        chapterText: String,
        sourceLanguage: String,
        context: CoroutineContext = EmptyCoroutineContext
    ): String? {
        context.ensureActive()
        val template = loadPromptTemplate(
            zhAsset = OnDevicePrompts.TERM_EXTRACTION_ZH,
            enAsset = OnDevicePrompts.TERM_EXTRACTION_EN,
            language = sourceLanguage
        ) ?: return null
        val prompt = OnDevicePrompts.render(
            template,
            "SOURCE_LANGUAGE" to sourceLanguage,
            "CHAPTER_TEXT" to chapterText
        )
        return runOnDeviceAssistCatching {
            withOnDeviceRetry {
                promptClient.generate(
                    prompt = prompt,
                    config = OnDevicePromptConfig(temperature = 0.2f, seed = 42, maxOutputTokens = 512),
                    context = context
                )
            }
        }
    }

    /** 章节标题翻译（Prompt 轻任务）。模板见 assets/ondevice/prompts/title_translation_{zh,en}.txt。 */
    suspend fun translateTitleAssist(
        title: String,
        sourceLanguage: String,
        targetLanguage: String,
        context: CoroutineContext = EmptyCoroutineContext
    ): String? {
        context.ensureActive()
        val template = loadPromptTemplate(
            zhAsset = OnDevicePrompts.TITLE_TRANSLATION_ZH,
            enAsset = OnDevicePrompts.TITLE_TRANSLATION_EN,
            language = targetLanguage
        ) ?: return null
        val prompt = OnDevicePrompts.render(
            template,
            "SOURCE_LANGUAGE" to sourceLanguage,
            "TARGET_LANGUAGE" to targetLanguage,
            "TITLE" to title
        )
        return runOnDeviceAssistCatching {
            withOnDeviceRetry {
                promptClient.generate(
                    prompt = prompt,
                    config = OnDevicePromptConfig(temperature = 0.2f, seed = 42, maxOutputTokens = 128),
                    context = context
                )
            }
        }
    }

    /** 释放全部客户端（页面销毁 / 进程退出前）。 */
    suspend fun shutdown() {
        runCatching { promptClient.close() }
        runCatching { summarizer.close() }
        runCatching { proofreader.close() }
    }

    /** 按章节语言选择中文或英文提示词模板；资产读取失败返回 null（回退云端）。 */
    private fun loadPromptTemplate(zhAsset: String, enAsset: String, language: String): String? =
        runCatching {
            val asset = if (OnDeviceLanguageSupport.isChinese(language)) zhAsset else enAsset
            OnDevicePrompts.load(appContext, asset)
        }.getOrNull()
}
