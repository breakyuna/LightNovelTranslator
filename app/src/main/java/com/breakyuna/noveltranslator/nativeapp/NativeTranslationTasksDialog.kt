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
    loadPage: (suspend (LocalProjectRef, Long?) -> TranslationTaskPage)? = null,
    inspectReview: (suspend (LocalProjectRef, RunIdentity) -> TranslationTaskReview)? = null,
    terminate: (suspend (TranslationTaskReview, CommandId) -> Unit)? = null,
    loadMembers: (suspend (LocalProjectRef, RunIdentity, Long) -> TranslationTaskMemberPage)? = null,
    prepareRetry: (suspend (TranslationTaskReview, Long, List<TranslationTaskMemberReview>, CommandId) -> NativeTranslationResumePreview)? = null,
    keepErrors: (suspend (TranslationTaskReview, Long, List<TranslationTaskMemberReview>, CommandId) -> Unit)? = null,
    queueManagedRuns: Set<String> = emptySet()) {
    var tasks by remember(ref) { mutableStateOf<List<TranslationTaskSummary>?>(null) }
    var cursor by remember(ref) { mutableStateOf<Long?>(null) }
    var loading by remember(ref) { mutableStateOf(false) }
    var failed by remember(ref) { mutableStateOf(false) }
    var checking by remember(ref) { mutableStateOf(false) }
    var review by remember(ref) { mutableStateOf(false) }
    var confirmation by remember(ref) { mutableStateOf<NativeTranslationResumePreview?>(null) }
    var decision by remember(ref) { mutableStateOf<TranslationTaskReview?>(null) }
    var retryReview by remember(ref) { mutableStateOf<TranslationTaskReview?>(null) }
    var decisionCommand by remember(ref) { mutableStateOf<CommandId?>(null) }
    var deciding by remember(ref) { mutableStateOf(false) }
    var decisionFailed by remember(ref) { mutableStateOf(false) }
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
    retryReview?.let { snapshot ->
        if (loadMembers != null && prepareRetry != null) {
            NativeTaskRetryDialog(snapshot, loadMembers, prepareRetry, { retryReview = null }, { preview ->
                retryReview = null; decision = null; confirmation = preview
            }, keep = keepErrors)
            return
        }
    }
    decision?.let { snapshot ->
        AlertDialog(onDismissRequest = { if (!deciding) decision = null },
            title = { Text(stringResource(R.string.native_tasks_review_title)) },
            text = { Column {
                Text(stringResource(R.string.native_tasks_progress, snapshot.committed, snapshot.remaining, snapshot.requests))
                Text(stringResource(R.string.native_tasks_unresolved, snapshot.unresolvedRequests))
                Text(stringResource(R.string.native_tasks_terminate_notice))
                if (loadMembers != null && prepareRetry != null && snapshot.stage in setOf("FAILED", "NEEDS_REVIEW", "CANCELLED")) TextButton(enabled = !deciding, onClick = {
                    retryReview = snapshot
                }) { Text(stringResource(R.string.native_tasks_retry_selected)) }
                if (decisionFailed) Text(stringResource(R.string.native_tasks_decision_error))
            } },
            confirmButton = { TextButton(enabled = !deciding && terminate != null && snapshot.stage !in setOf("COMPLETE", "CANCELLED"), onClick = {
                scope.launch {
                    deciding = true; decisionFailed = false
                    try {
                        terminate?.invoke(snapshot, requireNotNull(decisionCommand))
                        decision = null
                        fetch(false)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { decisionFailed = true }
                    finally { deciding = false }
                }
            }) { Text(stringResource(R.string.native_tasks_terminate)) } },
            dismissButton = { TextButton(onClick = { decision = null }, enabled = !deciding) {
                Text(stringResource(R.string.native_cancel))
            } })
        return
    }
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
                        if (task.stage == "CANCELLED") Text(stringResource(R.string.native_tasks_terminated))
                        val queueManaged = task.run.runId.value in queueManagedRuns
                        if (queueManaged) Text(stringResource(R.string.native_tasks_queue_managed),
                            style = MaterialTheme.typography.bodySmall)
                        if (!queueManaged && task.stage != "COMPLETE" && inspectReview != null && terminate != null)
                            TextButton(enabled = !checking && !task.active, onClick = {
                                checking = true; review = false
                                scope.launch {
                                    try {
                                        decision = inspectReview(ref, task.run)
                                        decisionCommand = CommandId(java.util.UUID.randomUUID().toString())
                                        decisionFailed = false
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { review = true }
                                    finally { checking = false }
                                }
                            }) { Text(stringResource(R.string.native_tasks_review_title)) }
                        if (!queueManaged && task.stage !in setOf("COMPLETE", "CANCELLED", "FAILED", "NEEDS_REVIEW")) TextButton(onClick = {
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
