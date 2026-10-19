package com.breakyuna.noveltranslator.core.ondevice

import android.content.Context

/**
 * assets/ondevice/prompts/ 下的提示词文本资产（术语抽取辅助、标题翻译，中英各一）。
 *
 * 资产为纯文本模板，用 {{KEY}} 占位，由 [render] 填充；避免把长提示词硬编码进 Kotlin。
 */
object OnDevicePrompts {

    const val TERM_EXTRACTION_ZH = "ondevice/prompts/term_extraction_zh.txt"
    const val TERM_EXTRACTION_EN = "ondevice/prompts/term_extraction_en.txt"
    const val TITLE_TRANSLATION_ZH = "ondevice/prompts/title_translation_zh.txt"
    const val TITLE_TRANSLATION_EN = "ondevice/prompts/title_translation_en.txt"

    /** 读取资产文本；调用方需自行处理 IO 异常（建议在 Dispatchers.IO 上调用）。 */
    fun load(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader().use { it.readText() }

    /** 用 {{KEY}} → value 填充模板。 */
    fun render(template: String, vararg values: Pair<String, String>): String {
        var result = template
        for ((key, value) in values) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
