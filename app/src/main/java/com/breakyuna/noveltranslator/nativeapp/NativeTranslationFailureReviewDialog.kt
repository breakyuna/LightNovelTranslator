package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.TranslationFailureMember
import com.breakyuna.noveltranslator.nativecore.domain.TranslationFailureReceipt
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueRejection
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueResult
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskCommandAction
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureBatch
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureBatchId
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureDecisionReceipt
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskFailureEvidence
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueLimits
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskReview
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueuedTask
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import com.breakyuna.noveltranslator.nativecore.engine.TranslationFailureAdjudicationResult
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Bounded by the default task failure-batch budget; larger custom task budgets remain partial. */
private const val MAX_REVIEW_EVIDENCE_PAGES =
    TranslationTaskFailureBatch.MAX_MEMBERS * TranslationTaskQueueLimits.DEFAULT_MAX_BATCHES

private data class FailureReviewKey(
    val batchId: TranslationTaskFailureBatchId,
    val itemId: ItemId,
)

private data class FailureReviewRow(
    val batchId: TranslationTaskFailureBatchId,
    val member: TranslationFailureMember,
    val verified: Boolean,
    val decision: TranslationTaskCommandAction?,
    val selectable: Boolean,
    val retryable: Boolean,
    val skippable: Boolean,
    val keepable: Boolean,
    val committed: Boolean,
    val terminal: Boolean,
)

private data class FailureReviewBatch(
    val id: TranslationTaskFailureBatchId,
    val rows: List<FailureReviewRow>,
)

private data class FailureReviewFeedback(
    val saved: Int = 0,
    val rejection: TranslationQueueRejection? = null,
    val unconfirmed: Boolean = false,
    val receipt: TranslationTaskFailureDecisionReceipt? = null,
)

/**
 * Explicit failure-member adjudication for one durable queue task.
 *
 * The dialog only reads [TranslationTaskFailureEvidence] (item identity, fingerprint, classified
 * failure, receipt state, usage certainty and committed/terminal flags) and the queue's frozen
 * failure batches. It never receives source or translation text. A callback returns only after the
 * queue command and its durable decision receipt settle; a missing receipt is shown as
 * unconfirmed, never as success. The dialog does not create a Run, task or paid request, and retry
 * only requeues the same task for a later application-level scheduler pass.
 */
@Composable
internal fun NativeTranslationFailureReviewDialog(
    identity: TranslationTaskQueueIdentity,
    loadTask: suspend (TranslationTaskQueueIdentity) -> TranslationQueuedTask,
    loadEvidence: suspend (TranslationTaskQueueIdentity, Long) -> TranslationTaskFailureEvidence,
    retry: suspend (TranslationTaskQueueIdentity, TranslationTaskFailureBatchId, List<ItemId>) ->
        TranslationFailureAdjudicationResult,
    skip: suspend (TranslationTaskQueueIdentity, TranslationTaskFailureBatchId, List<ItemId>) ->
        TranslationFailureAdjudicationResult,
    keep: suspend (TranslationTaskQueueIdentity, TranslationTaskFailureBatchId, List<ItemId>) ->
        TranslationFailureAdjudicationResult,
    terminate: suspend (TranslationTaskQueueIdentity) -> TranslationFailureAdjudicationResult,
    dismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    var task by remember(identity) { mutableStateOf<TranslationQueuedTask?>(null) }
    var review by remember(identity) { mutableStateOf<TranslationTaskReview?>(null) }
    var batches by remember(identity) { mutableStateOf<List<FailureReviewBatch>>(emptyList()) }
    var selected by remember(identity) { mutableStateOf<Set<FailureReviewKey>>(emptySet()) }
    var loading by remember(identity) { mutableStateOf(true) }
    var loadFailed by remember(identity) { mutableStateOf(false) }
    var partialEvidence by remember(identity) { mutableStateOf(false) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    var busy by remember(identity) { mutableStateOf(false) }
    var feedback by remember(identity) { mutableStateOf<FailureReviewFeedback?>(null) }
    var confirmTerminate by remember(identity) { mutableStateOf(false) }
    var actionFailed by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(identity, reload) {
        loading = true
        loadFailed = false
        actionFailed = false
        try {
            val current = loadTask(identity)
            val pages = ArrayList<TranslationTaskFailureEvidence>()
            val evidenceByItem = LinkedHashMap<ItemId, TranslationFailureMember>()
            val required = current.batches.flatMap { batch ->
                batch.members.filter { member ->
                    !member.committed && !member.terminal &&
                        !current.isCommitted(member.itemId) && !current.isTerminalFailure(member.itemId) &&
                        current.decision(batch.id, member.itemId) == null &&
                        current.finalDecision(member.itemId) == null
                }.map { it.itemId }
            }.toSortedSet(compareBy { it.value })
            val first = loadEvidence(identity, 0)
            require(first.review.ref == identity.ref && first.review.run == identity.run) {
                "Failure evidence belongs to another task"
            }
            pages += first
            first.members.forEach { evidenceByItem.putIfAbsent(it.itemId, it) }
            val queried = HashSet<Long>()
            queried += 0L
            var complete = required.all { it in evidenceByItem }
            // The port pages by project item identity, not by queue batch. Querying immediately
            // before each missing member keeps the review bounded even for a large project with
            // failures far beyond the first target page.
            for (memberId in required) {
                if (memberId in evidenceByItem) continue
                if (pages.size >= MAX_REVIEW_EVIDENCE_PAGES) break
                val after = maxOf(0L, memberId.value - 1)
                if (!queried.add(after)) continue
                val evidence = loadEvidence(identity, after)
                require(evidence.review.ref == identity.ref && evidence.review.run == identity.run) {
                    "Failure evidence belongs to another task"
                }
                require(pages.first().review == evidence.review &&
                    pages.first().projectRevision == evidence.projectRevision) {
                    "Failure evidence changed while paging"
                }
                pages += evidence
                evidence.members.forEach { evidenceByItem.putIfAbsent(it.itemId, it) }
            }
            complete = required.all { it in evidenceByItem }
            val built = current.batches.map { batch ->
                FailureReviewBatch(batch.id, batch.members.sortedBy { it.itemId.value }.map { member ->
                    val fresh = evidenceByItem[member.itemId]
                    val decision = current.decision(batch.id, member.itemId)?.action
                    val committed = member.committed || current.isCommitted(member.itemId)
                    val terminal = member.terminal || current.isTerminalFailure(member.itemId)
                    val verified = decision != null || committed || terminal || fresh == member
                    val selectable = current.state.awaitingDecision && verified && !committed &&
                        !terminal && decision == null &&
                        current.finalDecision(member.itemId) == null
                    FailureReviewRow(
                        batchId = batch.id,
                        member = member,
                        verified = verified,
                        decision = decision ?: current.finalDecision(member.itemId),
                        selectable = selectable,
                        retryable = selectable && member.receipt != TranslationFailureReceipt.DISPATCHED_NO_RECEIPT,
                        skippable = selectable && member.receipt != TranslationFailureReceipt.DISPATCHED_NO_RECEIPT,
                        keepable = selectable,
                        committed = committed,
                        terminal = terminal,
                    )
                })
            }
            task = current
            review = pages.firstOrNull()?.review
            batches = built
            partialEvidence = !complete
            val valid = built.flatMap { it.rows }.filter { it.selectable }.map {
                FailureReviewKey(it.batchId, it.member.itemId)
            }.toSet()
            selected = selected.filterTo(HashSet()) { it in valid }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            task = null
            review = null
            batches = emptyList()
            selected = emptySet()
            loadFailed = true
        } finally {
            loading = false
        }
    }

    val rows = batches.flatMap { it.rows }
    val selectedRows = rows.filter { FailureReviewKey(it.batchId, it.member.itemId) in selected }
    val awaiting = task?.state?.awaitingDecision == true
    val canRetry = awaiting && !busy && selectedRows.isNotEmpty() && selectedRows.all { it.retryable }
    val canSkip = awaiting && !busy && selectedRows.isNotEmpty() && selectedRows.all { it.skippable }
    val canKeep = awaiting && !busy && selectedRows.isNotEmpty() && selectedRows.all { it.keepable }
    val canTerminate = !busy && task?.state?.terminal == false

    suspend fun applyMembers(action: TranslationTaskCommandAction) {
        if (busy || selectedRows.isEmpty()) return
        busy = true
        feedback = null
        actionFailed = false
        var saved = 0
        var rejection: TranslationQueueRejection? = null
        var unconfirmed = false
        var lastReceipt: TranslationTaskFailureDecisionReceipt? = null
        try {
            for ((batchId, group) in selectedRows.groupBy { it.batchId }) {
                val ids = group.map { it.member.itemId }.sortedBy { it.value }
                val result = when (action) {
                    TranslationTaskCommandAction.RETRY_MEMBERS -> retry(identity, batchId, ids)
                    TranslationTaskCommandAction.SKIP_MEMBERS -> skip(identity, batchId, ids)
                    TranslationTaskCommandAction.KEEP_ERROR -> keep(identity, batchId, ids)
                    else -> error("Not a member action")
                }
                val refused = (result.queue as? TranslationQueueResult.Rejected)?.reason
                if (result.receipt == null || refused != null) {
                    if (result.receipt != null) {
                        saved++
                        lastReceipt = result.receipt
                    }
                    rejection = refused
                    unconfirmed = result.receipt == null && refused == null
                    break
                }
                saved++
                lastReceipt = result.receipt
            }
            if (saved > 0) onChanged()
            feedback = FailureReviewFeedback(saved, rejection, unconfirmed, lastReceipt)
            reload++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: TranslationQueueRefused) {
            if (saved > 0) onChanged()
            feedback = FailureReviewFeedback(saved, refused.reason, saved == 0, lastReceipt)
            reload++
        } catch (_: Exception) {
            if (saved > 0) onChanged()
            feedback = FailureReviewFeedback(saved, unconfirmed = saved == 0, receipt = lastReceipt)
            actionFailed = true
            reload++
        } finally {
            busy = false
            confirmTerminate = false
        }
    }

    fun applyTerminate() {
        if (busy) return
        busy = true
        feedback = null
        actionFailed = false
        scope.launch {
            try {
                val result = terminate(identity)
                val refused = (result.queue as? TranslationQueueResult.Rejected)?.reason
                if (refused != null) {
                    feedback = FailureReviewFeedback(rejection = refused)
                } else if (result.queue is TranslationQueueResult.Applied ||
                    result.queue is TranslationQueueResult.Duplicate
                ) {
                    // Claim termination only after reading the terminal queue fact back.
                    val confirmed = loadTask(identity).state.terminal
                    if (confirmed) {
                        onChanged()
                        feedback = FailureReviewFeedback(saved = 1)
                        dismiss()
                    } else {
                        feedback = FailureReviewFeedback(unconfirmed = true)
                        reload++
                    }
                } else {
                    feedback = FailureReviewFeedback(unconfirmed = true)
                    reload++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: TranslationQueueRefused) {
                feedback = FailureReviewFeedback(rejection = refused.reason)
            } catch (_: Exception) {
                feedback = FailureReviewFeedback(unconfirmed = true)
                actionFailed = true
                reload++
            } finally {
                busy = false
                confirmTerminate = false
            }
        }
    }

    NativeContentDialog(inline = true,
        onDismissRequest = { if (!busy) dismiss() },
        title = {
            Text(stringResource(R.string.native_failure_review_title),
                Modifier.testTag("native-failure-review"))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_failure_review_notice),
                    style = MaterialTheme.typography.bodySmall)
                task?.let { current ->
                    Text(stringResource(R.string.native_failure_task_state, current.state.name),
                        style = MaterialTheme.typography.titleSmall)
                    review?.let { evidenceReview ->
                        Text(stringResource(R.string.native_failure_task_counts,
                            evidenceReview.committed, evidenceReview.remaining,
                            evidenceReview.requests, evidenceReview.unresolvedRequests),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (loading && task == null) {
                    NativeBusyIndicator()
                    Text(stringResource(R.string.native_failure_review_loading),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loadFailed) Text(stringResource(R.string.native_failure_review_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("native-failure-load-error"))
                if (partialEvidence) Text(stringResource(R.string.native_failure_review_partial),
                    style = MaterialTheme.typography.bodySmall)
                if (!loading && !loadFailed && task != null && !awaiting) {
                    Text(stringResource(if (task!!.state.terminal) R.string.native_failure_task_terminal
                        else R.string.native_failure_task_not_awaiting),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native-failure-not-awaiting"))
                }
                if (!loading && !loadFailed && task != null && rows.isEmpty()) {
                    Text(stringResource(R.string.native_failure_review_empty))
                }
                if (task != null && rows.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy && awaiting,
                            onClick = {
                                selected = rows.filter { it.selectable }
                                    .map { FailureReviewKey(it.batchId, it.member.itemId) }.toSet()
                            },
                            modifier = Modifier.testTag("native-failure-select-all")) {
                            Text(stringResource(R.string.native_failure_select_all))
                        }
                        TextButton(enabled = !busy && selected.isNotEmpty(),
                            onClick = { selected = emptySet() },
                            modifier = Modifier.testTag("native-failure-select-none")) {
                            Text(stringResource(R.string.native_failure_select_none))
                        }
                    }
                    Text(stringResource(R.string.native_failure_selected, selectedRows.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native-failure-selected"))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        .testTag("native-failure-list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        batches.forEach { batch ->
                            item(key = "batch-${batch.id.value}") {
                                Card(Modifier.fillMaxWidth()
                                    .testTag("native-failure-batch-${batch.id.value}")) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.native_failure_batch_title,
                                            batch.id.value, batch.rows.size),
                                            style = MaterialTheme.typography.titleSmall)
                                        HorizontalDivider()
                                    }
                                }
                            }
                            items(batch.rows, key = {
                                "member-${batch.id.value}-${it.member.itemId.value}"
                            }) { row ->
                                val key = FailureReviewKey(row.batchId, row.member.itemId)
                                Column(Modifier.fillMaxWidth()
                                    .testTag("native-failure-row-${row.batchId.value}-${row.member.itemId.value}")) {
                                    Row {
                                        Checkbox(
                                            checked = key in selected,
                                            enabled = !busy && row.selectable,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) selected + key else selected - key
                                            },
                                            modifier = Modifier.testTag(
                                                "native-failure-member-${row.batchId.value}-${row.member.itemId.value}"),
                                        )
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.native_failure_member_meta,
                                                row.member.itemId.value,
                                                row.member.fingerprint.sha256.take(12)),
                                                style = MaterialTheme.typography.bodySmall)
                                            Text(stringResource(R.string.native_failure_member_failure,
                                                stringResource(failureKindLabel(row.member.failure))))
                                            Text(stringResource(R.string.native_failure_member_receipt,
                                                stringResource(failureReceiptLabel(row.member.receipt))))
                                            Text(stringResource(R.string.native_failure_member_usage,
                                                stringResource(failureUsageLabel(row.member.usage))),
                                                style = MaterialTheme.typography.bodySmall)
                                            if (row.committed) Text(
                                                stringResource(R.string.native_failure_member_committed),
                                                style = MaterialTheme.typography.bodySmall)
                                            if (row.terminal) Text(
                                                stringResource(R.string.native_failure_member_terminal),
                                                style = MaterialTheme.typography.bodySmall)
                                            if (row.member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                                                Text(stringResource(R.string.native_failure_member_uncertain),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.testTag(
                                                        "native-failure-uncertain-${row.batchId.value}-${row.member.itemId.value}"))
                                            }
                                            row.decision?.let { decision ->
                                                Text(stringResource(R.string.native_failure_member_decided,
                                                    stringResource(decisionLabel(decision))),
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (!row.verified) Text(
                                                stringResource(R.string.native_failure_member_stale),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) Text(stringResource(R.string.native_failure_action_running),
                    style = MaterialTheme.typography.bodySmall)
                feedback?.let { result ->
                    if (result.saved > 0) Text(stringResource(R.string.native_failure_action_saved, result.saved),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("native-failure-success"))
                    result.receipt?.let { receipt ->
                        Text(stringResource(R.string.native_failure_action_receipt,
                            receipt.command.value, stringResource(decisionLabel(receipt.action)),
                            receipt.batchId.value, receipt.memberIds.size, receipt.retryBudget),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("native-failure-receipt"))
                    }
                    result.rejection?.let { reason ->
                        Text(stringResource(R.string.native_failure_action_rejected,
                            stringResource(failureRejectionLabel(reason)), reason.name),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("native-failure-rejection"))
                    }
                    if (result.unconfirmed) Text(stringResource(R.string.native_failure_action_unconfirmed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("native-failure-unconfirmed"))
                }
                if (actionFailed && feedback?.unconfirmed != true) Text(
                    stringResource(R.string.native_failure_action_error),
                    color = MaterialTheme.colorScheme.error)
                if (rows.isNotEmpty() && awaiting) {
                    Text(stringResource(R.string.native_failure_action_semantics),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (canTerminate) {
                    Text(stringResource(R.string.native_failure_terminate_notice),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { confirmTerminate = true },
                        modifier = Modifier.testTag("native-failure-terminate")) {
                        Text(stringResource(R.string.native_failure_terminate))
                    }
                }
                if (confirmTerminate) {
                    Text(stringResource(R.string.native_failure_terminate_confirm),
                        color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy, onClick = { applyTerminate() },
                            modifier = Modifier.testTag("native-failure-terminate-confirm")) {
                            Text(stringResource(R.string.native_failure_terminate_confirm_action))
                        }
                        TextButton(enabled = !busy, onClick = { confirmTerminate = false },
                            modifier = Modifier.testTag("native-failure-terminate-cancel")) {
                            Text(stringResource(R.string.native_failure_terminate_cancel))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (rows.isNotEmpty() && awaiting) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = canRetry, onClick = { scope.launch {
                        applyMembers(TranslationTaskCommandAction.RETRY_MEMBERS)
                    } }, modifier = Modifier.testTag("native-failure-retry")) {
                        Text(stringResource(R.string.native_failure_retry))
                    }
                    TextButton(enabled = canSkip, onClick = { scope.launch {
                        applyMembers(TranslationTaskCommandAction.SKIP_MEMBERS)
                    } }, modifier = Modifier.testTag("native-failure-skip")) {
                        Text(stringResource(R.string.native_failure_skip))
                    }
                    TextButton(enabled = canKeep, onClick = { scope.launch {
                        applyMembers(TranslationTaskCommandAction.KEEP_ERROR)
                    } }, modifier = Modifier.testTag("native-failure-keep")) {
                        Text(stringResource(R.string.native_failure_keep))
                    }
                }
            } else {
                TextButton(enabled = !busy, onClick = dismiss,
                    modifier = Modifier.testTag("native-failure-close")) {
                    Text(stringResource(R.string.native_failure_close))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = { reload++ },
                    modifier = Modifier.testTag("native-failure-refresh")) {
                    Text(stringResource(R.string.native_failure_refresh))
                }
                if (rows.isNotEmpty() && awaiting) {
                    TextButton(enabled = !busy, onClick = dismiss,
                        modifier = Modifier.testTag("native-failure-close")) {
                        Text(stringResource(R.string.native_failure_close))
                    }
                }
            }
        },
    )
}

private fun decisionLabel(action: TranslationTaskCommandAction): Int = when (action) {
    TranslationTaskCommandAction.RETRY_MEMBERS -> R.string.native_failure_decision_retry
    TranslationTaskCommandAction.SKIP_MEMBERS -> R.string.native_failure_decision_skip
    TranslationTaskCommandAction.KEEP_ERROR -> R.string.native_failure_decision_keep
    else -> R.string.native_failure_decision_other
}

private fun failureReceiptLabel(receipt: TranslationFailureReceipt): Int = when (receipt) {
    TranslationFailureReceipt.RECEIVED -> R.string.native_failure_receipt_received
    TranslationFailureReceipt.DISPATCHED_NO_RECEIPT -> R.string.native_failure_receipt_uncertain
    TranslationFailureReceipt.NOT_DISPATCHED -> R.string.native_failure_receipt_not_dispatched
}

private fun failureUsageLabel(usage: UsageSource): Int = when (usage) {
    UsageSource.ACTUAL -> R.string.native_failure_usage_actual
    UsageSource.ESTIMATED -> R.string.native_failure_usage_estimated
    UsageSource.UNKNOWN -> R.string.native_failure_usage_unknown
}

private fun failureKindLabel(kind: ModelFailureKind): Int = when (kind) {
    ModelFailureKind.NETWORK -> R.string.native_failure_kind_network
    ModelFailureKind.TIMEOUT -> R.string.native_failure_kind_timeout
    ModelFailureKind.RATE_LIMIT -> R.string.native_failure_kind_rate_limit
    ModelFailureKind.SERVER -> R.string.native_failure_kind_server
    ModelFailureKind.AUTHENTICATION -> R.string.native_failure_kind_authentication
    ModelFailureKind.CONTEXT_OVERFLOW -> R.string.native_failure_kind_context_overflow
    ModelFailureKind.PARSING -> R.string.native_failure_kind_parsing
    ModelFailureKind.CONTENT -> R.string.native_failure_kind_content
    ModelFailureKind.TRUNCATED -> R.string.native_failure_kind_truncated
    ModelFailureKind.CONTENT_FILTERED -> R.string.native_failure_kind_content_filtered
    ModelFailureKind.EMPTY_RESPONSE -> R.string.native_failure_kind_empty_response
    ModelFailureKind.UNKNOWN -> R.string.native_failure_kind_unknown
}

private fun failureRejectionLabel(reason: TranslationQueueRejection): Int = when (reason) {
    TranslationQueueRejection.QUEUE_FULL -> R.string.native_failure_rejection_queue_full
    TranslationQueueRejection.IDENTITY_CONFLICT -> R.string.native_failure_rejection_identity_conflict
    TranslationQueueRejection.UNKNOWN_TASK -> R.string.native_failure_rejection_unknown_task
    TranslationQueueRejection.IDENTITY_MISMATCH -> R.string.native_failure_rejection_identity_mismatch
    TranslationQueueRejection.UNKNOWN_BATCH -> R.string.native_failure_rejection_unknown_batch
    TranslationQueueRejection.BATCH_CONFLICT -> R.string.native_failure_rejection_batch_conflict
    TranslationQueueRejection.BATCH_LIMIT -> R.string.native_failure_rejection_batch_limit
    TranslationQueueRejection.COMMAND_LIMIT -> R.string.native_failure_rejection_command_limit
    TranslationQueueRejection.COMMAND_CONFLICT -> R.string.native_failure_rejection_command_conflict
    TranslationQueueRejection.INVALID_TRANSITION -> R.string.native_failure_rejection_invalid_transition
    TranslationQueueRejection.TERMINAL_STATE -> R.string.native_failure_rejection_terminal_state
    TranslationQueueRejection.INVALID_EVIDENCE -> R.string.native_failure_rejection_invalid_evidence
    TranslationQueueRejection.MEMBER_NOT_IN_BATCH -> R.string.native_failure_rejection_member_not_in_batch
    TranslationQueueRejection.MEMBER_ALREADY_DECIDED -> R.string.native_failure_rejection_member_already_decided
    TranslationQueueRejection.MEMBER_STILL_UNRESOLVED -> R.string.native_failure_rejection_member_still_unresolved
    TranslationQueueRejection.COMMITTED_MEMBER -> R.string.native_failure_rejection_committed_member
    TranslationQueueRejection.UNCERTAIN_RECEIPT -> R.string.native_failure_rejection_uncertain_receipt
    TranslationQueueRejection.TERMINAL_FAILURE -> R.string.native_failure_rejection_terminal_failure
    TranslationQueueRejection.COMPLETION_NOT_PROVEN -> R.string.native_failure_rejection_completion_not_proven
    TranslationQueueRejection.INCOMPLETE_FAILURE_DECISIONS ->
        R.string.native_failure_rejection_incomplete_failure_decisions
}
