package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.FunctionalAIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/**
 * 一个对话会话：持有 Koog Agent、历史快照与取消信号，负责把一轮用户输入跑成最终答复。
 *
 * 生命周期约定：
 * - 每个会话一个实例；[send] 串行执行，运行中忽略新的输入（UI 负责禁用输入框）；
 * - [cancel] 只置取消信号：已发出的 HTTP 请求会自然结束，但不会有新的付费请求，
 *   与翻译引擎的暂停/取消边界一致；
 * - 历史跨回合保留在 [store] 与内存镜像中，每轮请求前按 [AgentSessionConfig.maxHistoryTurns]
 *   截断后灌入 Koog 会话（Koog 每次 run 都从初始 prompt 重建上下文）。
 *
 * 工具能力来自 `core/agent/AgentToolHost.kt`：接口自带工具目录（toolSpecs）与
 * 文本协议调度（executeTool），本类只负责把二者接进 Koog 回合循环。
 */
class AgentSession(
    val sessionId: Long?,
    private val gateway: LlmGateway,
    private val toolHost: AgentToolHost,
    private val config: AgentSessionConfig,
    private val store: AgentSessionStore = InMemoryAgentSessionStore(),
    private val promptAssetLoader: AgentPromptAssetLoader? = null,
    private val systemPromptOverride: String? = null,
    private val texts: AgentSessionTexts = AgentSessionTexts(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow(AgentSessionState(sessionId = sessionId))
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    private val history = mutableListOf<AgentMessage>()
    private var activeSessionId: Long? = sessionId

    @Volatile
    private var seedSnapshot: List<AgentMessage> = emptyList()

    @Volatile
    private var signal: TranslationControlSignal = TranslationControlSignal()

    @Volatile
    private var running: Boolean = false

    private var agent: FunctionalAIAgent<String, AgentTurnResult>? = null

    /**
     * 最近一次请求的用量，等待挂到下一条落库消息上。
     *
     * `agent_messages` 是"消息行自带 token 列"的结构，所以请求结束后把用量记到该请求
     * 产出的第一条消息上；重开历史会话时据此重建会话用量（此前这两列恒为 0，
     * 会话用量只活在内存里，切会话/重启即归零）。
     */
    private var pendingUsage: Pair<Int, Int> = 0 to 0

    /**
     * 流式预览缓冲。只在一次模型请求的生命周期内追加，请求结束即清空，因此永不落库。
     * 增量从 IO 调度器写入、重置发生在回合协程里，统一加锁保护。
     */
    private val streamBuffer = StringBuilder()

    /** 供执行器推送文本增量；写入 [_state] 供对话流末尾的预览气泡渲染。 */
    private val streamObserver: (String) -> Unit = { delta ->
        if (delta.isNotEmpty()) {
            val snapshot = synchronized(streamBuffer) {
                streamBuffer.append(delta)
                streamBuffer.toString()
            }
            _state.update { it.copy(streamingText = snapshot) }
        }
    }

    private fun resetStreamPreview() {
        synchronized(streamBuffer) { streamBuffer.setLength(0) }
        _state.update { it.copy(streamingText = "") }
    }

    private val sink = object : AgentTurnSink {

        override suspend fun onStreamReset() {
            resetStreamPreview()
        }

        override suspend fun onStreamSettled() {
            resetStreamPreview()
        }

        override suspend fun onToolCall(name: String, argumentsJson: String, rawBlock: String) {
            appendMessage(
                AgentMessage(
                    role = AgentRole.TOOL_CALL,
                    content = AgentToolProtocol.renderToolCallBlock(name, argumentsJson, rawBlock),
                    toolName = name,
                    toolArgsJson = argumentsJson,
                    createdAt = now()
                )
            )
        }

        override suspend fun onToolResult(name: String, ok: Boolean, resultJson: String) {
            appendMessage(
                AgentMessage(
                    role = AgentRole.TOOL_RESULT,
                    content = AgentToolProtocol.renderToolResultBlock(name, ok, resultJson),
                    toolName = name,
                    toolResultJson = resultJson,
                    isError = !ok,
                    createdAt = now()
                )
            )
        }

        override suspend fun onUsage(promptTokens: Int, completionTokens: Int) {
            pendingUsage = (pendingUsage.first + promptTokens) to (pendingUsage.second + completionTokens)
            _state.update { current ->
                current.copy(
                    usage = current.usage.copy(
                        promptTokens = current.usage.promptTokens + promptTokens,
                        completionTokens = current.usage.completionTokens + completionTokens,
                        requestCount = current.usage.requestCount + 1
                    )
                )
            }
        }

        override suspend fun onCompressed() {
            SystemLogger.info(TAG, "Agent 会话触发历史压缩: session=$activeSessionId")
        }
    }

    /** 重新打开已有会话时加载持久化历史。 */
    suspend fun loadPersistedHistory() {
        val id = activeSessionId ?: return
        val loaded = store.loadMessages(id)
        synchronized(history) {
            history.clear()
            history += loaded
        }
        _state.update {
            it.copy(
                messages = loaded,
                // 用量从消息行重建：切会话/重启/换设备后仍显示真实累计，而不是归零。
                usage = AgentUsage(
                    promptTokens = loaded.sumOf { message -> message.promptTokens.toLong() },
                    completionTokens = loaded.sumOf { message -> message.completionTokens.toLong() },
                    requestCount = loaded.count { message ->
                        message.promptTokens > 0 || message.completionTokens > 0
                    }
                )
            )
        }
    }

    /** 请求停止当前回合；非挂起，保证 UI 点击后立即生效。 */
    fun cancel() {
        signal.cancel()
    }

    val isRunning: Boolean get() = running

    /** 执行一个回合：追加用户消息 → 跑 Koog Agent → 落库最终答复或错误。 */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || running) return
        if (config.provider == null) {
            _state.update { it.copy(error = texts.noProvider) }
            return
        }
        running = true
        try {
            // 上一回合可能因异常没走到 onStreamSettled，这里兜底清空预览。
            resetStreamPreview()
            // 快照必须早于追加当前用户消息：策略会自行把 input 作为最新 user 消息加入。
            seedSnapshot = truncateToTurns(snapshotHistory(), config.maxHistoryTurns)
            // 建会话与写用户消息都要落库：数据库异常同样必须走下面的 finally，
            // 否则 running 会永久置位，整个 Agent 页锁死到进程重启。
            ensureSessionId(trimmed)
            appendMessage(
                AgentMessage(role = AgentRole.USER, content = trimmed, createdAt = now())
            )
            _state.update { it.copy(isRunning = true, error = null) }
            try {
                val result = ensureAgent().run(trimmed)
                if (result.finalText.isNotBlank()) {
                    appendMessage(
                        AgentMessage(
                            role = AgentRole.ASSISTANT,
                            content = result.finalText,
                            createdAt = now()
                        )
                    )
                } else {
                    appendSystemMessage(texts.emptyReply, isError = true)
                }
            } catch (cancelled: CancellationException) {
                // 区分“用户主动停止”与“外层作用域被取消”：前者记录状态，后者必须继续上抛。
                if (currentCoroutineContext().isActive) {
                    appendSystemMessage(texts.cancelled, isError = false)
                } else {
                    throw cancelled
                }
            } catch (failure: AgentLlmFailureException) {
                appendSystemMessage(
                    texts.requestFailed.replace("{reason}", failure.message ?: "未知错误"),
                    isError = true
                )
            } catch (error: Throwable) {
                SystemLogger.error(TAG, "Agent 回合失败", details = error.stackTraceToString())
                appendSystemMessage(
                    texts.turnFailed.replace("{reason}", error.localizedMessage ?: error.javaClass.simpleName),
                    isError = true
                )
            }
        } finally {
            running = false
            _state.update { it.copy(isRunning = false) }
            // 最终答复/错误消息已落库，流式预览必须让位，避免同一段文本出现两份。
            resetStreamPreview()
            // 重置信号，避免上一回合的取消影响下一回合（执行器经 lambda 读取最新信号）。
            signal = TranslationControlSignal()
        }
    }

    private suspend fun ensureSessionId(firstUserMessage: String): Long {
        activeSessionId?.let { return it }
        val created = store.createSession(
            title = firstUserMessage.take(SESSION_TITLE_CHARS),
            providerId = config.provider?.id,
            modelName = config.effectiveModelName
        )
        activeSessionId = created
        _state.update { it.copy(sessionId = created, title = firstUserMessage.take(SESSION_TITLE_CHARS)) }
        return created
    }

    private suspend fun appendMessage(message: AgentMessage) {
        val sessionId = activeSessionId ?: return
        // 把最近一次请求的用量记到它产出的第一条消息上（回合内单线程写入，无需加锁）。
        val usage = pendingUsage
        pendingUsage = 0 to 0
        val withUsage = if (usage.first > 0 || usage.second > 0) {
            message.copy(
                promptTokens = message.promptTokens + usage.first,
                completionTokens = message.completionTokens + usage.second
            )
        } else {
            message
        }
        val stored = withUsage.copy(id = store.appendMessage(sessionId, withUsage))
        synchronized(history) { history += stored }
        _state.update { it.copy(messages = it.messages + stored) }
        // 会话行的 preview/updatedAt 只随"用户可见的对话进展"更新：工具调用/回执是回合内部
        // 细节，逐条刷新等于给会话列表的 Flow 制造无意义的失效（一次工具回合多 2 次整行写）。
        if (withUsage.role != AgentRole.TOOL_CALL && withUsage.role != AgentRole.TOOL_RESULT) {
            store.touchSession(sessionId, previewOf(withUsage), now())
        }
    }

    private suspend fun appendSystemMessage(content: String, isError: Boolean) {
        appendMessage(
            AgentMessage(
                role = AgentRole.SYSTEM,
                content = content,
                isError = isError,
                createdAt = now()
            )
        )
    }

    private fun previewOf(message: AgentMessage): String = when (message.role) {
        AgentRole.USER -> message.content
        AgentRole.ASSISTANT -> message.content
        AgentRole.TOOL_CALL -> "调用工具 " + (message.toolName ?: "")
        AgentRole.TOOL_RESULT -> "工具 " + (message.toolName ?: "") + " 返回" + if (message.isError) "（失败）" else ""
        AgentRole.SYSTEM -> message.content
    }

    private fun snapshotHistory(): List<AgentMessage> = synchronized(history) { history.toList() }

    private fun ensureAgent(): FunctionalAIAgent<String, AgentTurnResult> {
        agent?.let { return it }
        val model = LLModel(
            provider = LntLlmProvider(config),
            id = config.effectiveModelName.ifBlank { "unknown-model" },
            capabilities = listOf(LLMCapability.Temperature)
        )
        val executor = LntPromptExecutor(
            gateway = gateway,
            config = config,
            toolSpecs = toolHost.toolSpecs(),
            signalProvider = { signal },
            streamObserver = streamObserver
        )
        val systemPrompt = AgentPrompts.buildSystemPrompt(
            basePrompt = AgentPrompts.resolveBasePrompt(promptAssetLoader, systemPromptOverride),
            specs = toolHost.toolSpecs(),
            maxToolRounds = config.maxToolRounds,
            runtimeContext = buildRuntimeContext()
        )
        val strategy = lntAgentStrategy(
            toolHost = toolHost,
            sink = sink,
            config = config,
            signalProvider = { signal },
            historySeed = { seedSnapshot }
        )
        return AIAgent(
            promptExecutor = executor,
            llmModel = model,
            strategy = strategy,
            toolRegistry = ToolRegistry.EMPTY,
            systemPrompt = systemPrompt,
            // 编排回合用收敛后的低温（默认 0.2，上限 0.5）：格式服从性对温度敏感，
            // 翻译用的高温度不适用于对话编排。Koog 的 LLMParams 要求 0.0..2.0。
            temperature = config.effectiveAgentTemperature.toDouble(),
            maxIterations = config.maxToolRounds + MAX_ITERATION_HEADROOM,
            id = AGENT_ID_PREFIX + (activeSessionId ?: 0L)
        ).also { agent = it }
    }

    private fun buildRuntimeContext(): String = buildString {
        appendLine("- 提供方：" + (config.provider?.name ?: "未配置"))
        appendLine("- 模型：" + config.effectiveModelName)
        appendLine("- 单回合工具调用上限：" + config.maxToolRounds + " 轮")
        appendLine("- 回复语言：" + config.replyLanguage)
    }

    private companion object {
        const val TAG = "AGENT_CHAT"
        const val SESSION_TITLE_CHARS = 20
        const val MAX_ITERATION_HEADROOM = 4
        const val AGENT_ID_PREFIX = "lnt-agent-"
    }
}

/**
 * LNT 自有 provider 标识。Koog 的 [LLMProvider] 是 open class，直接子类化即可，
 * 无需把 LNT 的 provider 类型映射到 Koog 预置枚举。
 */
private class LntLlmProvider(config: AgentSessionConfig) : LLMProvider(
    id = config.provider?.providerType?.name?.lowercase() ?: "lnt",
    display = config.provider?.name ?: "LightNovelTranslator"
)
