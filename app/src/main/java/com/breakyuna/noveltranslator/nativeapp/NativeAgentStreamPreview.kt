package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private fun AgentStreamPreview?.visibleFor(ref: LocalProjectRef, chat: ChatId?, committedIds: Set<String>): Boolean =
    this != null && this.ref == ref && key.run.chatId == chat && (messageId?.value?.let { it in committedIds } != true)

/** Only presence changes reach the page; token deltas remain local to the preview item. */
@Composable
internal fun rememberAgentStreamPreviewVisible(ref: LocalProjectRef, chat: ChatId?, committedIds: Set<String>,
    stream: StateFlow<AgentStreamPreviewState>?): Boolean {
    if (stream == null) return false
    return key(stream, ref, chat, committedIds) {
        val visibility = remember {
            stream.map { it.preview.visibleFor(ref, chat, committedIds) }.distinctUntilChanged()
        }
        val visible by visibility.collectAsStateWithLifecycle(initialValue = stream.value.preview.visibleFor(ref, chat, committedIds))
        visible
    }
}

/** High-frequency flow is collected only by this visible timeline item, never by the whole project page. */
@Composable
internal fun NativeAgentStreamPreview(ref: LocalProjectRef, chat: ChatId?, committedIds: Set<String>,
    stream: StateFlow<AgentStreamPreviewState>, onActive: suspend () -> Unit = {}) {
    val state by stream.collectAsStateWithLifecycle()
    val preview = state.preview ?: return
    if (!preview.visibleFor(ref, chat, committedIds)) return
    var thinking by rememberSaveable(ref.instanceId.value, ref.generation.value, chat?.value,
        preview.key.run.runId.value, preview.key.attemptId.value) { mutableStateOf(false) }
    val activate by rememberUpdatedState(onActive)
    var contentHeight by remember(preview.key) { mutableStateOf(0) }
    LaunchedEffect(preview.key, contentHeight, thinking) {
        withFrameNanos { }
        if (preview.phase != AgentPreviewPhase.INTERRUPTED) activate()
    }
    Card(
        modifier = Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }.testTag("native-agent-stream-preview"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (preview.phase == AgentPreviewPhase.INTERRUPTED) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (preview.phase) {
                            AgentPreviewPhase.RECEIVING -> if (preview.text.isEmpty()) R.string.native_agent_thinking else R.string.native_stream_receiving
                            AgentPreviewPhase.RECORDING -> R.string.native_stream_recording
                            AgentPreviewPhase.INTERRUPTED -> R.string.native_stream_interrupted
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.thinking.isNotEmpty()) {
                    TextButton(
                        onClick = { thinking = !thinking },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.native_agent_reasoning), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (preview.phase == AgentPreviewPhase.INTERRUPTED) {
                Text(
                    text = stringResource(R.string.native_stream_partial_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (preview.clipped) {
                Text(
                    text = stringResource(R.string.native_stream_tail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (thinking) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            NativeMarkdown(preview.thinking, Modifier.padding(8.dp))
                        }
                    }
                    if (preview.text.isNotEmpty()) NativeMarkdown(preview.text)
                }
            }
            if (preview.toolCalls > 0) {
                NativeStatusBadge(
                    text = stringResource(R.string.native_stream_tools, preview.toolCalls),
                    tone = NativeTone.NEUTRAL,
                )
            }
        }
    }
}
