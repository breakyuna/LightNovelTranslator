package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

/**
 * U10 model directory. Configuration and credentials still go through the
 * profile port; cached catalogs load on open, while metadata or generation
 * probes run only from explicit buttons and the generation probe requires a
 * confirmation dialog. This page never starts a paid request by itself.
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
    requestedProfileId: String? = null,
    requestedProfileOpened: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<NativeModelProfile?>(null) }
    LaunchedEffect(Unit) { if (state.modelProfiles.isEmpty() && !state.modelProfilesLoading) refresh() }
    LaunchedEffect(requestedProfileId, state.modelProfiles) {
        if (requestedProfileId != null) {
            state.modelProfiles.firstOrNull { it.id == requestedProfileId }?.let {
                editing = it
                requestedProfileOpened()
            }
        }
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<NativeModelProfile?>(null) }
    if (creating || editing != null) {
        NativeModelProfileEditor(
            profile = editing,
            busy = state.busy,
            keys = state.modelProfileKeys,
            catalog = state.modelCatalog,
            catalogActions = state.modelCatalogActions,
            oauth = state.oauth,
            oauthActions = state.oauthActions,
            runActive = state.foregroundExecutionActive,
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
        NativeContentDialog(
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
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = back, modifier = Modifier.testTag("native-models-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.native_settings_back))
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = !state.busy,
                        onClick = { creating = true },
                        modifier = Modifier.testTag("native-models-add"),
                    ) {
                        Text(stringResource(R.string.native_model_profile_add))
                    }
                    OutlinedButton(enabled = !state.busy, onClick = refresh) {
                        Text(stringResource(R.string.native_refresh))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (state.modelProfilesLoading) NativeBusyIndicator()
        state.modelProfilesError?.let {
            NativeBanner(
                text = stringResource(it),
                tone = NativeTone.ERROR,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val ungrouped = stringResource(R.string.native_model_profile_ungrouped)
        if (state.modelProfiles.isEmpty() && !state.modelProfilesLoading) {
            Box(Modifier.padding(16.dp)) {
                NativeSectionCard(
                    title = stringResource(R.string.native_model_profiles_empty),
                    subtitle = stringResource(R.string.native_model_profiles_empty_hint),
                ) {
                    Button(enabled = !state.busy, onClick = { creating = true }) {
                        Text(stringResource(R.string.native_model_profile_add))
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("native-models-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var lastGroup: String? = null
            state.modelProfiles.forEach { profile ->
                val group = profile.group.ifBlank { ungrouped }
                if (group != lastGroup) {
                    item(key = "group:$group:${profile.id}") {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-model-card-${profile.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (selectedLabels.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${profile.settings.model} · ${protocolLabel(profile.settings.protocol)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (profile.enabled) {
                    NativeStatusBadge(stringResource(R.string.native_model_profile_enabled), tone = NativeTone.SUCCESS)
                } else {
                    NativeStatusBadge(stringResource(R.string.native_model_profile_disabled))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeStatusBadge(
                    text = stringResource(R.string.native_model_profile_purpose_value, profilePurposeLabel(profile.purpose)),
                    tone = NativeTone.SECONDARY,
                )
                NativeStatusBadge(
                    text = stringResource(R.string.native_model_profile_keys_value, profile.settings.keyCount),
                    tone = NativeTone.NEUTRAL,
                )
            }
            if (selectedLabels.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.native_model_profile_selected_value, selectedLabels),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = edit,
                    modifier = Modifier.testTag("native-model-edit-${profile.id}"),
                ) {
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
                var menu by remember(profile.id) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = !busy) { Text(stringResource(R.string.native_more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.native_duplicate_model)) },
                            onClick = { menu = false; copy() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_move_up)) }, enabled = canMoveUp,
                            onClick = { menu = false; move(-1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_move_down)) }, enabled = canMoveDown,
                            onClick = { menu = false; move(1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.native_model_action_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; delete() })
                    }
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
    catalog: NativeModelCatalogUiState,
    catalogActions: NativeModelCatalogActions,
    oauth: NativeOAuthUiState,
    oauthActions: NativeOAuthActions,
    runActive: Boolean,
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
    var descriptorPicker by remember { mutableStateOf(false) }
    var authModeName by rememberSaveable(profile?.id) {
        mutableStateOf(NativeOAuthMode.API_KEY.name)
    }
    val authMode = runCatching { NativeOAuthMode.valueOf(authModeName) }
        .getOrDefault(NativeOAuthMode.API_KEY)
    LaunchedEffect(profile?.id, oauth.profileId, oauth.fingerprint) {
        if (profile != null && oauth.profileId == profile.id) {
            authModeName = if (oauth.configured) NativeOAuthMode.OAUTH.name else NativeOAuthMode.API_KEY.name
        }
    }
    val purpose = runCatching { ModelProfilePurpose.valueOf(purposeName) }.getOrDefault(ModelProfilePurpose.BOTH)
    val protocol = runCatching { NativeModelProtocol.valueOf(protocolName) }
        .getOrDefault(NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)
    val visibleCatalog = catalog.takeIf { it.profileId == profile?.id } ?: NativeModelCatalogUiState(
        profileId = profile?.id)
    LaunchedEffect(profile?.id) {
        profile?.let { loadKeys(it.id) }
    }
    val fields = listOf(name, group, purposeName, enabled, endpoint, model, protocolName, secret, concurrency, rpm, tools, vision, reasoning, context, output, temperature, topP, effort, serviceTier, headers, body)
    val originalFields = remember(profile?.id) { fields }
    var discard by remember(profile?.id) { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (!busy) { if (fields != originalFields) discard = true else dismiss() }
    }
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
    Dialog(onDismissRequest = { requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null) { requestClose() })
            Surface(
                modifier = Modifier.padding(16.dp).imePadding().widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 720.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.padding(20.dp).testTag("native-model-profile-form"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (profile == null) R.string.native_model_profile_add else R.string.native_model_profile_edit),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        NativeOAuthSection(
                            profile = profile,
                            oauth = oauth,
                            actions = oauthActions,
                            enabled = !busy && !runActive,
                            mode = authMode,
                            onModeChange = { authModeName = it.name },
                        )
                        OutlinedTextField(secret, { secret = it; invalid = false },
                            label = { Text(stringResource(if (profile == null) R.string.native_model_keys else R.string.native_model_keys_keep)) },
                            minLines = 2, maxLines = 4, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-keys"))
                        OutlinedTextField(model, { model = it.take(200); invalid = false },
                            label = { Text(stringResource(R.string.native_model_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("native-model-profile-model"))
                        ModelCatalogPanel(
                            profile = profile,
                            model = model,
                            catalog = visibleCatalog,
                            busy = busy,
                            actions = catalogActions,
                            choose = { descriptorPicker = true },
                        )
                        NativeAgentFoldout(stringResource(R.string.native_settings_expert), stateKey = profile?.id, forceExpanded = invalid) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            }
                        }
                        if (invalid) Text(stringResource(R.string.native_model_settings_error),
                            color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy, onClick = requestClose) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(enabled = !busy, onClick = { submit() },
                            modifier = Modifier.testTag("native-model-profile-save")) {
                            Text(stringResource(R.string.native_save))
                        }
                    }
                }
            }
        }
    }
    if (descriptorPicker && profile != null) {
        NativeCatalogPicker(
            descriptors = visibleCatalog.descriptors,
            current = model,
            select = { model = it; invalid = false; descriptorPicker = false },
            dismiss = { descriptorPicker = false },
        )
    }
    if (profile != null && visibleCatalog.generationConfirmation) {
        val hint = visibleCatalog.billing
        NativeContentDialog(
            onDismissRequest = { if (!busy) catalogActions.dismissConfirmation() },
            title = { Text(stringResource(R.string.native_model_probe_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hint?.priced == true) stringResource(
                        R.string.native_model_probe_billing_known,
                        hint?.currency.orEmpty(), hint?.outputPricePerMillion.orEmpty(),
                        NATIVE_MODEL_PROBE_MAX_OUTPUT_TOKENS)
                    else stringResource(R.string.native_model_probe_billing_unknown,
                        NATIVE_MODEL_PROBE_MAX_OUTPUT_TOKENS))
                    Text(stringResource(R.string.native_model_probe_no_write), style = MaterialTheme.typography.bodySmall)
                    if (busy) NativeBusyIndicator()
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { catalogActions.confirmGeneration(profile.id) },
                    modifier = Modifier.testTag("native-model-probe-confirm")) {
                    Text(stringResource(R.string.native_model_probe_confirm_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = catalogActions.dismissConfirmation) {
                    Text(stringResource(R.string.native_cancel))
                }
            },
        )
    }
    if (discard) NativeContentDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.native_discard_title)) },
        text = { Text(stringResource(R.string.native_discard_body)) },
        confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_cancel)) } })

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCatalogPanel(
    profile: NativeModelProfile?,
    model: String,
    catalog: NativeModelCatalogUiState,
    busy: Boolean,
    actions: NativeModelCatalogActions,
    choose: () -> Unit,
) {
    Text(stringResource(R.string.native_model_catalog_title), style = MaterialTheme.typography.titleSmall)
    if (profile == null) {
        Text(stringResource(R.string.native_model_catalog_profile_save_first),
            style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        text = stringResource(when (catalog.source) {
            NativeModelCatalogViewSource.LIVE -> R.string.native_model_catalog_source_live
            NativeModelCatalogViewSource.CACHED -> R.string.native_model_catalog_source_cached
            NativeModelCatalogViewSource.EMPTY_OVERRIDE -> R.string.native_model_catalog_source_empty
            null -> R.string.native_model_catalog_source_none
        }),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("native-model-catalog-source"),
    )
    catalog.fetchedAtMillis?.let { fetchedAt ->
        Text(stringResource(R.string.native_model_catalog_last_success, catalogTime(fetchedAt)),
            style = MaterialTheme.typography.bodySmall)
    }
    if (catalog.descriptors.isNotEmpty()) {
        Text(stringResource(R.string.native_model_catalog_models, catalog.descriptors.size),
            style = MaterialTheme.typography.bodySmall)
    }
    if (catalog.source != null && !catalog.persisted) {
        Text(stringResource(R.string.native_model_catalog_not_saved),
            style = MaterialTheme.typography.bodySmall)
    }
    if (catalog.descriptors.isEmpty() && catalog.source != null) {
        Text(stringResource(R.string.native_model_catalog_empty_hint),
            style = MaterialTheme.typography.bodySmall)
    }
    val selected = catalog.descriptor(model)
    selected?.contextTokens?.let { tokens ->
        Text(stringResource(R.string.native_model_catalog_context, formattedTokens(tokens)),
            style = MaterialTheme.typography.bodySmall)
    }
    selected?.outputTokens?.let { tokens ->
        Text(stringResource(R.string.native_model_catalog_output, formattedTokens(tokens)),
            style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !busy && !catalog.loading && !catalog.generationConfirmation,
            onClick = { actions.refresh(profile.id) },
            modifier = Modifier.testTag("native-model-catalog-refresh"),
        ) { Text(stringResource(R.string.native_model_catalog_refresh)) }
        OutlinedButton(
            enabled = !busy && !catalog.loading && !catalog.generationConfirmation,
            onClick = { actions.probeMetadata(profile.id) },
            modifier = Modifier.testTag("native-model-catalog-metadata"),
        ) { Text(stringResource(R.string.native_model_catalog_probe_metadata)) }
        OutlinedButton(
            enabled = !busy && !catalog.loading && !catalog.generationRunning &&
                !catalog.generationConfirmation,
            onClick = { actions.requestGeneration(profile.id) },
            modifier = Modifier.testTag("native-model-catalog-generation"),
        ) { Text(stringResource(R.string.native_model_catalog_probe_generation)) }
    }
    if (catalog.descriptors.isNotEmpty()) {
        TextButton(enabled = !busy, onClick = choose,
            modifier = Modifier.testTag("native-model-catalog-choose")) {
            Text(stringResource(R.string.native_model_catalog_choose, catalog.descriptors.size))
        }
    }
    if (catalog.loading || catalog.generationRunning) {
        NativeBusyIndicator()
        Text(stringResource(if (catalog.generationRunning) R.string.native_model_probe_generation_wait
        else R.string.native_model_catalog_loading), style = MaterialTheme.typography.bodySmall)
    }
    if (catalog.loading || catalog.generationRunning) {
        TextButton(enabled = true, onClick = actions.cancel,
            modifier = Modifier.testTag("native-model-catalog-cancel")) {
            Text(stringResource(R.string.native_cancel))
        }
    }
    catalog.error?.let { error ->
        Text(stringResource(error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    catalog.metadataProbe?.let { ProbeResultText(it) }
    catalog.generationProbe?.let { ProbeResultText(it) }
}

@Composable
private fun ProbeResultText(result: NativeModelProbeUi) {
    when (result) {
        is NativeModelProbeUi.Metadata -> Text(
            stringResource(R.string.native_model_probe_metadata_success,
                result.modelCount, result.elapsedMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is NativeModelProbeUi.Generation -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.native_model_probe_generation_success,
                result.outputChars, result.elapsedMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            result.usage?.let { ProbeUsageText(it) }
        }
        is NativeModelProbeUi.Failure -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(nativeProbeFailureLabel(result.kind)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            result.retryAfterMillis?.let { delay ->
                Text(stringResource(R.string.native_model_probe_retry_after,
                    ((delay + 999) / 1000).coerceAtLeast(1)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        NativeModelProbeUi.Cancelled -> Text(stringResource(R.string.native_model_probe_cancelled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProbeUsageText(usage: RequestUsage) {
    Text(stringResource(R.string.native_model_probe_usage,
        probeTokenLabel(usage.input.tokens, usage.input.source),
        probeTokenLabel(usage.output.tokens, usage.output.source)),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun probeTokenLabel(tokens: Long?, source: UsageSource): String {
    if (tokens == null) return stringResource(R.string.native_model_probe_usage_unknown)
    val number = formattedNumber(tokens)
    return when (source) {
        UsageSource.ACTUAL -> stringResource(R.string.native_model_probe_usage_actual, number)
        UsageSource.ESTIMATED -> stringResource(R.string.native_model_probe_usage_estimated, number)
        UsageSource.UNKNOWN -> stringResource(R.string.native_model_probe_usage_unknown)
    }
}

@Composable
private fun NativeCatalogPicker(
    descriptors: List<ModelDescriptor>,
    current: String,
    select: (String) -> Unit,
    dismiss: () -> Unit,
) {
    NativeContentDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_model_catalog_picker_title)) },
        text = {
            if (descriptors.isEmpty()) {
                Text(stringResource(R.string.native_model_catalog_picker_empty))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("native-model-catalog-list")) {
                    items(descriptors, key = { it.id }) { descriptor ->
                        TextButton(
                            onClick = { select(descriptor.id) },
                            modifier = Modifier.fillMaxWidth().testTag("native-model-catalog-item-${descriptor.id}"),
                        ) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(descriptor.displayName,
                                    color = if (descriptor.id == current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface)
                                if (descriptor.displayName != descriptor.id) {
                                    Text(descriptor.id, style = MaterialTheme.typography.bodySmall)
                                }
                                val limits = listOfNotNull(
                                    descriptor.contextTokens?.let {
                                        stringResource(R.string.native_model_catalog_context, formattedTokens(it))
                                    },
                                    descriptor.outputTokens?.let {
                                        stringResource(R.string.native_model_catalog_output, formattedTokens(it))
                                    },
                                )
                                if (limits.isNotEmpty()) {
                                    Text(limits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
    )
}

@Composable
private fun catalogTime(millis: Long): String {
    val locale = java.util.Locale.getDefault()
    return remember(millis, locale) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT, locale).format(java.util.Date(millis))
    }
}

@Composable
private fun formattedTokens(tokens: Int): String = formattedNumber(tokens.toLong())

@Composable
private fun formattedNumber(value: Long): String {
    val locale = java.util.Locale.getDefault()
    return remember(value, locale) {
        java.text.NumberFormat.getIntegerInstance(locale).format(value)
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
