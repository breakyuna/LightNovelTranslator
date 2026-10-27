package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState

/**
 * One-shot approval for a frozen mixed parent. Only identities, counts, paths and digests are
 * rendered; proposal bodies and file contents never reach this surface.
 */
@Composable
internal fun NativeMixedApprovalDialog(
    snapshot: NativeMixedApprovalSnapshot,
    busy: Boolean,
    error: Int?,
    allow: () -> Unit,
    reject: () -> Unit,
    dismiss: () -> Unit,
) {
    val approval = snapshot.approval
    val pending = approval.state == MixedApprovalState.PENDING
    var expanded by remember(approval.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.native_mixed_approval_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.native_mixed_approval_intro))
                Text(
                    stringResource(R.string.native_mixed_approval_digest, approval.digest.sha256),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-mixed-approval-digest"),
                )
                Text(stringResource(R.string.native_mixed_approval_revision, approval.baseRevision))
                Text(stringResource(R.string.native_mixed_approval_policy, approval.policy.name))
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("native-mixed-approval-details"),
                ) {
                    Text(stringResource(if (expanded) {
                        R.string.native_mixed_approval_hide_details
                    } else {
                        R.string.native_mixed_approval_show_details
                    }))
                }
                snapshot.members.forEachIndexed { index, member ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(
                                    R.string.native_mixed_approval_member,
                                    index + 1,
                                    stringResource(member.kind.labelRes()),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            MemberSummary(member)
                            Text(
                                stringResource(R.string.native_mixed_approval_state, member.state.name),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (expanded) {
                        Text(
                            stringResource(R.string.native_mixed_approval_member_digest, member.childDigest.sha256),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        member.target?.let { target ->
                            Text(
                                stringResource(R.string.native_mixed_approval_target, target),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        member.paths.take(MAX_DETAIL_PATHS).forEach { path ->
                            Text(path, style = MaterialTheme.typography.bodySmall)
                        }
                        if (member.paths.size > MAX_DETAIL_PATHS) {
                            Text(
                                stringResource(
                                    R.string.native_mixed_approval_paths_more,
                                    member.paths.size - MAX_DETAIL_PATHS,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.native_mixed_approval_cache_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (pending) {
                TextButton(
                    onClick = allow,
                    enabled = !busy,
                    modifier = Modifier.testTag("native-mixed-approval-allow"),
                ) { Text(stringResource(R.string.native_mixed_approval_allow)) }
            }
        },
        dismissButton = {
            if (pending) {
                TextButton(
                    onClick = reject,
                    enabled = !busy,
                    modifier = Modifier.testTag("native-mixed-approval-reject"),
                ) { Text(stringResource(R.string.native_mixed_approval_reject)) }
            } else {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
            }
        },
    )
}

@Composable
private fun MemberSummary(member: NativeMixedApprovalMemberView) {
    Text(
        text = when (member.kind) {
            MixedApprovalMemberKind.ITEM ->
                stringResource(R.string.native_mixed_approval_member_item, member.objects)
            MixedApprovalMemberKind.POLICY ->
                stringResource(
                    R.string.native_mixed_approval_member_policy,
                    member.added, member.updated, member.deleted,
                )
            MixedApprovalMemberKind.PDF_PAGE ->
                stringResource(R.string.native_mixed_approval_member_pdf, member.objects)
            MixedApprovalMemberKind.ASSET_IMPORT ->
                stringResource(
                    R.string.native_mixed_approval_member_asset,
                    member.target ?: "",
                )
            MixedApprovalMemberKind.WORKSPACE_FILE ->
                stringResource(
                    R.string.native_mixed_approval_member_workspace,
                    member.added, member.updated, member.deleted, member.bytes,
                )
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun MixedApprovalMemberKind.labelRes(): Int = when (this) {
    MixedApprovalMemberKind.ITEM -> R.string.native_mixed_approval_kind_item
    MixedApprovalMemberKind.POLICY -> R.string.native_mixed_approval_kind_policy
    MixedApprovalMemberKind.PDF_PAGE -> R.string.native_mixed_approval_kind_pdf
    MixedApprovalMemberKind.ASSET_IMPORT -> R.string.native_mixed_approval_kind_asset
    MixedApprovalMemberKind.WORKSPACE_FILE -> R.string.native_mixed_approval_kind_workspace
}

private const val MAX_DETAIL_PATHS = 8
