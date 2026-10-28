package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.legacy.LegacyExportFailure
import com.breakyuna.noveltranslator.legacy.LegacyExportPhase
import com.breakyuna.noveltranslator.legacy.LegacyExportProgress
import com.breakyuna.noveltranslator.legacy.LegacyExportResult

/**
 * Read-only legacy export surface. The panel never opens the legacy database and never touches
 * credentials, files or project stores: it forwards the chosen opaque output location to
 * [LegacyExportActions.start] and displays the progress/failures/result supplied by the caller.
 * [LegacyExportActions.cancel] is only offered while the caller reports
 * [NativeLegacyExportStatus.RUNNING].
 */
@Composable
internal fun NativeLegacyExportPanel(
    state: NativeLegacyExportState,
    actions: LegacyExportActions,
    labels: NativeLegacyExportLabels,
    chooseOutputDirectory: () -> Unit,
    dismiss: () -> Unit,
) {
    val running = state.status == NativeLegacyExportStatus.RUNNING
    val editable = state.status in setOf(
        NativeLegacyExportStatus.IDLE,
        NativeLegacyExportStatus.FAILED,
        NativeLegacyExportStatus.CANCELLED,
    )
    val startEnabled = editable && !state.outputDirectory.isNullOrBlank()
    val failures = state.result?.takeIf { state.status == NativeLegacyExportStatus.COMPLETED }?.failures
        ?: state.failures
    val directory = state.outputDisplayName?.takeIf { it.isNotBlank() }
        ?: state.outputDirectory?.takeIf { it.isNotBlank() }
        ?: labels.noDirectory
    Dialog(
        onDismissRequest = { if (!running) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.padding(20.dp).widthIn(min = 300.dp, max = 640.dp).fillMaxWidth()
                    .heightIn(max = 640.dp).testTag("native-legacy-export-panel"),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(labels.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "description") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(labels.description, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    labels.privacyNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("native-legacy-export-privacy"),
                                )
                            }
                        }
                        item(key = "directory") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(labels.outputDirectory, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    directory,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("native-legacy-export-directory"),
                                )
                                OutlinedButton(
                                    onClick = chooseOutputDirectory,
                                    enabled = editable,
                                    modifier = Modifier.testTag("native-legacy-export-choose"),
                                ) { Text(labels.chooseDirectory) }
                            }
                        }
                        item(key = "status") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    statusText(state.status, labels),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.testTag("native-legacy-export-status"),
                                )
                                state.message?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.testTag("native-legacy-export-error"),
                                    )
                                }
                            }
                        }
                        if (running) {
                            item(key = "progress") {
                                val progress = state.progress
                                val total = progress?.total ?: 0
                                Column(
                                    modifier = Modifier.fillMaxWidth().testTag("native-legacy-export-progress"),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (total > 0) {
                                        val fraction = ((progress?.completed ?: 0).toFloat() / total.toFloat())
                                            .coerceIn(0f, 1f)
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        NativeBusyIndicator()
                                    }
                                    progress?.let {
                                        Text(
                                            "${labels.phaseText(it.phase)} · " +
                                                labels.progressText(it.completed, it.total),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        it.path?.takeIf { path -> path.isNotBlank() }?.let { path ->
                                            Text(
                                                path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (state.status == NativeLegacyExportStatus.COMPLETED && state.result != null) {
                            item(key = "result") {
                                val result = requireNotNull(state.result)
                                Column(
                                    modifier = Modifier.fillMaxWidth().testTag("native-legacy-export-result"),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        labels.resultText(
                                            result.manifest.files.size,
                                            result.manifest.totalBytes,
                                            result.failures.size,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val resultLocation = state.outputDisplayName
                                        ?.takeIf { it.isNotBlank() }
                                        ?: result.outputDirectory.absolutePath
                                    Text(
                                        "${labels.outputDirectory}: $resultLocation",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (failures.isNotEmpty()) {
                            item(key = "failures-heading") {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(
                                        labels.failures,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            itemsIndexed(
                                failures,
                                key = { index, failure -> "$index|${failureKey(failure)}" },
                            ) { index, failure ->
                                Text(
                                    labels.failureText(failure),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag("native-legacy-export-failure-$index"),
                                )
                            }
                        } else if (state.status == NativeLegacyExportStatus.COMPLETED && state.result != null) {
                            item(key = "no-failures") {
                                Text(
                                    labels.noFailures,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (running) actions.cancel() else dismiss() },
                            modifier = Modifier.testTag(
                                if (running) "native-legacy-export-cancel" else "native-legacy-export-close",
                            ),
                        ) {
                            Text(if (running) labels.cancel else labels.close)
                        }
                        if (editable) {
                            Button(
                                onClick = { state.outputDirectory?.let(actions::start) },
                                enabled = startEnabled,
                                modifier = Modifier.testTag("native-legacy-export-start"),
                            ) { Text(labels.start) }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(status: NativeLegacyExportStatus, labels: NativeLegacyExportLabels): String =
    when (status) {
        NativeLegacyExportStatus.IDLE -> labels.idle
        NativeLegacyExportStatus.RUNNING -> labels.running
        NativeLegacyExportStatus.COMPLETED -> labels.completed
        NativeLegacyExportStatus.FAILED -> labels.failed
        NativeLegacyExportStatus.CANCELLED -> labels.cancelled
    }

private fun failureKey(failure: LegacyExportFailure): String =
    "${failure.scope}|${failure.bookId}|${failure.editionId}|${failure.path}|${failure.message}"

internal enum class NativeLegacyExportStatus { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * [outputDirectory] is the opaque value passed back to the export action; [outputDisplayName] is
 * the user-facing label (for example a SAF tree name). Only this view state holds a path, and it is
 * rendered in the UI only. [message] is a caller-supplied, already redacted failure description;
 * progress, failures and results are the service data classes unchanged.
 */
internal data class NativeLegacyExportState(
    val outputDirectory: String? = null,
    val outputDisplayName: String? = null,
    val status: NativeLegacyExportStatus = NativeLegacyExportStatus.IDLE,
    val progress: LegacyExportProgress? = null,
    val failures: List<LegacyExportFailure> = emptyList(),
    val result: LegacyExportResult? = null,
    val message: String? = null,
)

/** The only export operations this panel can invoke. */
internal interface LegacyExportActions {
    fun start(outputDirectory: String)
    fun cancel()
}

internal data class NativeLegacyExportLabels(
    val title: String,
    val description: String,
    val privacyNote: String,
    val outputDirectory: String,
    val chooseDirectory: String,
    val noDirectory: String,
    val start: String,
    val cancel: String,
    val close: String,
    val idle: String,
    val running: String,
    val completed: String,
    val failed: String,
    val cancelled: String,
    val failures: String,
    val noFailures: String,
    val progressText: (completed: Int, total: Int) -> String,
    val phaseText: (LegacyExportPhase) -> String,
    val resultText: (files: Int, bytes: Long, failures: Int) -> String,
    val failureText: (LegacyExportFailure) -> String,
)
