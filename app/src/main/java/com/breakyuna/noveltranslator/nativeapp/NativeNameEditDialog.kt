package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ManualNameEdit

/**
 * Explicit name editor. It keeps the body/status draft untouched and commits names through a
 * separate immutable command. Names are one-per-line; blank lines are ignored. Clearing both
 * fields is an explicit "no names" value, not an accidental preserve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeNameEditDialog(draft: NativeNameEditDraft, busy: Boolean,
                                  onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val identity = arrayOf(draft.ref.projectId.value, draft.ref.instanceId.value,
        draft.ref.generation.value.toString(), draft.itemId.value.toString(), draft.revision.toString())
    var source by rememberSaveable(*identity) { mutableStateOf(draft.sourceText) }
    var translation by rememberSaveable(*identity) { mutableStateOf(draft.translationText) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.native_names_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.native_back))
                    } },
                    actions = { TextButton(onClick = { onSave(source, translation) }, enabled = !busy,
                        modifier = Modifier.testTag("native-names-save")) {
                        Text(stringResource(R.string.native_names_save))
                    } })
            }) { padding ->
                Column(Modifier.padding(padding).imePadding().padding(16.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) Text(stringResource(R.string.native_working))
                    Text(stringResource(R.string.native_names_notice), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = source, onValueChange = { source = it }, enabled = !busy,
                        label = { Text(stringResource(R.string.native_names_source)) }, minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("native-names-source"))
                    OutlinedTextField(value = translation, onValueChange = { translation = it }, enabled = !busy,
                        label = { Text(stringResource(R.string.native_names_translation)) }, minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("native-names-translation"))
                    Text(stringResource(R.string.native_names_budget, ManualNameEdit.MAX_NAMES,
                        ManualNameEdit.MAX_NAME_CHARS), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
