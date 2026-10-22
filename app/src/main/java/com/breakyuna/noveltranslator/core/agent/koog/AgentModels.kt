package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.llm.LlmResult
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
    /**
     * 对话编排回合使用的温度。
     *
     * 格式服从性对温度高度敏感（调用块输出、协议解析都依赖模型严格遵守格式），
     * 因此编排温度不应跟随用户为翻译配置的 [temperature]：集成层
     * （AppViewModel）默认注入 0.2，并统一收敛到 0.0..0.5
     * （见 [effectiveAgentTemperature]）。null 表示未显式配置，跟随
     * [effectiveTemperature]（兼容既有调用方与测试）。
     */
    val agentTemperature: Float? = null,
    /**
     * 是否启用原生 function calling（provider 支持时）。
     *
     * 关闭时完全走文本工具协议；开启时请求携带 tools 数组，由 API 强制调用格式，
     * 执行器把原生 tool_calls 转换回文本协议块交给同一套回合循环（见 LntPromptExecutor）。
     * provider 不支持导致 400 时 LlmClient 自动去掉 tools 重试，不会打断回合。
     */
    val nativeToolsEnabled: Boolean = false,
    /**
     * 是否启用流式输出。
     *
     * 开启时执行器优先走 SSE，把模型生成的文本增量经流式预览送到界面；provider 不支持
     * 或流没有产出任何增量时自动整段回退非流式，回合内容、工具协议与计费口径都不变。
     * 关闭时行为与此前完全一致（一次性拿到完整答复）。
     */
    val streamingEnabled: Boolean = true,
    /** 单回合最大工具调用轮数，同时写入协议提示词。 */
    val maxToolRounds: Int = 6,
    /** 跨回合注入历史的用户回合数上限，超出后丢弃最旧回合。 */
    val maxHistoryTurns: Int = 12,
    /** 回合内消息数超过该值时触发一次 Koog 历史压缩（压缩本身是一次付费请求）。 */
    val compressAfterMessages: Int = 24,
    /** 展平后用户侧文本的字符预算，超出后保留头尾并标注截断。 */
    val maxTranscriptChars: Int = 60_000,
    /**
     * 对话回复语言（展示名，如 "中文" / "English"）。
     *
     * 写进运行环境段交给模型；此前是硬编码 "中文"，英文界面下用户会拿到中文答复。
     * 由集成层按界面语言注入，默认中文只用于没有 UI 上下文的调用方与测试。
     */
    val replyLanguage: String = "中文",
    /**
     * 每次 LLM 请求结束后回调（含失败），携带用量与错误分类。
     *
     * 供集成层把 Agent 对话的 Token / 费用写进审计日志——这条付费路径此前完全无记录。
     * 回调发生在回合协程内，必须轻量且不得抛异常；只传 [LlmResult]，不含提示词或正文。
     */
    val onRequestUsage: ((LlmResult) -> Unit)? = null,
    val operation: String = "AGENT_CHAT"
) {
    val effectiveModelName: String
        get() = modelName.ifBlank { provider?.selectedModel.orEmpty() }

    val effectiveTemperature: Float
        get() = temperature.takeIf { it > 0f } ?: (provider?.temperature ?: 0.4f)

    /** 编排回合实际下发的 temperature：显式配置时收敛到 0.0..0.5，否则跟随 [effectiveTemperature]。 */
    val effectiveAgentTemperature: Float
        get() = agentTemperature?.coerceIn(0f, AGENT_TEMPERATURE_MAX) ?: effectiveTemperature

    private companion object {
        const val AGENT_TEMPERATURE_MAX = 0.5f
    }
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

    /** 一次模型请求开始前回调：清空上一轮遗留的流式预览。 */
    suspend fun onStreamReset() { /* 默认不处理流式预览 */ }

    /**
     * 一次模型请求结束后回调：真实消息（工具行 / 最终答复）已接管展示，
     * 流式预览必须让位，避免同一段文本出现两份。
     */
    suspend fun onStreamSettled() { /* 默认不处理流式预览 */ }
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
    val usage: AgentUsage = AgentUsage(),
    /**
     * 当前正在流式生成的原文。
     *
     * 只在一次模型请求的生命周期内有效：请求结束（工具行 / 最终答复接管展示）即清空，
     * 因此它永远不是 [messages] 的一部分，也不会被持久化。
     */
    val streamingText: String = ""
)
