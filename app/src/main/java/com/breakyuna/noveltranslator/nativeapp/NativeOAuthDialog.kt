package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Authentication block for one saved profile. Tokens are never rendered; the UI only
 * sees configuration, presence, expiry and a sanitized failure. Login opens an
 * external browser, and the exported callback Activity only records a one-time result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeOAuthSection(
    profile: NativeModelProfile?,
    oauth: NativeOAuthUiState,
    actions: NativeOAuthActions,
    enabled: Boolean,
    mode: NativeOAuthMode,
    onModeChange: (NativeOAuthMode) -> Unit,
) {
    var authorizationEndpoint by remember(profile?.id) { mutableStateOf("") }
    var tokenEndpoint by remember(profile?.id) { mutableStateOf("") }
    var clientId by remember(profile?.id) { mutableStateOf("") }
    var scopes by remember(profile?.id) { mutableStateOf("") }
    var confirmApiKey by remember(profile?.id) { mutableStateOf(false) }

    LaunchedEffect(profile?.id) {
        profile?.let { actions.load(it.id) }
    }
    LaunchedEffect(profile?.id, oauth.profileId, oauth.fingerprint) {
        if (profile != null && oauth.profileId == profile.id) {
            authorizationEndpoint = oauth.authorizationEndpoint
            tokenEndpoint = oauth.tokenEndpoint
            clientId = oauth.clientId
            scopes = oauth.scopes.joinToString(" ")
        }
    }

    val oauthCurrent = profile != null && oauth.profileId == profile.id
    val oauthBusy = oauthCurrent && oauth.busy
    val parsedScopes = runCatching { nativeOAuthScopes(scopes) }.getOrNull()
    val dirty = parsedScopes == null ||
        authorizationEndpoint.trim() != oauth.authorizationEndpoint ||
        tokenEndpoint.trim() != oauth.tokenEndpoint ||
        clientId.trim() != oauth.clientId ||
        parsedScopes != oauth.scopes

    Column(
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.native_oauth_section_title),
            style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.native_oauth_notice),
            style = MaterialTheme.typography.bodySmall)
        if (profile == null) {
            Text(stringResource(R.string.native_oauth_save_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == NativeOAuthMode.API_KEY,
                enabled = profile != null && enabled,
                onClick = {
                    if (profile != null && oauthCurrent &&
                        (oauth.configured || oauth.hasToken || oauth.failure == NativeOAuthUiFailure.STORE)) {
                        confirmApiKey = true
                    } else {
                        onModeChange(NativeOAuthMode.API_KEY)
                    }
                },
                label = { Text(stringResource(R.string.native_oauth_mode_api_key)) },
                modifier = Modifier.testTag("native-oauth-mode-api-key"),
            )
            FilterChip(
                selected = mode == NativeOAuthMode.OAUTH,
                enabled = profile != null && enabled,
                onClick = { onModeChange(NativeOAuthMode.OAUTH) },
                label = { Text(stringResource(R.string.native_oauth_mode_oauth)) },
                modifier = Modifier.testTag("native-oauth-mode-oauth"),
            )
        }
        if (profile != null && mode == NativeOAuthMode.API_KEY) {
            Text(stringResource(R.string.native_oauth_api_key_hint),
                style = MaterialTheme.typography.bodySmall)
        }
        if (profile != null && mode == NativeOAuthMode.OAUTH) {
            OAuthConfigurationFields(
                enabled = enabled && !oauthBusy,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                scopes = scopes,
                changeAuthorizationEndpoint = { authorizationEndpoint = it.take(2048) },
                changeTokenEndpoint = { tokenEndpoint = it.take(2048) },
                changeClientId = { clientId = it.take(512) },
                changeScopes = { scopes = it.take(8192) },
            )
            Text(stringResource(R.string.native_oauth_public_client),
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = enabled && !oauthBusy,
                    onClick = {
                        profile?.let {
                            actions.saveConfiguration(it.id, NativeOAuthConfigDraft(
                                authorizationEndpoint, tokenEndpoint, clientId, scopes))
                        }
                    },
                    modifier = Modifier.testTag("native-oauth-save"),
                ) { Text(stringResource(R.string.native_oauth_save_config)) }
                OutlinedButton(
                    enabled = enabled && oauthCurrent && oauth.configured &&
                        !oauthBusy && !oauth.pendingLogin && !dirty,
                    onClick = { profile?.let { actions.login(it.id) } },
                    modifier = Modifier.testTag("native-oauth-login"),
                ) { Text(stringResource(R.string.native_oauth_login)) }
                if (oauthCurrent && oauth.pendingLogin) {
                    OutlinedButton(
                        enabled = !oauthBusy,
                        onClick = { profile?.let { actions.cancel(it.id) } },
                        modifier = Modifier.testTag("native-oauth-cancel"),
                    ) { Text(stringResource(R.string.native_oauth_cancel)) }
                }
                OutlinedButton(
                    enabled = enabled && !oauthBusy,
                    onClick = { profile?.let { actions.refreshStatus(it.id) } },
                    modifier = Modifier.testTag("native-oauth-refresh"),
                ) { Text(stringResource(R.string.native_oauth_refresh_status)) }
                OutlinedButton(
                    enabled = enabled && oauthCurrent && oauth.configured && !oauthBusy,
                    onClick = { profile?.let { actions.logout(it.id) } },
                    modifier = Modifier.testTag("native-oauth-logout"),
                ) { Text(stringResource(R.string.native_oauth_logout)) }
            }
            if (oauthCurrent && dirty && oauth.configured) {
                Text(stringResource(R.string.native_oauth_unsaved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (oauthCurrent) OAuthStatusText(oauth)
        }
        if (oauthCurrent && oauth.busy) NativeBusyIndicator()
        if (oauthCurrent) {
            oauth.failure?.let { failure ->
                Text(stringResource(nativeOAuthFailureLabel(failure)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (oauth.failure == null) {
                oauth.notice?.let { notice ->
                    Text(stringResource(nativeOAuthNoticeLabel(notice)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (confirmApiKey && profile != null) {
        NativeContentDialog(
            onDismissRequest = { confirmApiKey = false },
            title = { Text(stringResource(R.string.native_oauth_switch_api_key_title)) },
            text = { Text(stringResource(R.string.native_oauth_switch_api_key_text)) },
            confirmButton = {
                TextButton(
                    enabled = enabled && !oauthBusy,
                    onClick = {
                        profile?.let { actions.removeConfiguration(it.id) }
                        onModeChange(NativeOAuthMode.API_KEY)
                        confirmApiKey = false
                    },
                    modifier = Modifier.testTag("native-oauth-switch-api-key-confirm"),
                ) { Text(stringResource(R.string.native_oauth_switch_api_key_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApiKey = false }) {
                    Text(stringResource(R.string.native_cancel))
                }
            },
        )
    }
}

@Composable
private fun OAuthConfigurationFields(
    enabled: Boolean,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    clientId: String,
    scopes: String,
    changeAuthorizationEndpoint: (String) -> Unit,
    changeTokenEndpoint: (String) -> Unit,
    changeClientId: (String) -> Unit,
    changeScopes: (String) -> Unit,
) {
    OutlinedTextField(
        value = authorizationEndpoint,
        onValueChange = changeAuthorizationEndpoint,
        label = { Text(stringResource(R.string.native_oauth_authorization_endpoint)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-authorization-endpoint"),
    )
    OutlinedTextField(
        value = tokenEndpoint,
        onValueChange = changeTokenEndpoint,
        label = { Text(stringResource(R.string.native_oauth_token_endpoint)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-token-endpoint"),
    )
    OutlinedTextField(
        value = clientId,
        onValueChange = changeClientId,
        label = { Text(stringResource(R.string.native_oauth_client_id)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-client-id"),
    )
    OutlinedTextField(
        value = scopes,
        onValueChange = changeScopes,
        label = { Text(stringResource(R.string.native_oauth_scopes)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-scopes"),
    )
    OutlinedTextField(
        value = NativeOAuthConfig.REDIRECT_URI,
        onValueChange = {},
        label = { Text(stringResource(R.string.native_oauth_redirect_uri)) },
        singleLine = true,
        readOnly = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("native-oauth-redirect-uri"),
    )
}

@Composable
private fun OAuthStatusText(oauth: NativeOAuthUiState) {
    Text(stringResource(R.string.native_oauth_status),
        style = MaterialTheme.typography.titleSmall)
    val statusLabel = when (oauth.status) {
        NativeOAuthStatus.NOT_CONFIGURED -> R.string.native_oauth_status_not_configured
        NativeOAuthStatus.SIGNED_OUT -> R.string.native_oauth_status_signed_out
        NativeOAuthStatus.CONNECTED -> R.string.native_oauth_status_connected
        NativeOAuthStatus.EXPIRING_SOON -> R.string.native_oauth_status_expiring
        NativeOAuthStatus.EXPIRED -> if (oauth.refreshable) {
            R.string.native_oauth_status_expired_refreshable
        } else {
            R.string.native_oauth_status_expired
        }
    }
    Text(stringResource(statusLabel), style = MaterialTheme.typography.bodySmall)
    if (oauth.pendingLogin) {
        Text(stringResource(R.string.native_oauth_status_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
    oauth.expiresAtMillis?.let { expiresAt ->
        Text(stringResource(R.string.native_oauth_expires_at, nativeOAuthTime(expiresAt)),
            style = MaterialTheme.typography.bodySmall)
    }
    if (oauth.hasToken && !oauth.refreshable) {
        Text(stringResource(R.string.native_oauth_no_refresh_token),
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun nativeOAuthFailureLabel(failure: NativeOAuthUiFailure): Int = when (failure) {
    NativeOAuthUiFailure.BUSY -> R.string.native_oauth_failure_busy
    NativeOAuthUiFailure.PROFILE_MISSING -> R.string.native_oauth_failure_profile_missing
    NativeOAuthUiFailure.NOT_CONFIGURED -> R.string.native_oauth_failure_not_configured
    NativeOAuthUiFailure.INVALID_CONFIGURATION -> R.string.native_oauth_failure_invalid_configuration
    NativeOAuthUiFailure.CONFIG_CONFLICT -> R.string.native_oauth_failure_conflict
    NativeOAuthUiFailure.CONFIG_CHANGED -> R.string.native_oauth_failure_config_changed
    NativeOAuthUiFailure.CALLBACK_INVALID -> R.string.native_oauth_failure_callback
    NativeOAuthUiFailure.ACCESS_DENIED -> R.string.native_oauth_failure_access_denied
    NativeOAuthUiFailure.REJECTED -> R.string.native_oauth_failure_rejected
    NativeOAuthUiFailure.UNAVAILABLE -> R.string.native_oauth_failure_unavailable
    NativeOAuthUiFailure.NETWORK -> R.string.native_oauth_failure_network
    NativeOAuthUiFailure.TIMEOUT -> R.string.native_oauth_failure_timeout
    NativeOAuthUiFailure.MALFORMED -> R.string.native_oauth_failure_malformed
    NativeOAuthUiFailure.STORE -> R.string.native_oauth_failure_store
    NativeOAuthUiFailure.NO_BROWSER -> R.string.native_oauth_failure_no_browser
    NativeOAuthUiFailure.UNKNOWN -> R.string.native_oauth_failure_unknown
}

internal fun nativeOAuthNoticeLabel(notice: NativeOAuthUiNotice): Int = when (notice) {
    NativeOAuthUiNotice.CONFIG_SAVED -> R.string.native_oauth_notice_config_saved
    NativeOAuthUiNotice.LOGIN_STARTED -> R.string.native_oauth_notice_login_started
    NativeOAuthUiNotice.LOGIN_CANCELLED -> R.string.native_oauth_notice_login_cancelled
    NativeOAuthUiNotice.CONNECTED -> R.string.native_oauth_notice_connected
    NativeOAuthUiNotice.SIGNED_OUT -> R.string.native_oauth_notice_signed_out
}

@Composable
private fun nativeOAuthTime(millis: Long): String {
    val locale = Locale.getDefault()
    return remember(millis, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(millis))
    }
}
