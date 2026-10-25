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

internal class NativeBatchActions(
    val selecting: (Boolean) -> Unit, val toggle: (ItemId) -> Unit, val all: () -> Unit,
    val prepare: (TextBatchChange) -> Unit, val confirm: (ManualTextBatch) -> Unit, val dismiss: (ManualTextBatch) -> Unit,
    val prepareQuery: ((TextBatchChange) -> Unit)? = null,
    val prepareReplacement: ((TextReplacementOptions, NativeQueryReplacementScope) -> Unit)? = null,
    val prepareNames: ((List<String>, NativeQueryReplacementScope) -> Unit)? = null,
)

internal data class NativeQueryReplacementScope(val ref: LocalProjectRef, val revision: Long,
    val query: TextItemQuery, val quality: TextQualitySelection?)

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
                TextBatchChange.entries.filter { it !in setOf(TextBatchChange.REPLACE_TRANSLATION, TextBatchChange.ASSIGN_NAMES) }.forEach { change ->
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
    var replacing by remember(state.project?.ref) { mutableStateOf<NativeQueryReplacementScope?>(null) }
    var assigning by remember(state.project?.ref) { mutableStateOf<NativeQueryReplacementScope?>(null) }
    assigning?.let { scope -> NativeNameAssignmentDialog(scope, !state.busy, { assigning = null }) { names ->
        assigning = null; actions.prepareNames?.invoke(names, scope)
    } }
    replacing?.let { scope ->
        NativeQueryReplacementDialog(scope, !state.busy, { replacing = null }) { options ->
            replacing = null; actions.prepareReplacement?.invoke(options, scope)
        }
    }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { menu = true }, enabled = !state.busy && state.editor == null &&
            state.manualBatch == null && (state.qualityView == null || state.qualityCurrent) && state.policyDraft?.dirty != true &&
            !state.policyLoadIncomplete && !state.runLoadIncomplete && (state.matchingItems ?: 0) > 0,
            modifier = Modifier.testTag("native-query-batch-actions")) { Text(stringResource(if (state.qualityView == null) R.string.native_batch_all_query else R.string.native_batch_quality_selection)) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            TextBatchChange.entries.filter { it !in setOf(TextBatchChange.REPLACE_TRANSLATION, TextBatchChange.ASSIGN_NAMES) }.forEach { change ->
                DropdownMenuItem(text = { Text(stringResource(change.label())) }, onClick = { menu = false; prepare(change) })
            }
            if (actions.prepareNames != null) DropdownMenuItem(text = { Text(stringResource(R.string.native_batch_assign_names)) },
                onClick = {
                    menu = false
                    state.project?.let { assigning = NativeQueryReplacementScope(it.ref, it.revision, state.proofreadingQuery, state.qualityView) }
                })
            if (actions.prepareReplacement != null) DropdownMenuItem(text = { Text(stringResource(R.string.native_batch_replace)) },
                onClick = {
                    menu = false
                    state.project?.let { replacing = NativeQueryReplacementScope(it.ref, it.revision, state.proofreadingQuery, state.qualityView) }
                })
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
                batch.nameAssignment?.let { names ->
                    Text(stringResource(R.string.native_batch_assign_names_notice))
                    if (names.isEmpty()) Text(stringResource(R.string.native_batch_assign_names_clear), color = MaterialTheme.colorScheme.error)
                    else names.forEachIndexed { index, name -> Text("${index + 1}: $name") }
                }
                batch.replacement?.let {
                    Text(stringResource(R.string.native_query_replace_preview, it.find, it.replacement))
                    Text(stringResource(it.target.label()))
                    Row {
                        Checkbox(it.caseSensitive, onCheckedChange = null, enabled = false)
                        Text(stringResource(R.string.native_policy_case))
                    }
                    Row {
                        Checkbox(it.regex, onCheckedChange = null, enabled = false)
                        Text(stringResource(R.string.native_policy_regex))
                    }
                    if (it.regex) Text(stringResource(R.string.native_regex_replace_hint))
                    Text(stringResource(R.string.native_query_replace_notice))
                }
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
    TextBatchChange.ASSIGN_NAMES -> R.string.native_batch_assign_names
    TextBatchChange.REPLACE_TRANSLATION -> R.string.native_batch_replace
}

@Composable
private fun NativeQueryReplacementDialog(scope: NativeQueryReplacementScope, enabled: Boolean, close: () -> Unit,
    prepare: (TextReplacementOptions) -> Unit) {
    var find by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf("") }
    var replacement by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf("") }
    var sensitive by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf(true) }
    var regex by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf(false) }
    var targetName by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf(TextReplacementTarget.TRANSLATION.name) }
    val target = TextReplacementTarget.valueOf(targetName)
    val options = remember(find, replacement, sensitive, regex, target) { runCatching { TextReplacementOptions(find, replacement, sensitive, regex, target) }.getOrNull() }
    NativePolicyForm(close, stringResource(R.string.native_batch_replace), "native-query-replacement-dialog",
        enabled && options != null, { options?.let(prepare) }, applyLabel = stringResource(R.string.native_policy_preview)) {
        Text(stringResource(R.string.native_query_replace_notice))
        TextReplacementTarget.entries.forEach { choice -> Row {
            RadioButton(selected = target == choice, onClick = { targetName = choice.name }, enabled = enabled)
            Text(stringResource(choice.label()))
        } }
        OutlinedTextField(find, { if (it.length <= 256) find = it }, enabled = enabled,
            label = { Text(stringResource(R.string.native_replace_find)) }, modifier = Modifier.fillMaxWidth().testTag("native-query-replace-find"))
        OutlinedTextField(replacement, { if (it.length <= 4096) replacement = it }, enabled = enabled,
            label = { Text(stringResource(R.string.native_replace_with)) }, modifier = Modifier.fillMaxWidth().testTag("native-query-replace-target"))
        Row {
            Checkbox(sensitive, { sensitive = it }, enabled = enabled)
            Text(stringResource(R.string.native_policy_case))
        }
        Row {
            Checkbox(regex, { regex = it }, enabled = enabled, modifier = Modifier.testTag("native-query-replace-regex"))
            Text(stringResource(R.string.native_policy_regex))
        }
        if (regex) Text(stringResource(R.string.native_regex_replace_hint))
        if (find.isNotEmpty() && options == null) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
    }
}

internal fun TextReplacementTarget.label(): Int = when (this) {
    TextReplacementTarget.TRANSLATION -> R.string.native_replace_target_body
    TextReplacementTarget.NAMES -> R.string.native_replace_target_names
    TextReplacementTarget.BOTH -> R.string.native_replace_target_both
}

@Composable
private fun NativeNameAssignmentDialog(scope: NativeQueryReplacementScope, enabled: Boolean,
    close: () -> Unit, prepare: (List<String>) -> Unit) {
    var text by rememberSaveable(scope.ref, scope.revision, scope.query) { mutableStateOf("") }
    val names = remember(text) {
        runCatching {
            val values = if (text.isEmpty()) emptyList() else text.split('\n')
            ManualNameEdit(CommandId("name-preview"), scope.ref, ItemId(1), scope.revision,
                ObjectFingerprint("0".repeat(64)), values)
            values
        }.getOrNull()
    }
    NativePolicyForm(close, stringResource(R.string.native_batch_assign_names), "native-name-assignment-dialog",
        enabled && names != null, { names?.let(prepare) }, applyLabel = stringResource(R.string.native_policy_preview)) {
        Text(stringResource(R.string.native_batch_assign_names_notice))
        OutlinedTextField(text, { if (it.length <= 16_384) text = it }, enabled = enabled,
            label = { Text(stringResource(R.string.native_batch_assign_names_input)) }, modifier = Modifier.fillMaxWidth())
        if (text.isEmpty()) Text(stringResource(R.string.native_batch_assign_names_clear), color = MaterialTheme.colorScheme.error)
        if (names == null) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
    }
}
