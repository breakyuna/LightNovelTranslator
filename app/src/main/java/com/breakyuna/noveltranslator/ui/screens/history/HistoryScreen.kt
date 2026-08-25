package com.breakyuna.noveltranslator.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.data.model.LlmRequestLogEntity
import com.breakyuna.noveltranslator.data.model.TranslationLogEntity
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
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
fun HistoryScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val allLogs by viewModel.allTranslationLogs.collectAsState()
    val allRequestLogs by viewModel.allRequestLogs.collectAsState()
    val allProjects by viewModel.allProjects.collectAsState()

    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selectedLogForDetail by remember { mutableStateOf<TranslationLogEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Map project ID to Title
    val projectTitleMap = remember(allProjects) {
        allProjects.associate { it.id to it.title }
    }

    // Filter logs
    val filteredLogs = remember(allLogs, selectedFilter) {
        when (selectedFilter) {
            HistoryFilter.ALL -> allLogs
            HistoryFilter.SUCCESS -> allLogs.filter { it.isSuccess }
            HistoryFilter.FAILED -> allLogs.filter { !it.isSuccess }
        }
    }

    // Total Stats
    val totalTokens = remember(allLogs) { allLogs.sumOf { it.totalTokens } }
    val totalCost = remember(allLogs) { allLogs.sumOf { it.estimatedCost } }
    val successCount = remember(allLogs) { allLogs.count { it.isSuccess } }
    val successRate = if (allLogs.isNotEmpty()) (successCount * 100 / allLogs.size) else 100

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTitle(
                title = "审计历史",
                subtitle = "跨项目翻译请求与 Token 审计记录",
                trailingContent = {
                    if (allLogs.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "清空历史",
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
            contentPadding = PaddingValues(horizontal = Spacing.compactHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metrics Overview (Apple-style Grouped Surface)
            item {
                AppGroupedSurface {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem(
                            label = "总消耗 Tokens",
                            value = formatTokenCount(totalTokens),
                            color = MaterialTheme.colorScheme.primary
                        )
                        MetricItem(
                            label = "累计估算费用",
                            value = "$${String.format(Locale.US, "%.4f", totalCost)}",
                            color = StatusSuccess
                        )
                        MetricItem(
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
                    FilterTab(
                        text = "全部 (${allLogs.size})",
                        selected = selectedFilter == HistoryFilter.ALL,
                        onClick = { selectedFilter = HistoryFilter.ALL }
                    )
                    FilterTab(
                        text = "成功 ($successCount)",
                        selected = selectedFilter == HistoryFilter.SUCCESS,
                        onClick = { selectedFilter = HistoryFilter.SUCCESS }
                    )
                    FilterTab(
                        text = "异常 (${allLogs.size - successCount})",
                        selected = selectedFilter == HistoryFilter.FAILED,
                        onClick = { selectedFilter = HistoryFilter.FAILED }
                    )
                }
            }

            // History List
            if (filteredLogs.isEmpty()) {
                item {
                    AppGroupedSurface(
                        modifier = Modifier.padding(top = 24.dp),
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
                        text = "请求记录 (${filteredLogs.size})",
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
                        filteredLogs.forEachIndexed { index, log ->
                            val projTitle = projectTitleMap[log.projectId] ?: "项目 #${log.projectId}"
                            HistoryLogRow(
                                log = log,
                                projectTitle = projTitle,
                                onClick = { selectedLogForDetail = log }
                            )
                            if (index < filteredLogs.lastIndex) {
                                AppDivider(startIndent = 52.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedLogForDetail?.let { log ->
        val projTitle = projectTitleMap[log.projectId] ?: "项目 #${log.projectId}"
        HistoryDetailDialog(
            log = log,
            projectTitle = projTitle,
            onDismiss = { selectedLogForDetail = null }
        )
    }

    // Clear Confirmation Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空翻译历史") },
            text = { Text("确定要清空所有翻译日志与请求审计记录吗？该操作不会删除已翻译的章节正文。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistoryLogs()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            },
            shape = DialogShape
        )
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
private fun FilterTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = SmallControlShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HistoryLogRow(
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
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(log.timestamp) { dateFormat.format(Date(log.timestamp)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "请求详情",
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
                AppStatusRow(label = "所属项目", value = projectTitle)
                AppStatusRow(label = "章节序号", value = "第 ${log.chapterIndex} 章")
                AppStatusRow(label = "章节标题", value = log.chapterTitle.ifBlank { "无标题" })
                AppStatusRow(label = "服务供应商", value = log.providerName)
                AppStatusRow(label = "调用模型", value = log.modelName)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AppStatusRow(label = "Prompt Tokens", value = "${log.promptTokens}")
                AppStatusRow(label = "Completion Tokens", value = "${log.completionTokens}")
                AppStatusRow(label = "Total Tokens", value = "${log.totalTokens}")
                AppStatusRow(
                    label = "预估费用",
                    value = "$${String.format(Locale.US, "%.5f", log.estimatedCost)} ${log.currency}",
                    valueColor = StatusSuccess
                )
                AppStatusRow(label = "耗时", value = "${log.durationMs} ms")
                AppStatusRow(label = "请求时间", value = timeStr)
                AppStatusRow(
                    label = "状态",
                    value = if (log.isSuccess) "成功" else "失败/异常",
                    valueColor = if (log.isSuccess) StatusSuccess else StatusError
                )
                if (log.message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "附注/错误信息:",
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
                Text("关闭")
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
