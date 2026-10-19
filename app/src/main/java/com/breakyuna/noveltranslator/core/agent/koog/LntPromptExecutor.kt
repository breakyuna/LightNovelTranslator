package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/**
 * LLM 请求失败时抛出，携带分类与可展示原因；由 [AgentSession] 捕获后写入系统消息。
 * 只携带错误类别与消息，不携带提示词或正文，避免敏感内容进入 UI 与日志。
 */
class AgentLlmFailureException(val result: LlmResult) : Exception(
    result.errorMessage?.takeIf { it.isNotBlank() } ?: "模型请求失败（${result.errorCategory ?: LlmErrorCategory.UNKNOWN}）"
)

/**
 * 把 Koog 的 [PromptExecutor] 桥接到 LNT 现有云 provider 体系。
 *
 * 关键约束：`LlmGateway.executeCompletion(LlmRequest)` 是单轮请求-响应，没有 tools 参数，
 * 因此这里不做原生 function calling，只负责两件事：
 * 1. 把 Koog 的多消息 [Prompt] 展平成 system + user 两段文本（工具目录已在 system prompt 中，
 *    工具调用与结果以文本协议形式存在于消息历史里）；
 * 2. 把 [LlmResult] 转换回 [Message.Assistant]，并带上 Token 用量供 Koog 统计。
 *
 * 提供方、模型、温度、Token 上限等在 [AgentSessionConfig] 中固定，取消信号经
 * [signalProvider] 动态读取，避免取消后执行器持有已失效的旧信号。
 */
class LntPromptExecutor(
    private val gateway: LlmGateway,
    private val config: AgentSessionConfig,
    private val signalProvider: () -> TranslationControlSignal? = { null }
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val provider = requireProvider()
        val flattened = LntPromptMapper.flatten(
            prompt = prompt,
            maxTranscriptChars = config.maxTranscriptChars,
            maxContextTokens = provider.maxContextTokens
        )
        val result = gateway.executeCompletion(
            LlmRequest(
                provider = provider,
                systemPrompt = flattened.system,
                userPrompt = flattened.user,
                temperature = config.effectiveTemperature,
                maxTokens = config.maxTokens,
                operation = config.operation,
                controlSignal = signalProvider(),
                reasoningEffort = config.reasoningEffort
            )
        )
        if (!result.isSuccess || result.text.isBlank()) {
            val failure = if (result.text.isBlank() && result.isSuccess) {
                result.copy(
                    isSuccess = false,
                    errorCategory = LlmErrorCategory.EMPTY_RESPONSE,
                    errorMessage = "模型返回了空内容"
                )
            } else {
                result
            }
            SystemLogger.warn(
                TAG,
                "Agent 请求失败: 模型=${config.effectiveModelName} 分类=${failure.errorCategory}"
            )
            throw AgentLlmFailureException(failure)
        }
        SystemLogger.info(
            TAG,
            "Agent 请求完成: 模型=${config.effectiveModelName} " +
                "输入Token=${result.promptTokens} 输出Token=${result.completionTokens} " +
                "耗时=${result.durationMs}ms"
        )
        return Message.Assistant(
            content = result.text,
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.DISTANT_PAST,
                totalTokensCount = tokensToInt(result.promptTokens + result.completionTokens),
                inputTokensCount = tokensToInt(result.promptTokens),
                outputTokensCount = tokensToInt(result.completionTokens),
                modelId = model.id
            ),
            finishReason = result.finishReason
        )
    }

    /** 对话式编排不使用流式返回；显式失败，避免调用方误以为支持流式。 */
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow {
        throw UnsupportedOperationException("LNT Agent 编排不支持流式执行")
    }

    /** 未接入内容审核，显式失败而不是返回虚构结果。 */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("LNT Agent 编排不支持内容审核")

    override suspend fun models(): List<LLModel> = emptyList()

    override fun close() {
        // 执行器不持有需要释放的资源；LlmGateway 的生命周期由应用层管理。
    }

    private fun requireProvider(): com.breakyuna.noveltranslator.data.model.ApiProviderEntity =
        config.provider ?: error("Agent 会话缺少模型提供方配置")

    private fun tokensToInt(tokens: Long): Int = tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val TAG = "AGENT_CHAT"
    }
}

/** Prompt 展平结果：system 段与按时间排序的用户侧对话文本。 */
internal data class FlattenedPrompt(val system: String, val user: String)

internal object LntPromptMapper {

    private const val DEFAULT_SYSTEM = "你是 LightNovelTranslator 的内置助手。"

    /** Agent 提示词固定格式（角色标签、协议说明）之外的估计开销。 */
    private const val FORMAT_OVERHEAD_TOKENS = 1_200L

    /**
     * 展平 Prompt，并按 provider 上下文预算截断用户侧历史。
     *
     * 预算口径与翻译引擎一致：用 [TokenCalculator.calculateChunkBudget] 从
     * `maxContextTokens` 推出可用输入 token，再扣除 system prompt 与固定格式开销，
     * 避免把超长对话塞进小上下文 provider 触发上下文溢出。
     */
    fun flatten(prompt: Prompt, maxTranscriptChars: Int, maxContextTokens: Int): FlattenedPrompt {
        val system = prompt.messages
            .filterIsInstance<Message.System>()
            .flatMap { message -> message.parts.map { part -> part.text } }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
            .ifBlank { DEFAULT_SYSTEM }
        val transcript = StringBuilder()
        prompt.messages.forEach { message ->
            when (message) {
                is Message.System -> Unit
                is Message.User -> transcript.appendRole("用户", renderUserMessage(message))
                is Message.Assistant -> transcript.appendRole("助手", renderAssistantMessage(message))
            }
        }
        val systemTokens = TokenCalculator.estimateTokens(system)
        val transcriptBudget = TokenCalculator.calculateChunkBudget(
            maxContextTokens = maxContextTokens,
            overheadEstimate = FORMAT_OVERHEAD_TOKENS + systemTokens
        )
        return FlattenedPrompt(
            system = system,
            user = capTranscript(transcript.toString().trim(), maxTranscriptChars, transcriptBudget)
        )
    }

    private fun StringBuilder.appendRole(role: String, text: String) {
        if (text.isBlank()) return
        append(role).append("：").append(text).appendLine()
    }

    private fun renderUserMessage(message: Message.User): String {
        val builder = StringBuilder()
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> builder.append(part.text)
                is MessagePart.Tool.Result -> {
                    if (builder.isNotEmpty()) builder.appendLine()
                    builder.append(part.tool).append(" 结果：").append(part.output)
                }
                else -> Unit
            }
        }
        return builder.toString().trim()
    }

    private fun renderAssistantMessage(message: Message.Assistant): String {
        val builder = StringBuilder()
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> builder.append(part.text)
                // 防御性渲染：Koog 原生工具调用不会出现在本编排的历史里（工具走文本协议），
                // 一旦出现就按协议文本透出，避免调用痕迹被静默丢弃。
                is MessagePart.Tool.Call -> {
                    if (builder.isNotEmpty()) builder.appendLine()
                    builder.append(part.tool).append(" 调用参数：").append(part.args)
                }
                else -> Unit
            }
        }
        return builder.toString().trim()
    }

    /** 超长时保留头尾：系统提示通常最先被读，最新上下文对续跑最关键。 */
    private fun capTranscript(text: String, maxChars: Int, maxTokens: Long): String {
        var result = text
        if (result.length > maxChars) {
            result = result.take(maxChars / 4) + truncationMarker(result.length) + result.takeLast(maxChars - maxChars / 4)
        }
        if (maxTokens <= 0L) return result
        val estimated = TokenCalculator.estimateTokens(result)
        if (estimated <= maxTokens) return result
        // 按估算比例换算目标字符数，再头尾保留；比例截断是近似值，宁可少留不多塞。
        val targetChars = (result.length * (maxTokens.toDouble() / estimated.toDouble())).toInt()
        if (targetChars <= 0) return truncationMarker(estimated) + "（历史已清空）"
        return result.take(targetChars / 4) + truncationMarker(estimated) + result.takeLast(targetChars - targetChars / 4)
    }

    private fun truncationMarker(size: Number): String =
        "\n…[中间历史已截断，原规模 " + size + "]…\n"
}
