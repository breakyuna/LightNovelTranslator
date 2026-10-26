package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.BugReport
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
) {
    val project = state.project
    LaunchedEffect(project?.ref) { refreshStorage() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("native-more-page"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_more), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.native_more_description), style = MaterialTheme.typography.bodySmall)
            }
        }
        item(key = "rules") {
            RulesCard(
                enabled = project != null && !state.busy,
                onRules = onRules,
            )
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
        if (onAppSettings != null) item(key = "app-settings") {
            MoreEntry(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.native_more_app_settings),
                description = stringResource(R.string.native_more_app_settings_description),
                enabled = !state.busy,
                onClick = onAppSettings,
                tag = "native-more-app-settings",
            )
        }
        if (onExpertSettings != null) item(key = "expert-settings") {
            MoreEntry(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.native_more_expert_settings),
                description = stringResource(R.string.native_more_expert_settings_description),
                enabled = !state.busy,
                onClick = onExpertSettings,
                tag = "native-more-expert-settings",
            )
        }
        if (onAgentSettings != null) item(key = "agent-settings") {
            MoreEntry(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.native_agent_settings),
                description = stringResource(R.string.native_auto_context_title),
                enabled = !state.busy,
                onClick = onAgentSettings,
                tag = "native-more-agent-settings",
            )
        }
        item(key = "notifications") {
            MoreEntry(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.native_more_notifications),
                description = stringResource(if (notificationsGranted) R.string.native_more_notifications_granted
                    else R.string.native_more_notifications_missing),
                enabled = !notificationsGranted && !state.busy,
                onClick = requestNotifications,
                tag = "native-more-notifications",
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
        item(key = "app-backup") {
            Card(Modifier.fillMaxWidth().testTag("native-more-app-backup")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_more_app_backup), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_more_app_backup_description), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_more_app_backup_credentials), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBackupExport, enabled = !state.busy,
                            modifier = Modifier.testTag("native-more-app-backup-export")) {
                            Text(stringResource(R.string.native_app_backup_export))
                        }
                        OutlinedButton(onClick = onBackupImport, enabled = !state.busy,
                            modifier = Modifier.testTag("native-more-app-backup-import")) {
                            Text(stringResource(R.string.native_app_backup_import))
                        }
                    }
                }
            }
        }
        item(key = "formats") {
            Card(Modifier.fillMaxWidth().testTag("native-more-formats")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.native_more_formats), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_more_formats_description), style = MaterialTheme.typography.bodySmall)
                    val formatLabels = state.supportedFormats.filter { isNativeFormatActive(it) }
                        .sortedBy { it.name }
                        .map { stringResource(nativeFormatLabelRes(it)) }
                    Text(formatLabels.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_format_frozen_notice),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "about") {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    headlineContent = { Text(stringResource(R.string.native_more_about)) },
                    supportingContent = { Text(stringResource(R.string.native_more_version, BuildConfig.VERSION_NAME)) },
                )
            }
        }
    }
}

@Composable
internal fun NativeThemeDialog(
    current: NativeThemeMode,
    dismiss: () -> Unit,
    select: (NativeThemeMode) -> Unit,
) {
    var mode by remember(current) { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
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
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
private fun RulesCard(
    enabled: Boolean,
    onRules: (NativeRuleSection) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("native-more-rules")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, null)
                Text(stringResource(R.string.native_more_rules), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.native_more_rules_description), style = MaterialTheme.typography.bodySmall)
            NativeRuleSection.entries.forEach { section ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { onRules(section) }
                        .testTag("native-more-rules-${section.slug}")
                        .padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(section.icon(), null, Modifier.size(20.dp))
                    Text(stringResource(section.title()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun NativeRuleSection.icon(): ImageVector = when (this) {
    NativeRuleSection.GLOSSARY -> Icons.Default.MenuBook
    NativeRuleSection.TEXT_PRESERVE -> Icons.Default.Spellcheck
    NativeRuleSection.PRE_REPLACE -> Icons.Default.Translate
    NativeRuleSection.POST_REPLACE -> Icons.Default.Sync
    NativeRuleSection.PROMPTS -> Icons.Default.EditNote
}

@Composable
private fun MoreEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
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
    Card(Modifier.fillMaxWidth().testTag("native-more-storage")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, null)
                Text(stringResource(R.string.native_more_storage), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.native_more_storage_description), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(project.name, style = MaterialTheme.typography.titleSmall)
            Text(project.assetPath.value, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_item_count, project.itemCount), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_more_revision, project.revision), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_more_assets, project.assets.size), style = MaterialTheme.typography.bodySmall)
            state.storageBytes?.let {
                Text(stringResource(R.string.native_more_storage_used,
                    android.text.format.Formatter.formatFileSize(context, it)), style = MaterialTheme.typography.bodySmall)
            }
            if (onFiles != null) OutlinedButton(onClick = onFiles, enabled = canManage,
                modifier = Modifier.testTag("native-more-storage-open")) {
                Text(stringResource(R.string.native_files_title))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = cleanup, enabled = canManage) {
                    Text(stringResource(R.string.native_more_cleanup))
                }
                OutlinedButton(onClick = refresh, enabled = canManage) {
                    Text(stringResource(R.string.native_refresh))
                }
                OutlinedButton(onClick = refreshStorage, enabled = !state.busy) {
                    Text(stringResource(R.string.native_more_recalculate))
                }
            }
        }
    }
}
