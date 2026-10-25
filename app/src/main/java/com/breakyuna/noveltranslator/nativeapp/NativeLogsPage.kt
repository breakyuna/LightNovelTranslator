package com.breakyuna.noveltranslator.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticCursor
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticPage
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticRecord
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Routed U14 diagnostics reader. The list never loads record bodies, prompts or credentials;
 * [loadDetail] is called only for the explicit id the user opens. A stale cursor is never
 * retried: the error offers "reload newest" and starts again without an old token.
 */
@Composable
internal fun NativeLogsPage(
    includeDetails: Boolean,
    back: () -> Unit,
    loadPage: suspend (NativeDiagnosticCursor?, NativeDiagnosticLevel?, String?) -> NativeDiagnosticPage,
    loadDetail: suspend (String) -> NativeDiagnosticRecord?,
    loadCategories: suspend () -> List<String>,
    setIncludeDetails: (Boolean) -> Unit,
    export: () -> Unit,
    clear: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<NativeDiagnosticSummary>>(emptyList()) }
    var cursor by remember { mutableStateOf<NativeDiagnosticCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var levelName by rememberSaveable { mutableStateOf<String?>(null) }
    val level = levelName?.let { name ->
        NativeDiagnosticLevel.entries.firstOrNull { it.name == name }
    }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var filterKey by rememberSaveable {
        mutableStateOf(levelName.orEmpty() + "|" + category.orEmpty())
    }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var categoriesFailed by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<NativeDiagnosticRecord?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailFailed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmDebug by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var requestId by remember { mutableIntStateOf(0) }
    var detailRetry by remember { mutableIntStateOf(0) }
    val pageLoader by rememberUpdatedState(loadPage)
    val detailLoader by rememberUpdatedState(loadDetail)
    val categoryLoader by rememberUpdatedState(loadCategories)

    BackHandler(enabled = selectedId != null) {
        selectedId = null
        detail = null
        detailFailed = false
        detailLoading = false
    }

    suspend fun fetch(append: Boolean) {
        val id = ++requestId
        loading = true
        failed = false
        stale = false
        try {
            val page = pageLoader(if (append) cursor else null, level, category)
            if (id != requestId) return
            entries = if (append) (entries + page.entries).distinctBy { it.id } else page.entries
            cursor = page.nextCursor
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (id != requestId) return
            failed = true
            stale = append || cursor != null
            loading = false
        } finally {
            if (id == requestId) loading = false
        }
    }

    LaunchedEffect(level, category) {
        val key = levelName.orEmpty() + "|" + category.orEmpty()
        if (filterKey != key) {
            filterKey = key
            selectedId = null
            detail = null
            detailFailed = false
        }
        entries = emptyList()
        cursor = null
        copied = false
        fetch(false)
    }
    LaunchedEffect(Unit) {
        try {
            categories = categoryLoader()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            categoriesFailed = true
        }
    }
    LaunchedEffect(selectedId, detailRetry) {
        val id = selectedId ?: return@LaunchedEffect
        detail = null
        detailFailed = false
        detailLoading = true
        copied = false
        try {
            detail = detailLoader(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detailFailed = true
        } finally {
            detailLoading = false
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.native_logs_clear)) },
            text = { Text(stringResource(R.string.native_logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clear()
                    entries = emptyList()
                    cursor = null
                    failed = false
                    stale = false
                }, modifier = Modifier.testTag("native-logs-clear-confirm")) {
                    Text(stringResource(R.string.native_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.native_logs_export_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_logs_export_confirm_body))
                    if (includeDetails) {
                        Text(stringResource(R.string.native_logs_export_details_warning),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; export() },
                    modifier = Modifier.testTag("native-logs-export-confirm")) {
                    Text(stringResource(R.string.native_logs_export))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }
    if (confirmDebug) {
        AlertDialog(
            onDismissRequest = { confirmDebug = false },
            title = { Text(stringResource(R.string.native_logs_debug_title)) },
            text = { Text(stringResource(R.string.native_logs_debug_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDebug = false
                    setIncludeDetails(true)
                }, modifier = Modifier.testTag("native-logs-debug-confirm")) {
                    Text(stringResource(R.string.native_logs_debug_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDebug = false }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }

    val selected = selectedId
    if (selected != null) {
        val record = detail
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("native-logs-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = {
                    selectedId = null
                    detail = null
                    detailFailed = false
                    detailLoading = false
                }, modifier = Modifier.testTag("native-logs-detail-back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.native_logs_detail_back))
                }
                TextButton(onClick = {
                    if (record != null) copied = copyRecord(context, record)
                }, enabled = record != null, modifier = Modifier.testTag("native-logs-copy")) {
                    Text(stringResource(R.string.native_logs_copy))
                }
            }
            if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (detailFailed || (!detailLoading && record == null)) {
                Text(stringResource(R.string.native_logs_detail_missing), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { detailRetry++ },
                    modifier = Modifier.testTag("native-logs-detail-reload")) {
                    Text(stringResource(R.string.native_tasks_retry_load))
                }
            }
            if (copied) Text(stringResource(R.string.native_logs_copied))
            record?.let {
                Text(stringResource(R.string.native_logs_detail), style = MaterialTheme.typography.headlineSmall)
                Text("${it.level.name} · ${it.category}", style = MaterialTheme.typography.labelLarge)
                Text(formatDiagnosticTime(it.timestampMillis), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(stringResource(R.string.native_logs_detail_message), style = MaterialTheme.typography.titleSmall)
                Text(it.message)
                it.detail?.let { text ->
                    Text(stringResource(R.string.native_logs_detail_detail), style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall)
                } ?: Text(stringResource(R.string.native_logs_detail_none), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_logs_detail_scope), style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().testTag("native-logs-page"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = back, modifier = Modifier.testTag("native-logs-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Text(stringResource(R.string.native_back))
                    }
                    Text(stringResource(R.string.native_logs_title), style = MaterialTheme.typography.headlineSmall)
                }
                Text(stringResource(R.string.native_logs_notice), style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth().testTag("native-logs-debug-card")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_logs_debug_title), Modifier.weight(1f))
                            Switch(checked = includeDetails, onCheckedChange = { enabled ->
                                if (enabled && !includeDetails) confirmDebug = true
                                else if (!enabled) setIncludeDetails(false)
                            }, modifier = Modifier.testTag("native-logs-debug"))
                        }
                        Text(stringResource(if (includeDetails) R.string.native_logs_debug_enabled
                            else R.string.native_logs_debug_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (includeDetails) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.native_logs_debug_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.native_logs_filters), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = level == null, onClick = { levelName = null; category = null },
                        label = { Text(stringResource(R.string.native_logs_level_all)) },
                        modifier = Modifier.testTag("native-logs-level-ALL"))
                    NativeDiagnosticLevel.entries.forEach { candidate ->
                        FilterChip(selected = level == candidate, onClick = { levelName = candidate.name },
                            label = { Text(candidate.name) },
                            modifier = Modifier.testTag("native-logs-level-${candidate.name}"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { categoryMenu = true },
                            modifier = Modifier.testTag("native-logs-category")) {
                            Text(category ?: stringResource(R.string.native_logs_category_all))
                        }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.native_logs_category_all)) },
                                onClick = { category = null; categoryMenu = false },
                                modifier = Modifier.testTag("native-logs-category-ALL"))
                            categories.forEach { candidate ->
                                DropdownMenuItem(text = { Text(candidate) },
                                    onClick = { category = candidate; categoryMenu = false },
                                    modifier = Modifier.testTag("native-logs-category-$candidate"))
                            }
                        }
                    }
                    if (categoriesFailed) Text(stringResource(R.string.native_logs_categories_error),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmExport = true }, modifier = Modifier.testTag("native-logs-export")) {
                        Text(stringResource(R.string.native_logs_export))
                    }
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("native-logs-clear")) {
                        Text(stringResource(R.string.native_logs_clear))
                    }
                }
                HorizontalDivider()
            }
        }
        items(entries, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth().testTag("native-log-${entry.id}")) {
                TextButton(onClick = { selectedId = entry.id }, modifier = Modifier.fillMaxWidth()
                    .testTag("native-log-open-${entry.id}")) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${entry.level.name} · ${entry.category}", style = MaterialTheme.typography.labelMedium)
                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                        Text(formatDiagnosticTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (loading) item(key = "logs-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (failed) item(key = "logs-error") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (stale) R.string.native_logs_stale else R.string.native_logs_error),
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    cursor = null
                    entries = emptyList()
                    scope.launch { fetch(false) }
                }, enabled = !loading, modifier = Modifier.testTag("native-logs-reload")) {
                    Text(stringResource(R.string.native_logs_reload))
                }
            }
        }
        if (!loading && !failed && entries.isEmpty()) item(key = "logs-empty") {
            Text(stringResource(R.string.native_logs_empty))
        }
        if (!loading && !failed && cursor != null) item(key = "logs-more") {
            TextButton(onClick = { scope.launch { fetch(true) } }, modifier = Modifier.testTag("native-logs-more")) {
                Text(stringResource(R.string.native_logs_more))
            }
        }
    }
}

private fun copyRecord(context: Context, record: NativeDiagnosticRecord): Boolean {
    val text = buildString {
        append(record.level.name).append(" · ").append(record.category).append('\n')
        append(formatDiagnosticTime(record.timestampMillis)).append('\n')
        append(record.message)
        record.detail?.let { append('\n').append(it) }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
    return true
}

private fun formatDiagnosticTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
