package com.breakyuna.noveltranslator.ui.screens.agent

import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionTexts
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.EnglishStrings

/**
 * Agent 对话页的自带文案。
 *
 * 阅读平台的 i18n 由 ui/i18n 统一维护（AppStrings + ChineseStrings/EnglishStrings），
 * 但该目录属于其他改动范围，因此本页自带一套中英文案，避免并发修改冲突。
 * 集成阶段如需并入统一 i18n，把这里的字段搬进 AppStrings 即可，页面只依赖本类型。
 *
 * 宿主层面板（模型切换 / 诊断）的文案见
 * [com.breakyuna.noveltranslator.ui.i18n.AgentEntryStrings]。
 */
data class AgentChatText(
    val title: String,
    val subtitleIdle: String,
    val subtitleRunning: String,
    val inputPlaceholder: String,
    val send: String,
    val cancel: String,
    val newChat: String,
    val back: String,
    val switchSession: String,
    val thinking: String,
    val emptyTitle: String,
    val emptyDescription: String,
    val youLabel: String,
    val assistantLabel: String,
    val cancelled: String,
    val notConfigured: String,
    val requestFailedTemplate: String,
    val turnFailedTemplate: String,
    val emptyReply: String,
    val questionAnswerPlaceholder: String,
    val questionSkip: String,
    // ---- 工具调用 / 回执摘要行 ----
    /** 占位符：{tool} */
    val toolCallSummaryTemplate: String,
    /** 占位符：{tool} */
    val toolResultSummaryTemplate: String,
    /** 占位符：{tool} */
    val toolResultFailedSummaryTemplate: String,
    /** toolName 缺失时的占位 */
    val toolUnknownLabel: String,
    val toolArgsTitle: String,
    val toolResultTitle: String,
    val expandRaw: String,
    val collapseRaw: String,
    val copyRaw: String,
    /** 占位符：{count} */
    val rawCharsTemplate: String,
    /** 占位符：{input} {output} {requests}（chip 用的短版） */
    val usageShortTemplate: String,
    val modelUnknown: String,
    val providerUnknown: String,
    /** 长按气泡复制的无障碍标签 */
    val copyMessage: String,
    val copied: String,
    /** 顶部模型面板入口的无障碍描述 */
    val modelPanelEntry: String,
    /** 顶部诊断入口的无障碍描述 */
    val diagnosticsEntry: String,
    /** 信息栏展开时的收起按钮无障碍描述 */
    val sidebarHide: String,
    /** 信息栏收起后的展开按钮无障碍描述 */
    val sidebarShow: String,
    /** 流式生成中的气泡尾部提示 */
    val streamingHint: String,
    /** 写进 Agent 运行环境段的回复语言（展示名），让英文界面拿到英文答复 */
    val replyLanguage: String,
    /** 回合进行中尝试新建 / 切换会话时的提示 */
    val sessionBusy: String
)

object AgentChatStrings {

    val chinese = AgentChatText(
        title = "Agent 对话",
        subtitleIdle = "可以询问书架、翻译任务与术语情况",
        subtitleRunning = "正在处理，请稍候",
        inputPlaceholder = "输入消息，例如：书架上有哪些书？",
        send = "发送",
        cancel = "停止",
        newChat = "新对话",
        back = "返回",
        switchSession = "切换会话",
        thinking = "正在思考或调用工具",
        emptyTitle = "开始一段对话",
        emptyDescription = "Agent 可以查询书籍与翻译任务状态，并在你确认后执行翻译相关操作。",
        youLabel = "你",
        assistantLabel = "助手",
        cancelled = "已取消本次请求。",
        notConfigured = "尚未选择可用的模型提供方，请先在设置中配置。",
        requestFailedTemplate = "请求失败：{reason}",
        turnFailedTemplate = "本次对话未能完成：{reason}",
        emptyReply = "（模型没有返回可用内容）",
        questionAnswerPlaceholder = "输入回答",
        questionSkip = "跳过问题，按默认值继续",
        toolCallSummaryTemplate = "调用 {tool}",
        toolResultSummaryTemplate = "{tool} 已返回",
        toolResultFailedSummaryTemplate = "{tool} 调用失败",
        toolUnknownLabel = "未知工具",
        toolArgsTitle = "调用参数（协议原文）",
        toolResultTitle = "返回内容（协议原文）",
        expandRaw = "展开原文",
        collapseRaw = "收起原文",
        copyRaw = "复制原文",
        rawCharsTemplate = "{count} 字符",
        usageShortTemplate = "输入 {input} · 输出 {output} · {requests} 次请求",
        modelUnknown = "未选择模型",
        providerUnknown = "未配置提供方",
        copyMessage = "复制消息",
        copied = "已复制到剪贴板",
        modelPanelEntry = "模型与工具",
        diagnosticsEntry = "诊断",
        sidebarHide = "收起信息栏",
        sidebarShow = "展开信息栏",
        streamingHint = "正在生成",
        replyLanguage = "中文",
        sessionBusy = "正在处理本次请求，请先停止再切换会话"
    )

    val english = AgentChatText(
        title = "Agent Chat",
        subtitleIdle = "Ask about your shelf, translation jobs and glossary",
        subtitleRunning = "Working on it",
        inputPlaceholder = "Type a message, e.g. what books are on my shelf?",
        send = "Send",
        cancel = "Stop",
        newChat = "New chat",
        back = "Back",
        switchSession = "Switch conversation",
        thinking = "Thinking or calling tools",
        emptyTitle = "Start a conversation",
        emptyDescription = "The agent can look up books and translation jobs, and run translation actions after you confirm.",
        youLabel = "You",
        assistantLabel = "Assistant",
        cancelled = "Cancelled this request.",
        notConfigured = "No model provider selected yet. Configure one in Settings first.",
        requestFailedTemplate = "Request failed: {reason}",
        turnFailedTemplate = "This turn failed: {reason}",
        emptyReply = "(The model returned no content)",
        questionAnswerPlaceholder = "Type your answer",
        questionSkip = "Skip and continue with defaults",
        toolCallSummaryTemplate = "Calling {tool}",
        toolResultSummaryTemplate = "{tool} returned",
        toolResultFailedSummaryTemplate = "{tool} failed",
        toolUnknownLabel = "unknown tool",
        toolArgsTitle = "Arguments (raw protocol)",
        toolResultTitle = "Result (raw protocol)",
        expandRaw = "Show raw",
        collapseRaw = "Hide raw",
        copyRaw = "Copy raw",
        rawCharsTemplate = "{count} chars",
        usageShortTemplate = "In {input} · Out {output} · {requests} requests",
        modelUnknown = "No model selected",
        providerUnknown = "No provider configured",
        copyMessage = "Copy message",
        copied = "Copied to clipboard",
        modelPanelEntry = "Model & tools",
        diagnosticsEntry = "Diagnostics",
        sidebarHide = "Hide info panel",
        sidebarShow = "Show info panel",
        streamingHint = "Generating",
        replyLanguage = "English",
        sessionBusy = "Still working on this request; stop it before switching conversations"
    )

    /** 与全局语言保持一致：英文界面用英文文案，其余按中文（应用默认中文）。 */
    fun resolve(appStrings: AppStrings): AgentChatText =
        if (appStrings === EnglishStrings) english else chinese
}

/**
 * 把界面文案映射成会话层的系统消息文案（取消、失败、未配置提供方等）。
 * 控制器默认中文；需要跟随界面语言时由集成层传入映射结果。
 */
fun AgentChatText.toSessionTexts(): AgentSessionTexts = AgentSessionTexts(
    cancelled = cancelled,
    requestFailed = requestFailedTemplate,
    turnFailed = turnFailedTemplate,
    noProvider = notConfigured,
    emptyReply = emptyReply
)
