package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity

@Composable
internal fun NativeTranslationUsage(ref: LocalProjectRef, totals: RequestUsageTotals, run: RunIdentity? = null) {
    if (totals.responses == 0L) return
    var expanded by rememberSaveable(ref.projectId.value, ref.instanceId.value, ref.generation.value,
        run?.runId?.value, run?.commandId?.value) { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = Modifier.padding(horizontal = 16.dp).testTag("native-usage-toggle")) {
        Text(stringResource(R.string.native_usage_title, totals.responses))
    }
    if (expanded) NativeContentDialog(onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.native_usage_title, totals.responses)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for ((label, value) in listOf(R.string.native_usage_input to totals.input,
                R.string.native_usage_cache_read to totals.cacheRead, R.string.native_usage_cache_write to totals.cacheWrite,
                R.string.native_usage_thinking to totals.thinking, R.string.native_usage_output to totals.output)) {
                Text(stringResource(R.string.native_usage_quantity, stringResource(label), value.actual.toString(),
                    value.estimated.toString(), value.unknownRequests), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.native_usage_notice), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.native_close)) } },
    )
}
