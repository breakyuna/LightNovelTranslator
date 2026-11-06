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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.RequestPacingOverride
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.PresetModels
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.AppLanguage
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.i18n.agentEntryStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.util.Locale

enum class SettingsSubPage(val icon: ImageVector) {
    PROVIDERS(Icons.Outlined.SmartToy),
    APPEARANCE(Icons.Outlined.Palette),
    LOGS(Icons.Outlined.ReceiptLong),
    SYSTEM_INFO(Icons.Outlined.Info)
}

private fun SettingsSubPage.title(strings: AppStrings): String = when (this) {
    SettingsSubPage.PROVIDERS -> strings.settingsProvidersTitle
    SettingsSubPage.APPEARANCE -> strings.settingsAppearanceTitle
    SettingsSubPage.LOGS -> strings.settingsLogsTitle
    SettingsSubPage.SYSTEM_INFO -> strings.settingsSystemInfoTitle
}

private fun SettingsSubPage.subtitle(strings: AppStrings): String = when (this) {
    SettingsSubPage.PROVIDERS -> strings.settingsProvidersSubtitle
    SettingsSubPage.APPEARANCE -> strings.settingsAppearanceSubtitle
    SettingsSubPage.LOGS -> strings.settingsLogsSubtitle
    SettingsSubPage.SYSTEM_INFO -> strings.settingsSystemInfoSubtitle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    initialTab: Int = -1,
    onOpenAgent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val currentLang by viewModel.currentLanguage.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val systemLogs by viewModel.systemLogs.collectAsState()
    val projects by viewModel.platformTranslationProjects.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val debugModeEnabled by viewModel.debugModeEnabled.collectAsState()
    val unattendedEnabled by viewModel.unattendedCompanionEnabled.collectAsState()

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
    var expandedLogId by remember { mutableStateOf<String?>(null) }

    val filteredLogs = remember(systemLogs, logFilter) {
        when (logFilter) {
            "ERROR" -> systemLogs.filter { it.level == LogLevel.ERROR }
            "TRANSLATION" -> systemLogs.filter { it.tag.contains("TRANSLATE", ignoreCase = true) || it.tag.contains("SPLIT", ignoreCase = true) }
            "API" -> systemLogs.filter { it.tag.contains("LLM", ignoreCase = true) || it.tag.contains("API", ignoreCase = true) }
            else -> systemLogs
        }
    }
    val visibleLogs = remember(filteredLogs) { filteredLogs.takeLast(120) }
    val logListState = rememberLazyListState()
    LaunchedEffect(visibleLogs.lastOrNull()?.id, visibleLogs.size, logFilter) {
        if (visibleLogs.isNotEmpty()) {
            // Index 0 is the filter row; the last log is the final list item.
            logListState.animateScrollToItem(visibleLogs.size)
        }
    }

    AnimatedContent(
        targetState = activeSubPage,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(120)) { width -> width / 4 } + fadeIn(tween(90))).togetherWith(
                    slideOutHorizontally(tween(90)) { width -> -width / 8 } + fadeOut(tween(70))
                )
            } else {
                (slideInHorizontally(tween(120)) { width -> -width / 4 } + fadeIn(tween(90))).togetherWith(
                    slideOutHorizontally(tween(90)) { width -> width / 8 } + fadeOut(tween(70))
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
                        subtitle = strings.settingsSubtitle
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.compactHorizontalPadding,
                        vertical = Spacing.m
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l)
                ) {
                    // Section 1: Preference & Interfaces
                    item {
                        AppSection(title = strings.settingsSectionPreferences) {
                            // 1. 模型接口 Entrance
                            AppSettingsRow(
                                title = strings.settingsProvidersTitle,
                                subtitle = strings.settingsProvidersSubtitle,
                                leadingIcon = Icons.Outlined.SmartToy,
                                iconTint = MaterialTheme.colorScheme.primary,
                                iconBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                valueText = strings.settingsProvidersCount(providers.size),
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.PROVIDERS }
                            )

                            AppDivider(startIndent = 58.dp)

                            // 2. AI 助手 Entrance（Agent 对话页）
                            val agentEntry = agentEntryStrings()
                            AppSettingsRow(
                                title = agentEntry.entryTitle,
                                subtitle = agentEntry.entrySubtitle,
                                leadingIcon = Icons.Outlined.Forum,
                                iconTint = AccentPurple,
                                iconBackground = AccentPurple.copy(alpha = 0.12f),
                                showChevron = true,
                                onClick = onOpenAgent
                            )

                            AppDivider(startIndent = 58.dp)

                            // 3. 外观显示 Entrance
                            val themeLabel = when (themeMode) {
                                AppThemeMode.SYSTEM -> strings.themeModeSystem
                                AppThemeMode.LIGHT -> strings.themeModeLight
                                AppThemeMode.DARK -> strings.themeModeDark
                            }
                            val langLabel = if (currentLang == AppLanguage.CHINESE) "中文" else "English"

                            AppSettingsRow(
                                title = strings.settingsAppearanceTitle,
                                subtitle = strings.settingsAppearanceSubtitle,
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
                        AppSection(title = strings.settingsSectionDiagnostics) {
                            // 3. 系统日志 Entrance
                            AppSettingsRow(
                                title = strings.settingsLogsTitle,
                                subtitle = strings.settingsLogsSubtitle,
                                leadingIcon = Icons.Outlined.ReceiptLong,
                                iconTint = AccentOrange,
                                iconBackground = AccentOrange.copy(alpha = 0.12f),
                                valueText = strings.settingsLogsCount(systemLogs.size),
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.LOGS }
                            )

                            AppDivider(startIndent = 58.dp)

                            AppSettingsRow(
                                title = strings.settingsUnattendedTitle,
                                subtitle = if (unattendedEnabled) {
                                    strings.settingsUnattendedOnSubtitle
                                } else {
                                    strings.settingsUnattendedOffSubtitle
                                },
                                leadingIcon = Icons.Outlined.AutoAwesome,
                                iconTint = AccentPurple,
                                iconBackground = AccentPurple.copy(alpha = 0.12f),
                                showChevron = false,
                                trailingContent = {
                                    Switch(
                                        checked = unattendedEnabled,
                                        onCheckedChange = viewModel::setUnattendedCompanionEnabled
                                    )
                                },
                                onClick = { viewModel.setUnattendedCompanionEnabled(!unattendedEnabled) }
                            )

                            AppDivider(startIndent = 58.dp)

                            AppSettingsRow(
                                title = strings.settingsDebugTitle,
                                subtitle = if (debugModeEnabled) {
                                    strings.settingsDebugOnSubtitle
                                } else {
                                    strings.settingsDebugOffSubtitle
                                },
                                leadingIcon = Icons.Outlined.BugReport,
                                iconTint = MaterialTheme.colorScheme.error,
                                iconBackground = MaterialTheme.colorScheme.errorContainer,
                                showChevron = false,
                                trailingContent = {
                                    Switch(
                                        checked = debugModeEnabled,
                                        onCheckedChange = viewModel::setDebugModeEnabled
                                    )
                                },
                                onClick = { viewModel.setDebugModeEnabled(!debugModeEnabled) }
                            )

                            AppDivider(startIndent = 58.dp)

                            // 4. 系统信息 Entrance
                            AppSettingsRow(
                                title = strings.settingsSystemInfoTitle,
                                subtitle = strings.settingsSystemInfoSubtitle,
                                leadingIcon = Icons.Outlined.Info,
                                iconTint = AccentGreen,
                                iconBackground = AccentGreen.copy(alpha = 0.12f),
                                valueText = strings.settingsAppVersion,
                                showChevron = true,
                                onClick = { activeSubPage = SettingsSubPage.SYSTEM_INFO }
                            )
                        }
                    }

                    // Section 3: Active Default Provider Overview Card
                    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
                    if (defaultProvider != null) {
                        item {
                            AppCard(
                                shape = GroupedCardShape,
                                contentPadding = PaddingValues(Spacing.l),
                                onClick = { activeSubPage = SettingsSubPage.PROVIDERS }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppIconBubble(
                                        icon = Icons.Outlined.Dns,
                                        size = 42.dp
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.m))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.settingsDefaultProviderLabel(defaultProvider.selectedModel),
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
                                text = currentSubPage.title(strings),
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
                                            Toast.makeText(context, strings.logsCopiedToast, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = strings.settingsLogsTitle,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.clearSystemLogs() }) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = strings.logEmpty,
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
                                vertical = Spacing.s
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.settingsConfiguredProviders(providers.size),
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
                                    AppCard(contentPadding = PaddingValues(28.dp)) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = strings.settingsNoProviders,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(14.dp))
                                            AppPrimaryButton(
                                                text = strings.addProvider,
                                                onClick = { showAddDialog = true },
                                                icon = Icons.Default.Add,
                                                modifier = Modifier.widthIn(max = 220.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(providers, key = { it.id }) { provider ->
                                    ProviderCardApple(
                                        provider = provider,
                                        strings = strings,
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
                                vertical = Spacing.s
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item {
                                AppSection(title = strings.themeSettingsTitle) {
                                    AppSettingsRow(
                                        title = strings.themeModeSystem,
                                        subtitle = strings.themeModeSystemSubtitle,
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
                                        title = strings.themeModeLight,
                                        subtitle = strings.themeModeLightSubtitle,
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
                                        title = strings.themeModeDark,
                                        subtitle = strings.themeModeDarkSubtitle,
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
                                        title = strings.languageChineseTitle,
                                        subtitle = strings.languageChineseSubtitle,
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
                                        title = strings.languageEnglishTitle,
                                        subtitle = strings.languageEnglishSubtitle,
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
                        }
                    }

                    SettingsSubPage.LOGS -> {
                        // ----------------------------------------------------
                        // Sub-Page 3: 系统日志 (System Logs)
                        // ----------------------------------------------------
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.compactHorizontalPadding,
                                vertical = Spacing.s
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
                                            label = { Text(strings.logFilterAll) },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "ERROR",
                                            onClick = { logFilter = "ERROR" },
                                            label = { Text(strings.logFilterError) },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "TRANSLATION",
                                            onClick = { logFilter = "TRANSLATION" },
                                            label = { Text(strings.logFilterTranslation) },
                                            shape = SmallControlShape
                                        )
                                        FilterChip(
                                            selected = logFilter == "API",
                                            onClick = { logFilter = "API" },
                                            label = { Text(strings.logFilterApi) },
                                            shape = SmallControlShape
                                        )
                                    }

                                    Text(
                                        text = strings.logCountLabel(filteredLogs.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (filteredLogs.isEmpty()) {
                                item {
                                    AppCard(contentPadding = PaddingValues(28.dp)) {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = strings.logEmpty,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(visibleLogs, key = { it.id }) { log ->
                                    AppCard(
                                        contentPadding = PaddingValues(0.dp),
                                        onClick = { expandedLogId = if (expandedLogId == log.id) null else log.id }
                                    ) {
                                        val isExpanded = expandedLogId == log.id
                                        val levelColor = when (log.level) {
                                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                            LogLevel.WARN -> LocalAppColors.current.warning
                                            LogLevel.INFO -> MaterialTheme.colorScheme.primary
                                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = PillShape,
                                                    color = levelColor.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = log.level.name,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
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
                                vertical = Spacing.s
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item {
                                AppSection(title = strings.settingsSystemInfoTitle) {
                                    AppStatusRow(
                                        label = strings.settingsAppName,
                                        value = strings.settingsAppName,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(
                                        label = strings.settingsVersionLabel,
                                        value = strings.settingsAppVersion,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                    AppDivider(startIndent = 16.dp)
                                    AppStatusRow(
                                        label = strings.settingsProjectsLabel,
                                        value = strings.settingsProjectsCount(projects.size),
                                        modifier = Modifier.padding(16.dp)
                                    )
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
            onFetchModels = viewModel::fetchModelsFromEndpoint,
            onDismiss = { showAddDialog = false },
            onSave = { newProvider, pacing ->
                // saveProvider 内部用 insert 返回的 rowId 落限流值，新建也能一次配好。
                viewModel.saveProvider(newProvider, pacing)
                showAddDialog = false
            }
        )
    }

    // Edit Provider Dialog
    editingProvider?.let { provider ->
        AddEditProviderDialog(
            provider = provider,
            onFetchModels = viewModel::fetchModelsFromEndpoint,
            onDismiss = { editingProvider = null },
            onSave = { updatedProvider, pacing ->
                viewModel.saveProvider(updatedProvider, pacing)
                editingProvider = null
            },
            initialPacing = viewModel.requestPacingFor(provider.id)
        )
    }
}

@Composable
private fun ProviderCardApple(
    provider: ApiProviderEntity,
    strings: AppStrings,
    isTesting: Boolean,
    testResult: String?,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AppCard(
        modifier = Modifier.testTag("provider_card_${provider.name.lowercase(Locale.ROOT).replace(" ", "_")}"),
        shape = GroupedCardShape,
        contentPadding = PaddingValues(Spacing.l)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBadge(
                text = provider.providerType.displayName,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (provider.isDefault) {
                Spacer(modifier = Modifier.width(6.dp))
                AppBadge(
                    text = strings.providerDefaultBadge,
                    containerColor = LocalAppColors.current.successContainer,
                    contentColor = LocalAppColors.current.onSuccessContainer
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = strings.providerEdit, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = strings.providerDelete, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
                color = if (isSuccess) LocalAppColors.current.success.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (isSuccess) LocalAppColors.current.success else MaterialTheme.colorScheme.error
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
                    Text(strings.providerSetDefault, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            OutlinedButton(
                onClick = onTest,
                enabled = !isTesting,
                shape = SmallControlShape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("test_provider_button_${provider.name.lowercase(Locale.ROOT).replace(" ", "_")}")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(strings.providerTestConnection, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = DialogShape,
            title = { Text(strings.providerDeleteTitle) },
            text = { Text(strings.providerDeleteMessage(provider.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProviderDialog(
    provider: ApiProviderEntity?,
    onFetchModels: (ApiProviderEntity, (Result<List<String>>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    /** 一并提交限流额度：新建时 provider id 由 insert 产生，由保存链路负责落值。 */
    onSave: (ApiProviderEntity, RequestPacingOverride) -> Unit,
    /** 已保存的显式限流额度；0 = 自动档。 */
    initialPacing: RequestPacingOverride = RequestPacingOverride()
) {
    val strings = LocalAppStrings.current
    val initialPreset = remember(provider) {
        provider?.let { configured ->
            PresetModels.presets.firstOrNull {
                it.providerType == configured.providerType && it.defaultBaseUrl.trimEnd('/') == configured.baseUrl.trimEnd('/')
            }
        } ?: PresetModels.presets.first {
            it.id == if (provider?.providerType == ProviderType.ANTHROPIC_CLAUDE) "anthropic_compatible" else "openai_compatible"
        }
    }
    var preset by remember { mutableStateOf(initialPreset) }
    var presetMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(provider?.name ?: initialPreset.name) }
    var providerType by remember { mutableStateOf(provider?.providerType ?: initialPreset.providerType) }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: initialPreset.defaultBaseUrl) }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var selectedModel by remember { mutableStateOf(provider?.selectedModel ?: initialPreset.defaultModel) }
    var inputPrice by remember { mutableStateOf(provider?.inputPricePerMillion?.toString() ?: initialPreset.defaultInputPrice.toString()) }
    var outputPrice by remember { mutableStateOf(provider?.outputPricePerMillion?.toString() ?: initialPreset.defaultOutputPrice.toString()) }
    var currency by remember { mutableStateOf(provider?.currency ?: initialPreset.currency) }
    var maxContextTokens by remember { mutableStateOf(provider?.maxContextTokens ?: initialPreset.defaultMaxContextTokens) }
    var isDefault by remember { mutableStateOf(provider?.isDefault ?: false) }
    // 限流额度：空串表示"未配置/自动档"，避免用户为了填 0 而先删字符。
    var rpmText by remember {
        mutableStateOf(initialPacing.rpmLimit.takeIf { it > 0 }?.toString() ?: "")
    }
    var concurrencyText by remember {
        mutableStateOf(initialPacing.concurrencyLimit.takeIf { it > 0 }?.toString() ?: "")
    }
    var fetchedModels by remember { mutableStateOf(initialPreset.recommendedModels) }
    var modelSearch by remember { mutableStateOf("") }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf<String?>(null) }

    fun buildProvider(): ApiProviderEntity {
        val inPrice = inputPrice.toDoubleOrNull() ?: 0.0
        val outPrice = outputPrice.toDoubleOrNull() ?: 0.0
        return provider?.copy(
            name = name.trim(), providerType = providerType, baseUrl = baseUrl.trim(), apiKey = apiKey.trim(),
            selectedModel = selectedModel.trim(), inputPricePerMillion = inPrice,
            outputPricePerMillion = outPrice, currency = currency,
            maxContextTokens = maxContextTokens, isDefault = isDefault
        ) ?: ApiProviderEntity(
            name = name.trim(), providerType = providerType, baseUrl = baseUrl.trim(), apiKey = apiKey.trim(),
            selectedModel = selectedModel.trim(), inputPricePerMillion = inPrice,
            outputPricePerMillion = outPrice, currency = currency,
            maxContextTokens = maxContextTokens, isDefault = isDefault
        )
    }

    /** 非法输入按 0（自动档）处理：限流是保险丝，永远不该因为填错而挡住保存。 */
    fun buildPacing(): RequestPacingOverride = RequestPacingOverride(
        rpmLimit = rpmText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
        concurrencyLimit = concurrencyText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    ).sanitized()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = {
            Text(
                text = if (provider == null) strings.providerAddTitle else strings.providerEditTitle,
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
                    ExposedDropdownMenuBox(expanded = presetMenu, onExpandedChange = { presetMenu = it }) {
                        OutlinedTextField(
                            value = preset.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(strings.providerPresetLabel) },
                            supportingText = { Text(preset.description) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(presetMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                            PresetModels.presets.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.name) },
                                    onClick = {
                                        preset = candidate
                                        providerType = candidate.providerType
                                        name = candidate.name
                                        baseUrl = candidate.defaultBaseUrl
                                        selectedModel = candidate.defaultModel
                                        inputPrice = candidate.defaultInputPrice.toString()
                                        outputPrice = candidate.defaultOutputPrice.toString()
                                        currency = candidate.currency
                                        maxContextTokens = candidate.defaultMaxContextTokens
                                        fetchedModels = candidate.recommendedModels
                                        modelSearch = ""
                                        fetchMessage = null
                                        presetMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.providerNameLabel) },
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
                        label = { Text(strings.providerModelLabel) },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            fetchingModels = true
                            fetchMessage = null
                            onFetchModels(buildProvider()) { result ->
                                fetchingModels = false
                                result.onSuccess {
                                    fetchedModels = it
                                    fetchMessage = strings.providerFetchedCount(it.size)
                                }.onFailure { fetchMessage = it.localizedMessage ?: strings.providerFetchFailed }
                            }
                        },
                        enabled = !fetchingModels && baseUrl.isNotBlank(),
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (fetchingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (fetchingModels) strings.providerFetchingModels else strings.providerFetchModels)
                    }
                    fetchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (fetchedModels.isNotEmpty()) {
                    item {
                        OutlinedTextField(
                            value = modelSearch,
                            onValueChange = { modelSearch = it },
                            label = { Text(strings.providerSearchModel) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            shape = SmallControlShape,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val matches = fetchedModels.filter { modelSearch.isBlank() || it.contains(modelSearch, true) }.take(8)
                        Column {
                            matches.forEach { model ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedModel = model },
                                    shape = SmallControlShape,
                                    color = if (selectedModel == model) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                ) {
                                    Text(model, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputPrice,
                            onValueChange = { inputPrice = it },
                            label = { Text(strings.providerInputPriceLabel) },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = outputPrice,
                            onValueChange = { outputPrice = it },
                            label = { Text(strings.providerOutputPriceLabel) },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 请求级限流：留空即自动档。填了任一非零值就会固定额度、关闭自动升降。
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = rpmText,
                            onValueChange = { rpmText = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = { Text(strings.providerRpmLabel) },
                            placeholder = { Text("60") },
                            singleLine = true,
                            shape = SmallControlShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = concurrencyText,
                            onValueChange = { concurrencyText = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text(strings.providerConcurrencyLabel) },
                            placeholder = { Text("4") },
                            singleLine = true,
                            shape = SmallControlShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Text(
                        text = strings.providerPacingHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                        Text(strings.providerSetAsDefault, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(buildProvider(), buildPacing()) },
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
        }
    )
}
