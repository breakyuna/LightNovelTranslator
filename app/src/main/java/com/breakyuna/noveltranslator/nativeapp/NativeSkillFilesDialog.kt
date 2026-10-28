package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceFailureReason
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceSnapshot
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillFile
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Application-owned skill execution handle supplied by the project screen. The dialog only starts
 * or stops through this port; it never owns the execution coroutine.
 */
internal val LocalNativeSkillExecution = staticCompositionLocalOf<NativeSkillExecutionActions?> { null }

private val NoSkillExecutionState = MutableStateFlow<ForegroundWorkspaceSnapshot?>(null)

/** Script resources of the same skill cannot be edited while its direct run is active. */
internal fun skillExecutionLocksResources(
    snapshot: ForegroundWorkspaceSnapshot?,
    record: WorkspaceSkillRecord,
): Boolean = snapshot?.phase?.active == true && snapshot.request.skillId == record.summary.id.value

/** Localized short status for the dialog and the app-wide execution banner. */
internal fun skillExecutionPhaseLabel(snapshot: ForegroundWorkspaceSnapshot?): Int? = when (snapshot?.phase) {
    ForegroundWorkspacePhase.PREPARING -> R.string.native_skill_execution_starting
    ForegroundWorkspacePhase.RUNNING -> R.string.native_skill_execution_running
    ForegroundWorkspacePhase.STOPPING -> R.string.native_skill_execution_stopping
    ForegroundWorkspacePhase.COMPLETED -> R.string.native_skill_execution_completed
    ForegroundWorkspacePhase.CANCELLED -> R.string.native_skill_execution_cancelled
    ForegroundWorkspacePhase.FAILED -> when (snapshot?.failure) {
        ForegroundWorkspaceFailureReason.START_FAILED,
        ForegroundWorkspaceFailureReason.START_TIMEOUT,
        -> R.string.native_skill_execution_start_failed
        ForegroundWorkspaceFailureReason.EXECUTION_TIMEOUT -> R.string.native_skill_execution_timeout
        else -> R.string.native_skill_run_failed
    }
    ForegroundWorkspacePhase.INTERRUPTED -> R.string.native_skill_execution_interrupted
    null -> null
}

/** Resource browsing and version-checked user-file edits; execution uses the app-owned runner. */
@Composable
internal fun NativeSkillFilesDialog(actions: NativeSkillActions, record: WorkspaceSkillRecord,
    dismiss: () -> Unit) {
    var files by remember(record.summary.id) { mutableStateOf<List<WorkspaceSkillFile>?>(null) }
    var preview by remember(record.summary.id) { mutableStateOf<String?>(null) }
    var selected by remember(record.summary.id) { mutableStateOf<String?>(null) }
    var failed by remember(record.summary.id) { mutableStateOf(false) }
    var loading by remember(record.summary.id) { mutableStateOf(false) }
    var editing by remember(record.summary.id) { mutableStateOf<WorkspaceSkillFile?>(null) }
    var editorVisible by remember(record.summary.id) { mutableStateOf(false) }
    var execution by remember(record.summary.id) { mutableStateOf<NativeSkillExecutionTarget?>(null) }
    var runUnavailable by remember(record.summary.id) { mutableStateOf(false) }
    var refresh by remember(record.summary.id) { mutableStateOf(0) }
    val controller = LocalNativeSkillExecution.current
    val executionState by (controller?.state ?: NoSkillExecutionState).collectAsState()
    val resourcesLocked = skillExecutionLocksResources(executionState, record)
    val canEdit = record.summary.source == WorkspaceSkillSource.USER &&
        actions.writeResource != null && !resourcesLocked
    val scope = rememberCoroutineScope()
    val binaryLabel = stringResource(R.string.native_skill_file_binary)
    LaunchedEffect(record.summary.id, refresh) {
        try { files = actions.files(record.summary.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    fun open(file: WorkspaceSkillFile) {
        if (!file.text) { preview = null; selected = file.path.value; return }
        selected = file.path.value
        loading = true; failed = false
        scope.launch {
            try {
                preview = actions.readFileText(record.summary.id,
                    file.path.value.removePrefix(record.summary.id.value + "/"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true } finally { loading = false }
        }
    }
    if (execution != null && controller != null) {
        NativeSkillExecutionDialog(controller, execution!!) { execution = null }
    }
    if (editorVisible) NativeSkillResourceEditor(actions, record, editing,
        dismiss = { editorVisible = false },
        changed = { refresh++; preview = null; selected = null })
    NativeContentDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_skill_files_title, record.summary.name)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("native-skill-files"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (runUnavailable) Text(stringResource(R.string.native_skill_run_unavailable), color = MaterialTheme.colorScheme.error)
                if (resourcesLocked) Text(stringResource(R.string.native_skill_execution_locked),
                    style = MaterialTheme.typography.bodySmall)
                NativeSkillPolicySummary(
                    policy = record.summary.policy,
                    builtIn = record.summary.source == WorkspaceSkillSource.BUILT_IN,
                    modifier = Modifier.testTag("native-skill-files-policy"),
                )
                if (failed) Text(stringResource(R.string.native_skill_file_load_error),
                    color = MaterialTheme.colorScheme.error)
                when {
                    files == null && !failed -> Text(stringResource(R.string.native_working))
                    files?.isEmpty() == true -> Text(stringResource(R.string.native_skill_file_empty))
                }
                if (canEdit) TextButton(onClick = { editing = null; editorVisible = true }) {
                    Text(stringResource(R.string.native_skill_resource_new))
                }
                for (file in files.orEmpty()) {
                    val relative = file.path.value.removePrefix(record.summary.id.value + "/")
                    TextButton(onClick = { open(file) }, modifier = Modifier.fillMaxWidth()) {
                        Text(relative + if (file.text) "" else "  ·  " + binaryLabel,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (record.summary.enabled && relative.startsWith("scripts/") && file.text &&
                        actions.prepareExecution != null && controller != null && !resourcesLocked) TextButton(onClick = {
                            execution = actions.prepareExecution.invoke(record, file)
                            runUnavailable = execution == null
                        }) { Text(stringResource(R.string.native_skill_run)) }
                    if (canEdit && file.text && relative != "SKILL.md") TextButton(
                        onClick = { editing = file; editorVisible = true }) {
                        Text(stringResource(R.string.native_skill_resource_edit))
                    }
                    Text(stringResource(R.string.native_skill_file_size, file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp))
                }
                if (selected != null && loading) Text(stringResource(R.string.native_working))
                if (preview != null) Text(preview!!.take(16_384), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                        .testTag("native-skill-file-preview"))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } })
}

@Composable
private fun NativeSkillExecutionDialog(
    controller: NativeSkillExecutionActions,
    target: NativeSkillExecutionTarget,
    close: () -> Unit,
) {
    var input by remember(target) { mutableStateOf("{}") }
    var network by remember(target) { mutableStateOf(false) }
    var attempted by remember(target) { mutableStateOf(false) }
    var invalid by remember(target) { mutableStateOf(false) }
    var token by remember(target) { mutableStateOf<String?>(null) }
    val snapshot by controller.state.collectAsState()
    val current = snapshot?.takeIf { it.request.identity == target.identity }
    val active = current?.phase?.active == true
    val policy = target.skill.summary.policy
    val networkAllowed = nativeSkillNetworkCheckboxEnabled(policy)
    // A recomposed dialog can adopt the still-live token without replaying the Run.
    LaunchedEffect(current?.token) {
        if (token == null && current != null) token = current.token
    }
    NativeContentDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.native_skill_run)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.file.path.value)
                Text(stringResource(R.string.native_workspace_revision, target.revision))
                Text(stringResource(R.string.native_skill_run_notice))
                Text(stringResource(R.string.native_skill_execution_background),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(input, { if (it.length <= 16_384) input = it }, enabled = !attempted && !active,
                    label = { Text(stringResource(R.string.native_skill_run_input)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.native_skill_run_policy_notice),
                    style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        if (networkAllowed) R.string.native_skill_run_network_allowed
                        else R.string.native_skill_run_network_denied,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (networkAllowed) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )
                Checkbox(network, { network = it }, enabled = !attempted && !active && networkAllowed,
                    modifier = Modifier.testTag("native-skill-run-network"))
                Text(stringResource(R.string.native_skill_run_network))
                skillExecutionPhaseLabel(current)?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.titleSmall)
                }
                if (invalid) Text(stringResource(R.string.native_skill_run_failed), color = MaterialTheme.colorScheme.error)
                current?.observation?.logs?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                current?.observation?.artifacts?.forEach { artifact ->
                    Text(artifact.path, style = MaterialTheme.typography.bodySmall)
                }
                if (current?.phase == ForegroundWorkspacePhase.COMPLETED) {
                    Text(stringResource(R.string.native_skill_run_result))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !attempted && !active, onClick = {
                val started = try {
                    controller.start(target, input, nativeDirectExecutionFetchAllowed(policy, network))
                } catch (_: Exception) {
                    null
                }
                token = started
                attempted = true
                invalid = started == null
            }) { Text(stringResource(R.string.native_skill_run)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val liveToken = token ?: current?.token
                if (active && liveToken != null) TextButton(onClick = { controller.stop(liveToken) }) {
                    Text(stringResource(R.string.native_skill_execution_stop))
                }
                TextButton(onClick = close) { Text(stringResource(R.string.native_close)) }
            }
        })
}

@Composable
private fun NativeSkillResourceEditor(actions: NativeSkillActions, record: WorkspaceSkillRecord,
    file: WorkspaceSkillFile?, dismiss: () -> Unit, changed: () -> Unit) {
    val originalPath = file?.path?.value?.removePrefix(record.summary.id.value + "/").orEmpty()
    var path by remember(record.summary.id, originalPath) { mutableStateOf(originalPath) }
    var text by remember(record.summary.id, originalPath) { mutableStateOf("") }
    var base by remember(record.summary.id, originalPath) { mutableStateOf("") }
    var loading by remember(record.summary.id, originalPath) { mutableStateOf(file != null) }
    var loaded by remember(record.summary.id, originalPath) { mutableStateOf(file == null) }
    var busy by remember(record.summary.id, originalPath) { mutableStateOf(false) }
    var failed by remember(record.summary.id, originalPath) { mutableStateOf(false) }
    var discard by remember(record.summary.id, originalPath) { mutableStateOf(false) }
    var deleting by remember(record.summary.id, originalPath) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = text != base || path != originalPath
    fun close() { if (!busy) { if (dirty) discard = true else dismiss() } }
    LaunchedEffect(record.summary.id, originalPath) {
        if (file != null) try {
            val body = actions.readFileText(record.summary.id, originalPath)
            if (body == null) failed = true else { text = body; base = body; loaded = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    fun mutate(operation: suspend () -> Unit) {
        busy = true; failed = false
        scope.launch {
            try { operation(); changed(); dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    NativeContentDialog(onDismissRequest = ::close,
        title = { Text(stringResource(R.string.native_skill_resource_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) Text(stringResource(R.string.native_skill_resource_error), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(path, { path = it.take(512) }, enabled = !busy && !loading,
                    label = { Text(stringResource(R.string.native_skill_resource_path)) }, modifier = Modifier.fillMaxWidth())
                if (loading) Text(stringResource(R.string.native_working))
                OutlinedTextField(text, { if (it.length <= 64 * 1024) text = it },
                    enabled = !busy && loaded,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp))
                if (file != null) {
                    TextButton(enabled = !busy && loaded && text == base && path != originalPath && actions.renameResource != null,
                        onClick = { mutate { requireNotNull(actions.renameResource)(record.summary.id, originalPath, path,
                            record.fingerprint, file.sha256) } }) { Text(stringResource(R.string.native_skill_resource_rename)) }
                    TextButton(enabled = !busy && loaded && actions.deleteResource != null,
                        onClick = { deleting = true }) { Text(stringResource(R.string.native_skill_resource_delete)) }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy && loaded && path.isNotBlank() &&
            (file == null || path == originalPath) && actions.writeResource != null,
            onClick = { mutate { requireNotNull(actions.writeResource)(record.summary.id, path, text,
                record.fingerprint, file?.sha256) } }) { Text(stringResource(R.string.native_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = ::close) { Text(stringResource(R.string.native_cancel)) } })
    if (discard) NativeContentDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.native_skill_resource_discard)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_skill_resource_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_cancel)) } })
    if (deleting && file != null) NativeContentDialog(onDismissRequest = { if (!busy) deleting = false },
        title = { Text(stringResource(R.string.native_skill_resource_delete)) },
        text = { Text(originalPath) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            deleting = false
            mutate { requireNotNull(actions.deleteResource)(record.summary.id, originalPath, record.fingerprint, file.sha256) }
        }) { Text(stringResource(R.string.native_skill_resource_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.native_cancel)) } })

}
