package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.ProjectPromptTemplateService
import kotlinx.coroutines.*

@Composable
internal fun NativePromptTemplateDialog(project: TextProjectSummary, baseline: ProjectTextPolicy,
    enabled: Boolean, close: () -> Unit, library: NativePromptTemplateActions? = null,
    apply: (ObjectFingerprint, ProjectTextPolicy) -> Unit) {
    var body by rememberSaveable(project.ref) { mutableStateOf(baseline.prompt) }
    var libraryOpen by rememberSaveable(project.ref) { mutableStateOf(false) }
    var template by remember(project.ref) { mutableStateOf<PromptTemplate?>(null) }
    val variables = rememberSaveable(project.ref, saver = mapSaver<SnapshotStateMap<String, String>>(
        save = { it.toMap() },
        restore = { values -> mutableStateMapOf<String, String>().apply {
            if (values.size <= PromptTemplate.MAX_VARIABLES && values.values.all { it is String && it.length <= PromptTemplate.MAX_VARIABLE_CHARS } &&
                values.values.sumOf { (it as String).length } <= PromptTemplate.MAX_TOTAL_VARIABLE_CHARS)
                values.forEach { (key, value) -> put(key, value as String) }
        } },
    )) { mutableStateMapOf<String, String>() }
    var preview by remember(project.ref) { mutableStateOf<ProjectTextPolicy?>(null) }
    var reading by remember(project.ref) { mutableStateOf(false) }
    var invalid by remember(project.ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (libraryOpen && library != null) NativePromptTemplateLibrary(project.ref, body, library,
        enabled && !reading, { libraryOpen = false }) { chosen ->
        body = chosen.body
        template = null
        preview = null
        invalid = false
        libraryOpen = false
    }
    NativePolicyForm({ if (!reading) close() }, stringResource(R.string.native_prompt_template), "native-prompt-template-dialog",
        enabled && !reading && preview != null, { preview?.let { apply(baseline.fingerprint(), it) } }) {
        Text(stringResource(R.string.native_prompt_template_notice))
        if (library != null) TextButton(onClick = { libraryOpen = true }, enabled = enabled && !reading,
            modifier = Modifier.testTag("native-prompt-template-open-library")) {
            Text(stringResource(R.string.native_prompt_template_library))
        }
        OutlinedTextField(body, { if (it.length <= PromptTemplate.MAX_BODY_CHARS) {
            body = it; template = null; preview = null; invalid = false
        } }, enabled = enabled && !reading, label = { Text(stringResource(R.string.native_prompt_template_body)) },
            minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth().testTag("native-prompt-template-body"))
        TextButton(onClick = {
            reading = true; invalid = false; preview = null
            scope.launch {
                try {
                    val parsed = withContext(Dispatchers.Default) {
                        ensureActive()
                        PromptTemplate("project-prompt", "Project prompt", body = body)
                    }
                    val previous = variables.toMap()
                    variables.clear()
                    parsed.placeholders.forEach { name -> variables[name] = previous[name] ?: when (name) {
                        "project_name" -> project.name
                        "source_language" -> project.settings?.sourceLanguage ?: "auto"
                        "target_language" -> project.settings?.targetLanguage.orEmpty()
                        else -> ""
                    } }
                    template = parsed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { template = null; invalid = true }
                finally { reading = false }
            }
        }, enabled = enabled && !reading, modifier = Modifier.testTag("native-prompt-template-parse")) {
            Text(stringResource(R.string.native_prompt_template_parse))
        }
        template?.let { parsed ->
            if (parsed.placeholders.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().height(200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(parsed.placeholders, key = { it }) { name ->
                    OutlinedTextField(variables[name].orEmpty(), { value -> if (value.length <= PromptTemplate.MAX_VARIABLE_CHARS &&
                        variables.values.sumOf { it.length } - variables[name].orEmpty().length + value.length <= PromptTemplate.MAX_TOTAL_VARIABLE_CHARS) {
                        variables[name] = value; preview = null; invalid = false
                    } }, enabled = enabled && !reading, label = { Text(name) }, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("native-prompt-variable-$name"))
                }
            }
            TextButton(onClick = {
                val values = variables.toMap()
                reading = true; invalid = false; preview = null
                scope.launch {
                    try {
                        preview = withContext(Dispatchers.Default) {
                            ProjectPromptTemplateService.render(baseline, parsed, values) { ensureActive() }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { invalid = true }
                    finally { reading = false }
                }
            }, enabled = enabled && !reading, modifier = Modifier.testTag("native-prompt-template-preview")) {
                Text(stringResource(R.string.native_policy_preview))
            }
        }
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        preview?.let { rendered ->
            OutlinedTextField(rendered.prompt, {}, readOnly = true,
                label = { Text(stringResource(R.string.native_prompt_template_rendered)) }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("native-prompt-template-rendered"))
        }
    }
}
