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
 * 本轮加固新增两条防线：
 * - **相同调用熔断**：同一回合内（工具名 + 归一化参数）签名第 2 次起不再执行、不再付费，
 *   直接回执 `duplicate_call`，避免弱模型反复发起同一个调用把额度耗光；
 * - **静默失败纠正**：模型想调工具但格式没解析出来时，先回一次格式纠正（每回合一次），
 *   仍失败才把回复当最终答复，修掉“调用块从未执行却无声结束”的故障形态。
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

        // 已知工具名集合：供散文里的 JSON 块提取与“疑似调用”检测使用。
        val knownToolNames: Set<String> = toolHost.toolSpecs().map { it.name }.toSet()

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

        // 同一回合内已执行的 (工具名 + 归一化参数) 签名，用于相同调用熔断。
        val executedSignatures = mutableSetOf<String>()
        // 静默失败纠正每回合只给一次：修不掉就降级为答复，不再继续烧请求。
        var silentFailureCorrected = false

        var response = requestWithUsage(input)
        var rounds = 0
        var finalText = ""
        var compressed = false

        while (true) {
            signalProvider()?.let { signal ->
                if (signal.isCancelled) throw CancellationException("Agent turn cancelled")
            }
            val parsed = AgentToolProtocol.parseAssistantResponse(response.textContent(), knownToolNames)
            finalText = parsed.visibleText

            if (parsed.calls.isEmpty()) {
                if (rounds < config.maxToolRounds) {
                    // 没有可执行调用时先尝试纠正：标签匹配但 JSON 坏（malformed）按批纠正；
                    // 完全零调用但“疑似想调用”（含标签痕迹 / name+arguments 结构 / 已知工具名）
                    // 也纠正一次，避免静默失败把调用块当最终答复显示给用户。
                    val correction = when {
                        parsed.malformedBlocks.isNotEmpty() ->
                            AgentToolProtocol.formatProtocolError(parsed.malformedBlocks)

                        !silentFailureCorrected -> {
                            val suspected = AgentToolProtocol.detectSuspectedToolCall(
                                response.textContent(),
                                knownToolNames
                            )
                            if (suspected == null) {
                                null
                            } else {
                                silentFailureCorrected = true
                                AgentToolProtocol.formatSuspectedToolCallError(suspected)
                            }
                        }

                        else -> null
                    }
                    if (correction != null) {
                        appendPrompt { user(correction) }
                        response = requestWithUsage(null)
                        rounds++
                        continue
                    }
                }
                break
            }

            if (rounds >= config.maxToolRounds) {
                appendPrompt { user(AgentToolProtocol.formatRoundLimitNotice(config.maxToolRounds)) }
                response = requestWithUsage(null)
                finalText = AgentToolProtocol.parseAssistantResponse(
                    response.textContent(),
                    knownToolNames
                ).visibleText
                break
            }

            val records = mutableListOf<ToolExecutionRecord>()
            for (call in parsed.calls) {
                sink.onToolCall(call.name, call.argumentsJson, call.rawBlock)
                val signature = AgentToolProtocol.toolCallSignature(call.name, call.argumentsJson)
                if (!executedSignatures.add(signature)) {
                    // 相同调用熔断：本回合已执行过同样的调用，不再执行、不再付费。
                    val duplicate = AgentToolOutcome.failure(
                        "duplicate_call",
                        "该调用本回合已执行过，请基于已获得的回执继续，不要重复相同调用。"
                    )
                    sink.onToolResult(call.name, duplicate.ok, duplicate.output)
                    records += ToolExecutionRecord(
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        ok = duplicate.ok,
                        output = duplicate.output
                    )
                    continue
                }
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

        // 清洗思考块与残留的调用/回执原始块；空白交由会话层的“空答复”兜底。
        AgentTurnResult(
            finalText = AgentToolProtocol.cleanFinalText(finalText),
            toolRounds = rounds
        )
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
