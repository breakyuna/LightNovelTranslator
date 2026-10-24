package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.*

internal class NativePromptTemplateActions(
    val read: suspend () -> PromptTemplateLibrarySnapshot,
    val replace: suspend (String, PromptTemplateSet) -> PromptTemplateLibrarySnapshot,
)

/**
 * Library writes never touch a project or a model. "Use" only hands the selected
 * template body back to the render editor, which still requires explicit parse,
 * preview and project save.
 */
@Composable
internal fun NativePromptTemplateLibrary(ref: LocalProjectRef, body: String,
    actions: NativePromptTemplateActions, enabled: Boolean, close: () -> Unit,
    use: (PromptTemplate) -> Unit) {
    var snapshot by remember(ref) { mutableStateOf<PromptTemplateLibrarySnapshot?>(null) }
    val templateSaver = remember { Saver<PromptTemplate?, String>(
        save = { template -> template?.let { PromptTemplateCodec.encode(PromptTemplateSet(listOf(it))) } },
        restore = { text -> runCatching { PromptTemplateCodec.decode(text).templates.single() }.getOrNull() },
    ) }
    var selected by rememberSaveable(ref, stateSaver = templateSaver) { mutableStateOf<PromptTemplate?>(null) }
    var editingFingerprint by rememberSaveable(ref) { mutableStateOf<String?>(null) }
    var selectionConflict by remember(ref) { mutableStateOf(false) }
    var name by rememberSaveable(ref) { mutableStateOf("") }
    var description by rememberSaveable(ref) { mutableStateOf("") }
    var enabledFlag by rememberSaveable(ref) { mutableStateOf(true) }
    var orderText by rememberSaveable(ref) { mutableStateOf("0") }
    var languagesText by rememberSaveable(ref) { mutableStateOf("") }
    var selectedBody by rememberSaveable(ref) { mutableStateOf("") }
    var pendingSelection by remember(ref) { mutableStateOf<PromptTemplate?>(null) }
    var discardToClose by remember(ref) { mutableStateOf(false) }
    var busy by remember(ref) { mutableStateOf(true) }
    var error by remember(ref) { mutableStateOf(false) }
    var invalid by remember(ref) { mutableStateOf(false) }
    var saved by remember(ref) { mutableStateOf(false) }
    var overwrite by remember(ref) { mutableStateOf(false) }
    var deleting by remember(ref) { mutableStateOf(false) }
    var importing by remember(ref) { mutableStateOf(false) }
    var pendingExport by remember(ref) { mutableStateOf<PromptTemplateSet?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val frozen = pendingExport
        pendingExport = null
        if (uri != null && frozen == null) error = true
        if (uri != null && frozen != null) {
            busy = true; error = false; saved = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = TextProjectFingerprint.utf8(PromptTemplateCodec.encode(frozen))
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                            ?: throw java.io.IOException("Template destination unavailable")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }
    }

    fun perform(block: suspend () -> Unit) {
        if (busy || !enabled) return
        busy = true; error = false; saved = false; invalid = false
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { invalid = true }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    fun choose(template: PromptTemplate) {
        selected = template
        editingFingerprint = snapshot?.fingerprint
        selectionConflict = false
        name = template.name
        description = template.description
        enabledFlag = template.enabled
        orderText = template.order.toString()
        languagesText = template.languages.joinToString(", ") { it.value }
        selectedBody = template.body
    }
    suspend fun persist(templates: PromptTemplateSet, selectId: String?) {
        val current = snapshot ?: throw IllegalStateException("Library not loaded")
        require(!selectionConflict && (selected == null || editingFingerprint == current.fingerprint)) { "Template selection changed" }
        val result = actions.replace(current.fingerprint, templates)
        snapshot = result
        editingFingerprint = result.fingerprint
        val next = selectId?.let { id -> result.templates.templates.firstOrNull { it.id == id } }
        if (next != null && next.id != selected?.id) choose(next) else selected = next
        if (next == null) selectedBody = ""
        saved = true
    }
    fun edited(id: String, templateBody: String = body): PromptTemplate {
        val order = orderText.trim().toIntOrNull()
        if (name.isBlank() || name.length > PromptTemplate.MAX_NAME_CHARS ||
            description.length > PromptTemplate.MAX_DESCRIPTION_CHARS ||
            order == null || order !in 0..PromptTemplate.MAX_ORDER) throw IllegalArgumentException("Template metadata")
        val languages = languagesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (languages.size > PromptTemplate.MAX_LANGUAGE_TAGS) throw IllegalArgumentException("Template languages")
        val tags = languages.map { PromptLanguageTag.of(it) }
        return PromptTemplate(id, name.trim(), description, enabledFlag, tags, order, templateBody)
    }
    val draftDirty = selected?.let {
        name != it.name || description != it.description || enabledFlag != it.enabled ||
            orderText != it.order.toString() || languagesText != it.languages.joinToString(", ") { tag -> tag.value } || selectedBody != it.body
    } == true
    fun move(offset: Int) {
        val current = snapshot ?: return
        val chosen = selected ?: return
        val ids = current.templates.ordered().map { it.id }.toMutableList()
        val index = ids.indexOf(chosen.id)
        if (index < 0 || index + offset !in ids.indices) return
        val neighbor = ids[index + offset]; ids[index + offset] = chosen.id; ids[index] = neighbor
        perform {
            persist(withContext(Dispatchers.Default) { current.templates.reordered(ids) }, chosen.id)
            orderText = requireNotNull(selected).order.toString()
        }
    }
    LaunchedEffect(ref) {
        busy = true
        try {
            val loaded = actions.read()
            snapshot = loaded
            selectionConflict = selected != null && editingFingerprint != loaded.fingerprint
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { busy = false }
    }
    if (importing && snapshot != null) NativePromptTemplateImportDialog(ref, requireNotNull(snapshot),
        enabled && !busy, { importing = false }) { preview ->
        perform {
            require(preview.clean)
            snapshot = actions.replace(preview.expected, preview.templates)
            editingFingerprint = snapshot?.fingerprint; selectionConflict = false
            selected = null; selectedBody = ""; saved = true; importing = false
        }
    }
    if ((overwrite || deleting) && selected != null) AlertDialog(
        onDismissRequest = { if (!busy) { overwrite = false; deleting = false } },
        title = { Text(stringResource(if (deleting) R.string.native_prompt_template_delete else R.string.native_prompt_template_overwrite)) },
        text = { Text(stringResource(if (deleting) R.string.native_prompt_template_delete_warning else R.string.native_prompt_template_overwrite_warning, selected!!.name)) },
        confirmButton = { TextButton(onClick = {
            val remove = deleting
            overwrite = false; deleting = false
            val current = snapshot ?: return@TextButton
            val chosen = selected ?: return@TextButton
            perform {
                if (remove) {
                    persist(PromptTemplateSet(current.templates.templates.filter { it.id != chosen.id }), null)
                } else {
                    val replacement = edited(chosen.id)
                    persist(PromptTemplateSet(current.templates.templates.map {
                        if (it.id == chosen.id) replacement else it
                    }), replacement.id)
                    choose(replacement)
                }
            }
        }, enabled = !busy && enabled && !selectionConflict && snapshot != null, modifier = Modifier.testTag("native-template-confirm")) { Text(stringResource(R.string.native_asset_confirm)) } },
        dismissButton = { TextButton(onClick = { overwrite = false; deleting = false }, enabled = !busy) {
            Text(stringResource(R.string.native_cancel)) } })
    if (pendingSelection != null || discardToClose) AlertDialog(
        onDismissRequest = { pendingSelection = null; discardToClose = false },
        title = { Text(stringResource(R.string.native_template_unsaved)) },
        text = { Text(stringResource(R.string.native_policy_discard_body)) },
        confirmButton = { TextButton(onClick = {
            val next = pendingSelection
            val leave = discardToClose
            pendingSelection = null; discardToClose = false
            if (leave) close() else next?.let(::choose)
        }, modifier = Modifier.testTag("native-template-discard")) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { pendingSelection = null; discardToClose = false }) {
            Text(stringResource(R.string.native_cancel)) } })
    NativePolicyForm({ if (!busy) { if (draftDirty) discardToClose = true else close() } }, stringResource(R.string.native_prompt_template_library),
        "native-prompt-template-library", enabled && !busy && selected != null && !selectionConflict && snapshot != null,
        { selected?.let {
            try { use(edited(it.id, selectedBody)) }
            catch (_: IllegalArgumentException) { invalid = true }
        } }, applyLabel = stringResource(R.string.native_prompt_template_use)) {
        Text(stringResource(R.string.native_prompt_template_library_notice))
        if (selectionConflict) Text(stringResource(R.string.native_template_selection_conflict), color = MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick = { importing = true }, enabled = enabled && !busy && snapshot != null && !draftDirty,
                modifier = Modifier.testTag("native-template-import")) { Text(stringResource(R.string.native_template_import)) }
            TextButton(onClick = {
                pendingExport = snapshot?.templates
                try { saveFile.launch("prompt-templates.json") }
                catch (_: Exception) { pendingExport = null; error = true }
            }, enabled = enabled && !busy && snapshot != null && pendingExport == null,
                modifier = Modifier.testTag("native-template-export")) { Text(stringResource(R.string.native_template_export)) }
        }
        TextFieldValueForm(name, { if (it.length <= PromptTemplate.MAX_NAME_CHARS) { name = it; invalid = false } },
            stringResource(R.string.native_prompt_template_name), enabled && !busy, "native-prompt-template-name")
        OutlinedTextField(description, {
            if (it.length <= PromptTemplate.MAX_DESCRIPTION_CHARS) { description = it; invalid = false }
        }, enabled = enabled && !busy, maxLines = 3,
            label = { Text(stringResource(R.string.native_prompt_template_description)) }, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(enabledFlag, { enabledFlag = it; invalid = false }, enabled = enabled && !busy,
                modifier = Modifier.testTag("native-template-enabled"))
            Text(stringResource(R.string.native_prompt_template_enabled), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(orderText, { if (it.length <= 7) { orderText = it; invalid = false } },
                enabled = enabled && !busy, label = { Text(stringResource(R.string.native_prompt_template_order)) },
                singleLine = true, modifier = Modifier.width(120.dp))
            OutlinedTextField(languagesText, {
                if (it.length <= 400) { languagesText = it; invalid = false }
            }, enabled = enabled && !busy, label = { Text(stringResource(R.string.native_prompt_template_languages)) },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.native_prompt_template_languages_hint), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().height(160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(snapshot?.templates?.ordered().orEmpty(), key = { it.id }) { entry ->
                TextButton(onClick = {
                    if (draftDirty && (selected?.id != entry.id || selectionConflict)) pendingSelection = entry
                    else if (selected?.id != entry.id || selectionConflict) { choose(entry); invalid = false }
                }, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-template-${entry.id}")) {
                    RadioButton(selected?.id == entry.id, onClick = null)
                    Text(entry.name + " · " + entry.order + (if (entry.enabled) "" else " · " + stringResource(R.string.native_prompt_template_disabled)),
                        modifier = Modifier.weight(1f))
                }
            }
        }
        if (snapshot?.templates?.templates.isNullOrEmpty()) Text(stringResource(R.string.native_prompt_template_empty))
        selected?.let { chosen ->
            if (draftDirty) Text(stringResource(R.string.native_template_unsaved), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(selectedBody, {
                if (it.length <= PromptTemplate.MAX_BODY_CHARS) { selectedBody = it; invalid = false }
            }, enabled = enabled && !busy, minLines = 3, maxLines = 8,
                label = { Text(stringResource(R.string.native_template_body)) },
                modifier = Modifier.fillMaxWidth().testTag("native-template-body"))
            TextButton(onClick = { perform {
                val current = snapshot ?: return@perform
                val replacement = withContext(Dispatchers.Default) { edited(chosen.id, selectedBody) }
                persist(PromptTemplateSet(current.templates.templates.map { if (it.id == chosen.id) replacement else it }), chosen.id)
                choose(replacement)
            } }, enabled = enabled && !busy && !selectionConflict && snapshot != null, modifier = Modifier.testTag("native-template-save-edits")) {
                Text(stringResource(R.string.native_template_save_edits))
            }
            val ordered = snapshot?.templates?.ordered().orEmpty()
            val position = ordered.indexOfFirst { it.id == chosen.id }
            Row {
                TextButton(onClick = { move(-1) }, enabled = enabled && !busy && !selectionConflict && position > 0,
                    modifier = Modifier.testTag("native-template-move-up")) { Text(stringResource(R.string.native_policy_up)) }
                TextButton(onClick = { move(1) }, enabled = enabled && !busy && !selectionConflict && position >= 0 && position < ordered.lastIndex,
                    modifier = Modifier.testTag("native-template-move-down")) { Text(stringResource(R.string.native_policy_down)) }
            }
        }
        Row {
            TextButton(onClick = { perform {
                val current = snapshot ?: return@perform
                require(current.templates.templates.size < PromptTemplateSet.MAX_TEMPLATES)
                val template = edited(UUID.randomUUID().toString())
                persist(PromptTemplateSet(current.templates.templates + template), template.id)
            } }, enabled = enabled && !busy && !selectionConflict && name.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.testTag("native-template-save-new")) {
                Text(stringResource(R.string.native_prompt_template_save_new))
            }
            TextButton(onClick = { overwrite = true }, enabled = enabled && !busy && !selectionConflict && selected != null && name.isNotBlank()) {
                Text(stringResource(R.string.native_prompt_template_overwrite))
            }
        }
        if (selected != null) Row {
            TextButton(onClick = { perform {
                val text = withContext(Dispatchers.Default) {
                    PromptTemplateCodec.encode(requireNotNull(snapshot).templates)
                }
                clipboard.setText(AnnotatedString(text))
            } }, enabled = enabled && !busy) { Text(stringResource(R.string.native_prompt_template_copy)) }
            TextButton(onClick = { deleting = true }, enabled = enabled && !busy && !selectionConflict,
                modifier = Modifier.testTag("native-template-delete")) {
                Text(stringResource(R.string.native_prompt_template_delete))
            }
        }
        if (invalid) Text(stringResource(R.string.native_prompt_template_invalid), color = MaterialTheme.colorScheme.error)
        if (error) Text(stringResource(R.string.native_prompt_template_failed), color = MaterialTheme.colorScheme.error)
        if (saved && !draftDirty) Text(stringResource(R.string.native_prompt_template_saved))
        TextButton(onClick = { perform {
            snapshot = actions.read(); selected = null; selectedBody = ""; editingFingerprint = null; selectionConflict = false
        } }, enabled = enabled && !busy && !draftDirty) {
            Text(stringResource(R.string.native_policy_reload))
        }
    }
}

@Composable
private fun TextFieldValueForm(value: String, change: (String) -> Unit, label: String,
    enabled: Boolean, tag: String) {
    OutlinedTextField(value, change, singleLine = true, enabled = enabled, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag))
}
