package com.breakyuna.noveltranslator.ui.screens.workspace

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.model.ChapterEntity
import com.breakyuna.noveltranslator.data.model.ChapterStatus
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onNavigateToTranslation: () -> Unit,
    onNavigateToGlossary: () -> Unit,
    onNavigateToReader: (Long) -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val glossary by viewModel.activeGlossary.collectAsState()

    val defaultProvider = providers.firstOrNull { it.id == project?.defaultProviderId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()

    var selectedFilter by remember { mutableStateOf<ChapterStatus?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var showSplitDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentProject = project!!
    val filteredChapters = chapters.filter { ch ->
        (selectedFilter == null || ch.status == selectedFilter) &&
                (searchQuery.isBlank() || ch.title.contains(searchQuery, ignoreCase = true))
    }

    val progress = if (currentProject.totalChapters > 0) {
        currentProject.translatedChapters.toFloat() / currentProject.totalChapters.toFloat()
    } else 0f
    val percentInt = (progress * 100).toInt()

    val pendingCount = chapters.count { it.status == ChapterStatus.PENDING || it.status == ChapterStatus.ERROR }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentProject.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentProject.sourceLanguage} → ${currentProject.targetLanguage} · ${currentProject.fileType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.cancel,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (currentProject.fileType == "TXT") {
                        IconButton(
                            onClick = { showSplitDialog = true },
                            modifier = Modifier.testTag("open_splitter_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCut,
                                contentDescription = strings.openChapterSplitter,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.testTag("open_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = strings.openExport,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
            // 1. Overview & Primary Action Surface
            item {
                AppGroupedSurface {
                    // Header info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "翻译进度",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${currentProject.translatedChapters} / ${currentProject.totalChapters} 章",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "$percentInt%",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (percentInt == 100) StatusSuccess else MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = if (percentInt == 100) StatusSuccess else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Token & Cost row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "已消耗: ${TokenCalculator.formatTokenCount(currentProject.totalPromptTokens + currentProject.totalCompletionTokens)} Tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "预估费用: ${TokenCalculator.formatCost(currentProject.totalCost, currentProject.costCurrency)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppPrimaryButton(
                            text = if (percentInt == 100) "重新翻译 / 润色" else "继续翻译",
                            onClick = onNavigateToTranslation,
                            icon = Icons.Default.Translate,
                            modifier = Modifier.weight(1f)
                        )

                        if (pendingCount > 0 && defaultProvider != null) {
                            AppSecondaryButton(
                                text = "批量加入队列 ($pendingCount)",
                                onClick = {
                                    val targets = chapters.filter { it.status == ChapterStatus.PENDING || it.status == ChapterStatus.ERROR }
                                    viewModel.enqueueBatchChapters(currentProject, targets, defaultProvider)
                                },
                                icon = Icons.Outlined.PlaylistAdd,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 2. Project Modules Section
            item {
                AppSection(title = "工程功能") {
                    AppGroupedSurface(contentPadding = PaddingValues(0.dp)) {
                        AppSettingsRow(
                            title = "角色与专有名词术语表",
                            subtitle = "${glossary.size} 条已收录专有名词 · 支持 AI 提取",
                            leadingIcon = Icons.Outlined.MenuBook,
                            onClick = onNavigateToGlossary
                        )
                        AppDivider(startIndent = 52.dp)
                        AppSettingsRow(
                            title = "双语排版对照阅读",
                            subtitle = "全书段落级双语对照与译文微调",
                            leadingIcon = Icons.Outlined.AutoStories,
                            onClick = {
                                val firstChapterId = chapters.firstOrNull()?.id ?: 0L
                                onNavigateToReader(firstChapterId)
                            }
                        )
                        AppDivider(startIndent = 52.dp)
                        AppSettingsRow(
                            title = "全书导出与分享",
                            subtitle = "支持 EPUB / TXT 格式与双语对照导出",
                            leadingIcon = Icons.Outlined.FileDownload,
                            onClick = { showExportDialog = true }
                        )
                    }
                }
            }

            // 3. Chapter Management Section
            item {
                Column {
                    Text(
                        text = "章节管理 (${chapters.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.searchChapterPlaceholder) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Status Filters
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterTabApple(
                                text = "全部 (${chapters.size})",
                                selected = selectedFilter == null,
                                onClick = { selectedFilter = null }
                            )
                        }
                        item {
                            FilterTabApple(
                                text = "待翻译 (${chapters.count { it.status == ChapterStatus.PENDING }})",
                                selected = selectedFilter == ChapterStatus.PENDING,
                                onClick = { selectedFilter = ChapterStatus.PENDING }
                            )
                        }
                        item {
                            FilterTabApple(
                                text = "已完成 (${chapters.count { it.status == ChapterStatus.COMPLETED }})",
                                selected = selectedFilter == ChapterStatus.COMPLETED,
                                onClick = { selectedFilter = ChapterStatus.COMPLETED }
                            )
                        }
                        item {
                            FilterTabApple(
                                text = "异常 (${chapters.count { it.status == ChapterStatus.ERROR }})",
                                selected = selectedFilter == ChapterStatus.ERROR,
                                onClick = { selectedFilter = ChapterStatus.ERROR }
                            )
                        }
                    }
                }
            }

            // Chapter list items
            if (filteredChapters.isEmpty()) {
                item {
                    AppGroupedSurface(
                        contentPadding = PaddingValues(24.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.noMatchingChapters,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredChapters,
                    key = { _, chapter -> chapter.id }
                ) { index, chapter ->
                    val isFirst = index == 0
                    val isLast = index == filteredChapters.lastIndex

                    val shape = when {
                        isFirst && isLast -> GroupedCardShape
                        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        else -> RoundedCornerShape(0.dp)
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column {
                            WorkspaceChapterRow(
                                chapter = chapter,
                                onPreview = { onNavigateToReader(chapter.id) },
                                onTranslate = {
                                    if (defaultProvider != null) {
                                        viewModel.translateSingleChapter(chapter.id, defaultProvider)
                                        onNavigateToTranslation()
                                    } else {
                                        viewModel.showMessage(strings.noProvidersConfigured)
                                    }
                                }
                            )
                            if (!isLast) {
                                AppDivider(startIndent = 16.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSplitDialog) {
        ChapterSplitDialog(
            currentRegex = TxtParser.REGEX_CHINESE,
            onDismiss = { showSplitDialog = false },
            onReSplitWithRegex = { regex ->
                viewModel.reSplitChapters(currentProject.id, regex)
                showSplitDialog = false
            },
            onRunAgentSplit = {
                if (defaultProvider != null) {
                    viewModel.runAgentChapterSplit(currentProject.id, defaultProvider)
                    showSplitDialog = false
                } else {
                    viewModel.showMessage(strings.noProvidersConfigured)
                }
            }
        )
    }

    if (showExportDialog) {
        ExportNovelDialog(
            onDismiss = { showExportDialog = false },
            onExportTxt = { includeGlossary, includeParallel ->
                viewModel.exportToTxt(currentProject.id, includeGlossary, includeParallel) { exportedFile ->
                    shareExportedFile(context, exportedFile, "text/plain")
                }
                showExportDialog = false
            },
            onExportEpub = {
                viewModel.exportToEpub(currentProject.id) { exportedFile ->
                    shareExportedFile(context, exportedFile, "application/epub+zip")
                }
                showExportDialog = false
            }
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
private fun WorkspaceChapterRow(
    chapter: ChapterEntity,
    onPreview: () -> Unit,
    onTranslate: () -> Unit
) {
    val statusColor = when (chapter.status) {
        ChapterStatus.COMPLETED -> StatusSuccess
        ChapterStatus.TRANSLATING -> MaterialTheme.colorScheme.primary
        ChapterStatus.ERROR -> StatusError
        ChapterStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val statusText = when (chapter.status) {
        ChapterStatus.COMPLETED -> "已完成"
        ChapterStatus.TRANSLATING -> "翻译中"
        ChapterStatus.ERROR -> "异常"
        ChapterStatus.PENDING -> "待翻译"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreview() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Chapter Index & Title
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "第 ${chapter.chapterIndex} 章 · ${chapter.title.ifBlank { "无标题" }}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${chapter.originalWordCount} 原文字 · $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Action Button
        if (chapter.status == ChapterStatus.COMPLETED) {
            TextButton(
                onClick = onPreview,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("阅读", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedButton(
                onClick = onTranslate,
                shape = SmallControlShape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("翻译", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun shareExportedFile(context: android.content.Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Translated Novel"))
    } catch (e: Exception) {
        // Share error fallback
    }
}

@Composable
fun ChapterSplitDialog(
    currentRegex: String,
    onDismiss: () -> Unit,
    onReSplitWithRegex: (String) -> Unit,
    onRunAgentSplit: () -> Unit
) {
    val strings = LocalAppStrings.current
    var selectedPreset by remember { mutableStateOf("chinese") }
    var customRegexText by remember { mutableStateOf(currentRegex) }
    var showDestructiveConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestDestructiveAction(action: () -> Unit) {
        pendingAction = action
        showDestructiveConfirm = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.splitterTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.splitterDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedPreset == "chinese",
                        onClick = {
                            selectedPreset = "chinese"
                            customRegexText = TxtParser.REGEX_CHINESE
                        },
                        label = { Text(strings.presetChinese) },
                        shape = SmallControlShape
                    )
                    FilterChip(
                        selected = selectedPreset == "english",
                        onClick = {
                            selectedPreset = "english"
                            customRegexText = TxtParser.REGEX_ENGLISH
                        },
                        label = { Text(strings.presetEnglish) },
                        shape = SmallControlShape
                    )
                    FilterChip(
                        selected = selectedPreset == "markdown",
                        onClick = {
                            selectedPreset = "markdown"
                            customRegexText = TxtParser.REGEX_MARKDOWN
                        },
                        label = { Text(strings.presetMarkdown) },
                        shape = SmallControlShape
                    )
                }

                OutlinedTextField(
                    value = customRegexText,
                    onValueChange = {
                        customRegexText = it
                        selectedPreset = "custom"
                    },
                    label = { Text(strings.regexPatternLabel) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = GroupedCardShape
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = strings.aiAgentSlicerTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = strings.aiAgentSlicerDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { requestDestructiveAction(onRunAgentSplit) },
                            shape = ButtonShape,
                            modifier = Modifier.fillMaxWidth().testTag("run_agent_split_button")
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.runAiAgentSlicer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { requestDestructiveAction { onReSplitWithRegex(customRegexText) } },
                shape = ButtonShape
            ) {
                Text(strings.resliceByRegex)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
        shape = DialogShape
    )

    if (showDestructiveConfirm) {
        AlertDialog(
            onDismissRequest = {
                showDestructiveConfirm = false
                pendingAction = null
            },
            title = { Text(strings.confirmDestructiveAction) },
            text = { Text(strings.destructiveSplitWarning) },
            confirmButton = {
                Button(
                    onClick = {
                        val action = pendingAction
                        showDestructiveConfirm = false
                        pendingAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = ButtonShape
                ) {
                    Text(strings.confirmDestructiveAction)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDestructiveConfirm = false
                    pendingAction = null
                }) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }
}

@Composable
fun ExportNovelDialog(
    onDismiss: () -> Unit,
    onExportTxt: (includeGlossary: Boolean, includeParallel: Boolean) -> Unit,
    onExportEpub: () -> Unit
) {
    val strings = LocalAppStrings.current
    var includeGlossary by remember { mutableStateOf(true) }
    var includeParallel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.exportTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppGroupedSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        text = strings.epubExportTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = strings.epubExportDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AppSecondaryButton(
                        text = strings.exportEpubBtn,
                        onClick = onExportEpub,
                        icon = Icons.Outlined.Book,
                        modifier = Modifier.fillMaxWidth().testTag("export_epub_button")
                    )
                }

                AppGroupedSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        text = strings.txtExportTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeGlossary,
                            onCheckedChange = { includeGlossary = it }
                        )
                        Text(strings.includeGlossaryOption, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeParallel,
                            onCheckedChange = { includeParallel = it }
                        )
                        Text(strings.bilingualComparisonOption, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    AppPrimaryButton(
                        text = strings.exportTxtBtn,
                        onClick = { onExportTxt(includeGlossary, includeParallel) },
                        icon = Icons.Outlined.Description,
                        modifier = Modifier.fillMaxWidth().testTag("export_txt_button")
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
        shape = DialogShape
    )
}
