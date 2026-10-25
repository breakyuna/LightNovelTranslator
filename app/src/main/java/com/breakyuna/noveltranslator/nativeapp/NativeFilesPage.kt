package com.breakyuna.noveltranslator.nativeapp

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary

/**
 * Routed U15 files/output/storage page. It is project-scoped, reads only the bounded project
 * summary and an on-demand storage projection, and delegates every write/export to the
 * existing application service. No second writer or destructive cleanup is introduced here.
 */
@Composable
internal fun NativeFilesPage(
    state: NativeProjectUiState,
    back: () -> Unit,
    exportAsset: (Long) -> Unit,
    exportBilingual: ((Long) -> Unit)? = null,
    saveProject: (() -> Unit)? = null,
    exportBackup: (() -> Unit)? = null,
    exportAllBackups: (() -> Unit)? = null,
    cleanup: () -> Unit,
    refreshStorage: () -> Unit,
    shareAll: (() -> Unit)? = null,
    loadInventory: (suspend (LocalProjectRef, Long) -> ProjectFileInventory)? = null,
) {
    val project = state.project ?: return
    val context = LocalContext.current
    val formatSize: (Long) -> String = { bytes -> Formatter.formatFileSize(context, bytes) }
    var inventory by remember(project.ref, project.revision) { mutableStateOf<ProjectFileInventory?>(null) }
    var inventoryFailed by remember(project.ref, project.revision) { mutableStateOf(false) }
    LaunchedEffect(project.ref, project.revision) {
        if (loadInventory != null) try {
            inventory = loadInventory(project.ref, project.revision)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { inventoryFailed = true }
    }
    val metadata = remember(inventory) { inventory?.entries?.associateBy { it.assetId }.orEmpty() }
    val busy = state.busy
    LaunchedEffect(project.ref) { refreshStorage() }
    LazyColumn(Modifier.fillMaxSize().testTag("native-files-page"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = back, modifier = Modifier.testTag("native-files-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Text(stringResource(R.string.native_back))
                    }
                    Text(stringResource(R.string.native_files_title), style = MaterialTheme.typography.headlineSmall)
                }
                Text(stringResource(R.string.native_files_description), style = MaterialTheme.typography.bodySmall)
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(project.assetPath.value, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_item_count, project.itemCount),
                    style = MaterialTheme.typography.bodySmall)
                if (shareAll != null) OutlinedButton(onClick = shareAll, enabled = !busy,
                    modifier = Modifier.testTag("native-files-share-all")) {
                    Text(stringResource(R.string.native_files_share_all))
                }
                HorizontalDivider()
            }
        }
        item(key = "assets-header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_files_assets), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.native_files_assets_description), style = MaterialTheme.typography.bodySmall)
                if (inventoryFailed) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
            }
        }
        if (project.assets.isEmpty()) item(key = "assets-empty") {
            Text(stringResource(R.string.native_files_empty_assets))
        }
        items(project.assets, key = { "files-asset-${it.id}" }) { asset ->
            AssetInventoryCard(project.ref.projectId.value, project.ref.instanceId.value,
                project.ref.generation.value, asset, busy, exportAsset, exportBilingual, formatSize, metadata[asset.id])
        }
        if (saveProject != null || exportBackup != null || exportAllBackups != null) item(key = "actions") {
            Card(Modifier.fillMaxWidth().testTag("native-files-actions")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_files_actions), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_files_actions_description),
                        style = MaterialTheme.typography.bodySmall)
                    saveProject?.let {
                        OutlinedButton(onClick = it, enabled = !busy,
                            modifier = Modifier.testTag("native-files-save-lnt")) {
                            Text(stringResource(R.string.native_save_project))
                        }
                    }
                    exportBackup?.let {
                        OutlinedButton(onClick = it, enabled = !busy,
                            modifier = Modifier.testTag("native-files-backup")) {
                            Text(stringResource(R.string.native_backup_export))
                        }
                    }
                    exportAllBackups?.let {
                        OutlinedButton(onClick = it, enabled = !busy,
                            modifier = Modifier.testTag("native-files-backup-all")) {
                            Text(stringResource(R.string.native_project_set_export))
                        }
                    }
                }
            }
        }
        item(key = "storage") {
            Card(Modifier.fillMaxWidth().testTag("native-files-storage")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.native_files_storage), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_files_storage_description),
                        style = MaterialTheme.typography.bodySmall)
                    val usage = state.storageUsage
                    if (usage == null) {
                        Text(stringResource(R.string.native_files_storage_unavailable),
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        StorageRow(R.string.native_files_storage_project, usage.projectBytes, formatSize)
                        StorageRow(R.string.native_files_storage_materials, usage.workspaceMaterialBytes, formatSize)
                        StorageRow(R.string.native_files_storage_artifacts, usage.workspaceArtifactBytes, formatSize)
                        StorageRow(R.string.native_files_storage_cache, usage.cacheBytes, formatSize)
                        StorageRow(R.string.native_files_storage_other, usage.otherFilesBytes, formatSize)
                        HorizontalDivider()
                        StorageRow(R.string.native_files_storage_total, usage.totalPrivateBytes, formatSize)
                    }
                    TextButton(onClick = refreshStorage, enabled = !busy,
                        modifier = Modifier.testTag("native-files-recalculate")) {
                        Text(stringResource(R.string.native_more_recalculate))
                    }
                }
            }
        }
        item(key = "retention") {
            Card(Modifier.fillMaxWidth().testTag("native-files-retention")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.native_files_retention_title),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_files_retention_body),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_files_retention_failure),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = cleanup, enabled = !busy,
                        modifier = Modifier.testTag("native-files-cleanup")) {
                        Text(stringResource(R.string.native_more_cleanup))
                    }
                }
            }
        }
        item(key = "backups") {
            Card(Modifier.fillMaxWidth().testTag("native-files-backup-boundaries")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_files_backup_title),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_files_backup_warning),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    BackupBoundary(R.string.native_files_backup_lnt_title, R.string.native_files_backup_lnt_body,
                        "native-files-boundary-lnt")
                    BackupBoundary(R.string.native_files_backup_project_title,
                        R.string.native_files_backup_project_body, "native-files-boundary-project")
                    BackupBoundary(R.string.native_files_backup_all_title, R.string.native_files_backup_all_body,
                        "native-files-boundary-all")
                    BackupBoundary(R.string.native_files_backup_app_title, R.string.native_files_backup_app_body,
                        "native-files-boundary-app")
                }
            }
        }
        if (busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun AssetInventoryCard(
    projectId: String,
    instanceId: String,
    generation: Long,
    asset: ProjectAssetSummary,
    busy: Boolean,
    exportAsset: (Long) -> Unit,
    exportBilingual: ((Long) -> Unit)?,
    formatSize: (Long) -> String,
    inventory: AssetFileInventory? = null,
) {
    var showFullSha by rememberSaveable(projectId, instanceId, generation, asset.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val available = inventory?.source != AssetSourceAvailability.INCOMPLETE
    val sha = if (showFullSha) asset.sha256 else asset.sha256.take(12) + "…"
    Card(Modifier.fillMaxWidth().testTag("native-file-${asset.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(asset.path.value, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.native_files_format, filesFormatLabel(asset.format)))
            Text(stringResource(R.string.native_files_items, asset.itemCount))
            val updated = inventory?.updatedAtMillis
            Text(stringResource(R.string.native_files_updated, if (updated == null)
                stringResource(R.string.native_files_time_unknown) else
                android.text.format.DateUtils.formatDateTime(context, updated,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)))
            Text(stringResource(when (inventory?.source) {
                AssetSourceAvailability.PRESENT -> R.string.native_files_source_present
                AssetSourceAvailability.INCOMPLETE -> R.string.native_files_source_incomplete
                null -> R.string.native_files_source_unchecked
            }))
            Text(stringResource(R.string.native_files_size, formatSize(asset.originalSize)))
            Text(stringResource(if (asset.enabled) R.string.native_files_enabled
                else R.string.native_files_disabled),
                color = if (asset.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.native_files_sha, sha),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("native-file-sha-${asset.id}"))
            TextButton(onClick = { showFullSha = !showFullSha }, enabled = !busy,
                modifier = Modifier.testTag("native-file-sha-toggle-${asset.id}")) {
                Text(stringResource(if (showFullSha) R.string.native_files_sha_hide
                    else R.string.native_files_sha_show))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportAsset(asset.id) }, enabled = !busy && available,
                    modifier = Modifier.testTag("native-file-export-${asset.id}")) {
                    Text(stringResource(R.string.native_export_asset))
                }
                exportBilingual?.let { export ->
                    OutlinedButton(onClick = { export(asset.id) }, enabled = !busy && available,
                        modifier = Modifier.testTag("native-file-export-bilingual-${asset.id}")) {
                        Text(stringResource(R.string.native_export_bilingual))
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageRow(label: Int, bytes: Long, formatSize: (Long) -> String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
        Text(formatSize(bytes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BackupBoundary(title: Int, body: Int, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun filesFormatLabel(format: ProjectAssetFormat): String = when (format) {
    ProjectAssetFormat.RPGMAKER_RGSS -> stringResource(R.string.native_format_rpgmaker_rgss)
    else -> format.name
}

