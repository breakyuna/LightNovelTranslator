package com.breakyuna.noveltranslator.nativeapp

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssProject
import java.util.Collections
import kotlinx.coroutines.CancellationException

internal const val NativeRgssInventoryDialogTag = "native-rgss-inventory-dialog"
internal const val NativeRgssLoadingTag = "native-rgss-loading"
internal const val NativeRgssFailedTag = "native-rgss-failed"
internal const val NativeRgssInventoryTag = "native-rgss-inventory"
internal fun nativeRgssEntryTag(path: String): String = "native-rgss-entry-$path"

internal enum class NativeRgssEntryKind { SCRIPT, DATA, OTHER }

/** One archive member's metadata. Member bodies never leave the read-only inventory path. */
internal class NativeRgssEntry(
    val path: String,
    val size: Long,
    val kind: NativeRgssEntryKind,
    val compressed: Boolean,
) {
    init { require(path.isNotEmpty() && size >= 0) }

    override fun toString(): String =
        "NativeRgssEntry(path=$path,size=$size,kind=$kind,compressed=$compressed)"
}

/**
 * Read-only projection of an RGSS archive. Entries are sorted by path for stable display; counts
 * and sizes are derived once and no member body is retained.
 */
internal class NativeRgssInventory(
    val version: Int,
    entries: List<NativeRgssEntry>,
    val archiveBytes: Long,
) {
    val entries: List<NativeRgssEntry> = Collections.unmodifiableList(ArrayList(entries.sortedBy { it.path }))
    val scriptCount: Int = this.entries.count { it.kind == NativeRgssEntryKind.SCRIPT }
    val dataCount: Int = this.entries.count { it.kind == NativeRgssEntryKind.DATA }
    val otherCount: Int = this.entries.count { it.kind == NativeRgssEntryKind.OTHER }

    init {
        require(version == 1 || version == 3)
        require(archiveBytes >= 0)
    }
}

internal fun rgssEntryKind(path: String): NativeRgssEntryKind {
    val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
    return when {
        name.startsWith("scripts.") -> NativeRgssEntryKind.SCRIPT
        name.endsWith(".rxdata") || name.endsWith(".rvdata") || name.endsWith(".rvdata2") ->
            NativeRgssEntryKind.DATA
        else -> NativeRgssEntryKind.OTHER
    }
}

/** Maps the format service model to bounded display metadata. */
internal fun rgssInventoryOf(project: RgssProject, archiveBytes: Long): NativeRgssInventory =
    NativeRgssInventory(
        version = project.version,
        entries = project.files.map { file ->
            NativeRgssEntry(file.path, file.bytes.size.toLong(), rgssEntryKind(file.path), file.compressed)
        },
        archiveBytes = archiveBytes,
    )

@Composable
internal fun NativeRgssInventoryDialog(
    ref: LocalProjectRef,
    asset: ProjectAssetSummary,
    load: suspend (LocalProjectRef, Long) -> NativeRgssInventory,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val formatSize: (Long) -> String = remember(context) {
        { bytes -> Formatter.formatFileSize(context, bytes) }
    }
    var inventory by remember(ref, asset.id, asset.sha256) { mutableStateOf<NativeRgssInventory?>(null) }
    var failure by remember(ref, asset.id, asset.sha256) { mutableStateOf<Int?>(null) }
    var loading by remember(ref, asset.id, asset.sha256) { mutableStateOf(true) }
    LaunchedEffect(ref, asset.id, asset.sha256) {
        inventory = null
        failure = null
        loading = true
        try {
            inventory = load(ref, asset.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TextProjectException) {
            failure = when (error.reason) {
                TextProjectException.Reason.LIMIT_EXCEEDED -> R.string.native_rgss_error_limit
                TextProjectException.Reason.UNSUPPORTED_VERSION -> R.string.native_rgss_error_version
                TextProjectException.Reason.INVALID_FORMAT,
                TextProjectException.Reason.CORRUPT_PROJECT -> R.string.native_rgss_error_invalid
                else -> R.string.native_rgss_failed
            }
        } catch (_: Exception) {
            failure = R.string.native_rgss_failed
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.92f)
                    .testTag(NativeRgssInventoryDialogTag),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_rgss_title),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(asset.path.value, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.native_rgss_description),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_rgss_read_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.native_rgss_no_preview),
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    val current = inventory
                    when {
                        loading -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.native_rgss_loading),
                                modifier = Modifier.testTag(NativeRgssLoadingTag))
                        }
                        current != null -> {
                            Text(stringResource(R.string.native_rgss_version, current.version))
                            Text(stringResource(R.string.native_rgss_entries, current.entries.size))
                            Text(stringResource(R.string.native_rgss_archive_size,
                                formatSize(current.archiveBytes)))
                            Text(stringResource(R.string.native_rgss_script_count, current.scriptCount))
                            Text(stringResource(R.string.native_rgss_data_count, current.dataCount))
                            Text(stringResource(R.string.native_rgss_other_count, current.otherCount))
                            Text(stringResource(R.string.native_rgss_members),
                                style = MaterialTheme.typography.titleMedium)
                            if (current.entries.isEmpty()) {
                                Text(stringResource(R.string.native_rgss_empty),
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f)
                                    .testTag(NativeRgssInventoryTag)) {
                                    items(current.entries, key = { it.path }) { entry ->
                                        RgssEntryRow(entry, formatSize)
                                    }
                                }
                            }
                        }
                        else -> Text(stringResource(failure ?: R.string.native_rgss_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(NativeRgssFailedTag))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RgssEntryRow(entry: NativeRgssEntry, formatSize: (Long) -> String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .testTag(nativeRgssEntryTag(entry.path)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.path, style = MaterialTheme.typography.titleSmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.native_rgss_entry_meta,
            stringResource(when (entry.kind) {
                NativeRgssEntryKind.SCRIPT -> R.string.native_rgss_kind_script
                NativeRgssEntryKind.DATA -> R.string.native_rgss_kind_data
                NativeRgssEntryKind.OTHER -> R.string.native_rgss_kind_other
            }),
            formatSize(entry.size),
            stringResource(if (entry.compressed) R.string.native_rgss_compressed
                else R.string.native_rgss_uncompressed)),
            style = MaterialTheme.typography.bodySmall)
    }
}
