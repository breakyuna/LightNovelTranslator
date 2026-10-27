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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillLimits
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Skill catalog editor over the existing file-backed store: list, enable, reorder, create,
 * metadata/body edit and delete. Built-in skills stay read-only. This dialog never executes a
 * skill and never owns a second catalog.
 */
@Composable
internal fun NativeSkillsDialog(actions: NativeSkillActions, dismiss: () -> Unit,
                                openPersona: (() -> Unit)? = null) {
    var records by remember { mutableStateOf<List<WorkspaceSkillRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<WorkspaceSkillRecord?>(null) }
    var content by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var order by remember { mutableStateOf("0") }
    var languages by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<WorkspaceSkillRecord?>(null) }
    var filesFor by remember { mutableStateOf<WorkspaceSkillRecord?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true; failed = false
        try { records = actions.list() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true } finally { loading = false }
    }
    fun edit(record: WorkspaceSkillRecord?) {
        selected = record
        creating = record == null
        name = record?.summary?.name.orEmpty()
        description = record?.summary?.description.orEmpty()
        enabled = record?.summary?.enabled ?: true
        order = (record?.summary?.order ?: records.size).toString()
        languages = NativeSkillMetadata.formatLabels(record?.summary?.languages.orEmpty())
        tags = NativeSkillMetadata.formatLabels(record?.summary?.tags.orEmpty())
        content = ""
        editorError = null
        saving = false
    }
    suspend fun mutate(block: suspend () -> Unit) {
        val failure = try {
            block(); null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        reload()
        if (failure != null) failed = true
    }
    LaunchedEffect(Unit) { reload() }
    val selectedSkillId = selected?.summary?.id
    LaunchedEffect(selectedSkillId) {
        val record = selected ?: return@LaunchedEffect
        val target = record.summary.id
        try {
            val text = actions.read(target).orEmpty()
            if (selected?.summary?.id == target) content = text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (selected?.summary?.id == target) {
                editorError = nativeSkillEditorError(IllegalStateException("Skill body could not be read"))
            }
        }
    }

    filesFor?.let { fixed ->
        NativeSkillFilesDialog(actions, fixed, { filesFor = null })
        return
    }
    // Keep the window and focus owner stable when switching between catalog and editor.
    Dialog(onDismissRequest = {
        when {
            deleteConfirm != null -> deleteConfirm = null
            selected != null || creating -> { selected = null; creating = false }
            else -> dismiss()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        deleteConfirm?.let { fixed ->
            NativeDialogSurface(
                title = { Text(stringResource(R.string.native_skills_delete)) },
                text = { Text(stringResource(R.string.native_skills_delete_confirm, fixed.summary.name)) },
                confirmButton = { TextButton(onClick = {
                    deleteConfirm = null
                    scope.launch {
                        runCatching { actions.delete(fixed.summary.id, fixed.fingerprint) }
                        selected = null; reload()
                    }
                }, modifier = Modifier.testTag("native-skill-delete-confirm")) { Text(stringResource(R.string.native_discard)) } },
                dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(R.string.native_cancel)) } })
            return@Dialog
        }

        if (selected != null || creating) {
            val current = selected
            val readOnly = current?.summary?.source == WorkspaceSkillSource.BUILT_IN
            val sourceLabel = stringResource(
                if (current?.summary?.source == WorkspaceSkillSource.BUILT_IN)
                    R.string.native_skills_source_builtin
                else R.string.native_skills_source_user,
            )
            val input = NativeSkillEditorInput(name, description, content, enabled, order, languages, tags,
                policy = current?.summary?.policy ?: WorkspaceSkillPolicy.DEFAULT)
            val metadataValid = remember(input, current?.summary?.id) {
                runCatching {
                    input.toDraft(current?.summary?.id ?: WorkspaceSkillId("draft-validation"))
                }.isSuccess
            }
            NativeDialogSurface(
                title = { Text(stringResource(if (creating) R.string.native_skills_create else R.string.native_skills_edit)) },
                text = {
                    Column(
                        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.native_skills_source_value, sourceLabel),
                            style = MaterialTheme.typography.bodySmall)
                        NativeSkillPolicySummary(
                            policy = current?.summary?.policy ?: WorkspaceSkillPolicy.DEFAULT,
                            builtIn = readOnly,
                            modifier = Modifier.testTag("native-skill-policy"),
                        )
                        if (!metadataValid) {
                            Text(stringResource(R.string.native_skills_metadata_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        editorError?.let {
                            Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("native-skill-editor-error"))
                        }
                        if (readOnly) {
                            Text(stringResource(R.string.native_skills_builtin_readonly),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_skills_enabled), Modifier.weight(1f))
                            Switch(enabled, { enabled = it }, enabled = !readOnly && !saving,
                                modifier = Modifier.testTag("native-skill-enabled"))
                        }
                        OutlinedTextField(name, { if (it.length <= 128) name = it },
                            label = { Text(stringResource(R.string.native_skills_name)) }, singleLine = true,
                            enabled = !readOnly && !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-skill-name"))
                        OutlinedTextField(description, { if (it.length <= 1_024) description = it },
                            label = { Text(stringResource(R.string.native_skills_description)) }, maxLines = 3,
                            enabled = !readOnly && !saving,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(order, { if (it.length <= 7 && it.all(Char::isDigit)) order = it },
                            label = { Text(stringResource(R.string.native_skills_order)) }, singleLine = true,
                            enabled = !readOnly && !saving,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text(stringResource(R.string.native_skills_order_hint)) },
                            modifier = Modifier.fillMaxWidth().testTag("native-skill-order"))
                        OutlinedTextField(languages, { if (it.length <= 4_096) languages = it },
                            label = { Text(stringResource(R.string.native_skills_languages)) }, maxLines = 2,
                            enabled = !readOnly && !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-skill-languages"))
                        OutlinedTextField(tags, { if (it.length <= 8_192) tags = it },
                            label = { Text(stringResource(R.string.native_skills_tags)) }, maxLines = 2,
                            enabled = !readOnly && !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-skill-tags"))
                        Text(stringResource(
                            R.string.native_skills_labels_hint,
                            WorkspaceSkillLimits.MAX_LANGUAGES,
                            WorkspaceSkillLimits.MAX_TAGS,
                        ), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(content, { if (it.length <= 64_000) content = it },
                            label = { Text(stringResource(R.string.native_skills_content)) }, minLines = 4, maxLines = 12,
                            enabled = !readOnly && !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-skill-content"))
                    }
                },
                confirmButton = {
                    TextButton(enabled = !readOnly && !saving && name.isNotBlank() && content.isNotBlank() &&
                        metadataValid,
                        onClick = {
                            val record = selected
                            scope.launch {
                                saving = true
                                editorError = null
                                try {
                                    val draft = NativeSkillEditorInput(
                                        name, description, content, enabled, order, languages, tags,
                                        policy = record?.summary?.policy ?: WorkspaceSkillPolicy.DEFAULT,
                                    ).toDraft(record?.summary?.id ?: WorkspaceSkillId("user-" + UUID.randomUUID()))
                                    if (record == null) actions.create(draft)
                                    else actions.update(record.summary.id, draft, record.fingerprint)
                                    selected = null; creating = false; reload()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    editorError = nativeSkillEditorError(failure)
                                } finally {
                                    saving = false
                                }
                            }
                        }, modifier = Modifier.testTag("native-skill-save")) { Text(stringResource(R.string.native_save)) }
                },
                dismissButton = { TextButton(onClick = { selected = null; creating = false },
                    modifier = Modifier.testTag("native-skill-editor-cancel")) { Text(stringResource(R.string.native_cancel)) } })
            return@Dialog
        }

        NativeDialogSurface(
            modifier = Modifier.testTag("native-skills-catalog"),
            title = { Text(stringResource(R.string.native_skills_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_skills_notice), style = MaterialTheme.typography.bodySmall)
                    if (failed) Text(stringResource(R.string.native_skills_error), color = MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(records, key = { it.summary.id.value }) { record ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(record.summary.enabled, { checked ->
                                            scope.launch {
                                                mutate { actions.setEnabled(record.summary.id, checked, record.fingerprint) }
                                            }
                                        }, enabled = !loading)
                                        Text(record.summary.name, Modifier.weight(1f))
                                        Text(stringResource(
                                            if (record.summary.source == WorkspaceSkillSource.BUILT_IN)
                                                R.string.native_skills_source_builtin
                                            else R.string.native_skills_source_user,
                                        ), style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (record.summary.description.isNotBlank()) Text(record.summary.description,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.native_skills_order_value, record.summary.order),
                                        style = MaterialTheme.typography.labelSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { edit(record) }, enabled = !loading) {
                                            Text(stringResource(R.string.native_skills_edit))
                                        }
                                        TextButton(onClick = { filesFor = record }, enabled = !loading,
                                            modifier = Modifier.testTag("native-skill-files-${record.summary.id.value}")) {
                                            Text(stringResource(R.string.native_skills_files))
                                        }
                                        TextButton(onClick = {
                                            val index = records.indexOfFirst { it.summary.id == record.summary.id }
                                            if (index > 0) {
                                                val previous = records[index - 1]
                                                var target = previous.summary.order
                                                if (target >= record.summary.order) target = (target - 1).coerceAtLeast(0)
                                                scope.launch {
                                                    mutate { actions.setOrder(record.summary.id, target, record.fingerprint) }
                                                }
                                            }
                                        }, enabled = !loading &&
                                            records.firstOrNull()?.summary?.id != record.summary.id) {
                                            Text(stringResource(R.string.native_policy_up))
                                        }
                                        TextButton(onClick = {
                                            val index = records.indexOfFirst { it.summary.id == record.summary.id }
                                            if (index >= 0 && index < records.lastIndex) {
                                                val next = records[index + 1]
                                                val target = if (next.summary.order <= record.summary.order)
                                                    record.summary.order + 1 else next.summary.order
                                                scope.launch {
                                                    mutate { actions.setOrder(record.summary.id, target, record.fingerprint) }
                                                }
                                            }
                                        }, enabled = !loading &&
                                            records.lastOrNull()?.summary?.id != record.summary.id) {
                                            Text(stringResource(R.string.native_policy_down))
                                        }
                                        if (record.summary.source == WorkspaceSkillSource.USER) {
                                            TextButton(onClick = { deleteConfirm = record }, enabled = !loading) {
                                                Text(stringResource(R.string.native_skills_delete))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!loading && records.isEmpty()) item(key = "empty") { Text(stringResource(R.string.native_skills_empty)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { edit(null) }) { Text(stringResource(R.string.native_skills_create)) } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (openPersona != null) {
                        TextButton(onClick = openPersona,
                            modifier = Modifier.testTag("native-skills-open-persona")) {
                            Text(stringResource(R.string.native_skills_open_persona))
                        }
                    }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
                }
            })
    }
}

/**
 * Read-only policy projection. Built-in packages define their own grants; user edits preserve
 * whatever the store has already validated. No control here can widen a policy.
 */
@Composable
internal fun NativeSkillPolicySummary(
    policy: WorkspaceSkillPolicy,
    builtIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val materials = stringResource(R.string.native_skills_policy_materials)
    val artifacts = stringResource(R.string.native_skills_policy_artifacts)
    val search = stringResource(R.string.native_skills_policy_search)
    val fetch = stringResource(R.string.native_skills_policy_fetch)
    val proposals = stringResource(R.string.native_skills_policy_proposals)
    val print = stringResource(R.string.native_skills_policy_print)
    val allowed = listOfNotNull(
        materials.takeIf { policy.allowMaterials },
        artifacts.takeIf { policy.allowArtifacts },
        search.takeIf { policy.allowSearch },
        fetch.takeIf { policy.allowFetch },
        proposals.takeIf { policy.allowProposeChanges },
        print.takeIf { policy.allowPrint },
    )
    val denied = listOfNotNull(
        materials.takeIf { !policy.allowMaterials },
        artifacts.takeIf { !policy.allowArtifacts },
        search.takeIf { !policy.allowSearch },
        fetch.takeIf { !policy.allowFetch },
        proposals.takeIf { !policy.allowProposeChanges },
        print.takeIf { !policy.allowPrint },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.native_skills_policy_title),
            style = MaterialTheme.typography.labelLarge)
        if (allowed.isNotEmpty()) Text(
            stringResource(R.string.native_skills_policy_allowed, allowed.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
        if (denied.isNotEmpty()) Text(
            stringResource(R.string.native_skills_policy_denied, denied.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.native_skills_policy_limits,
                policy.maxRunMillis,
                policy.maxOutputBytes,
                policy.maxArtifactBytes,
                policy.maxChangeBytes,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                if (builtIn) R.string.native_skills_policy_builtin
                else R.string.native_skills_policy_preserved,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
