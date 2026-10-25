package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

/**
 * U10 model directory. Configuration and credentials still go through the
 * profile port; this page never starts a probe or a paid request by itself.
 */
@Composable
internal fun NativeModelsPage(
    state: NativeProjectUiState,
    back: () -> Unit,
    refresh: () -> Unit,
    create: (NewModelProfile) -> Unit,
    update: (String, ModelProfileEdit, String, String, NativeModelProtocol, List<String>?) -> Unit,
    copy: (String, String) -> Unit,
    delete: (String) -> Unit,
    move: (String, Int) -> Unit,
    select: (String, ModelProfilePurpose) -> Unit,
    loadKeys: (String) -> Unit,
    setKeyEnabled: (String, String, Boolean) -> Unit,
) {
    var editing by remember { mutableStateOf<NativeModelProfile?>(null) }
    LaunchedEffect(Unit) { if (state.modelProfiles.isEmpty() && !state.modelProfilesLoading) refresh() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<NativeModelProfile?>(null) }
    if (creating || editing != null) {
        NativeModelProfileEditor(
            profile = editing,
            busy = state.busy,
            keys = state.modelProfileKeys,
            loadKeys = loadKeys,
            setKeyEnabled = setKeyEnabled,
            dismiss = { if (!state.busy) { creating = false; editing = null } },
            create = { create(it); creating = false },
            update = { id, edit, endpoint, model, protocol, keys ->
                update(id, edit, endpoint, model, protocol, keys); editing = null
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleting = null },
            title = { Text(stringResource(R.string.native_model_profile_delete)) },
            text = { Text(stringResource(R.string.native_model_profile_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    delete(target.id); deleting = null
                }) { Text(stringResource(R.string.native_model_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }
    Column(Modifier.fillMaxSize().testTag("native-models-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, modifier = Modifier.testTag("native-models-back")) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.native_settings_back))
            }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !state.busy, onClick = { creating = true },
                modifier = Modifier.testTag("native-models-add")) {
                Text(stringResource(R.string.native_model_profile_add))
            }
            TextButton(enabled = !state.busy, onClick = refresh) {
                Text(stringResource(R.string.native_refresh))
            }
        }
        if (state.modelProfilesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.modelProfilesError?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        val ungrouped = stringResource(R.string.native_model_profile_ungrouped)
        if (state.modelProfiles.isEmpty() && !state.modelProfilesLoading) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.native_model_profiles_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.native_model_profiles_empty_hint), style = MaterialTheme.typography.bodySmall)
                Button(enabled = !state.busy, onClick = { creating = true }) {
                    Text(stringResource(R.string.native_model_profile_add))
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("native-models-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var lastGroup: String? = null
            state.modelProfiles.forEach { profile ->
                val group = profile.group.ifBlank { ungrouped }
                if (group != lastGroup) {
                    item(key = "group:$group") {
                        Text(group, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    lastGroup = group
                }
                item(key = profile.id) {
                    val copyName = stringResource(R.string.native_model_profile_copy_name, profile.name)
                    val translationLabel = stringResource(R.string.native_model_profile_purpose_translation)
                    val agentLabel = stringResource(R.string.native_model_profile_purpose_agent)
                    val selectedLabels = listOfNotNull(
                        translationLabel.takeIf { ModelProfilePurpose.TRANSLATION in profile.selectedFor },
                        agentLabel.takeIf { ModelProfilePurpose.AGENT in profile.selectedFor },
                    ).joinToString(", ")
                    ModelProfileCard(
                        profile = profile,
                        busy = state.busy,
                        selectedLabels = selectedLabels,
                        canMoveUp = state.modelProfiles.firstOrNull()?.id != profile.id,
                        canMoveDown = state.modelProfiles.lastOrNull()?.id != profile.id,
                        edit = { editing = profile },
                        copy = { copy(profile.id, copyName) },
                        delete = { deleting = profile },
                        move = { offset -> move(profile.id, offset) },
                        select = { purpose -> select(profile.id, purpose) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProfileCard(
    profile: NativeModelProfile,
    busy: Boolean,
    selectedLabels: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    edit: () -> Unit,
    copy: () -> Unit,
    delete: () -> Unit,
    move: (Int) -> Unit,
    select: (ModelProfilePurpose) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().testTag("native-model-card-${profile.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text("${profile.settings.model} · ${protocolLabel(profile.settings.protocol)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (profile.enabled) AssistChip(onClick = {}, label = { Text(stringResource(R.string.native_model_profile_enabled)) })
                else AssistChip(onClick = {}, label = { Text(stringResource(R.string.native_model_profile_disabled)) })
            }
            Text(stringResource(R.string.native_model_profile_purpose_value,
                profilePurposeLabel(profile.purpose)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_model_profile_keys_value, profile.settings.keyCount),
                style = MaterialTheme.typography.bodySmall)
            if (selectedLabels.isNotEmpty()) {
                Text(stringResource(R.string.native_model_profile_selected_value, selectedLabels),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = edit,
                    modifier = Modifier.testTag("native-model-edit-${profile.id}")) {
                    Text(stringResource(R.string.native_model_action_edit))
                }
                if (profile.serves(ModelProfilePurpose.AGENT)) {
                    TextButton(enabled = !busy && profile.enabled, onClick = { select(ModelProfilePurpose.AGENT) }) {
                        Text(stringResource(R.string.native_model_profile_use_agent))
                    }
                }
                if (profile.serves(ModelProfilePurpose.TRANSLATION)) {
                    TextButton(enabled = !busy && profile.enabled, onClick = { select(ModelProfilePurpose.TRANSLATION) }) {
                        Text(stringResource(R.string.native_model_profile_use_translation))
                    }
                }
                TextButton(enabled = !busy, onClick = copy) { Text(stringResource(R.string.native_copy)) }
                TextButton(enabled = !busy && canMoveUp, onClick = { move(-1) }) { Text("↑") }
                TextButton(enabled = !busy && canMoveDown, onClick = { move(1) }) { Text("↓") }
                TextButton(enabled = !busy, onClick = delete) {
                    Text(stringResource(R.string.native_model_action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NativeModelProfileEditor(
    profile: NativeModelProfile?,
    busy: Boolean,
    keys: List<ModelProfileKeyState>,
    loadKeys: (String) -> Unit,
    setKeyEnabled: (String, String, Boolean) -> Unit,
    dismiss: () -> Unit,
    create: (NewModelProfile) -> Unit,
    update: (String, ModelProfileEdit, String, String, NativeModelProtocol, List<String>?) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var group by remember(profile?.id) { mutableStateOf(profile?.group.orEmpty()) }
    var purposeName by remember(profile?.id) {
        mutableStateOf((profile?.purpose ?: ModelProfilePurpose.BOTH).name)
    }
    var enabled by remember(profile?.id) { mutableStateOf(profile?.enabled ?: true) }
    var endpoint by remember(profile?.id) {
        mutableStateOf(profile?.settings?.let { runCatching { NativeModelEndpoint.chat(it.endpoint) }.getOrDefault(it.endpoint) }.orEmpty())
    }
    var model by remember(profile?.id) { mutableStateOf(profile?.settings?.model.orEmpty()) }
    var protocolName by remember(profile?.id) {
        mutableStateOf((profile?.settings?.protocol ?: NativeModelProtocol.OPENAI_CHAT_COMPLETIONS).name)
    }
    var secret by remember(profile?.id) { mutableStateOf("") }
    var concurrency by remember(profile?.id) { mutableStateOf((profile?.limits?.concurrency ?: 4).toString()) }
    var rpm by remember(profile?.id) { mutableStateOf((profile?.limits?.requestsPerMinute ?: 0).toString()) }
    var tools by remember(profile?.id) { mutableStateOf(profile?.capabilities?.tools) }
    var vision by remember(profile?.id) { mutableStateOf(profile?.capabilities?.vision) }
    var reasoning by remember(profile?.id) { mutableStateOf(profile?.capabilities?.reasoning) }
    var context by remember(profile?.id) { mutableStateOf(profile?.capabilities?.contextWindowTokens?.toString().orEmpty()) }
    var output by remember(profile?.id) { mutableStateOf(profile?.capabilities?.maxOutputTokens?.toString().orEmpty()) }
    var temperature by remember(profile?.id) { mutableStateOf(profile?.generation?.temperature?.toString().orEmpty()) }
    var topP by remember(profile?.id) { mutableStateOf(profile?.generation?.topP?.toString().orEmpty()) }
    var effort by remember(profile?.id) { mutableStateOf(profile?.generation?.reasoningEffort.orEmpty()) }
    var serviceTier by remember(profile?.id) { mutableStateOf(profile?.generation?.serviceTier.orEmpty()) }
    var headers by remember(profile?.id) {
        mutableStateOf(profile?.extraHeaders?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
    }
    var body by remember(profile?.id) { mutableStateOf(profile?.extraBody.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    var purposeMenu by remember { mutableStateOf(false) }
    val purpose = runCatching { ModelProfilePurpose.valueOf(purposeName) }.getOrDefault(ModelProfilePurpose.BOTH)
    val protocol = runCatching { NativeModelProtocol.valueOf(protocolName) }
        .getOrDefault(NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)
    LaunchedEffect(profile?.id) { profile?.let { loadKeys(it.id) } }
    fun submit() {
        try {
            val limits = ModelProfileLimits(concurrency.trim().toInt(), rpm.trim().toInt())
            val capabilities = ModelProfileCapabilities(tools, vision, reasoning,
                context.trim().takeIf { it.isNotEmpty() }?.toInt(),
                output.trim().takeIf { it.isNotEmpty() }?.toInt())
            val generation = ModelGenerationSettings(
                temperature.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                topP.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                output.trim().takeIf { it.isNotEmpty() }?.toInt(),
                effort.trim().takeIf { it.isNotEmpty() },
                serviceTier.trim().takeIf { it.isNotEmpty() })
            val extraHeaders = headers.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .associate { line ->
                    val split = line.indexOf(':')
                    if (split <= 0) throw InvalidModelProfile()
                    line.substring(0, split).trim() to line.substring(split + 1).trim()
                }
            val extraBody = body.trim().takeIf { it.isNotEmpty() }
            val submittedKeys = secret.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (profile == null) {
                val new = NewModelProfile(name.trim(), ModelProfileKind.CUSTOM, purpose, group.trim(), enabled,
                    endpoint, model.trim(), protocol, submittedKeys, limits, capabilities, generation,
                    extraHeaders, extraBody)
                NativeModelProfileValidation.validate(new)
                create(new)
            } else {
                val edit = ModelProfileEdit(name.trim(), profile.kind, purpose, group.trim(), enabled,
                    limits, capabilities, generation, extraHeaders, extraBody)
                NativeModelProfileValidation.validate(edit)
                update(profile.id, edit, endpoint, model.trim(), protocol,
                    if (submittedKeys.isEmpty()) null else submittedKeys)
            }
            invalid = false
        } catch (_: Exception) { invalid = true }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) dismiss() })
            Surface(Modifier.padding(16.dp).imePadding().widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 720.dp),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(20.dp).testTag("native-model-profile-form"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (profile == null) R.string.native_model_profile_add else R.string.native_model_profile_edit),
                        style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(name, { name = it.take(120); invalid = false },
                            label = { Text(stringResource(R.string.native_model_profile_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-name"))
                        OutlinedTextField(group, { group = it.take(80) },
                            label = { Text(stringResource(R.string.native_model_profile_group)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { purposeMenu = true }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.native_model_profile_purpose) + ": " + profilePurposeLabel(purpose))
                                }
                                DropdownMenu(expanded = purposeMenu, onDismissRequest = { purposeMenu = false }) {
                                    ModelProfilePurpose.entries.forEach { candidate ->
                                        DropdownMenuItem(text = { Text(profilePurposeLabel(candidate)) }, onClick = {
                                            purposeName = candidate.name; purposeMenu = false
                                        })
                                    }
                                }
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                            Text(stringResource(R.string.native_model_profile_enabled))
                        }
                        Text(stringResource(R.string.native_model_config_notice), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(endpoint, { endpoint = it.take(2048); invalid = false },
                            label = { Text(stringResource(R.string.native_model_endpoint)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-endpoint"))
                        Box {
                            OutlinedButton(onClick = { protocolMenu = true }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("native-model-profile-protocol")) {
                                Text(stringResource(R.string.native_model_protocol) + ": " + protocolLabel(protocol))
                            }
                            DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                                NativeModelProtocol.entries.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(protocolLabel(candidate)) }, onClick = {
                                        protocolName = candidate.name; protocolMenu = false
                                    })
                                }
                            }
                        }
                        OutlinedTextField(secret, { secret = it; invalid = false },
                            label = { Text(stringResource(if (profile == null) R.string.native_model_keys else R.string.native_model_keys_keep)) },
                            minLines = 2, maxLines = 4, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-keys"))
                        OutlinedTextField(model, { model = it.take(200); invalid = false },
                            label = { Text(stringResource(R.string.native_model_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-model"))
                        Text(stringResource(R.string.native_model_profile_limits), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(concurrency, { concurrency = it.take(2) },
                                label = { Text(stringResource(R.string.native_model_profile_concurrency)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            OutlinedTextField(rpm, { rpm = it.take(5) },
                                label = { Text(stringResource(R.string.native_model_profile_rpm)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                        Text(stringResource(R.string.native_model_profile_capabilities), style = MaterialTheme.typography.titleSmall)
                        CapabilityRow(stringResource(R.string.native_model_profile_tools), tools) { tools = it }
                        CapabilityRow(stringResource(R.string.native_model_profile_vision), vision) { vision = it }
                        CapabilityRow(stringResource(R.string.native_model_profile_reasoning), reasoning) { reasoning = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(context, { context = it.take(8) },
                                label = { Text(stringResource(R.string.native_model_profile_context)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            OutlinedTextField(output, { output = it.take(8) },
                                label = { Text(stringResource(R.string.native_model_profile_output)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(temperature, { temperature = it.take(8) },
                                label = { Text(stringResource(R.string.native_model_profile_temperature)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                            OutlinedTextField(topP, { topP = it.take(8) },
                                label = { Text(stringResource(R.string.native_model_profile_top_p)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(effort, { effort = it.take(32) },
                                label = { Text(stringResource(R.string.native_model_profile_effort)) }, singleLine = true,
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(serviceTier, { serviceTier = it.take(32) },
                                label = { Text(stringResource(R.string.native_model_profile_service_tier)) }, singleLine = true,
                                modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(headers, { headers = it },
                            label = { Text(stringResource(R.string.native_model_profile_headers)) }, minLines = 2,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(body, { body = it },
                            label = { Text(stringResource(R.string.native_model_profile_extra_body)) }, minLines = 2,
                            modifier = Modifier.fillMaxWidth())
                        if (profile != null) {
                            Text(stringResource(R.string.native_model_profile_key_states), style = MaterialTheme.typography.titleSmall)
                            if (keys.isEmpty()) Text(stringResource(R.string.native_model_profile_no_keys),
                                style = MaterialTheme.typography.bodySmall)
                            for (key in keys) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key.id, style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.native_model_profile_key_failures, key.failures),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = key.enabled, enabled = !busy,
                                        onCheckedChange = { setKeyEnabled(profile.id, key.id, it) })
                                }
                            }
                        }
                        if (invalid) Text(stringResource(R.string.native_model_settings_error),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(enabled = !busy, onClick = { submit() },
                            modifier = Modifier.testTag("native-model-profile-save")) {
                            Text(stringResource(R.string.native_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: Boolean?, change: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = {
            change(when (value) { null -> true; true -> false; false -> null })
        }) {
            Text(stringResource(when (value) {
                null -> R.string.native_model_profile_capability_unknown
                true -> R.string.native_model_profile_capability_supported
                false -> R.string.native_model_profile_capability_unsupported
            }))
        }
    }
}

@Composable
private fun protocolLabel(protocol: NativeModelProtocol): String = stringResource(when (protocol) {
    NativeModelProtocol.OPENAI_CHAT_COMPLETIONS -> R.string.native_model_protocol_chat
    NativeModelProtocol.OPENAI_RESPONSES -> R.string.native_model_protocol_responses
    NativeModelProtocol.GOOGLE_GEMINI -> R.string.native_model_protocol_gemini
    NativeModelProtocol.ANTHROPIC_MESSAGES -> R.string.native_model_protocol_anthropic
    NativeModelProtocol.SAKURA_LLM -> R.string.native_model_protocol_sakura
})

@Composable
private fun profilePurposeLabel(purpose: ModelProfilePurpose): String = stringResource(when (purpose) {
    ModelProfilePurpose.TRANSLATION -> R.string.native_model_profile_purpose_translation
    ModelProfilePurpose.AGENT -> R.string.native_model_profile_purpose_agent
    ModelProfilePurpose.BOTH -> R.string.native_model_profile_purpose_both
})
