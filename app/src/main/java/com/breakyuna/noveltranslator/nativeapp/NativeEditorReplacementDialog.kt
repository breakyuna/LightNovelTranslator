package com.breakyuna.noveltranslator.nativeapp

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

@Composable
internal fun NativeEditorReplacementDialog(draft: NativeEditDraft, busy: Boolean, error: Int?,
    prepared: NativeEditorReplacement?, dismiss: () -> Unit, prepare: (TextReplacementOptions) -> Unit,
    apply: (NativeEditorReplacement) -> Unit, cancel: () -> Unit) {
    var find by rememberSaveable(draft.ref, draft.item.id.value) { mutableStateOf("") }
    var replacement by rememberSaveable(draft.ref, draft.item.id.value) { mutableStateOf("") }
    var sensitive by rememberSaveable(draft.ref, draft.item.id.value) { mutableStateOf(true) }
    var regex by rememberSaveable(draft.ref, draft.item.id.value) { mutableStateOf(false) }
    val options = remember(find, replacement, sensitive, regex) { runCatching { TextReplacementOptions(find, replacement, sensitive, regex) }.getOrNull() }
    val current = prepared?.takeIf { it.ref == draft.ref && it.command == draft.command && it.preview.options == options }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 680.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_replace_title), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.native_replace_hint))
                        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(find, { if (it.length <= 256) find = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.native_replace_find)) }, modifier = Modifier.fillMaxWidth().testTag("native-replace-find"))
                        OutlinedTextField(replacement, { if (it.length <= 4096) replacement = it }, enabled = !busy,
                            label = { Text(stringResource(R.string.native_replace_with)) }, modifier = Modifier.fillMaxWidth().testTag("native-replace-with"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(sensitive, { sensitive = it }, enabled = !busy)
                            Text(stringResource(R.string.native_policy_case))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(regex, { regex = it }, enabled = !busy, modifier = Modifier.testTag("native-replace-regex"))
                            Text(stringResource(R.string.native_policy_regex))
                        }
                        if (regex) Text(stringResource(R.string.native_regex_replace_hint))
                        if (find.isNotEmpty() && options == null) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { options?.let(prepare) }, enabled = !busy && options != null,
                            modifier = Modifier.testTag("native-replace-preview")) { Text(stringResource(R.string.native_replace_preview)) }
                        if (busy) {
                            NativeBusyIndicator()
                            TextButton(onClick = cancel) { Text(stringResource(R.string.native_cancel)) }
                        }
                        if (current != null) {
                            Text(stringResource(R.string.native_replace_count, current.preview.occurrences))
                            Text(stringResource(if (current.preview.changed) R.string.native_replace_excerpt else R.string.native_replace_unchanged))
                            if (current.preview.changed) Text(current.preview.text.take(240))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(onClick = { current?.let(apply) }, enabled = !busy && current?.preview?.changed == true,
                            modifier = Modifier.testTag("native-replace-apply")) { Text(stringResource(R.string.native_policy_apply)) }
                    }
                }
            }
        }
    }
}
