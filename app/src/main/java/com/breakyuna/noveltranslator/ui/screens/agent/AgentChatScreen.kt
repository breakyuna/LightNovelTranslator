package com.breakyuna.noveltranslator.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.core.agent.koog.AgentMessage
import com.breakyuna.noveltranslator.core.agent.koog.AgentRole
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionState
import com.breakyuna.noveltranslator.core.agent.koog.AgentToolProtocol
import com.breakyuna.noveltranslator.core.agent.koog.AgentUsage
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppLargeTitle
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.Spacing

/** 宽屏右侧信息侧栏占聊天列的宽度比例（侧栏 ≈ 0.58 / 1.58 ≈ 37%）。 */
private val SidebarWeight = 0.58f

/** 气泡最大宽度，避免平板 / 横屏下单行文字拉得过长。 */
private val BubbleMaxWidth = 320.dp

/** 流式预览在 LazyColumn 里的 key：它不是持久消息，不能用消息 id。 */
private const val StreamingPreviewKey = "agent-streaming-preview"

/** 是否停在底部附近：用户主动上翻时不抢滚动位置。 */
private fun LazyListState.isNearBottom(itemCount: Int): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= itemCount - 2
}

/**
 * Agent 对话页（纯展示层）。
 *
 * 所有状态经 [state] 注入，交互经回调上抛，不直接依赖数据库或 provider；完整行为由
 * [com.breakyuna.noveltranslator.ui.screens.agent.AgentChatHost] 收集控制器状态后传入。
 * 文案来自本页自带的 [AgentChatStrings]，跟随全局语言。
 *
 * 布局自适应：宽屏（[rememberWindowSize] 判定 isExpanded，不分横竖屏）时右侧展开
 * [infoPanel] 信息侧栏；紧凑宽度时信息挤成顶部信息条。两种布局都能经标题栏的 Info 按钮
 * 收回信息区——宽屏收回侧栏后聊天列顶部退回紧凑信息条，紧凑宽度收回后只留模型面板与
 * 诊断入口，都不会丢掉功能入口。宽屏侧栏展开时的运行状态、用量与诊断都经 [infoPanel]
 * 展示，因此不重复绘制底部状态条。
 *
 * 流式：模型生成期间，[AgentSessionState.streamingText] 的增量以预览气泡形式挂在对话流
 * 末尾，最终答复落库后由正式消息接管。预览文本经 [AgentToolProtocol.cleanFinalText] 剥掉
 * 正在生成的协议块，不会把调用标签闪给用户。预览文本经 [streamingText] 单独传入，
 * 信息侧栏 / 信息条 / 标题栏不参与每个 token 的重组。
 */
@Composable
fun AgentChatScreen(
    state: AgentSessionState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    modelName: String? = null,
    providerLabel: String? = null,
    onNewChat: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenSessions: (() -> Unit)? = null,
    onOpenModelPanel: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    infoPanel: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * 流式预览文本。与 [state] 分开传，信息侧栏 / 信息条 / 标题栏才不会因为每个 token
     * 重组一次；null 时回退读 [AgentSessionState.streamingText]，方便只持有 state 的
     * 纯展示调用方。
     */
    streamingText: String? = null,
    question: AgentChatQuestion? = null,
    onAnswerQuestion: (String) -> Unit = {},
    onDismissQuestion: () -> Unit = {}
) {
    val appStrings = LocalAppStrings.current
    val strings = remember(appStrings) { AgentChatStrings.resolve(appStrings) }
    // 列表状态按会话区分：切换会话必须回到新会话的位置，不能沿用上一个会话的滚动偏移。
    val listState = remember(state.sessionId) { LazyListState() }
    val windowSize = rememberWindowSize()
    // 宽屏（不分方向）才放得下右侧侧栏；紧凑宽度用顶部信息条。
    val useSidebar = infoPanel != null && windowSize.isExpanded
    // 信息区收起状态：宽屏收回右侧侧栏，紧凑宽度收回顶部信息条。跨旋转保持。
    var infoVisible by rememberSaveable { mutableStateOf(true) }
    // 没有侧栏时也要能收回顶部信息条；一条入口都没有时不画这个按钮。
    val canToggleInfo = infoPanel != null || onOpenModelPanel != null || onOpenDiagnostics != null
    // 预览要剥掉正在生成的协议块：模型输出调用块时不该把标签闪给用户。
    // 纯散文（最常见）不必跑协议正则——流式每帧都会走到这里。
    val rawStreamingText = streamingText ?: state.streamingText
    val streamingPreview = remember(rawStreamingText) {
        val raw = rawStreamingText
        if (raw.contains('<')) AgentToolProtocol.cleanFinalText(raw) else raw.trim()
    }
    val hasStreaming = streamingPreview.isNotBlank()
    // 流式预览算一个虚拟条目，滚动与空态判断都要把它计入。
    val itemCount = state.messages.size + if (hasStreaming) 1 else 0

    // 新消息落库（或流式预览条目首次出现）时平滑滚动到底部。
    LaunchedEffect(itemCount, state.isRunning) {
        if (itemCount == 0) return@LaunchedEffect
        if (listState.isNearBottom(itemCount)) listState.animateScrollToItem(itemCount - 1)
    }

    // 流式增量增长时贴着底部走。用瞬时滚动：每个 token 都重启一次动画会让滚动永远完不成。
    LaunchedEffect(streamingPreview) {
        if (streamingPreview.isBlank() || itemCount == 0) return@LaunchedEffect
        if (listState.isNearBottom(itemCount)) listState.scrollToItem(itemCount - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        val trailingContent: (@Composable RowScope.() -> Unit)? =
            if (onNewChat != null || onBack != null || onOpenSessions != null || canToggleInfo) {
                {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = strings.back
                            )
                        }
                    }
                    if (onOpenSessions != null) {
                        IconButton(onClick = onOpenSessions) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = strings.switchSession
                            )
                        }
                    }
                    if (onNewChat != null) {
                        IconButton(onClick = onNewChat) {
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = strings.newChat)
                        }
                    }
                    if (canToggleInfo) {
                        IconButton(onClick = { infoVisible = !infoVisible }) {
                            Icon(
                                imageVector = if (infoVisible) Icons.Filled.Info else Icons.Outlined.Info,
                                contentDescription =
                                if (infoVisible) strings.sidebarHide else strings.sidebarShow
                            )
                        }
                    }
                }
            } else {
                null
            }
        AppLargeTitle(
            title = strings.title,
            subtitle = if (state.isRunning) strings.subtitleRunning else strings.subtitleIdle,
            trailingContent = trailingContent
        )

        if (useSidebar && infoPanel != null) {
            // 宽屏：聊天列 + 右侧信息侧栏，输入区留在布局流内，不会被键盘/侧栏遮挡。
            // 侧栏收回时聊天列顶部退回紧凑信息条，避免模型 / 用量信息一起消失。
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()) {
                    if (!infoVisible) {
                        AgentInfoBar(
                            modelName = modelName,
                            providerLabel = providerLabel,
                            usage = state.usage,
                            strings = strings,
                            onOpenModelPanel = onOpenModelPanel,
                            onOpenDiagnostics = onOpenDiagnostics
                        )
                    }
                    AgentTranscript(
                        state = state,
                        streamingPreview = streamingPreview,
                        listState = listState,
                        strings = strings,
                        onCopyText = onCopyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
                if (infoVisible) {
                    Box(
                        modifier = Modifier
                            .width(0.8.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    )
                    Surface(
                        modifier = Modifier
                            .weight(SidebarWeight)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    horizontal = Spacing.expandedHorizontalPadding,
                                    vertical = Spacing.m
                                )
                        ) {
                            infoPanel()
                        }
                    }
                }
            }
        } else {
            AgentInfoBar(
                modelName = modelName,
                providerLabel = providerLabel,
                usage = state.usage,
                strings = strings,
                onOpenModelPanel = onOpenModelPanel,
                onOpenDiagnostics = onOpenDiagnostics,
                // 信息区收回时只留入口按钮：不丢模型面板 / 诊断，也不占对话流的高度。
                actionsOnly = !infoVisible
            )
            AgentTranscript(
                state = state,
                streamingPreview = streamingPreview,
                listState = listState,
                strings = strings,
                onCopyText = onCopyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        if (question != null) {
            AgentQuestionPanel(
                question = question,
                onAnswer = onAnswerQuestion,
                onDismiss = onDismissQuestion
            )
        }

        // 宽屏且侧栏展开时，运行状态 / 错误由信息侧栏展示；其余情况（单列、信息区收回）画底部状态条。
        if (!useSidebar || !infoVisible) {
            AgentStatusPanel(
                state = state,
                strings = strings,
                hasStreaming = hasStreaming,
                onCancel = onCancel
            )
        }

        AgentInputBar(
            enabled = !state.isRunning,
            draftKey = state.sessionId,
            strings = strings,
            onSend = onSend
        )
    }
}

/**
 * Agent 通过 ask_user 工具提出的问题。类型与宿主层解耦：集成层把 ViewModel 的
 * 待答问题映射到本类型即可，屏幕不依赖宿主的问题实现。
 */
data class AgentChatQuestion(
    val id: String,
    val prompt: String,
    val options: List<String> = emptyList()
)

/**
 * 单列时的顶部信息条：模型 / 提供方 / 用量 chip，以及模型面板与诊断入口。
 *
 * [actionsOnly] 为真时只画入口按钮（信息区被用户收回后的最小形态）：功能入口不丢，
 * 但不再占用对话流的垂直空间。
 */
@Composable
private fun AgentInfoBar(
    modelName: String?,
    providerLabel: String?,
    usage: AgentUsage,
    strings: AgentChatText,
    onOpenModelPanel: (() -> Unit)?,
    onOpenDiagnostics: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionsOnly: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.compactHorizontalPadding,
                end = Spacing.xs,
                top = Spacing.xs,
                bottom = Spacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (actionsOnly) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                item {
                    AppBadge(
                        text = modelName ?: strings.modelUnknown,
                        icon = Icons.Outlined.SmartToy,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                item {
                    AppBadge(
                        text = providerLabel ?: strings.providerUnknown,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    AppBadge(
                        text = strings.usageShortTemplate
                            .replace("{input}", usage.promptTokens.toString())
                            .replace("{output}", usage.completionTokens.toString())
                            .replace("{requests}", usage.requestCount.toString())
                    )
                }
            }
        }
        if (onOpenModelPanel != null) {
            IconButton(onClick = onOpenModelPanel) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = strings.modelPanelEntry
                )
            }
        }
        if (onOpenDiagnostics != null) {
            IconButton(onClick = onOpenDiagnostics) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = strings.diagnosticsEntry
                )
            }
        }
    }
}

/** 对话流：空态用 [AppEmptyState]，有消息（或流式预览）时用带稳定 key 的 LazyColumn。 */
@Composable
private fun AgentTranscript(
    state: AgentSessionState,
    streamingPreview: String,
    listState: LazyListState,
    strings: AgentChatText,
    onCopyText: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val hasStreaming = streamingPreview.isNotBlank()
    if (state.messages.isEmpty() && !hasStreaming) {
        AppEmptyState(
            icon = Icons.Outlined.SmartToy,
            title = strings.emptyTitle,
            description = strings.emptyDescription,
            modifier = modifier.fillMaxWidth()
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(
                horizontal = Spacing.compactHorizontalPadding,
                vertical = Spacing.s
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            items(state.messages, key = { it.id }) { message ->
                AgentMessageRow(message = message, strings = strings, onCopyText = onCopyText)
            }
            if (hasStreaming) {
                item(key = StreamingPreviewKey) {
                    AgentStreamingRow(text = streamingPreview, strings = strings)
                }
            }
        }
    }
}

/**
 * 流式预览行：最终答复落库前，把正在生成的文本先画出来。
 * 与助手气泡同一套形状，尾部带一个光标色块表示"还在生成"；不做长按复制——
 * 内容还没生成完，复制半段没有意义。
 */
@Composable
private fun AgentStreamingRow(
    text: String,
    strings: AgentChatText,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = BubbleMaxWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = strings.assistantLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = strings.streamingHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp
                ),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .size(width = 2.dp, height = 14.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}

/** 待答问题面板：选项按钮 + 自由文本回答，回答后由集成层恢复被阻塞的工具调用。 */
@Composable
private fun AgentQuestionPanel(
    question: AgentChatQuestion,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = AgentChatStrings.resolve(LocalAppStrings.current)
    var answer by rememberSaveable(question.id) { mutableStateOf("") }
    Surface(
        modifier = modifier.padding(
            horizontal = Spacing.compactHorizontalPadding,
            vertical = Spacing.s
        ),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (question.options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.s))
                question.options.forEach { option ->
                    AppSecondaryButton(
                        text = option,
                        onClick = { onAnswer(option) },
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(strings.questionAnswerPlaceholder) },
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (answer.isNotBlank()) {
                                onAnswer(answer)
                                answer = ""
                            }
                        }
                    )
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Box(modifier = Modifier.padding(bottom = Spacing.xs)) {
                    FilledIconButton(
                        onClick = {
                            onAnswer(answer)
                            answer = ""
                        },
                        enabled = answer.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = strings.send
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            AppSecondaryButton(
                text = strings.questionSkip,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 单条消息渲染。工具调用 / 回执走 [AgentToolRow] 摘要行；用户与助手消息长按复制原文。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentMessageRow(
    message: AgentMessage,
    strings: AgentChatText,
    onCopyText: ((String) -> Unit)?
) {
    val clipboardManager = LocalClipboardManager.current
    val copy: (String) -> Unit = { text ->
        if (onCopyText != null) onCopyText(text) else clipboardManager.setText(AnnotatedString(text))
    }
    when (message.role) {
        AgentRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier.widthIn(max = BubbleMaxWidth),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = strings.youLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClickLabel = strings.copyMessage,
                        onLongClick = { copy(message.content) }
                    )
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        AgentRole.ASSISTANT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(modifier = Modifier.widthIn(max = BubbleMaxWidth)) {
                Text(
                    text = strings.assistantLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClickLabel = strings.copyMessage,
                        onLongClick = { copy(message.content) }
                    )
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT -> AgentToolRow(
            message = message,
            strings = strings,
            onCopyRaw = copy
        )

        AgentRole.SYSTEM -> Text(
            text = message.content,
            style = MaterialTheme.typography.labelMedium,
            color = if (message.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
        )
    }
}

/** 单列时的底部状态条：思考中 + 停止按钮 + 错误信息。[hasStreaming] 为真时提示"正在生成"。 */
@Composable
private fun AgentStatusPanel(
    state: AgentSessionState,
    strings: AgentChatText,
    hasStreaming: Boolean,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = Spacing.compactHorizontalPadding)) {
        if (state.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = if (hasStreaming) strings.streamingHint else strings.thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                AppSecondaryButton(text = strings.cancel, onClick = onCancel)
            }
        }
        if (!state.error.isNullOrBlank()) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
    }
}

/**
 * 底部输入区：运行中禁用发送，保留 IME 发送动作与多行输入。
 *
 * [draftKey] 是草稿归属的会话 id：切换会话时草稿必须清空，否则会把上一段对话没发出去的
 * 文字带进新会话（甚至误发）。
 */
@Composable
private fun AgentInputBar(
    enabled: Boolean,
    draftKey: Long?,
    strings: AgentChatText,
    onSend: (String) -> Unit
) {
    var input by rememberSaveable(draftKey) { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.compactHorizontalPadding,
                vertical = Spacing.s
            ),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 128.dp),
            enabled = enabled,
            placeholder = { Text(strings.inputPlaceholder) },
            maxLines = 4,
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (input.isNotBlank() && enabled) {
                        onSend(input)
                        input = ""
                    }
                }
            )
        )
        Spacer(modifier = Modifier.width(Spacing.s))
        Box(modifier = Modifier.padding(bottom = Spacing.xs)) {
            FilledIconButton(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = enabled && input.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = strings.send
                )
            }
        }
    }
}
