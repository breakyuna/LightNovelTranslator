package com.breakyuna.noveltranslator.ui.screens.tasks

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskQueueScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val tasks by viewModel.taskManager.tasks.collectAsState()
    val maxConcurrency by viewModel.taskManager.maxConcurrency.collectAsState()
    val isQueuePaused by viewModel.taskManager.isQueuePaused.collectAsState()

    var statusFilter by remember { mutableStateOf<TaskStatus?>(null) }

    val runningCount = tasks.count { it.status == TaskStatus.RUNNING }
    val queuedCount = tasks.count { it.status == TaskStatus.QUEUED }
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val failedCount = tasks.count { it.status == TaskStatus.FAILED }

    val filteredTasks = tasks.filter { statusFilter == null || it.status == statusFilter }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTitle(
                title = strings.taskQueueTitle,
                subtitle = "多章节并行翻译与队列调度",
                trailingContent = {
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
                    if (completedCount > 0) {
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
            // Concurrency Controller & Metrics Overview
            item {
                AppGroupedSurface {
                    // Metrics Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem(
                            label = strings.tasksRunningCount,
                            value = "$runningCount",
                            color = MaterialTheme.colorScheme.primary
                        )
                        MetricItem(
                            label = strings.tasksQueuedCount,
                            value = "$queuedCount",
                            color = StatusWarning
                        )
                        MetricItem(
                            label = strings.tasksCompletedCount,
                            value = "$completedCount",
                            color = StatusSuccess
                        )
                        MetricItem(
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

                        // Concurrency Picker (1..6)
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
                    FilterTabApple(
                        text = "全部 (${tasks.size})",
                        selected = statusFilter == null,
                        onClick = { statusFilter = null }
                    )
                    FilterTabApple(
                        text = "执行中 ($runningCount)",
                        selected = statusFilter == TaskStatus.RUNNING,
                        onClick = { statusFilter = TaskStatus.RUNNING }
                    )
                    FilterTabApple(
                        text = "排队中 ($queuedCount)",
                        selected = statusFilter == TaskStatus.QUEUED,
                        onClick = { statusFilter = TaskStatus.QUEUED }
                    )
                    FilterTabApple(
                        text = "已完成 ($completedCount)",
                        selected = statusFilter == TaskStatus.COMPLETED,
                        onClick = { statusFilter = TaskStatus.COMPLETED }
                    )
                    if (failedCount > 0) {
                        FilterTabApple(
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
                            TaskRowApple(
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
    }
}

@Composable
private fun MetricItem(
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
private fun FilterTabApple(
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
private fun TaskRowApple(
    task: TranslationTaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val (statusColor, statusLabel) = when (task.status) {
        TaskStatus.QUEUED -> StatusWarning to "排队中"
        TaskStatus.RUNNING -> AccentBlue to "执行中"
        TaskStatus.PAUSED -> StatusWarning to "已暂停"
        TaskStatus.COMPLETED -> StatusSuccess to "已完成"
        TaskStatus.FAILED -> StatusError to "失败"
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant to "已取消"
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
                    text = task.chapterTitle.ifBlank { "第 ${task.chapterIndex} 章" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "《${task.projectTitle}》 · ${task.providerName}",
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
                    text = "分块 ${task.currentChunk} / ${task.totalChunks}",
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
                        Text("暂停", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = StatusError)) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TaskStatus.QUEUED -> {
                    TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = StatusError)) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TaskStatus.PAUSED -> {
                    TextButton(onClick = onResume) {
                        Text("恢复", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                TaskStatus.FAILED -> {
                    TextButton(onClick = onRetry) {
                        Text("重试", style = MaterialTheme.typography.labelMedium)
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
