package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun NativeAgentBranchDialog(ref: LocalProjectRef, chat: ChatId, id: MessageId,
    prepare: suspend (LocalProjectRef, ChatId, MessageId) -> AgentBranchPreview,
    inspect: suspend (LocalProjectRef, ChatId, String) -> AgentMessageDetail,
    save: suspend (AgentBranchPreview, CommandId, String?) -> Unit, dismiss: () -> Unit, done: () -> Unit) {
    var preview by remember(ref, chat, id) { mutableStateOf<AgentBranchPreview?>(null) }
    var edit by remember(ref, chat, id) { mutableStateOf(false) }
    var text by remember(ref, chat, id) { mutableStateOf("") }
    var busy by remember(ref, chat, id) { mutableStateOf(true) }
    var failed by remember(ref, chat, id) { mutableStateOf(false) }
    val command = remember(ref, chat, id, edit, text) { CommandId(java.util.UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref, chat, id) {
        try {
            preview = prepare(ref, chat, id)
            if (preview!!.editable) text = inspect(ref, chat, id.value).text
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    NativeContentDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(R.string.native_branch_title)) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.native_branch_notice))
            preview?.let { value ->
                Text(stringResource(R.string.native_branch_count, value.messages))
                if (value.editable) Row {
                    Checkbox(edit, { edit = it }, enabled = !busy)
                    Text(stringResource(R.string.native_branch_edit))
                }
            }
            if (edit) OutlinedTextField(text, { if (it.length <= 8000) text = it }, enabled = !busy, minLines = 4, maxLines = 12)
            if (failed) Text(stringResource(R.string.native_branch_error))
        } }, confirmButton = { TextButton(enabled = !busy && preview != null && (!edit || text.isNotBlank() && text.length <= 8000), onClick = {
            scope.launch {
                busy = true; failed = false
                try { save(requireNotNull(preview), command, text.takeIf { edit }); done() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.native_branch_title)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.native_cancel)) } })
}
