package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

@Composable
internal fun NativeModelDialog(settings: NativeModelSettings?, busy: Boolean, error: Int?, loading: Boolean,
    dismiss: () -> Unit, save: (String, String, String?, () -> Unit) -> Unit,
    fetch: (String, String, ModelConfigurationRef?, (List<String>) -> Unit) -> Unit, cancel: () -> Unit) {
    // The form accepts operation URLs. Show one explicitly so reopening a base ending in
    // /models or /chat/completions cannot strip a reverse-proxy prefix on save/discovery.
    var endpoint by rememberSaveable(settings?.reference) { mutableStateOf(settings?.let {
        runCatching { NativeModelEndpoint.chat(it.endpoint) }.getOrDefault(it.endpoint)
    } ?: "") }
    var model by rememberSaveable(settings?.reference) { mutableStateOf(settings?.model ?: "") }
    // Password and discovery results never enter SavedStateHandle/Bundle or model snapshots.
    var secret by remember(settings?.reference) { mutableStateOf("") }
    var keepKey by rememberSaveable(settings?.reference) { mutableStateOf(settings?.hasCredential == true) }
    var invalid by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val normalized = runCatching { NativeModelEndpoint.normalize(endpoint) }.getOrNull()
    val savedBase = settings?.let { runCatching { NativeModelEndpoint.base(it.endpoint) }.getOrNull() }
    val canKeep = settings?.hasCredential == true && normalized != null &&
        normalized == savedBase
    val stored = settings?.reference?.takeIf { keepKey && canKeep && secret.isEmpty() }
    var available by remember(endpoint, secret, stored) { mutableStateOf<List<String>?>(null) }
    var picker by remember(endpoint, secret, stored) { mutableStateOf(false) }
    val latestLoading by rememberUpdatedState(loading)
    val latestCancel by rememberUpdatedState(cancel)
    DisposableEffect(Unit) { onDispose { if (latestLoading) latestCancel() } }
    fun close() { if (loading) cancel(); secret = ""; dismiss() }
    fun submit() {
        try {
            NativeModelSettingsValidation.validate(endpoint, model, secret)
            invalid = false; attempted = true
            save(endpoint, model, if (stored != null) null else secret) { secret = ""; dismiss() }
        } catch (_: InvalidModelSettings) { invalid = true }
    }
    // A bounded Surface instead of AlertDialog: the dialog text slot measures intrinsic widths,
    // which never settles for a scrolling column of text fields (Compose never reports idle).
    Dialog(onDismissRequest = { if (!busy || loading) close() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.native_model_configure), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
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
                    OutlinedTextField(value = secret, onValueChange = { invalid = false; if (it.length <= 8192) secret = it },
                        label = { Text(stringResource(R.string.native_model_key)) }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    if (settings?.hasCredential == true) Row {
                        Checkbox(checked = stored != null, onCheckedChange = { keepKey = it }, enabled = !busy && canKeep && secret.isEmpty())
                        Text(stringResource(R.string.native_model_keep_key), Modifier.weight(1f).padding(top = 12.dp))
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && normalized != null, onClick = {
                            attempted = true; invalid = false
                            fetch(endpoint, secret, stored) { models -> available = models; picker = models.isNotEmpty() }
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
    if (picker) NativeModelPicker(requireNotNull(available), model, { model = it; picker = false }, { picker = false })
}

@Composable
private fun NativeModelPicker(models: List<String>, current: String, select: (String) -> Unit, dismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) { models.filter { it.contains(search, ignoreCase = true) } }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.native_models_select), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(search, { search = it.take(200) }, label = { Text(stringResource(R.string.native_models_search)) }, singleLine = true)
                if (filtered.isEmpty()) Text(stringResource(R.string.native_models_no_match), Modifier.padding(12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { id -> TextButton(onClick = { select(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(id, color = if (id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
                }
            }
        }
    }
}
