package com.breakyuna.noveltranslator.ui.screens.agent

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.components.apple.AppCard
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.i18n.AgentEntryStrings
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.i18n.agentEntryStrings
import com.breakyuna.noveltranslator.ui.theme.GroupedCardShape
import com.breakyuna.noveltranslator.ui.theme.ModalSheetShape
import com.breakyuna.noveltranslator.ui.theme.Spacing
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 分享日志文本的上限：超出后改为复制到剪贴板，避免 Intent 事务过大。 */
private const val ShareTextLimit = 400_000

/**
 * 进程内一次性标记：无会话参数打开助手时恢复最近一段会话，但绝不用历史会话覆盖
 * 用户刚刚开始的空白对话。
 */
private var autoOpenedLatestSession = false

/**
 * Agent 助手页宿主：把 AppViewModel 已有的能力接到纯展示层 [AgentChatScreen] 上。
 *
 * 这里只做接线，不新增任何 ViewModel API：
 * - 会话：使用 [AppViewModel.agentChatController]（ViewModel 作用域内单例，离开页面不销毁），
 *   [initialSessionId] 有值时打开指定会话，为空时恢复最近一段会话；
 * - 会话列表：读取 [AppViewModel.observeAgentSessions] 提供的新建 / 切换入口
 *   （归档需要数据层入口，当前不可用）；
 * - ask_user：读 [AppViewModel.agentPendingQuestion]（StateFlow，切页/旋屏后仍能重新读到），
 *   用户作答 / 跳过时回调 [AppViewModel.answerAgentQuestion] /
 *   [AppViewModel.dismissAgentQuestion]，解除被阻塞的工具调用；点"停止"时经
 *   [AppViewModel.cancelPendingAgentQuestion] 一并作废，避免工具空等超时；
 * - 模型与工具：读取 provider 列表与 [AppViewModel.agentModelSelection] /
 *   [AppViewModel.agentReasoningEffort] / [AppViewModel.agentNativeToolsEnabled]，
 *   经底部弹窗修改后写回，并用 Snackbar 提示"对新对话生效"；
 * - 诊断：读取 [AppViewModel.agentLastCrash] 与 [AppViewModel.systemLogs]，
 *   宽屏时作为信息侧栏、竖屏时作为底部弹窗展示，支持清除崩溃与导出 / 复制日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatHostScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSessionId: Long? = null
) {
    val appStrings = LocalAppStrings.current
    val entryStrings = agentEntryStrings()
    val chatStrings = remember(appStrings) { AgentChatStrings.resolve(appStrings) }

    val controller = viewModel.agentChatController
    val controllerState by controller.state.collectAsStateWithLifecycle()
    // 流式预览单独订阅：它每个 token 变一次，不能混在 controllerState 里，
    // 否则右侧信息栏（含数十条诊断日志）会跟着每个 token 重组一次。
    val streamingText by controller.streamingText.collectAsStateWithLifecycle()
    val modelName by controller.modelName.collectAsStateWithLifecycle()
    val sessions by remember(viewModel) { viewModel.observeAgentSessions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val providers by viewModel.allProviders.collectAsStateWithLifecycle()
    val modelSelection by viewModel.agentModelSelection.collectAsStateWithLifecycle()
    val reasoningEffort by viewModel.agentReasoningEffort.collectAsStateWithLifecycle()
    val nativeToolsEnabled by viewModel.agentNativeToolsEnabled.collectAsStateWithLifecycle()
    val lastCrash by viewModel.agentLastCrash.collectAsStateWithLifecycle()
    val systemLogs by viewModel.systemLogs.collectAsStateWithLifecycle()

    var showSessionSheet by rememberSaveable { mutableStateOf(false) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticsSheet by rememberSaveable { mutableStateOf(false) }
    // 待确认删除的会话 id（会话实体不可序列化，这里只存 id，标题按列表现查）。
    var pendingDeleteSessionId by remember { mutableStateOf<Long?>(null) }
    // ask_user：问题源是 ViewModel 的 StateFlow，不再用页面内 remember 缓存一次性事件。
    val pendingQuestion by viewModel.agentPendingQuestion.collectAsStateWithLifecycle()
    val question = remember(pendingQuestion) {
        pendingQuestion?.let { q ->
            AgentChatQuestion(id = q.id, prompt = q.prompt, options = q.options)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // 解析当前生效的提供方：显式选择优先，否则跟随默认提供方。
    val activeProvider = remember(providers, modelSelection.providerId) {
        modelSelection.providerId
            ?.let { id -> providers.firstOrNull { it.id == id } }
            ?: providers.firstOrNull { it.isDefault }
            ?: providers.firstOrNull()
    }
    val providerLabel = activeProvider?.let { "${it.name} · ${it.providerType.displayName}" }
    // controller.modelName 要等会话真正发起后才解析；此前用当前选择预览将要使用的模型。
    val displayModelName = modelName
        ?: modelSelection.modelName?.takeIf { it.isNotBlank() }
        ?: activeProvider?.selectedModel

    // 界面语言 → 控制器文案与 Agent 回复语言（对之后新建的会话生效）。
    LaunchedEffect(controller, chatStrings) {
        controller.updateTexts(chatStrings)
    }

    LaunchedEffect(controller, initialSessionId) {
        if (initialSessionId != null) {
            controller.openSession(initialSessionId)
        } else if (!autoOpenedLatestSession) {
            val current = controller.state.value
            if (current.sessionId == null && current.messages.isEmpty()) {
                val latest = viewModel.observeAgentSessions().first().firstOrNull()
                // 先读再置位：读取被取消（离开页面）时保留机会，下次进入仍能恢复最近会话。
                autoOpenedLatestSession = true
                if (latest != null) controller.openSession(latest.id)
            } else {
                autoOpenedLatestSession = true
            }
        }
    }

    // 下面这些回调用 remember 包住：AgentChatScreen 每个流式 token 重组一次，若每次都传
    // 新实例，右侧信息栏（含数十条诊断日志）和对话流里每一行消息都会被拖着一起重组。
    fun copyText(text: String, message: String) {
        clipboardManager.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** 导出日志：优先系统分享；空日志/文本过大/没有可用分享入口时退回剪贴板。 */
    fun exportLogs() {
        // 读持久化 JSONL（suspend）：崩溃重启后仍能导出崩溃前的记录。
        scope.launch {
            val text = viewModel.exportAgentLogsWithHistory()
            if (text.isBlank()) {
                snackbarHostState.showSnackbar(entryStrings.logsEmpty)
                return@launch
            }
            if (text.length > ShareTextLimit) {
                copyText(text, entryStrings.copiedLabel)
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val started = runCatching {
                context.startActivity(Intent.createChooser(send, entryStrings.logsExport))
            }.isSuccess
            if (!started) copyText(text, entryStrings.copiedLabel)
        }
    }

    // 真正需要跟着 token 变的只有流式预览本身。
    // 回合进行中新建 / 切换会被控制器拒绝（返回 false）：这里明说原因，避免点了没反应。
    val startNewChatAction = remember(controller, chatStrings) {
        {
            if (!controller.startNewChat()) {
                scope.launch { snackbarHostState.showSnackbar(chatStrings.sessionBusy) }
            }
        }
    }
    val openSessionsAction = remember<() -> Unit> { { showSessionSheet = true } }
    val openModelPanelAction = remember<() -> Unit> { { showModelSheet = true } }
    val openDiagnosticsAction = remember<() -> Unit> { { showDiagnosticsSheet = true } }
    // 停止 = 置取消信号 + 作废待答问题：只置信号时，卡在 ask_user 里的工具会继续等到超时。
    val cancelTurnAction = remember(viewModel, controller) {
        {
            viewModel.cancelPendingAgentQuestion()
            controller.cancel()
        }
    }
    val copyChatTextAction = remember(chatStrings) { { text: String ->
        copyText(text, chatStrings.copied)
    } }
    // 作答/跳过同样按当前问题记住：问题不变时回调实例不变，面板不因父级重组而重建。
    // 末尾显式 Unit：ViewModel 的这两个方法返回"问题是否仍有效"，而回调类型是 () -> Unit，
    // 让 lambda 直接从 Boolean 推断成 (String) -> Boolean? 会与 onAnswerQuestion 的类型不匹配。
    val answerQuestionAction = remember(question, viewModel) { { answer: String ->
        question?.let { viewModel.answerAgentQuestion(it.id, answer) }
        Unit
    } }
    val dismissQuestionAction = remember(question, viewModel) {
        {
            question?.let { viewModel.dismissAgentQuestion(it.id) }
            Unit
        }
    }
    // 信息侧栏内容同样记住：它依赖的数据在流式期间不变，没理由每个 token 重建一次。
    val infoPanelContent: @Composable ColumnScope.() -> Unit = remember(
        entryStrings,
        controllerState,
        displayModelName,
        providerLabel,
        lastCrash,
        systemLogs
    ) {
        {
            AgentInfoPanel(
                strings = entryStrings,
                state = controllerState,
                modelName = displayModelName,
                providerLabel = providerLabel,
                crash = lastCrash,
                logs = systemLogs,
                onOpenModelPanel = { showModelSheet = true },
                onCancel = cancelTurnAction,
                onClearCrash = viewModel::clearAgentLastCrash,
                onExportLogs = { exportLogs() },
                onCopyLogs = {
                    scope.launch {
                        copyText(viewModel.exportAgentLogsWithHistory(), entryStrings.copiedLabel)
                    }
                }
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // AgentChatScreen 自己绘制带 statusBarsPadding 的大标题，这里只补底部内边距，
        // 避免状态栏 inset 被计算两次。状态由宿主收集后下传，屏幕保持纯展示。
        AgentChatScreen(
            state = controllerState,
            onSend = controller::send,
            onCancel = cancelTurnAction,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            modelName = displayModelName,
            providerLabel = providerLabel,
            onNewChat = startNewChatAction,
            onBack = onBack,
            onOpenSessions = openSessionsAction,
            onOpenModelPanel = openModelPanelAction,
            onOpenDiagnostics = openDiagnosticsAction,
            onCopyText = copyChatTextAction,
            streamingText = streamingText,
            infoPanel = infoPanelContent,
            question = question,
            onAnswerQuestion = answerQuestionAction,
            onDismissQuestion = dismissQuestionAction
        )
    }

    if (showSessionSheet) {
        AgentSessionSheet(
            sessions = sessions,
            currentSessionId = controllerState.sessionId,
            strings = entryStrings,
            // 回合进行中禁止切换 / 新建 / 删除：正在跑的会话被换掉会让工具调用失去界面，
            // 删除当前会话更会让后续消息写回已删除的行。
            actionsEnabled = !controllerState.isRunning,
            onDismiss = { showSessionSheet = false },
            onSelect = { sessionId ->
                if (controller.openSession(sessionId)) {
                    showSessionSheet = false
                } else {
                    scope.launch { snackbarHostState.showSnackbar(chatStrings.sessionBusy) }
                }
            },
            onNewChat = {
                showSessionSheet = false
                startNewChatAction()
            },
            onDelete = { session -> pendingDeleteSessionId = session.id }
        )
    }

    pendingDeleteSessionId?.let { sessionId ->
        val target = sessions.firstOrNull { it.id == sessionId }
        AlertDialog(
            onDismissRequest = { pendingDeleteSessionId = null },
            title = { Text(entryStrings.sessionsDeleteTitle) },
            text = {
                Text(
                    entryStrings.sessionsDeleteMessage(
                        target?.title?.takeIf { it.isNotBlank() }
                            ?: target?.lastMessagePreview
                            ?: entryStrings.sessionsUntitled
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSessionId = null
                        scope.launch {
                            val deleted = viewModel.deleteAgentSession(sessionId)
                            if (!deleted) {
                                snackbarHostState.showSnackbar(entryStrings.sessionsDeleteFailed)
                                return@launch
                            }
                            // 删掉的正是当前会话：控制器必须丢掉引用，否则下一次发送会写回
                            // 已被删除的会话行（数据层删除 + 旧 sessionId 是最坏组合）。
                            if (controller.state.value.sessionId == sessionId) controller.startNewChat()
                            snackbarHostState.showSnackbar(entryStrings.sessionsDeleted)
                        }
                    }
                ) {
                    Text(entryStrings.sessionsDeleteConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSessionId = null }) {
                    Text(entryStrings.sessionsDeleteCancel)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = GroupedCardShape
        )
    }

    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            shape = ModalSheetShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            // 小屏弹窗高度有限，内容整体可滚动；内部提供方列表另有高度上限。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l)
                    .padding(bottom = Spacing.xl)
            ) {
                AgentModelPanelContent(
                    strings = entryStrings,
                    providers = providers,
                    selectedProviderId = modelSelection.providerId,
                    selectedModel = modelSelection.modelName,
                    reasoningEffort = reasoningEffort,
                    nativeToolsEnabled = nativeToolsEnabled,
                    onApply = { providerId, model, effort, nativeTools ->
                        viewModel.setAgentModelSelection(providerId, model)
                        viewModel.setAgentReasoningEffort(effort)
                        viewModel.setAgentNativeToolsEnabled(nativeTools)
                        showModelSheet = false
                        scope.launch {
                            snackbarHostState.showSnackbar(entryStrings.modelAppliedToNewChat)
                        }
                    }
                )
            }
        }
    }

    if (showDiagnosticsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDiagnosticsSheet = false },
            shape = ModalSheetShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                Text(
                    text = entryStrings.diagnosticsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = entryStrings.diagnosticsSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AgentDiagnosticsContent(
                    strings = entryStrings,
                    crash = lastCrash,
                    logs = systemLogs,
                    onClearCrash = viewModel::clearAgentLastCrash,
                    onExportLogs = { exportLogs() },
                    onCopyLogs = {
                        scope.launch {
                            copyText(viewModel.exportAgentLogsWithHistory(), entryStrings.copiedLabel)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 会话列表面板：新建、切换、删除；归档暂无可用的数据层入口，只在页脚说明。
 *
 * [actionsEnabled] 为假（回合进行中）时切换与新建都会被控制器拒绝，这里直接把入口禁用，
 * 用户看到的是"不可点"，而不是点了没反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSessionSheet(
    sessions: List<AgentSessionEntity>,
    currentSessionId: Long?,
    strings: AgentEntryStrings,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (AgentSessionEntity) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ModalSheetShape,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = strings.sessionsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (sessions.isNotEmpty()) {
                    AppBadge(text = strings.sessionsCount(sessions.size))
                }
            }
            Text(
                text = strings.sessionsSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AppSecondaryButton(
                text = strings.sessionsNew,
                onClick = onNewChat,
                icon = Icons.Outlined.Add,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            if (sessions.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Outlined.SmartToy,
                    title = strings.sessionsTitle,
                    description = strings.sessionsEmpty
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        AgentSessionRow(
                            session = session,
                            isCurrent = session.id == currentSessionId,
                            currentLabel = strings.sessionsCurrent,
                            updatedTemplate = strings.sessionsUpdatedTemplate,
                            untitled = strings.sessionsUntitled,
                            deleteLabel = strings.sessionsDelete,
                            onClick = if (actionsEnabled) ({ onSelect(session.id) }) else null,
                            onDelete = if (actionsEnabled) ({ onDelete(session) }) else null
                        )
                    }
                }
            }

            Text(
                text = strings.sessionsArchiveUnavailable,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgentSessionRow(
    session: AgentSessionEntity,
    isCurrent: Boolean,
    currentLabel: String,
    updatedTemplate: (String) -> String,
    untitled: String,
    deleteLabel: String,
    onClick: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = GroupedCardShape,
        contentPadding = PaddingValues(
            horizontal = Spacing.l,
            vertical = Spacing.m
        ),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { session.lastMessagePreview.ifBlank { untitled } },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (session.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = updatedTemplate(formatSessionTime(session.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrent) {
                Spacer(modifier = Modifier.width(Spacing.s))
                AppBadge(text = currentLabel)
            }
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(Spacing.xs))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = deleteLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatSessionTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
