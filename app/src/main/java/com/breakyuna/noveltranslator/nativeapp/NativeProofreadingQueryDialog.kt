package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Draft-only controls: only Apply/Clear dispatch a query, and neither authorizes translation. */
@Composable
internal fun NativeProofreadingQueryDialog(state: NativeProjectUiState, onDismiss: () -> Unit, apply: (TextItemQuery) -> Unit) {
    val applied = state.proofreadingQuery
    key(state.project?.ref, applied) {
        var text by rememberSaveable { mutableStateOf(applied.text) }
        var statusName by rememberSaveable { mutableStateOf(applied.status?.name) }
        var fieldName by rememberSaveable { mutableStateOf(applied.field.name) }
        var statuses by remember { mutableStateOf(false) }
        var fields by remember { mutableStateOf(false) }
        val status = statusName?.let(TextItemStatus::valueOf)
        val field = TextSearchField.valueOf(fieldName)
        val query = runCatching { TextItemQuery(status, text, field) }.getOrNull()
        AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("native-proof-dialog"),
            title = { Text(stringResource(R.string.native_proof_filter)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.itemStatistics?.let { counts ->
                        Text(stringResource(R.string.native_proof_counts, counts.total, counts.unprocessed, counts.processed, counts.excluded))
                    }
                    state.matchingItems?.let { Text(stringResource(R.string.native_proof_matching, it)) }
                    Box {
                        OutlinedButton(onClick = { statuses = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("native-proof-status")) {
                            Text(stringResource(R.string.native_status) + ": " + stringResource(status?.label() ?: R.string.native_proof_all))
                        }
                        DropdownMenu(expanded = statuses, onDismissRequest = { statuses = false }) {
                            (listOf<TextItemStatus?>(null) + TextItemStatus.entries).forEach { value ->
                                DropdownMenuItem(text = { Text(stringResource(value?.label() ?: R.string.native_proof_all)) },
                                    onClick = { statusName = value?.name; statuses = false })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { fields = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("native-proof-field")) {
                            Text(stringResource(field.label()))
                        }
                        DropdownMenu(expanded = fields, onDismissRequest = { fields = false }) {
                            TextSearchField.entries.forEach { value ->
                                DropdownMenuItem(text = { Text(stringResource(value.label())) },
                                    onClick = { fieldName = value.name; fields = false })
                            }
                        }
                    }
                    OutlinedTextField(text, { if (it.length <= TextItemQuery.MAX_TEXT_LENGTH) text = it },
                        label = { Text(stringResource(R.string.native_proof_search)) },
                        supportingText = { Text(stringResource(R.string.native_proof_literal)) },
                        enabled = !state.busy, singleLine = true, isError = query == null,
                        modifier = Modifier.fillMaxWidth().testTag("native-proof-search"))
                    Text(stringResource(R.string.native_proof_window), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_proof_simulation_scope), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { apply(TextItemQuery()) }, enabled = !state.busy,
                        modifier = Modifier.testTag("native-proof-clear")) { Text(stringResource(R.string.native_proof_clear)) }
                }
            },
            confirmButton = { TextButton(onClick = { query?.let(apply) }, enabled = !state.busy && query != null,
                modifier = Modifier.testTag("native-proof-apply")) { Text(stringResource(R.string.native_proof_apply)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
}

private fun TextSearchField.label(): Int = when (this) {
    TextSearchField.BOTH -> R.string.native_proof_both
    TextSearchField.SOURCE -> R.string.native_source
    TextSearchField.TRANSLATION -> R.string.native_translation
}
