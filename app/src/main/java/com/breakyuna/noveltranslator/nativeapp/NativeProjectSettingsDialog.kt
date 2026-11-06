package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID

internal class NativeProjectSettingsChange(val ref: LocalProjectRef, val revision: Long,
    val command: CommandId, val settings: ProjectSettings)

@Composable
internal fun NativeProjectSettingsDialog(state: NativeProjectUiState, close: () -> Unit,
    save: (NativeProjectSettingsChange) -> Unit) {
    val project = remember { requireNotNull(state.project) }
    val initial = project.settings ?: ProjectSettings()
    var target by rememberSaveable(project.ref) { mutableStateOf(initial.targetLanguage) }
    var source by rememberSaveable(project.ref) { mutableStateOf(initial.sourceLanguage) }
    var deduplicate by rememberSaveable(project.ref) { mutableStateOf(initial.bilingualDeduplicateSame) }
    var names by rememberSaveable(project.ref) { mutableStateOf(initial.writeTranslatedNames) }
    val command = rememberSaveable(project.ref, target, source, deduplicate, names) { UUID.randomUUID().toString() }
    val settings = remember(target, source, deduplicate, names) {
        runCatching { ProjectSettings(target, source, deduplicate, names, initial.promptEnabled, initial.pdfPages) }.getOrNull()
    }
    var submitted by remember(project.ref) { mutableStateOf<NativeProjectSettingsChange?>(null) }
    val current = state.project
    LaunchedEffect(state.busy, current?.revision, submitted) {
        val fixed = submitted
        if (fixed != null && !state.busy && current != null && current.ref == fixed.ref && current.revision > fixed.revision &&
            current.settings?.fingerprint() == fixed.settings.fingerprint()) close()
    }
    val eligible = !state.busy && current != null && current.ref == project.ref && current.revision == project.revision &&
        state.editor == null && state.manualBatch == null && !state.selectingItems &&
        !state.policyLoadIncomplete && !state.runLoadIncomplete && state.policyDraft?.dirty != true
    Dialog(onDismissRequest = { if (!state.busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_project_settings), style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.native_project_settings_description))
                        OutlinedTextField(target, { if (it.length <= 80) target = it }, singleLine = true, enabled = !state.busy,
                            label = { Text(stringResource(R.string.native_remote_target)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(source, { if (it.length <= 80) source = it }, singleLine = true, enabled = !state.busy,
                            label = { Text(stringResource(R.string.native_remote_source)) }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deduplicate, { deduplicate = it }, enabled = !state.busy)
                            Text(stringResource(R.string.native_project_settings_deduplicate), Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(names, { names = it }, enabled = !state.busy)
                            Text(stringResource(R.string.native_project_settings_names), Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = close, enabled = !state.busy) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(onClick = {
                            settings?.let {
                                val fixed = NativeProjectSettingsChange(project.ref, project.revision, CommandId(command), it)
                                submitted = fixed
                                save(fixed)
                            }
                        }, enabled = eligible && settings != null) { Text(stringResource(R.string.native_asset_confirm)) }
                    }
                }
            }
        }
    }
}
