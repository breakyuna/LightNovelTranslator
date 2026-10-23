package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_quality_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.native_quality_hint), style = MaterialTheme.typography.bodySmall)
        LanguageMenu(R.string.native_remote_source, options.source, ProofreadingLanguage.entries, !busy) { sourceTag = it.name }
        LanguageMenu(R.string.native_remote_target, options.target,
            ProofreadingLanguage.entries.filter { it != ProofreadingLanguage.AUTO }, !busy) { targetTag = it.name }
        OutlinedButton(modifier = Modifier.testTag("native-quality-check"), onClick = { onInspect(options) }, enabled = !busy && enabled && !draft.conflict) {
            Text(stringResource(R.string.native_quality_check))
        }
        if (!enabled || draft.conflict) Text(stringResource(R.string.native_quality_unavailable))
        if (enabled && !draft.conflict && result != null && result.options == options) {
            when {
                !result.evaluated -> Text(stringResource(R.string.native_quality_skipped))
                result.codes.isEmpty() -> Text(stringResource(R.string.native_quality_clear))
                else -> result.codes.forEach { Text(stringResource(it.qualityLabel()), color = MaterialTheme.colorScheme.error) }
            }
            if (result.missingTermRuleIds.isNotEmpty())
                Text(stringResource(R.string.native_quality_missing_terms, result.missingTermRuleIds.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
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
