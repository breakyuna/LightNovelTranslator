package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R

/**
 * Approval surface for a frozen workspace-file batch. It intentionally shows only counts and
 * identities: file bodies are not copied into UI state, logs or this dialog.
 */
@Composable
internal fun NativeWorkspaceChangeDialog(
    view: WorkspaceChangeProposalSummary,
    allow: () -> Unit,
    reject: () -> Unit,
    dismiss: () -> Unit,
) {
    val pending = view.state == WorkspaceChangeProposalState.PENDING
    NativeContentDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_workspace_change_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(
                    R.string.native_workspace_change_counts,
                    view.summary.created, view.summary.updated, view.summary.deleted, view.summary.totalBytes,
                ))
                view.paths.forEach { path ->
                    Text(path, style = MaterialTheme.typography.bodySmall)
                }
                NativeAgentFoldout(stringResource(R.string.native_agent_message_details), stateKey = view.digest) {
                    Text(stringResource(R.string.native_workspace_change_revision, view.baseRevision))
                    Text(stringResource(R.string.native_workspace_change_digest, view.digest.sha256.take(16)))
                }
                Text(stringResource(R.string.native_workspace_change_state, stringResource(workspaceProposalLabel(view.state))),
                    modifier = Modifier.testTag("native-workspace-change-state"))
                Text(stringResource(R.string.native_workspace_change_notice),
                    style = MaterialTheme.typography.bodySmall)
                view.receipt?.let { receipt ->
                    Text(stringResource(
                        R.string.native_workspace_change_receipt,
                        stringResource(when (receipt.status) {
                            com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus.COMMITTED -> R.string.native_saved
                            com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus.PARTIAL -> R.string.native_item_proposal_partial
                            com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus.REJECTED -> R.string.native_agent_result_rejected
                        }), receipt.committed, receipt.conflicts,
                    ), style = MaterialTheme.typography.bodySmall)
                    receipt.conflictPaths.forEach { path ->
                        Text(path, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (pending) TextButton(onClick = allow, modifier = Modifier.testTag("native-workspace-change-allow")) {
                Text(stringResource(R.string.native_agent_allow_once))
            }
        },
        dismissButton = {
            if (pending) TextButton(onClick = reject, modifier = Modifier.testTag("native-workspace-change-reject")) {
                Text(stringResource(R.string.native_agent_reject))
            } else TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
    )
}

private fun workspaceProposalLabel(state: WorkspaceChangeProposalState): Int = when (state) {
    WorkspaceChangeProposalState.PENDING -> R.string.native_item_proposal_pending
    WorkspaceChangeProposalState.APPLIED -> R.string.native_saved
    WorkspaceChangeProposalState.PARTIAL -> R.string.native_item_proposal_partial
    WorkspaceChangeProposalState.REJECTED -> R.string.native_agent_result_rejected
    WorkspaceChangeProposalState.CONFLICT -> R.string.native_conflict
}
