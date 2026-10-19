package com.breakyuna.noveltranslator.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakyuna.noveltranslator.core.agent.koog.AgentMessage
import com.breakyuna.noveltranslator.core.agent.koog.AgentRole
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionState
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppLargeTitle
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.Spacing

/**
 * Agent 对话页（纯展示层）。
 *
 * 所有状态经 [state] 注入，交互经回调上抛，不直接依赖数据库或 provider；
 * 需要完整行为时使用持有 [AgentChatController] 的重载。文案来自本页自带的
 * [AgentChatStrings]，跟随全局语言。
 */
@Composable
fun AgentChatScreen(
    state: AgentSessionState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    modelName: String? = null,
    onNewChat: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenSessions: (() -> Unit)? = null,
    question: AgentChatQuestion? = null,
    onAnswerQuestion: (String) -> Unit = {},
    onDismissQuestion: () -> Unit = {}
) {
    val appStrings = LocalAppStrings.current
    val strings = remember(appStrings) { AgentChatStrings.resolve(appStrings) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.isRunning) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val trailingContent: (@Composable RowScope.() -> Unit)? =
            if (onNewChat != null || onBack != null || onOpenSessions != null) {
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
                }
            } else {
                null
            }
        AppLargeTitle(
            title = strings.title,
            subtitle = if (state.isRunning) strings.subtitleRunning else strings.subtitleIdle,
            trailingContent = trailingContent
        )

        if (state.messages.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.SmartToy,
                title = strings.emptyTitle,
                description = strings.emptyDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    horizontal = Spacing.compactHorizontalPadding,
                    vertical = Spacing.s
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    AgentMessageRow(message = message, strings = strings)
                }
            }
        }

        if (question != null) {
            AgentQuestionPanel(
                question = question,
                onAnswer = onAnswerQuestion,
                onDismiss = onDismissQuestion
            )
        }

        AgentStatusPanel(
            state = state,
            strings = strings,
            modelName = modelName,
            onCancel = onCancel
        )

        AgentInputBar(
            enabled = !state.isRunning,
            strings = strings,
            onSend = onSend
        )
    }
}

/** 便捷重载：直接绑定控制器，省去调用方手工收集状态。 */
@Composable
fun AgentChatScreen(
    controller: AgentChatController,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenSessions: (() -> Unit)? = null,
    question: AgentChatQuestion? = null,
    onAnswerQuestion: (String) -> Unit = {},
    onDismissQuestion: () -> Unit = {}
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val modelName by controller.modelName.collectAsStateWithLifecycle()
    AgentChatScreen(
        state = state,
        onSend = controller::send,
        onCancel = controller::cancel,
        modifier = modifier,
        modelName = modelName,
        onNewChat = controller::startNewChat,
        onBack = onBack,
        onOpenSessions = onOpenSessions,
        question = question,
        onAnswerQuestion = onAnswerQuestion,
        onDismissQuestion = onDismissQuestion
    )
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

@Composable
private fun AgentMessageRow(message: AgentMessage, strings: AgentChatText) {
    when (message.role) {
        AgentRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        AgentRole.ASSISTANT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                tonalElevation = 0.dp
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            val isCall = message.role == AgentRole.TOOL_CALL
            val label = when {
                isCall -> strings.toolCallLabel
                message.isError -> strings.toolResultFailedLabel
                else -> strings.toolResultLabel
            }
            AppBadge(
                text = label,
                icon = Icons.Outlined.Build,
                containerColor = if (message.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (message.isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

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

@Composable
private fun AgentStatusPanel(
    state: AgentSessionState,
    strings: AgentChatText,
    modelName: String?,
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
                    text = strings.thinking,
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
        if (modelName != null) {
            Text(
                text = strings.modelTemplate.replace("{model}", modelName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = strings.usageTemplate
                .replace("{input}", state.usage.promptTokens.toString())
                .replace("{output}", state.usage.completionTokens.toString())
                .replace("{requests}", state.usage.requestCount.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
    }
}

@Composable
private fun AgentInputBar(
    enabled: Boolean,
    strings: AgentChatText,
    onSend: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
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
