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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private var texts: AgentChatText = AgentChatStrings.chinese
) {

    /**
     * 切换界面语言后更新文案与回复语言。
     *
     * 由页面在每次组合时下发（界面语言由 Compose 侧决定）；对之后新建的会话生效，
     * 已在跑的回合不受影响。
     */
    fun updateTexts(value: AgentChatText) {
        texts = value
    }

    /**
     * 控制器自己的 scope（不属于 viewModelScope，需要 [dispose] 显式停止）。
     *
     * 用 [Dispatchers.Default] 而不是 Main.immediate：一个回合里有大量同步 CPU 工作
     * （解析工具参数、拼协议文本、清洗与校验模型响应），跑在主线程会直接掉帧；状态一律
     * 经 StateFlow 下发，Compose 侧仍在主线程重组，线程切换是安全的。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AgentSessionState())
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    /**
     * 流式预览文本，与 [state] 分开暴露。
     *
     * 界面里只有对话流末尾的预览气泡需要它。把它从 [state] 里拆走，信息侧栏、信息条与
     * 标题栏就不会因为模型每吐一个 token 就重组一次——侧栏的诊断面板有数十条日志，
     * 那是流式期间最贵的重组，也是"流式看起来不流畅"的主因。
     */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    /** 首次发送后解析到的模型名，供界面展示；未解析前为 null。 */
    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    private var session: AgentSession? = null

    /** 当前会话状态的收集任务；替换会话前先取消，避免旧会话继续覆盖状态。 */
    private var stateJob: Job? = null

    /**
     * 串行化"会话创建 / 切换"。
     *
     * 首次发送与切换会话都是协程，两条路径同时创建会话会各自 replaceSession 并起两个
     * stateJob：后建的那个覆盖 `session`，先建的继续把手里的消息写进旧会话，界面看到的
     * 状态与真正在跑的会话错位。发送只在"取会话 + 预留回合"这一小段持锁，整个回合在锁外跑，
     * 否则模型请求会挡住停止与新对话。
     */
    private val sessionLock = Mutex()

    /**
     * 已预留回合所属的会话，必须与 [session] 的取得/创建在同一把 [sessionLock] 内设置。
     *
     * 只看 `session.isRunning` 不够：`AgentSession.send` 要等进入方法后才置位，取到会话到
     * 真正置位之间有一个窗口，新建/切换会话能在窗口内通过检查，于是旧回合继续跑且停止按钮
     * 指向新会话。这里在释放 [sessionLock] 之前就登记回合，并让 [cancel] 绑定这个会话。
     *
     * 该字段同时充当"一次只跑一个回合"的门闩：并发的第二次发送直接丢弃，而不是排队
     * （排队会在回合结束后再发一次，等于双倍付费）。
     */
    @Volatile
    private var activeTurn: AgentSession? = null

    val isRunning: Boolean get() = activeTurn != null || session?.isRunning == true

    fun send(text: String) {
        if (text.isBlank()) return
        scope.launch {
            // 会话取得/创建与回合预留必须原子完成；释放锁后任何切换路径都会看到 activeTurn。
            val active = sessionLock.withLock {
                if (activeTurn != null) return@launch
                val current = session ?: createSessionLocked() ?: return@launch
                activeTurn = current
                current
            }
            try {
                active.send(text)
            } finally {
                // dispose() 会取消 scope，清理必须在 NonCancellable 中完成，否则预留会永久残留。
                withContext(NonCancellable) {
                    sessionLock.withLock {
                        if (activeTurn === active) activeTurn = null
                    }
                }
            }
        }
    }

    /** 停止实际在跑的回合；没有预留回合时不误伤下一次发送的信号。 */
    fun cancel() {
        activeTurn?.cancel()
    }

    /**
     * 开一段新对话。
     *
     * @return 是否受理；回合进行中返回 false（此时切换会话会让正在跑的回合失去界面，
     * 入口应提示用户先停止）。
     */
    fun startNewChat(): Boolean {
        if (isRunning) return false
        scope.launch {
            sessionLock.withLock {
                // 拿到锁之前回合可能已经预留（用户刚点发送）：这时换会话会让正在跑的回合
                // 失去界面却继续花钱。re-check 一次，宁可不切也不丢状态。
                if (isRunning) return@withLock
                replaceSession(null)
                _state.value = AgentSessionState()
                _streamingText.value = ""
                // 新会话还没解析模型：清掉上一条会话的模型名，否则界面继续显示已不生效的模型。
                _modelName.value = null
            }
        }
        return true
    }

    /**
     * 打开历史会话并加载其消息。
     *
     * @return 是否受理；回合进行中返回 false，理由同 [startNewChat]。
     */
    fun openSession(sessionId: Long): Boolean {
        if (isRunning) return false
        scope.launch {
            sessionLock.withLock {
                if (isRunning) return@withLock
                replaceSession(null)
                val created = createSessionLocked(sessionId) ?: return@withLock
                created.loadPersistedHistory()
            }
        }
        return true
    }

    fun dispose() {
        scope.cancel()
    }

    private fun replaceSession(next: AgentSession?) {
        stateJob?.cancel()
        stateJob = null
        session = next
    }

    /**
     * 创建会话并接管状态流。
     *
     * 必须在 [sessionLock] 内调用：它会同时改 [session]、[stateJob] 与三个对外 StateFlow。
     */
    private suspend fun createSessionLocked(sessionId: Long? = null): AgentSession? {
        val resolved = configProvider() ?: run {
            _state.value = AgentSessionState(error = texts.notConfigured)
            _streamingText.value = ""
            return null
        }
        // 回复语言跟随界面语言（配置由 ViewModel 提供，它不知道 Compose 侧的语言选择）。
        val config = resolved.copy(replyLanguage = texts.replyLanguage)
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
            created.state.collect { snapshot ->
                _streamingText.value = snapshot.streamingText
                // 交给界面的 state 不再携带流式预览：它只表示已落库的消息、用量与错误。
                _state.value = snapshot.copy(streamingText = "")
            }
        }
        return created
    }
}
