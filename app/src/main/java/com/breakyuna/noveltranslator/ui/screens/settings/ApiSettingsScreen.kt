package com.breakyuna.noveltranslator.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.core.logger.SystemLogEntry
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.PresetModels
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.EmeraldAccent
import com.breakyuna.noveltranslator.ui.theme.PrimaryIndigo
import com.breakyuna.noveltranslator.ui.theme.RoseAccent
import com.breakyuna.noveltranslator.ui.theme.TertiaryAmber
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    initialTab: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentLang by viewModel.currentLanguage.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val systemLogs by viewModel.systemLogs.collectAsState()
    val projects by viewModel.allProjects.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 5)) }
    var generalSection by remember { mutableStateOf(0) } // 0: appearance, 1: language, 2: system info

    var showAddDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProviderEntity?>(null) }
    var testingProviderId by remember { mutableStateOf<Long?>(null) }
    var testResultMessage by remember { mutableStateOf<Pair<Long, String>?>(null) }

    // Log Filter
    var logFilter by remember { mutableStateOf("ALL") } // ALL, ERROR, TRANSLATION, API
    var expandedLogId by remember { mutableStateOf<Long?>(null) }

    val filteredLogs = remember(systemLogs, logFilter) {
        when (logFilter) {
            "ERROR" -> systemLogs.filter { it.level == LogLevel.ERROR }
            "TRANSLATION" -> systemLogs.filter { it.tag.contains("TRANSLATE", ignoreCase = true) || it.tag.contains("SPLIT", ignoreCase = true) }
            "API" -> systemLogs.filter { it.tag.contains("LLM", ignoreCase = true) || it.tag.contains("API", ignoreCase = true) }
            else -> systemLogs
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when (selectedTab) {
                                    1 -> strings.llmSettingsSection
                                    2 -> strings.systemLogsSection
                                    3 -> strings.themeSettingsTitle
                                    4 -> strings.languageSettingTitle
                                    5 -> strings.aboutStorageSection
                                    else -> strings.settingsTitle
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (selectedTab == 0) strings.settingsSubtitle else strings.settingsTitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (selectedTab == 0) onBack() else selectedTab = 0
                                },
                                modifier = Modifier.testTag("settings_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                            }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )

            }
        },
        floatingActionButton = {
            if (selectedTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(strings.addProvider) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_provider_fab")
                )
            }
        },
        modifier = modifier
    ) { padding ->
        when (selectedTab) {
            0 -> {
                // ==================== SYSTEM SETTINGS OVERVIEW ====================
                val isChinese = currentLang == AppLanguage.CHINESE
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp)
                ) {
                    item {
                        Text(
                            text = if (isChinese) "请选择要修改的系统设置" else "Choose a system setting to configure",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    item {
                        SettingsEntryCard(
                            number = 1,
                            title = strings.llmSettingsSection,
                            description = if (isChinese) "管理 LLM 接口、模型、API Key 与费率" else "Manage LLM providers, models, API keys, and pricing",
                            icon = Icons.Default.Tune,
                            onClick = { selectedTab = 1 }
                        )
                    }
                    item {
                        SettingsEntryCard(
                            number = 2,
                            title = strings.systemLogsSection,
                            description = if (isChinese) "查看运行状态、接口调用与错误日志（${systemLogs.size}）" else "Inspect runtime, API, and error logs (${systemLogs.size})",
                            icon = Icons.Default.Terminal,
                            onClick = { selectedTab = 2 }
                        )
                    }
                    item {
                        SettingsEntryCard(
                            number = 3,
                            title = strings.themeSettingsTitle,
                            description = if (isChinese) "选择跟随系统、浅色或深色外观" else "Choose system, light, or dark appearance",
                            icon = Icons.Default.DarkMode,
                            onClick = {
                                generalSection = 0
                                selectedTab = 3
                            }
                        )
                    }
                    item {
                        SettingsEntryCard(
                            number = 4,
                            title = strings.languageSettingTitle,
                            description = if (isChinese) "切换应用界面语言" else "Change the application display language",
                            icon = Icons.Default.Language,
                            onClick = {
                                generalSection = 1
                                selectedTab = 4
                            }
                        )
                    }
                    item {
                        SettingsEntryCard(
                            number = 5,
                            title = if (isChinese) "系统信息" else "System Information",
                            description = if (isChinese) "查看应用版本、数据库与本地工程状态" else "View app version, database, and local project status",
                            icon = Icons.Default.Info,
                            onClick = {
                                generalSection = 2
                                selectedTab = 5
                            }
                        )
                    }
                }
            }
            1 -> {
                // ==================== LLM SETTINGS TAB ====================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)
                ) {
                    // Calculator Engine Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.calculatorEngineTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = strings.calculatorEngineDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Provider List Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已配置的 LLM 接口列表 (${providers.size})",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    items(providers, key = { it.id }) { provider ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provider_card_${provider.name.lowercase().replace(" ", "_")}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(
                                if (provider.isDefault) 1.5.dp else 1.dp,
                                if (provider.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (provider.isDefault) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = EmeraldAccent.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = strings.defaultLabel,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = EmeraldAccent
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    IconButton(onClick = { editingProvider = provider }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = strings.editProvider, modifier = Modifier.size(18.dp))
                                    }

                                    if (!provider.isDefault) {
                                        IconButton(onClick = { viewModel.deleteProvider(provider.id) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = strings.delete, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "${strings.modelIdLabel}: ${provider.selectedModel} (${provider.providerType.displayName})",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    text = "${strings.baseUrlLabel}: ${provider.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Pricing Pill
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "Prompt: ${provider.currency} ${provider.inputPricePerMillion}/M • Completion: ${provider.currency} ${provider.outputPricePerMillion}/M",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TertiaryAmber
                                            )
                                        )
                                    }

                                    if (!provider.isDefault) {
                                        TextButton(onClick = { viewModel.setDefaultProvider(provider.id) }) {
                                            Text(strings.setAsDefaultBtn, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                // Test Connection Result
                                if (testResultMessage?.first == provider.id) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = testResultMessage!!.second,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = if (testResultMessage!!.second.startsWith("Success")) EmeraldAccent else MaterialTheme.colorScheme.error
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                // The endpoint model fetcher is intentionally kept in the edit page.
                                Button(
                                    onClick = {
                                        testingProviderId = provider.id
                                        viewModel.testProviderConnection(provider) { _, msg ->
                                            testResultMessage = Pair(provider.id, msg)
                                            testingProviderId = null
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("test_provider_${provider.id}"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                        if (testingProviderId == provider.id) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.testingStatus, fontSize = 12.sp)
                                        } else {
                                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(strings.testConnectionBtn, fontSize = 12.sp)
                                        }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // ==================== SYSTEM LOGS TAB ====================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
                ) {
                    // Log Controls & Status Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldAccent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = strings.systemStatusHealthy,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    Text(
                                        text = "共 ${systemLogs.size} 条日志",
                                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }

                                Text(
                                    text = strings.systemLogsDesc,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val logText = systemLogs.joinToString("\n") { log ->
                                                "[${log.formattedDate}] [${log.level}] [${log.tag}] ${log.message}${if (log.details != null) "\n  Detail: ${log.details}" else ""}"
                                            }
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("System Logs", logText)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, strings.logsCopiedToast, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(strings.copyLogsBtn, fontSize = 11.5.sp)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.clearSystemLogs()
                                            Toast.makeText(context, strings.logsClearedToast, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                                    ) {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(strings.clearLogsBtn, fontSize = 11.5.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Filter Chips
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = logFilter == "ALL",
                                    onClick = { logFilter = "ALL" },
                                    label = { Text(strings.filterLogsAll) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = logFilter == "ERROR",
                                    onClick = { logFilter = "ERROR" },
                                    label = { Text(strings.filterLogsError) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = logFilter == "TRANSLATION",
                                    onClick = { logFilter = "TRANSLATION" },
                                    label = { Text(strings.filterLogsTranslation) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = logFilter == "API",
                                    onClick = { logFilter = "API" },
                                    label = { Text(strings.filterLogsApi) }
                                )
                            }
                        }
                    }

                    if (filteredLogs.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = strings.noSystemLogs,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredLogs, key = { it.id }) { log ->
                            val levelColor = when (log.level) {
                                LogLevel.INFO -> MaterialTheme.colorScheme.primary
                                LogLevel.WARN -> TertiaryAmber
                                LogLevel.ERROR -> RoseAccent
                                LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedLogId = if (expandedLogId == log.id) null else log.id
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (log.level == LogLevel.ERROR) RoseAccent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (log.level == LogLevel.ERROR) RoseAccent.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = levelColor.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = log.level.name,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = levelColor
                                                    )
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "[${log.tag}]",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }

                                        Text(
                                            text = log.formattedTime,
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
                                            fontSize = 11.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (!log.details.isNullOrBlank()) {
                                        if (expandedLogId == log.id) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            ) {
                                                Text(
                                                    text = log.details,
                                                    modifier = Modifier.padding(8.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "点击展开详情 / 错误堆栈...",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3, 4, 5 -> {
                // ==================== GENERAL & STORAGE TAB ====================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
                ) {
                    // Theme Mode Card
                    if (generalSection == 0) item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.themeSettingsTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    FilterChip(
                                        selected = themeMode == com.breakyuna.noveltranslator.ui.theme.AppThemeMode.SYSTEM,
                                        onClick = { viewModel.setThemeMode(com.breakyuna.noveltranslator.ui.theme.AppThemeMode.SYSTEM) },
                                        label = { Text(strings.themeFollowSystem) },
                                        leadingIcon = { Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = themeMode == com.breakyuna.noveltranslator.ui.theme.AppThemeMode.LIGHT,
                                        onClick = { viewModel.setThemeMode(com.breakyuna.noveltranslator.ui.theme.AppThemeMode.LIGHT) },
                                        label = { Text(strings.themeLightMode) },
                                        leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = themeMode == com.breakyuna.noveltranslator.ui.theme.AppThemeMode.DARK,
                                        onClick = { viewModel.setThemeMode(com.breakyuna.noveltranslator.ui.theme.AppThemeMode.DARK) },
                                        label = { Text(strings.themeDarkMode) },
                                        leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Language Selection Card
                    if (generalSection == 1) item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.languageSettingTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = currentLang == AppLanguage.CHINESE,
                                        onClick = { viewModel.setLanguage(AppLanguage.CHINESE) },
                                        label = { Text(strings.chineseDefault) }
                                    )
                                    FilterChip(
                                        selected = currentLang == AppLanguage.ENGLISH,
                                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                                        label = { Text(strings.english) }
                                    )
                                }
                            }
                        }
                    }

                    // Storage & System Info Card
                    if (generalSection == 2) item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.aboutStorageSection,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(strings.appVersionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("v2.2.0 (Build 2026.08)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("本地工程数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${projects.size} 个小说工程", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(strings.databaseStatusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Room SQLite (正常在线)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = EmeraldAccent))
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("LLM 通信协议", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("OpenAI-Compatible REST API / OkHttp", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProviderEditDialog(
            viewModel = viewModel,
            provider = null,
            onDismiss = { showAddDialog = false },
            onSave = { newProvider ->
                viewModel.saveProvider(newProvider)
                showAddDialog = false
            }
        )
    }

    if (editingProvider != null) {
        ProviderEditDialog(
            viewModel = viewModel,
            provider = editingProvider,
            onDismiss = { editingProvider = null },
            onSave = { updatedProvider ->
                viewModel.saveProvider(updatedProvider)
                editingProvider = null
            }
        )
    }
}

@Composable
private fun SettingsEntryCard(
    number: Int,
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ProviderEditDialog(
    viewModel: AppViewModel,
    provider: ApiProviderEntity?,
    onDismiss: () -> Unit,
    onSave: (ApiProviderEntity) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(provider?.name ?: "Custom OpenAI-Compatible") }
    var providerType by remember { mutableStateOf(provider?.providerType ?: ProviderType.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://api.deepseek.com/v1") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(provider?.selectedModel ?: "deepseek-chat") }
    var inputPrice by remember { mutableStateOf("${provider?.inputPricePerMillion ?: 0.14}") }
    var outputPrice by remember { mutableStateOf("${provider?.outputPricePerMillion ?: 0.28}") }
    var currency by remember { mutableStateOf(provider?.currency ?: "USD") }
    var maxContextTokens by remember { mutableStateOf("${provider?.maxContextTokens ?: 8192}") }
    var temperature by remember { mutableStateOf(provider?.temperature ?: 0.3f) }
    var customHeadersJson by remember { mutableStateOf(provider?.customHeadersJson ?: "{}") }
    var isDefault by remember { mutableStateOf(provider?.isDefault ?: false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchModelNotice by remember { mutableStateOf<String?>(null) }

    val recommendedModels = PresetModels.presets.firstOrNull {
        it.providerType == providerType && (baseUrl.startsWith(it.defaultBaseUrl) || it.defaultBaseUrl.startsWith(baseUrl))
    }?.recommendedModels.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) strings.addProviderDialogTitle else strings.editProviderDialogTitle) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Preset Selector
                item {
                    Text(strings.quickPresetsLabel, style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PresetModels.presets) { preset ->
                            FilterChip(
                                selected = selectedModel == preset.defaultModel && baseUrl == preset.defaultBaseUrl,
                                onClick = {
                                    val endpointChanged = baseUrl != preset.defaultBaseUrl || providerType != preset.providerType
                                    name = preset.name
                                    providerType = preset.providerType
                                    baseUrl = preset.defaultBaseUrl
                                    if (endpointChanged) {
                                        apiKey = ""
                                        customHeadersJson = "{}"
                                    }
                                    selectedModel = preset.defaultModel
                                    inputPrice = "${preset.defaultInputPrice}"
                                    outputPrice = "${preset.defaultOutputPrice}"
                                    currency = preset.currency
                                    maxContextTokens = "${preset.defaultMaxContextTokens}"
                                    fetchedModels = emptyList()
                                    fetchModelNotice = null
                                },
                                label = { Text(preset.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.providerNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            if (it != baseUrl) {
                                apiKey = ""
                                customHeadersJson = "{}"
                                fetchedModels = emptyList()
                                fetchModelNotice = null
                            }
                            baseUrl = it
                        },
                        label = { Text(strings.baseUrlLabel) },
                        placeholder = { Text("https://api.deepseek.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(strings.apiKeyLabel) },
                        placeholder = { Text("sk-...") },
                        visualTransformation = if (isApiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isApiKeyVisible) "Hide API Key" else "Show API Key"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Fetch Models from Endpoint Action
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (baseUrl.isBlank()) {
                                    fetchModelNotice = "请先填写 Base URL"
                                    return@OutlinedButton
                                }
                                val tempProvider = ApiProviderEntity(
                                    name = name.ifBlank { "Test" },
                                    providerType = providerType,
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    selectedModel = selectedModel,
                                    inputPricePerMillion = 0.0,
                                    outputPricePerMillion = 0.0,
                                    currency = currency,
                                    maxContextTokens = 8192
                                )
                                isFetchingModels = true
                                fetchModelNotice = null
                                viewModel.fetchModelsFromEndpoint(tempProvider) { result ->
                                    isFetchingModels = false
                                    result.onSuccess { list ->
                                        fetchedModels = list
                                        fetchModelNotice = String.format(strings.fetchModelsSuccess, list.size)
                                        if (list.isNotEmpty() && selectedModel.isBlank()) {
                                            selectedModel = list.first()
                                        }
                                    }.onFailure { err ->
                                        fetchModelNotice = String.format(strings.fetchModelsError, err.message ?: "Network error")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.fetchingModels)
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.fetchModelsBtn)
                            }
                        }
                    }

                    fetchModelNotice?.let { notice ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = if (notice.contains("成功") || notice.contains("Successfully")) EmeraldAccent else MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }

                // If models fetched from endpoint
                if (fetchedModels.isNotEmpty()) {
                    item {
                        Text(strings.selectModelFromList, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(fetchedModels) { model ->
                                FilterChip(
                                    selected = selectedModel == model,
                                    onClick = { selectedModel = model },
                                    label = { Text(model, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                } else if (recommendedModels.isNotEmpty()) {
                    item {
                        Text("推荐模型标识 (Recommended IDs)", style = MaterialTheme.typography.labelSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(recommendedModels) { model ->
                                FilterChip(
                                    selected = selectedModel == model,
                                    onClick = { selectedModel = model },
                                    label = { Text(model, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        label = { Text(strings.modelIdLabel) },
                        placeholder = { Text(strings.modelPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inputPrice,
                            onValueChange = { inputPrice = it },
                            label = { Text(strings.inputPriceLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = outputPrice,
                            onValueChange = { outputPrice = it },
                            label = { Text(strings.outputPriceLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = maxContextTokens,
                            onValueChange = { maxContextTokens = it },
                            label = { Text("Max Context Tokens") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = currency == "USD",
                                onClick = { currency = "USD" },
                                label = { Text("USD ($)") }
                            )
                            FilterChip(
                                selected = currency == "CNY",
                                onClick = { currency = "CNY" },
                                label = { Text("CNY (¥)") }
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it }
                        )
                        Text(strings.setDefaultProviderCheck, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                validationError?.let { error ->
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val inP = inputPrice.toDoubleOrNull()
                    val outP = outputPrice.toDoubleOrNull()
                    val maxCtx = maxContextTokens.toIntOrNull()
                    validationError = when {
                        baseUrl.isBlank() -> "Base URL is required"
                        inP == null || inP < 0.0 -> "Input price must be a non-negative number"
                        outP == null || outP < 0.0 -> "Output price must be a non-negative number"
                        maxCtx == null || maxCtx !in 4_096..2_000_000 -> "Max context tokens must be between 4,096 and 2,000,000"
                        else -> null
                    }
                    if (validationError != null) return@Button
                    val entity = provider?.copy(
                        name = name.trim(),
                        providerType = providerType,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        selectedModel = selectedModel.trim(),
                        inputPricePerMillion = inP!!,
                        outputPricePerMillion = outP!!,
                        currency = currency,
                        maxContextTokens = maxCtx!!,
                        temperature = temperature,
                        customHeadersJson = customHeadersJson,
                        isDefault = isDefault
                    ) ?: ApiProviderEntity(
                        name = name.trim(),
                        providerType = providerType,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        selectedModel = selectedModel.trim(),
                        inputPricePerMillion = inP!!,
                        outputPricePerMillion = outP!!,
                        currency = currency,
                        maxContextTokens = maxCtx!!,
                        temperature = temperature,
                        customHeadersJson = customHeadersJson,
                        isDefault = isDefault
                    )
                    onSave(entity)
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && selectedModel.isNotBlank()
            ) {
                Text(strings.saveProvider)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

