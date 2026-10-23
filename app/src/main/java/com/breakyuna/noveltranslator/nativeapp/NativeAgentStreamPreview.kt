package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    val scroll = rememberScrollState()
    val activate by rememberUpdatedState(onActive)
    LaunchedEffect(preview.key) { if (preview.phase != AgentPreviewPhase.INTERRUPTED) activate() }
    val following = scroll.value >= scroll.maxValue - 24
    LaunchedEffect(state.revision, thinking) {
        withFrameNanos { }
        if (following && !scroll.isScrollInProgress && preview.phase != AgentPreviewPhase.INTERRUPTED) scroll.scrollTo(scroll.maxValue)
    }
    Card(Modifier.fillMaxWidth().testTag("native-agent-stream-preview")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (preview.phase) {
                AgentPreviewPhase.RECEIVING -> R.string.native_stream_receiving
                AgentPreviewPhase.RECORDING -> R.string.native_stream_recording
                AgentPreviewPhase.INTERRUPTED -> R.string.native_stream_interrupted
            }), style = MaterialTheme.typography.labelMedium)
            if (preview.phase == AgentPreviewPhase.RECEIVING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preview.phase == AgentPreviewPhase.INTERRUPTED) Text(stringResource(R.string.native_stream_partial_notice), style = MaterialTheme.typography.bodySmall)
            if (preview.clipped) Text(stringResource(R.string.native_stream_tail), style = MaterialTheme.typography.labelSmall)
            if (preview.thinking.isNotEmpty()) TextButton(onClick = { thinking = !thinking }) { Text(stringResource(R.string.native_agent_reasoning)) }
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (thinking) Text(preview.thinking, style = MaterialTheme.typography.bodySmall)
                    if (preview.text.isNotEmpty()) Text(preview.text)
                }
            }
            if (preview.toolCalls > 0) Text(stringResource(R.string.native_stream_tools, preview.toolCalls), style = MaterialTheme.typography.bodySmall)
        }
    }
}
