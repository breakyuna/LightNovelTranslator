package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity

/** 消息角色，取值与 agent_messages 表的 role 列一一对应。 */
enum class AgentRole {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    SYSTEM;

    val storageKey: String get() = name

    companion object {
        fun fromStorageKey(key: String): AgentRole =
            entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/**
 * 一条对话消息。工具调用与工具结果各自独立成条，content 保存送入模型的协议原文，
 * 从而可以按顺序精确重建发送给模型的历史。
 */
data class AgentMessage(
    val id: Long = 0L,
    val role: AgentRole,
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResultJson: String? = null,
    val isError: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val createdAt: Long = 0L
)

/** 会话累计用量。失败调用同样计入（LlmGateway 已按尝试累计）。 */
data class AgentUsage(
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val requestCount: Int = 0
)

/**
 * 一次会话的运行配置。[provider] 由应用层经 ApiProviderRepository 边界解析后注入，
 * 携带解密后的 API Key，不得写入日志或提示词。
 */
data class AgentSessionConfig(
    val provider: ApiProviderEntity? = null,
    val modelName: String = "",
    val temperature: Float = 0.4f,
    val maxTokens: Int = 2048,
    val reasoningEffort: String? = null,
    /** 单回合最大工具调用轮数，同时写入协议提示词。 */
    val maxToolRounds: Int = 6,
    /** 跨回合注入历史的用户回合数上限，超出后丢弃最旧回合。 */
    val maxHistoryTurns: Int = 12,
    /** 回合内消息数超过该值时触发一次 Koog 历史压缩（压缩本身是一次付费请求）。 */
    val compressAfterMessages: Int = 24,
    /** 展平后用户侧文本的字符预算，超出后保留头尾并标注截断。 */
    val maxTranscriptChars: Int = 60_000,
    val operation: String = "AGENT_CHAT"
) {
    val effectiveModelName: String
        get() = modelName.ifBlank { provider?.selectedModel.orEmpty() }

    val effectiveTemperature: Float
        get() = temperature.takeIf { it > 0f } ?: (provider?.temperature ?: 0.4f)
}

/** 回合执行过程中的事件回流，由会话实现并落库/更新 UI。 */
interface AgentTurnSink {
    /** [rawBlock] 为模型原始调用块文本（可能为空），供历史原样重建。 */
    suspend fun onToolCall(name: String, argumentsJson: String, rawBlock: String)

    suspend fun onToolResult(name: String, ok: Boolean, resultJson: String)

    /** 每次 LLM 请求成功后回调，携带本次用量。 */
    suspend fun onUsage(promptTokens: Int, completionTokens: Int)

    /** 触发历史压缩时回调一次。 */
    suspend fun onCompressed()
}

/** 一个回合（一次用户输入到最终答复）的结果。 */
data class AgentTurnResult(
    val finalText: String,
    val toolRounds: Int
)

/** 会话内系统消息使用的文案，默认中文，可由 UI 层替换为当前语言。 */
data class AgentSessionTexts(
    val cancelled: String = "已取消本次请求。",
    val requestFailed: String = "请求失败：{reason}",
    val turnFailed: String = "本次对话未能完成：{reason}",
    val noProvider: String = "尚未选择可用的模型提供方，请先在设置中配置。",
    val emptyReply: String = "（模型没有返回可用内容）"
)

/** UI 可直接渲染的会话状态。 */
data class AgentSessionState(
    val sessionId: Long? = null,
    val title: String = "",
    val messages: List<AgentMessage> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
    val usage: AgentUsage = AgentUsage()
)
