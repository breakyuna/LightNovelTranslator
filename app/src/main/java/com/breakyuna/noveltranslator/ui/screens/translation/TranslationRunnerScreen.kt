package com.breakyuna.noveltranslator.ui.screens.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.translator.TranslationJobState
import com.breakyuna.noveltranslator.data.model.ChapterStatus
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.EmeraldAccent
import com.breakyuna.noveltranslator.ui.theme.PrimaryIndigo
import com.breakyuna.noveltranslator.ui.theme.RoseAccent
import com.breakyuna.noveltranslator.ui.theme.TertiaryAmber
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationRunnerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onNavigateToReader: (Long) -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val glossary by viewModel.activeGlossary.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val logs by viewModel.activeLogs.collectAsState()
    val requestLogs by viewModel.activeRequestLogs.collectAsState()
    val liveLogs by viewModel.liveLogs.collectAsState()
    val jobState by viewModel.translationState.collectAsState()
    val recoverableRun by viewModel.recoverableRun.collectAsState()

    var logTab by remember { mutableStateOf(0) } // 0: Live Pipeline Logs, 1: Chapter History Logs

    var selectedProviderId by remember { mutableStateOf<Long?>(null) }
    val defaultProvider = providers.firstOrNull { it.id == project?.defaultProviderId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()
    val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: defaultProvider

    var rangeStart by remember { mutableStateOf("1") }
    var rangeEnd by remember { mutableStateOf("${chapters.size.coerceAtLeast(1)}") }
    var isAutoContinuousMode by remember { mutableStateOf(true) }

    val dontShowWarning by viewModel.dontShowContinuousWarning.collectAsState()
    var showContinuousConfirmDialog by remember { mutableStateOf(false) }
    var dontRemindChecked by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(providers, project?.defaultProviderId) {
        if (providers.none { it.id == selectedProviderId }) selectedProviderId = defaultProvider?.id
    }
    LaunchedEffect(chapters.size) {
        if (rangeEnd == "1" || rangeEnd.toIntOrNull() !in 1..chapters.size.coerceAtLeast(1)) {
            rangeEnd = chapters.size.coerceAtLeast(1).toString()
        }
    }

    LaunchedEffect(recoverableRun?.id, jobState) {
        if (recoverableRun != null && jobState !is TranslationJobState.Running) {
            showRecoveryDialog = true
        }
    }

    val remainingChapters = chapters.filter { it.status != ChapterStatus.COMPLETED }
    val remainingWords = remainingChapters.sumOf { it.originalWordCount }
    val summaryOverhead = remainingChapters.sumOf { TokenCalculator.estimateTokens(it.summary.take(600)) }
    val glossaryOverhead = glossary.filter { !it.isAutoExtracted }.sumOf {
        TokenCalculator.estimateTokens(it.originalTerm + it.translatedTerm + it.notes) + 12L
    }.coerceAtMost(10_000L)
    val systemPromptOverhead = remainingChapters.size * 180L
    val retrySafetyFactor = 1.15
    val estPromptTokens = ((remainingWords * 1.35) + summaryOverhead + glossaryOverhead + systemPromptOverhead).times(retrySafetyFactor).toLong()
    val estCompTokens = (remainingWords * 1.25 * retrySafetyFactor).toLong()
    val estTotalCost = if (activeProvider != null) {
        TokenCalculator.calculateCost(estPromptTokens, estCompTokens, activeProvider.inputPricePerMillion, activeProvider.outputPricePerMillion)
    } else 0.0

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
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("cockpit_drawer_button")) {
                            Icon(Icons.Default.Menu, contentDescription = strings.navMenuDesc)
                        }
                    } else {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("cockpit_back_button")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                        }
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
                                    text = "In: ${activeProvider?.currency} ${activeProvider?.inputPricePerMillion}/M • Out: ${activeProvider?.currency} ${activeProvider?.outputPricePerMillion}/M",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(providers, key = { it.id }) { provider ->
                                FilterChip(
                                    selected = provider.id == activeProvider?.id,
                                    onClick = {
                                        selectedProviderId = provider.id
                                        viewModel.setActiveProjectProvider(provider.id)
                                    },
                                    label = { Text(provider.name, maxLines = 1) }
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
                                    if (dontShowWarning) {
                                        viewModel.startContinuousTranslation(activeProvider)
                                    } else {
                                        showContinuousConfirmDialog = true
                                    }
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

            // Live Translation Logs Section with Tab Switching
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = logTab == 0,
                        onClick = { logTab = 0 },
                        label = { Text("流水线实时 (${liveLogs.size})", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = logTab == 1,
                        onClick = { logTab = 1 },
                        label = { Text("翻译历史 (${logs.size})", fontSize = 12.sp) }
                    )
                }
                if (logTab == 0 && liveLogs.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearLiveLogs() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.clearLogsBtn, fontSize = 11.sp)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                if (logTab == 0) {
                    // Live pipeline execution logs
                    if (liveLogs.isEmpty()) {
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
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(liveLogs, key = { it.id }) { log ->
                                val tagColor = when (log.type) {
                                    com.breakyuna.noveltranslator.data.model.LiveLogType.SUCCESS -> EmeraldAccent
                                    com.breakyuna.noveltranslator.data.model.LiveLogType.ERROR -> RoseAccent
                                    com.breakyuna.noveltranslator.data.model.LiveLogType.WARNING -> TertiaryAmber
                                    com.breakyuna.noveltranslator.data.model.LiveLogType.STEP -> PrimaryIndigo
                                    com.breakyuna.noveltranslator.data.model.LiveLogType.INFO -> MaterialTheme.colorScheme.primary
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = tagColor.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = log.type.name,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = tagColor
                                                    )
                                                )
                                            }
                                            if (log.chapterIndex != null) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "第${log.chapterIndex}章",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                            }
                                            if (log.chunkInfo != null) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "[分块 ${log.chunkInfo}]",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )
                                            }
                                        }
                                        Text(
                                            text = log.timeFormatted,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = log.message,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (!log.detail.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = log.detail,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            maxLines = 3
                                        )
                                    }

                                    if (log.tokensInfo != null || log.costInfo != null) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (log.tokensInfo != null) {
                                                Text(
                                                    text = log.tokensInfo,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                            if (log.costInfo != null) {
                                                Text(
                                                    text = log.costInfo,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = TertiaryAmber, fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Chapter summary logs from DB
                    if (logs.isEmpty() && requestLogs.isEmpty()) {
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
                            val requestHistory = requestLogs
                            val totalPromptTokens = if (requestHistory.isNotEmpty()) requestHistory.sumOf { it.promptTokens } else logs.sumOf { it.promptTokens }
                            val totalCompletionTokens = if (requestHistory.isNotEmpty()) requestHistory.sumOf { it.completionTokens } else logs.sumOf { it.completionTokens }
                            val totalCost = if (requestHistory.isNotEmpty()) requestHistory.sumOf { it.estimatedCost } else logs.sumOf { it.estimatedCost }
                            val historyCurrency = requestHistory.map { it.currency.trim() }.firstOrNull { it.isNotBlank() }
                                ?: logs.map { it.currency.trim() }
                                .firstOrNull { it.isNotBlank() }
                                ?: activeProvider?.currency
                                ?: "USD"

                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "翻译历史汇总",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "${logs.size} 章级记录 · ${requestLogs.size} 次请求",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Prompt ${TokenCalculator.formatTokenCount(totalPromptTokens)}", style = MaterialTheme.typography.labelSmall)
                                            Text("Completion ${TokenCalculator.formatTokenCount(totalCompletionTokens)}", style = MaterialTheme.typography.labelSmall)
                                            Text("Total ${TokenCalculator.formatTokenCount(totalPromptTokens + totalCompletionTokens)}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                        Text(
                                            text = "累计费用：${TokenCalculator.formatCost(totalCost, historyCurrency)}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = TertiaryAmber)
                                        )
                                    }
                                }
                            }

                            items(logs, key = { it.id }) { log ->
                                val currency = log.currency.ifBlank {
                                    providers.firstOrNull { it.name == log.providerName }?.currency
                                        ?: activeProvider?.currency
                                        ?: "USD"
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(
                                        1.dp,
                                        if (log.isSuccess) EmeraldAccent.copy(alpha = 0.18f) else RoseAccent.copy(alpha = 0.35f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                            Text(
                                                text = "[${strings.chapterPrefix}${log.chapterIndex}] ${log.chapterTitle}",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (log.isSuccess) "成功" else "失败",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (log.isSuccess) EmeraldAccent else RoseAccent,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                        Text(
                                            text = log.message,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                        Text(
                                            text = "${log.providerName} • ${log.modelName}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Prompt ${log.promptTokens} · Completion ${log.completionTokens}", style = MaterialTheme.typography.labelSmall)
                                            Text("Total ${log.totalTokens}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(TokenCalculator.formatCost(log.estimatedCost, currency), style = MaterialTheme.typography.labelSmall.copy(color = TertiaryAmber, fontWeight = FontWeight.Bold))
                                            Text("${log.durationMs}ms • ${formatHistoryTimestamp(log.timestamp)}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
                                        }
                                    }
                                }
                            }

                            if (requestLogs.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "请求级明细（包含重试、失败和未知用量）",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(requestLogs.take(200), key = { "request_${it.id}" }) { request ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    ) {
                                        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                text = "${request.operation} · ${request.providerName}/${request.modelName}",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "尝试 #${request.attemptNumber} · ${if (request.isSuccess) "成功" else "失败"} · ${request.usageSource}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (request.isSuccess) EmeraldAccent else RoseAccent
                                            )
                                            Text(
                                                text = "Prompt ${request.promptTokens} · Completion ${request.completionTokens} · ${TokenCalculator.formatCost(request.estimatedCost, request.currency)} · ${request.durationMs}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            request.errorCategory?.let { category ->
                                                Text(
                                                    text = "$category${request.errorMessage?.let { ": $it" }.orEmpty()}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = RoseAccent,
                                                    maxLines = 2
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRecoveryDialog && recoverableRun != null && jobState !is TranslationJobState.Running) {
        val run = recoverableRun!!
        val matchingProvider = providers.firstOrNull { it.id == run.providerId }
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            icon = { Icon(Icons.Default.Restore, contentDescription = null, tint = PrimaryIndigo) },
            title = { Text("发现未完成的翻译任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("任务状态：${run.state}")
                    Text("Provider：${run.providerName} / ${run.modelName}")
                    Text("已记录费用：${TokenCalculator.formatCost(run.totalCost, run.currency)}")
                    if (matchingProvider == null) {
                        Text(
                            "原 Provider 当前不可用，不能安全继续此任务。",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "已完成分块会被复用；上次中断时正在进行的请求可能已被供应商计费。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = matchingProvider != null,
                    onClick = {
                        showRecoveryDialog = false
                        viewModel.resumeRecoverableTranslation()
                    }
                ) { Text("继续任务") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRecoveryDialog = false
                    viewModel.abandonRecoverableTranslation()
                }) { Text("放弃任务") }
            }
        )
    }

    if (showContinuousConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showContinuousConfirmDialog = false },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = TertiaryAmber) },
            title = { Text(strings.continuousWarningTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = strings.continuousWarningDesc,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${strings.continuousEstWords}: ${remainingChapters.size} (${remainingWords} ${strings.wordsUnit})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                text = "${strings.continuousEstTokens}: ~${TokenCalculator.formatTokenCount(estPromptTokens + estCompTokens)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${strings.continuousEstCost}: ${TokenCalculator.formatCost(estTotalCost, activeProvider?.currency ?: "USD")}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = TertiaryAmber)
                            )
                            Text(
                                text = "估算费用（含上下文、摘要、术语与安全系数，不等于供应商账单）",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                        }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontRemindChecked,
                            onCheckedChange = { dontRemindChecked = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = strings.dontRemindThisSession,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dontRemindChecked) {
                            viewModel.setDontShowContinuousWarning(true)
                        }
                        showContinuousConfirmDialog = false
                        if (activeProvider != null) {
                            viewModel.startContinuousTranslation(activeProvider)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text(strings.continueTranslation)
                }
            },
            dismissButton = {
                TextButton(onClick = { showContinuousConfirmDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

private fun formatHistoryTimestamp(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
