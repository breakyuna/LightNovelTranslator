package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // A wrap-content dialog window keeps remeasuring a text field (Compose never reports
            // idle); a full-bleed window with a centered, height-bounded card settles, as in the
            // model settings dialog.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().testTag("native-proof-scrim").clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
                Surface(Modifier.padding(24.dp).widthIn(min = 280.dp, max = 560.dp).fillMaxWidth()
                    .heightIn(max = 480.dp).testTag("native-proof-dialog"),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.native_proof_filter), style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_cancel)) }
                            TextButton(onClick = { query?.let(apply) }, enabled = !state.busy && query != null,
                                modifier = Modifier.testTag("native-proof-apply")) { Text(stringResource(R.string.native_proof_apply)) }
                        }
                    }
                }
            }
        }
    }
}

private fun TextSearchField.label(): Int = when (this) {
    TextSearchField.BOTH -> R.string.native_proof_both
    TextSearchField.SOURCE -> R.string.native_source
    TextSearchField.TRANSLATION -> R.string.native_translation
}
