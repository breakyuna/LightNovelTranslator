package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelProxyCredentialStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Ephemeral status for proxy-credential commands. It deliberately has no username, password
 * or reference field, so a password can never leak through UI state, diagnostics or snapshots.
 */
internal data class NativeProxyCredentialUiState(
    val busy: Boolean = false,
    val clearing: Boolean = false,
    val error: Int? = null,
    val notice: Int? = null,
    /** Incremented after a successful save or clear so the form can drop typed secrets. */
    val revision: Long = 0,
)

/**
 * What a network-settings save should do with the encrypted credential. [Replace] carries
 * the credential only as a command argument; it is never placed in [NativeProxyCredentialUiState].
 */
internal sealed interface NativeProxyCredentialChange {
    /** Keep the current opaque reference, even when the proxy is being removed for later use. */
    data object Keep : NativeProxyCredentialChange
    /** Encrypt and attach a new username/password under a fresh opaque reference. */
    data class Replace(val username: String, val password: String) : NativeProxyCredentialChange {
        override fun toString(): String = "Replace(username=redacted, password=redacted)"
    }
    /** Remove the reference from the proxy but leave the encrypted file for an explicit clear. */
    data object DetachKeepFile : NativeProxyCredentialChange
    /** Remove the reference and delete the encrypted file. */
    data object Delete : NativeProxyCredentialChange
}

/** Commands shared by the network form and the ViewModel; state never contains the secret. */
internal class NativeProxyCredentialActions(
    val state: StateFlow<NativeProxyCredentialUiState>,
    val saveNetwork: (NativeNetworkSettingsInput, NativeProxyCredentialChange) -> Unit,
    val clear: () -> Unit,
)

/** Narrow write boundary over the Keystore-backed store so ViewModel tests use a fake. */
internal interface NativeProxyCredentialWriter {
    suspend fun save(reference: String, credential: ModelProxyCredential)
    suspend fun clear(reference: String)
}

internal class StoreProxyCredentialWriter(
    private val store: NativeModelProxyCredentialStore,
) : NativeProxyCredentialWriter {
    override suspend fun save(reference: String, credential: ModelProxyCredential) =
        store.save(reference, credential)
    override suspend fun clear(reference: String) = store.clear(reference)
}

/**
 * Proxy username/password fields. The password is never pre-filled, never rendered as plain
 * text and never written into saveable state; a successful command recreates the form.
 */
@Composable
internal fun NativeProxyCredentialFields(
    hasStoredCredential: Boolean,
    busy: Boolean,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.native_settings_proxy_auth_description),
            style = MaterialTheme.typography.bodySmall)
        if (hasStoredCredential) {
            Text(stringResource(R.string.native_settings_proxy_saved),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_settings_proxy_existing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = username,
            onValueChange = { if (it.length <= ModelProxyCredential.MAX_PART_CHARS) onUsernameChange(it) },
            label = { Text(stringResource(R.string.native_settings_proxy_username)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("native-settings-proxy-username"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { if (it.length <= ModelProxyCredential.MAX_PART_CHARS) onPasswordChange(it) },
            label = { Text(stringResource(R.string.native_settings_proxy_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("native-settings-proxy-password"),
        )
        if (busy) Text(stringResource(R.string.native_settings_proxy_busy),
            style = MaterialTheme.typography.bodySmall)
        if (hasStoredCredential) {
            OutlinedButton(onClick = onClear, enabled = !busy,
                modifier = Modifier.testTag("native-settings-proxy-clear")) {
                Text(stringResource(R.string.native_settings_proxy_clear))
            }
        }
    }
}

/** Explicit keep/delete choice when authentication or the whole proxy is switched off. */
@Composable
internal fun NativeProxyCredentialDispositionDialog(
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    NativeContentDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_settings_proxy_detach_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_settings_proxy_detach_body))
                TextButton(onClick = onDismiss,
                    modifier = Modifier.testTag("native-settings-proxy-cancel")) {
                    Text(stringResource(R.string.native_cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep, modifier = Modifier.testTag("native-settings-proxy-keep")) {
                Text(stringResource(R.string.native_settings_proxy_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("native-settings-proxy-delete")) {
                Text(stringResource(R.string.native_settings_proxy_delete))
            }
        },
    )
}
