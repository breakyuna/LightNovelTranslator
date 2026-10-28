package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.breakyuna.noveltranslator.nativecore.storage.InvalidMcpCredential
import com.breakyuna.noveltranslator.nativecore.storage.InvalidMcpSettings
import com.breakyuna.noveltranslator.nativecore.storage.McpSettingsConflict
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpServerEntry
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpServerRecord
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpSettingsSnapshot
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpException
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpToolCatalog
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Strict UI input for one MCP server. Pure conversion keeps save commands deterministic and
 * idempotent: the same draft always produces the same record and fingerprint.
 */
internal data class NativeMcpServerDraft(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val allowPrivateNetwork: Boolean,
    val allowedToolsText: String,
    val timeoutMillisText: String,
    val maxToolsText: String,
) {
    fun toRecord(): NativeMcpServerRecord = NativeMcpServerRecord(
        id = id.trim(),
        name = name.trim(),
        url = url.trim(),
        enabled = enabled,
        allowPrivateNetwork = allowPrivateNetwork,
        allowedTools = allowedTools(),
        timeoutMillis = timeoutMillisText.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Invalid MCP timeout"),
        maxTools = maxToolsText.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Invalid MCP tool limit"),
    )

    private fun allowedTools(): List<String> = allowedToolsText
        .split(',', '\n', '\r', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    companion object {
        fun from(entry: NativeMcpServerEntry): NativeMcpServerDraft = NativeMcpServerDraft(
            id = entry.config.id,
            name = entry.config.name,
            url = entry.config.url,
            enabled = entry.config.enabled,
            allowPrivateNetwork = entry.config.allowPrivateNetwork,
            allowedToolsText = entry.config.allowedTools.sorted().joinToString("\n"),
            timeoutMillisText = entry.config.timeoutMillis.toString(),
            maxToolsText = entry.config.maxTools.toString(),
        )

        fun create(): NativeMcpServerDraft = NativeMcpServerDraft(
            id = "",
            name = "",
            url = "",
            enabled = true,
            allowPrivateNetwork = false,
            allowedToolsText = "",
            timeoutMillisText = "30000",
            maxToolsText = "64",
        )
    }
}

private data class McpErrorText(val resource: Int, val detail: String? = null)

private fun nativeMcpErrorText(failure: Throwable?): McpErrorText? = when (failure) {
    null -> null
    is NativeMcpBusy -> McpErrorText(R.string.native_mcp_busy)
    is McpSettingsConflict -> McpErrorText(R.string.native_mcp_conflict)
    is InvalidMcpSettings -> McpErrorText(R.string.native_mcp_storage_error)
    is InvalidMcpCredential -> McpErrorText(R.string.native_mcp_credential_error)
    is NativeMcpRejected -> McpErrorText(R.string.native_mcp_error, failure.code)
    is McpException -> McpErrorText(R.string.native_mcp_error, failure.kind.name)
    is IllegalArgumentException -> McpErrorText(R.string.native_mcp_invalid)
    else -> McpErrorText(R.string.native_mcp_error, "INTERNAL")
}

private class McpToolView(val serverName: String, val catalog: McpToolCatalog)

/**
 * MCP configuration editor. The token field is always blank, write-only and never rendered
 * from storage. Mutations are serialized through the graph controller and show a visible
 * error when an Agent run or a stale fingerprint rejects the change.
 */
@Composable
internal fun NativeMcpSettingsDialog(
    actions: NativeMcpSettingsActions?,
    dismiss: () -> Unit,
) {
    if (actions == null) {
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            NativeDialogSurface(
                title = { Text(stringResource(R.string.native_mcp_title)) },
                text = {
                    Text(stringResource(R.string.native_mcp_unavailable), color = MaterialTheme.colorScheme.error)
                },
                confirmButton = {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.native_mcp_close)) }
                },
            )
        }
        return
    }

    var snapshot by remember { mutableStateOf<NativeMcpSettingsSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<NativeMcpServerDraft?>(null) }
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<NativeMcpServerEntry?>(null) }
    var toolsView by remember { mutableStateOf<McpToolView?>(null) }
    var toolsLoading by remember { mutableStateOf(false) }
    var toolsFailed by remember { mutableStateOf<Throwable?>(null) }
    var probe by remember { mutableStateOf<NativeMcpConnectionProbe?>(null) }
    var probeFor by remember { mutableStateOf<String?>(null) }
    var tokenDraft by remember { mutableStateOf("") }
    var tokenConfigured by remember { mutableStateOf(false) }
    var tokenBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        failed = false
        try {
            snapshot = actions.load()
            failure = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed = true
            failure = error
        } finally {
            loading = false
        }
    }

    fun mutate(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                notice = null
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                failed = true
            }
        }
    }

    fun edit(entry: NativeMcpServerEntry?) {
        editing = entry?.let { NativeMcpServerDraft.from(it) } ?: NativeMcpServerDraft.create()
        creating = entry == null
        failure = null
        tokenDraft = ""
        tokenConfigured = false
        probe = null
        probeFor = null
    }

    LaunchedEffect(Unit) { reload() }
    val editingId = editing?.takeUnless { creating }?.id
    LaunchedEffect(editingId, snapshot?.fingerprint) {
        if (editingId == null) return@LaunchedEffect
        tokenConfigured = try {
            actions.tokenConfigured(editingId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    deleteConfirm?.let { fixed ->
        Dialog(
            onDismissRequest = { deleteConfirm = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            NativeDialogSurface(
                title = { Text(stringResource(R.string.native_mcp_delete)) },
                text = { Text(stringResource(R.string.native_mcp_delete_confirm, fixed.config.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val current = snapshot ?: return@TextButton
                            deleteConfirm = null
                            scope.launch {
                                try {
                                    snapshot = actions.deleteServer(fixed.config.id, current.fingerprint)
                                    editing = null
                                    creating = false
                                    probe = null
                                    probeFor = null
                                    notice = R.string.native_mcp_deleted
                                    failure = null
                                    failed = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    failure = error
                                }
                            }
                        },
                        modifier = Modifier.testTag("native-mcp-delete-confirm"),
                    ) { Text(stringResource(R.string.native_mcp_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(R.string.native_cancel)) }
                },
            )
        }
        return
    }

    Dialog(
        onDismissRequest = {
            when {
                saving -> Unit
                toolsView != null -> toolsView = null
                editing != null -> { editing = null; creating = false }
                else -> dismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val current = snapshot
        if (editing != null) {
            val draft = editing!!
            val valid = remember(draft) { runCatching { draft.toRecord() }.isSuccess }
            val existingId = draft.id.takeIf { !creating }
            NativeDialogSurface(
                modifier = Modifier.testTag("native-mcp-editor"),
                title = {
                    Text(stringResource(if (creating) R.string.native_mcp_add else R.string.native_mcp_edit))
                },
                text = {
                    Column(
                        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.native_mcp_secret_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!valid) {
                            Text(
                                stringResource(R.string.native_mcp_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(
                            value = draft.id,
                            onValueChange = { if (it.length <= 64) editing = draft.copy(id = it) },
                            label = { Text(stringResource(R.string.native_mcp_id)) },
                            supportingText = { Text(stringResource(R.string.native_mcp_id_hint)) },
                            singleLine = true,
                            enabled = !saving && existingId == null,
                            modifier = Modifier.fillMaxWidth().testTag("native-mcp-id"),
                        )
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { if (it.length <= 128) editing = draft.copy(name = it) },
                            label = { Text(stringResource(R.string.native_mcp_name)) },
                            singleLine = true,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-mcp-name"),
                        )
                        OutlinedTextField(
                            value = draft.url,
                            onValueChange = { if (it.length <= 2_048) editing = draft.copy(url = it) },
                            label = { Text(stringResource(R.string.native_mcp_url)) },
                            supportingText = { Text(stringResource(R.string.native_mcp_url_hint)) },
                            singleLine = true,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-mcp-url"),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_mcp_enabled), Modifier.weight(1f))
                            Switch(
                                checked = draft.enabled,
                                onCheckedChange = { editing = draft.copy(enabled = it) },
                                enabled = !saving,
                                modifier = Modifier.testTag("native-mcp-enabled"),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_mcp_private_network), Modifier.weight(1f))
                            Switch(
                                checked = draft.allowPrivateNetwork,
                                onCheckedChange = { editing = draft.copy(allowPrivateNetwork = it) },
                                enabled = !saving,
                                modifier = Modifier.testTag("native-mcp-private-network"),
                            )
                        }
                        Text(
                            stringResource(R.string.native_mcp_private_network_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = draft.allowedToolsText,
                            onValueChange = {
                                if (it.length <= 64 * 1024) editing = draft.copy(allowedToolsText = it)
                            },
                            label = { Text(stringResource(R.string.native_mcp_allowed_tools)) },
                            supportingText = { Text(stringResource(R.string.native_mcp_allowed_tools_hint)) },
                            minLines = 2,
                            maxLines = 6,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().testTag("native-mcp-allowed-tools"),
                        )
                        Text(
                            stringResource(R.string.native_mcp_search_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.timeoutMillisText,
                                onValueChange = {
                                    if (it.length <= 7 && it.all(Char::isDigit)) {
                                        editing = draft.copy(timeoutMillisText = it)
                                    }
                                },
                                label = { Text(stringResource(R.string.native_mcp_timeout)) },
                                singleLine = true,
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f).testTag("native-mcp-timeout"),
                            )
                            OutlinedTextField(
                                value = draft.maxToolsText,
                                onValueChange = {
                                    if (it.length <= 3 && it.all(Char::isDigit)) {
                                        editing = draft.copy(maxToolsText = it)
                                    }
                                },
                                label = { Text(stringResource(R.string.native_mcp_max_tools)) },
                                singleLine = true,
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f).testTag("native-mcp-max-tools"),
                            )
                        }
                        if (existingId != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                stringResource(
                                    if (tokenConfigured) R.string.native_mcp_token_set
                                    else R.string.native_mcp_token_not_set,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (tokenConfigured) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("native-mcp-token-state"),
                            )
                            OutlinedTextField(
                                value = tokenDraft,
                                onValueChange = { if (it.length <= 8_000) tokenDraft = it },
                                label = { Text(stringResource(R.string.native_mcp_token)) },
                                supportingText = { Text(stringResource(R.string.native_mcp_token_hint)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !tokenBusy,
                                modifier = Modifier.fillMaxWidth().testTag("native-mcp-token"),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        val serverId = existingId
                                        val token = tokenDraft
                                        if (serverId.isNullOrBlank() || token.isEmpty()) return@TextButton
                                        scope.launch {
                                            tokenBusy = true
                                            try {
                                                actions.saveToken(serverId, token)
                                                tokenDraft = ""
                                                tokenConfigured = true
                                                notice = R.string.native_mcp_token_saved
                                                failure = null
                                                failed = false
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                failure = error
                                            } finally {
                                                tokenBusy = false
                                            }
                                        }
                                    },
                                    enabled = !tokenBusy && tokenDraft.isNotEmpty(),
                                    modifier = Modifier.testTag("native-mcp-token-save"),
                                ) { Text(stringResource(R.string.native_mcp_token_save)) }
                                TextButton(
                                    onClick = {
                                        val serverId = existingId
                                        if (serverId.isNullOrBlank()) return@TextButton
                                        scope.launch {
                                            tokenBusy = true
                                            try {
                                                actions.clearToken(serverId)
                                                tokenDraft = ""
                                                tokenConfigured = false
                                                notice = R.string.native_mcp_token_cleared
                                                failure = null
                                                failed = false
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                failure = error
                                            } finally {
                                                tokenBusy = false
                                            }
                                        }
                                    },
                                    enabled = !tokenBusy && tokenConfigured,
                                    modifier = Modifier.testTag("native-mcp-token-clear"),
                                ) { Text(stringResource(R.string.native_mcp_token_clear)) }
                            }
                        }
                        nativeMcpErrorText(failure)?.let { error ->
                            Text(
                                text = if (error.detail == null) stringResource(error.resource)
                                else stringResource(error.resource, error.detail),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val base = snapshot ?: return@TextButton
                            scope.launch {
                                saving = true
                                try {
                                    draft.toRecord()
                                    snapshot = actions.saveServer(draft, base.fingerprint)
                                    editing = null
                                    creating = false
                                    notice = R.string.native_mcp_saved
                                    failure = null
                                    failed = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    failure = error
                                    runCatching { snapshot = actions.load() }
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = valid && !saving,
                        modifier = Modifier.testTag("native-mcp-save"),
                    ) { Text(stringResource(if (saving) R.string.native_settings_saving else R.string.native_save)) }
                },
                dismissButton = {
                    TextButton(enabled = !saving, onClick = { editing = null; creating = false }) {
                        Text(stringResource(R.string.native_cancel))
                    }
                },
            )
            return@Dialog
        }

        toolsView?.let { view ->
            NativeDialogSurface(
                modifier = Modifier.testTag("native-mcp-tools"),
                title = { Text(stringResource(R.string.native_mcp_tools_title, view.serverName)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.native_mcp_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (toolsLoading) NativeBusyIndicator()
                        nativeMcpErrorText(toolsFailed)?.let { error ->
                            Text(
                                text = if (error.detail == null) stringResource(error.resource)
                                else stringResource(error.resource, error.detail),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        LazyColumn(
                            Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(view.catalog.tools, key = { it.externalName }) { tool -> mcpToolRow(tool) }
                            if (!toolsLoading && view.catalog.tools.isEmpty()) {
                                item(key = "empty") { Text(stringResource(R.string.native_mcp_tools_empty)) }
                            }
                        }
                        if (view.catalog.truncated) {
                            Text(stringResource(R.string.native_mcp_tools_truncated),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { toolsView = null }) { Text(stringResource(R.string.native_mcp_close)) }
                },
            )
            return@Dialog
        }

        NativeDialogSurface(
            modifier = Modifier.testTag("native-mcp-catalog"),
            title = { Text(stringResource(R.string.native_mcp_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.native_mcp_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.native_mcp_secret_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    notice?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (loading) {
                        NativeBusyIndicator()
                        Text(stringResource(R.string.native_mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    if (failed) {
                        nativeMcpErrorText(failure)?.let { error ->
                            Text(
                                text = if (error.detail == null) stringResource(error.resource)
                                else stringResource(error.resource, error.detail),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { scope.launch { reload() } }) {
                            Text(stringResource(R.string.native_mcp_retry))
                        }
                    }
                    current?.servers?.takeIf { it.isEmpty() }?.let {
                        Text(stringResource(R.string.native_mcp_empty), style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current?.servers.orEmpty(), key = { it.config.id }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().testTag("native-mcp-server-${entry.config.id}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.config.name, style = MaterialTheme.typography.titleSmall)
                                            Text(entry.config.id, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            stringResource(
                                                if (entry.config.enabled) R.string.native_mcp_enabled
                                                else R.string.native_mcp_disabled,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        Switch(
                                            checked = entry.config.enabled,
                                            onCheckedChange = { checked ->
                                                val value = snapshot ?: return@Switch
                                                mutate {
                                                    actions.setEnabled(entry.config.id, checked, value.fingerprint)
                                                }
                                            },
                                            modifier = Modifier.testTag("native-mcp-toggle-${entry.config.id}"),
                                        )
                                    }
                                    Text(entry.config.url, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        NativeStatusBadge(
                                            stringResource(R.string.native_mcp_tool_count,
                                                entry.config.allowedTools.size),
                                            tone = NativeTone.NEUTRAL,
                                        )
                                        NativeStatusBadge(
                                            stringResource(
                                                if (entry.config.allowPrivateNetwork)
                                                    R.string.native_mcp_private_enabled
                                                else R.string.native_mcp_private_disabled,
                                            ),
                                            tone = if (entry.config.allowPrivateNetwork) NativeTone.WARNING
                                            else NativeTone.NEUTRAL,
                                        )
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { edit(entry) }, enabled = !loading) {
                                            Text(stringResource(R.string.native_mcp_edit))
                                        }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        probe = actions.testConnection(entry.config.id)
                                                        probeFor = entry.config.id
                                                        failure = null
                                                        failed = false
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (error: Exception) {
                                                        probe = null
                                                        probeFor = entry.config.id
                                                        failure = error
                                                    }
                                                }
                                            },
                                            enabled = !loading,
                                            modifier = Modifier.testTag("native-mcp-test-${entry.config.id}"),
                                        ) {
                                            Text(stringResource(R.string.native_mcp_test))
                                        }
                                        TextButton(
                                            onClick = {
                                                toolsLoading = true
                                                toolsFailed = null
                                                toolsView = McpToolView(
                                                    entry.config.name,
                                                    McpToolCatalog(entry.config.id, emptyList(), false),
                                                )
                                                scope.launch {
                                                    try {
                                                        val catalog = actions.listTools(entry.config.id)
                                                        toolsView = McpToolView(entry.config.name, catalog)
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (error: Exception) {
                                                        toolsFailed = error
                                                    } finally {
                                                        toolsLoading = false
                                                    }
                                                }
                                            },
                                            enabled = !loading,
                                            modifier = Modifier.testTag("native-mcp-list-${entry.config.id}"),
                                        ) {
                                            Text(stringResource(R.string.native_mcp_list_tools))
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { deleteConfirm = entry },
                                            enabled = !loading,
                                            modifier = Modifier.testTag("native-mcp-delete-${entry.config.id}"),
                                        ) {
                                            Text(stringResource(R.string.native_mcp_delete))
                                        }
                                    }
                                    if (probeFor == entry.config.id) {
                                        probe?.let { value ->
                                            val toolsLabel = stringResource(
                                                if (value.toolsSupported) R.string.native_mcp_connection_tools_yes
                                                else R.string.native_mcp_connection_tools_no,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.native_mcp_connection_ok,
                                                    value.protocolVersion,
                                                    toolsLabel,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        nativeMcpErrorText(failure)?.let { error ->
                                            Text(
                                                text = if (error.detail == null) stringResource(error.resource)
                                                else stringResource(error.resource, error.detail),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { edit(null) }, modifier = Modifier.testTag("native-mcp-add")) {
                        Text(stringResource(R.string.native_mcp_add))
                    }
                    TextButton(onClick = { scope.launch { reload() } }, enabled = !loading) {
                        Text(stringResource(R.string.native_mcp_retry))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.native_mcp_close)) }
            },
        )
    }
}

@Composable
private fun mcpToolRow(tool: McpToolDescriptor) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.externalName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        if (tool.readOnly) R.string.native_mcp_tool_read_only
                        else R.string.native_mcp_tool_writable,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tool.readOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            if (tool.description.isNotBlank()) {
                Text(tool.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
