package com.breakyuna.noveltranslator.core.exporter

/**
 * Pure statistics about what an export of an Edition would contain.
 *
 * 这是只读预检：只统计，不做任何修复、不写文件。统计数据全部来自导出路径同一套
 * 逐章逐段解析逻辑（[ExportContent]），与导出结果保持一致。
 *
 * 中文摘要文案暂时作为 core 内常量存在；接入 UI 时搬入 `AppStrings` / `PlatformUiStrings`
 * （本仓库 i18n 为纯 Kotlin 双实现，不得使用 XML 字符串资源）。
 */
data class ExportPrecheckReport(
    /** 含有至少一个非空段的逻辑章数量。 */
    val totalChapters: Int = 0,
    /** 解析到的逻辑段总数（含空段）。 */
    val totalSegments: Int = 0,
    /** 译文缺失、导出时将回退为原文的段数。 */
    val fallbackSegments: Int = 0,
    /** 原文与译文均为空、导出时不产生正文的段数。 */
    val emptySegments: Int = 0,
    /** 导出正文（译文缺失段按原文计）中 `[IMG:name]` 标记总数。 */
    val imageMarkers: Int = 0,
    /** 在书籍共享图片目录中找不到对应文件的 `[IMG:name]` 标记数（导出时按纯文本输出）。 */
    val missingImageMarkers: Int = 0,
    /** 预计导出正文字符数（译文缺失段按原文计；双语导出约为该值的两倍）。 */
    val estimatedCharacters: Int = 0,
) {
    /** 译文缺失比例；无段时为 0。 */
    val missingTranslationRatio: Double
        get() = if (totalSegments == 0) 0.0 else fallbackSegments.toDouble() / totalSegments

    /**
     * 是否存在阻断问题：导出物基本不可用（空书、几乎全是原文回退）。
     * 图片缺失与部分回退只算警告，不阻断。
     */
    val hasBlockingIssues: Boolean
        get() = totalSegments == 0 ||
            (totalSegments - emptySegments) == 0 ||
            missingTranslationRatio >= BLOCKING_MISSING_RATIO

    /** 是否存在不阻断但值得提示用户的问题。 */
    val hasWarnings: Boolean
        get() = emptySegments > 0 || missingImageMarkers > 0 || missingTranslationRatio >= WARNING_MISSING_RATIO

    /** 人类可读中文摘要；接入 UI 时搬入 i18n（见类注释）。 */
    fun summary(): String {
        if (totalSegments == 0) {
            return "没有可导出的内容：未解析到任何章节或段落。"
        }
        val lines = mutableListOf("共 ${totalChapters} 章 / ${totalSegments} 段，预计导出约 ${estimatedCharacters} 字。")
        if (fallbackSegments > 0) {
            lines += "译文缺失 ${fallbackSegments} 段（${percent(missingTranslationRatio)}），导出时将回退为原文。"
        }
        if (emptySegments > 0) {
            lines += "空段落 ${emptySegments} 段，导出时不产生正文。"
        }
        if (missingImageMarkers > 0) {
            lines += "${missingImageMarkers} 处图片标记在书籍图片目录中找不到对应文件，将按纯文本输出。"
        }
        lines += when {
            hasBlockingIssues -> "存在阻断问题：导出内容几乎全部回退为原文，建议先完成翻译再导出。"
            hasWarnings -> "存在警告，但仍可导出。"
            else -> "未发现阻断问题。"
        }
        return lines.joinToString("\n")
    }

    private fun percent(ratio: Double): String {
        val rounded = Math.round(ratio * 1000.0) / 10.0
        return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}%" else "$rounded%"
    }

    companion object {
        /** 译文缺失比例达到该值时视为阻断（导出物等于原文）。 */
        const val BLOCKING_MISSING_RATIO = 0.9

        /** 译文缺失比例达到该值时给出警告。 */
        const val WARNING_MISSING_RATIO = 0.3
    }
}
