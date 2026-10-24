package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

internal typealias NativeRemoteTranslationAction = (LocalProjectRef, Long, ModelConfigurationRef, RemoteTranslationOptions, Boolean) -> Unit

/** A separate explicit paid-call confirmation. Merely editing this form never starts a request. */
@Composable
internal fun NativeRemoteTranslationDialog(state: NativeProjectUiState, onDismiss: () -> Unit,
    start: NativeRemoteTranslationAction) {
    val project = remember { requireNotNull(state.project) }
    val model = remember { requireNotNull(state.modelSettings) }
    var target by rememberSaveable(project.ref) { mutableStateOf(project.settings?.targetLanguage.orEmpty()) }
    var source by rememberSaveable(project.ref) { mutableStateOf(project.settings?.sourceLanguage?.ifBlank { "auto" } ?: "auto") }
    var instructions by rememberSaveable(project.ref) { mutableStateOf("") }
    var batch by rememberSaveable(project.ref) { mutableStateOf("10") }
    var deduplicate by rememberSaveable(project.ref) { mutableStateOf(false) }
    var retries by rememberSaveable(project.ref) { mutableStateOf("0") }
    var preceding by rememberSaveable(project.ref) { mutableStateOf("2") }
    var characters by rememberSaveable(project.ref) { mutableStateOf("16384") }
    var modeName by rememberSaveable(project.ref) { mutableStateOf(TranslationStartMode.NEW.name) }
    val mode = TranslationStartMode.valueOf(modeName)
    val enabledAssets = project.assets.filter { it.enabled }.map { it.id }.toSet()
    val pendingPage = state.items.any { mode.accepts(it.status) && it.hasTranslatableSource &&
        (project.assets.isEmpty() || it.assetId in enabledAssets) }
    val statistics = state.itemStatistics
    val pendingProject = statistics != null && (statistics.translatableUnprocessed > 0 ||
        (mode != TranslationStartMode.NEW && statistics.errors > 0) ||
        (mode == TranslationStartMode.RESET && statistics.processed > 0))
    var whole by rememberSaveable(project.ref) { mutableStateOf(!pendingPage && pendingProject) }
    val options = remember(target, source, instructions, batch, characters, mode, preceding, retries, deduplicate) {
        runCatching { RemoteTranslationOptions(target, source, instructions,
            requireNotNull(batch.toIntOrNull()), requireNotNull(characters.toIntOrNull()), mode, requireNotNull(preceding.toIntOrNull()), requireNotNull(retries.toIntOrNull()), deduplicate) }.getOrNull()
    }
    val activeProject = state.project
    val eligible = !state.busy && !state.runLoadIncomplete && !state.policyLoadIncomplete && state.policyDraft?.dirty != true && activeProject != null && activeProject.ref == project.ref &&
        activeProject.revision == project.revision && !state.proofreadingQuery.active && state.qualityView == null &&
        state.editor == null && !state.selectingItems && state.manualBatch == null &&
        (if (whole) pendingProject else pendingPage)
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null) { if (!state.busy) onDismiss() })
            Surface(Modifier.padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp)
                .testTag("native-remote-translation-dialog"), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_remote_translate), style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(model.model)
                        Text(model.endpoint, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.native_remote_warning), color = MaterialTheme.colorScheme.error)
                        TranslationStartMode.entries.forEach { candidate ->
                            val label = when (candidate) {
                                TranslationStartMode.NEW -> R.string.native_translation_mode_new
                                TranslationStartMode.CONTINUE -> R.string.native_translation_mode_continue
                                TranslationStartMode.RESET -> R.string.native_translation_mode_reset
                            }
                            TextButton(onClick = { modeName = candidate.name }, enabled = !state.busy,
                                modifier = Modifier.testTag("native-translation-mode-${candidate.name}")) {
                                RadioButton(mode == candidate, onClick = null)
                                Text(stringResource(label))
                            }
                        }
                        if (mode == TranslationStartMode.RESET) Text(
                            stringResource(R.string.native_translation_reset_warning), color = MaterialTheme.colorScheme.error)
                        OutlinedTextField(target, { if (it.length <= 80) target = it }, singleLine = true,
                            label = { Text(stringResource(R.string.native_remote_target)) }, modifier = Modifier.fillMaxWidth().testTag("native-remote-target"))
                        OutlinedTextField(source, { if (it.length <= 80) source = it }, singleLine = true,
                            label = { Text(stringResource(R.string.native_remote_source)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(instructions, { if (it.length <= 16_384) instructions = it }, minLines = 2, maxLines = 5,
                            label = { Text(stringResource(R.string.native_remote_instructions)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(batch, { if (it.length <= 2 && it.all { c -> c in '0'..'9' }) batch = it }, singleLine = true,
                            label = { Text(stringResource(R.string.native_remote_batch)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(characters, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) characters = it }, singleLine = true,
                            label = { Text(stringResource(R.string.native_remote_characters)) }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deduplicate, onCheckedChange = { if (!state.busy) deduplicate = it },
                                enabled = !state.busy, modifier = Modifier.testTag("native-translation-deduplicate"))
                            Text(stringResource(R.string.native_translation_deduplicate), modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(retries, { if (it.length <= 1 && it.all { c -> c in '0'..'9' }) retries = it },
                            singleLine = true, label = { Text(stringResource(R.string.native_translation_retries)) },
                            modifier = Modifier.fillMaxWidth().testTag("native-translation-retries"))
                        Text(stringResource(R.string.native_translation_retry_warning), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(preceding, { if (it.length <= 2 && it.all { c -> c in '0'..'9' }) preceding = it },
                            singleLine = true, label = { Text(stringResource(R.string.native_translation_preceding)) },
                            modifier = Modifier.fillMaxWidth().testTag("native-translation-preceding"))
                        TextButton(onClick = { whole = false }, enabled = pendingPage) {
                            RadioButton(!whole, onClick = null); Text(stringResource(R.string.native_simulation_scope_page))
                        }
                        TextButton(onClick = { whole = true }, enabled = pendingProject) {
                            RadioButton(whole, onClick = null); Text(stringResource(R.string.native_simulation_scope_project))
                        }
                        Text(stringResource(R.string.native_remote_policy), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(onClick = {
                            val fixed = options ?: return@TextButton
                            start(project.ref, project.revision, model.reference, fixed, whole)
                            onDismiss()
                        }, enabled = eligible && options != null, modifier = Modifier.testTag("native-remote-start")) {
                            Text(stringResource(R.string.native_remote_start))
                        }
                    }
                }
            }
        }
    }
}
