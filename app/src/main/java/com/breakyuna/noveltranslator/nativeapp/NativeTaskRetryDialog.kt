package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeTaskRetryDialog(review: TranslationTaskReview,
    load: suspend (LocalProjectRef, RunIdentity, Long) -> TranslationTaskMemberPage,
    prepare: suspend (TranslationTaskReview, Long, List<TranslationTaskMemberReview>, CommandId) -> NativeTranslationResumePreview,
    dismiss: () -> Unit, ready: (NativeTranslationResumePreview) -> Unit,
    keep: (suspend (TranslationTaskReview, Long, List<TranslationTaskMemberReview>, CommandId) -> Unit)? = null) {
    var page by remember(review) { mutableStateOf<TranslationTaskMemberPage?>(null) }
    var selected by remember(review) { mutableStateOf<Set<ItemId>>(emptySet()) }
    var failed by remember(review) { mutableStateOf(false) }
    var busy by remember(review) { mutableStateOf(false) }
    val command = remember(review, page?.revision, selected) { CommandId(java.util.UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(after: Long) {
        busy = true; failed = false
        try { page = load(review.ref, review.run, after); selected = emptySet() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    LaunchedEffect(review) { fetch(0) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.native_tasks_retry_selected)) },
        text = { Column {
            Text(stringResource(R.string.native_tasks_retry_notice))
            if (failed) Text(stringResource(R.string.native_tasks_decision_error))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(page?.members.orEmpty(), key = { it.id.value }) { member ->
                    Row {
                        Checkbox(member.id in selected, enabled = !busy && member.status == TextItemStatus.ERROR && member.decision == null,
                            onCheckedChange = { checked -> selected = if (checked) selected + member.id else selected - member.id })
                        Column {
                        member.decision?.let { Text(stringResource(R.string.native_tasks_member_decided)) }
                        Text(stringResource(R.string.native_tasks_retry_member, member.id.value,
                            stringResource(if (member.status == TextItemStatus.ERROR) R.string.native_tasks_failed
                                else R.string.native_tasks_retry_ineligible)))
                        }
                    }
                }
            }
            if (keep != null) TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { scope.launch {
                busy = true; failed = false
                try {
                    val current = requireNotNull(page)
                    keep(review, current.revision, current.members.filter { it.id in selected }, command)
                    dismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            } }) { Text(stringResource(R.string.native_tasks_keep_errors)) }
            Row {
                TextButton(enabled = !busy, onClick = { scope.launch { fetch(0) } }) { Text(stringResource(R.string.native_refresh)) }
                page?.next?.let { next -> TextButton(enabled = !busy, onClick = { scope.launch { fetch(next) } }) {
                    Text(stringResource(R.string.native_tasks_more))
                } }
            }
        } },
        confirmButton = { TextButton(enabled = !busy && selected.isNotEmpty() && review.unresolvedRequests == 0, onClick = { scope.launch {
            busy = true; failed = false
            try {
                val current = requireNotNull(page)
                ready(prepare(review, current.revision, current.members.filter { it.id in selected }, command))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        } }) { Text(stringResource(R.string.native_tasks_retry_prepare)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.native_cancel)) } })
}
