package com.breakyuna.noveltranslator.ui.screens.tasks

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.task.TaskStatus
import com.breakyuna.noveltranslator.core.task.TranslationTaskItem
import com.breakyuna.noveltranslator.data.model.TranslationLogEntity
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.i18n.EnglishStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class HistoryFilter {
    ALL,
    SUCCESS,
    FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskQueueScreen(
    viewModel: AppViewModel,
    initialTab: Int = 0,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val english = strings === EnglishStrings
    var currentTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    // --- Task Queue Data ---
    val tasks by viewModel.taskManager.tasks.collectAsState()
    val maxConcurrency by viewModel.taskManager.maxConcurrency.collectAsState()
    val isQueuePaused by viewModel.taskManager.isQueuePaused.collectAsState()

    var statusFilter by remember { mutableStateOf<TaskStatus?>(null) }
    val runningCount = tasks.count { it.status == TaskStatus.RUNNING }
    val queuedCount = tasks.count { it.status == TaskStatus.QUEUED }
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val failedCount = tasks.count { it.status == TaskStatus.FAILED }
    val filteredTasks = tasks.filter { statusFilter == null || it.status == statusFilter }

    // --- History Audit Data ---
    val allLogs by viewModel.allTranslationLogs.collectAsState()
    val allProjects by viewModel.allProjects.collectAsState()
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selectedLogForDetail by remember { mutableStateOf<TranslationLogEntity?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    val projectTitleMap = remember(allProjects) {
        allProjects.associate { it.id to it.title }
    }

    val filteredHistoryLogs = remember(allLogs, historyFilter) {
        when (historyFilter) {
            HistoryFilter.ALL -> allLogs
            HistoryFilter.SUCCESS -> allLogs.filter { it.isSuccess }
            HistoryFilter.FAILED -> allLogs.filter { !it.isSuccess }
        }
    }

    val totalTokens = remember(allLogs) { allLogs.sumOf { it.totalTokens } }
    val totalCost = remember(allLogs) { allLogs.sumOf { it.estimatedCost } }
    val successCount = remember(allLogs) { allLogs.count { it.isSuccess } }
    val successRate = if (allLogs.isNotEmpty()) (successCount * 100 / allLogs.size) else 100

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTitle(
                title = "后台任务与审计",
                subtitle = if (currentTab == 0) "并行翻译任务队列与调度管理" else "跨项目 LLM 请求与 Token 消耗审计",
                trailingContent = {
                    if (currentTab == 0) {
                        IconButton(
                            onClick = {
                                if (isQueuePaused) viewModel.taskManager.resumeQueue()
                                else viewModel.taskManager.pauseQueue()
                            },
                            modifier = Modifier.testTag("toggle_queue_pause_btn")
                        ) {
                            Icon(
                                imageVector = if (isQueuePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isQueuePaused) strings.resumeAllTasks else strings.pauseAllTasks,
                                tint = if (isQueuePaused) StatusSuccess else MaterialTheme.colorScheme.primary
                            )
                        }
                        if (completedCount > 0 || failedCount > 0) {
                            IconButton(
                                onClick = { viewModel.taskManager.clearCompletedTasks() },
                                modifier = Modifier.testTag("clear_completed_tasks_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = strings.clearCompletedTasks,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        if (allLogs.isNotEmpty()) {
                            IconButton(
                                onClick = { showClearHistoryConfirm = true },
                                modifier = Modifier.testTag("clear_history_logs_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = if (english) "Clear audit history" else "清空审计历史",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                horizontal = Spacing.compactHorizontalPadding,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Segment Switcher Tab (实时队列 / 审计历史)
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            onClick = { currentTab = 0 },
                            shape = RoundedCornerShape(9.dp),
                            color = if (currentTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent,
                            tonalElevation = if (currentTab == 0) 2.dp else 0.dp,
                            shadowElevation = if (currentTab == 0) 1.dp else 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatListNumbered,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (currentTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (runningCount > 0) "实时队列 ($runningCount)" else "实时队列",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (currentTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            onClick = { currentTab = 1 },
                            shape = RoundedCornerShape(9.dp),
                            color = if (currentTab == 1) MaterialTheme.colorScheme.surface else Color.Transparent,
                            tonalElevation = if (currentTab == 1) 2.dp else 0.dp,
                            shadowElevation = if (currentTab == 1) 1.dp else 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (currentTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (allLogs.isNotEmpty()) "审计历史 (${allLogs.size})" else "审计历史",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (currentTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ==================== TAB 0: LIVE TASK QUEUE ====================
            if (currentTab == 0) {
                // Concurrency Controller & Metrics Overview
                item {
                    AppGroupedSurface {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TaskMetricItem(
                                label = strings.tasksRunningCount,
                                value = "$runningCount",
                                color = MaterialTheme.colorScheme.primary
                            )
                            TaskMetricItem(
                                label = strings.tasksQueuedCount,
                                value = "$queuedCount",
                                color = StatusWarning
                            )
                            TaskMetricItem(
                                label = strings.tasksCompletedCount,
                                value = "$completedCount",
                                color = StatusSuccess
                            )
                            TaskMetricItem(
                                label = strings.tasksFailedCount,
                                value = "$failedCount",
                                color = if (failedCount > 0) StatusError else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        AppDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Concurrency Controller
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = strings.maxConcurrencyLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "同时进行 LLM 请求的章节数",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Concurrency Picker (1..5)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                (1..5).forEach { limit ->
                                    Surface(
                                        onClick = { viewModel.taskManager.setMaxConcurrency(limit) },
                                        shape = SmallControlShape,
                                        color = if (maxConcurrency == limit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "$limit",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = if (maxConcurrency == limit) FontWeight.Bold else FontWeight.Normal
                                                ),
                                                color = if (maxConcurrency == limit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Filter Chips Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TaskFilterTab(
                            text = "全部 (${tasks.size})",
                            selected = statusFilter == null,
                            onClick = { statusFilter = null }
                        )
                        TaskFilterTab(
                            text = "执行中 ($runningCount)",
                            selected = statusFilter == TaskStatus.RUNNING,
                            onClick = { statusFilter = TaskStatus.RUNNING }
                        )
                        TaskFilterTab(
                            text = "排队中 ($queuedCount)",
                            selected = statusFilter == TaskStatus.QUEUED,
                            onClick = { statusFilter = TaskStatus.QUEUED }
                        )
                        TaskFilterTab(
                            text = "已完成 ($completedCount)",
                            selected = statusFilter == TaskStatus.COMPLETED,
                            onClick = { statusFilter = TaskStatus.COMPLETED }
                        )
                        if (failedCount > 0) {
                            TaskFilterTab(
                                text = "失败 ($failedCount)",
                                selected = statusFilter == TaskStatus.FAILED,
                                onClick = { statusFilter = TaskStatus.FAILED }
                            )
                        }
                    }
                }

                // Task List
                if (filteredTasks.isEmpty()) {
                    item {
                        AppGroupedSurface(
                            modifier = Modifier.padding(top = 16.dp),
                            contentPadding = PaddingValues(32.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlaylistAddCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = strings.noTasksInQueue,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    item {
                        AppGroupedSurface(
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            filteredTasks.forEachIndexed { index, task ->
                                TaskRowItem(
                                    task = task,
                                    onPause = { viewModel.taskManager.pauseTask(task.id) },
                                    onResume = { viewModel.taskManager.resumeTask(task.id) },
                                    onRetry = { viewModel.taskManager.retryTask(task.id) },
                                    onCancel = { viewModel.taskManager.cancelTask(task.id) },
                                    onDelete = { viewModel.taskManager.removeTask(task.id) }
                                )
                                if (index < filteredTasks.lastIndex) {
                                    AppDivider(startIndent = 52.dp)
                                }
                            }
                        }
                    }
                }
            }

            // ==================== TAB 1: AUDIT & TOKEN LOGS ====================
            if (currentTab == 1) {
                // Metrics Overview
                item {
                    AppGroupedSurface {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TaskMetricItem(
                                label = "总消耗 Tokens",
                                value = formatTokenCount(totalTokens),
                                color = MaterialTheme.colorScheme.primary
                            )
                            TaskMetricItem(
                                label = "累计估算费用",
                                value = "$${String.format(Locale.US, "%.4f", totalCost)}",
                                color = StatusSuccess
                            )
                            TaskMetricItem(
                                label = "成功率",
                                value = "$successRate%",
                                color = if (successRate >= 90) StatusSuccess else StatusWarning
                            )
                        }
                    }
                }

                // Filter Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TaskFilterTab(
                            text = "全部 (${allLogs.size})",
                            selected = historyFilter == HistoryFilter.ALL,
                            onClick = { historyFilter = HistoryFilter.ALL }
                        )
                        TaskFilterTab(
                            text = "成功 ($successCount)",
                            selected = historyFilter == HistoryFilter.SUCCESS,
                            onClick = { historyFilter = HistoryFilter.SUCCESS }
                        )
                        TaskFilterTab(
                            text = "异常 (${allLogs.size - successCount})",
                            selected = historyFilter == HistoryFilter.FAILED,
                            onClick = { historyFilter = HistoryFilter.FAILED }
                        )
                    }
                }

                // History List
                if (filteredHistoryLogs.isEmpty()) {
                    item {
                        AppGroupedSurface(
                            modifier = Modifier.padding(top = 16.dp),
                            contentPadding = PaddingValues(32.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (allLogs.isEmpty()) "暂无翻译历史记录" else "无匹配记录",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "翻译或测试请求完成后将自动在此归档审计",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "请求记录 (${filteredHistoryLogs.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )
                    }

                    item {
                        AppGroupedSurface(
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            filteredHistoryLogs.forEachIndexed { index, log ->
                                val projTitle = projectTitleMap[log.projectId] ?: "项目 #${log.projectId}"
                                HistoryLogRowItem(
                                    log = log,
                                    projectTitle = projTitle,
                                    onClick = { selectedLogForDetail = log }
                                )
                                if (index < filteredHistoryLogs.lastIndex) {
                                    AppDivider(startIndent = 52.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Dialog for History
    selectedLogForDetail?.let { log ->
        val projTitle = projectTitleMap[log.projectId] ?: "项目 #${log.projectId}"
        HistoryDetailDialog(
            log = log,
            projectTitle = projTitle,
            onDismiss = { selectedLogForDetail = null }
        )
    }

    // Clear History Confirmation Dialog
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(if (english) "Clear translation history" else "清空翻译历史") },
            text = { Text(if (english) "Clear all translation logs and request audit records? Translated chapter text will not be deleted." else "确定要清空所有翻译日志与请求审计记录吗？该操作不会删除已翻译的章节正文。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistoryLogs()
                        showClearHistoryConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text(if (english) "Clear" else "清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }
}

@Composable
private fun TaskMetricItem(
    label: String,
    value: String,
    color: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
private fun TaskFilterTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = SmallControlShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TaskRowItem(
    task: TranslationTaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    val english = strings === EnglishStrings
    val (statusColor, statusLabel) = when (task.status) {
        TaskStatus.QUEUED -> StatusWarning to if (english) "Queued" else "排队中"
        TaskStatus.RUNNING -> AccentBlue to if (english) "Running" else "执行中"
        TaskStatus.PAUSED -> StatusWarning to if (english) "Paused" else "已暂停"
        TaskStatus.COMPLETED -> StatusSuccess to if (english) "Completed" else "已完成"
        TaskStatus.FAILED -> StatusError to if (english) "Failed" else "失败"
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant to if (english) "Cancelled" else "已取消"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("task_card_${task.chapterIndex}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chapter index badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${task.chapterIndex}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Task title & project
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.chapterTitle.ifBlank { if (english) "Chapter ${task.chapterIndex}" else "第 ${task.chapterIndex} 章" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (english) "${task.projectTitle} · ${task.providerName}" else "《${task.projectTitle}》 · ${task.providerName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status chip
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = statusLabel,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = statusColor
                    )
                )
            }
        }

        // Running progress bar
        if (task.status == TaskStatus.RUNNING) {
            Spacer(modifier = Modifier.height(8.dp))
            val chunkProgress = if (task.totalChunks > 0) (task.currentChunk.toFloat() / task.totalChunks.toFloat()) else 0f
            LinearProgressIndicator(
                progress = { chunkProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = AccentBlue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (english) "Chunk ${task.currentChunk} / ${task.totalChunks}" else "分块 ${task.currentChunk} / ${task.totalChunks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${TokenCalculator.formatTokenCount(task.promptTokens + task.completionTokens)} tok · ${TokenCalculator.formatCost(task.cost, task.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusSuccess
                )
            }
        }

        // Error message
        if (!task.errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = SmallControlShape,
                color = StatusError.copy(alpha = 0.08f)
            ) {
                Text(
                    text = task.errorMessage,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = StatusError
                )
            }
        }

        // Actions
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (task.status) {
                TaskStatus.RUNNING -> {
                    TextButton(onClick = onPause) {
                        Text(strings.pauseTaskBtn, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = StatusError)) {
                        Text(strings.cancelTaskBtn, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TaskStatus.QUEUED -> {
                    TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = StatusError)) {
                        Text(strings.cancelTaskBtn, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TaskStatus.PAUSED -> {
                    TextButton(onClick = onResume) {
                        Text(strings.resumeTaskBtn, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                TaskStatus.FAILED -> {
                    TextButton(onClick = onRetry) {
                        Text(strings.retryTaskBtn, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                TaskStatus.COMPLETED, TaskStatus.CANCELLED -> {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLogRowItem(
    log: TranslationLogEntity,
    projectTitle: String,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Icon
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (log.isSuccess) StatusSuccess.copy(alpha = 0.12f)
                    else StatusError.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (log.isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (log.isSuccess) StatusSuccess else StatusError,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title & Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$projectTitle · 第 ${log.chapterIndex} 章 ${log.chapterTitle}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${log.providerName} / ${log.modelName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Tokens & Cost
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${log.totalTokens} Tok",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (log.estimatedCost > 0) "$${String.format(Locale.US, "%.4f", log.estimatedCost)}" else "${log.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HistoryDetailDialog(
    log: TranslationLogEntity,
    projectTitle: String,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val english = strings === EnglishStrings
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (english) "Request audit details" else "请求审计详情",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppStatusRow(label = if (english) "Project" else "所属项目", value = projectTitle)
                AppStatusRow(label = if (english) "Chapter" else "章节序号", value = if (english) "Chapter ${log.chapterIndex}" else "第 ${log.chapterIndex} 章")
                AppStatusRow(label = if (english) "Chapter title" else "章节标题", value = log.chapterTitle.ifBlank { if (english) "Untitled" else "无标题" })
                AppStatusRow(label = if (english) "Provider" else "服务供应商", value = log.providerName)
                AppStatusRow(label = if (english) "Model" else "调用模型", value = log.modelName)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AppStatusRow(label = "Prompt Tokens", value = "${log.promptTokens}")
                AppStatusRow(label = "Completion Tokens", value = "${log.completionTokens}")
                AppStatusRow(label = "Total Tokens", value = "${log.totalTokens}")
                AppStatusRow(
                    label = if (english) "Estimated cost" else "预估费用",
                    value = "$${String.format(Locale.US, "%.5f", log.estimatedCost)} ${log.currency}",
                    valueColor = StatusSuccess
                )
                AppStatusRow(label = if (english) "Duration" else "耗时", value = "${log.durationMs} ms")
                AppStatusRow(label = if (english) "Request time" else "请求时间", value = timeStr)
                AppStatusRow(
                    label = if (english) "Status" else "状态",
                    value = if (log.isSuccess) { if (english) "Success" else "成功" } else { if (english) "Failed / error" else "失败/异常" },
                    valueColor = if (log.isSuccess) StatusSuccess else StatusError
                )
                if (log.message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (english) "Notes / error:" else "附注/错误信息:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = SmallControlShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = log.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
        shape = DialogShape
    )
}

private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
        else -> tokens.toString()
    }
}
