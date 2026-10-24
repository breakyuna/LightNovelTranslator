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
internal fun NativeAgentHistoryDialog(ref: LocalProjectRef, chat: ChatId,
    load: suspend (LocalProjectRef, ChatId, AgentMessageCursor?) -> AgentMessagePage,
    inspect: suspend (LocalProjectRef, ChatId, String) -> AgentMessageDetail, dismiss: () -> Unit,
    prepareBranch: (suspend (LocalProjectRef, ChatId, MessageId) -> AgentBranchPreview)? = null,
    createBranch: (suspend (AgentBranchPreview, CommandId, String?) -> Unit)? = null) {
    var page by remember(ref, chat) { mutableStateOf<AgentMessagePage?>(null) }
    var current by remember(ref, chat) { mutableStateOf<AgentMessageCursor?>(null) }
    var back by remember(ref, chat) { mutableStateOf<List<AgentMessageCursor?>>(emptyList()) }
    var branch by remember(ref, chat) { mutableStateOf<MessageId?>(null) }
    var detail by remember(ref, chat) { mutableStateOf<AgentMessageDetail?>(null) }
    var busy by remember(ref, chat) { mutableStateOf(false) }
    var failed by remember(ref, chat) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(cursor: AgentMessageCursor?): Boolean {
        if (busy) return false
        busy = true; failed = false
        return try {
            val next = load(ref, chat, cursor)
            require(cursor == null || next.next == null || next.next!!.beforeTime < cursor.beforeTime ||
                next.next!!.beforeTime == cursor.beforeTime && next.next!!.beforeId.value < cursor.beforeId.value)
            page = next; current = cursor
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; false }
        finally { busy = false }
    }
    LaunchedEffect(ref, chat) { fetch(null) }
    branch?.let { id ->
        if (prepareBranch != null && createBranch != null) {
            NativeAgentBranchDialog(ref, chat, id, prepareBranch, inspect, createBranch, { branch = null }, dismiss)
            return
        }
    }
    detail?.let { NativeAgentMessageDialog(it, { detail = null }); return }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.native_agent_full_history)) },
        text = { Column {
            Text(stringResource(R.string.native_agent_history_notice))
            if (failed) Text(stringResource(R.string.native_io_error))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(page?.lines.orEmpty(), key = { it.id }) { line ->
                    Column {
                    if (prepareBranch != null && createBranch != null) TextButton(enabled = !busy,
                        onClick = { branch = MessageId(line.id) }) { Text(stringResource(R.string.native_branch_title)) }
                    NativeAgentBubble(line, !busy) { id -> scope.launch {
                        busy = true; failed = false
                        try { detail = inspect(ref, chat, id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    } }
                    }
                }
                if (page?.lines?.isEmpty() == true) item { Text(stringResource(R.string.native_ledger_empty)) }
            }
            Row {
                TextButton(enabled = !busy && back.isNotEmpty(), onClick = { scope.launch {
                    if (fetch(back.last())) back = back.dropLast(1)
                } }) { Text(stringResource(R.string.native_agent_history_newer)) }
                TextButton(enabled = !busy && page?.next != null, onClick = { scope.launch {
                    val previous = current
                    val next = page?.next ?: return@launch
                    if (fetch(next)) back = back + listOf(previous)
                } }) { Text(stringResource(R.string.native_agent_history_older)) }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            if (fetch(null)) back = emptyList()
        } }) { Text(stringResource(R.string.native_refresh)) } })
}
