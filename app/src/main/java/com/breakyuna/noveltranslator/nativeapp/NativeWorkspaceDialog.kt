package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactInventory
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import java.util.UUID

/** UI-facing workspace reads and explicit user-confirmed artifact import. */
internal class NativeWorkspaceActions(
    val materials: suspend (LocalProjectRef, Long) -> List<WorkspaceMaterialSummary>,
    val readMaterial: suspend (LocalProjectRef, Long, WorkspaceMaterialId) -> String,
    val artifacts: suspend (LocalProjectRef, RunId?) -> List<WorkspaceArtifactRecord>,
    val readArtifact: suspend (LocalProjectRef, RunId, WorkspaceArtifactId) -> String,
    val importArtifact: (suspend (LocalProjectRef, Long, WorkspaceArtifactRecord, String, CommandId) -> Unit)? = null,
    val inventory: (suspend (LocalProjectRef, RunId) -> List<WorkspaceArtifactInventory>)? = null,
)

private sealed interface WorkspacePreviewTarget {
    class Material(val id: WorkspaceMaterialId) : WorkspacePreviewTarget
    class Artifact(val id: WorkspaceArtifactId) : WorkspacePreviewTarget
}

@Composable
internal fun NativeWorkspaceDialog(
    ref: LocalProjectRef,
    revision: Long,
    chatId: ChatId?,
    runId: RunId?,
    actions: NativeWorkspaceActions,
    dismiss: () -> Unit,
    saveArtifact: (WorkspaceArtifactRecord) -> Unit,
) {
    var materials by remember(ref, revision) { mutableStateOf<List<WorkspaceMaterialSummary>?>(null) }
    var materialFailed by remember(ref, revision) { mutableStateOf(false) }
    var artifacts by remember(ref, chatId, runId) { mutableStateOf<List<WorkspaceArtifactRecord>?>(null) }
    var artifactInventory by remember(ref, chatId, runId) { mutableStateOf<Map<WorkspaceArtifactId, WorkspaceArtifactAvailability>>(emptyMap()) }
    var refresh by remember(ref, chatId, runId) { mutableStateOf(0) }
    var artifactFailed by remember(ref, chatId, runId) { mutableStateOf(false) }
    var preview by remember(ref, revision, chatId, runId) { mutableStateOf<WorkspacePreviewTarget?>(null) }

    LaunchedEffect(ref, revision) {
        materialFailed = false
        try { materials = actions.materials(ref, revision) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { materialFailed = true; materials = emptyList() }
    }
    LaunchedEffect(ref, chatId, runId, refresh) {
        artifactFailed = false
        if (runId == null) {
            artifacts = emptyList()
            return@LaunchedEffect
        }
        artifacts = null
        try {
            if (actions.inventory != null) {
                val inspected = actions.inventory.invoke(ref, runId)
                artifacts = inspected.map { it.record }
                artifactInventory = inspected.associate { it.record.id to it.availability }
            } else artifacts = actions.artifacts(ref, runId)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { artifactFailed = true; artifacts = emptyList() }
    }

    var importing by remember(ref, revision, chatId, runId) { mutableStateOf<WorkspaceArtifactRecord?>(null) }
    var importBusy by remember(ref, revision, chatId, runId) { mutableStateOf(false) }
    // All pages share one window; switching content must not recreate the focus owner.
    Dialog(onDismissRequest = {
        when {
            importing != null -> if (!importBusy) importing = null
            preview != null -> preview = null
            else -> dismiss()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val importingArtifact = importing
        if (importingArtifact != null) {
            NativeWorkspaceArtifactImport(ref, revision, importingArtifact, requireNotNull(actions.importArtifact),
                busy = importBusy, setBusy = { importBusy = it }, close = { importing = null })
            return@Dialog
        }
        preview?.let { target -> when (target) {
            is WorkspacePreviewTarget.Material -> NativeWorkspacePreview(
                title = stringResource(R.string.native_workspace_preview_title),
                identity = listOf("material", ref, revision, chatId, runId, target.id.value),
                load = { actions.readMaterial(ref, revision, target.id) },
                close = { preview = null },
            )
            is WorkspacePreviewTarget.Artifact -> if (runId != null) NativeWorkspacePreview(
                title = stringResource(R.string.native_workspace_preview_title),
                identity = listOf("artifact", ref, revision, chatId, runId, target.id.value),
                load = { actions.readArtifact(ref, runId, target.id) },
                close = { preview = null },
            )
        }; return@Dialog }

        NativeDialogSurface(
            modifier = Modifier.testTag("native-workspace-dialog"),
            title = { Text(stringResource(R.string.native_workspace_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_workspace_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_workspace_revision, revision), style = MaterialTheme.typography.labelMedium)
                    if (runId == null) Text(stringResource(R.string.native_workspace_no_run), style = MaterialTheme.typography.bodySmall)
                    else Text(stringResource(R.string.native_workspace_run, runId.value),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider()
                    Text(stringResource(R.string.native_workspace_materials), style = MaterialTheme.typography.titleSmall)
                    if (materialFailed) Text(stringResource(R.string.native_workspace_load_failed),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val loadedMaterials = materials
                    when {
                        loadedMaterials == null -> CircularProgressIndicator()
                        loadedMaterials.isEmpty() -> Text(stringResource(R.string.native_workspace_no_materials),
                            style = MaterialTheme.typography.bodySmall)
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(loadedMaterials, key = { "material-" + it.id.value }) { material ->
                                WorkspaceMaterialRow(material) {
                                    preview = WorkspacePreviewTarget.Material(material.id)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.native_workspace_artifacts), style = MaterialTheme.typography.titleSmall)
                    if (actions.inventory != null) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.native_workspace_recheck)) }
                    if (artifactFailed) Text(stringResource(R.string.native_workspace_load_failed),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val loadedArtifacts = artifacts
                    when {
                        runId == null -> Unit
                        loadedArtifacts == null -> CircularProgressIndicator()
                        loadedArtifacts.isEmpty() -> Text(stringResource(R.string.native_workspace_no_artifacts),
                            style = MaterialTheme.typography.bodySmall)
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(loadedArtifacts, key = { "artifact-" + it.id.value }) { artifact ->
                                WorkspaceArtifactRow(artifact,
                                    preview = if (isTextArtifact(artifact)) {
                                        { preview = WorkspacePreviewTarget.Artifact(artifact.id) }
                                    } else null,
                                    save = { saveArtifact(artifact) },
                                    importAction = if (actions.importArtifact != null) ({ importing = artifact }) else null,
                                    availability = artifactInventory[artifact.id])
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } })
    }
}

@Composable
private fun WorkspaceMaterialRow(material: WorkspaceMaterialSummary, preview: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(material.path.value, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.native_workspace_size, material.sizeBytes), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.native_workspace_digest, material.sha256),
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (isTextMaterial(material)) TextButton(onClick = preview,
            modifier = Modifier.testTag("native-workspace-preview-material-" + material.id.value)) {
            Text(stringResource(R.string.native_workspace_preview))
        }
    }
}

@Composable
private fun WorkspaceArtifactRow(
    artifact: WorkspaceArtifactRecord,
    preview: (() -> Unit)?,
    save: () -> Unit,
    importAction: (() -> Unit)? = null,
    availability: WorkspaceArtifactAvailability? = null,
) {
    val context = LocalContext.current
    val available = availability == null || availability == WorkspaceArtifactAvailability.VERIFIED
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(stringResource(R.string.native_workspace_created,
            android.text.format.DateUtils.formatDateTime(context, artifact.createdAtMillis,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)))
        availability?.let { Text(stringResource(when (it) {
            WorkspaceArtifactAvailability.VERIFIED -> R.string.native_workspace_verified
            WorkspaceArtifactAvailability.MISSING -> R.string.native_workspace_missing
            WorkspaceArtifactAvailability.MISMATCH -> R.string.native_workspace_mismatch
            WorkspaceArtifactAvailability.UNREADABLE -> R.string.native_workspace_unreadable
        })) }
        Row(Modifier.fillMaxWidth()) {
            Text(artifact.path.value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artifact.kind.name, style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(R.string.native_workspace_size, artifact.sizeBytes), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.native_workspace_digest, artifact.sha256),
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (preview != null) TextButton(onClick = preview, enabled = available,
                modifier = Modifier.testTag("native-workspace-preview-artifact-" + artifact.id.value)) {
                Text(stringResource(R.string.native_workspace_preview))
            } else Text(stringResource(R.string.native_workspace_binary),
                style = MaterialTheme.typography.labelSmall)
            if (importAction != null) TextButton(onClick = importAction, enabled = available,
                modifier = Modifier.testTag("native-workspace-import-" + artifact.id.value)) {
                Text(stringResource(R.string.native_workspace_import))
            }
            TextButton(onClick = save, enabled = available,
                modifier = Modifier.testTag("native-workspace-save-" + artifact.id.value)) {
                Text(stringResource(R.string.native_workspace_save))
            }
        }
    }
}

@Composable
private fun NativeWorkspacePreview(
    title: String,
    identity: List<Any?>,
    load: suspend () -> String,
    close: () -> Unit,
) {
    var text by remember(identity) { mutableStateOf<String?>(null) }
    var failed by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(identity) {
        failed = false
        try { text = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    NativeDialogSurface(
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_workspace_preview_notice), style = MaterialTheme.typography.bodySmall)
                when {
                    failed -> Text(stringResource(R.string.native_workspace_preview_failed),
                        color = MaterialTheme.colorScheme.error)
                    text == null -> CircularProgressIndicator()
                    else -> SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(requireNotNull(text), Modifier.testTag("native-workspace-preview-text"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_close)) } })
}

private fun isTextMaterial(material: WorkspaceMaterialSummary): Boolean =
    material.kind == WorkspaceMaterialKind.SOURCE || material.kind == WorkspaceMaterialKind.DATASET

private fun isTextArtifact(artifact: WorkspaceArtifactRecord): Boolean =
    artifact.kind in setOf(
        WorkspaceArtifactKind.TEXT,
        WorkspaceArtifactKind.MARKDOWN,
        WorkspaceArtifactKind.JSON,
        WorkspaceArtifactKind.CSV,
    )

@Composable
private fun NativeWorkspaceArtifactImport(ref: LocalProjectRef, revision: Long,
    artifact: WorkspaceArtifactRecord,
    apply: suspend (LocalProjectRef, Long, WorkspaceArtifactRecord, String, CommandId) -> Unit,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    close: () -> Unit) {
    var path by remember(ref, revision, artifact.runId, artifact.id) { mutableStateOf(artifact.path.value) }
    var failed by remember(ref, revision, artifact.runId, artifact.id) { mutableStateOf(false) }
    val command = remember(ref, revision, artifact.runId, artifact.id, path) { CommandId(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    NativeDialogSurface(
        title = { Text(stringResource(R.string.native_workspace_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_workspace_import_notice))
                Text(stringResource(R.string.native_workspace_revision, revision))
                Text(artifact.path.value)
                Text(stringResource(R.string.native_workspace_size, artifact.sizeBytes))
                Text(stringResource(R.string.native_workspace_digest, artifact.sha256),
                    style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(path, { path = it.take(512) }, enabled = !busy,
                    label = { Text(stringResource(R.string.native_workspace_import_path)) },
                    modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.native_workspace_import_error),
                    color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator()
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = close,
                modifier = Modifier.testTag("native-workspace-import-cancel")) {
                Text(stringResource(R.string.native_cancel))
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("native-workspace-import-confirm"),
                enabled = !busy && path.isNotBlank(), onClick = {
                    setBusy(true); failed = false
                    scope.launch {
                        try { apply(ref, revision, artifact, path, command); close() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { setBusy(false) }
                    }
                }) { Text(stringResource(R.string.native_workspace_import)) }
        })
}
