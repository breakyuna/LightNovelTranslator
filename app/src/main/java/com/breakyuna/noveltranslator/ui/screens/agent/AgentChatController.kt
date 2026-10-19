package com.breakyuna.noveltranslator.ui.screens.agent

import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.agent.koog.AgentPromptAssetLoader
import com.breakyuna.noveltranslator.core.agent.koog.AgentSession
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionConfig
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionState
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionStore
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Agent 对话页的控制器：把 UI 事件翻译成 [AgentSession] 调用，并向外暴露会话状态。
 *
 * 依赖全部由外部注入（LlmGateway、AgentToolHost、存储、配置解析），页面本身不感知数据库与
 * provider 细节；集成层用 AppViewModel 提供这些实现。AgentToolHost 自带工具目录与文本协议
 * 调度，控制器原样传给会话。会话在首次发送时按当前配置创建，之后同一会话内继续对话，
 * [startNewChat] 开新会话。
 */
class AgentChatController(
    private val gateway: LlmGateway,
    private val toolHost: AgentToolHost,
    private val store: AgentSessionStore,
    private val configProvider: suspend () -> AgentSessionConfig?,
    private val promptAssetLoader: AgentPromptAssetLoader? = null,
    private val texts: AgentChatText = AgentChatStrings.chinese
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AgentSessionState())
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    /** 首次发送后解析到的模型名，供界面展示；未解析前为 null。 */
    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private var session: AgentSession? = null

    /** 当前会话状态的收集任务；替换会话前先取消，避免旧会话继续覆盖状态。 */
    private var stateJob: Job? = null

    val isRunning: Boolean get() = session?.isRunning == true

    fun send(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val active = session ?: createSession() ?: return@launch
            active.send(text)
        }
    }

    fun cancel() {
        session?.cancel()
    }

    fun startNewChat() {
        if (isRunning) return
        session?.startNewChat()
        replaceSession(null)
        _state.value = AgentSessionState()
    }

    /** 打开历史会话并加载其消息。 */
    fun openSession(sessionId: Long) {
        if (isRunning) return
        scope.launch {
            replaceSession(null)
            val created = createSession(sessionId) ?: return@launch
            created.loadPersistedHistory()
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun replaceSession(next: AgentSession?) {
        stateJob?.cancel()
        stateJob = null
        session = next
    }

    private suspend fun createSession(sessionId: Long? = null): AgentSession? {
        val config = configProvider() ?: run {
            _state.value = AgentSessionState(error = texts.notConfigured)
            return null
        }
        _modelName.value = config.effectiveModelName
        val created = AgentSession(
            sessionId = sessionId,
            gateway = gateway,
            toolHost = toolHost,
            config = config,
            store = store,
            promptAssetLoader = promptAssetLoader,
            texts = texts.toSessionTexts()
        )
        replaceSession(created)
        stateJob = scope.launch {
            created.state.collect { snapshot -> _state.value = snapshot }
        }
        return created
    }
}
