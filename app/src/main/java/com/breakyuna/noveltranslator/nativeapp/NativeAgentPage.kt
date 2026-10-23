package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.AgentStreamPreviewState
import kotlinx.coroutines.flow.StateFlow

/** Conversation first. Local exercises and execution diagnostics are explicitly expandable. */
@Composable
internal fun NativeAgentPage(state: NativeProjectUiState, query: () -> Unit, prepare: (String, String) -> Unit,
    allow: () -> Unit, reject: () -> Unit, navigate: (NativePage) -> Unit,
    input: (String) -> Unit = {}, send: () -> Unit = {},
    configure: (String, String, String?, () -> Unit) -> Unit = { _, _, _, _ -> },
    newChat: () -> Unit = {}, selectChat: (ChatId) -> Unit = {},
    fetchModels: (String, String, ModelConfigurationRef?, (List<String>) -> Unit) -> Unit = { _, _, _, _ -> },
    cancel: () -> Unit = {}, inspect: (String) -> Unit = {}, closeDetail: () -> Unit = {},
    stream: StateFlow<AgentStreamPreviewState>? = null) {
    val ref = state.project!!.ref
    val ready = !state.busy && !state.agentLoadIncomplete && !state.runLoadIncomplete
    var showModel by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var showChats by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var tools by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf(false) }
    var runs by rememberSaveable(ref.instanceId.value, ref.generation.value) { mutableStateOf(false) }
    val interrupted = state.recentRuns.any { it.state == StoredRunState.INTERRUPTED }
    LaunchedEffect(interrupted) { if (interrupted) runs = true }
    if (showModel) NativeModelDialog(state.modelSettings, state.busy, state.modelSettingsError, state.modelCatalogLoading,
        { showModel = false }, configure, fetchModels, cancel)
    state.agentMessageDetail?.let { NativeAgentMessageDialog(it, closeDetail) }
    val timeline = rememberLazyListState()
    val committedIds = remember(state.agentMessages) { state.agentMessages.map { it.id }.toSet() }
    val showPreview = rememberAgentStreamPreviewVisible(ref, state.agentChatId, committedIds, stream)
    val followTail by remember { derivedStateOf {
        timeline.layoutInfo.totalItemsCount == 0 ||
            (timeline.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= timeline.layoutInfo.totalItemsCount - 4
    } }
    var openedChat by remember(ref.instanceId, ref.generation, state.agentChatId) { mutableStateOf(false) }
    LaunchedEffect(ref, state.agentChatId, state.agentMessages.lastOrNull()?.id) {
        if (state.agentMessages.isNotEmpty()) {
            if (!openedChat || followTail) {
                val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                timeline.animateScrollToItem(warnings + state.agentMessages.lastIndex)
            }
            openedChat = true
        } else {
            timeline.scrollToItem(0)
            openedChat = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.native_agent), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { showModel = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text(state.modelSettings?.model ?: stringResource(R.string.native_model_configure), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { showChats = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.native_agent_choose_chat), maxLines = 2)
                }
                DropdownMenu(showChats, { showChats = false }) {
                    state.agentChats.forEachIndexed { index, chat -> DropdownMenuItem(text = {
                        Text((if (chat == state.agentChatId) "✓ " else "") + stringResource(R.string.native_agent_chat_number, state.agentChats.size - index))
                    }, enabled = state.agentInput.isBlank(), onClick = { showChats = false; selectChat(chat) }) }
                }
            }
            TextButton(onClick = newChat, enabled = ready && state.approval == null && state.agentInput.isBlank(), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.native_agent_new_chat), maxLines = 2)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("native-agent-page"), state = timeline,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.agentLoadIncomplete) item(key = "load-error") { Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error) }
            if (state.runLoadIncomplete) item(key = "run-error") { Text(stringResource(R.string.native_runs_load_error), color = MaterialTheme.colorScheme.error) }
            if (state.agentMessages.isEmpty()) item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_agent_welcome), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_agent_welcome_body))
                    OutlinedButton(onClick = query, enabled = ready) { Text(stringResource(R.string.native_agent_query)) }
                }
            }
            items(state.agentMessages, key = { "message-" + it.id }) { line -> NativeAgentBubble(line, ready, inspect) }
            if (showPreview && stream != null) item(key = "stream-preview") {
                NativeAgentStreamPreview(ref, state.agentChatId, committedIds, stream) {
                    if (followTail && !timeline.isScrollInProgress) {
                        val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                        timeline.animateScrollToItem(warnings + if (state.agentMessages.isEmpty()) 1 else state.agentMessages.size)
                    }
                }
            }
            state.approval?.let { approval -> item(key = "approval-" + approval.id.value) { NativeApprovalCard(approval, ready, allow, reject) } }
            items(state.agentResults, key = { "result-" + it.id }) { result ->
                Text(stringResource(R.string.native_edit_row, result.row + 1) + " · " + stringResource(when (result.state) {
                    TextApprovalState.COMMITTED -> R.string.native_agent_result_saved
                    TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
                    TextApprovalState.CONFLICT -> R.string.native_agent_result_conflict
                    TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                    else -> R.string.native_agent_unresolved
                }), style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) item(key = "working") { Text(stringResource(R.string.native_working), style = MaterialTheme.typography.bodySmall) }
            item(key = "open-editor") { TextButton(onClick = { navigate(NativePage.PROOFREADING) }, enabled = !state.busy) { Text(stringResource(R.string.native_open_proofreading)) } }
            if (state.recentRuns.isNotEmpty()) item(key = "runs") {
                Column {
                    TextButton(onClick = { runs = !runs }) { Text(stringResource(R.string.native_runs_title)) }
                    if (runs) NativeRunHistory(state)
                }
            }
            item(key = "local-tools") {
                Column {
                    TextButton(onClick = { tools = !tools }, enabled = !state.busy) { Text(stringResource(R.string.native_agent_local_tools)) }
                    if (tools) NativeLocalAgentTools(state, query, prepare)
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.modelSettings == null) TextButton(onClick = { showModel = true }, enabled = !state.busy) { Text(stringResource(R.string.native_model_missing)) }
                else Text(stringResource(R.string.native_model_remote_notice), style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (state.approval != null) Text(stringResource(R.string.native_agent_waiting_hint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.agentInput, input, modifier = Modifier.weight(1f), minLines = 2, maxLines = 5,
                        enabled = ready && state.approval == null, label = { Text(stringResource(R.string.native_agent_message)) })
                    if (state.busy) Button(onClick = cancel) { Text(stringResource(R.string.native_agent_stop)) }
                    else Button(onClick = send, enabled = ready && state.approval == null && state.modelSettings != null && state.agentInput.isNotBlank()) {
                        Text(stringResource(R.string.native_agent_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun roleLabel(role: String): String = stringResource(when (role) {
    "USER" -> R.string.native_role_you
    "ASSISTANT" -> R.string.native_role_assistant
    "TOOL" -> R.string.native_role_tool
    else -> R.string.native_role_system
})

@Composable
private fun NativeAgentBubble(line: AgentChatLine, enabled: Boolean, inspect: (String) -> Unit) {
    val user = line.role == "USER"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(Modifier.widthIn(max = 720.dp).fillMaxWidth(if (user) .92f else 1f), colors = CardDefaults.cardColors(
            containerColor = if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(roleLabel(line.role), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(line.text.ifBlank { stringResource(R.string.native_agent_tool_activity) }, maxLines = 12, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { inspect(line.id) }, enabled = enabled) { Text(stringResource(R.string.native_agent_message_details)) }
            }
        }
    }
}

@Composable
private fun NativeLocalAgentTools(state: NativeProjectUiState, query: () -> Unit, prepare: (String, String) -> Unit) {
    val ref = state.project!!.ref
    val ready = !state.busy && !state.agentLoadIncomplete && !state.runLoadIncomplete
    var selected by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value, state.items.firstOrNull()?.id?.value) { mutableStateOf(state.items.firstOrNull()?.id?.value) }
    var draft by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf("") }
    var expanded by remember(ref, state.agentChatId) { mutableStateOf(false) }
    val entry = state.items.firstOrNull { it.id.value == selected }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_agent_local_notice))
        if (state.agentMessages.isNotEmpty()) OutlinedButton(onClick = query, enabled = ready) { Text(stringResource(R.string.native_agent_query)) }
        Text(stringResource(R.string.native_agent_prepare), style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = ready && state.approval == null && state.items.isNotEmpty()) {
                Text(if (entry == null) stringResource(R.string.native_empty_items) else stringResource(R.string.native_edit_row, entry.row + 1))
            }
            DropdownMenu(expanded, { expanded = false }) {
                state.items.forEach { row -> DropdownMenuItem(text = { Text(stringResource(R.string.native_edit_row, row.row + 1) + ": " + row.source.take(40), maxLines = 2) },
                    onClick = { selected = row.id.value; expanded = false }) }
            }
        }
        OutlinedTextField(draft, { if (it.length <= 65_536) draft = it }, enabled = ready && state.approval == null,
            label = { Text(stringResource(R.string.native_translation)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        OutlinedButton(onClick = { entry?.let { prepare(it.id.value.toString(), draft) } }, enabled = ready && state.approval == null && entry != null) {
            Text(stringResource(R.string.native_agent_prepare))
        }
    }
}

@Composable
private fun NativeApprovalCard(approval: TextApproval, enabled: Boolean, allow: () -> Unit, reject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_agent_approval_title, approval.row + 1), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_agent_approval_effect))
            Text(stringResource(R.string.native_row_status, approval.row + 1, stringResource(when (approval.beforeStatus) {
                TextItemStatus.NONE -> R.string.native_status_none
                TextItemStatus.PROCESSED -> R.string.native_status_processed
                TextItemStatus.EXCLUDED -> R.string.native_status_excluded
            })))
            Text(stringResource(R.string.native_agent_before), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(approval.before.ifEmpty { stringResource(R.string.native_empty_result) }) }
            Text(stringResource(R.string.native_agent_after), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(approval.after.ifEmpty { stringResource(R.string.native_empty_result) }) }
            if (approval.state == TextApprovalState.CONSUMED) Text(stringResource(R.string.native_agent_unresolved))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = allow, enabled = enabled && approval.state == TextApprovalState.PENDING) { Text(stringResource(R.string.native_agent_allow_once)) }
                OutlinedButton(onClick = reject, enabled = enabled && approval.state == TextApprovalState.PENDING) { Text(stringResource(R.string.native_agent_reject)) }
            }
        }
    }
}

@Composable
private fun NativeAgentMessageDialog(detail: AgentMessageDetail, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(detail.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(roleLabel(detail.role)) }, text = {
        SelectionContainer { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail.text.isNotEmpty()) Text(detail.text)
            if (detail.reasoning.isNotEmpty()) { Text(stringResource(R.string.native_agent_reasoning), style = MaterialTheme.typography.titleSmall); Text(detail.reasoning) }
            if (detail.tools.isNotEmpty()) { Text(stringResource(R.string.native_agent_tool_details), style = MaterialTheme.typography.titleSmall); Text(detail.tools) }
        } }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(listOf(detail.text, detail.reasoning, detail.tools).filter { it.isNotEmpty() }.joinToString("\n\n"))); copied = true }) {
            Text(stringResource(if (copied) R.string.native_copied else R.string.native_copy_message))
        } })
}
