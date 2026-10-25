package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposalState
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposalSummary
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportReceipt
import kotlinx.coroutines.CancellationException

/**
 * Explicit user review of one frozen workspace-artifact import. The artifact body is not held in
 * this dialog; only frozen metadata, target path, revision and the durable receipt are shown.
 */
@Composable
internal fun NativeAssetImportProposalDialog(
    view: AssetImportProposalSummary,
    loadReceipt: suspend (AssetImportProposalSummary) -> AssetImportReceipt?,
    accept: (AssetImportProposalSummary) -> Unit,
    reject: (AssetImportProposalSummary) -> Unit,
    dismiss: () -> Unit,
) {
    var receipt by remember(view.ref, view.id) { mutableStateOf<AssetImportReceipt?>(null) }
    var failed by remember(view.ref, view.id) { mutableStateOf(false) }
    LaunchedEffect(view.ref, view.id, view.digest.sha256) {
        try { receipt = loadReceipt(view) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val pending = view.state == AssetImportProposalState.PENDING
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_asset_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(assetImportProposalStateLabel(view.state)))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.native_asset_import_artifact, view.artifactPath.value))
                        Text(stringResource(R.string.native_asset_import_size, view.artifactSizeBytes))
                        Text(stringResource(R.string.native_asset_import_sha, view.artifactSha256))
                        Text(stringResource(R.string.native_asset_import_target, view.target.value))
                        Text(stringResource(R.string.native_asset_import_revision, view.revision))
                    }
                }
                Text(stringResource(if (view.enabled) R.string.native_asset_import_enabled
                else R.string.native_asset_import_disabled))
                Text(stringResource(R.string.native_asset_import_binding), style = MaterialTheme.typography.bodySmall)
                receipt?.let { result ->
                    if (result.assetId != null) {
                        Text(stringResource(R.string.native_asset_import_receipt, result.assetId, result.revision))
                    }
                }
                if (failed) Text(stringResource(R.string.native_agent_load_error),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (pending) TextButton(onClick = { accept(view); dismiss() },
                modifier = Modifier.testTag("native-asset-import-accept")) {
                Text(stringResource(R.string.native_asset_import_accept))
            } else TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
        dismissButton = {
            Row {
                if (pending) TextButton(onClick = { reject(view); dismiss() },
                    modifier = Modifier.testTag("native-asset-import-reject")) {
                    Text(stringResource(R.string.native_asset_import_reject))
                }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
            }
        },
    )
}

internal fun assetImportProposalStateLabel(state: AssetImportProposalState): Int = when (state) {
    AssetImportProposalState.PENDING -> R.string.native_item_proposal_pending
    AssetImportProposalState.APPLIED -> R.string.native_saved
    AssetImportProposalState.REJECTED -> R.string.native_agent_result_rejected
    AssetImportProposalState.STALE -> R.string.native_asset_import_state_stale
    AssetImportProposalState.CONFLICT -> R.string.native_asset_import_conflict
    AssetImportProposalState.CANCELLED -> R.string.native_agent_result_cancelled
}
