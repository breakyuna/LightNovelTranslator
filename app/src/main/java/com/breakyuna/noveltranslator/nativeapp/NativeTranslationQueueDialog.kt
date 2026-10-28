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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueSnapshot
import kotlinx.coroutines.CancellationException

/**
 * Durable queue projection with explicit lifecycle commands. The caller may pass dedicated
 * start/pause callbacks; when omitted they fall back to the state-aware [resume] callback so the
 * existing application entry point keeps a working QUEUED start and RUNNING pause action.
 */
@Composable
internal fun NativeTranslationQueueDialog(
    load: suspend (LocalProjectRef) -> TranslationQueueSnapshot,
    ref: LocalProjectRef,
    resume: (com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity) -> Unit,
    cancel: (com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity) -> Unit,
    start: (com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity) -> Unit = resume,
    pause: (com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity) -> Unit = resume,
    review: ((com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity) -> Unit)? = null,
    refreshKey: Int = 0,
    dismiss: () -> Unit,
) {
    var snapshot by remember(ref) { mutableStateOf<TranslationQueueSnapshot?>(null) }
    var refresh by remember(ref) { mutableIntStateOf(0) }
    var failed by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref, refresh, refreshKey) {
        try {
            snapshot = load(ref); failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    NativeContentDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_queue_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val entries = snapshot?.entries.orEmpty()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.spec.run.runId.value }) { entry ->
                        Card(Modifier.fillMaxWidth().testTag("native-queue-${entry.spec.run.runId.value}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(queueStateLabel(entry.task.state)), style = MaterialTheme.typography.titleSmall)
                                Text(entry.spec.options.targetLanguage, style = MaterialTheme.typography.bodySmall)
                                Text(if (entry.spec.wholeProject) stringResource(R.string.native_simulation_scope_project)
                                    else stringResource(R.string.native_simulation_scope_page),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.native_queue_sequence, entry.task.sequence),
                                    style = MaterialTheme.typography.labelSmall)
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (entry.task.state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.QUEUED) {
                                        TextButton(onClick = { start(entry.task.identity) },
                                            modifier = Modifier.testTag("native-queue-start-${entry.spec.run.runId.value}")) {
                                            Text(stringResource(R.string.native_queue_resume))
                                        }
                                    }
                                    if (entry.task.state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.PAUSED) {
                                        TextButton(onClick = { resume(entry.task.identity) },
                                            modifier = Modifier.testTag("native-queue-resume-${entry.spec.run.runId.value}")) {
                                            Text(stringResource(R.string.native_queue_resume))
                                        }
                                    }
                                    if (entry.task.state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.RUNNING) {
                                        TextButton(onClick = { pause(entry.task.identity) },
                                            modifier = Modifier.testTag("native-queue-pause-${entry.spec.run.runId.value}")) {
                                            Text(stringResource(R.string.native_pause))
                                        }
                                    }
                                    if (review != null && entry.task.batches.isNotEmpty() &&
                                        (entry.task.state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.NEEDS_REVIEW ||
                                            entry.task.state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.FAILED)) {
                                        TextButton(onClick = { review?.invoke(entry.task.identity) },
                                            modifier = Modifier.testTag("native-queue-review-${entry.spec.run.runId.value}")) {
                                            Text(stringResource(R.string.native_queue_review_failures))
                                        }
                                    }
                                    if (!entry.task.state.terminal) {
                                        TextButton(onClick = { cancel(entry.task.identity) },
                                            modifier = Modifier.testTag("native-queue-cancel-${entry.spec.run.runId.value}")) {
                                            Text(stringResource(R.string.native_queue_cancel))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (failed) item(key = "queue-error") {
                        Text(stringResource(R.string.native_queue_error), color = MaterialTheme.colorScheme.error)
                    }
                    if (!failed && entries.isEmpty()) item(key = "queue-empty") {
                        Text(stringResource(R.string.native_queue_empty))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(onClick = { refresh++ }, modifier = Modifier.testTag("native-queue-refresh")) {
            Text(stringResource(R.string.native_refresh))
        } },
    )
}

private fun queueStateLabel(state: com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState): Int = when (state) {
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.QUEUED -> R.string.native_queue_waiting
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.RUNNING -> R.string.native_runs_running
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.PAUSED -> R.string.native_runs_paused
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.NEEDS_REVIEW -> R.string.native_queue_review_failures
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.FAILED -> R.string.native_runs_failed
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.COMPLETED -> R.string.native_runs_succeeded
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.COMPLETED_WITH_ERRORS -> R.string.native_queue_partial
    com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.CANCELLED -> R.string.native_runs_cancelled
}
