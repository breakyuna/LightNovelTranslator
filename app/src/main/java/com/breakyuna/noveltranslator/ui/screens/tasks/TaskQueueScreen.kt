package com.breakyuna.noveltranslator.ui.screens.tasks

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.task.TaskStatus
import com.breakyuna.noveltranslator.core.task.TranslationTaskItem
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskQueueScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = strings.taskQueueTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${strings.tasksRunningCount}: $runningCount • ${strings.tasksQueuedCount}: $queuedCount",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("task_queue_drawer_btn")) {
                            Icon(Icons.Default.Menu, contentDescription = strings.navMenuDesc)
                        }
                    } else {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("task_queue_back_btn")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                        }
                    }
                },
                actions = {
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
                            tint = if (isQueuePaused) EmeraldAccent else TertiaryAmber
                        )
                    }
                    IconButton(
                        onClick = { viewModel.taskManager.clearCompletedTasks() },
                        modifier = Modifier.testTag("clear_completed_tasks_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = strings.clearCompletedTasks)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Concurrency Controller Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.maxConcurrencyLabel,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "$maxConcurrency ${strings.tasksRunningCount}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = strings.concurrencyLimitNotice,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            (1..6).forEach { limit ->
                                FilterChip(
                                    selected = maxConcurrency == limit,
                                    onClick = { viewModel.taskManager.setMaxConcurrency(limit) },
                                    label = { Text("$limit", fontWeight = FontWeight.SemiBold) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Summary Metrics Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricChip(
                        label = strings.tasksRunningCount,
                        count = runningCount,
                        color = SecondaryCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = strings.tasksQueuedCount,
                        count = queuedCount,
                        color = TertiaryAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = strings.tasksCompletedCount,
                        count = completedCount,
                        color = EmeraldAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = strings.tasksFailedCount,
                        count = failedCount,
                        color = RoseAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Status Filter Row
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = statusFilter == null,
                            onClick = { statusFilter = null },
                            label = { Text("${strings.filterAll} (${tasks.size})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == TaskStatus.RUNNING,
                            onClick = { statusFilter = TaskStatus.RUNNING },
                            label = { Text("${strings.tasksRunningCount} ($runningCount)") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == TaskStatus.QUEUED,
                            onClick = { statusFilter = TaskStatus.QUEUED },
                            label = { Text("${strings.tasksQueuedCount} ($queuedCount)") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == TaskStatus.COMPLETED,
                            onClick = { statusFilter = TaskStatus.COMPLETED },
                            label = { Text("${strings.tasksCompletedCount} ($completedCount)") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == TaskStatus.FAILED,
                            onClick = { statusFilter = TaskStatus.FAILED },
                            label = { Text("${strings.tasksFailedCount} ($failedCount)") }
                        )
                    }
                }
            }

            // Tasks List
            if (filteredTasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAddCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = strings.noTasksInQueue,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onPause = { viewModel.taskManager.pauseTask(task.id) },
                        onResume = { viewModel.taskManager.resumeTask(task.id) },
                        onRetry = { viewModel.taskManager.retryTask(task.id) },
                        onCancel = { viewModel.taskManager.cancelTask(task.id) },
                        onDelete = { viewModel.taskManager.removeTask(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = color)
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: TranslationTaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current

    val (statusColor, statusLabel) = when (task.status) {
        TaskStatus.QUEUED -> TertiaryAmber to strings.taskStatusQueued
        TaskStatus.RUNNING -> SecondaryCyan to strings.taskStatusRunning
        TaskStatus.PAUSED -> TertiaryAmber to strings.taskStatusPaused
        TaskStatus.COMPLETED -> EmeraldAccent to strings.taskStatusCompleted
        TaskStatus.FAILED -> RoseAccent to strings.taskStatusFailed
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant to strings.taskStatusCancelled
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.chapterIndex}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.status == TaskStatus.RUNNING)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (task.status == TaskStatus.RUNNING) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Chapter & Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(statusColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${task.chapterIndex}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.chapterTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "《${task.projectTitle}》 • ${task.providerName} (${task.modelName})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = statusColor
                        )
                    )
                }
            }

            // Progress Bar (if running or has chunks)
            if (task.status == TaskStatus.RUNNING) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (task.totalChunks > 0) (task.currentChunk.toFloat() / task.totalChunks.toFloat()) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = SecondaryCyan,
                    trackColor = SecondaryCyan.copy(alpha = 0.2f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Chunk ${task.currentChunk} / ${task.totalChunks}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${TokenCalculator.formatTokenCount(task.promptTokens + task.completionTokens)} tok • ${TokenCalculator.formatCost(task.cost, task.currency)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, color = EmeraldAccent)
                    )
                }
            } else if (task.status == TaskStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "✅ 翻译完成 (${task.totalChunks} 分块)",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = EmeraldAccent)
                    )
                    Text(
                        text = "${TokenCalculator.formatTokenCount(task.promptTokens + task.completionTokens)} tok • ${TokenCalculator.formatCost(task.cost, task.currency)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = EmeraldAccent)
                    )
                }
            }

            // Error Message
            if (!task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = RoseAccent.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = task.errorMessage,
                        modifier = Modifier.padding(6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = RoseAccent)
                    )
                }
            }

            // Actions Row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TaskStatus.RUNNING -> {
                        OutlinedButton(
                            onClick = onPause,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.pauseTaskBtn, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.cancelTaskBtn, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TaskStatus.QUEUED -> {
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.cancelTaskBtn, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TaskStatus.PAUSED -> {
                        Button(
                            onClick = onResume,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.resumeTaskBtn, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    TaskStatus.FAILED -> {
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.retryTaskBtn, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    TaskStatus.COMPLETED, TaskStatus.CANCELLED -> {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
