package com.breakyuna.noveltranslator.ui.screens.translation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.breakyuna.noveltranslator.data.model.LiveLogType
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationRunnerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onNavigateToReader: (Long) -> Unit,
    onNavigateToTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val jobState by viewModel.translationState.collectAsState()
    val liveLogs by viewModel.liveLogs.collectAsState()
    val logs by viewModel.activeLogs.collectAsState()
    val recoverableRun by viewModel.recoverableRun.collectAsState()

    var selectedProviderId by remember { mutableStateOf<Long?>(null) }
    val activeProvider = providers.firstOrNull { it.id == (selectedProviderId ?: project?.defaultProviderId) }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()

    var isAutoContinuousMode by remember { mutableStateOf(true) }
    var rangeStart by remember { mutableStateOf("1") }
    var rangeEnd by remember { mutableStateOf(chapters.size.coerceAtLeast(1).toString()) }

    var logTabSelected by remember { mutableStateOf(0) } // 0: Live Logs, 1: Chapter History
    var showContinuousConfirmDialog by remember { mutableStateOf(false) }
    var dontRemindChecked by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember(recoverableRun) { mutableStateOf(recoverableRun != null) }

    val completedChaptersCount = remember(chapters) { chapters.count { it.status == ChapterStatus.COMPLETED } }
    val progressPercent = if (chapters.isNotEmpty()) (completedChaptersCount.toFloat() / chapters.size.toFloat()) else 0f

    // Estimated costs
    val pendingChapters = remember(chapters) { chapters.filter { it.status != ChapterStatus.COMPLETED } }
    val pendingWordCount = remember(pendingChapters) { pendingChapters.sumOf { it.originalWordCount } }
    val estPromptTokens = (pendingWordCount * 1.5).toLong()
    val estCompletionTokens = (pendingWordCount * 1.5).toLong()
    val estTotalCost = activeProvider?.let {
        TokenCalculator.calculateCost(
            estPromptTokens,
            estCompletionTokens,
            it.inputPricePerMillion,
            it.outputPricePerMillion
        )
    } ?: 0.0

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = strings.runnerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "《${project?.title ?: ""}》 · $completedChaptersCount/${chapters.size} 章完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("runner_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.cancel,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToTasks,
                        modifier = Modifier.testTag("runner_task_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FormatListNumbered,
                            contentDescription = "并发任务队列",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Spacing.compactHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Active Provider Switcher
            AppGroupedSurface(contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = strings.activeProviderCardTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${activeProvider?.name ?: strings.noProvidersConfigured} · ${activeProvider?.selectedModel ?: ""}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (activeProvider != null) MaterialTheme.colorScheme.onSurface else StatusError
                        )
                    }

                    if (activeProvider != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "${activeProvider.currency} ${activeProvider.inputPricePerMillion}/M in",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                if (providers.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(providers, key = { it.id }) { provider ->
                            val isSelected = provider.id == activeProvider?.id
                            Surface(
                                onClick = {
                                    selectedProviderId = provider.id
                                    viewModel.setActiveProjectProvider(provider.id)
                                },
                                shape = SmallControlShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ) {
                                Text(
                                    text = provider.name,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. Real-time Status Card (Apple Grouped)
            AppGroupedSurface(contentPadding = PaddingValues(14.dp)) {
                when (val state = jobState) {
                    is TranslationJobState.Running -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = if (state.isPaused) StatusWarning else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.isPaused) "已暂停: 第 ${state.currentChapterIndex} 章" else "正在翻译: 第 ${state.currentChapterIndex} 章",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (state.isPaused) StatusWarning else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = state.currentChapterTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    is TranslationJobState.Finished -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = strings.batchCompleteTitle,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = StatusSuccess
                                )
                                Text(
                                    text = "共完成 ${state.translatedCount} 个章节翻译",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is TranslationJobState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = StatusError,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "翻译异常中断",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = StatusError
                                )
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "待译: ${pendingChapters.size} 章 (${TokenCalculator.formatTokenCount(estPromptTokens.toLong())} 字/词)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "完成进度: ${(progressPercent * 100).toInt()}% ($completedChaptersCount/${chapters.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "预估: ${TokenCalculator.formatCost(estTotalCost, activeProvider?.currency ?: "USD")}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }

            // 3. Translation Controls
            val isJobActive = jobState is TranslationJobState.Running
            val isPaused = (jobState as? TranslationJobState.Running)?.isPaused == true

            if (!isJobActive) {
                // Mode switcher
                AppSegmentedControl(
                    items = listOf("智能全书自动翻译", "指定章节区间"),
                    selectedIndex = if (isAutoContinuousMode) 0 else 1,
                    onItemSelected = { isAutoContinuousMode = (it == 0) }
                )

                if (!isAutoContinuousMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = rangeStart,
                            onValueChange = { rangeStart = it },
                            label = { Text("起始章") },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Text("➔", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = rangeEnd,
                            onValueChange = { rangeEnd = it },
                            label = { Text("截止章") },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                AppPrimaryButton(
                    text = if (isAutoContinuousMode) strings.startAutoTranslationBtn else strings.translateRangeBtn,
                    onClick = {
                        if (activeProvider != null) {
                            if (isAutoContinuousMode) {
                                viewModel.startContinuousTranslation(activeProvider)
                            } else {
                                val s = rangeStart.toIntOrNull() ?: 1
                                val e = rangeEnd.toIntOrNull() ?: chapters.size
                                viewModel.translateRange(s, e, activeProvider)
                            }
                        } else {
                            viewModel.showMessage(strings.noProvidersConfigured)
                        }
                    },
                    icon = Icons.Default.PlayArrow
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isPaused) {
                        Button(
                            onClick = { viewModel.resumeTranslation() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = ButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.resumeBtn)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.pauseTranslation() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = ButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusWarning)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.pauseBtn)
                        }
                    }

                    Button(
                        onClick = { viewModel.stopTranslation() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.stopBtn)
                    }
                }
            }

            // 4. Live Logs Viewport (Grouped Box)
            AppGroupedSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = logTabSelected == 0,
                            onClick = { logTabSelected = 0 },
                            label = { Text("实时日志 (${liveLogs.size})") },
                            shape = SmallControlShape
                        )
                        FilterChip(
                            selected = logTabSelected == 1,
                            onClick = { logTabSelected = 1 },
                            label = { Text("审计账单 (${logs.size})") },
                            shape = SmallControlShape
                        )
                    }

                    if (logTabSelected == 0 && liveLogs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearLiveLogs() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (logTabSelected == 0) {
                    if (liveLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = strings.noLogsYet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(liveLogs, key = { it.id }) { log ->
                                val tagColor = when (log.type) {
                                    LiveLogType.SUCCESS -> StatusSuccess
                                    LiveLogType.ERROR -> StatusError
                                    LiveLogType.WARNING -> StatusWarning
                                    LiveLogType.STEP -> AccentBlue
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "[${log.timeFormatted}]",
                                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        modifier = Modifier.width(68.dp)
                                    )
                                    Text(
                                        text = log.message,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                                        color = tagColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (logs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "暂无章节历史日志",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs, key = { it.id }) { log ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "第 ${log.chapterIndex} 章 · ${log.chapterTitle}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${log.providerName} · ${log.modelName} · ${TokenCalculator.formatTokenCount(log.promptTokens + log.completionTokens)} tok",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = TokenCalculator.formatCost(log.estimatedCost, log.currency),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StatusSuccess)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRecoveryDialog && recoverableRun != null) {
        val run = recoverableRun!!
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text("恢复中断的翻译任务") },
            text = {
                Text(
                    text = "检测到上一次翻译任务在《${project?.title ?: ""}》中中断，是否立即恢复？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecoveryDialog = false
                        viewModel.resumeRecoverableTranslation()
                    },
                    shape = ButtonShape
                ) {
                    Text("继续恢复翻译")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.abandonRecoverableTranslation()
                        showRecoveryDialog = false
                    }
                ) {
                    Text("放弃任务")
                }
            },
            shape = DialogShape
        )
    }
}
