package com.example.ui.screens.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.llm.TokenCalculator
import com.example.core.translator.TranslationJobState
import com.example.data.model.ApiProviderEntity
import com.example.data.model.ChapterStatus
import com.example.ui.components.MetricStatCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.RoseAccent
import com.example.ui.theme.TertiaryAmber
import com.example.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationRunnerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onNavigateToReader: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val logs by viewModel.activeLogs.collectAsState()
    val jobState by viewModel.translationState.collectAsState()

    var selectedProviderId by remember { mutableStateOf<Long?>(null) }
    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
    val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: defaultProvider

    var rangeStart by remember { mutableStateOf("1") }
    var rangeEnd by remember { mutableStateOf("${chapters.size.coerceAtLeast(1)}") }
    var isAutoContinuousMode by remember { mutableStateOf(true) }

    val pendingCount = chapters.count { it.status == ChapterStatus.PENDING }
    val doneCount = chapters.count { it.status == ChapterStatus.COMPLETED }
    val errorCount = chapters.count { it.status == ChapterStatus.ERROR }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.runnerTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = project?.title ?: strings.novelLabel,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("cockpit_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model Provider Picker
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = strings.activeProviderCardTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (providers.isEmpty()) {
                        Text(
                            text = strings.noProvidersConfigured,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${activeProvider?.name} (${activeProvider?.selectedModel})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "In: $${activeProvider?.inputPricePerMillion}/M • Out: $${activeProvider?.outputPricePerMillion}/M",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Real-time Status Banner
            when (val state = jobState) {
                is TranslationJobState.Running -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (state.isPaused) strings.pausedStatus else if (state.totalChunksInChapter > 1) {
                                            "${strings.translatingStatus} ${strings.chapterPrefix}${state.currentChapterIndex} (Part ${state.currentChunkIndex}/${state.totalChunksInChapter})..."
                                        } else {
                                            "${strings.translatingStatus} ${strings.chapterPrefix}${state.currentChapterIndex}..."
                                        },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Text(
                                    text = "${state.completedCount} / ${state.totalToTranslate}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "《${state.currentChapterTitle}》",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            val runningProgress = if (state.totalToTranslate > 0) state.completedCount.toFloat() / state.totalToTranslate.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { runningProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Tokens: ${TokenCalculator.formatTokenCount(state.currentPromptTokens + state.currentCompletionTokens)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                                )
                                Text(
                                    text = "Current Cost: ${TokenCalculator.formatCost(state.currentCost, state.currency)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TertiaryAmber)
                                )
                            }
                        }
                    }
                }
                is TranslationJobState.Finished -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldAccent.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = strings.batchCompleteTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = EmeraldAccent)
                                )
                                Text(
                                    text = "${strings.translatedChaptersCount}: ${state.translatedCount} • ${strings.totalCostLabel}: ${TokenCalculator.formatCost(state.totalCost, state.currency)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                is TranslationJobState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = RoseAccent.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, RoseAccent.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RoseAccent)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Error: ${state.message}",
                                style = MaterialTheme.typography.bodySmall.copy(color = RoseAccent)
                            )
                        }
                    }
                }
                else -> {}
            }

            // Mode Selector: Continuous Auto vs Manual Range
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isAutoContinuousMode,
                    onClick = { isAutoContinuousMode = true },
                    label = { Text(strings.modeAutoContinuous) },
                    leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !isAutoContinuousMode,
                    onClick = { isAutoContinuousMode = false },
                    label = { Text(strings.modeManualRange) },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (!isAutoContinuousMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = rangeStart,
                        onValueChange = { rangeStart = it },
                        label = { Text(strings.fromChapLabel) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Text("➔", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = rangeEnd,
                        onValueChange = { rangeEnd = it },
                        label = { Text(strings.toChapLabel) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            // Translation Controls (Start, Pause, Resume, Stop)
            val isJobActive = jobState is TranslationJobState.Running
            val isPaused = (jobState as? TranslationJobState.Running)?.isPaused == true

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isJobActive) {
                    Button(
                        onClick = {
                            if (activeProvider != null) {
                                if (isAutoContinuousMode) {
                                    viewModel.startContinuousTranslation(activeProvider)
                                } else {
                                    val start = rangeStart.toIntOrNull() ?: 1
                                    val end = rangeEnd.toIntOrNull() ?: chapters.size
                                    viewModel.translateRange(start, end, activeProvider)
                                }
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        modifier = Modifier.weight(1f).testTag("start_translation_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAutoContinuousMode) strings.startAutoTranslationBtn else strings.translateRangeBtn)
                    }
                } else {
                    if (isPaused) {
                        Button(
                            onClick = { viewModel.resumeTranslation() },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("resume_translation_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.resumeBtn)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.pauseTranslation() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("pause_translation_button")
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.pauseBtn)
                        }
                    }

                    Button(
                        onClick = { viewModel.stopTranslation() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("stop_translation_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.stopBtn)
                    }
                }
            }

            // Live Translation Logs
            Text(
                text = "${strings.liveLogsTitle} (${logs.size})",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 4.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                if (logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = strings.logsEmptyState,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (log.isSuccess) EmeraldAccent else RoseAccent)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "[${strings.chapterPrefix}${log.chapterIndex}] ${log.message}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${log.modelName} • ${log.totalTokens} tok • ${TokenCalculator.formatCost(log.estimatedCost)} • ${log.durationMs}ms",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }
    }
}

