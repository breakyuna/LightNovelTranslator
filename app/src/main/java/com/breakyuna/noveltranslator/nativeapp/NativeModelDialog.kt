package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
 * CI's Robolectric probes did not settle text fields in a wrap-content dialog window.
 * Keep a fixed-size window and switch between the form and picker inside that same window.
 */
@Composable
internal fun NativeModelDialog(settings: NativeModelSettings?, busy: Boolean, error: Int?, loading: Boolean,
    dismiss: () -> Unit, save: (String, String, String?, () -> Unit) -> Unit,
    fetch: (String, String, ModelConfigurationRef?, (List<String>) -> Unit) -> Unit, cancel: () -> Unit,
    saveProfile: ((String, String, NativeModelProtocol, List<String>?, () -> Unit) -> Unit)? = null,
    fetchProfile: ((String, String, ModelConfigurationRef?, NativeModelProtocol, (List<String>) -> Unit) -> Unit)? = null) {
    // The form accepts operation URLs. Show one explicitly so reopening a base ending in
    // /models or /chat/completions cannot strip a reverse-proxy prefix on save/discovery.
    var endpoint by rememberSaveable(settings?.reference) { mutableStateOf(settings?.let {
        runCatching { NativeModelEndpoint.chat(it.endpoint) }.getOrDefault(it.endpoint)
    } ?: "") }
    var model by rememberSaveable(settings?.reference) { mutableStateOf(settings?.model ?: "") }
    // Password and discovery results never enter SavedStateHandle/Bundle or model snapshots.
    var secret by remember(settings?.reference) { mutableStateOf("") }
    var keysText by remember(settings?.reference) { mutableStateOf("") }
    var keepKey by rememberSaveable(settings?.reference) { mutableStateOf(settings?.hasCredential == true) }
    var protocolName by rememberSaveable(settings?.reference) {
        mutableStateOf((settings?.protocol ?: NativeModelProtocol.OPENAI_CHAT_COMPLETIONS).name)
    }
    var protocolMenu by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val profileMode = saveProfile != null
    val protocol = runCatching { NativeModelProtocol.valueOf(protocolName) }
        .getOrDefault(NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)
    val normalized = runCatching { NativeModelEndpoint.normalize(endpoint) }.getOrNull()
    val savedBase = settings?.let { runCatching { NativeModelEndpoint.base(it.endpoint) }.getOrNull() }
    val canKeep = settings?.hasCredential == true && normalized != null &&
        normalized == savedBase
    val canReuse = keepKey && canKeep && (if (profileMode) keysText.isBlank() else secret.isEmpty())
    val stored = settings?.reference?.takeIf { canReuse }
    val discoveryKey = if (profileMode) keysText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    else secret
    var available by remember(endpoint, discoveryKey, stored) { mutableStateOf<List<String>?>(null) }
    var picker by remember(endpoint, discoveryKey, stored) { mutableStateOf(false) }
    val formScroll = rememberScrollState()
    val latestLoading by rememberUpdatedState(loading)
    val latestCancel by rememberUpdatedState(cancel)
    DisposableEffect(Unit) { onDispose { if (latestLoading) latestCancel() } }
    fun close() { if (loading) cancel(); secret = ""; keysText = ""; dismiss() }
    fun submit() {
        try {
            if (profileMode) {
                val keys = if (stored != null) null else keysText.lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                if (keys != null && keys.distinct().size != keys.size) throw InvalidModelSettings()
                NativeModelSettingsValidation.validateProfile(endpoint, model, keys ?: emptyList())
                invalid = false; attempted = true
                val persist = requireNotNull(saveProfile)
                persist(endpoint, model, protocol, keys) { secret = ""; keysText = ""; dismiss() }
            } else {
                NativeModelSettingsValidation.validate(endpoint, model, secret)
                invalid = false; attempted = true
                save(endpoint, model, if (stored != null) null else secret) { secret = ""; dismiss() }
            }
        } catch (_: InvalidModelSettings) { invalid = true }
    }
    fun dismissVisibleContent() { if (picker) picker = false else if (!busy || loading) close() }
    Dialog(onDismissRequest = { dismissVisibleContent() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogScrim(onDismiss = { dismissVisibleContent() }) {
            Surface(
                modifier = Modifier.padding(20.dp).imePadding().widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 3.dp,
            ) {
                if (picker) {
                    NativeModelPicker(requireNotNull(available), model, { model = it; picker = false }, { picker = false })
                } else Column(Modifier.testTag("native-model-form").padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_model_configure),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(formScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.native_model_config_notice))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { endpoint = "http://127.0.0.1:11434/v1"; invalid = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Ollama") }
                            OutlinedButton(onClick = { endpoint = "http://127.0.0.1:1234/v1"; invalid = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("LM Studio") }
                        }
                        OutlinedTextField(value = endpoint, onValueChange = { invalid = false; if (it.length <= 2048) endpoint = it },
                            label = { Text(stringResource(R.string.native_model_endpoint)) }, enabled = !busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.native_model_endpoint_hint), style = MaterialTheme.typography.bodySmall)
                        if (normalized?.startsWith("http://") == true) Text(stringResource(R.string.native_model_http_notice), style = MaterialTheme.typography.bodySmall)
                        if (profileMode) {
                            Box {
                                OutlinedButton(onClick = { protocolMenu = true }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("native-model-protocol")) {
                                    val title = stringResource(R.string.native_model_protocol) + ": " + protocolLabel(protocol)
                                    Text(title)
                                }
                                DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                                    NativeModelProtocol.entries.forEach { candidate ->
                                        DropdownMenuItem(text = { Text(protocolLabel(candidate)) },
                                            onClick = { protocolName = candidate.name; protocolMenu = false; invalid = false })
                                    }
                                }
                            }
                            OutlinedTextField(value = keysText, onValueChange = {
                                invalid = false
                                if (it.length <= 8192 * NativeModelSettings.MAX_KEYS) keysText = it
                            }, label = { Text(stringResource(R.string.native_model_keys)) }, enabled = !busy,
                                minLines = 2, maxLines = 4, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth().testTag("native-model-keys"))
                            Text(stringResource(R.string.native_model_keys_hint), style = MaterialTheme.typography.bodySmall)
                        } else {
                            OutlinedTextField(value = secret, onValueChange = { invalid = false; if (it.length <= 8192) secret = it },
                                label = { Text(stringResource(R.string.native_model_key)) }, enabled = !busy, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        }
                        if (settings?.hasCredential == true) Row {
                            Checkbox(checked = stored != null, onCheckedChange = { keepKey = it },
                                enabled = !busy && canKeep && (if (profileMode) keysText.isBlank() else secret.isEmpty()))
                            Text(stringResource(R.string.native_model_keep_key), Modifier.weight(1f).padding(top = 12.dp))
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && normalized != null, onClick = {
                                attempted = true; invalid = false
                                val loaded = { models: List<String> -> available = models; picker = models.isNotEmpty() }
                                if (profileMode && fetchProfile != null)
                                    requireNotNull(fetchProfile)(endpoint, discoveryKey, stored, protocol, loaded)
                                else fetch(endpoint, discoveryKey, stored, loaded)
                            }) { Text(stringResource(R.string.native_models_fetch)) }
                            if (loading) TextButton(onClick = cancel) { Text(stringResource(R.string.native_cancel)) }
                        }
                        available?.let { models ->
                            if (models.isEmpty()) Text(stringResource(R.string.native_models_empty))
                            else TextButton(onClick = { picker = true }, enabled = !busy) { Text(stringResource(R.string.native_models_count, models.size)) }
                        }
                        OutlinedTextField(value = model, onValueChange = { invalid = false; if (it.length <= 200) model = it },
                            label = { Text(stringResource(R.string.native_model_name)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val failure = if (invalid) R.string.native_model_settings_error else error?.takeIf { attempted }
                        failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy || loading, onClick = { close() }) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(enabled = !busy && normalized != null && model.isNotBlank(), onClick = { submit() }) {
                            Text(stringResource(R.string.native_save))
                        }
                    }
                }
            }
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
private fun NativeModelPicker(models: List<String>, current: String, select: (String) -> Unit, dismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) { models.filter { it.contains(search, ignoreCase = true) } }
    Column(Modifier.testTag("native-model-picker").padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.native_models_select), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(search, { search = it.take(200) }, label = { Text(stringResource(R.string.native_models_search)) }, singleLine = true)
        if (filtered.isEmpty()) Text(stringResource(R.string.native_models_no_match), Modifier.padding(12.dp))
        LazyColumn(Modifier.testTag("native-model-list").fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp)) {
            items(filtered, key = { it }) { id -> TextButton(onClick = { select(id) }, modifier = Modifier.fillMaxWidth()) {
                Text(id, color = if (id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
        }
    }
}

/** Full-bleed dialog window: dismiss on a scrim tap, which a fixed-size window no longer provides. */
@Composable
private fun DialogScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.testTag("native-model-window").fillMaxSize(), contentAlignment = Alignment.Center) {
        // Surface blocks touches behind the card. A sibling backdrop needs no no-op card click
        // handler, and does not merge the form/list into a full-window clickable semantics node.
        Box(Modifier.matchParentSize().testTag("native-model-scrim").clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
        content()
    }
}
