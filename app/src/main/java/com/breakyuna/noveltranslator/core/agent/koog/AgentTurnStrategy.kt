package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.agents.core.agent.AIAgentFunctionalStrategy
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.prompt.message.Message
import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.agent.AgentToolOutcome
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import kotlinx.coroutines.CancellationException

/**
 * 构造 LNT 对话 Agent 的功能策略：一次用户输入到最终答复为一个回合。
 *
 * 回合内的工具循环完全走文本协议（见 [AgentToolProtocol]）：
 * 1. 先把此前历史按角色灌入本轮全新的 LLM 会话（Koog 每次 run 都从初始 prompt 开始）；
 * 2. 请求模型，解析回复中的工具调用块；
 * 3. 有调用就经 [AgentToolHost] 的顺序执行（翻译等副作用必须串行），把结果作为用户消息送回；
 * 4. 没有调用即最终答复，回合结束。
 *
 * 每个回合的工具轮数、历史长度、压缩阈值都来自 [AgentSessionConfig]；
 * 取消信号在每一轮工具执行前检查，保证“暂停/取消后不再发起新的付费请求”。
 */
fun lntAgentStrategy(
    toolHost: AgentToolHost,
    sink: AgentTurnSink,
    config: AgentSessionConfig,
    signalProvider: () -> TranslationControlSignal?,
    historySeed: () -> List<AgentMessage>
): AIAgentFunctionalStrategy<String, AgentTurnResult> =
    functionalStrategy<String, AgentTurnResult>("lnt-agent-chat") { input ->

        // 本地挂起函数：发送一次请求并回传用量。message 为 null 时沿用当前 prompt 直接请求。
        suspend fun requestWithUsage(message: String?): Message.Assistant {
            val response = if (message == null) {
                llm.writeSession { requestLLM() }
            } else {
                requestLLM(message)
            }
            sink.onUsage(
                response.metaInfo.inputTokensCount ?: 0,
                response.metaInfo.outputTokensCount ?: 0
            )
            return response
        }

        // 本地挂起函数：把历史消息按协议原样灌入会话。
        suspend fun seedHistory(prior: List<AgentMessage>) {
            if (prior.isEmpty()) return
            appendPrompt {
                prior.forEach { message ->
                    when (message.role) {
                        AgentRole.USER -> user(message.content)
                        AgentRole.ASSISTANT -> assistant(message.content)
                        AgentRole.TOOL_CALL -> assistant(message.content)
                        AgentRole.TOOL_RESULT -> user(message.content)
                        AgentRole.SYSTEM -> user(message.content)
                    }
                }
            }
        }

        suspend fun executeOne(call: ParsedToolCall): ToolExecutionRecord {
            val outcome = try {
                toolHost.executeTool(call.name, call.argumentsJson)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // 工具异常不得中断回合：转成模型可见的失败回执，让它修正或说明限制。
                AgentToolOutcome.failure(
                    "tool_crashed",
                    error.localizedMessage ?: error.javaClass.simpleName
                )
            }
            return ToolExecutionRecord(
                name = call.name,
                argumentsJson = call.argumentsJson,
                ok = outcome.ok,
                output = outcome.output
            )
        }

        seedHistory(historySeed())

        var response = requestWithUsage(input)
        var rounds = 0
        var finalText = ""
        var compressed = false

        while (true) {
            signalProvider()?.let { signal ->
                if (signal.isCancelled) throw CancellationException("Agent turn cancelled")
            }
            val parsed = AgentToolProtocol.parseAssistantResponse(response.textContent())
            finalText = parsed.visibleText

            if (parsed.calls.isEmpty()) {
                if (parsed.malformedBlocks.isNotEmpty() && rounds < config.maxToolRounds) {
                    // 只给一次格式修正机会 per malformed batch，仍然计入轮次。
                    appendPrompt { user(AgentToolProtocol.formatProtocolError(parsed.malformedBlocks)) }
                    response = requestWithUsage(null)
                    rounds++
                    continue
                }
                break
            }

            if (rounds >= config.maxToolRounds) {
                appendPrompt { user(AgentToolProtocol.formatRoundLimitNotice(config.maxToolRounds)) }
                response = requestWithUsage(null)
                finalText = AgentToolProtocol.parseAssistantResponse(response.textContent()).visibleText
                break
            }

            val records = mutableListOf<ToolExecutionRecord>()
            for (call in parsed.calls) {
                sink.onToolCall(call.name, call.argumentsJson, call.rawBlock)
                val record = executeOne(call)
                sink.onToolResult(record.name, record.ok, record.output)
                records += record
            }
            appendPrompt { user(AgentToolProtocol.formatToolResults(records)) }
            response = requestWithUsage(null)
            rounds++

            if (!compressed) {
                // 压缩本身是一次付费请求，只在会话明显变长时触发一次。
                val messageCount = llm.readSession { prompt.messages.size }
                if (messageCount > config.compressAfterMessages) {
                    compressHistory(HistoryCompressionStrategy.WholeHistory, preserveMemory = false)
                    compressed = true
                    sink.onCompressed()
                }
            }
        }

        AgentTurnResult(finalText = finalText, toolRounds = rounds)
    }

/** 按用户回合数截断历史：只保留最近 [maxTurns] 个用户回合及其后的全部消息。 */
internal fun truncateToTurns(messages: List<AgentMessage>, maxTurns: Int): List<AgentMessage> {
    if (maxTurns <= 0) return emptyList()
    val userIndices = messages.withIndex()
        .filter { it.value.role == AgentRole.USER }
        .map { it.index }
    if (userIndices.size <= maxTurns) return messages
    val startIndex = userIndices[userIndices.size - maxTurns]
    return messages.subList(startIndex, messages.size).toList()
}
