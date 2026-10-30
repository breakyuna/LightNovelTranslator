package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
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
    val activate by rememberUpdatedState(onActive)
    var contentHeight by remember(ref, preview.key) { mutableStateOf(0) }
    LaunchedEffect(ref, preview.key, contentHeight) {
        withFrameNanos { }
        if (preview.phase != AgentPreviewPhase.INTERRUPTED) activate()
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(ref, preview.key) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    val status = stringResource(when (preview.phase) {
        AgentPreviewPhase.RECEIVING -> if (preview.text.isEmpty()) R.string.native_agent_thinking else R.string.native_stream_receiving
        AgentPreviewPhase.RECORDING -> R.string.native_stream_recording
        AgentPreviewPhase.INTERRUPTED -> R.string.native_stream_interrupted
    })
    val tone = when (preview.phase) {
        AgentPreviewPhase.RECEIVING -> NativeTone.PRIMARY
        AgentPreviewPhase.RECORDING -> NativeTone.NEUTRAL
        AgentPreviewPhase.INTERRUPTED -> NativeTone.ERROR
    }
    Column(
        modifier = Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }
            .testTag("native-agent-stream-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (preview.thinking.isNotEmpty()) {
            NativeAgentMessageFoldout(stringResource(R.string.native_agent_reasoning), stateKey = listOf(ref, chat, preview.key),
                tone = tone, status = status) {
                SelectionContainer { NativeMarkdown(preview.thinking) }
            }
        }
        if (preview.phase == AgentPreviewPhase.INTERRUPTED) {
            Text(stringResource(R.string.native_stream_partial_notice),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (preview.clipped) {
            Text(stringResource(R.string.native_stream_tail),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (preview.text.isNotEmpty()) SelectionContainer { NativeMarkdown(preview.text) }
        if (preview.toolCalls > 0) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.native_stream_tools, preview.toolCalls), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                NativeAgentActivityIndicator(tone, status)
            }
        }
        if (preview.text.isNotEmpty()) {
            NativeAgentMessageActions(copied, true, {
                clipboard.setText(AnnotatedString(preview.text))
                copied = true
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(status, modifier = Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.labelSmall,
                color = if (preview.phase == AgentPreviewPhase.INTERRUPTED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            NativeAgentActivityIndicator(tone, status)
        }
    }
}
