package com.breakyuna.noveltranslator.core.agent.koog

/**
 * 一次解析出的工具调用。[argumentsJson] 一定是合法的 JSON 对象文本（可能为 `{}`）。
 */
data class ParsedToolCall(
    val name: String,
    val argumentsJson: String,
    /** 模型原始调用块文本；由裸 JSON 恢复时为空，由编排层按协议重建。 */
    val rawBlock: String = ""
)

/** 助手回复的协议解析结果。 */
data class ParsedAssistantResponse(
    /** 按出现顺序排列的合法工具调用；为空表示模型给出了最终答复（或只有格式错误）。 */
    val calls: List<ParsedToolCall>,
    /** 无法解析的工具调用块说明，用于让模型自我修正而不是静默丢弃。 */
    val malformedBlocks: List<String>,
    /** 去掉工具调用块后剩余的自然语言文本。 */
    val visibleText: String
)

/** 一次工具执行的完整记录，用于渲染回执与持久化。 */
data class ToolExecutionRecord(
    val name: String,
    val argumentsJson: String,
    val ok: Boolean,
    val output: String
)
