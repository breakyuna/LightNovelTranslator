package com.breakyuna.noveltranslator.ui.i18n

import androidx.compose.runtime.Composable

/**
 * Strings for the assistant (agent) entry points: the settings row, the
 * workbench app-bar action and the chat host chrome (back, session switcher).
 *
 * The chat transcript itself keeps its own table ([com.breakyuna.noveltranslator.ui.screens.agent.AgentChatStrings]),
 * which already resolves Chinese/English from the global language.
 */
data class AgentEntryStrings(
    val entryTitle: String,
    val entrySubtitle: String,
    val entryOpenDescription: String,
    val sessionsTitle: String,
    val sessionsSubtitle: String,
    val sessionsNew: String,
    val sessionsEmpty: String,
    val sessionsUntitled: String,
    val sessionsCurrent: String,
    val sessionsCount: (Int) -> String,
    val sessionsUpdatedTemplate: (String) -> String,
    val sessionsArchiveUnavailable: String
)

private val ChineseAgentEntryStrings = AgentEntryStrings(
    entryTitle = "AI 助手",
    entrySubtitle = "对话式查询书架、翻译任务与术语，确认后执行操作",
    entryOpenDescription = "打开 AI 助手",
    sessionsTitle = "对话会话",
    sessionsSubtitle = "切换到历史会话，或开始新的对话",
    sessionsNew = "新对话",
    sessionsEmpty = "还没有历史会话，发起第一段对话吧。",
    sessionsUntitled = "未命名会话",
    sessionsCurrent = "当前会话",
    sessionsCount = { count -> "$count 段会话" },
    sessionsUpdatedTemplate = { time -> "更新于 $time" },
    sessionsArchiveUnavailable = "归档需要先在数据层提供入口，当前只能新建或切换会话。"
)

private val EnglishAgentEntryStrings = AgentEntryStrings(
    entryTitle = "AI assistant",
    entrySubtitle = "Ask about your shelf, translation jobs and glossary, then confirm actions",
    entryOpenDescription = "Open the AI assistant",
    sessionsTitle = "Conversations",
    sessionsSubtitle = "Switch to a previous conversation or start a new one",
    sessionsNew = "New chat",
    sessionsEmpty = "No conversations yet. Start the first one.",
    sessionsUntitled = "Untitled conversation",
    sessionsCurrent = "Current",
    sessionsCount = { count -> "$count conversation(s)" },
    sessionsUpdatedTemplate = { time -> "Updated $time" },
    sessionsArchiveUnavailable = "Archiving needs a data-layer entry point first; for now you can only start or switch conversations."
)

@Composable
fun agentEntryStrings(): AgentEntryStrings =
    if (LocalAppStrings.current === EnglishStrings) EnglishAgentEntryStrings else ChineseAgentEntryStrings
