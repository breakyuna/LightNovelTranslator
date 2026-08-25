package com.breakyuna.noveltranslator.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage(val code: String, val displayName: String, val nativeName: String) {
    CHINESE("zh", "简体中文 (默认)", "中文"),
    ENGLISH("en", "English", "English")
}

interface AppStrings {
    val appTitle: String
    val appSubtitle: String
    val openSettings: String
    val getStartedTitle: String
    val getStartedDesc: String
    val loadDemo: String
    val projectsHeader: String
    val noProjectsTitle: String
    val noProjectsDesc: String
    val importNovel: String
    val createProject: String
    val deleteProject: String
    val deleteProjectConfirm: String
    val delete: String
    val cancel: String
    val close: String
    val save: String
    val edit: String

    // Import Dialog
    val importDialogTitle: String
    val fileUploadTab: String
    val pasteTextTab: String
    val chooseFileBtn: String
    val selectedFilePrefix: String
    val pastePlaceholder: String
    val sourceLangLabel: String
    val targetLangLabel: String
    val translationStyleLabel: String
    val translationStylePlaceholder: String
    val customRegexLabel: String
    val customRegexPlaceholder: String

    // Workspace & Cards
    val authorLabel: String
    val styleLabel: String
    val progressLabel: String
    val tokensLabel: String
    val totalCostLabel: String
    val chaptersCount: String
    val donePercent: String
    val searchChapterPlaceholder: String
    val filterAll: String
    val filterPending: String
    val filterDone: String
    val filterError: String
    val noMatchingChapters: String
    val chapterStatusTranslated: String
    val chapterStatusTranslating: String
    val chapterStatusPending: String
    val chapterStatusError: String
    val wordsUnit: String
    val previewRead: String
    val translateChapter: String
    val reTranslateChapter: String
    val translateNext: String
    val translationCockpit: String
    val openChapterSplitter: String
    val openGlossary: String
    val openExport: String
    val novelLabel: String
    val chapterPrefix: String
    val ofChapter: String

    // Splitter Dialog
    val splitterTitle: String
    val splitterDesc: String
    val presetChinese: String
    val presetEnglish: String
    val presetMarkdown: String
    val regexPatternLabel: String
    val aiAgentSlicerTitle: String
    val aiAgentSlicerDesc: String
    val runAiAgentSlicer: String
    val resliceByRegex: String
    val destructiveSplitWarning: String
    val confirmDestructiveAction: String

    // Export Dialog
    val exportTitle: String
    val epubExportTitle: String
    val epubExportDesc: String
    val exportEpubBtn: String
    val txtExportTitle: String
    val includeGlossaryOption: String
    val bilingualComparisonOption: String
    val exportTxtBtn: String

    // Translation Cockpit & Runner
    val runnerTitle: String
    val cockpitTitle: String
    val activeProviderCardTitle: String
    val activeProviderLabel: String
    val noProvidersConfigured: String
    val promptCostUnit: String
    val completionCostUnit: String
    val translatingStatus: String
    val translatingChapter: String
    val pausedStatus: String
    val batchCompleteTitle: String
    val batchCompleteDesc: String
    val translatedChaptersCount: String
    val modeAutoContinuous: String
    val autoContinuousMode: String
    val modeManualRange: String
    val manualRangeMode: String
    val fromChapLabel: String
    val fromChapterLabel: String
    val toChapLabel: String
    val toChapterLabel: String
    val startAutoTranslationBtn: String
    val startAutoTranslation: String
    val translateRangeBtn: String
    val resumeBtn: String
    val pauseBtn: String
    val stopBtn: String
    val liveLogsTitle: String
    val liveLogsHeader: String
    val logsEmptyState: String
    val noLogsYet: String

    // Bilingual Reader & Editor
    val readerTitle: String
    val chapterXofY: String
    val toggleBilingual: String
    val toggleBilingualDesc: String
    val prevChapter: String
    val prevChapterBtn: String
    val nextChapter: String
    val nextChapterBtn: String
    val chapterEmpty: String
    val emptyChapterState: String
    val chapterNotTranslatedYet: String
    val pendingTranslation: String
    val pendingTranslationPlaceholder: String
    val aiRetranslateAction: String
    val aiRetranslateParagraph: String
    val editInPlace: String
    val editInPlaceAction: String
    val editParagraphTitle: String
    val editTranslationDialogTitle: String
    val paragraphLabel: String
    val originalTextLabel: String
    val revisedTranslationLabel: String
    val saveEditBtn: String
    val aiRetranslateDialogTitle: String
    val aiRetranslateParaTitle: String
    val nuanceInstructionLabel: String
    val instructionToneLabel: String
    val nuancePlaceholder: String
    val instructionPlaceholder: String
    val retranslatingWithLlm: String
    val retranslateBtn: String
    val themeLight: String
    val themeSepia: String
    val themeDark: String
    val themeSlate: String

    // Glossary
    val glossaryTitle: String
    val glossarySubtitle: String
    val termsCount: String
    val aiExtractTerms: String
    val aiExtractTermsBtn: String
    val addTerm: String
    val addTermBtn: String
    val aiMinerTitle: String
    val aiMinerDesc: String
    val extractBtn: String
    val searchGlossaryPlaceholder: String
    val searchTermsPlaceholder: String
    val noMatchingTerms: String
    val noTermsYet: String
    val noTermsAdded: String
    val editTermDialogTitle: String
    val addTermDialogTitle: String
    val originalTermLabel: String
    val originalTermPlaceholder: String
    val targetTermLabel: String
    val translatedTermLabel: String
    val targetTermPlaceholder: String
    val translatedTermPlaceholder: String
    val categoryLabel: String
    val contextNotesLabel: String
    val notesLabel: String
    val contextNotesPlaceholder: String
    val notesPlaceholder: String
    val aiExtractedBadge: String
    val approveTerminology: String
    val catCharacter: String
    val categoryCharacter: String
    val catLocation: String
    val categoryLocation: String
    val catLore: String
    val categoryFaction: String
    val catSkill: String
    val categoryMartialArt: String
    val catItem: String
    val categoryMagicItem: String
    val catHonorific: String
    val catCustom: String
    val categoryGeneral: String

    // Settings
    val settingsTitle: String
    val settingsSubtitle: String
    val languageSettingTitle: String
    val languageSettingsTitle: String
    val languageSettingDesc: String
    val languageSettingsDesc: String
    val chineseDefault: String
    val languageChinese: String
    val english: String
    val languageEnglish: String
    val currentLanguageLabel: String
    val addProvider: String
    val addProviderBtn: String
    val calculatorEngineTitle: String
    val tokenCalculatorCardTitle: String
    val calculatorEngineDesc: String
    val tokenCalculatorCardDesc: String
    val defaultLabel: String
    val defaultBadge: String
    val setAsDefaultBtn: String
    val testConnectionBtn: String
    val testingBtn: String
    val testingStatus: String
    val providerNameLabel: String
    val baseUrlLabel: String
    val providerEndpointLabel: String
    val apiKeyLabel: String
    val providerApiKeyLabel: String
    val modelIdLabel: String
    val providerModelLabel: String
    val modelPlaceholder: String
    val inputPriceLabel: String
    val outputPriceLabel: String
    val setDefaultProviderCheck: String
    val setAsDefaultCheckbox: String
    val quickPresetsLabel: String
    val editProvider: String
    val editProviderTitle: String
    val editProviderDialogTitle: String
    val addProviderTitle: String
    val addProviderDialogTitle: String
    val saveProvider: String
    val saveProviderBtn: String
    val switchLanguagePrompt: String

    // Endpoint model fetching & System logs
    val fetchModelsBtn: String
    val fetchingModels: String
    val fetchModelsSuccess: String
    val fetchModelsError: String
    val selectModelFromList: String
    val llmSettingsSection: String
    val systemLogsSection: String
    val systemLogsDesc: String
    val generalSettingsSection: String
    val aboutStorageSection: String
    val clearLogsBtn: String
    val exportLogsBtn: String
    val copyLogsBtn: String
    val logsCopiedToast: String
    val logsClearedToast: String
    val filterLogsAll: String
    val filterLogsError: String
    val filterLogsTranslation: String
    val filterLogsApi: String
    val noSystemLogs: String
    val systemStatusHealthy: String
    val appVersionLabel: String
    val storageUsedLabel: String
    val databaseStatusLabel: String

    // Theme Mode
    val themeSettingsTitle: String
    val themeFollowSystem: String
    val themeLightMode: String
    val themeDarkMode: String

    // Continuous Translation Alert
    val continuousWarningTitle: String
    val continuousWarningDesc: String
    val continuousEstWords: String
    val continuousEstTokens: String
    val continuousEstCost: String
    val dontRemindThisSession: String
    val continueTranslation: String

    // Novel Reader Mode & Stats
    val novelReaderTitle: String
    val themeMint: String
    val themeAmoled: String
    val fontSerif: String
    val fontSans: String
    val lineHeightLabel: String
    val indentLabel: String
    val tocSheetTitle: String
    val origWordCountLabel: String
    val transWordCountLabel: String
    val totalWordsLabel: String
    val extractNewTermsAction: String
    val newTermsDiscoveredToast: String
    val presetLanguagesLabel: String

    // Navigation Drawer & Navigation Rails
    val navHome: String
    val navWorkspace: String
    val navTranslation: String
    val navGlossary: String
    val navReader: String
    val navSettings: String
    val navLogs: String
    val navCurrentProject: String
    val navNoActiveProject: String
    val navSwitchProject: String
    val navMenuDesc: String
    val navCollapseDrawer: String

    // Task Queue & Concurrency
    val navTaskQueue: String
    val taskQueueTitle: String
    val taskQueueSubtitle: String
    val maxConcurrencyLabel: String
    val concurrencyLimitNotice: String
    val tasksQueuedCount: String
    val tasksRunningCount: String
    val tasksCompletedCount: String
    val tasksFailedCount: String
    val clearCompletedTasks: String
    val pauseAllTasks: String
    val resumeAllTasks: String
    val addTaskToQueue: String
    val taskAddedToQueueToast: String
    val noTasksInQueue: String
    val taskStatusQueued: String
    val taskStatusRunning: String
    val taskStatusPaused: String
    val taskStatusCompleted: String
    val taskStatusFailed: String
    val taskStatusCancelled: String
    val retryTaskBtn: String
    val cancelTaskBtn: String
    val pauseTaskBtn: String
    val resumeTaskBtn: String

    // AI Term Extraction & Project Isolation
    val termExtractionDialogTitle: String
    val extractionScopeLabel: String
    val scopeAllChapters: String
    val scopeFirstNChapters: String
    val scopeCustomRange: String
    val scopeSelectedChapters: String
    val startExtraction: String
    val pauseExtraction: String
    val resumeExtraction: String
    val stopExtraction: String
    val extractionProgressScanning: String
    val extractionCandidatesFound: String
    val extractionReviewTitle: String
    val extractionReviewSubtitle: String
    val saveSelectedTerms: String
    val selectAll: String
    val deselectAll: String
    val projectBoundNotice: String
    val selectProjectPrompt: String
    val noProjectSelectedGlossary: String

    // Model Search
    val searchModelPlaceholder: String
    val matchingModelsCount: String
    val noMatchingModels: String
    val allEndpointModels: String
}

object ChineseStrings : AppStrings {
    override val appTitle = "小说翻译工作室"
    override val appSubtitle = "基于大语言模型的小说全自动/交互式翻译系统"
    override val openSettings = "系统设置"
    override val getStartedTitle = "快速体验示例工程"
    override val getStartedDesc = "一键加载预设的多章节奇幻小说《星穹炼金师》与丰富术语表"
    override val loadDemo = "加载示例"
    override val projectsHeader = "工程列表"
    override val noProjectsTitle = "暂无小说翻译工程"
    override val noProjectsDesc = "点击“导入小说”或“加载示例”开始"
    override val importNovel = "导入小说"
    override val createProject = "创建工程"
    override val deleteProject = "删除工程"
    override val deleteProjectConfirm = "确定要删除工程《%s》吗？所有章节切片、翻译文件及图像都将被永久移除。"
    override val delete = "删除"
    override val cancel = "取消"
    override val close = "关闭"
    override val save = "保存"
    override val edit = "编辑"

    // Import Dialog
    override val importDialogTitle = "导入小说文件 (.txt / .epub)"
    override val fileUploadTab = "文件上传"
    override val pasteTextTab = "粘贴文本"
    override val chooseFileBtn = "选择 .txt 或 .epub 文件"
    override val selectedFilePrefix = "已选文件: "
    override val pastePlaceholder = "在此直接粘贴小说文本内容..."
    override val sourceLangLabel = "源语言"
    override val targetLangLabel = "目标语言"
    override val translationStyleLabel = "翻译风格 / 语气"
    override val translationStylePlaceholder = "例如：文学小说、信达雅、流畅通俗"
    override val customRegexLabel = "自定义章节正则 (选填)"
    override val customRegexPlaceholder = "留空将自动匹配中英文章节标题"

    // Workspace & Cards
    override val authorLabel = "作者"
    override val styleLabel = "风格"
    override val progressLabel = "翻译进度"
    override val tokensLabel = "Tokens"
    override val totalCostLabel = "总费用"
    override val chaptersCount = "章节数"
    override val donePercent = "已完成"
    override val searchChapterPlaceholder = "搜索章节标题..."
    override val filterAll = "全部"
    override val filterPending = "待翻译"
    override val filterDone = "已完成"
    override val filterError = "出错"
    override val noMatchingChapters = "未找到符合筛选条件的章节"
    override val chapterStatusTranslated = "已完成"
    override val chapterStatusTranslating = "翻译中..."
    override val chapterStatusPending = "待翻译"
    override val chapterStatusError = "出错"
    override val wordsUnit = "字"
    override val previewRead = "双语阅读 / 对照"
    override val translateChapter = "翻译本章"
    override val reTranslateChapter = "重新翻译本章"
    override val translateNext = "继续翻译下一章"
    override val translationCockpit = "进入翻译控制台"
    override val openChapterSplitter = "章节裁剪与 Agent 分割"
    override val openGlossary = "术语表与名词管理"
    override val openExport = "导出已翻译小说"
    override val novelLabel = "小说"
    override val chapterPrefix = "第"
    override val ofChapter = "章 / 共"

    // Splitter Dialog
    override val splitterTitle = "章节裁剪与 Agent 智能分割"
    override val splitterDesc = "指定章节切分规则，或者使用 AI Agent 智能分析与分割。重新切分会删除现有译文和进度，请先确认已备份："
    override val presetChinese = "第X章"
    override val presetEnglish = "Chapter X"
    override val presetMarkdown = "Markdown #"
    override val regexPatternLabel = "章节正则表达式"
    override val aiAgentSlicerTitle = "AI Agent 智能切分 (针对不规范或缺失标题的小说)"
    override val aiAgentSlicerDesc = "使用大模型分析自然段落与剧情转折，自动标记章节切分点并重建章节文件，原有译文不会保留。"
    override val runAiAgentSlicer = "运行 AI Agent 智能切分"
    override val resliceByRegex = "按正则重新切分"
    override val destructiveSplitWarning = "此操作会删除当前章节译文、摘要和进度，且只保留重新生成的章节文件。确定继续吗？"
    override val confirmDestructiveAction = "确认重建"

    // Export Dialog
    override val exportTitle = "导出已翻译小说"
    override val epubExportTitle = "EPUB 格式 (推荐用于电子书阅读器)"
    override val epubExportDesc = "标准 EPUB3 电子书，包含完整目录、排版样式和内嵌插图。"
    override val exportEpubBtn = "导出 .EPUB"
    override val txtExportTitle = "纯文本格式 (.TXT)"
    override val includeGlossaryOption = "在末尾附带专有名词对照表附录"
    override val bilingualComparisonOption = "生成中英双语段落平行对照排版"
    override val exportTxtBtn = "导出 .TXT"

    // Translation Cockpit & Runner
    override val runnerTitle = "翻译控制台"
    override val cockpitTitle = "翻译控制台"
    override val activeProviderCardTitle = "当前生效的 LLM 接口"
    override val activeProviderLabel = "当前生效的 LLM 接口"
    override val noProvidersConfigured = "尚未配置 API 接口，请前往设置页面添加。"
    override val promptCostUnit = "输入"
    override val completionCostUnit = "输出"
    override val translatingStatus = "正在翻译"
    override val translatingChapter = "正在翻译第 %d 章..."
    override val pausedStatus = "已暂停"
    override val batchCompleteTitle = "批量翻译已全部完成！"
    override val batchCompleteDesc = "批量翻译已完成"
    override val translatedChaptersCount = "已完成翻译"
    override val modeAutoContinuous = "全自动持续翻译"
    override val autoContinuousMode = "全自动持续翻译"
    override val modeManualRange = "手动指定范围翻译"
    override val manualRangeMode = "手动指定范围翻译"
    override val fromChapLabel = "起始章节"
    override val fromChapterLabel = "起始章节"
    override val toChapLabel = "结束章节"
    override val toChapterLabel = "结束章节"
    override val startAutoTranslationBtn = "启动全自动翻译"
    override val startAutoTranslation = "启动全自动翻译"
    override val translateRangeBtn = "翻译指定章节范围"
    override val resumeBtn = "继续翻译"
    override val pauseBtn = "暂停"
    override val stopBtn = "停止"
    override val liveLogsTitle = "实时执行日志"
    override val liveLogsHeader = "实时执行日志"
    override val logsEmptyState = "开始翻译后，执行日志与 Token 计费明细将在此实时输出。"
    override val noLogsYet = "开始翻译后，执行日志与 Token 计费明细将在此实时输出。"

    // Bilingual Reader & Editor
    override val readerTitle = "双语阅读与微调"
    override val chapterXofY = "第 %d 章 (共 %d 章)"
    override val toggleBilingual = "切换双语对照 / 仅译文"
    override val toggleBilingualDesc = "切换双语对照"
    override val prevChapter = "上一章"
    override val prevChapterBtn = "上一章"
    override val nextChapter = "下一章"
    override val nextChapterBtn = "下一章"
    override val chapterEmpty = "章节内容为空"
    override val emptyChapterState = "章节内容为空"
    override val chapterNotTranslatedYet = "【本章节尚未翻译，可切换至双语模式查看原文，或在控制台中开始翻译】"
    override val pendingTranslation = "【待翻译】"
    override val pendingTranslationPlaceholder = "【待翻译】"
    override val aiRetranslateAction = "AI 单段润色 / 重译"
    override val aiRetranslateParagraph = "AI 单段润色 / 重译"
    override val editInPlace = "就地修改译文"
    override val editInPlaceAction = "就地修改译文"
    override val editParagraphTitle = "修改译文"
    override val editTranslationDialogTitle = "修改译文"
    override val paragraphLabel = "段落"
    override val originalTextLabel = "原文"
    override val revisedTranslationLabel = "修改后的译文内容"
    override val saveEditBtn = "保存修改"
    override val aiRetranslateDialogTitle = "AI 单段重译与微调"
    override val aiRetranslateParaTitle = "AI 单段重译与微调"
    override val nuanceInstructionLabel = "调整要求 / 语气风格 / 术语修正"
    override val instructionToneLabel = "调整要求 / 语气风格 / 术语修正"
    override val nuancePlaceholder = "例如：更有文采诗意、增加讽刺口吻、修正魔法名词"
    override val instructionPlaceholder = "例如：更有文采诗意、增加讽刺口吻、修正魔法名词"
    override val retranslatingWithLlm = "正在调用大模型重译该段落..."
    override val retranslateBtn = "重新翻译"
    override val themeLight = "浅色"
    override val themeSepia = "羊皮纸"
    override val themeDark = "深色"
    override val themeSlate = "石板灰"

    // Glossary
    override val glossaryTitle = "专有名词与术语表 (Glossary)"
    override val glossarySubtitle = "译名统一性引擎"
    override val termsCount = "条术语"
    override val aiExtractTerms = "AI 自动提炼术语"
    override val aiExtractTermsBtn = "AI 自动提炼术语"
    override val addTerm = "添加术语"
    override val addTermBtn = "添加术语"
    override val aiMinerTitle = "AI 专有名词提炼器"
    override val aiMinerDesc = "自动扫描小说前几章，智能挖掘角色姓名、宗门势力、武学技能、奇珍异宝与专有名词。"
    override val extractBtn = "智能提炼"
    override val searchGlossaryPlaceholder = "搜索原文或译名..."
    override val searchTermsPlaceholder = "搜索原文或译名..."
    override val noMatchingTerms = "未找到匹配的术语条目"
    override val noTermsYet = "暂无专有名词，点击“添加术语”或使用 AI 智能提炼"
    override val noTermsAdded = "暂无专有名词，点击“添加术语”或使用 AI 智能提炼"
    override val editTermDialogTitle = "编辑专有名词"
    override val addTermDialogTitle = "添加专有名词"
    override val originalTermLabel = "原文术语 / 角色名"
    override val originalTermPlaceholder = "例如：Luke Sterling, Excalibur"
    override val targetTermLabel = "目标译名"
    override val translatedTermLabel = "目标译名"
    override val targetTermPlaceholder = "例如：卢克·斯特林, 誓约胜利之剑"
    override val translatedTermPlaceholder = "例如：卢克·斯特林, 誓约胜利之剑"
    override val categoryLabel = "分类"
    override val contextNotesLabel = "背景设定与备注 (选填)"
    override val notesLabel = "背景设定与备注 (选填)"
    override val contextNotesPlaceholder = "例如：银月骑士团圣骑士，主角导师"
    override val notesPlaceholder = "例如：银月骑士团圣骑士，主角导师"
    override val aiExtractedBadge = "AI 提炼"
    override val approveTerminology = "审核通过该术语"
    override val catCharacter = "角色人名"
    override val categoryCharacter = "角色人名"
    override val catLocation = "地名场景"
    override val categoryLocation = "地名场景"
    override val catLore = "势力世界观"
    override val categoryFaction = "势力世界观"
    override val catSkill = "功法武学"
    override val categoryMartialArt = "功法武学"
    override val catItem = "道具神器"
    override val categoryMagicItem = "道具神器"
    override val catHonorific = "尊称头衔"
    override val catCustom = "自定义"
    override val categoryGeneral = "通用术语"

    // Settings
    override val settingsTitle = "系统设置"
    override val settingsSubtitle = "管理应用功能、LLM 接口与系统状态"
    override val languageSettingTitle = "语言"
    override val languageSettingsTitle = "语言"
    override val languageSettingDesc = "支持简体中文 (默认) 与 English 实时切换，所有界面元素即时生效。"
    override val languageSettingsDesc = "支持简体中文 (默认) 与 English 实时切换，所有界面元素即时生效。"
    override val chineseDefault = "简体中文 (默认)"
    override val languageChinese = "简体中文 (默认)"
    override val english = "English"
    override val languageEnglish = "English"
    override val currentLanguageLabel = "当前语言: 简体中文 (默认)"
    override val addProvider = "添加接口"
    override val addProviderBtn = "添加接口"
    override val calculatorEngineTitle = "Token 估算与费率计算器引擎"
    override val tokenCalculatorCardTitle = "Token 估算与费率计算器引擎"
    override val calculatorEngineDesc = "自动估算每次请求所耗费的 Prompt 与 Completion Tokens，并按百万 Token 计费单价实时累计工程总成本。"
    override val tokenCalculatorCardDesc = "自动估算每次请求所耗费的 Prompt 与 Completion Tokens，并按百万 Token 计费单价实时累计工程总成本。"
    override val defaultLabel = "默认"
    override val defaultBadge = "默认"
    override val setAsDefaultBtn = "设为默认"
    override val testConnectionBtn = "测试连通性"
    override val testingBtn = "测试中..."
    override val testingStatus = "测试中..."
    override val providerNameLabel = "接口备注名称"
    override val baseUrlLabel = "API Base URL (端点)"
    override val providerEndpointLabel = "API Base URL (端点)"
    override val apiKeyLabel = "API Key"
    override val providerApiKeyLabel = "API Key"
    override val modelIdLabel = "Model 模型标识"
    override val providerModelLabel = "Model 模型标识"
    override val modelPlaceholder = "例如：deepseek-v4-flash, gpt-5.6-luna"
    override val inputPriceLabel = "输入单价 ($ / 百万 Tokens)"
    override val outputPriceLabel = "输出单价 ($ / 百万 Tokens)"
    override val setDefaultProviderCheck = "设为当前默认调用接口"
    override val setAsDefaultCheckbox = "设为当前默认调用接口"
    override val quickPresetsLabel = "快速预设模板"
    override val editProvider = "编辑接口"
    override val editProviderTitle = "编辑接口"
    override val editProviderDialogTitle = "编辑接口"
    override val addProviderTitle = "添加 LLM 接口"
    override val addProviderDialogTitle = "添加 LLM 接口"
    override val saveProvider = "保存接口"
    override val saveProviderBtn = "保存接口"
    override val switchLanguagePrompt = "切换语言"

    // Endpoint model fetching & System logs
    override val fetchModelsBtn = "拉取端点模型"
    override val fetchingModels = "正在拉取..."
    override val fetchModelsSuccess = "成功拉取到 %d 个可用模型"
    override val fetchModelsError = "拉取模型失败: %s"
    override val selectModelFromList = "从端点拉取的模型中选择"
    override val llmSettingsSection = "LLM 设置"
    override val systemLogsSection = "系统日志"
    override val systemLogsDesc = "记录系统全周期运行事件、LLM 接口调用耗时以及翻译错误排查日志"
    override val generalSettingsSection = "界面与常规设置"
    override val aboutStorageSection = "系统信息"
    override val clearLogsBtn = "清空日志"
    override val exportLogsBtn = "导出日志"
    override val copyLogsBtn = "复制全部日志"
    override val logsCopiedToast = "日志已复制到剪贴板"
    override val logsClearedToast = "系统运行日志已清空"
    override val filterLogsAll = "全部"
    override val filterLogsError = "仅报错 (ERROR)"
    override val filterLogsTranslation = "翻译管线"
    override val filterLogsApi = "接口调用"
    override val noSystemLogs = "暂无系统运行日志，系统状态良好"
    override val systemStatusHealthy = "系统运行状态良好"
    override val appVersionLabel = "应用版本"
    override val storageUsedLabel = "工程工作区存储"
    override val databaseStatusLabel = "本地数据库状态"

    // Theme Mode
    override val themeSettingsTitle = "外观主题"
    override val themeFollowSystem = "跟随系统"
    override val themeLightMode = "浅色模式"
    override val themeDarkMode = "深色模式"

    // Continuous Translation Alert
    override val continuousWarningTitle = "全自动持续翻译提示"
    override val continuousWarningDesc = "全自动持续翻译模型会按顺序一次性翻译项目所有未完成章节。请确认预计消耗量："
    override val continuousEstWords = "剩余总字数"
    override val continuousEstTokens = "预估消耗 Tokens"
    override val continuousEstCost = "预计消耗金额"
    override val dontRemindThisSession = "本次会话不再提醒"
    override val continueTranslation = "继续全自动翻译"

    // Novel Reader Mode & Stats
    override val novelReaderTitle = "小说阅读器预览"
    override val themeMint = "护眼绿"
    override val themeAmoled = "纯黑夜间"
    override val fontSerif = "宋体/明朝 (Serif)"
    override val fontSans = "黑体 (Sans)"
    override val lineHeightLabel = "行间距"
    override val indentLabel = "首行缩进"
    override val tocSheetTitle = "小说目录"
    override val origWordCountLabel = "原文"
    override val transWordCountLabel = "译文"
    override val totalWordsLabel = "全书字数"
    override val extractNewTermsAction = "提取本章新术语"
    override val newTermsDiscoveredToast = "已从本章中发现并增量添加 %d 个新术语条目！"
    override val presetLanguagesLabel = "快捷选择语言"

    // Navigation Drawer & Navigation Rails
    override val navHome = "项目列表"
    override val navWorkspace = "工程工作区"
    override val navTranslation = "翻译驾驶舱"
    override val navGlossary = "术语专有名词库"
    override val navReader = "双语/译文阅读器"
    override val navSettings = "系统设置"
    override val navLogs = "系统日志"
    override val navCurrentProject = "当前工程"
    override val navNoActiveProject = "尚未选择项目"
    override val navSwitchProject = "切换项目"
    override val navMenuDesc = "导航菜单"
    override val navCollapseDrawer = "收起导航栏"

    // Task Queue & Concurrency
    override val navTaskQueue = "任务队列"
    override val taskQueueTitle = "后台任务队列"
    override val taskQueueSubtitle = "多任务并发与后台调度队列"
    override val maxConcurrencyLabel = "并发任务数上限"
    override val concurrencyLimitNotice = "超过并发上限的任务将自动在后台队列中排队，当前任务执行完毕后依次自动启动。"
    override val tasksQueuedCount = "排队中"
    override val tasksRunningCount = "进行中"
    override val tasksCompletedCount = "已完成"
    override val tasksFailedCount = "失败"
    override val clearCompletedTasks = "清空已完成"
    override val pauseAllTasks = "暂停队列"
    override val resumeAllTasks = "恢复队列"
    override val addTaskToQueue = "加入任务队列"
    override val taskAddedToQueueToast = "已将 %d 个章节加入并发翻译队列！"
    override val noTasksInQueue = "队列中暂无翻译任务"
    override val taskStatusQueued = "排队等待"
    override val taskStatusRunning = "正在翻译"
    override val taskStatusPaused = "已暂停"
    override val taskStatusCompleted = "翻译完成"
    override val taskStatusFailed = "执行失败"
    override val taskStatusCancelled = "已取消"
    override val retryTaskBtn = "重试"
    override val cancelTaskBtn = "取消"
    override val pauseTaskBtn = "暂停"
    override val resumeTaskBtn = "继续"

    // AI Term Extraction & Project Isolation
    override val termExtractionDialogTitle = "AI 智能提炼专有名词"
    override val extractionScopeLabel = "提炼扫描范围"
    override val scopeAllChapters = "全书所有章节"
    override val scopeFirstNChapters = "前 %d 章"
    override val scopeCustomRange = "自定义章节范围"
    override val scopeSelectedChapters = "当前选中章节"
    override val startExtraction = "开始智能提炼"
    override val pauseExtraction = "暂停扫描"
    override val resumeExtraction = "继续扫描"
    override val stopExtraction = "停止并查看结果"
    override val extractionProgressScanning = "正在扫描: 第 %d 章 (分块 %d/%d)... 已发现 %d 个候选术语"
    override val extractionCandidatesFound = "共发现 %d 个候选专有名词与术语"
    override val extractionReviewTitle = "审核提炼出的专有名词"
    override val extractionReviewSubtitle = "勾选需要保存至当前工程术语表的专有名词"
    override val saveSelectedTerms = "保存选中的 %d 个术语至工程"
    override val selectAll = "全选"
    override val deselectAll = "取消全选"
    override val projectBoundNotice = "🔒 术语表已严格绑定至工程: 《%s》"
    override val selectProjectPrompt = "请先选择一个小说工程"
    override val noProjectSelectedGlossary = "未选择工程，请在左侧或上方选择一个工程查看其术语表"

    // Model Search
    override val searchModelPlaceholder = "搜索端点模型名称 (如 deepseek, gpt-4o, claude, qwen)..."
    override val matchingModelsCount = "已匹配到 %d 个端点模型"
    override val noMatchingModels = "未找到与关键词匹配的模型"
    override val allEndpointModels = "端点拉取的全部模型 (%d 个)"
}

object EnglishStrings : AppStrings {
    override val appTitle = "Novel Translator"
    override val appSubtitle = "LLM-Powered Novel Translation Studio"
    override val openSettings = "System Settings"
    override val getStartedTitle = "Get Started Instantly"
    override val getStartedDesc = "Load a pre-configured multi-chapter fantasy novel with rich terminology."
    override val loadDemo = "Load Demo"
    override val projectsHeader = "Projects"
    override val noProjectsTitle = "No novel translation projects yet"
    override val noProjectsDesc = "Click 'Import Novel' or 'Load Demo' to start"
    override val importNovel = "Import Novel"
    override val createProject = "Create Project"
    override val deleteProject = "Delete Project"
    override val deleteProjectConfirm = "Are you sure you want to delete 《%s》? All chapter slices, translations, and images will be permanently removed."
    override val delete = "Delete"
    override val cancel = "Cancel"
    override val close = "Close"
    override val save = "Save"
    override val edit = "Edit"

    // Import Dialog
    override val importDialogTitle = "Import Novel (.txt / .epub)"
    override val fileUploadTab = "File Upload"
    override val pasteTextTab = "Paste Text"
    override val chooseFileBtn = "Choose .txt or .epub File"
    override val selectedFilePrefix = "Selected: "
    override val pastePlaceholder = "Paste novel text here..."
    override val sourceLangLabel = "Source Lang"
    override val targetLangLabel = "Target Lang"
    override val translationStyleLabel = "Translation Style"
    override val translationStylePlaceholder = "e.g. Literary Novel, Faithful, Fluent"
    override val customRegexLabel = "Custom Chapter Regex (Optional)"
    override val customRegexPlaceholder = "Leave empty for auto Chinese/English regex"

    // Workspace & Cards
    override val authorLabel = "Author"
    override val styleLabel = "Style"
    override val progressLabel = "Progress"
    override val tokensLabel = "Tokens"
    override val totalCostLabel = "Total Cost"
    override val chaptersCount = "Chapters"
    override val donePercent = "Completed"
    override val searchChapterPlaceholder = "Search chapter title..."
    override val filterAll = "All"
    override val filterPending = "Pending"
    override val filterDone = "Done"
    override val filterError = "Error"
    override val noMatchingChapters = "No matching chapters found"
    override val chapterStatusTranslated = "Completed"
    override val chapterStatusTranslating = "Translating..."
    override val chapterStatusPending = "Pending"
    override val chapterStatusError = "Error"
    override val wordsUnit = "words"
    override val previewRead = "Bilingual Read / Compare"
    override val translateChapter = "Translate Chapter"
    override val reTranslateChapter = "Re-translate Chapter"
    override val translateNext = "Translate Next Chapter"
    override val translationCockpit = "Enter Translation Cockpit"
    override val openChapterSplitter = "Chapter Splitter & Agent"
    override val openGlossary = "Glossary & Terminology"
    override val openExport = "Export Translated Novel"
    override val novelLabel = "Novel"
    override val chapterPrefix = "Chapter "
    override val ofChapter = " / "

    // Splitter Dialog
    override val splitterTitle = "Chapter Splitter & AI Agent Slicer"
    override val splitterDesc = "Specify a slicing regex or use AI Agent to split unstructured chapters. Re-splitting deletes existing translations and progress, so back up first:"
    override val presetChinese = "第X章 (Chinese)"
    override val presetEnglish = "Chapter X (English)"
    override val presetMarkdown = "Markdown #"
    override val regexPatternLabel = "Chapter Regex Pattern"
    override val aiAgentSlicerTitle = "AI Agent Smart Slicer (For non-standard/unmarked novels)"
    override val aiAgentSlicerDesc = "Analyzes narrative progression and plot beats to rebuild chapter files; existing translations are not preserved."
    override val runAiAgentSlicer = "Run AI Agent Slicer"
    override val resliceByRegex = "Re-slice by Regex"
    override val destructiveSplitWarning = "This deletes current translations, summaries, and progress, keeping only the rebuilt chapter files. Continue?"
    override val confirmDestructiveAction = "Rebuild"

    // Export Dialog
    override val exportTitle = "Export Translated Novel"
    override val epubExportTitle = "EPUB Format (Recommended for E-Readers)"
    override val epubExportDesc = "Standard EPUB3 with full TOC, styling, and embedded illustrations."
    override val exportEpubBtn = "Export .EPUB"
    override val txtExportTitle = "Plain Text (.TXT)"
    override val includeGlossaryOption = "Append Terminology Glossary at the end"
    override val bilingualComparisonOption = "Format as Bilingual Parallel Paragraphs"
    override val exportTxtBtn = "Export .TXT"

    // Translation Cockpit & Runner
    override val runnerTitle = "Translation Cockpit"
    override val cockpitTitle = "Translation Cockpit"
    override val activeProviderCardTitle = "Active LLM Provider"
    override val activeProviderLabel = "Active LLM Provider"
    override val noProvidersConfigured = "No API providers configured. Please add one in Settings."
    override val promptCostUnit = "Prompt"
    override val completionCostUnit = "Completion"
    override val translatingStatus = "Translating"
    override val translatingChapter = "Translating Chapter %d..."
    override val pausedStatus = "Paused"
    override val batchCompleteTitle = "Batch translation completed!"
    override val batchCompleteDesc = "Batch translation complete"
    override val translatedChaptersCount = "Translated"
    override val modeAutoContinuous = "Auto Continuous"
    override val autoContinuousMode = "Auto Continuous"
    override val modeManualRange = "Manual Range"
    override val manualRangeMode = "Manual Range"
    override val fromChapLabel = "From Chap"
    override val fromChapterLabel = "From Chapter"
    override val toChapLabel = "To Chap"
    override val toChapterLabel = "To Chapter"
    override val startAutoTranslationBtn = "Start Auto Translation"
    override val startAutoTranslation = "Start Auto Translation"
    override val translateRangeBtn = "Translate Range"
    override val resumeBtn = "Resume"
    override val pauseBtn = "Pause"
    override val stopBtn = "Stop"
    override val liveLogsTitle = "Real-time Execution Logs"
    override val liveLogsHeader = "Real-time Execution Logs"
    override val logsEmptyState = "Execution logs and token billing breakdown will stream here live."
    override val noLogsYet = "Execution logs and token billing breakdown will stream here live."

    // Bilingual Reader & Editor
    override val readerTitle = "Bilingual Reader & Editor"
    override val chapterXofY = "Chapter %d (of %d)"
    override val toggleBilingual = "Toggle Bilingual / Translated"
    override val toggleBilingualDesc = "Toggle Bilingual View"
    override val prevChapter = "Previous Chapter"
    override val prevChapterBtn = "Previous Chapter"
    override val nextChapter = "Next Chapter"
    override val nextChapterBtn = "Next Chapter"
    override val chapterEmpty = "Chapter content is empty"
    override val emptyChapterState = "Chapter content is empty"
    override val chapterNotTranslatedYet = "[This chapter is not translated yet. Switch to bilingual mode to view original or translate in Cockpit]"
    override val pendingTranslation = "[Pending Translation]"
    override val pendingTranslationPlaceholder = "[Pending Translation]"
    override val aiRetranslateAction = "AI Refine / Re-translate"
    override val aiRetranslateParagraph = "AI Refine / Re-translate"
    override val editInPlace = "Edit in Place"
    override val editInPlaceAction = "Edit in Place"
    override val editParagraphTitle = "Edit Translation"
    override val editTranslationDialogTitle = "Edit Translation"
    override val paragraphLabel = "Paragraph"
    override val originalTextLabel = "Original Text"
    override val revisedTranslationLabel = "Revised Translation"
    override val saveEditBtn = "Save Edit"
    override val aiRetranslateDialogTitle = "AI Paragraph Refine & Tune"
    override val aiRetranslateParaTitle = "AI Paragraph Refine & Tune"
    override val nuanceInstructionLabel = "Nuance / Tone / Term Fix Instructions"
    override val instructionToneLabel = "Nuance / Tone / Term Fix Instructions"
    override val nuancePlaceholder = "e.g. More poetic, sarcastic tone, fix artifact names"
    override val instructionPlaceholder = "e.g. More poetic, sarcastic tone, fix artifact names"
    override val retranslatingWithLlm = "Invoking LLM to refine this paragraph..."
    override val retranslateBtn = "Re-translate"
    override val themeLight = "Light"
    override val themeSepia = "Sepia"
    override val themeDark = "Dark"
    override val themeSlate = "Slate"

    // Glossary
    override val glossaryTitle = "Terminology & Glossary"
    override val glossarySubtitle = "Consistency Engine"
    override val termsCount = "terms"
    override val aiExtractTerms = "AI Auto-Extract Terms"
    override val aiExtractTermsBtn = "AI Auto-Extract Terms"
    override val addTerm = "Add Term"
    override val addTermBtn = "Add Term"
    override val aiMinerTitle = "AI Terminology Miner"
    override val aiMinerDesc = "Scans early chapters to automatically discover characters, locations, factions, martial arts, and artifacts."
    override val extractBtn = "Mine Terms"
    override val searchGlossaryPlaceholder = "Search original or translated..."
    override val searchTermsPlaceholder = "Search original or translated..."
    override val noMatchingTerms = "No matching terms found"
    override val noTermsYet = "No terms added yet. Click 'Add Term' or use AI extraction."
    override val noTermsAdded = "No terms added yet. Click 'Add Term' or use AI extraction."
    override val editTermDialogTitle = "Edit Term"
    override val addTermDialogTitle = "Add Term"
    override val originalTermLabel = "Original Term / Character Name"
    override val originalTermPlaceholder = "e.g. Luke Sterling, Excalibur"
    override val targetTermLabel = "Target Translation"
    override val translatedTermLabel = "Target Translation"
    override val targetTermPlaceholder = "e.g. 卢克·斯特林, 誓约胜利之剑"
    override val translatedTermPlaceholder = "e.g. 卢克·斯特林, 誓约胜利之剑"
    override val categoryLabel = "Category"
    override val contextNotesLabel = "Context & Notes (Optional)"
    override val notesLabel = "Context & Notes (Optional)"
    override val contextNotesPlaceholder = "e.g. Silvermoon Paladin mentor"
    override val notesPlaceholder = "e.g. Silvermoon Paladin mentor"
    override val aiExtractedBadge = "AI Extracted"
    override val approveTerminology = "Approve terminology"
    override val catCharacter = "Character"
    override val categoryCharacter = "Character"
    override val catLocation = "Location"
    override val categoryLocation = "Location"
    override val catLore = "Faction / Lore"
    override val categoryFaction = "Faction / Lore"
    override val catSkill = "Martial Art / Skill"
    override val categoryMartialArt = "Martial Art / Skill"
    override val catItem = "Item / Artifact"
    override val categoryMagicItem = "Item / Artifact"
    override val catHonorific = "Honorific / Title"
    override val catCustom = "Custom"
    override val categoryGeneral = "General Term"

    // Settings
    override val settingsTitle = "System Settings"
    override val settingsSubtitle = "Manage app features, LLM providers, and system status"
    override val languageSettingTitle = "Language"
    override val languageSettingsTitle = "Language"
    override val languageSettingDesc = "Switch instantly between 简体中文 (Default) and English."
    override val languageSettingsDesc = "Switch instantly between 简体中文 (Default) and English."
    override val chineseDefault = "简体中文 (Default)"
    override val languageChinese = "简体中文 (Default)"
    override val english = "English"
    override val languageEnglish = "English"
    override val currentLanguageLabel = "Current Language: English"
    override val addProvider = "Add Provider"
    override val addProviderBtn = "Add Provider"
    override val calculatorEngineTitle = "Token & Cost Calculator Engine"
    override val tokenCalculatorCardTitle = "Token & Cost Calculator Engine"
    override val calculatorEngineDesc = "Estimates prompt and completion tokens for every request and calculates real-time project expenses."
    override val tokenCalculatorCardDesc = "Estimates prompt and completion tokens for every request and calculates real-time project expenses."
    override val defaultLabel = "Default"
    override val defaultBadge = "Default"
    override val setAsDefaultBtn = "Set Default"
    override val testConnectionBtn = "Test Connection"
    override val testingBtn = "Testing..."
    override val testingStatus = "Testing..."
    override val providerNameLabel = "Provider Name"
    override val baseUrlLabel = "API Base URL (Endpoint)"
    override val providerEndpointLabel = "API Base URL (Endpoint)"
    override val apiKeyLabel = "API Key"
    override val providerApiKeyLabel = "API Key"
    override val modelIdLabel = "Model ID"
    override val providerModelLabel = "Model ID"
    override val modelPlaceholder = "e.g. deepseek-v4-flash, gpt-5.6-luna"
    override val inputPriceLabel = "Input $/1M"
    override val outputPriceLabel = "Output $/1M"
    override val setDefaultProviderCheck = "Set as Default Provider"
    override val setAsDefaultCheckbox = "Set as Default Provider"
    override val quickPresetsLabel = "Quick Presets"
    override val editProvider = "Edit Provider"
    override val editProviderTitle = "Edit Provider"
    override val editProviderDialogTitle = "Edit Provider"
    override val addProviderTitle = "Add LLM Provider"
    override val addProviderDialogTitle = "Add LLM Provider"
    override val saveProvider = "Save Provider"
    override val saveProviderBtn = "Save Provider"
    override val switchLanguagePrompt = "Switch Language"

    // Endpoint model fetching & System logs
    override val fetchModelsBtn = "Fetch Models"
    override val fetchingModels = "Fetching..."
    override val fetchModelsSuccess = "Successfully fetched %d models"
    override val fetchModelsError = "Failed to fetch models: %s"
    override val selectModelFromList = "Select from fetched models"
    override val llmSettingsSection = "LLM Settings"
    override val systemLogsSection = "System Logs"
    override val systemLogsDesc = "Tracks system lifecycle, LLM latency, and translation errors for diagnostics"
    override val generalSettingsSection = "General Preferences"
    override val aboutStorageSection = "System Information"
    override val clearLogsBtn = "Clear Logs"
    override val exportLogsBtn = "Export Logs"
    override val copyLogsBtn = "Copy All Logs"
    override val logsCopiedToast = "Logs copied to clipboard"
    override val logsClearedToast = "System runtime logs cleared"
    override val filterLogsAll = "All"
    override val filterLogsError = "Errors Only"
    override val filterLogsTranslation = "Translation Pipeline"
    override val filterLogsApi = "API Calls"
    override val noSystemLogs = "No system logs recorded yet, system is healthy"
    override val systemStatusHealthy = "System Operating Normally"
    override val appVersionLabel = "App Version"
    override val storageUsedLabel = "Workspace Storage"
    override val databaseStatusLabel = "Local Database Status"

    // Theme Mode
    override val themeSettingsTitle = "Appearance"
    override val themeFollowSystem = "System Default"
    override val themeLightMode = "Light"
    override val themeDarkMode = "Dark"

    // Continuous Translation Alert
    override val continuousWarningTitle = "Continuous Translation Notice"
    override val continuousWarningDesc = "Continuous translation will automatically process all remaining chapters in sequence. Please review the estimated usage:"
    override val continuousEstWords = "Remaining Words"
    override val continuousEstTokens = "Estimated Tokens"
    override val continuousEstCost = "Estimated Cost"
    override val dontRemindThisSession = "Do not remind again this session"
    override val continueTranslation = "Continue Translation"

    // Novel Reader Mode & Stats
    override val novelReaderTitle = "Novel Reader Preview"
    override val themeMint = "Mint Green"
    override val themeAmoled = "AMOLED Dark"
    override val fontSerif = "Serif"
    override val fontSans = "Sans-Serif"
    override val lineHeightLabel = "Line Height"
    override val indentLabel = "Paragraph Indent"
    override val tocSheetTitle = "Table of Contents"
    override val origWordCountLabel = "Original"
    override val transWordCountLabel = "Translated"
    override val totalWordsLabel = "Total Words"
    override val extractNewTermsAction = "Extract New Terms"
    override val newTermsDiscoveredToast = "Discovered & added %d new terms from this chapter!"
    override val presetLanguagesLabel = "Preset Languages"

    // Navigation Drawer & Navigation Rails
    override val navHome = "Projects"
    override val navWorkspace = "Workspace"
    override val navTranslation = "Translation Cockpit"
    override val navGlossary = "Glossary & Terms"
    override val navReader = "Bilingual Reader"
    override val navSettings = "System Settings"
    override val navLogs = "System Logs"
    override val navCurrentProject = "Active Project"
    override val navNoActiveProject = "No Project Selected"
    override val navSwitchProject = "Switch Project"
    override val navMenuDesc = "Navigation Menu"
    override val navCollapseDrawer = "Collapse Menu"

    // Task Queue & Concurrency
    override val navTaskQueue = "Task Queue"
    override val taskQueueTitle = "Background Task Queue"
    override val taskQueueSubtitle = "Concurrent & Background Translation Queue"
    override val maxConcurrencyLabel = "Max Concurrent Tasks"
    override val concurrencyLimitNotice = "Tasks exceeding the limit will automatically queue and start in order as slots open."
    override val tasksQueuedCount = "Queued"
    override val tasksRunningCount = "Running"
    override val tasksCompletedCount = "Completed"
    override val tasksFailedCount = "Failed"
    override val clearCompletedTasks = "Clear Completed"
    override val pauseAllTasks = "Pause Queue"
    override val resumeAllTasks = "Resume Queue"
    override val addTaskToQueue = "Add to Queue"
    override val taskAddedToQueueToast = "Added %d chapter(s) to concurrent task queue!"
    override val noTasksInQueue = "No translation tasks in queue"
    override val taskStatusQueued = "Queued"
    override val taskStatusRunning = "Translating"
    override val taskStatusPaused = "Paused"
    override val taskStatusCompleted = "Completed"
    override val taskStatusFailed = "Failed"
    override val taskStatusCancelled = "Cancelled"
    override val retryTaskBtn = "Retry"
    override val cancelTaskBtn = "Cancel"
    override val pauseTaskBtn = "Pause"
    override val resumeTaskBtn = "Resume"

    // AI Term Extraction & Project Isolation
    override val termExtractionDialogTitle = "AI Terminology Extraction"
    override val extractionScopeLabel = "Scan Scope"
    override val scopeAllChapters = "All Chapters"
    override val scopeFirstNChapters = "First %d Chapters"
    override val scopeCustomRange = "Custom Chapter Range"
    override val scopeSelectedChapters = "Selected Chapters"
    override val startExtraction = "Start Extraction"
    override val pauseExtraction = "Pause Scan"
    override val resumeExtraction = "Resume Scan"
    override val stopExtraction = "Stop & Review"
    override val extractionProgressScanning = "Scanning: Chap %d (Chunk %d/%d)... Discovered %d candidate terms"
    override val extractionCandidatesFound = "Found %d candidate terms & entities"
    override val extractionReviewTitle = "Review Extracted Terminology"
    override val extractionReviewSubtitle = "Select terms to save to the project glossary"
    override val saveSelectedTerms = "Save %d Selected Terms to Project"
    override val selectAll = "Select All"
    override val deselectAll = "Deselect All"
    override val projectBoundNotice = "🔒 Glossary is strictly isolated & bound to: 《%s》"
    override val selectProjectPrompt = "Please select a novel project first"
    override val noProjectSelectedGlossary = "No project selected. Select a project on the left or top to view its isolated glossary."

    // Model Search
    override val searchModelPlaceholder = "Search endpoint models (e.g. deepseek, gpt-4o, claude, qwen)..."
    override val matchingModelsCount = "Matched %d endpoint models"
    override val noMatchingModels = "No models matched the search query"
    override val allEndpointModels = "All Endpoint Models (%d)"
}

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ChineseStrings }

fun getAppStrings(language: AppLanguage): AppStrings {
    return when (language) {
        AppLanguage.CHINESE -> ChineseStrings
        AppLanguage.ENGLISH -> EnglishStrings
    }
}
