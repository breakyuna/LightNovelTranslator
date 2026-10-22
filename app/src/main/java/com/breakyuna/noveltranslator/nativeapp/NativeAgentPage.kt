package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.TextApprovalState

/** Local S2 workflow, explicitly distinct from a real model chat. */
@Composable
internal fun NativeAgentPage(state: NativeProjectUiState, query: () -> Unit, prepare: (String, String) -> Unit,
                             allow: () -> Unit, reject: () -> Unit, navigate: (NativePage) -> Unit,
                             input: (String) -> Unit = {}, send: () -> Unit = {},
                             configure: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
                             newChat: () -> Unit = {}, selectChat: (com.breakyuna.noveltranslator.nativecore.domain.ChatId) -> Unit = {}) {
    val ref = state.project!!.ref
    var selected by rememberSaveable(ref.instanceId.value, ref.generation.value, state.items.firstOrNull()?.id?.value) {
        mutableStateOf(state.items.firstOrNull()?.id?.value)
    }
    var draft by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf("") }
    var expanded by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var showModel by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var showChats by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    if (showModel) NativeModelDialog(state.modelSettings, state.busy, state.error, { showModel = false }, configure)
    val selectedEntry = state.items.firstOrNull { it.id.value == selected }
    LazyColumn(Modifier.fillMaxSize().testTag("native-agent-page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.native_agent), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.native_agent_local_notice)) }
        if (state.agentLoadIncomplete) item {
            Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showModel = true }, enabled = !state.busy) { Text(stringResource(R.string.native_model_configure)) }
            TextButton(onClick = newChat, enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null && state.agentInput.isBlank()) {
                Text(stringResource(R.string.native_agent_new_chat))
            }
        } }
        item { Box {
            TextButton(onClick = { showChats = true }, enabled = !state.busy) { Text(stringResource(R.string.native_agent_choose_chat)) }
            DropdownMenu(expanded = showChats, onDismissRequest = { showChats = false }) {
                state.agentChats.forEachIndexed { index, chat -> DropdownMenuItem(text = {
                    Text(stringResource(R.string.native_agent_chat_number, state.agentChats.size - index))
                }, enabled = state.agentInput.isBlank(), onClick = { showChats = false; selectChat(chat) }) }
            }
        } }
        item { Text(if (state.modelSettings == null) stringResource(R.string.native_model_missing)
            else stringResource(R.string.native_model_remote_notice), style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(value = state.agentInput, onValueChange = input,
            label = { Text(stringResource(R.string.native_agent_message)) }, enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null,
            modifier = Modifier.fillMaxWidth(), minLines = 2) }
        item { Button(onClick = send, enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null && state.modelSettings != null && state.agentInput.isNotBlank()) {
            Text(stringResource(R.string.native_agent_send))
        } }
        item { Button(onClick = query, enabled = !state.busy && !state.agentLoadIncomplete) { Text(stringResource(R.string.native_agent_query)) } }
        items(state.agentMessages, key = { it.id }) { line -> Card(Modifier.fillMaxWidth()) {
            Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        } }
        items(state.agentResults, key = { "result-" + it.id }) { result ->
            Text(stringResource(R.string.native_edit_row, result.row + 1) + " · " + stringResource(when (result.state) {
                TextApprovalState.COMMITTED -> R.string.native_agent_result_saved
                TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
                TextApprovalState.CONFLICT -> R.string.native_agent_result_conflict
                TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                else -> R.string.native_agent_unresolved
            }), style = MaterialTheme.typography.bodySmall)
        }
        item { Text(stringResource(R.string.native_agent_prepare), style = MaterialTheme.typography.titleMedium) }
        item { Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null && state.items.isNotEmpty()) {
                Text(if (selectedEntry == null) stringResource(R.string.native_empty_items) else stringResource(R.string.native_edit_row, selectedEntry.row + 1))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.items.forEach { row -> DropdownMenuItem(text = {
                    Text(stringResource(R.string.native_edit_row, row.row + 1) + ": " + row.source.take(40), maxLines = 2)
                }, onClick = { selected = row.id.value; expanded = false }) }
            }
        } }
        item { OutlinedTextField(value = draft, onValueChange = { if (it.length <= 65_536) draft = it },
            enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null, label = { Text(stringResource(R.string.native_translation)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3) }
        item { OutlinedButton(onClick = { selectedEntry?.let { prepare(it.id.value.toString(), draft) } },
            enabled = !state.busy && !state.agentLoadIncomplete && state.approval == null && selectedEntry != null) { Text(stringResource(R.string.native_agent_prepare)) } }
        state.approval?.let { approval ->
            item(key = "approval-" + approval.id.value) { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_agent_approval_title, approval.row + 1), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_agent_approval_effect))
                    Text(stringResource(R.string.native_row_status, approval.row + 1, stringResource(when (approval.beforeStatus) {
                        com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus.NONE -> R.string.native_status_none
                        com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus.PROCESSED -> R.string.native_status_processed
                        com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus.EXCLUDED -> R.string.native_status_excluded
                    })))
                    Text(stringResource(R.string.native_agent_before), style = MaterialTheme.typography.labelLarge)
                    Text(approval.before.ifEmpty { stringResource(R.string.native_empty_result) })
                    Text(stringResource(R.string.native_agent_after), style = MaterialTheme.typography.labelLarge)
                    Text(approval.after.ifEmpty { stringResource(R.string.native_empty_result) })
                    if (approval.state == TextApprovalState.CONSUMED) Text(stringResource(R.string.native_agent_unresolved))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = allow, enabled = !state.busy && !state.agentLoadIncomplete && approval.state == TextApprovalState.PENDING) {
                            Text(stringResource(R.string.native_agent_allow_once))
                        }
                        OutlinedButton(onClick = reject, enabled = !state.busy && !state.agentLoadIncomplete && approval.state == TextApprovalState.PENDING) {
                            Text(stringResource(R.string.native_agent_reject))
                        }
                    }
                }
            } }
        }
        item { TextButton(onClick = { navigate(NativePage.PROOFREADING) }, enabled = !state.busy) {
            Text(stringResource(R.string.native_open_proofreading))
        } }
    }
}
