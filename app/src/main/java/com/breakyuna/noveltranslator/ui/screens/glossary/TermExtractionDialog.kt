package com.breakyuna.noveltranslator.ui.screens.glossary

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.LegacyGlossaryCandidateVoting
import com.breakyuna.noveltranslator.data.model.ProjectEntity
import com.breakyuna.noveltranslator.data.model.TermExtractionCandidate
import com.breakyuna.noveltranslator.data.model.TermExtractionUiState
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

enum class ExtractionScope {
    ALL,
    FIRST_5,
    FIRST_20,
    CUSTOM_RANGE
}

@Composable
fun TermExtractionDialog(
    viewModel: AppViewModel,
    project: ProjectEntity,
    provider: ApiProviderEntity,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val extractionState by viewModel.termExtractionState.collectAsState()

    var scopeType by remember { mutableStateOf(ExtractionScope.FIRST_5) }
    var customStartText by remember { mutableStateOf("1") }
    var customEndText by remember { mutableStateOf("${minOf(project.totalChapters, 10)}") }

    val selectedCandidates = remember { mutableStateListOf<TermExtractionCandidate>() }

    // Sync selected candidates when entering Review state
    LaunchedEffect(extractionState) {
        if (extractionState is TermExtractionUiState.Review) {
            val review = extractionState as TermExtractionUiState.Review
            selectedCandidates.clear()
            selectedCandidates.addAll(
                review.candidates.filter { candidate ->
                    LegacyGlossaryCandidateVoting.isHighConfidenceForBatch(candidate.term)
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (extractionState is TermExtractionUiState.Scanning) {
                viewModel.stopTermExtraction()
            } else {
                viewModel.dismissTermExtraction()
                onDismiss()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (extractionState) {
                        is TermExtractionUiState.Review -> strings.extractionReviewTitle
                        else -> strings.termExtractionDialogTitle
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                when (val state = extractionState) {
                    is TermExtractionUiState.Idle -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Text(
                                    text = String.format(strings.projectBoundNotice, project.title),
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            Text(
                                text = strings.extractionScopeLabel,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    FilterChip(
                                        selected = scopeType == ExtractionScope.FIRST_5,
                                        onClick = { scopeType = ExtractionScope.FIRST_5 },
                                        label = { Text(String.format(strings.scopeFirstNChapters, 5)) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = scopeType == ExtractionScope.FIRST_20,
                                        onClick = { scopeType = ExtractionScope.FIRST_20 },
                                        label = { Text(String.format(strings.scopeFirstNChapters, 20)) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = scopeType == ExtractionScope.ALL,
                                        onClick = { scopeType = ExtractionScope.ALL },
                                        label = { Text(strings.scopeAllChapters) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = scopeType == ExtractionScope.CUSTOM_RANGE,
                                        onClick = { scopeType = ExtractionScope.CUSTOM_RANGE },
                                        label = { Text(strings.scopeCustomRange) }
                                    )
                                }
                            }

                            if (scopeType == ExtractionScope.CUSTOM_RANGE) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customStartText,
                                        onValueChange = { customStartText = it },
                                        label = { Text(strings.fromChapLabel) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = customEndText,
                                        onValueChange = { customEndText = it },
                                        label = { Text(strings.toChapLabel) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Text(
                                text = "使用模型: ${provider.name} (${provider.selectedModel})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is TermExtractionUiState.Scanning -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = {
                                    if (state.totalWindows > 0) (state.currentWindowIndex.toFloat() / state.totalWindows.toFloat()) else 0f
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = PrimaryIndigo,
                                trackColor = PrimaryIndigo.copy(alpha = 0.2f)
                            )

                            Text(
                                text = String.format(
                                    strings.extractionProgressScanning,
                                    state.currentChapterIndex,
                                    state.currentWindowIndex,
                                    state.totalWindows,
                                    state.discoveredTerms.size
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )

                            Text(
                                text = "《${state.currentChapterTitle}》",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "${TokenCalculator.formatTokenCount(state.promptTokens + state.completionTokens)} tokens",
                                        style = MaterialTheme.typography.labelSmall.copy(color = TertiaryAmber, fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "${TokenCalculator.formatCost(state.estimatedCost, state.currency)}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = EmeraldAccent, fontWeight = FontWeight.Bold)
                                    )
                                    if (state.isPaused) {
                                        Text(
                                            text = "⏸ 已暂停",
                                            style = MaterialTheme.typography.labelSmall.copy(color = RoseAccent, fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (state.isPaused) viewModel.resumeTermExtraction()
                                        else viewModel.pauseTermExtraction()
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (state.isPaused) strings.resumeExtraction else strings.pauseExtraction)
                                }

                                Button(
                                    onClick = { viewModel.stopTermExtraction() },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(strings.stopExtraction)
                                }
                            }
                        }
                    }

                    is TermExtractionUiState.Review -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(strings.extractionCandidatesFound, state.candidates.size),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row {
                                    TextButton(onClick = {
                                        selectedCandidates.clear()
                                        selectedCandidates.addAll(state.candidates)
                                    }) {
                                        Text(strings.selectAll, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = {
                                        selectedCandidates.clear()
                                    }) {
                                        Text(strings.deselectAll, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(state.candidates, key = TermExtractionCandidate::id) { candidate ->
                                    val isChecked = selectedCandidates.any { it.id == candidate.id }
                                    CandidateTermCard(
                                        term = candidate.term,
                                        isSelected = isChecked,
                                        onToggle = {
                                            if (isChecked) {
                                                selectedCandidates.removeAll { it.id == candidate.id }
                                            } else {
                                                selectedCandidates.add(candidate)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    is TermExtractionUiState.Finished -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "成功添加 ${state.savedCount} 个专有名词！",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "消耗: ${TokenCalculator.formatCost(state.cost, state.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is TermExtractionUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = RoseAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "提炼失败",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = RoseAccent)
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val state = extractionState) {
                is TermExtractionUiState.Idle -> {
                    Button(
                        onClick = {
                            val firstN = when (scopeType) {
                                ExtractionScope.FIRST_5 -> 5
                                ExtractionScope.FIRST_20 -> 20
                                else -> null
                            }
                            val start = if (scopeType == ExtractionScope.CUSTOM_RANGE) customStartText.toIntOrNull() ?: 1 else 1
                            val end = if (scopeType == ExtractionScope.CUSTOM_RANGE) customEndText.toIntOrNull() ?: 10 else 1000

                            viewModel.startControlledTermExtraction(
                                projectId = project.id,
                                provider = provider,
                                scopeType = scopeType,
                                firstN = firstN,
                                startChapter = start,
                                endChapter = end
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.startExtraction)
                    }
                }
                is TermExtractionUiState.Review -> {
                    Button(
                        onClick = {
                            viewModel.saveExtractedTerms(project.id, selectedCandidates.map { it.term })
                            viewModel.dismissTermExtraction()
                            onDismiss()
                        },
                        enabled = selectedCandidates.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(String.format(strings.saveSelectedTerms, selectedCandidates.size))
                    }
                }
                is TermExtractionUiState.Finished -> {
                    Button(onClick = {
                        viewModel.dismissTermExtraction()
                        onDismiss()
                    }) {
                        Text(strings.close)
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (extractionState !is TermExtractionUiState.Scanning) {
                TextButton(onClick = {
                    viewModel.dismissTermExtraction()
                    onDismiss()
                }) {
                    Text(strings.cancel)
                }
            }
        }
    )
}

@Composable
private fun CandidateTermCard(
    term: GlossaryEntity,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val hasConflict = LegacyGlossaryCandidateVoting.hasConflict(term)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            when {
                hasConflict -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = term.originalTerm,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = term.category.name,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                Text(
                    text = "➔ ${term.translatedTerm}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                )
                if (hasConflict) {
                    Text(
                        text = "存在译名或类别冲突，请人工确认",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                LegacyGlossaryCandidateVoting.evidenceSummary(term)?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val displayNotes = LegacyGlossaryCandidateVoting.displayNotes(term)
                if (displayNotes.isNotBlank()) {
                    Text(
                        text = displayNotes,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
