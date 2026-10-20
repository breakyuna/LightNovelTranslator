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
import com.breakyuna.noveltranslator.core.agent.AgentToolSpec
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.LlmStreamChunk
import com.breakyuna.noveltranslator.core.llm.LlmToolCall
import com.breakyuna.noveltranslator.core.llm.LlmToolSpec
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.llm.UsageSource
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.model.ProviderType
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
 * 关键约束：`LlmGateway.executeCompletion(LlmRequest)` 是单轮请求-响应，因此工具调用有两条路：
 * 1. **原生 function calling**（默认开启，`AgentSessionConfig.nativeToolsEnabled`）：请求携带
 *    tools 数组（仅 OpenAI 兼容形状的 provider），由 API 强制调用格式；响应里的 tool_calls
 *    被转换回文本协议调用块，后续执行/回执/历史与文本协议完全同路。provider 以 400 拒绝
 *    tools 时 LlmClient 自动去掉 tools 重试，回合不中断。
 * 2. **文本工具协议**（兜底，见 [AgentToolProtocol]）：提示词里描述协议，模型输出调用块。
 *
 * 两条路都先把 Koog 的多消息 [Prompt] 展平成 system + user 两段文本；历史里因此不存在
 * 原生 tool_calls 消息，也就不需要回传 reasoning_content，多轮对便宜模型更宽容。
 *
 * 提供方、模型、温度、Token 上限等在 [AgentSessionConfig] 中固定，取消信号经
 * [signalProvider] 动态读取，避免取消后执行器持有已失效的旧信号。
 *
 * 流式：配置开启且注入了 [streamObserver] 时，[execute] 改走
 * [LlmGateway.executeStreaming]，把文本增量同步送给观察者，同时本地累积出与非流式
 * 同构的 [LlmResult]。Koog 的回合循环、工具协议、计费与落库全部不变——流式只改变
 * "用户何时看到字"，不改变"回合何时结束"。
 */
class LntPromptExecutor(
    private val gateway: LlmGateway,
    private val config: AgentSessionConfig,
    /** Agent 工具目录：nativeToolsEnabled 时映射为 provider 原生 tools 数组。 */
    private val toolSpecs: List<AgentToolSpec> = emptyList(),
    private val signalProvider: () -> TranslationControlSignal? = { null },
    /**
     * 流式增量观察者。非空且 [AgentSessionConfig.streamingEnabled] 为真时启用流式；
     * 由会话层注入，负责把增量文本送进 UI 状态。
     */
    private val streamObserver: ((String) -> Unit)? = null
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
        // 原生 function calling：仅 OpenAI 兼容形状的 provider 携带 tools 数组，
        // 由 API 强制调用格式；不支持的 provider 由 LlmClient 400 自动降级为文本协议。
        val nativeTools = if (config.nativeToolsEnabled && supportsNativeTools(provider.providerType)) {
            toolSpecs
                .map { LlmToolSpec(name = it.name, description = it.description, parametersJson = it.parametersSchemaJson) }
                .takeIf { specs -> specs.isNotEmpty() }
        } else {
            null
        }
        val llmRequest = LlmRequest(
            provider = provider,
            systemPrompt = flattened.system,
            userPrompt = flattened.user,
            temperature = config.effectiveAgentTemperature,
            maxTokens = config.maxTokens,
            operation = config.operation,
            controlSignal = signalProvider(),
            reasoningEffort = config.reasoningEffort,
            tools = nativeTools
        )
        val result = if (streamObserver != null && config.streamingEnabled) {
            executeStreamingRequest(llmRequest)
        } else {
            gateway.executeCompletion(llmRequest)
        }
        // 原生 tool_calls 转换回文本协议调用块，交给同一套回合循环（执行/回执/历史不变）。
        val callBlocks = result.toolCalls
            .filter { it.name.isNotBlank() }
            .joinToString(separator = "\n") { call ->
                AgentToolProtocol.TOOL_CALL_OPEN +
                    """{"name":"${call.name}","arguments":${call.argumentsJson}}""" +
                    AgentToolProtocol.TOOL_CALL_CLOSE
            }
        val text = when {
            callBlocks.isBlank() -> result.text
            result.text.isBlank() -> callBlocks
            else -> result.text + "\n" + callBlocks
        }
        if (!result.isSuccess || text.isBlank()) {
            val failure = if (text.isBlank() && result.isSuccess) {
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
                "耗时=${result.durationMs}ms" +
                if (result.toolCalls.isEmpty()) "" else " 原生工具调用=${result.toolCalls.size}"
        )
        return Message.Assistant(
            content = text,
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

    /**
     * 走 SSE 完成同一次请求：文本增量同步送给 [streamObserver]，同时本地累积出与非流式
     * 同构的 [LlmResult]（文本、原生工具调用、用量）。
     *
     * 兜底规则：
     * - 明确的失败（含 HTTP 层失败）直接回传，由上层决定重试或结束回合；
     * - 流给出了增量但没有结束事件时，按已收到的文本估算用量判成功，不丢掉已生成的内容；
     * - 流一个增量都没有（例如 gateway 未实现 SSE 却也没发文本）才整段回退非流式。
     *
     * 取消会以 [kotlinx.coroutines.CancellationException] 原样抛出，与 Koog 的回合取消一致。
     */
    private suspend fun executeStreamingRequest(request: LlmRequest): LlmResult {
        val observer = streamObserver ?: return gateway.executeCompletion(request)
        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        var streamed = false
        var completed: LlmResult? = null
        var failure: LlmResult? = null

        gateway.executeStreaming(request).collect { chunk ->
            when (chunk) {
                is LlmStreamChunk.TextDelta -> {
                    streamed = true
                    text.append(chunk.text)
                    observer(chunk.text)
                }
                is LlmStreamChunk.ToolCalls -> toolCalls.addAll(chunk.calls)
                is LlmStreamChunk.Completed -> completed = chunk.result
                is LlmStreamChunk.Failed -> failure = chunk.result
            }
        }

        failure?.let { return it }
        completed?.let {
            return it.copy(text = text.toString(), toolCalls = toolCalls)
        }
        if (!streamed) return gateway.executeCompletion(request)
        return LlmResult(
            text = text.toString(),
            promptTokens = TokenCalculator.estimateTokens(request.systemPrompt + request.userPrompt),
            completionTokens = TokenCalculator.estimateTokens(text.toString()),
            isSuccess = true,
            usageSource = UsageSource.ESTIMATED,
            operation = request.operation,
            toolCalls = toolCalls
        )
    }

    /**
     * 对话式编排的回合由 [execute] 驱动（流式在 [execute] 内部完成），Koog 的
     * `executeStreaming` 入口不参与：显式失败，避免调用方误以为支持流式。
     */
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow {
        throw UnsupportedOperationException("LNT Agent 编排的流式在 execute 内部完成，不经过 Koog executeStreaming")
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

    /**
     * 是否走原生 tools：只有 OpenAI 兼容请求形状的 provider 支持（LlmClient 的
     * callOpenAiCompatible）；Anthropic 与 Gemini 的 tools 形状未接线，保持文本协议。
     */
    private fun supportsNativeTools(providerType: ProviderType): Boolean =
        providerType != ProviderType.ANTHROPIC_CLAUDE && providerType != ProviderType.GEMINI_DIRECT

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
