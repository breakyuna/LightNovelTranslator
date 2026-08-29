package com.breakyuna.noveltranslator.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage(val code: String, val displayName: String, val nativeName: String) {
    CHINESE("zh", "简体中文 (默认)", "中文"),
    ENGLISH("en", "English", "English")
}

/**
 * Shared strings that are still used by the platform shell and provider settings.
 *
 * Reader-platform pages use [PlatformUiStrings], so the old project, glossary and
 * task-queue vocabulary does not remain in this interface.
 */
interface AppStrings {
    val appTitle: String
    val openSettings: String
    val delete: String
    val cancel: String
    val save: String
    val presetChinese: String
    val presetEnglish: String
    val presetMarkdown: String
    val settingsTitle: String
    val languageSettingTitle: String
    val addProvider: String
    val baseUrlLabel: String
    val apiKeyLabel: String
    val themeSettingsTitle: String
    val navSettings: String
}

object ChineseStrings : AppStrings {
    override val appTitle = "小说翻译工作室"
    override val openSettings = "系统设置"
    override val delete = "删除"
    override val cancel = "取消"
    override val save = "保存"
    override val presetChinese = "自动推断"
    override val presetEnglish = "Chapter X"
    override val presetMarkdown = "Markdown #"
    override val settingsTitle = "系统设置"
    override val languageSettingTitle = "语言"
    override val addProvider = "添加接口"
    override val baseUrlLabel = "API Base URL (端点)"
    override val apiKeyLabel = "API Key"
    override val themeSettingsTitle = "外观主题"
    override val navSettings = "系统设置"
}

object EnglishStrings : AppStrings {
    override val appTitle = "Novel Translator"
    override val openSettings = "System Settings"
    override val delete = "Delete"
    override val cancel = "Cancel"
    override val save = "Save"
    override val presetChinese = "Auto detect"
    override val presetEnglish = "Chapter X (English)"
    override val presetMarkdown = "Markdown #"
    override val settingsTitle = "System Settings"
    override val languageSettingTitle = "Language"
    override val addProvider = "Add Provider"
    override val baseUrlLabel = "API Base URL (Endpoint)"
    override val apiKeyLabel = "API Key"
    override val themeSettingsTitle = "Appearance"
    override val navSettings = "System Settings"
}

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ChineseStrings }

fun getAppStrings(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.CHINESE -> ChineseStrings
    AppLanguage.ENGLISH -> EnglishStrings
}
