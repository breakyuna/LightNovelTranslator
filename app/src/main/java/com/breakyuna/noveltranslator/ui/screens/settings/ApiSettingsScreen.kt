package com.breakyuna.noveltranslator.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

enum class SettingsSubPage(val title: String, val subtitle: String) {
    PROVIDERS("模型接口", "配置大模型 API 供应商与计费"),
    APPEARANCE("外观显示", "深浅色主题模式与语言偏好"),
    LOGS("系统日志", "运行调试与 API 审计记录"),
    SYSTEM_INFO("系统信息", "版本详情与本地架构状态")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    initialTab: Int = -1,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentLang by viewModel.currentLanguage.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val systemLogs by viewModel.systemLogs.collectAsState()
    val projects by viewModel.allProjects.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    var activeSubPage by remember {
        mutableStateOf(
            when (initialTab) {
                0 -> SettingsSubPage.PROVIDERS
                1 -> SettingsSubPage.APPEARANCE
                2 -> SettingsSubPage.LOGS
                3 -> SettingsSubPage.SYSTEM_INFO
                else -> null
            }
        )
    }

    // Intercept back button when inside a sub-page
    BackHandler(enabled = activeSubPage != null) {
        activeSubPage = null
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProviderEntity?>(null) }
    var testingProviderId by remember { mutableStateOf<Long?>(null) }
    var testResultMessage by remember { mutableStateOf<Pair<Long, String>?>(null) }

    var logFilter by remember { mutableStateOf("ALL") }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }

    val filteredLogs = remember(systemLogs, logFilter) {
        when (logFilter) {
            "ERROR" -> systemLogs.filter { it.level == LogLevel.ERROR }
            "TRANSLATION" -> systemLogs.filter { it.tag.contains("TRANSLATE", ignoreCase = true) || it.tag.contains("SPLIT", ignoreCase = true) }
            "API" -> systemLogs.filter { it.tag.contains("LLM", ignoreCase = true) || it.tag.contains("API", ignoreCase = true) }
            else -> systemLogs
        }
    }

    AnimatedContent(
        targetState = activeSubPage,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width / 3 } + fadeOut()
                )
            } else {
                (slideInHorizontally { width -> -width / 3 } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> width } + fadeOut()
                )
            }
        },
        label = "SettingsNavigationTransition"
    ) { currentSubPage ->
        if (currentSubPage == null) {
            // ==========================================
            // Main Settings Root: Vertical Entry List
            // ==========================================
            Scaffold(
                modifier = modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    AppLargeTitle(
                        title = strings.settingsTitle,
                        subtitle = "偏好配置与系统管理"
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.compactHorizontalPadding,
                        vertical = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Section 1: Preference & Interfaces
                    item {
                        AppSection(title = "功能设置") {
                            // 1. 模型接口 Entrance
                            AppSettingsRow(
                                title = "模型接口",
                                subtitle = "管理大模型 API 端点、Token 价格与连通性",
                                leadingIcon = Icons.Outlined.SmartToy,
                                iconTint = AccentBlue,
                                iconBackground = AccentBlue.copy(alpha = 0.12f),
                                valueText = "${providers.size} 个供应商",
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.PROVIDERS }
                            )

                            AppDivider(startIndent = 58.dp)

                            // 2. 外观显示 Entrance
                            val themeLabel = when (themeMode) {
                                AppThemeMode.SYSTEM -> "跟随系统"
                                AppThemeMode.LIGHT -> "浅色模式"
                                AppThemeMode.DARK -> "深色模式"
                            }
                            val langLabel = if (currentLang == AppLanguage.CHINESE) "中文" else "English"

                            AppSettingsRow(
                                title = "外观显示",
                                subtitle = "深浅色主题模式与应用界面语言",
                                leadingIcon = Icons.Outlined.Palette,
                                iconTint = AccentPurple,
                                iconBackground = AccentPurple.copy(alpha = 0.12f),
                                valueText = "$themeLabel · $langLabel",
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.APPEARANCE }
                            )
                        }
                    }

                    // Section 2: Diagnostics & Information
                    item {
                        AppSection(title = "诊断与系统") {
                            // 3. 系统日志 Entrance
                            AppSettingsRow(
                                title = "系统日志",
                                subtitle = "查看运行时审计、API 调试与翻译异常",
                                leadingIcon = Icons.Outlined.ReceiptLong,
                                iconTint = AccentOrange,
                                iconBackground = AccentOrange.copy(alpha = 0.12f),
                                valueText = "${systemLogs.size} 条",
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.LOGS }
                            )

                            AppDivider(startIndent = 58.dp)

                            // 4. 系统信息 Entrance
                            AppSettingsRow(
                                title = "系统信息",
                                subtitle = "应用版本、项目数量与存储架构",
                                leadingIcon = Icons.Outlined.Info,
                                iconTint = AccentGreen,
                                iconBackground = AccentGreen.copy(alpha = 0.12f),
                                valueText = "v2.5.0",
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.SYSTEM_INFO }
                            )
                        }
                    }

                    // Section 3: Active Default Provider Overview Card
                    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                    if (defaultProvider != null) {
                        item {
                            AppGroupedSurface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activeSubPage = SettingsSubPage.PROVIDERS },
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(AccentBlue.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Dns,
                                            contentDescription = null,
                                            tint = AccentBlue,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "当前默认模型: ${defaultProvider.selectedModel}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${defaultProvider.name} · ${defaultProvider.baseUrl}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // Detail Sub-Page View
            // ==========================================
            Scaffold(
                modifier = modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentSubPage.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { activeSubPage = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = strings.cancel
                                )
                            }
                        },
                        actions = {
                            when (currentSubPage) {
                                SettingsSubPage.PROVIDERS -> {
                                    IconButton(
                                        onClick = { showAddDialog = true },
                                        modifier = Modifier.testTag("add_provider_fab")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = strings.addProvider,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                SettingsSubPage.LOGS -> {
                                    IconButton(
                                        onClick = {
                                            val text = systemLogs.joinToString("\n") { "[${it.level}] ${it.tag}: ${it.message}" }
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("System Logs", text))
                                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "复制日志",
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.clearSystemLogs() }) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "清空日志",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                else -> {}
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            ) { paddingValues ->
                when (currentSubPage) {
                    SettingsSubPage.PROVIDERS -> {
                        // ----------------------------------------------------
                        // Sub-Page 1: 模型接口 (Model Providers)
                        // ----------------------------------------------------
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
                            item {
                                AppGroupedSurface {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Calculate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = strings.calculatorEngineTitle,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = strings.calculatorEngineDesc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "已配置供应商 (${providers.size})",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )

                                    TextButton(
                                        onClick = { showAddDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(strings.addProvider, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            if (providers.isEmpty()) {
                                item {
                                    AppGroupedSurface(contentPadding = PaddingValues(28.dp)) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "暂无配置的 LLM 供应商",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(14.dp))
                                            AppPrimaryButton(
                                                text = strings.addProvider,
                                                onClick = { showAddDialog = true },
                                                icon = Icons.Default.Add,
                                                modifier = Modifier.widthIn(max = 180.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(providers, key = { it.id }) { provider ->
                                    ProviderCardApple(
                                        provider = provider,
                                        isTesting = testingProviderId == provider.id,
                                        testResult = if (testResultMessage?.first == provider.id) testResultMessage?.second else null,
                                        onSetDefault = { viewModel.setDefaultProvider(provider.id) },
                                        onEdit = { editingProvider = provider },
                                        onDelete = { viewModel.deleteProvider(provider.id) },
                                        onTest = {
                                            testingProviderId = provider.id
                                            testResultMessage = null
                                            viewModel.testProvider(provider) { success, msg ->
                                                testingProviderId = null
                                                testResultMessage = Pair(provider.id, msg)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    SettingsSubPage.APPEARANCE -> {
                        // ----------------------------------------------------
                        // Sub-Page 2: 外观显示 (Appearance & Display)
                        // ----------------------------------------------------
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
                            item {
                                AppSection(title = strings.themeSettingsTitle) {
                                    AppSettingsRow(
                                        title = "跟随系统",
                                        subtitle = "根据系统深浅色设置自适应切换",
                                        trailingContent = {
                                            RadioButton(
                                                selected = themeMode == AppThemeMode.SYSTEM,
                                                onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                                            )
                                        },
                                        onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                                    )
                                    AppDivider(startIndent = 16.dp)
                                    AppSettingsRow(
                                        title = "浅色模式",
                                        subtitle = "明亮清爽的经典浅色界面",
                                        trailingContent = {
                                            RadioButton(
                                                selected = themeMode == AppThemeMode.LIGHT,
                                                onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                                            )
                                        },
                                        onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                                    )
                                    AppDivider(startIndent = 16.dp)
                                    AppSettingsRow(
                                        title = "深色模式",
                                        subtitle = "深邃舒适的暗夜夜间界面",
                                        trailingContent = {
                                            RadioButton(
                                                selected = themeMode == AppThemeMode.DARK,
                                                onClick = { viewModel.setThemeMode(AppThemeMode.DARK) }
                                            )
                                        },
                                        onClick = { viewModel.setThemeMode(AppThemeMode.DARK) }
                                    )
                                }
                            }

                            item {
                                AppSection(title = strings.languageSettingTitle) {
                                    AppSettingsRow(
                                        title = "简体中文 (Chinese)",
                                        subtitle = "默认界面语言",
                                        trailingContent = {
                                            RadioButton(
                                                selected = currentLang == AppLanguage.CHINESE,
                                                onClick = { viewModel.setLanguage(AppLanguage.CHINESE) }
                                            )
                                        },
                                        onClick = { viewModel.setLanguage(AppLanguage.CHINESE) }
                                    )
                                    AppDivider(startIndent = 16.dp)
                                    AppSettingsRow(
                                        title = "English",
                                        subtitle = "Full English interface translation",
                                        trailingContent = {
                                            RadioButton(
                                                selected = currentLang == AppLanguage.ENGLISH,
                                                onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) }
                                            )
                                        },
                                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) }
                                    )
                                }
                            }

                            item {
                                AppGroupedSurface(contentPadding = PaddingValues(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "双语阅读器支持在正文阅读时自由定制独立羊皮纸、薄荷绿、板岩灰或 AMOLED 极黑配色。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsSubPage.LOGS -> {
                        // ----------------------------------------------------
                        // Sub-Page 3: 系统日志 (System Logs)
                        // ----------------------------------------------------
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.compactHorizontalPadding,
                                vertical = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = logFilter == "ALL",
                                            onClick = { logFilter = "ALL" },
                                            label = { Text("全部") },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "ERROR",
                                            onClick = { logFilter = "ERROR" },
                                            label = { Text("错误") },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "TRANSLATION",
                                            onClick = { logFilter = "TRANSLATION" },
                                            label = { Text("翻译") },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "API",
                                            onClick = { logFilter = "API" },
                                            label = { Text("API") },
                                            shape = SmallControlShape
                                        )
                                    }

                                    Text(
                                        text = "共 ${filteredLogs.size} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (filteredLogs.isEmpty()) {
                                item {
                                    AppGroupedSurface(contentPadding = PaddingValues(28.dp)) {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "暂无系统运行日志",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                item {
                                    AppGroupedSurface(contentPadding = PaddingValues(0.dp)) {
                                        filteredLogs.take(120).forEachIndexed { index, log ->
                                            val isExpanded = expandedLogId == log.id
                                            val levelColor = when (log.level) {
                                                LogLevel.ERROR -> StatusError
                                                LogLevel.WARN -> StatusWarning
                                                LogLevel.INFO -> AccentBlue
                                                LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { expandedLogId = if (isExpanded) null else log.id }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = levelColor.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = log.level.name,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 9.sp,
                                                                color = levelColor
                                                            )
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = log.tag,
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1
                                                    )
                                                    Spacer(modifier = Modifier.weight(1f))
                                                    Text(
                                                        text = log.formattedTime,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = log.message,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (index < filteredLogs.take(120).lastIndex) {
                                                AppDivider(startIndent = 14.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SettingsSubPage.SYSTEM_INFO -> {
                        // ----------------------------------------------------
                        // Sub-Page 4: 系统信息 (System Info)
                        // ----------------------------------------------------
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
                            item {
                                AppSection(title = "应用程序") {
                                    AppStatusRow(label = "应用名称", value = "AI Novel Translator", modifier = Modifier.padding(16.dp))
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(label = "当前版本", value = "v2.5.0 (Build 2026.08)", modifier = Modifier.padding(16.dp))
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(label = "本地小说项目", value = "${projects.size} 个工程", modifier = Modifier.padding(16.dp))
                                }
                            }

                            item {
                                AppSection(title = "架构与存储") {
                                    AppStatusRow(label = "数据持久化", value = "Room Database (SQLite ACID)", modifier = Modifier.padding(16.dp))
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(label = "分段对齐引擎", value = "StableSegmentParser (双向映射)", modifier = Modifier.padding(16.dp))
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(label = "并发处理", value = "Kotlin Coroutines Flow & Channel", modifier = Modifier.padding(16.dp))
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(label = "API 协议兼容", value = "OpenAI / Claude / DeepSeek / Gemini", modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Provider Dialog
    if (showAddDialog) {
        AddEditProviderDialog(
            provider = null,
            onDismiss = { showAddDialog = false },
            onSave = { newProvider ->
                viewModel.saveProvider(newProvider)
                showAddDialog = false
            }
        )
    }

    // Edit Provider Dialog
    editingProvider?.let { provider ->
        AddEditProviderDialog(
            provider = provider,
            onDismiss = { editingProvider = null },
            onSave = { updatedProvider ->
                viewModel.saveProvider(updatedProvider)
                editingProvider = null
            }
        )
    }
}

@Composable
private fun ProviderCardApple(
    provider: ApiProviderEntity,
    isTesting: Boolean,
    testResult: String?,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val strings = LocalAppStrings.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AppGroupedSurface(
        modifier = Modifier.testTag("provider_card_${provider.name.lowercase().replace(" ", "_")}"),
        contentPadding = PaddingValues(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AccentBlue.copy(alpha = 0.12f)
            ) {
                Text(
                    text = provider.providerType.displayName,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue
                    )
                )
            }

            if (provider.isDefault) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StatusSuccess.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "默认",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = StatusSuccess
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = provider.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "${provider.baseUrl} · ${provider.selectedModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Test Result Notice
        testResult?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            val isSuccess = msg.contains("成功", ignoreCase = true) || msg.contains("Success", ignoreCase = true)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = SmallControlShape,
                color = if (isSuccess) StatusSuccess.copy(alpha = 0.1f) else StatusError.copy(alpha = 0.1f)
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (isSuccess) StatusSuccess else StatusError
                )
            }
        }

        // Actions
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!provider.isDefault) {
                TextButton(
                    onClick = onSetDefault,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("设为默认", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            OutlinedButton(
                onClick = onTest,
                enabled = !isTesting,
                shape = SmallControlShape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("test_provider_button_${provider.name.lowercase().replace(" ", "_")}")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text("测试连通性", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除供应商") },
            text = { Text("确定要删除供应商 \"${provider.name}\" 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }
}

@Composable
fun AddEditProviderDialog(
    provider: ApiProviderEntity?,
    onDismiss: () -> Unit,
    onSave: (ApiProviderEntity) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(provider?.name ?: "") }
    var providerType by remember { mutableStateOf(provider?.providerType ?: ProviderType.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var selectedModel by remember { mutableStateOf(provider?.selectedModel ?: "gpt-4o") }
    var inputPrice by remember { mutableStateOf(provider?.inputPricePerMillion?.toString() ?: "2.5") }
    var outputPrice by remember { mutableStateOf(provider?.outputPricePerMillion?.toString() ?: "10.0") }
    var isDefault by remember { mutableStateOf(provider?.isDefault ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (provider == null) "添加模型供应商" else "编辑模型供应商",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("供应商名称") },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(strings.baseUrlLabel) },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(strings.apiKeyLabel) },
                        placeholder = { Text("sk-...") },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        label = { Text("模型名称 (如 gpt-4o, claude-3-5-sonnet)") },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputPrice,
                            onValueChange = { inputPrice = it },
                            label = { Text("输入 $/M Tok") },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = outputPrice,
                            onValueChange = { outputPrice = it },
                            label = { Text("输出 $/M Tok") },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                        Text("设为默认供应商", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val inPrice = inputPrice.toDoubleOrNull() ?: 0.0
                    val outPrice = outputPrice.toDoubleOrNull() ?: 0.0
                    val entity = provider?.copy(
                        name = name,
                        providerType = providerType,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        selectedModel = selectedModel,
                        inputPricePerMillion = inPrice,
                        outputPricePerMillion = outPrice,
                        isDefault = isDefault
                    ) ?: ApiProviderEntity(
                        name = name,
                        providerType = providerType,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        selectedModel = selectedModel,
                        inputPricePerMillion = inPrice,
                        outputPricePerMillion = outPrice,
                        isDefault = isDefault
                    )
                    onSave(entity)
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && selectedModel.isNotBlank(),
                shape = ButtonShape
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
        shape = DialogShape
    )
}
