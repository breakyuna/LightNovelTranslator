package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

@Composable
internal fun NativeModelDialog(settings: NativeModelSettings?, busy: Boolean, error: Int?,
                               dismiss: () -> Unit, save: (String, String, String, () -> Unit) -> Unit) {
    var endpoint by rememberSaveable(settings?.reference) { mutableStateOf(settings?.endpoint ?: "") }
    var model by rememberSaveable(settings?.reference) { mutableStateOf(settings?.model ?: "") }
    // Never place an API key into SavedStateHandle, Bundle or an on-screen configuration projection.
    var secret by remember(settings?.reference) { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) { secret = ""; dismiss() } }, title = { Text(stringResource(R.string.native_model_configure)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_model_config_notice))
            OutlinedTextField(value = endpoint, onValueChange = { invalid = false; if (it.length <= 2048) endpoint = it },
                label = { Text(stringResource(R.string.native_model_endpoint)) }, enabled = !busy, singleLine = true)
            OutlinedTextField(value = model, onValueChange = { invalid = false; if (it.length <= 200) model = it },
                label = { Text(stringResource(R.string.native_model_name)) }, enabled = !busy, singleLine = true)
            OutlinedTextField(value = secret, onValueChange = { invalid = false; if (it.length <= 8192) secret = it },
                label = { Text(stringResource(R.string.native_model_key)) }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            val failure = if (invalid) R.string.native_model_settings_error else error
            failure?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && endpoint.isNotBlank() && model.isNotBlank() && secret.isNotBlank(), onClick = {
            try {
                NativeModelSettingsValidation.validate(endpoint, model, secret)
                invalid = false
                save(endpoint, model, secret) { secret = ""; dismiss() }
            } catch (_: InvalidModelSettings) { invalid = true }
        }) { Text(stringResource(R.string.native_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { secret = ""; dismiss() }) { Text(stringResource(R.string.native_cancel)) } },
    )
}
