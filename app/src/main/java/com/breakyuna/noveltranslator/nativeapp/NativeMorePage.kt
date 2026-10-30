package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.breakyuna.noveltranslator.BuildConfig
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary

/**
 * The "More" destination keeps secondary management pages reachable on phones without
 * overloading the bottom bar. Every entry here is backed by a real application service; this
 * page owns no business state.
 */
@Composable
internal fun NativeMorePage(
    state: NativeProjectUiState,
    onRules: (NativeRuleSection) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onAppearance: () -> Unit,
    onLogs: () -> Unit,
    onSkills: () -> Unit,
    onBackupExport: () -> Unit,
    onBackupImport: () -> Unit,
    notificationsGranted: Boolean,
    requestNotifications: () -> Unit,
    cleanup: () -> Unit,
    refresh: () -> Unit,
    refreshStorage: () -> Unit,
    onAgentSettings: (() -> Unit)? = null,
    onAppSettings: (() -> Unit)? = null,
    onExpertSettings: (() -> Unit)? = null,
    onFiles: (() -> Unit)? = null,
    onFullArchiveExport: (() -> Unit)? = null,
    onFullArchiveImport: (() -> Unit)? = null,
    onLegacyExport: (() -> Unit)? = null,
    skillPanel: (@Composable () -> Unit)? = null,
) {
    val project = state.project
    var mcpVisible by remember { mutableStateOf(false) }
    LaunchedEffect(project?.ref) { refreshStorage() }
    Column(Modifier.fillMaxSize()) {
    skillPanel?.invoke()
    LazyColumn(
        Modifier.weight(1f).wrapContentSize(Alignment.TopCenter).widthIn(max = 1000.dp).fillMaxSize().testTag("native-more-page"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            NativeSectionCard(
                title = stringResource(R.string.native_more),
                badge = BuildConfig.VERSION_NAME,
                badgeTone = NativeTone.PRIMARY,
            ) {}
        }
        item(key = "models") {
            MoreEntry(
                icon = Icons.Default.Key,
                title = stringResource(R.string.native_more_models),
                description = stringResource(R.string.native_more_models_description),
                enabled = !state.busy,
                onClick = onModels,
                tag = "native-more-models",
            )
        }
        if (project != null) {
            item(key = "prompts") {
                MoreEntry(
                    icon = Icons.Default.EditNote,
                    title = stringResource(R.string.native_rules_prompts),
                    description = stringResource(R.string.native_more_prompts_description),
                    enabled = !state.busy && state.editor == null && state.manualBatch == null,
                    onClick = { onRules(NativeRuleSection.PROMPTS) },
                    tag = "native-more-rules-prompts",
                )
            }
            item(key = "settings") {
                MoreEntry(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.native_more_settings),
                    description = stringResource(R.string.native_more_settings_description),
                    enabled = !state.busy && state.editor == null && state.manualBatch == null,
                    onClick = onSettings,
                    tag = "native-more-settings",
                )
            }
            item(key = "storage") { StorageCard(project, state, cleanup, refresh, refreshStorage, onFiles) }
        }
        item(key = "appearance") {
            MoreEntry(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.native_more_appearance),
                description = stringResource(R.string.native_more_appearance_description),
                enabled = !state.busy,
                onClick = onAppearance,
                tag = "native-more-appearance",
            )
        }
        if (onAppSettings != null) {
            item(key = "app-settings") {
                MoreEntry(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.native_more_app_settings),
                    description = stringResource(R.string.native_more_app_settings_description),
                    enabled = !state.busy,
                    onClick = onAppSettings,
                    tag = "native-more-app-settings",
                )
            }
        }
        if (onExpertSettings != null) {
            item(key = "expert-settings") {
                MoreEntry(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.native_more_expert_settings),
                    description = stringResource(R.string.native_more_expert_settings_description),
                    enabled = !state.busy,
                    onClick = onExpertSettings,
                    tag = "native-more-expert-settings",
                )
            }
        }
        if (onAgentSettings != null) {
            item(key = "agent-settings") {
                MoreEntry(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.native_agent_settings),
                    description = stringResource(R.string.native_auto_context_title),
                    enabled = !state.busy,
                    onClick = onAgentSettings,
                    tag = "native-more-agent-settings",
                )
            }
        }
        item(key = "notifications") {
            MoreEntry(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.native_more_notifications),
                description = stringResource(
                    if (notificationsGranted) R.string.native_more_notifications_granted
                    else R.string.native_more_notifications_missing,
                ),
                enabled = !notificationsGranted && !state.busy,
                onClick = requestNotifications,
                tag = "native-more-notifications",
                showDescription = true,
            )
        }
        item(key = "logs") {
            MoreEntry(
                icon = Icons.Default.BugReport,
                title = stringResource(R.string.native_more_logs),
                description = stringResource(R.string.native_more_logs_description),
                enabled = !state.busy,
                onClick = onLogs,
                tag = "native-more-logs",
            )
        }
        item(key = "skills") {
            MoreEntry(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.native_more_skills),
                description = stringResource(R.string.native_more_skills_description),
                enabled = !state.busy,
                onClick = onSkills,
                tag = "native-more-skills",
            )
        }
        item(key = "mcp") {
            MoreEntry(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.native_more_mcp),
                description = stringResource(R.string.native_more_mcp_description),
                enabled = !state.busy,
                onClick = { mcpVisible = true },
                tag = "native-more-mcp",
            )
        }
        item(key = "app-backup") {
            NativeSectionCard(
                modifier = Modifier.testTag("native-more-app-backup"),
                title = stringResource(R.string.native_more_app_backup),
                subtitle = stringResource(R.string.native_more_app_backup_description),
            ) {
                NativeBanner(
                    text = stringResource(R.string.native_more_app_backup_credentials),
                    tone = NativeTone.NEUTRAL,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBackupExport,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp).testTag("native-more-app-backup-export"),
                    ) {
                        Text(stringResource(R.string.native_app_backup_export))
                    }
                    OutlinedButton(
                        onClick = onBackupImport,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp).testTag("native-more-app-backup-import"),
                    ) {
                        Text(stringResource(R.string.native_app_backup_import))
                    }
                }
            }
        }
        if (onFullArchiveExport != null && onFullArchiveImport != null) {
            item(key = "full-archive") {
                NativeSectionCard(
                    modifier = Modifier.testTag("native-more-full-archive"),
                    title = stringResource(R.string.native_more_full_archive),
                    subtitle = stringResource(R.string.native_more_full_archive_description),
                ) {
                    NativeBanner(
                        text = stringResource(R.string.native_more_full_archive_boundary),
                        tone = NativeTone.WARNING,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onFullArchiveExport,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp).testTag("native-more-full-archive-export"),
                        ) {
                            Text(stringResource(R.string.native_full_archive_export))
                        }
                        OutlinedButton(
                            onClick = onFullArchiveImport,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp).testTag("native-more-full-archive-import"),
                        ) {
                            Text(stringResource(R.string.native_full_archive_import))
                        }
                    }
                }
            }
        }
        if (onLegacyExport != null) {
            item(key = "legacy-export") {
                NativeSectionCard(
                    modifier = Modifier.testTag("native-more-legacy-export"),
                    title = stringResource(R.string.native_more_legacy_export),
                    subtitle = stringResource(R.string.native_more_legacy_export_description),
                ) {
                    NativeBanner(
                        text = stringResource(R.string.native_more_legacy_export_boundary),
                        tone = NativeTone.NEUTRAL,
                    )
                    OutlinedButton(
                        onClick = onLegacyExport,
                        enabled = !state.busy,
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("native-more-legacy-export-open"),
                    ) {
                        Text(stringResource(R.string.native_legacy_export_open))
                    }
                }
            }
        }
        item(key = "formats") {
            val formatLabels = state.supportedFormats.filter { isNativeFormatActive(it) }
                .sortedBy { it.name }
                .map { stringResource(nativeFormatLabelRes(it)) }
            NativeSectionCard(
                modifier = Modifier.testTag("native-more-formats"),
                title = stringResource(R.string.native_more_formats),
                subtitle = stringResource(R.string.native_more_formats_description),
                badge = formatLabels.size.toString(),
                badgeTone = NativeTone.NEUTRAL,
            ) {
                Text(formatLabels.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(R.string.native_format_frozen_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "about") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    leadingContent = {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(Icons.Default.Info, null, Modifier.padding(8.dp))
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.native_more_about)) },
                    supportingContent = { Text(stringResource(R.string.native_more_version, BuildConfig.VERSION_NAME)) },
                )
            }
        }
    }
    }
    if (mcpVisible) {
        val model: NativeProjectViewModel = viewModel()
        NativeMcpSettingsDialog(model.mcpActions, dismiss = { mcpVisible = false })
    }
}

@Composable
internal fun NativeThemeDialog(
    current: NativeThemeMode,
    dismiss: () -> Unit,
    select: (NativeThemeMode) -> Unit,
) {
    var mode by remember(current) { mutableStateOf(current) }
    NativeContentDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_more_appearance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NativeThemeMode.entries.forEach { candidate ->
                    val label = when (candidate) {
                        NativeThemeMode.SYSTEM -> R.string.native_theme_system
                        NativeThemeMode.LIGHT -> R.string.native_theme_light
                        NativeThemeMode.DARK -> R.string.native_theme_dark
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { mode = candidate },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == candidate, onClick = { mode = candidate })
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { select(mode); dismiss() }) { Text(stringResource(R.string.native_save)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
        },
    )
}

@Composable
private fun MoreEntry(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    showDescription: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(icon, null, Modifier.padding(8.dp))
                }
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            headlineContent = {
                Text(title, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = if (showDescription) ({ Text(description, style = MaterialTheme.typography.bodySmall) }) else null,
        )
    }
}

@Composable
private fun StorageCard(
    project: TextProjectSummary,
    state: NativeProjectUiState,
    cleanup: () -> Unit,
    refresh: () -> Unit,
    refreshStorage: () -> Unit,
    onFiles: (() -> Unit)?,
) {
    val context = LocalContext.current
    val canManage = !state.busy && state.editor == null && state.manualBatch == null
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-more-storage"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.Folder, null, Modifier.padding(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.native_more_storage), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.native_more_storage_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(project.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = project.assetPath.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeStatusBadge(stringResource(R.string.native_item_count, project.itemCount), tone = NativeTone.PRIMARY)
                NativeStatusBadge(stringResource(R.string.native_more_revision, project.revision), tone = NativeTone.NEUTRAL)
                NativeStatusBadge(stringResource(R.string.native_more_assets, project.assets.size), tone = NativeTone.SECONDARY)
            }
            state.storageBytes?.let {
                Text(
                    text = stringResource(
                        R.string.native_more_storage_used,
                        android.text.format.Formatter.formatFileSize(context, it),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onFiles != null) {
                OutlinedButton(
                    onClick = onFiles,
                    enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("native-more-storage-open"),
                ) {
                    Text(stringResource(R.string.native_files_title))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = cleanup, enabled = canManage, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.native_more_cleanup), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = refresh, enabled = canManage, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.native_refresh), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = refreshStorage, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.native_more_recalculate), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
