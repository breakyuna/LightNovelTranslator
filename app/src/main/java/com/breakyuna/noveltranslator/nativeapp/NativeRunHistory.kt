package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.StoredRunState

@Composable
internal fun NativeRunHistory(state: NativeProjectUiState) {
    if (state.runLoadIncomplete) Text(stringResource(R.string.native_runs_load_error), color = MaterialTheme.colorScheme.error)
    if (state.recentRuns.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_runs_title),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        state.recentRuns.forEach { run ->
            val stateRes = when (run.state) {
                StoredRunState.PAUSED -> R.string.native_runs_paused
                StoredRunState.RUNNING -> R.string.native_runs_running
                StoredRunState.WAITING_APPROVAL -> R.string.native_runs_waiting
                StoredRunState.SUCCEEDED -> R.string.native_runs_succeeded
                StoredRunState.CANCELLED -> R.string.native_runs_cancelled
                StoredRunState.FAILED -> R.string.native_runs_failed
                StoredRunState.INTERRUPTED -> R.string.native_runs_interrupted
            }
            val tone = when (run.state) {
                StoredRunState.SUCCEEDED -> NativeTone.SUCCESS
                StoredRunState.RUNNING -> NativeTone.PRIMARY
                StoredRunState.PAUSED, StoredRunState.WAITING_APPROVAL, StoredRunState.INTERRUPTED -> NativeTone.WARNING
                StoredRunState.FAILED -> NativeTone.ERROR
                StoredRunState.CANCELLED -> NativeTone.NEUTRAL
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (run.owner == RuntimeOwner.TRANSLATION) R.string.native_runs_translation else R.string.native_agent) +
                                " · " + stringResource(stateRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (run.owner == RuntimeOwner.TRANSLATION) Text(stringResource(R.string.native_runs_committed, run.checkpoint.committedItems),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    run.translationUsage?.let { NativeTranslationUsage(run.handle.ref, it, run.handle.run) }
                    if (run.state == StoredRunState.INTERRUPTED) Text(stringResource(
                        if (run.needsReview) R.string.native_runs_uncertain else R.string.native_runs_recovery),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
