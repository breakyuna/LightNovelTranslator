package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch

@Composable
internal fun NativeItemProposalDialog(view: ItemProposalSummary,
    load: suspend (ItemProposalSummary) -> List<ItemProposalMemberSummary>,
    detail: suspend (ItemProposalSummary, ItemId) -> ItemProposalMember,
    loadReceipt: suspend (ItemProposalSummary) -> ItemProposalReceipt?,
    accept: (ItemProposalSummary, Set<ItemId>) -> Unit, reject: (ItemProposalSummary) -> Unit,
    dismiss: () -> Unit) {
    var members by remember(view.ref, view.id, view.digest) { mutableStateOf<List<ItemProposalMemberSummary>?>(null) }
    var selected by remember(view.ref, view.id, view.digest) { mutableStateOf<Set<ItemId>>(emptySet()) }
    var error by remember(view.ref, view.id) { mutableStateOf(false) }
    var opened by remember(view.ref, view.id) { mutableStateOf<ItemProposalMember?>(null) }
    var detailTarget by remember(view.ref, view.id) { mutableStateOf<ItemId?>(null) }
    var receipt by remember(view.ref, view.id) { mutableStateOf<ItemProposalReceipt?>(null) }
    val scope = rememberCoroutineScope()
    val pending = view.state == ItemProposalState.PENDING
    LaunchedEffect(view.ref, view.id, view.digest) {
        try {
            members = load(view)
            receipt = loadReceipt(view)
            selected = if (pending) members.orEmpty().map { it.id }.toSet() else receipt?.accepted?.toSet().orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    opened?.let { member ->
        AlertDialog(onDismissRequest = { opened = null; detailTarget = null },
            title = { Text(stringResource(R.string.native_edit_row, member.row + 1)) },
            text = { SelectionContainer {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_item_proposal_before), style = MaterialTheme.typography.titleSmall)
                    ProposalValue(member.before)
                    HorizontalDivider()
                    Text(stringResource(R.string.native_item_proposal_after), style = MaterialTheme.typography.titleSmall)
                    ProposalValue(member.after)
                }
            } },
            confirmButton = { TextButton(onClick = { opened = null; detailTarget = null }) { Text(stringResource(R.string.native_close)) } })
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_item_proposal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(itemProposalStateLabel(view.state)))
                Text(stringResource(if (view.policy == ConflictPolicy.REJECT_BATCH) R.string.native_item_proposal_reject_batch
                    else R.string.native_item_proposal_independent), style = MaterialTheme.typography.bodySmall)
                if (pending || receipt != null) Text(stringResource(
                    if (pending) R.string.native_item_proposal_selection else R.string.native_item_proposal_saved_count,
                    selected.size, view.count))
                else Text(stringResource(R.string.native_item_proposal_total, view.count))
                if (pending && members != null) Row {
                    TextButton(onClick = { selected = members.orEmpty().map { it.id }.toSet() }) {
                        Text(stringResource(R.string.native_item_proposal_select_all))
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(stringResource(R.string.native_item_proposal_select_none))
                    }
                }
                if (error) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
                else if (members == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(members.orEmpty(), key = { it.id.value }) { member ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pending) Checkbox(member.id in selected, { checked ->
                                selected = if (checked) selected + member.id else selected - member.id
                            }, Modifier.testTag("native-proposal-select-${member.id.value}"))
                            Column(Modifier.weight(1f).clickable {
                                detailTarget = member.id
                                scope.launch {
                                    try {
                                        val loaded = detail(view, member.id)
                                        if (detailTarget == member.id) opened = loaded
                                    }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = true }
                                }
                            }.padding(8.dp)) {
                                Text(stringResource(R.string.native_edit_row, member.row + 1))
                                val changes = listOfNotNull(
                                    if (member.textChanged) stringResource(R.string.native_item_proposal_text) else null,
                                    if (member.namesChanged) stringResource(R.string.native_item_proposal_names) else null,
                                    if (member.statusChanged) stringResource(R.string.native_item_proposal_status) else null)
                                Text(changes.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                val reason = receipt?.rejected?.get(member.id)
                                if (reason != null) Text(stringResource(when (reason) {
                                    ItemProposalRejection.NOT_SELECTED -> R.string.native_item_proposal_not_selected
                                    ItemProposalRejection.STALE_REVISION -> R.string.native_item_proposal_stale_revision
                                    ItemProposalRejection.STALE_FINGERPRINT -> R.string.native_item_proposal_stale_item
                                    ItemProposalRejection.UNKNOWN_ITEM -> R.string.native_item_proposal_missing
                                    ItemProposalRejection.BATCH_CONFLICT -> R.string.native_item_proposal_batch_conflict
                                }), style = MaterialTheme.typography.bodySmall)
                                else if (member.id in receipt?.accepted.orEmpty()) Text(stringResource(R.string.native_saved),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pending) TextButton(onClick = { accept(view, selected.toSet()); dismiss() },
                enabled = members != null && !error && selected.isNotEmpty(), modifier = Modifier.testTag("native-proposal-accept")) {
                Text(stringResource(R.string.native_item_proposal_accept))
            } else TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
        dismissButton = {
            Row {
                if (pending) TextButton(onClick = { reject(view); dismiss() }) {
                    Text(stringResource(R.string.native_item_proposal_reject))
                }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
            }
        })
}

@Composable
private fun ProposalValue(value: ItemProposalValue) {
    Text(stringResource(R.string.native_item_proposal_text) + ": " + value.translation)
    Text(stringResource(R.string.native_item_proposal_names) + ": " + (value.nameSource?.joinToString(" / ") ?: "—") +
        " → " + (value.nameTranslation?.joinToString(" / ") ?: "—"))
    Text(stringResource(R.string.native_item_proposal_status) + ": " + stringResource(when (value.status) {
        TextItemStatus.NONE -> R.string.native_status_none
        TextItemStatus.PROCESSED -> R.string.native_status_processed
        TextItemStatus.EXCLUDED -> R.string.native_status_excluded
        TextItemStatus.ERROR -> R.string.native_status_error
        TextItemStatus.RULE_SKIPPED -> R.string.native_status_rule_skipped
        TextItemStatus.LANGUAGE_SKIPPED -> R.string.native_status_language_skipped
        TextItemStatus.DUPLICATED -> R.string.native_status_duplicated
    }))
}

internal fun itemProposalStateLabel(state: ItemProposalState): Int = when (state) {
    ItemProposalState.PENDING -> R.string.native_item_proposal_pending
    ItemProposalState.CONSUMED -> R.string.native_agent_unresolved
    ItemProposalState.COMMITTED -> R.string.native_saved
    ItemProposalState.PARTIAL -> R.string.native_item_proposal_partial
    ItemProposalState.CONFLICT -> R.string.native_conflict
    ItemProposalState.REJECTED -> R.string.native_agent_result_rejected
    ItemProposalState.CANCELLED -> R.string.native_agent_result_cancelled
}
