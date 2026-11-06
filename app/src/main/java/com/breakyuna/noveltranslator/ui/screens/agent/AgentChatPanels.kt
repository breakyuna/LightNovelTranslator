package com.breakyuna.noveltranslator.ui.screens.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.agent.koog.AgentSessionState
import com.breakyuna.noveltranslator.core.crash.CrashRecord
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.core.logger.SystemLogEntry
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.components.apple.AppCard
import com.breakyuna.noveltranslator.ui.components.apple.AppIconBubble
import com.breakyuna.noveltranslator.ui.components.apple.AppPrimaryButton
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.components.apple.AppSegmentedControl
import com.breakyuna.noveltranslator.ui.components.apple.AppStatusRow
import com.breakyuna.noveltranslator.ui.i18n.AgentEntryStrings
import com.breakyuna.noveltranslator.ui.theme.GroupedCardShape
import com.breakyuna.noveltranslator.ui.theme.Spacing

/** 思考强度四档，与 ViewModel 的合法取值保持一致（off/low/medium/high）。 */
private val AgentReasoningEfforts = listOf("off", "low", "medium", "high")

/** 提供方列表与日志列表的最大高度，超出后内部滚动，保证面板整体可折叠。 */
private val ProviderListMaxHeight = 220.dp
private val LogListMaxHeight = 220.dp
private val CrashStackMaxHeight = 220.dp

/**
 * 模型切换面板内容：提供方单选（含"跟随默认"）+ 模型自由文本 + 思考强度四档 +
 * 原生工具调用开关。底部弹窗与宽屏侧栏共用同一份内容。
 *
 * 面板内部维护草稿状态，点击"保存并应用"后一次性提交给集成层，避免半套配置生效。
 */
@Composable
fun AgentModelPanelContent(
    strings: AgentEntryStrings,
    providers: List<ApiProviderEntity>,
    selectedProviderId: Long?,
    selectedModel: String?,
    reasoningEffort: String,
    nativeToolsEnabled: Boolean,
    onApply: (providerId: Long?, modelName: String?, effort: String, nativeTools: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultProvider = remember(providers) {
        providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
    }
    val effectiveProviderId = selectedProviderId ?: defaultProvider?.id
    var draftProviderId by remember(selectedProviderId, providers) {
        mutableStateOf(effectiveProviderId)
    }
    var modelEdited by remember { mutableStateOf(false) }
    var draftModel by remember(selectedProviderId, selectedModel, providers) {
        mutableStateOf(
            selectedModel?.takeIf { it.isNotBlank() }
                ?: providers.firstOrNull { it.id == effectiveProviderId }?.selectedModel
                ?: ""
        )
    }
    var draftEffort by remember(reasoningEffort) { mutableStateOf(reasoningEffort) }
    var draftNative by remember(nativeToolsEnabled) { mutableStateOf(nativeToolsEnabled) }
    val draftProvider = providers.firstOrNull { it.id == draftProviderId }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Text(
            text = strings.modelPanelTitle,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = strings.modelPanelSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = strings.providerLabel,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (providers.isEmpty()) {
            Text(
                text = strings.noProvidersHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = ProviderListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                item(key = "follow-default") {
                    AgentProviderOptionRow(
                        title = strings.followDefaultProvider,
                        subtitle = defaultProvider?.let {
                            "${it.name} · ${it.providerType.displayName}"
                        } ?: strings.noProvidersHint,
                        isSelected = draftProviderId == null,
                        selectedLabel = strings.selectedBadge,
                        badge = null,
                        onClick = {
                            draftProviderId = null
                            if (!modelEdited) draftModel = ""
                        }
                    )
                }
                items(providers, key = { it.id }) { provider ->
                    AgentProviderOptionRow(
                        title = provider.name,
                        subtitle = "${provider.providerType.displayName} · ${provider.selectedModel}",
                        isSelected = draftProviderId == provider.id,
                        selectedLabel = strings.selectedBadge,
                        badge = if (provider.isDefault) strings.defaultProviderBadge else null,
                        onClick = {
                            draftProviderId = provider.id
                            if (!modelEdited) draftModel = provider.selectedModel
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = draftModel,
            onValueChange = {
                draftModel = it
                modelEdited = true
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.modelLabel) },
            placeholder = { Text(draftProvider?.selectedModel.orEmpty()) },
            supportingText = { Text(strings.modelInputHint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        Text(
            text = strings.reasoningLabel,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppSegmentedControl(
            items = listOf(
                strings.effortOff,
                strings.effortLow,
                strings.effortMedium,
                strings.effortHigh
            ),
            selectedIndex = AgentReasoningEfforts.indexOf(draftEffort).coerceAtLeast(0),
            onItemSelected = { index ->
                AgentReasoningEfforts.getOrNull(index)?.let { draftEffort = it }
            }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.nativeToolsTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = strings.nativeToolsDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(Spacing.s))
            Switch(checked = draftNative, onCheckedChange = { draftNative = it })
        }

        AppPrimaryButton(
            text = strings.modelApply,
            onClick = {
                onApply(
                    draftProviderId,
                    draftModel.trim().ifBlank { null },
                    draftEffort,
                    draftNative
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = strings.modelAppliedToNewChat,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

/** 提供方单选行：名称 + 类型/默认模型，选中态用对勾圆环表示。 */
@Composable
private fun AgentProviderOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    selectedLabel: String,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier,
        shape = GroupedCardShape,
        containerColor = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AppBadge(text = badge)
                    }
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AppBadge(
                            text = selectedLabel,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.s))
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 宽屏右侧信息侧栏：运行状态、当前模型、会话用量与诊断（崩溃 + 最近日志）。
 * 由宿主层（持有 ViewModel）提供内容，屏幕只负责摆放。
 */
@Composable
fun AgentInfoPanel(
    strings: AgentEntryStrings,
    state: AgentSessionState,
    modelName: String?,
    providerLabel: String?,
    crash: CrashRecord?,
    logs: List<SystemLogEntry>,
    onOpenModelPanel: () -> Unit,
    onCancel: () -> Unit,
    onClearCrash: () -> Unit,
    onExportLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val errorText = state.error
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        if (!errorText.isNullOrBlank()) {
            // 宽屏下没有底部状态条，错误信息必须出现在侧栏里。
            AppCard(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
            ) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (state.isRunning) {
            AppCard(contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(
                        text = strings.runningLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryButton(text = strings.stopLabel, onClick = onCancel)
                }
            }
        }

        AppCard {
            Text(
                text = strings.modelCardTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconBubble(icon = Icons.Outlined.SmartToy, size = 36.dp)
                Spacer(modifier = Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelName ?: strings.modelUnknown,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = providerLabel ?: strings.providerUnknown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.s))
            AppSecondaryButton(
                text = strings.switchModelLabel,
                onClick = onOpenModelPanel,
                icon = Icons.Filled.Tune,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AppCard {
            Text(
                text = strings.usageTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppStatusRow(
                label = strings.usageInputLabel,
                value = state.usage.promptTokens.toString()
            )
            AppStatusRow(
                label = strings.usageOutputLabel,
                value = state.usage.completionTokens.toString()
            )
            AppStatusRow(
                label = strings.usageRequestsLabel,
                value = state.usage.requestCount.toString()
            )
        }

        AppCard {
            AgentDiagnosticsContent(
                strings = strings,
                crash = crash,
                logs = logs,
                onClearCrash = onClearCrash,
                onExportLogs = onExportLogs,
                onCopyLogs = onCopyLogs
            )
        }
    }
}

/** 诊断面板展示的日志条数上限，避免长日志把面板撑长。 */
private const val DiagnosticsLogLimit = 30

/** 诊断内容：上次崩溃卡片 + 最近日志列表（含导出 / 复制）。侧栏与底部弹窗共用。 */
@Composable
fun AgentDiagnosticsContent(
    strings: AgentEntryStrings,
    crash: CrashRecord?,
    logs: List<SystemLogEntry>,
    onClearCrash: () -> Unit,
    onExportLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recentLogs = remember(logs) { logs.takeLast(DiagnosticsLogLimit) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        if (crash != null) {
            AgentCrashCard(strings = strings, crash = crash, onClear = onClearCrash)
        } else {
            Text(
                text = strings.crashNone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.logsTitle,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopyLogs) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = strings.logsCopy,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onExportLogs) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = strings.logsExport,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (logs.isEmpty()) {
            Text(
                text = strings.logsEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = LogListMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    recentLogs.forEach { entry -> AgentLogRow(entry = entry) }
                }
            }
        }

        Text(
            text = strings.logsPrivacyNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

/** 上次崩溃卡片：异常类 / 消息 / 设备 / 版本，堆栈可展开，可一键清除。 */
@Composable
private fun AgentCrashCard(
    strings: AgentEntryStrings,
    crash: CrashRecord,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stackExpanded by rememberSaveable(crash.id) { mutableStateOf(false) }
    AppCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(
                text = crash.exceptionClass,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            AppBadge(
                text = if (crash.fatal) strings.crashFatalLabel else strings.crashNonFatalLabel,
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.error
            )
        }
        if (crash.exceptionMessage.isNotBlank()) {
            Text(
                text = crash.exceptionMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        AppStatusRow(
            label = strings.crashTimeLabel,
            value = crash.formattedTimestamp,
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppStatusRow(
            label = strings.crashDeviceLabel,
            value = "${crash.deviceManufacturer} ${crash.deviceModel} · " +
                "Android ${crash.androidRelease} (API ${crash.androidSdkInt})",
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppStatusRow(
            label = strings.crashAppLabel,
            value = "${crash.appVersionName} (${crash.appVersionCode})",
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSecondaryButton(
                text = strings.crashStackLabel,
                onClick = { stackExpanded = !stackExpanded },
                icon = if (stackExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            AppSecondaryButton(text = strings.clearLabel, onClick = onClear)
        }
        if (stackExpanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = crash.stackTrace,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = CrashStackMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                )
            }
        }
    }
}

/** 单条日志：时间 + 级别（着色）+ tag + 消息（两行截断）。 */
@Composable
private fun AgentLogRow(
    entry: SystemLogEntry,
    modifier: Modifier = Modifier
) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = levelColor
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
