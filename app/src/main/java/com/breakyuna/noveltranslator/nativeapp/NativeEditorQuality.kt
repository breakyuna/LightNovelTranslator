package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

@Composable
internal fun NativeEditorQuality(draft: NativeEditDraft, busy: Boolean, enabled: Boolean,
    result: TextProofreadingResult?, onInspect: (TextProofreadingOptions) -> Unit) {
    var sourceTag by rememberSaveable(draft.ref.projectId.value, draft.ref.instanceId.value,
        draft.ref.generation.value, draft.item.id.value) { mutableStateOf(ProofreadingLanguage.AUTO.name) }
    var targetTag by rememberSaveable(draft.ref.projectId.value, draft.ref.instanceId.value,
        draft.ref.generation.value, draft.item.id.value) { mutableStateOf(ProofreadingLanguage.ZH.name) }
    val options = TextProofreadingOptions(ProofreadingLanguage.valueOf(sourceTag), ProofreadingLanguage.valueOf(targetTag))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.native_quality_title),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    modifier = Modifier.testTag("native-quality-check"),
                    onClick = { onInspect(options) },
                    enabled = !busy && enabled && !draft.conflict,
                ) {
                    Text(stringResource(R.string.native_quality_check))
                }
            }
            Text(stringResource(R.string.native_quality_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageMenu(R.string.native_remote_source, options.source, ProofreadingLanguage.entries, !busy) { sourceTag = it.name }
                LanguageMenu(R.string.native_remote_target, options.target,
                    ProofreadingLanguage.entries.filter { it != ProofreadingLanguage.AUTO }, !busy) { targetTag = it.name }
            }
            if (!enabled || draft.conflict) Text(stringResource(R.string.native_quality_unavailable),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled && !draft.conflict && result != null && result.options == options) {
                when {
                    !result.evaluated -> Text(stringResource(R.string.native_quality_skipped), style = MaterialTheme.typography.bodySmall)
                    result.codes.isEmpty() -> Text(stringResource(R.string.native_quality_clear),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    else -> result.codes.forEach {
                        Text(stringResource(it.qualityLabel()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (result.missingTermRuleIds.isNotEmpty())
                    Text(stringResource(R.string.native_quality_missing_terms, result.missingTermRuleIds.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun LanguageMenu(label: Int, value: ProofreadingLanguage, values: List<ProofreadingLanguage>,
    enabled: Boolean, onChange: (ProofreadingLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(stringResource(label) + ": " + value.tag) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            values.forEach { language -> DropdownMenuItem(text = { Text(language.tag) },
                onClick = { expanded = false; onChange(language) }) }
        }
    }
}

internal fun TextWarningCode.qualityLabel(): Int = when (this) {
    TextWarningCode.FOREIGN_CHAR_RESIDUE -> R.string.native_quality_foreign
    TextWarningCode.SIMILARITY -> R.string.native_quality_similarity
    TextWarningCode.LINE_COUNT_MISMATCH -> R.string.native_quality_lines
    TextWarningCode.GLOSSARY -> R.string.native_quality_glossary
    TextWarningCode.TEXT_PRESERVE -> R.string.native_quality_preserve
    TextWarningCode.PUNCTUATION_MISMATCH -> R.string.native_quality_punctuation
}
