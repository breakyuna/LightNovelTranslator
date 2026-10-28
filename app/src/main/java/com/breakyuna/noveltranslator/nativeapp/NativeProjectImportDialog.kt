package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.foundation.lazy.items
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

/**
 * Selection and confirmation UI for creating a project from TXT/EPUB files.
 *
 * [NativeProjectImportReview] is computed by [reviewNativeProjectImport] before this composable is
 * called. The dialog only offers pickers, removes an already listed selection, displays validation
 * issues and sends the frozen review to [NativeProjectImportActions.create]. Cancelling, validation
 * failures or [NativeProjectImportUiState.busy] never call `create`.
 */
@Composable
internal fun NativeProjectImportDialog(
    state: NativeProjectImportUiState,
    actions: NativeProjectImportActions,
    labels: NativeProjectImportLabels,
) {
    val review = state.review
    val createEnabled = review.canCreate && !state.busy && state.error.isNullOrBlank()
    Dialog(
        onDismissRequest = { if (!state.busy) actions.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.padding(20.dp).widthIn(max = 640.dp).fillMaxWidth()
                    .heightIn(max = 640.dp).testTag("native-project-import-dialog"),
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
                                    labels.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("native-project-import-note"),
                                )
                            }
                        }
                        item(key = "pickers") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = actions.chooseFiles,
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f).testTag("native-project-import-pick-files"),
                                ) { Text(labels.chooseFiles) }
                                OutlinedButton(
                                    onClick = actions.chooseDirectory,
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f).testTag("native-project-import-pick-directory"),
                                ) { Text(labels.chooseDirectory) }
                            }
                        }
                        if (review.projectName != null || review.seed != null) {
                            item(key = "project") {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    review.projectName?.let {
                                        Text("${labels.projectName}: $it", style = MaterialTheme.typography.titleSmall)
                                    }
                                    review.seed?.let {
                                        Text("${labels.seed}: ${labels.seedText(it)}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        item(key = "files-heading") {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (review.files.isEmpty()) {
                            item(key = "no-files") {
                                Text(
                                    labels.noFiles,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("native-project-import-empty"),
                                )
                            }
                        } else {
                            items(review.files, key = { it.sourceId }) { file ->
                                NativeProjectImportFileRow(file, state.busy, actions, labels)
                            }
                        }
                        if (review.issues.isNotEmpty()) {
                            item(key = "issues-heading") {
                                Text(
                                    labels.issues,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            itemsIndexed(
                                review.issues,
                                key = { index, issue -> "${index}|${issue.sourceId}|${issue.code.name}" },
                            ) { index, issue ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().testTag("native-project-import-issue-$index"),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${issue.fileName}: ${labels.issueText(issue.code)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { actions.remove(issue.sourceId) },
                                        enabled = !state.busy,
                                        modifier = Modifier.testTag("native-project-import-issue-remove-$index"),
                                    ) { Text(labels.remove) }
                                }
                            }
                        }
                        state.error?.takeIf { it.isNotBlank() }?.let { message ->
                            item(key = "error") {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("native-project-import-error"),
                                )
                            }
                        }
                        if (state.busy) {
                            item(key = "busy") {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().testTag("native-project-import-busy"),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = actions.cancel,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("native-project-import-cancel"),
                        ) { Text(labels.cancel) }
                        Button(
                            onClick = { actions.create(review) },
                            enabled = createEnabled,
                            modifier = Modifier.testTag("native-project-import-create"),
                        ) { Text(labels.create) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeProjectImportFileRow(
    file: NativeImportFile,
    busy: Boolean,
    actions: NativeProjectImportActions,
    labels: NativeProjectImportLabels,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("native-project-import-file-${file.order}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${file.order + 1}. ${file.fileName}", style = MaterialTheme.typography.titleSmall)
            Text(
                "${labels.format}: ${labels.formatText(file.format)} · " +
                    "${labels.size}: ${labels.bytesText(file.sizeBytes)} · " +
                    "${labels.encoding}: ${file.encoding ?: labels.unknownEncoding}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { actions.remove(file.sourceId) },
            enabled = !busy,
            modifier = Modifier.testTag("native-project-import-remove-${file.order}"),
        ) { Text(labels.remove) }
    }
}

/**
 * State comes from the caller; [review] must be produced by [reviewNativeProjectImport] with the
 * current project registry names. No project or asset write happens while this dialog is open.
 */
internal data class NativeProjectImportUiState(
    val review: NativeProjectImportReview = NativeProjectImportReview(),
    val busy: Boolean = false,
    val error: String? = null,
)

internal class NativeProjectImportActions(
    val chooseFiles: () -> Unit,
    val chooseDirectory: () -> Unit,
    val remove: (sourceId: String) -> Unit,
    val create: (NativeProjectImportReview) -> Unit,
    val cancel: () -> Unit,
)

/**
 * All user-visible text is supplied by the caller so this component does not add resource keys or
 * assume a locale. The *Text callbacks keep format/issue labels in the same five-language sources
 * as the rest of the native UI.
 */
internal data class NativeProjectImportLabels(
    val title: String,
    val description: String,
    val note: String,
    val chooseFiles: String,
    val chooseDirectory: String,
    val noFiles: String,
    val projectName: String,
    val seed: String,
    val format: String,
    val size: String,
    val encoding: String,
    val unknownEncoding: String,
    val remove: String,
    val issues: String,
    val create: String,
    val cancel: String,
    val formatText: (NativeImportFormat) -> String,
    val seedText: (NativeProjectSeed) -> String,
    val issueText: (NativeImportIssueCode) -> String,
    val bytesText: (Long) -> String,
)
