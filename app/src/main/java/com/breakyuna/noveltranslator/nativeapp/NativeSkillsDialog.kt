package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Minimal skill catalog editor: list, enable, reorder, create, edit and delete. Built-in bodies
 * are read-only; user skills are ordinary private files. This dialog never executes a skill.
 */
@Composable
internal fun NativeSkillsDialog(actions: NativeSkillActions, dismiss: () -> Unit) {
    var records by remember { mutableStateOf<List<WorkspaceSkillRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<WorkspaceSkillRecord?>(null) }
    var content by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<WorkspaceSkillRecord?>(null) }
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
        content = ""
        if (record != null) scope.launch {
            content = runCatching { actions.read(record.summary.id).orEmpty() }.getOrDefault("")
        }
    }
    LaunchedEffect(Unit) { reload() }

    deleteConfirm?.let { fixed ->
        AlertDialog(onDismissRequest = { deleteConfirm = null },
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
    }

    if (selected != null || creating) {
        AlertDialog(onDismissRequest = { selected = null; creating = false },
            title = { Text(stringResource(if (creating) R.string.native_skills_create else R.string.native_skills_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { if (it.length <= 128) name = it },
                        label = { Text(stringResource(R.string.native_skills_name)) }, singleLine = true,
                        enabled = selected?.summary?.source != WorkspaceSkillSource.BUILT_IN,
                        modifier = Modifier.fillMaxWidth().testTag("native-skill-name"))
                    OutlinedTextField(description, { if (it.length <= 1_024) description = it },
                        label = { Text(stringResource(R.string.native_skills_description)) }, maxLines = 3,
                        enabled = selected?.summary?.source != WorkspaceSkillSource.BUILT_IN,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { if (it.length <= 64_000) content = it },
                        label = { Text(stringResource(R.string.native_skills_content)) }, minLines = 4, maxLines = 12,
                        enabled = selected?.summary?.source != WorkspaceSkillSource.BUILT_IN,
                        modifier = Modifier.fillMaxWidth().testTag("native-skill-content"))
                    if (selected?.summary?.source == WorkspaceSkillSource.BUILT_IN) {
                        Text(stringResource(R.string.native_skills_builtin_readonly), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && content.isNotBlank() &&
                    selected?.summary?.source != WorkspaceSkillSource.BUILT_IN,
                    onClick = {
                        val current = selected
                        scope.launch {
                            try {
                                if (current == null) {
                                    val draft = WorkspaceSkillDraft(WorkspaceSkillId("user-" + UUID.randomUUID()),
                                        name.trim(), content, description.trim(), enabled = true, order = records.size)
                                    actions.create(draft)
                                } else {
                                    val draft = WorkspaceSkillDraft(current.summary.id, name.trim(), content,
                                        description.trim(), current.summary.enabled, current.summary.order,
                                        current.summary.languages, current.summary.tags)
                                    actions.update(current.summary.id, draft, current.fingerprint)
                                }
                                selected = null; creating = false; reload()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                        }
                    }, modifier = Modifier.testTag("native-skill-save")) { Text(stringResource(R.string.native_save)) }
            },
            dismissButton = { TextButton(onClick = { selected = null; creating = false }) { Text(stringResource(R.string.native_cancel)) } })
    }

    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_skills_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_skills_notice), style = MaterialTheme.typography.bodySmall)
                if (failed) Text(stringResource(R.string.native_skills_error), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(records, key = { it.summary.id.value }) { record ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(record.summary.enabled, { checked ->
                                        scope.launch {
                                            runCatching { actions.setEnabled(record.summary.id, checked, record.fingerprint) }
                                            reload()
                                        }
                                    }, enabled = !loading && record.summary.source == WorkspaceSkillSource.USER)
                                    Text(record.summary.name, Modifier.weight(1f))
                                    Text(if (record.summary.source == WorkspaceSkillSource.BUILT_IN) "built-in" else "user",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                if (record.summary.description.isNotBlank()) Text(record.summary.description,
                                    style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { edit(record) }, enabled = !loading) {
                                        Text(stringResource(R.string.native_skills_edit))
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            val index = records.indexOfFirst { it.summary.id == record.summary.id }
                                            if (index > 0) runCatching {
                                                actions.setOrder(record.summary.id, records[index - 1].summary.order + 1,
                                                    record.fingerprint)
                                            }
                                            reload()
                                        }
                                    }, enabled = !loading && record.summary.source == WorkspaceSkillSource.USER &&
                                        records.firstOrNull()?.summary?.id != record.summary.id) {
                                        Text(stringResource(R.string.native_policy_up))
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            val index = records.indexOfFirst { it.summary.id == record.summary.id }
                                            if (index >= 0 && index < records.lastIndex) runCatching {
                                                actions.setOrder(record.summary.id, records[index + 1].summary.order - 1,
                                                    record.fingerprint)
                                            }
                                            reload()
                                        }
                                    }, enabled = !loading && record.summary.source == WorkspaceSkillSource.USER &&
                                        records.lastOrNull()?.summary?.id != record.summary.id) {
                                        Text(stringResource(R.string.native_policy_down))
                                    }
                                    if (record.summary.source == WorkspaceSkillSource.USER) {
                                        TextButton(onClick = { deleteConfirm = record }, enabled = !loading && record.summary.source == WorkspaceSkillSource.USER) {
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
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } })
}
