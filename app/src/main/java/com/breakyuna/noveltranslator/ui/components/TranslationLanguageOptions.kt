package com.breakyuna.noveltranslator.ui.components

data class TargetLanguageOption(
    val code: String,
    val displayName: String,
    val defaultNameZh: String,
    val defaultNameEn: String
)

/** Shared by every translation-version creation flow. */
val TARGET_LANGUAGE_OPTIONS = listOf(
    TargetLanguageOption("Chinese", "简体中文 (Simplified Chinese)", "中文译本", "Chinese translation"),
    TargetLanguageOption("Traditional Chinese", "繁體中文 (Traditional Chinese)", "繁体中文译本", "Traditional Chinese translation"),
    TargetLanguageOption("English", "英语 (English)", "英文译本", "English translation"),
    TargetLanguageOption("Japanese", "日语 (Japanese / 日本語)", "日文译本", "Japanese translation"),
    TargetLanguageOption("Korean", "韩语 (Korean / 한국어)", "韩文译本", "Korean translation"),
    TargetLanguageOption("French", "法语 (French / Français)", "法文译本", "French translation"),
    TargetLanguageOption("German", "德语 (German / Deutsch)", "德文译本", "German translation"),
    TargetLanguageOption("Spanish", "西班牙语 (Spanish / Español)", "西班牙文译本", "Spanish translation"),
    TargetLanguageOption("Russian", "俄语 (Russian / Русский)", "俄文译本", "Russian translation"),
    TargetLanguageOption("Italian", "意大利语 (Italian / Italiano)", "意大利文译本", "Italian translation"),
    TargetLanguageOption("Portuguese", "葡萄牙语 (Portuguese / Português)", "葡萄牙文译本", "Portuguese translation"),
    TargetLanguageOption("Vietnamese", "越南语 (Vietnamese / Tiếng Việt)", "越南文译本", "Vietnamese translation"),
    TargetLanguageOption("Thai", "泰语 (Thai / ไทย)", "泰文译本", "Thai translation"),
    TargetLanguageOption("Indonesian", "印尼语 (Indonesian / Bahasa Indonesia)", "印尼文译本", "Indonesian translation"),
    TargetLanguageOption("Arabic", "阿拉伯语 (Arabic / العربية)", "阿拉伯文译本", "Arabic translation")
)
