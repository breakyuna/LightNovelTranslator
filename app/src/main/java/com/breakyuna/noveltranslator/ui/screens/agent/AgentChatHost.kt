package com.breakyuna.noveltranslator.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.components.apple.AppCard
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.i18n.AgentEntryStrings
import com.breakyuna.noveltranslator.ui.i18n.agentEntryStrings
import com.breakyuna.noveltranslator.ui.theme.GroupedCardShape
import com.breakyuna.noveltranslator.ui.theme.ModalSheetShape
import com.breakyuna.noveltranslator.ui.theme.Spacing
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内一次性标记：无会话参数打开助手时恢复最近一段会话，但绝不用历史会话覆盖
 * 用户刚刚开始的空白对话。
 */
private var autoOpenedLatestSession = false

/**
 * Agent 助手页宿主：把 AppViewModel 已有的能力接到纯展示层 [AgentChatScreen] 上。
 *
 * 这里只做三件接线，不新增任何 ViewModel API：
 * - 会话：使用 [AppViewModel.agentChatController]（ViewModel 作用域内单例，离开页面不销毁），
 *   [initialSessionId] 有值时打开指定会话，为空时恢复最近一段会话；
 * - 会话列表：读取 [AppViewModel.observeAgentSessions] 提供的新建 / 切换入口
 *   （归档需要数据层入口，当前不可用）；
 * - ask_user：收集 [AppViewModel.agentQuestions]，用户作答 / 跳过时回调
 *   [AppViewModel.answerAgentQuestion] / [AppViewModel.dismissAgentQuestion]，
 *   解除被阻塞的工具调用。
 */
@Composable
fun AgentChatHostScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSessionId: Long? = null
) {
    val entryStrings = agentEntryStrings()

    val controller = viewModel.agentChatController
    val controllerState by controller.state.collectAsStateWithLifecycle()
    val sessions by remember(viewModel) { viewModel.observeAgentSessions() }
        .collectAsState(initial = emptyList())

    var showSessionSheet by rememberSaveable { mutableStateOf(false) }
    var pendingQuestion by remember { mutableStateOf<AgentChatQuestion?>(null) }

    LaunchedEffect(controller, initialSessionId) {
        if (initialSessionId != null) {
            controller.openSession(initialSessionId)
        } else if (!autoOpenedLatestSession) {
            autoOpenedLatestSession = true
            val current = controller.state.value
            if (current.sessionId == null && current.messages.isEmpty()) {
                val latest = viewModel.observeAgentSessions().first().firstOrNull()
                if (latest != null) controller.openSession(latest.id)
            }
        }
    }

    // ask_user：Agent 通过工具提问时弹出面板，作答后恢复被阻塞的工具调用。
    LaunchedEffect(viewModel) {
        viewModel.agentQuestions.collect { question ->
            pendingQuestion = AgentChatQuestion(
                id = question.id,
                prompt = question.prompt,
                options = question.options
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // AgentChatScreen 自己绘制带 statusBarsPadding 的大标题，这里只补底部内边距，
        // 避免状态栏 inset 被计算两次。
        AgentChatScreen(
            controller = controller,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            onBack = onBack,
            onOpenSessions = { showSessionSheet = true },
            question = pendingQuestion,
            onAnswerQuestion = { answer ->
                val question = pendingQuestion
                pendingQuestion = null
                question?.let { viewModel.answerAgentQuestion(it.id, answer) }
            },
            onDismissQuestion = {
                val question = pendingQuestion
                pendingQuestion = null
                question?.let { viewModel.dismissAgentQuestion(it.id) }
            }
        )
    }

    if (showSessionSheet) {
        AgentSessionSheet(
            sessions = sessions,
            currentSessionId = controllerState.sessionId,
            strings = entryStrings,
            onDismiss = { showSessionSheet = false },
            onSelect = { sessionId ->
                showSessionSheet = false
                controller.openSession(sessionId)
            },
            onNewChat = {
                showSessionSheet = false
                controller.startNewChat()
            }
        )
    }
}

/** 会话列表面板：新建、切换；归档暂无可用的数据层入口，只在页脚说明。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSessionSheet(
    sessions: List<AgentSessionEntity>,
    currentSessionId: Long?,
    strings: AgentEntryStrings,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onNewChat: () -> Unit
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
                            onClick = { onSelect(session.id) }
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
    onClick: () -> Unit
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
        }
    }
}

private fun formatSessionTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
