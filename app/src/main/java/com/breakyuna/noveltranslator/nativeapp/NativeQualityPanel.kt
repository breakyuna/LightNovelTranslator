package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

internal class NativeQualityActions(val scan: (TextProofreadingOptions) -> Unit,
    val filter: (TextQualityFilter) -> Unit, val clear: () -> Unit)

@Composable
internal fun NativeQualityPanel(state: NativeProjectUiState, actions: NativeQualityActions) {
    val ref = state.project?.ref ?: return
    var scan by rememberSaveable(ref.projectId.value, ref.instanceId.value, ref.generation.value) { mutableStateOf(false) }
    var filter by rememberSaveable(ref.projectId.value, ref.instanceId.value, ref.generation.value) { mutableStateOf(false) }
    val enabled = !state.busy && !state.selectingItems && state.manualBatch == null && state.editor == null
    if (scan) QualityScanDialog(state, { scan = false }) { scan = false; actions.scan(it) }
    val view = state.qualityView
    if (filter && view != null && state.qualityCurrent) QualityFilterDialog(view, { filter = false }) {
        filter = false; actions.filter(it)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row {
            TextButton(onClick = { scan = true }, enabled = enabled && !state.policyLoadIncomplete && state.policyDraft?.dirty != true,
                modifier = Modifier.testTag("native-quality-scan")) { Text(stringResource(R.string.native_quality_scan)) }
            if (view != null) {
                TextButton(onClick = { filter = true }, enabled = enabled && state.qualityCurrent,
                    modifier = Modifier.testTag("native-quality-filter")) { Text(stringResource(R.string.native_quality_filter)) }
                TextButton(onClick = actions.clear, enabled = enabled,
                    modifier = Modifier.testTag("native-quality-clear")) { Text(stringResource(R.string.native_quality_close)) }
            }
        }
        state.qualityProgress?.let { Text(stringResource(R.string.native_quality_progress, it.scanned, it.total)) }
        if (view != null) {
            if (!state.qualityCurrent) Text(stringResource(R.string.native_quality_stale), color = MaterialTheme.colorScheme.error)
            else Text(stringResource(R.string.native_quality_summary, view.ids.size, view.index.facts.size,
                view.index.evaluatedCount, view.index.cleanCount), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QualityScanDialog(state: NativeProjectUiState, dismiss: () -> Unit, scan: (TextProofreadingOptions) -> Unit) {
    var source by rememberSaveable(state.project?.ref) { mutableStateOf(ProofreadingLanguage.AUTO.name) }
    var target by rememberSaveable(state.project?.ref) { mutableStateOf(ProofreadingLanguage.ZH.name) }
    QualityDialog(dismiss) {
        Text(stringResource(R.string.native_quality_scan), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_quality_scan_hint))
            Text(stringResource(R.string.native_quality_hint), style = MaterialTheme.typography.bodySmall)
            LanguageMenu(R.string.native_remote_source, ProofreadingLanguage.valueOf(source), ProofreadingLanguage.entries, !state.busy) { source = it.name }
            LanguageMenu(R.string.native_remote_target, ProofreadingLanguage.valueOf(target),
                ProofreadingLanguage.entries.filter { it != ProofreadingLanguage.AUTO }, !state.busy) { target = it.name }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
            TextButton(onClick = { scan(TextProofreadingOptions(ProofreadingLanguage.valueOf(source), ProofreadingLanguage.valueOf(target))) },
                enabled = !state.busy && state.policyDraft?.dirty != true && !state.policyLoadIncomplete,
                modifier = Modifier.testTag("native-quality-scan-confirm")) { Text(stringResource(R.string.native_quality_scan)) }
        }
    }
}

@Composable
private fun QualityFilterDialog(view: TextQualitySelection, dismiss: () -> Unit, apply: (TextQualityFilter) -> Unit) {
    var outcome by rememberSaveable(view.index.ref, view.index.revision) { mutableStateOf(view.filter.outcome.name) }
    var code by rememberSaveable(view.index.ref, view.index.revision) { mutableStateOf(view.filter.warning?.name) }
    var term by rememberSaveable(view.index.ref, view.index.revision) { mutableStateOf(view.filter.termRuleId) }
    QualityDialog(dismiss) {
        Text(stringResource(R.string.native_quality_filter), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f)) {
            items(TextQualityOutcome.entries, key = { "outcome:" + it.name }) { item ->
                val count = when (item) {
                    TextQualityOutcome.ALL -> view.index.facts.size
                    TextQualityOutcome.NOT_EVALUATED -> view.index.facts.size - view.index.evaluatedCount
                    TextQualityOutcome.NO_WARNING -> view.index.cleanCount
                    TextQualityOutcome.ANY_WARNING -> view.index.evaluatedCount - view.index.cleanCount
                }
                QualityChoice(stringResource(item.label()) + " ($count)", outcome == item.name && code == null) {
                    outcome = item.name; code = null; term = null
                }
            }
            items(TextWarningCode.entries, key = { "warning:" + it.name }) { warning ->
                QualityChoice(stringResource(warning.qualityLabel()) + " (${view.index.warningCounts[warning] ?: 0})", code == warning.name) {
                    outcome = TextQualityOutcome.ANY_WARNING.name; code = warning.name; term = null
                }
            }
            item { HorizontalDivider(); Text(stringResource(R.string.native_quality_term_filter)) }
            item { QualityChoice(stringResource(R.string.native_quality_all_terms), term == null) { term = null } }
            items(view.index.missingTermCounts.entries.toList(), key = { "term:" + it.key }) { entry ->
                QualityChoice(entry.key + " (${entry.value})", term == entry.key) {
                    term = entry.key; outcome = TextQualityOutcome.ANY_WARNING.name; code = TextWarningCode.GLOSSARY.name
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_cancel)) }
            TextButton(onClick = { apply(TextQualityFilter(TextQualityOutcome.valueOf(outcome), code?.let(TextWarningCode::valueOf), term)) },
                modifier = Modifier.testTag("native-quality-filter-apply")) { Text(stringResource(R.string.native_quality_apply_filter)) }
        }
    }
}

@Composable
private fun QualityChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun QualityDialog(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

private fun TextQualityOutcome.label(): Int = when (this) {
    TextQualityOutcome.ALL -> R.string.native_quality_all
    TextQualityOutcome.NOT_EVALUATED -> R.string.native_quality_unevaluated
    TextQualityOutcome.NO_WARNING -> R.string.native_quality_no_warning
    TextQualityOutcome.ANY_WARNING -> R.string.native_quality_any_warning
}
