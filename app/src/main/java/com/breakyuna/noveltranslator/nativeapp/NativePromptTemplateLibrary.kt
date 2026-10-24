package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var selected by remember(ref) { mutableStateOf<PromptTemplate?>(null) }
    var name by rememberSaveable(ref) { mutableStateOf("") }
    var description by rememberSaveable(ref) { mutableStateOf("") }
    var enabledFlag by rememberSaveable(ref) { mutableStateOf(true) }
    var orderText by rememberSaveable(ref) { mutableStateOf("0") }
    var languagesText by rememberSaveable(ref) { mutableStateOf("") }
    var busy by remember(ref) { mutableStateOf(true) }
    var error by remember(ref) { mutableStateOf(false) }
    var invalid by remember(ref) { mutableStateOf(false) }
    var saved by remember(ref) { mutableStateOf(false) }
    var overwrite by remember(ref) { mutableStateOf(false) }
    var deleting by remember(ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

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
        name = template.name
        description = template.description
        enabledFlag = template.enabled
        orderText = template.order.toString()
        languagesText = template.languages.joinToString(", ") { it.value }
    }
    suspend fun persist(templates: PromptTemplateSet, selectId: String?) {
        val current = snapshot ?: throw IllegalStateException("Library not loaded")
        val result = actions.replace(current.fingerprint, templates)
        snapshot = result
        selected = selectId?.let { id -> result.templates.templates.firstOrNull { it.id == id } }
        saved = true
    }
    fun edited(id: String): PromptTemplate {
        val order = orderText.trim().toIntOrNull()
        if (name.isBlank() || name.length > PromptTemplate.MAX_NAME_CHARS ||
            description.length > PromptTemplate.MAX_DESCRIPTION_CHARS ||
            order == null || order !in 0..PromptTemplate.MAX_ORDER) throw IllegalArgumentException("Template metadata")
        val languages = languagesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (languages.size > PromptTemplate.MAX_LANGUAGE_TAGS) throw IllegalArgumentException("Template languages")
        val tags = languages.map { PromptLanguageTag.of(it) }
        return PromptTemplate(id, name.trim(), description, enabledFlag, tags, order, body)
    }
    LaunchedEffect(ref) {
        busy = true
        try { snapshot = actions.read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { busy = false }
    }
    if ((overwrite || deleting) && selected != null) AlertDialog(
        onDismissRequest = { if (!busy) { overwrite = false; deleting = false } },
        title = { Text(stringResource(if (deleting) R.string.native_prompt_template_delete else R.string.native_prompt_template_overwrite)) },
        text = { Text(stringResource(if (deleting) R.string.native_prompt_template_delete_warning else R.string.native_prompt_template_overwrite_warning, selected!!.name)) },
        confirmButton = { TextButton(onClick = {
            overwrite = false; deleting = false
            val current = snapshot ?: return@TextButton
            val chosen = selected ?: return@TextButton
            val remove = deleting
            perform {
                if (remove) {
                    persist(PromptTemplateSet(current.templates.templates.filter { it.id != chosen.id }), null)
                } else {
                    val replacement = edited(chosen.id)
                    persist(PromptTemplateSet(current.templates.templates.map {
                        if (it.id == chosen.id) replacement else it
                    }), replacement.id)
                }
            }
        }, enabled = !busy && enabled) { Text(stringResource(R.string.native_asset_confirm)) } },
        dismissButton = { TextButton(onClick = { overwrite = false; deleting = false }, enabled = !busy) {
            Text(stringResource(R.string.native_cancel)) } })
    NativePolicyForm({ if (!busy) close() }, stringResource(R.string.native_prompt_template_library),
        "native-prompt-template-library", enabled && !busy && selected != null,
        { selected?.let(use) }, applyLabel = stringResource(R.string.native_prompt_template_use)) {
        Text(stringResource(R.string.native_prompt_template_library_notice))
        TextFieldValueForm(name, { if (it.length <= PromptTemplate.MAX_NAME_CHARS) { name = it; invalid = false } },
            stringResource(R.string.native_prompt_template_name), enabled && !busy, "native-prompt-template-name")
        OutlinedTextField(description, {
            if (it.length <= PromptTemplate.MAX_DESCRIPTION_CHARS) { description = it; invalid = false }
        }, enabled = enabled && !busy, maxLines = 3,
            label = { Text(stringResource(R.string.native_prompt_template_description)) }, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(enabledFlag, { enabledFlag = it; invalid = false }, enabled = enabled && !busy)
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
                TextButton(onClick = { choose(entry); invalid = false }, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-template-${entry.id}")) {
                    RadioButton(selected?.id == entry.id, onClick = null)
                    Text(entry.name + " · " + entry.order + (if (entry.enabled) "" else " · " + stringResource(R.string.native_prompt_template_disabled)),
                        modifier = Modifier.weight(1f))
                }
            }
        }
        if (snapshot?.templates?.templates.isNullOrEmpty()) Text(stringResource(R.string.native_prompt_template_empty))
        Row {
            TextButton(onClick = { perform {
                val current = snapshot ?: return@perform
                require(current.templates.templates.size < PromptTemplateSet.MAX_TEMPLATES)
                val template = edited(UUID.randomUUID().toString())
                persist(PromptTemplateSet(current.templates.templates + template), template.id)
            } }, enabled = enabled && !busy && name.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.testTag("native-template-save-new")) {
                Text(stringResource(R.string.native_prompt_template_save_new))
            }
            TextButton(onClick = { overwrite = true }, enabled = enabled && !busy && selected != null && name.isNotBlank()) {
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
            TextButton(onClick = { deleting = true }, enabled = enabled && !busy) {
                Text(stringResource(R.string.native_prompt_template_delete))
            }
        }
        if (invalid) Text(stringResource(R.string.native_prompt_template_invalid), color = MaterialTheme.colorScheme.error)
        if (error) Text(stringResource(R.string.native_prompt_template_failed), color = MaterialTheme.colorScheme.error)
        if (saved) Text(stringResource(R.string.native_prompt_template_saved))
        TextButton(onClick = { perform { snapshot = actions.read() } }, enabled = enabled && !busy) {
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
