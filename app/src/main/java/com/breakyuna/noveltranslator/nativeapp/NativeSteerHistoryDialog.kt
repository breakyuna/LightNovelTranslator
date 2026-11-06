package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeSteerHistoryDialog(ref: LocalProjectRef, chat: ChatId,
    load: suspend (LocalProjectRef, ChatId, AgentSteerRunCursor?) -> AgentSteerRunPage,
    detail: suspend (LocalProjectRef, ChatId, RunIdentity) -> StoredAgentSteerLedger?,
    recover: (LocalProjectRef, RunIdentity, CommandId) -> Unit, dismiss: () -> Unit) {
    var rows by remember(ref, chat) { mutableStateOf<List<AgentSteerRunSummary>>(emptyList()) }
    var cursor by remember(ref, chat) { mutableStateOf<AgentSteerRunCursor?>(null) }
    var ledger by remember(ref, chat) { mutableStateOf<StoredAgentSteerLedger?>(null) }
    var failed by remember(ref, chat) { mutableStateOf(false) }
    var busy by remember(ref, chat) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(append: Boolean) {
        if (busy) return
        busy = true; failed = false
        try {
            val page = load(ref, chat, if (append) cursor else null)
            require(!append || page.next == null || page.next != cursor)
            rows = ((if (append) rows else emptyList()) + page.runs).distinctBy { it.run }
            cursor = page.next
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    LaunchedEffect(ref, chat) { fetch(false) }
    NativeContentDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.native_steer_history)) },
        text = { Column {
            Text(stringResource(R.string.native_steer_history_notice))
            if (failed) Text(stringResource(R.string.native_io_error))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val selected = ledger
                if (selected == null) {
                    items(rows, key = { it.run.runId.value + ":" + it.run.commandId.value }) { row ->
                        TextButton(enabled = !busy, onClick = { scope.launch {
                            busy = true; failed = false
                            try { ledger = detail(ref, chat, row.run) ?: error("Missing steer ledger") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { busy = false }
                        } }) { Text(row.run.runId.value) }
                    }
                    if (rows.isEmpty() && !busy && !failed) item { Text(stringResource(R.string.native_ledger_empty)) }
                    if (cursor != null) item { TextButton(enabled = !busy, onClick = { scope.launch { fetch(true) } }) {
                        Text(stringResource(R.string.native_tasks_more))
                    } }
                } else {
                    item { Text(stringResource(steerStageLabel(selected.state.stage))) }
                    items(selected.state.attempts, key = { it.id.value }) { attempt ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
                            Text(stringResource(when (attempt.status) {
                                AgentSteerStatus.ACCEPTED -> R.string.native_steer_status_accepted
                                AgentSteerStatus.INJECTED -> R.string.native_steer_status_injected
                                AgentSteerStatus.REJECTED -> R.string.native_steer_status_rejected
                                AgentSteerStatus.CANCELLED -> R.string.native_steer_status_cancelled
                            }))
                            Text(attempt.text)
                            if (attempt.status == AgentSteerStatus.ACCEPTED || attempt.status == AgentSteerStatus.CANCELLED)
                                TextButton(onClick = { recover(ref, selected.state.run, attempt.id); dismiss() }) {
                                    Text(stringResource(R.string.native_steer_restore))
                                }
                        } }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = {
            ledger = null; scope.launch { fetch(false) }
        }) { Text(stringResource(R.string.native_refresh)) } })
}

private fun steerStageLabel(stage: AgentRunStage): Int = when (stage) {
    AgentRunStage.MODEL_REQUEST -> R.string.native_steer_stage_model
    AgentRunStage.TOOL_EXECUTION -> R.string.native_steer_stage_tools
    AgentRunStage.TOOL_LOOP_BOUNDARY -> R.string.native_steer_stage_boundary
    AgentRunStage.STOPPING -> R.string.native_steer_stage_stopping
    AgentRunStage.STOPPED -> R.string.native_steer_stage_stopped
    AgentRunStage.FINISHED -> R.string.native_steer_stage_finished
    AgentRunStage.FAILED -> R.string.native_steer_stage_failed
}
