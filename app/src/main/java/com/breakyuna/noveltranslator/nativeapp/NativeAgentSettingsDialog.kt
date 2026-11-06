package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R

@Composable
internal fun NativeAgentSettingsDialog(current: Boolean, dismiss: () -> Unit, save: (Boolean) -> Unit) {
    var enabled by remember(current) { mutableStateOf(current) }
    NativeContentDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_agent_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_auto_context_title), Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, Modifier.testTag("native-auto-context-toggle"))
                }
                Text(stringResource(R.string.native_auto_context_description), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { save(enabled); dismiss() },
            modifier = Modifier.testTag("native-auto-context-save")) { Text(stringResource(R.string.native_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) } })
}
