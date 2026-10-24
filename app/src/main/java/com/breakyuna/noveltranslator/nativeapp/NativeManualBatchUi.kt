package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

internal class NativeBatchActions(
    val selecting: (Boolean) -> Unit, val toggle: (ItemId) -> Unit, val all: () -> Unit,
    val prepare: (TextBatchChange) -> Unit, val confirm: (ManualTextBatch) -> Unit, val dismiss: (ManualTextBatch) -> Unit,
    val prepareQuery: ((TextBatchChange) -> Unit)? = null,
)

@Composable
internal fun NativeBatchToolbar(state: NativeProjectUiState, actions: NativeBatchActions) {
    var menu by remember(state.project?.ref) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.native_batch_selected, state.selectedItems.size), Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = actions.all, enabled = !state.busy) { Text(stringResource(R.string.native_batch_all_page)) }
        Box {
            TextButton(onClick = { menu = true }, enabled = !state.busy && state.selectedItems.isNotEmpty(),
                modifier = Modifier.testTag("native-batch-actions")) { Text(stringResource(R.string.native_batch_actions)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                TextBatchChange.entries.forEach { change ->
                    DropdownMenuItem(text = { Text(stringResource(change.label())) }, onClick = { menu = false; actions.prepare(change) })
                }
            }
        }
    }
}

@Composable
internal fun NativeQueryBatchButton(state: NativeProjectUiState, actions: NativeBatchActions) {
    val prepare = actions.prepareQuery ?: return
    var menu by remember(state.project?.ref, state.proofreadingQuery) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { menu = true }, enabled = !state.busy && state.editor == null &&
            state.manualBatch == null && (state.qualityView == null || state.qualityCurrent) && state.policyDraft?.dirty != true &&
            !state.policyLoadIncomplete && !state.runLoadIncomplete && (state.matchingItems ?: 0) > 0,
            modifier = Modifier.testTag("native-query-batch-actions")) { Text(stringResource(if (state.qualityView == null) R.string.native_batch_all_query else R.string.native_batch_quality_selection)) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            TextBatchChange.entries.forEach { change ->
                DropdownMenuItem(text = { Text(stringResource(change.label())) }, onClick = { menu = false; prepare(change) })
            }
        }
    }
}

@Composable
internal fun NativeManualBatchDialog(state: NativeProjectUiState, actions: NativeBatchActions) {
    val batch = state.manualBatch ?: return
    AlertDialog(onDismissRequest = { if (!state.busy) actions.dismiss(batch) }, modifier = Modifier.testTag("native-batch-dialog"),
        title = { Text(stringResource(R.string.native_batch_confirm)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.project?.let { Text(it.name, style = MaterialTheme.typography.titleSmall) }
                if (state.manualBatchQuery) Text(stringResource(R.string.native_batch_query_frozen))
                Text(stringResource(R.string.native_batch_preview, batch.targets.size, stringResource(batch.change.label())))
                Text(stringResource(R.string.native_batch_rows, batch.targets.take(10).joinToString(", ") { (it.row.toLong() + 1).toString() }))
                if (batch.change.clearsTranslation) Text(stringResource(R.string.native_batch_clear_warning), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.native_batch_retry), style = MaterialTheme.typography.bodySmall)
                if (state.manualBatchConflict) Text(stringResource(R.string.native_batch_conflict), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { actions.confirm(batch) }, enabled = !state.busy && !state.manualBatchConflict,
            modifier = Modifier.testTag("native-batch-confirm")) { Text(stringResource(R.string.native_batch_apply)) } },
        dismissButton = { TextButton(onClick = { actions.dismiss(batch) }, enabled = !state.busy) { Text(stringResource(R.string.native_cancel)) } },
    )
}

internal fun TextBatchChange.label(): Int = when (this) {
    TextBatchChange.MARK_UNPROCESSED -> R.string.native_batch_mark_none
    TextBatchChange.MARK_PROCESSED -> R.string.native_batch_mark_processed
    TextBatchChange.EXCLUDE -> R.string.native_batch_exclude
    TextBatchChange.CLEAR_AND_RESET -> R.string.native_batch_clear
}
