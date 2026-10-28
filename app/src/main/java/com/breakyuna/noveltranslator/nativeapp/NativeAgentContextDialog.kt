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
internal fun NativeAgentContextDialog(ref: LocalProjectRef, chat: ChatId,
    prepare: suspend (LocalProjectRef, ChatId) -> AgentContextPreview,
    save: suspend (AgentContextPreview, String) -> Unit, dismiss: () -> Unit) {
    var preview by remember(ref, chat) { mutableStateOf<AgentContextPreview?>(null) }
    var text by remember(ref, chat) { mutableStateOf("") }
    var failed by remember(ref, chat) { mutableStateOf(false) }
    var busy by remember(ref, chat) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref, chat) {
        try { preview = prepare(ref, chat); text = preview!!.previousSummary }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    NativeContentDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(R.string.native_context_title)) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.native_context_notice))
            preview?.let { Text(stringResource(R.string.native_context_counts, it.archivedMessages, it.retainedMessages)) }
            OutlinedTextField(text, { if (it.length <= 16_384) text = it }, minLines = 5, maxLines = 12,
                enabled = !busy && preview != null, label = { Text(stringResource(R.string.native_context_summary)) })
            if (failed) Text(stringResource(R.string.native_context_error))
        } }, confirmButton = { TextButton(enabled = !busy && preview != null && text.isNotBlank(), onClick = {
            scope.launch {
                busy = true; failed = false
                try { save(requireNotNull(preview), text); dismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.native_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.native_cancel)) } })
}
