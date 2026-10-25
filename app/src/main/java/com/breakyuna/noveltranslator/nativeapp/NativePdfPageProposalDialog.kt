package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun NativePdfPageProposalDialog(view: PdfPageProposalSummary,
    loadMembers: suspend (PdfPageProposalSummary) -> List<PdfPageChangeMemberSummary>,
    loadReceipt: suspend (PdfPageProposalSummary) -> PdfPageProposalReceipt?,
    accept: (PdfPageProposalSummary, Set<Int>) -> Unit, reject: (PdfPageProposalSummary) -> Unit,
    dismiss: () -> Unit) {
    var members by remember(view.ref, view.id, view.digest) { mutableStateOf<List<PdfPageChangeMemberSummary>?>(null) }
    var selected by remember(view.ref, view.id, view.digest) { mutableStateOf<Set<Int>>(emptySet()) }
    var receipt by remember(view.ref, view.id) { mutableStateOf<PdfPageProposalReceipt?>(null) }
    var error by remember(view.ref, view.id) { mutableStateOf(false) }
    val pending = view.state == PdfPageProposalState.PENDING
    LaunchedEffect(view.ref, view.id, view.digest) {
        try {
            members = loadMembers(view)
            receipt = loadReceipt(view)
            selected = if (pending) members.orEmpty().map { it.page }.toSet() else receipt?.accepted?.toSet().orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_pdf_proposal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(pdfPageProposalStateLabel(view.state)),
                    modifier = Modifier.testTag("native-pdf-proposal-state"))
                Text(stringResource(R.string.native_pdf_proposal_asset, view.assetId, view.pageCount),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (view.policy == ConflictPolicy.REJECT_BATCH) R.string.native_pdf_proposal_reject_batch
                    else R.string.native_pdf_proposal_independent), style = MaterialTheme.typography.bodySmall)
                if (pending || receipt != null) Text(stringResource(
                    if (pending) R.string.native_pdf_proposal_selection else R.string.native_pdf_proposal_saved_count,
                    selected.size, view.count)) else Text(stringResource(R.string.native_pdf_proposal_total, view.count))
                if (pending && members != null) Row {
                    TextButton(onClick = { selected = members.orEmpty().map { it.page }.toSet() }) {
                        Text(stringResource(R.string.native_item_proposal_select_all))
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(stringResource(R.string.native_item_proposal_select_none))
                    }
                }
                if (error) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
                else if (members == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(members.orEmpty(), key = { it.page }) { member ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("native-pdf-proposal-page-${member.page}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (pending) Checkbox(member.page in selected, { checked ->
                                selected = if (checked) selected + member.page else selected - member.page
                            }, Modifier.testTag("native-pdf-proposal-select-${member.page}"))
                            Column(Modifier.weight(1f).padding(8.dp)) {
                                Text(stringResource(R.string.native_pdf_page_number, member.page))
                                Text(stringResource(pdfPageModeLabel(member.before)) + " → " +
                                    stringResource(pdfPageModeLabel(member.after)), style = MaterialTheme.typography.bodySmall)
                                val reason = receipt?.rejected?.get(member.page)
                                if (reason != null) Text(stringResource(when (reason) {
                                    PdfPageProposalRejection.NOT_SELECTED -> R.string.native_pdf_proposal_not_selected
                                    PdfPageProposalRejection.STALE_REVISION -> R.string.native_pdf_proposal_stale_revision
                                    PdfPageProposalRejection.STALE_ASSET -> R.string.native_pdf_proposal_stale_asset
                                    PdfPageProposalRejection.STALE_PAGE -> R.string.native_pdf_proposal_stale_page
                                    PdfPageProposalRejection.BATCH_CONFLICT -> R.string.native_pdf_proposal_batch_conflict
                                }), style = MaterialTheme.typography.bodySmall)
                                else if (member.page in receipt?.accepted.orEmpty()) Text(stringResource(R.string.native_saved),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pending) TextButton(onClick = { accept(view, selected.toSet()); dismiss() },
                enabled = members != null && !error && selected.isNotEmpty(),
                modifier = Modifier.testTag("native-pdf-proposal-accept")) {
                Text(stringResource(R.string.native_pdf_proposal_accept))
            } else TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
        dismissButton = {
            Row {
                if (pending) TextButton(onClick = { reject(view); dismiss() },
                    modifier = Modifier.testTag("native-pdf-proposal-reject")) {
                    Text(stringResource(R.string.native_item_proposal_reject))
                }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
            }
        })
}

@Composable
internal fun pdfPageModeLabel(mode: PdfPageMode): Int = when (mode) {
    PdfPageMode.KEEP -> R.string.native_pdf_keep
    PdfPageMode.OMIT -> R.string.native_pdf_omit
    PdfPageMode.TRANSLATE -> R.string.native_pdf_translate
}

internal fun pdfPageProposalStateLabel(state: PdfPageProposalState): Int = when (state) {
    PdfPageProposalState.PENDING -> R.string.native_item_proposal_pending
    PdfPageProposalState.CONSUMED -> R.string.native_agent_unresolved
    PdfPageProposalState.COMMITTED -> R.string.native_saved
    PdfPageProposalState.PARTIAL -> R.string.native_pdf_proposal_partial
    PdfPageProposalState.CONFLICT -> R.string.native_conflict
    PdfPageProposalState.REJECTED -> R.string.native_agent_result_rejected
    PdfPageProposalState.CANCELLED -> R.string.native_agent_result_cancelled
}
