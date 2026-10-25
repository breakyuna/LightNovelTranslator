package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativePolicyProposalDialog(originalView: PolicyApprovalSummary,
    load: suspend (PolicyApprovalSummary) -> PolicyChangeProposal,
    loadReceipt: suspend (PolicyApprovalSummary) -> PolicyEditReceipt?,
    accept: (PolicyApprovalSummary) -> Unit, reject: (PolicyApprovalSummary) -> Unit, dismiss: () -> Unit,
    select: (suspend (PolicyApprovalSummary, Set<String>, Boolean, Boolean) -> PolicyApprovalSummary)? = null) {
    var view by remember(originalView.ref, originalView.id) { mutableStateOf(originalView) }
    var selectedRules by remember(originalView.ref, originalView.id) { mutableStateOf<Set<String>>(emptySet()) }
    var acceptOrder by remember(originalView.ref, originalView.id) { mutableStateOf(false) }
    var acceptPrompt by remember(originalView.ref, originalView.id) { mutableStateOf(false) }
    var reviewed by remember(originalView.ref, originalView.id) { mutableStateOf(select == null) }
    var selecting by remember(originalView.ref, originalView.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var proposal by remember(view.ref, view.id, view.digest) { mutableStateOf<PolicyChangeProposal?>(null) }
    var receipt by remember(view.ref, view.id) { mutableStateOf<PolicyEditReceipt?>(null) }
    var failed by remember(view.ref, view.id) { mutableStateOf(false) }
    var showAll by remember(view.ref, view.id) { mutableStateOf(false) }
    LaunchedEffect(view.ref, view.id, view.digest) {
        try {
            proposal = load(view); receipt = loadReceipt(view)
            val fixed = requireNotNull(proposal)
            val old = fixed.before.rules.associateBy { it.id }
            val after = fixed.after.rules.associateBy { it.id }
            selectedRules = (old.keys + after.keys).filter { old[it] != after[it] }.toSet()
            acceptOrder = fixed.before.rules.map { it.id } != fixed.after.rules.map { it.id }
            acceptPrompt = fixed.promptChanged
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val pending = view.state == TextApprovalState.PENDING
    AlertDialog(onDismissRequest = { if (!selecting) dismiss() },
        title = { Text(stringResource(R.string.native_policy_proposal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(policyApprovalStateLabel(view.state)))
                Text(stringResource(R.string.native_policy_proposal_counts, view.added, view.updated, view.deleted))
                Text(stringResource(R.string.native_policy_proposal_atomic), style = MaterialTheme.typography.bodySmall)
                if (view.reordered) Text(stringResource(R.string.native_policy_proposal_reordered))
                if (view.promptChanged) Text(stringResource(R.string.native_policy_proposal_prompt_changed))
                receipt?.let { result -> Text(stringResource(R.string.native_more_revision, result.revision)) }
                if (failed) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
                if (proposal == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (select != null && !reviewed) Text(stringResource(R.string.native_policy_select_notice))
                if (selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                proposal?.let { fixed ->
                    if (select != null && fixed.before.rules.map { it.id } != fixed.after.rules.map { it.id }) Row {
                        Checkbox(acceptOrder, { acceptOrder = it }, enabled = pending && !reviewed && !selecting)
                        Text(stringResource(R.string.native_policy_select_order))
                    }
                    if (select != null && fixed.promptChanged) Row {
                        Checkbox(acceptPrompt, { acceptPrompt = it }, enabled = pending && !reviewed && !selecting)
                        Text(stringResource(R.string.native_policy_select_prompt))
                    }
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(stringResource(if (showAll) R.string.native_policy_proposal_only_changes else R.string.native_policy_proposal_all))
                    }
                    val before = remember(fixed) { fixed.before.rules.associateBy { it.id } }
                    val after = remember(fixed) { fixed.after.rules.associateBy { it.id } }
                    val ids = remember(fixed, showAll) {
                        (fixed.before.rules.map { it.id } + fixed.after.rules.map { it.id }).distinct().filter { id ->
                            showAll || before[id] != after[id] || fixed.before.rules.indexOfFirst { it.id == id } !=
                                fixed.after.rules.indexOfFirst { it.id == id }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ids, key = { "rule-$it" }) { id ->
                            Card(Modifier.fillMaxWidth()) {
                                if (select != null && before[id] != after[id]) Row {
                                    Checkbox(id in selectedRules, { checked ->
                                        selectedRules = if (checked) selectedRules + id else selectedRules - id
                                    }, enabled = pending && !reviewed && !selecting)
                                    Text(id)
                                }
                                SelectionContainer { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val oldPosition = fixed.before.rules.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "—"
                                    val newPosition = fixed.after.rules.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "—"
                                    Text(stringResource(R.string.native_policy_proposal_position, oldPosition, newPosition))
                                    Text(stringResource(R.string.native_item_proposal_before), style = MaterialTheme.typography.titleSmall)
                                    PolicyRuleSnapshot(before[id])
                                    HorizontalDivider()
                                    Text(stringResource(R.string.native_item_proposal_after), style = MaterialTheme.typography.titleSmall)
                                    PolicyRuleSnapshot(after[id])
                                } }
                            }
                        }
                        if (showAll || fixed.promptChanged) item(key = "prompt") {
                            Card { SelectionContainer { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.native_policy_prompt), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.native_item_proposal_before))
                                PolicyFlag(R.string.native_policy_prompt_enabled, fixed.before.promptEnabled)
                                Text(fixed.before.prompt)
                                HorizontalDivider()
                                Text(stringResource(R.string.native_item_proposal_after))
                                PolicyFlag(R.string.native_policy_prompt_enabled, fixed.after.promptEnabled)
                                Text(fixed.after.prompt)
                            } } }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pending) TextButton(onClick = {
                if (reviewed) { accept(view); dismiss() }
                else {
                    selecting = true; failed = false
                    scope.launch {
                        try {
                            view = requireNotNull(select)(view, selectedRules, acceptOrder, acceptPrompt)
                            reviewed = true; showAll = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { selecting = false }
                    }
                }
            }, enabled = proposal != null && !failed && !selecting &&
                (reviewed || selectedRules.isNotEmpty() || acceptOrder || acceptPrompt),
                modifier = Modifier.testTag("native-policy-proposal-accept")) {
                Text(stringResource(if (reviewed) R.string.native_policy_proposal_accept else R.string.native_policy_select_review))
            } else TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
        dismissButton = { Row {
            if (pending) TextButton(enabled = !selecting, onClick = { reject(view); dismiss() }) { Text(stringResource(R.string.native_item_proposal_reject)) }
            TextButton(enabled = !selecting, onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
        } })
}

internal fun policyApprovalStateLabel(state: TextApprovalState): Int = when (state) {
    TextApprovalState.PENDING -> R.string.native_item_proposal_pending
    TextApprovalState.COMMITTED -> R.string.native_saved
    TextApprovalState.CONFLICT -> R.string.native_conflict
    TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
    TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
    TextApprovalState.CONSUMED -> R.string.native_agent_unresolved
}

@Composable private fun PolicyFlag(label: Int, enabled: Boolean) {
    Text(stringResource(label) + ": " + stringResource(if (enabled) R.string.native_policy_proposal_on else R.string.native_policy_proposal_off),
        style = MaterialTheme.typography.bodySmall)
}

@Composable private fun PolicyRuleSnapshot(rule: ProjectTextRule?) {
    if (rule == null) { Text("—"); return }
    Text(stringResource(when (rule.kind) {
        TextRuleKind.TERM -> R.string.native_policy_terms
        TextRuleKind.PROTECT -> R.string.native_policy_protect
        TextRuleKind.PRE_REPLACE -> R.string.native_policy_pre
        TextRuleKind.POST_REPLACE -> R.string.native_policy_post
    }))
    Text(stringResource(R.string.native_policy_source) + ": " + rule.source)
    Text(stringResource(R.string.native_policy_target) + ": " + rule.target)
    Text(stringResource(R.string.native_policy_note) + ": " + rule.note)
    PolicyFlag(R.string.native_policy_proposal_enabled, rule.enabled)
    PolicyFlag(R.string.native_policy_regex, rule.regex)
    PolicyFlag(R.string.native_policy_case, rule.caseSensitive)
}
