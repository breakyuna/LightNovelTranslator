package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class NativeTranslationResumePreview(val spec: TranslationTaskSpec, val model: String)

@Composable
internal fun NativeTranslationTasksDialog(ref: LocalProjectRef,
    load: suspend (LocalProjectRef) -> List<TranslationTaskSummary>,
    inspect: suspend (LocalProjectRef, RunIdentity) -> NativeTranslationResumePreview,
    dismiss: () -> Unit, resume: (TranslationTaskSpec) -> Unit,
    loadPage: (suspend (LocalProjectRef, Long?) -> TranslationTaskPage)? = null) {
    var tasks by remember(ref) { mutableStateOf<List<TranslationTaskSummary>?>(null) }
    var cursor by remember(ref) { mutableStateOf<Long?>(null) }
    var loading by remember(ref) { mutableStateOf(false) }
    var failed by remember(ref) { mutableStateOf(false) }
    var checking by remember(ref) { mutableStateOf(false) }
    var review by remember(ref) { mutableStateOf(false) }
    var confirmation by remember(ref) { mutableStateOf<NativeTranslationResumePreview?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(append: Boolean) {
        if (loading) return
        loading = true; failed = false
        try {
            val page = loadPage?.invoke(ref, if (append) cursor else null)
                ?: TranslationTaskPage(load(ref), null)
            val previous = if (append) tasks.orEmpty() else emptyList()
            check(page.nextCursor == null || !append || page.nextCursor!! < requireNotNull(cursor))
            tasks = (previous + page.tasks).distinctBy { it.run }
            cursor = page.nextCursor
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    LaunchedEffect(ref) { fetch(false) }
    confirmation?.let { preview ->
        val spec = preview.spec
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.native_tasks_resume)) },
            text = { Text(stringResource(R.string.native_tasks_confirmation, spec.options.targetLanguage, preview.model)) },
            confirmButton = { TextButton(onClick = { confirmation = null; resume(spec) }, Modifier.testTag("native-task-confirm")) {
                Text(stringResource(R.string.native_tasks_resume))
            } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.native_cancel)) } })
    } ?: AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.native_tasks_title)) },
        text = { Column {
            Text(stringResource(R.string.native_tasks_notice), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.native_io_error))
            if (review) Text(stringResource(R.string.native_tasks_review), Modifier.testTag("native-task-review"))
            if (tasks == null && !failed) Text(stringResource(R.string.native_working))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks.orEmpty(), key = { "${it.run.runId.value}:${it.run.commandId.value}" }) { task ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
                        Text(stringResource(R.string.native_tasks_progress, task.progress.committed, task.remaining, task.progress.requests))
                        Text(task.targetLanguage, style = MaterialTheme.typography.bodySmall)
                        if (task.stage == "FAILED") Text(stringResource(R.string.native_tasks_failed),
                            Modifier.testTag("native-task-failed-${task.run.commandId.value}"))
                        if (task.stage == "NEEDS_REVIEW") Text(stringResource(R.string.native_tasks_review),
                            Modifier.testTag("native-task-uncertain-${task.run.commandId.value}"))
                        if (task.stage !in setOf("COMPLETE", "FAILED", "NEEDS_REVIEW")) TextButton(onClick = {
                            checking = true; review = false
                            scope.launch {
                                try { confirmation = inspect(ref, task.run) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { review = true }
                                finally { checking = false }
                            }
                        }, enabled = !checking && !task.active, modifier = Modifier.testTag("native-task-${task.run.commandId.value}")) {
                            Text(stringResource(R.string.native_tasks_resume))
                        }
                    } }
                }
                if (cursor != null || failed || loading) item(key = "task-pagination") {
                    TextButton(onClick = { scope.launch { fetch(tasks != null) } }, enabled = !loading && !checking,
                        modifier = Modifier.testTag("native-tasks-more")) {
                        Text(stringResource(if (loading) R.string.native_working else if (failed) R.string.native_tasks_retry_load else R.string.native_tasks_more))
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } })
}
