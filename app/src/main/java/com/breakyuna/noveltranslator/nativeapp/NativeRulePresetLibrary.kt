package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.*

internal class NativeRulePresetActions(
    val list: suspend () -> List<RulePresetSummary>,
    val load: suspend (String) -> RulePreset,
    val save: suspend (RulePreset, String?) -> Unit,
    val remove: suspend (String, String) -> Unit,
)

/** Library writes are separate from project writes. Apply only prepares a policy draft. */
@Composable
internal fun NativeRulePresetLibrary(ref: LocalProjectRef, baseline: ProjectTextPolicy,
    actions: NativeRulePresetActions, enabled: Boolean, close: () -> Unit,
    apply: (ObjectFingerprint, ProjectTextPolicy) -> Unit) {
    var entries by remember(ref) { mutableStateOf<List<RulePresetSummary>>(emptyList()) }
    var selected by remember(ref) { mutableStateOf<RulePreset?>(null) }
    var fingerprint by remember(ref) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(ref) { mutableStateOf("") }
    var description by rememberSaveable(ref) { mutableStateOf("") }
    var replace by rememberSaveable(ref) { mutableStateOf(false) }
    var includePrompt by rememberSaveable(ref) { mutableStateOf(true) }
    var preview by remember(ref) { mutableStateOf<RulePresetApplication?>(null) }
    var busy by remember(ref) { mutableStateOf(true) }
    var error by remember(ref) { mutableStateOf(false) }
    var saved by remember(ref) { mutableStateOf(false) }
    var overwrite by remember(ref) { mutableStateOf(false) }
    var deleting by remember(ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    fun perform(block: suspend () -> Unit) {
        if (busy || !enabled) return
        busy = true; error = false; saved = false
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    suspend fun choose(preset: RulePreset) {
        val digest = withContext(Dispatchers.Default) { RulePresetCodec.fingerprint(preset) }
        selected = preset; fingerprint = digest; name = preset.name; description = preset.description; preview = null
    }
    LaunchedEffect(ref) {
        busy = true
        try { entries = actions.list() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { busy = false }
    }
    val chosen = selected
    if ((overwrite || deleting) && chosen != null) NativeContentDialog(
        onDismissRequest = { if (!busy) { overwrite = false; deleting = false } },
        title = { Text(stringResource(if (deleting) R.string.native_preset_delete else R.string.native_preset_overwrite)) },
        text = { Text(stringResource(if (deleting) R.string.native_preset_delete_warning else R.string.native_preset_overwrite_warning, chosen.name)) },
        confirmButton = { TextButton(onClick = {
            val expected = fingerprint ?: return@TextButton
            val remove = deleting
            overwrite = false; deleting = false
            perform {
                if (remove) {
                    actions.remove(chosen.id, expected)
                    selected = null; fingerprint = null; preview = null
                } else {
                    val replacement = withContext(Dispatchers.Default) {
                        RulePreset(chosen.id, name.trim(), description, baseline.rules, baseline.prompt, baseline.promptEnabled)
                    }
                    actions.save(replacement, expected)
                    choose(replacement)
                }
                entries = actions.list(); saved = true
            }
        }, enabled = !busy && enabled && (deleting || name.isNotBlank())) { Text(stringResource(R.string.native_asset_confirm)) } },
        dismissButton = { TextButton(onClick = { overwrite = false; deleting = false }, enabled = !busy) {
            Text(stringResource(R.string.native_cancel)) } })
    NativePolicyForm({ if (!busy) close() }, stringResource(R.string.native_preset_library), "native-preset-library",
        enabled && !busy && preview != null, { preview?.let { apply(baseline.fingerprint(), it.policy) } }) {
        Text(stringResource(R.string.native_preset_library_notice))
        OutlinedTextField(name, { if (it.length <= RulePreset.MAX_NAME_CHARS) name = it }, singleLine = true,
            enabled = enabled && !busy, label = { Text(stringResource(R.string.native_preset_name)) }, modifier = Modifier.fillMaxWidth().testTag("native-preset-name"))
        OutlinedTextField(description, { if (it.length <= RulePreset.MAX_DESCRIPTION_CHARS) description = it },
            enabled = enabled && !busy, maxLines = 3, label = { Text(stringResource(R.string.native_preset_description)) }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { perform {
                val preset = withContext(Dispatchers.Default) {
                    RulePreset(UUID.randomUUID().toString(), name.trim(), description, baseline.rules, baseline.prompt, baseline.promptEnabled)
                }
                actions.save(preset, null); entries = actions.list(); choose(preset); saved = true
            } }, enabled = enabled && !busy && name.isNotBlank(), modifier = Modifier.testTag("native-preset-save-new")) {
                Text(stringResource(R.string.native_preset_save_new))
            }
            TextButton(onClick = { overwrite = true }, enabled = enabled && !busy && chosen != null && name.isNotBlank()) {
                Text(stringResource(R.string.native_preset_overwrite))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries, key = { it.id }) { entry ->
                TextButton(onClick = { perform { choose(actions.load(entry.id)) } }, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-preset-${entry.id}")) {
                    RadioButton(selected?.id == entry.id, onClick = null)
                    Text(entry.name + " (" + entry.rules + ")", modifier = Modifier.weight(1f))
                }
            }
        }
        if (chosen != null) {
            Row {
                TextButton(onClick = { perform {
                    val text = withContext(Dispatchers.Default) { RulePresetCodec.encode(chosen) }
                    clipboard.setText(AnnotatedString(text))
                } }, enabled = enabled && !busy) { Text(stringResource(R.string.native_preset_copy)) }
                TextButton(onClick = { deleting = true }, enabled = enabled && !busy) { Text(stringResource(R.string.native_preset_delete)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(replace, { replace = it; preview = null }, enabled = enabled && !busy)
                Text(stringResource(R.string.native_policy_preset_replace), modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includePrompt, { includePrompt = it; preview = null }, enabled = enabled && !busy)
                Text(stringResource(R.string.native_policy_include_prompt), modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { perform {
                preview = withContext(Dispatchers.Default) {
                    chosen.applyTo(baseline, if (replace) RulePresetApplyMode.REPLACE else RulePresetApplyMode.MERGE, includePrompt)
                }
            } }, enabled = enabled && !busy, modifier = Modifier.testTag("native-preset-preview")) {
                Text(stringResource(R.string.native_policy_preview))
            }
        }
        preview?.diff?.let { Text(stringResource(R.string.native_policy_preset_diff,
            it.addedRuleIds.size, it.updatedRuleIds.size, it.unchangedRuleIds.size, it.removedRuleIds.size)) }
        if (error) Text(stringResource(R.string.native_preset_operation_failed), color = MaterialTheme.colorScheme.error)
        if (saved) Text(stringResource(R.string.native_preset_saved))
        TextButton(onClick = { perform { entries = actions.list() } }, enabled = enabled && !busy) { Text(stringResource(R.string.native_policy_reload)) }
    }
}
