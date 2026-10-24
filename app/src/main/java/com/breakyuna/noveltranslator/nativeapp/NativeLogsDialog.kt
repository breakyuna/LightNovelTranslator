package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticCursor
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticPage
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Paginated diagnostics reader over the bounded, redacted store. Details are loaded only for the
 * explicit record the user opened; no prompt, source text or credential is requested here.
 */
@Composable
internal fun NativeLogsDialog(
    loadPage: suspend (NativeDiagnosticCursor?) -> NativeDiagnosticPage,
    loadDetail: suspend (String) -> NativeDiagnosticRecord?,
    export: (() -> Unit)?,
    clear: () -> Unit,
    dismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticSummary>>(emptyList()) }
    var cursor by remember { mutableStateOf<NativeDiagnosticCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<NativeDiagnosticRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pageLoader by rememberUpdatedState(loadPage)
    val detailLoader by rememberUpdatedState(loadDetail)
    suspend fun fetch(append: Boolean) {
        if (loading) return
        loading = true; failed = false
        try {
            val page = pageLoader(if (append) cursor else null)
            entries = ((if (append) entries else emptyList()) + page.entries).distinctBy { it.id }
            cursor = page.nextCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { fetch(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.native_logs_clear)) },
            text = { Text(stringResource(R.string.native_logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; clear(); entries = emptyList(); cursor = null }) {
                    Text(stringResource(R.string.native_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }
    detail?.let { record ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.native_logs_detail)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${record.level} · ${record.category}")
                    Text(formatDiagnosticTime(record.timestampMillis), style = MaterialTheme.typography.bodySmall)
                    Text(record.message)
                    record.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.native_close)) } },
        )
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_logs_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_logs_notice), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth().testTag("native-log-${entry.id}")) {
                            Column(Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    scope.launch {
                                        detail = runCatching { detailLoader(entry.id) }.getOrNull()
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${entry.level} · ${entry.category}", style = MaterialTheme.typography.labelMedium)
                                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                                        Text(formatDiagnosticTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (failed) item(key = "logs-error") { Text(stringResource(R.string.native_logs_error), color = MaterialTheme.colorScheme.error) }
                    if (!loading && !failed && entries.isEmpty()) item(key = "logs-empty") { Text(stringResource(R.string.native_logs_empty)) }
                    if (loading || failed || cursor != null) item(key = "logs-more") {
                        TextButton(onClick = { scope.launch { fetch(entries.isNotEmpty()) } }, enabled = !loading,
                            modifier = Modifier.testTag("native-logs-more")) {
                            Text(stringResource(if (loading) R.string.native_working
                                else if (failed) R.string.native_tasks_retry_load else R.string.native_logs_more))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = {
            RowButtons(
                export = export,
                clear = { confirmClear = true },
                enabled = !loading,
            )
        },
    )
}

@Composable
private fun RowButtons(export: (() -> Unit)?, clear: () -> Unit, enabled: Boolean) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { export?.invoke() }, enabled = enabled && export != null,
            modifier = Modifier.testTag("native-logs-export")) { Text(stringResource(R.string.native_logs_export)) }
        TextButton(onClick = clear, enabled = enabled,
            modifier = Modifier.testTag("native-logs-clear")) { Text(stringResource(R.string.native_logs_clear)) }
    }
}

private fun formatDiagnosticTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
