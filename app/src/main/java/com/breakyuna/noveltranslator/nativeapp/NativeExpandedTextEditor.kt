package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R

/** Edits the owning draft directly; closing never commits or discards it. */
@Composable
internal fun NativeExpandedTextEditor(
    identity: Any, title: String, value: String, change: (String) -> Unit, enabled: Boolean = true,
) {
    var open by rememberSaveable(identity) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) {
        Text(stringResource(R.string.native_expand_editor))
    }
    if (open) Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = value, onValueChange = change, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.native_close)) }
            }
        }
    }
}
