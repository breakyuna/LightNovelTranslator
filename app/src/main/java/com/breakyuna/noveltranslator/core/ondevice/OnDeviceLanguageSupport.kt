package com.breakyuna.noveltranslator.core.ondevice

/**
 * 端侧 GenAI 的语言支持判定（纯 JVM 逻辑，可单测）。
 *
 * 官方硬限制（已核实）：
 * - Summarization 仅支持 EN / JA / KO：https://developers.google.com/ml-kit/genai/summarization/android
 * - Proofreading 仅支持 EN / JA / DE / FR / IT / ES / KO：
 *   https://developers.google.com/ml-kit/genai/proofreading/android
 * - Prompt API 无官方语言白名单（Gemini Nano 多语言），术语抽取/标题翻译等轻任务可用；
 *   但摘要/校对必须按上面的白名单判定，中文一律回退云端。
 */
enum class OnDeviceSummaryLanguage {
    ENGLISH,
    JAPANESE,
    KOREAN
}

enum class OnDeviceProofreadLanguage {
    ENGLISH,
    JAPANESE,
    GERMAN,
    FRENCH,
    ITALIAN,
    SPANISH,
    KOREAN
}

object OnDeviceLanguageSupport {

    const val CHINESE = "zh"

    /** Summarization 支持的语言代码（与 SummarizerOptions.Language 对应）。 */
    val SUMMARY_LANGUAGE_CODES = setOf("en", "ja", "ko")

    /** Proofreading 支持的语言代码（与 ProofreaderOptions.Language 对应）。 */
    val PROOFREAD_LANGUAGE_CODES = setOf("en", "ja", "de", "fr", "it", "es", "ko")

    /** 中文摘要不可用时的回退建议（展示给用户/调用方）。 */
    const val CHINESE_SUMMARY_FALLBACK_ADVICE =
        "端侧摘要仅支持英语、日语、韩语；中文章节请回退云端 ContextEngine 生成记忆摘要。"

    /** 中文校对不可用时的回退建议。 */
    const val CHINESE_PROOFREAD_FALLBACK_ADVICE =
        "端侧校对仅支持英语、日语、韩语及欧洲五国语言，暂不支持中文；中文译文请改用云端校对或人工检查。"

    /** 归一化语言代码："zh-CN" → "zh"，"EN" → "en"，空值 → ""。 */
    fun normalizeLanguage(language: String?): String {
        val trimmed = language?.trim()?.lowercase() ?: return ""
        if (trimmed.isEmpty()) return ""
        val base = trimmed.substringBefore('-').substringBefore('_')
        return base.ifEmpty { trimmed }
    }

    /** 是否中文（zh / zh-CN / zh-TW / zh-rCN / chinese 等）。 */
    fun isChinese(language: String?): Boolean = when (normalizeLanguage(language)) {
        CHINESE, "chinese", "中文" -> true
        else -> false
    }

    /** 端侧摘要语言；不支持（含中文）返回 null。 */
    fun summaryLanguage(language: String?): OnDeviceSummaryLanguage? = when (normalizeLanguage(language)) {
        "en", "english" -> OnDeviceSummaryLanguage.ENGLISH
        "ja", "japanese" -> OnDeviceSummaryLanguage.JAPANESE
        "ko", "korean" -> OnDeviceSummaryLanguage.KOREAN
        else -> null
    }

    /** 端侧校对语言；不支持（含中文）返回 null。 */
    fun proofreadLanguage(language: String?): OnDeviceProofreadLanguage? = when (normalizeLanguage(language)) {
        "en", "english" -> OnDeviceProofreadLanguage.ENGLISH
        "ja", "japanese" -> OnDeviceProofreadLanguage.JAPANESE
        "de", "german" -> OnDeviceProofreadLanguage.GERMAN
        "fr", "french" -> OnDeviceProofreadLanguage.FRENCH
        "it", "italian" -> OnDeviceProofreadLanguage.ITALIAN
        "es", "spanish" -> OnDeviceProofreadLanguage.SPANISH
        "ko", "korean" -> OnDeviceProofreadLanguage.KOREAN
        else -> null
    }
}
