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

    // ---- Settings shell ----
    val settingsSubtitle: String
    val settingsSectionPreferences: String
    val settingsSectionDiagnostics: String
    val settingsProvidersTitle: String
    val settingsProvidersSubtitle: String
    val settingsProvidersCount: (Int) -> String
    val settingsAppearanceTitle: String
    val settingsAppearanceSubtitle: String
    val settingsLogsTitle: String
    val settingsLogsSubtitle: String
    val settingsLogsCount: (Int) -> String
    val settingsDebugTitle: String

    val settingsUnattendedTitle: String
    val settingsUnattendedOnSubtitle: String
    val settingsUnattendedOffSubtitle: String
    val settingsDebugOnSubtitle: String
    val settingsDebugOffSubtitle: String
    val settingsSystemInfoTitle: String
    val settingsSystemInfoSubtitle: String
    val settingsDefaultProviderLabel: (String) -> String
    val settingsConfiguredProviders: (Int) -> String
    val settingsNoProviders: String
    val settingsAppName: String
    val settingsVersionLabel: String
    val settingsProjectsLabel: String
    val settingsProjectsCount: (Int) -> String
    val settingsAppVersion: String

    // ---- Appearance ----
    val themeModeSystem: String
    val themeModeSystemSubtitle: String
    val themeModeLight: String
    val themeModeLightSubtitle: String
    val themeModeDark: String
    val themeModeDarkSubtitle: String
    val languageChineseTitle: String
    val languageChineseSubtitle: String
    val languageEnglishTitle: String
    val languageEnglishSubtitle: String

    // ---- Logs ----
    val logFilterAll: String
    val logFilterError: String
    val logFilterTranslation: String
    val logFilterApi: String
    val logCountLabel: (Int) -> String
    val logEmpty: String
    val logsCopiedToast: String

    // ---- Provider cards ----
    val providerDefaultBadge: String
    val providerEdit: String
    val providerDelete: String
    val providerSetDefault: String
    val providerTestConnection: String
    val providerDeleteTitle: String
    val providerDeleteMessage: (String) -> String

    // ---- Provider dialog ----
    val providerAddTitle: String
    val providerEditTitle: String
    val providerPresetLabel: String
    val providerNameLabel: String
    val providerModelLabel: String
    val providerFetchModels: String
    val providerFetchingModels: String
    val providerFetchedCount: (Int) -> String
    val providerFetchFailed: String
    val providerSearchModel: String
    val providerInputPriceLabel: String
    val providerOutputPriceLabel: String
    val providerRpmLabel: String
    val providerConcurrencyLabel: String
    val providerPacingHint: String
    val providerSetAsDefault: String
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

    override val settingsSubtitle = "偏好配置与系统管理"
    override val settingsSectionPreferences = "功能设置"
    override val settingsSectionDiagnostics = "诊断与系统"
    override val settingsProvidersTitle = "模型接口"
    override val settingsProvidersSubtitle = "管理大模型 API 端点、Token 价格与连通性"
    override val settingsProvidersCount: (Int) -> String = { count -> "$count 个供应商" }
    override val settingsAppearanceTitle = "外观显示"
    override val settingsAppearanceSubtitle = "深浅色主题模式与应用界面语言"
    override val settingsLogsTitle = "系统日志"
    override val settingsLogsSubtitle = "查看运行时审计、API 调试与翻译异常"
    override val settingsLogsCount: (Int) -> String = { count -> "$count 条" }
    override val settingsDebugTitle = "Debug 模式"

    override val settingsUnattendedTitle = "无人值守：自动整理记忆与术语"
    override val settingsUnattendedOnSubtitle = "每章落库后自动跑一次独立请求生成章节摘要与故事记忆，并归集术语候选；达到跨章高置信门槛的术语才自动入库。会产生额外付费请求。"
    override val settingsUnattendedOffSubtitle = "关闭时章节摘要与术语归集只在你或助手明确要求时进行。"
    override val settingsDebugOnSubtitle = "记录后续翻译任务的完整提示词、模型响应、重试与失败原因"
    override val settingsDebugOffSubtitle = "默认关闭；开启后可在工作台查看完整 API 交互内容"
    override val settingsSystemInfoTitle = "系统信息"
    override val settingsSystemInfoSubtitle = "应用版本与翻译工程统计"
    override val settingsDefaultProviderLabel: (String) -> String = { model -> "当前默认模型: $model" }
    override val settingsConfiguredProviders: (Int) -> String = { count -> "已配置供应商 ($count)" }
    override val settingsNoProviders = "暂无配置的 LLM 供应商"
    override val settingsAppName = "AI Novel Translator"
    override val settingsVersionLabel = "当前版本"
    override val settingsProjectsLabel = "翻译工程"
    override val settingsProjectsCount: (Int) -> String = { count -> "$count 个工程" }
    override val settingsAppVersion = "v0.7.0 beta"

    override val themeModeSystem = "跟随系统"
    override val themeModeSystemSubtitle = "根据系统深浅色设置自适应切换"
    override val themeModeLight = "浅色模式"
    override val themeModeLightSubtitle = "明亮清爽的经典浅色界面"
    override val themeModeDark = "深色模式"
    override val themeModeDarkSubtitle = "深邃舒适的暗夜夜间界面"
    override val languageChineseTitle = "简体中文 (Chinese)"
    override val languageChineseSubtitle = "默认界面语言"
    override val languageEnglishTitle = "English"
    override val languageEnglishSubtitle = "Full English interface translation"

    override val logFilterAll = "全部"
    override val logFilterError = "错误"
    override val logFilterTranslation = "翻译"
    override val logFilterApi = "API"
    override val logCountLabel: (Int) -> String = { count -> "共 $count 条" }
    override val logEmpty = "暂无系统运行日志"
    override val logsCopiedToast = "日志已复制"

    override val providerDefaultBadge = "默认"
    override val providerEdit = "编辑"
    override val providerDelete = "删除"
    override val providerSetDefault = "设为默认"
    override val providerTestConnection = "测试连通性"
    override val providerDeleteTitle = "删除供应商"
    override val providerDeleteMessage: (String) -> String = { name -> "确定要删除供应商 \"$name\" 吗？" }

    override val providerAddTitle = "添加模型供应商"
    override val providerEditTitle = "编辑模型供应商"
    override val providerPresetLabel = "模型厂家 / 协议预设"
    override val providerNameLabel = "供应商名称"
    override val providerModelLabel = "模型名称"
    override val providerFetchModels = "自动从端点拉取模型列表"
    override val providerFetchingModels = "正在拉取…"
    override val providerFetchedCount: (Int) -> String = { count -> "已从端点获取 $count 个模型" }
    override val providerFetchFailed = "拉取模型失败"
    override val providerSearchModel = "搜索匹配模型"
    override val providerInputPriceLabel = "输入 $/M Tok"
    override val providerOutputPriceLabel = "输出 $/M Tok"
    override val providerRpmLabel = "每分钟请求上限 (RPM)"
    override val providerConcurrencyLabel = "并发请求上限"
    override val providerPacingHint = "填 0 = 自动档（云端默认 60 次/分、并发 4，被限流时自动减半）。填非 0 = 固定额度，不再自动调整。"
    override val providerSetAsDefault = "设为默认供应商"
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
    override val navSettings = "System settings"

    override val settingsSubtitle = "Preferences & system management"
    override val settingsSectionPreferences = "Preferences"
    override val settingsSectionDiagnostics = "Diagnostics & system"
    override val settingsProvidersTitle = "Model providers"
    override val settingsProvidersSubtitle = "Manage API endpoints, token pricing and connectivity"
    override val settingsProvidersCount: (Int) -> String = { count -> "$count provider(s)" }
    override val settingsAppearanceTitle = "Appearance"
    override val settingsAppearanceSubtitle = "Theme mode and interface language"
    override val settingsLogsTitle = "System logs"
    override val settingsLogsSubtitle = "Runtime audit, API debugging and translation errors"
    override val settingsLogsCount: (Int) -> String = { count -> "$count entries" }
    override val settingsDebugTitle = "Debug mode"

    override val settingsUnattendedTitle = "Unattended companion"
    override val settingsUnattendedOnSubtitle = "After each committed chapter, runs one independent request to write chapter/story memory and collect term candidates. Only terms that reach the cross-chapter confidence bar are auto-imported. Costs extra API calls."
    override val settingsUnattendedOffSubtitle = "When off, chapter summaries and term collection happen only when you or the assistant ask."
    override val settingsDebugOnSubtitle = "Records full prompts, model responses, retries and failure reasons for later tasks"
    override val settingsDebugOffSubtitle = "Off by default; enabling shows full API interactions in the workbench"
    override val settingsSystemInfoTitle = "System info"
    override val settingsSystemInfoSubtitle = "App version and translation project stats"
    override val settingsDefaultProviderLabel: (String) -> String = { model -> "Default model: $model" }
    override val settingsConfiguredProviders: (Int) -> String = { count -> "Configured providers ($count)" }
    override val settingsNoProviders = "No LLM provider configured"
    override val settingsAppName = "AI Novel Translator"
    override val settingsVersionLabel = "Version"
    override val settingsProjectsLabel = "Translation projects"
    override val settingsProjectsCount: (Int) -> String = { count -> "$count project(s)" }
    override val settingsAppVersion = "v0.7.0 beta"

    override val themeModeSystem = "Follow system"
    override val themeModeSystemSubtitle = "Switch automatically with the system theme"
    override val themeModeLight = "Light mode"
    override val themeModeLightSubtitle = "Bright and classic light interface"
    override val themeModeDark = "Dark mode"
    override val themeModeDarkSubtitle = "Deep and comfortable night interface"
    override val languageChineseTitle = "简体中文 (Chinese)"
    override val languageChineseSubtitle = "Default interface language"
    override val languageEnglishTitle = "English"
    override val languageEnglishSubtitle = "Full English interface translation"

    override val logFilterAll = "All"
    override val logFilterError = "Errors"
    override val logFilterTranslation = "Translation"
    override val logFilterApi = "API"
    override val logCountLabel: (Int) -> String = { count -> "$count entries" }
    override val logEmpty = "No system logs yet"
    override val logsCopiedToast = "Logs copied"

    override val providerDefaultBadge = "Default"
    override val providerEdit = "Edit"
    override val providerDelete = "Delete"
    override val providerSetDefault = "Set as default"
    override val providerTestConnection = "Test connection"
    override val providerDeleteTitle = "Delete provider"
    override val providerDeleteMessage: (String) -> String = { name -> "Delete provider \"$name\"?" }

    override val providerAddTitle = "Add model provider"
    override val providerEditTitle = "Edit model provider"
    override val providerPresetLabel = "Vendor / protocol preset"
    override val providerNameLabel = "Provider name"
    override val providerModelLabel = "Model name"
    override val providerFetchModels = "Fetch models from endpoint"
    override val providerFetchingModels = "Fetching…"
    override val providerFetchedCount: (Int) -> String = { count -> "Fetched $count model(s)" }
    override val providerFetchFailed = "Failed to fetch models"
    override val providerSearchModel = "Search matching models"
    override val providerInputPriceLabel = "Input $/M Tok"
    override val providerOutputPriceLabel = "Output $/M Tok"
    override val providerRpmLabel = "Requests per minute (RPM)"
    override val providerConcurrencyLabel = "Concurrent requests"
    override val providerPacingHint = "0 = auto (cloud defaults to 60 rpm / concurrency 4, halved on 429). Non-zero pins the limit and disables auto-tuning."
    override val providerSetAsDefault = "Set as default provider"
}

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ChineseStrings }

fun getAppStrings(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.CHINESE -> ChineseStrings
    AppLanguage.ENGLISH -> EnglishStrings
}
