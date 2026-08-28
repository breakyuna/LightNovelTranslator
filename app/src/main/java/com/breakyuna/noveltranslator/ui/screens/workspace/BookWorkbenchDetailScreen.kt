package com.breakyuna.noveltranslator.ui.screens.workspace

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.core.logger.SystemLogEntry
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.screens.bookdetail.TARGET_LANGUAGE_OPTIONS
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.breakyuna.noveltranslator.ui.components.rememberAsyncBookImage
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class WorkbenchTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TASKS("任务控制台", Icons.Default.Dashboard),
    CONFIG("功能配置", Icons.Default.Tune),
    GLOSSARY("名词与术语表", Icons.Default.Spellcheck),
    LOGS("运行与系统日志", Icons.Default.Terminal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookWorkbenchDetailScreen(
    bookId: Long,
    viewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onOpenReader: (Long, Long?) -> Unit,
    onOpenBookDetail: (Long) -> Unit
) {
    val windowSize = rememberWindowSize()
    val useWideWorkbench = windowSize.isExpanded &&
        windowSize.screenWidthDp > windowSize.screenHeightDp

    val book by remember(bookId) { viewModel.bookPlatformRepo.observeBook(bookId) }
        .collectAsState(initial = null)
    val editions by remember(bookId) { viewModel.bookPlatformRepo.observeEditions(bookId) }
        .collectAsState(initial = emptyList())
    val chapters by remember(bookId) { viewModel.bookPlatformRepo.observeChapters(bookId) }
        .collectAsState(initial = emptyList())
    val translationProjects by remember(bookId) { viewModel.bookPlatformRepo.observeTranslationProjects(bookId) }
        .collectAsState(initial = emptyList())
    val allRuns by remember(bookId) { viewModel.observeRunsByBook(bookId) }
        .collectAsState(initial = emptyList())
    val allProviders by viewModel.allProviders.collectAsState()
    val debugModeEnabled by viewModel.debugModeEnabled.collectAsState()
    var selectedTab by rememberSaveable(bookId) { mutableStateOf(WorkbenchTab.TASKS) }
    val systemLogs by remember(selectedTab) {
        if (selectedTab == WorkbenchTab.TASKS || selectedTab == WorkbenchTab.LOGS) viewModel.systemLogs
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Active target edition selection (defaults to preferred reading edition or first translation edition)
    var selectedTargetEditionId by rememberSaveable(bookId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(editions, book) {
        if (selectedTargetEditionId == null && editions.isNotEmpty()) {
            val transEdition = editions.firstOrNull { it.type == EditionType.AI_TRANSLATION.name }
            val preferred = editions.firstOrNull { it.id == book?.preferredReadingEditionId }
            selectedTargetEditionId = preferred?.id ?: transEdition?.id ?: editions.last().id
        }
    }

    val currentTargetEdition = editions.firstOrNull { it.id == selectedTargetEditionId }
    val currentProject = translationProjects.firstOrNull { it.targetEditionId == selectedTargetEditionId }

    val activeRuns by remember(currentProject?.id) {
        if (currentProject != null) viewModel.observeRunsByProject(currentProject.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val latestRun = activeRuns.firstOrNull() ?: allRuns.firstOrNull { it.translationProjectId == currentProject?.id }

    val projectLexicon by remember(currentProject?.id, selectedTab, useWideWorkbench) {
        if (currentProject != null && (selectedTab == WorkbenchTab.GLOSSARY || useWideWorkbench)) viewModel.observeLexicon(currentProject.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val projectLexiconCandidates by remember(currentProject?.id, selectedTab, useWideWorkbench) {
        if (currentProject != null && (selectedTab == WorkbenchTab.GLOSSARY || useWideWorkbench)) viewModel.observeLexiconCandidates(currentProject.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val projectStoryMemory by remember(currentProject?.id, selectedTab) {
        if (currentProject != null && selectedTab == WorkbenchTab.GLOSSARY) viewModel.observeStoryMemory(currentProject.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val latestBatches by remember(latestRun?.id, selectedTab) {
        if (latestRun != null && selectedTab == WorkbenchTab.TASKS) viewModel.observeBatches(latestRun.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var showCreateEditionDialog by rememberSaveable { mutableStateOf(false) }
    var showTermScannerDialog by rememberSaveable { mutableStateOf(false) }
    var showAddTermDialog by rememberSaveable { mutableStateOf(false) }
    var chapterActionTarget by remember { mutableStateOf<LogicalChapterEntity?>(null) }

    val currentBook = book
    if (currentBook == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("工作台") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "工作台 · ${currentBook.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "全书共 ${chapters.size} 章 · ${editions.size} 个版本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenBookDetail(currentBook.id) }) {
                        Icon(Icons.Default.Info, "书籍详情")
                    }
                    IconButton(onClick = { onOpenReader(currentBook.id, currentTargetEdition?.id) }) {
                        Icon(Icons.Default.MenuBook, "阅读译文")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        val tabContent: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier = modifier,
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = if (selectedTab == WorkbenchTab.LOGS) 1440.dp else 1180.dp)
                        .fillMaxSize()
                ) {
                    when (selectedTab) {
                        WorkbenchTab.TASKS -> {
                            TasksAndControlTab(
                                book = currentBook,
                                editions = editions,
                                chapters = chapters,
                                targetEdition = currentTargetEdition,
                                project = currentProject,
                                run = latestRun,
                                batches = latestBatches,
                                providers = allProviders,
                                systemLogs = systemLogs,
                                viewModel = viewModel,
                                onConfigureProject = { selectedTab = WorkbenchTab.CONFIG },
                                onScanTerms = { showTermScannerDialog = true },
                                onSelectChapterAction = { chapterActionTarget = it }
                            )
                        }
                        WorkbenchTab.CONFIG -> {
                            ConfigurationTab(
                                book = currentBook,
                                editions = editions,
                                targetEdition = currentTargetEdition,
                                project = currentProject,
                                providers = allProviders,
                                viewModel = viewModel,
                                onCreateEdition = { showCreateEditionDialog = true }
                            )
                        }
                        WorkbenchTab.GLOSSARY -> {
                            GlossaryManagementTab(
                                book = currentBook,
                                project = currentProject,
                                lexicon = projectLexicon,
                                candidates = projectLexiconCandidates,
                                storyMemory = projectStoryMemory,
                                onScanTerms = { showTermScannerDialog = true },
                                onAddTerm = { showAddTermDialog = true },
                                viewModel = viewModel
                            )
                        }
                        WorkbenchTab.LOGS -> {
                            LogsAndHistoryTab(
                                book = currentBook,
                                project = currentProject,
                                allRuns = allRuns,
                                systemLogs = systemLogs,
                                viewModel = viewModel,
                                debugModeEnabled = debugModeEnabled
                            )
                        }
                    }
                }
            }
        }

        if (useWideWorkbench) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                WorkbenchSidebar(
                    modifier = Modifier
                        .width(if (windowSize.screenWidthDp >= 1100.dp) 304.dp else 264.dp)
                        .fillMaxHeight(),
                    book = currentBook,
                    editions = editions,
                    selectedEdition = currentTargetEdition,
                    project = currentProject,
                    run = latestRun,
                    providers = allProviders,
                    selectedTab = selectedTab,
                    chapterCount = chapters.size,
                    lexiconCount = projectLexicon.count { it.reviewStatus == ReviewStatus.CONFIRMED.name },
                    onSelectTab = { selectedTab = it },
                    onSelectEdition = { selectedTargetEditionId = it },
                    onCreateEdition = { showCreateEditionDialog = true },
                    onScanTerms = { showTermScannerDialog = true },
                    onOpenReader = { onOpenReader(currentBook.id, currentTargetEdition?.id) },
                    viewModel = viewModel
                )
                VerticalDivider()
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WideWorkspaceHeader(
                        tab = selectedTab,
                        edition = currentTargetEdition,
                        project = currentProject
                    )
                    tabContent(Modifier.fillMaxWidth().weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                WorkbenchHeroCard(
                    book = currentBook,
                    editions = editions,
                    selectedEdition = currentTargetEdition,
                    onSelectEdition = { selectedTargetEditionId = it },
                    onCreateEdition = { showCreateEditionDialog = true }
                )

                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WorkbenchTab.values().forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(tab.icon, null, modifier = Modifier.size(16.dp))
                                    Text(tab.title, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        )
                    }
                }

                tabContent(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }

    // Dialogs
    if (showCreateEditionDialog) {
        val originalEdition = editions.firstOrNull { it.id == currentBook.primaryEditionId }
            ?: editions.firstOrNull { it.type == EditionType.IMPORTED.name }
            ?: editions.firstOrNull()

        CreateEditionDialog(
            book = currentBook,
            sourceEdition = originalEdition,
            onDismiss = { showCreateEditionDialog = false },
            onConfirm = { sourceId, targetLang, name ->
                viewModel.createTranslationEdition(currentBook.id, sourceId, targetLang, name) { newEditionId ->
                    selectedTargetEditionId = newEditionId
                    showCreateEditionDialog = false
                    selectedTab = WorkbenchTab.CONFIG
                }
            }
        )
    }

    if (showTermScannerDialog) {
        val originalEdition = editions.firstOrNull { it.id == currentBook.primaryEditionId }
            ?: editions.firstOrNull { it.type == EditionType.IMPORTED.name }
            ?: editions.firstOrNull()

        TermScannerDialog(
            book = currentBook,
            sourceEdition = originalEdition,
            totalChapters = chapters.size,
            providers = allProviders,
            targetProjectId = currentProject?.id,
            onDismiss = { showTermScannerDialog = false },
            onStartScan = { startCh, endCh, provider, targetLang, onProgress, onComplete ->
                if (originalEdition != null) {
                    viewModel.scanGlossaryForBook(
                        bookId = currentBook.id,
                        sourceEditionId = originalEdition.id,
                        targetProjectId = currentProject?.id,
                        startChapter = startCh,
                        endChapter = endCh,
                        provider = provider,
                        targetLanguage = targetLang,
                        onProgress = onProgress,
                        onComplete = { count ->
                            onComplete(count)
                        }
                    )
                }
            }
        )
    }

    if (showAddTermDialog && currentProject != null) {
        AddLexiconEntryDialog(
            projectId = currentProject.id,
            onDismiss = { showAddTermDialog = false },
            onConfirm = { entry ->
                viewModel.upsertLexiconEntry(entry)
                showAddTermDialog = false
            }
        )
    }

    if (chapterActionTarget != null && currentTargetEdition != null) {
        val targetCh = chapterActionTarget!!
        AlertDialog(
            onDismissRequest = { chapterActionTarget = null },
            title = { Text("章节操作 · ${targetCh.canonicalTitle}") },
            text = {
                Text("章节编号: 第 ${targetCh.chapterIndex} 章\n您可以直接重新翻译此章节，或在阅读器中预览内容。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.retranslateChapter(
                            bookId = currentBook.id,
                            editionId = currentTargetEdition.id,
                            logicalChapterId = targetCh.id,
                            projectId = currentProject?.id
                        )
                        chapterActionTarget = null
                    }
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重译此章")
                }
            },
            dismissButton = {
                TextButton(onClick = { chapterActionTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WorkbenchSidebar(
    modifier: Modifier,
    book: BookEntity,
    editions: List<EditionEntity>,
    selectedEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    run: PlatformTranslationRunEntity?,
    providers: List<ApiProviderEntity>,
    selectedTab: WorkbenchTab,
    chapterCount: Int,
    lexiconCount: Int,
    onSelectTab: (WorkbenchTab) -> Unit,
    onSelectEdition: (Long) -> Unit,
    onCreateEdition: () -> Unit,
    onScanTerms: () -> Unit,
    onOpenReader: () -> Unit,
    viewModel: AppViewModel
) {
    var editionMenuExpanded by remember { mutableStateOf(false) }
    val provider = providers.firstOrNull { it.id == project?.providerId }
    val currentState = project?.state ?: run?.state ?: "IDLE"
    val completedChapters = run?.completedChapters ?: 0
    val progress = if (chapterCount > 0) {
        (completedChapters.toFloat() / chapterCount).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp, 76.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 2.dp
                    ) {
                        val cover by rememberAsyncBookImage(book.coverPath, maxDimension = 320)
                        if (cover != null) {
                            Image(
                                bitmap = cover!!,
                                contentDescription = book.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            book.author.ifBlank { "未知作者" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$chapterCount 章 · ${editions.size} 个版本",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { editionMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Layers, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("当前版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    selectedEdition?.name ?: "选择目标译本",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                    DropdownMenu(
                        expanded = editionMenuExpanded,
                        onDismissRequest = { editionMenuExpanded = false }
                    ) {
                        editions.forEach { edition ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        edition.name,
                                        fontWeight = if (edition.id == selectedEdition?.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (edition.id == selectedEdition?.id) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onSelectEdition(edition.id)
                                    editionMenuExpanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("新建翻译版本") },
                            leadingIcon = { Icon(Icons.Default.AddCircleOutline, null) },
                            onClick = {
                                editionMenuExpanded = false
                                onCreateEdition()
                            }
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    "工作区",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkbenchTab.values().forEach { tab ->
                        NavigationDrawerItem(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = { Icon(tab.icon, null, modifier = Modifier.size(20.dp)) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tab.title, modifier = Modifier.weight(1f), maxLines = 1)
                                    when (tab) {
                                        WorkbenchTab.GLOSSARY -> if (lexiconCount > 0) Text(
                                            lexiconCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        WorkbenchTab.TASKS -> if (currentState == "RUNNING") Icon(
                                            Icons.Default.Circle,
                                            null,
                                            modifier = Modifier.size(8.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        else -> Unit
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTab(WorkbenchTab.CONFIG) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(7.dp))
                            Text("模型配置", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            provider?.name ?: "尚未选择供应商",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            project?.modelName?.ifBlank { provider?.selectedModel ?: "默认模型" } ?: "点击配置翻译模型",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (project != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("任务进度", style = MaterialTheme.typography.labelMedium)
                        Text("$completedChapters / $chapterCount", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            when {
                                project == null -> onSelectTab(WorkbenchTab.CONFIG)
                                currentState == "RUNNING" -> viewModel.pauseBookTranslation(project.id)
                                currentState == "PAUSED" -> viewModel.resumeBookTranslation(project.id)
                                else -> viewModel.runBookTranslation(project.id)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            when (currentState) {
                                "RUNNING" -> Icons.Default.Pause
                                "PAUSED" -> Icons.Default.PlayArrow
                                else -> Icons.Default.PlayArrow
                            },
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                project == null -> "配置任务"
                                currentState == "RUNNING" -> "暂停"
                                currentState == "PAUSED" -> "继续"
                                else -> "开始翻译"
                            },
                            maxLines = 1
                        )
                    }
                    if (project != null && currentState in listOf("RUNNING", "PAUSED")) {
                        IconButton(onClick = { viewModel.cancelBookTranslation(project.id) }) {
                            Icon(Icons.Default.StopCircle, "终止任务", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScanTerms, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("扫描术语", maxLines = 1)
                    }
                    OutlinedButton(onClick = onOpenReader, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("阅读", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun WideWorkspaceHeader(
    tab: WorkbenchTab,
    edition: EditionEntity?,
    project: TranslationProjectV2Entity?
) {
    val description = when (tab) {
        WorkbenchTab.TASKS -> "监控进度、控制翻译并处理单章任务"
        WorkbenchTab.CONFIG -> "选择模型、翻译范围与文学风格"
        WorkbenchTab.GLOSSARY -> "审核候选术语并维护 Story Memory"
        WorkbenchTab.LOGS -> "查看任务历史、模型调用和诊断日志"
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(tab.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(21.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(tab.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (edition != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            edition.name,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = when (project?.state) {
                        "RUNNING" -> MaterialTheme.colorScheme.primary
                        "PAUSED" -> MaterialTheme.colorScheme.tertiary
                        "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(9.dp)
                ) {}
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorkbenchHeroCard(
    book: BookEntity,
    editions: List<EditionEntity>,
    selectedEdition: EditionEntity?,
    onSelectEdition: (Long) -> Unit,
    onCreateEdition: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Book Cover
                Surface(
                    modifier = Modifier.size(52.dp, 72.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 2.dp
                ) {
                    val cover by rememberAsyncBookImage(book.coverPath, maxDimension = 320)
                    if (cover != null) {
                        Image(
                            bitmap = cover!!,
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Info & Edition selector
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                if (selectedEdition?.type == EditionType.AI_TRANSLATION.name) "AI 译本" else "原版",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Text(
                        "${book.author.ifBlank { "未知作者" }} · 原文: ${book.originalLanguage.ifBlank { "Auto" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Edition Dropdown chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("当前版本:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        editions.forEach { edition ->
                            val isSelected = edition.id == selectedEdition?.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectEdition(edition.id) },
                                label = {
                                    Text(
                                        edition.name.take(12),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.height(28.dp),
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) }
                                } else null
                            )
                        }
                        IconButton(
                            onClick = onCreateEdition,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, "新建版本", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksAndControlTab(
    book: BookEntity,
    editions: List<EditionEntity>,
    chapters: List<LogicalChapterEntity>,
    targetEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    run: PlatformTranslationRunEntity?,
    batches: List<PlatformTranslationBatchEntity>,
    providers: List<ApiProviderEntity>,
    systemLogs: List<SystemLogEntry>,
    viewModel: AppViewModel,
    onConfigureProject: () -> Unit,
    onScanTerms: () -> Unit,
    onSelectChapterAction: (LogicalChapterEntity) -> Unit
) {
    val totalChapters = chapters.size
    val completedChapters = run?.completedChapters ?: 0
    val progress = if (totalChapters > 0) (completedChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
    val currentState = project?.state ?: run?.state ?: "IDLE"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (targetEdition == null || project == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Text(
                            "当前版本尚未配置翻译任务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "选择或创建目标翻译版本，并指定 AI 供应商与提示词偏好即可一键开启翻译。",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onConfigureProject) {
                            Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("前往配置翻译任务")
                        }
                    }
                }
            }
        } else {
            // Task Monitor & Controls Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "翻译任务运行状态",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "模型: ${project.modelName.ifBlank { "默认模型" }} · 模式: ${project.translationMode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = when (currentState) {
                                    "RUNNING" -> MaterialTheme.colorScheme.primary
                                    "PAUSED" -> MaterialTheme.colorScheme.tertiary
                                    "COMPLETED" -> Color(0xFF2E7D32)
                                    "FAILED" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    when (currentState) {
                                        "RUNNING" -> "正在翻译"
                                        "PAUSED" -> "已暂停"
                                        "COMPLETED" -> "已完成"
                                        "FAILED" -> "异常中断"
                                        else -> "待绪"
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (currentState in listOf("RUNNING", "COMPLETED", "FAILED")) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Visual Progress Bar
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "进度: $completedChapters / $totalChapters 章",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        // Stats Summary Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatBox(
                                title = "Prompt Tokens",
                                value = TokenCalculator.formatTokenCount(run?.promptTokens ?: 0L)
                            )
                            StatBox(
                                title = "Completion Tokens",
                                value = TokenCalculator.formatTokenCount(run?.completionTokens ?: 0L)
                            )
                            StatBox(
                                title = "预估费用",
                                value = "$${String.format(java.util.Locale.US, "%.4f", run?.totalCost ?: 0.0)}"
                            )
                        }

                        // Main Control Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (currentState) {
                                "RUNNING" -> {
                                    Button(
                                        onClick = { viewModel.pauseBookTranslation(project.id) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                    ) {
                                        Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("暂停任务")
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.cancelBookTranslation(project.id) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("终止")
                                    }
                                }
                                "PAUSED" -> {
                                    Button(
                                        onClick = { viewModel.resumeBookTranslation(project.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("继续翻译")
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.cancelBookTranslation(project.id) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("取消")
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { viewModel.runBookTranslation(project.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (completedChapters > 0) "继续翻译剩余章节" else "开始全书翻译")
                                    }
                                    OutlinedButton(onClick = onConfigureProject) {
                                        Icon(Icons.Default.Tune, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("修改配置")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Chapter Matrix / Status Grid
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "章节状态矩阵 (点击章节可重译/预览)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "共 ${chapters.size} 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Matrix Flow Grid
                        val sampleDisplayChapters = chapters.take(60)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 48.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(sampleDisplayChapters, key = { it.id }) { chapter ->
                                val isDone = chapter.chapterIndex <= completedChapters
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp, 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onSelectChapterAction(chapter) },
                                    color = if (isDone) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isDone) borderModifier(Color(0xFF2E7D32)) else null
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "${chapter.chapterIndex}",
                                            fontSize = 12.sp,
                                            fontWeight = if (isDone) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isDone) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (chapters.size > 60) {
                            Text(
                                "已展示前 60 章，更多章节可直接在阅读器中浏览。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Embedded Live Process Log Console
            item {
                LiveProcessLogCard(
                    projectId = project.id,
                    systemLogs = systemLogs
                )
            }
        }
    }
}

@Composable
private fun borderModifier(color: Color) = androidx.compose.foundation.BorderStroke(1.dp, color)

@Composable
private fun StatBox(title: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveProcessLogCard(
    projectId: Long?,
    systemLogs: List<SystemLogEntry>
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTagFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(systemLogs, projectId, searchQuery, selectedTagFilter) {
        systemLogs.filter { entry ->
            val matchProject = projectId == null || entry.projectId == null || entry.projectId == projectId
            val matchTag = when (selectedTagFilter) {
                "ALL" -> true
                "TRANSLATION" -> entry.tag in listOf("TRANSLATION", "BATCH")
                "LLM_API" -> entry.tag in listOf("LLM_API", "API_REQ", "API_RESP")
                "GLOSSARY" -> entry.tag in listOf("GLOSSARY", "GLOSSARY_SCAN", "MEMORY")
                "QA_CHECK" -> entry.tag in listOf("QA_CHECK", "STORAGE")
                "ERROR" -> entry.level == LogLevel.ERROR
                else -> true
            }
            val matchQuery = searchQuery.isBlank() || entry.message.contains(searchQuery, ignoreCase = true) || entry.details?.contains(searchQuery, ignoreCase = true) == true
            matchProject && matchTag && matchQuery
        }.takeLast(200)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Terminal, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                    Text(
                        "实时运行与流程日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val text = filteredLogs.joinToString("\n") { "[${it.formattedDate}] [${it.tag}] ${it.message}" }
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, "复制日志", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { SystemLogger.clearLogs() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, "清空日志", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ALL" to "全部", "TRANSLATION" to "流程", "LLM_API" to "模型API", "GLOSSARY" to "术语库", "ERROR" to "错误").forEach { (tag, label) ->
                    val isSelected = selectedTagFilter == tag
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { selectedTagFilter = tag },
                        color = if (isSelected) Color(0xFF3F51B5) else Color(0xFF2C2C34)
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Log Console Box
            val listState = rememberLazyListState()
            LaunchedEffect(filteredLogs.size) {
                if (filteredLogs.isNotEmpty()) {
                    listState.animateScrollToItem(filteredLogs.size - 1)
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF141418)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无匹配的运行日志，启动翻译后此处将实时显示每个阶段进度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { log ->
                            val color = when (log.level) {
                                LogLevel.ERROR -> Color(0xFFFF5252)
                                LogLevel.WARN -> Color(0xFFFFD740)
                                else -> when (log.tag) {
                                    "TRANSLATION", "BATCH" -> Color(0xFF69F0AE)
                                    "LLM_API" -> Color(0xFF40C4FF)
                                    "GLOSSARY" -> Color(0xFFE040FB)
                                    "QA_CHECK" -> Color(0xFFFFAB40)
                                    else -> Color(0xFFE0E0E0)
                                }
                            }
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        "[${log.formattedDate}]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                    Text(
                                        "[${log.tag}]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = color,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        log.message,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (!log.details.isNullOrBlank()) {
                                    Text(
                                        "  └ ${log.details}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color.LightGray.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(start = 24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationTab(
    book: BookEntity,
    editions: List<EditionEntity>,
    targetEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    providers: List<ApiProviderEntity>,
    viewModel: AppViewModel,
    onCreateEdition: () -> Unit
) {
    if (targetEdition == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请先创建一个目标翻译版本 (Edition)")
                Button(onClick = onCreateEdition) {
                    Text("+ 新建目标翻译版本")
                }
            }
        }
        return
    }

    val originalEdition = editions.firstOrNull { it.id == book.primaryEditionId }
        ?: editions.firstOrNull { it.type == EditionType.IMPORTED.name }
        ?: editions.firstOrNull()

    var selectedProviderId by rememberSaveable(project) { mutableStateOf(project?.providerId ?: providers.firstOrNull()?.id) }
    var selectedModel by rememberSaveable(project) { mutableStateOf(project?.modelName ?: providers.firstOrNull { it.id == selectedProviderId }?.selectedModel.orEmpty()) }
    var translationMode by rememberSaveable(project) { mutableStateOf(project?.translationMode ?: TranslationMode.FULL_BOOK.name) }
    var batchSize by rememberSaveable(project) { mutableStateOf(project?.maxBatchChapters ?: 1) }
    var rangeStartText by rememberSaveable(project) { mutableStateOf(project?.rangeStart?.toString() ?: "1") }
    var rangeEndText by rememberSaveable(project) { mutableStateOf(project?.rangeEnd?.toString() ?: "50") }
    var styleGuide by rememberSaveable(project) { mutableStateOf(project?.styleGuide ?: "保持文学韵味与专有名词一致性") }
    var autoGlossaryUpdate by rememberSaveable { mutableStateOf(true) }

    var isTestingProvider by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "配置当前译本任务 · ${targetEdition.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "源版本: ${originalEdition?.name ?: "原版"} (${originalEdition?.language ?: "Auto"}) -> 目标语言: ${targetEdition.language}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Provider & Model Selection
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. AI 供应商与模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    if (providers.isEmpty()) {
                        Text("暂无可用的 AI 供应商，请先在系统设置中添加 API Key。", color = MaterialTheme.colorScheme.error)
                    } else {
                        var expandedProvider by remember { mutableStateOf(false) }
                        val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.first()

                        ExposedDropdownMenuBox(
                            expanded = expandedProvider,
                            onExpandedChange = { expandedProvider = !expandedProvider }
                        ) {
                            OutlinedTextField(
                                value = "${activeProvider.name} (${activeProvider.providerType.displayName})",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("AI 供应商") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedProvider) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedProvider,
                                onDismissRequest = { expandedProvider = false }
                            ) {
                                providers.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text("${p.name} (${p.providerType.displayName})") },
                                        onClick = {
                                            selectedProviderId = p.id
                                            selectedModel = p.selectedModel
                                            expandedProvider = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = { selectedModel = it },
                            label = { Text("指定模型名称 (例如 gemini-2.5-flash, deepseek-chat)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isTestingProvider = true
                                    testResult = null
                                    viewModel.testProvider(activeProvider) { success, msg ->
                                        isTestingProvider = false
                                        testResult = if (success) "连接成功: $msg" else "连接失败: $msg"
                                    }
                                }
                            ) {
                                if (isTestingProvider) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.NetworkCheck, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("测试连接")
                            }

                            if (!testResult.isNullOrBlank()) {
                                Text(
                                    testResult!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testResult!!.startsWith("连接成功")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Batch & Mode Settings
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("2. 翻译模式与批处理规模", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    // Mode Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            TranslationMode.FULL_BOOK.name to "全书翻译",
                            TranslationMode.CHAPTER_RANGE.name to "指定范围",
                            TranslationMode.SEAMLESS.name to "无缝预翻译"
                        ).forEach { (mode, label) ->
                            val isSelected = translationMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { translationMode = mode },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (translationMode == TranslationMode.CHAPTER_RANGE.name) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = rangeStartText,
                                onValueChange = { rangeStartText = it.filter { c -> c.isDigit() } },
                                label = { Text("起始章节") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = rangeEndText,
                                onValueChange = { rangeEndText = it.filter { c -> c.isDigit() } },
                                label = { Text("结束章节") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Batch Size Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("单次批处理章节数:", style = MaterialTheme.typography.bodyMedium)
                            Text("$batchSize 章 / 批次", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = batchSize.toFloat(),
                            onValueChange = { batchSize = it.toInt() },
                            valueRange = 1f..5f,
                            steps = 3
                        )
                        Text(
                            "推荐 1~2 章以获得最高的文学翻译精度与专有名词一致性。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Auto Glossary Switch
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("随翻译进度自动更新名词表", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "在翻译过程中，智能识别新出现的人物名、地名、功法术语并自动存入名词库供后文参考。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoGlossaryUpdate, onCheckedChange = { autoGlossaryUpdate = it })
                    }
                }
            }
        }

        // Custom Prompt & Literary Persona
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. 风格与提示词定制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    // Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("仙侠修真", "西幻魔法", "轻小说", "严谨文学").forEach { preset ->
                            AssistChip(
                                onClick = {
                                    styleGuide = when (preset) {
                                        "仙侠修真" -> "保留东方仙侠玄幻风格，境界称号统一规范，对话自然流畅"
                                        "西幻魔法" -> "保留西幻史诗与魔法体系设定，地名人名音译典雅"
                                        "轻小说" -> "日系轻小说风格，语气生动鲜明，保留人物个性口吻"
                                        else -> "文学出版级翻译，语句通顺优美，严格保持专有名词一致"
                                    }
                                },
                                label = { Text(preset, fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = styleGuide,
                        onValueChange = { styleGuide = it },
                        label = { Text("文学风格指导与自定义提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                }
            }
        }

        // Save Button
        item {
            Button(
                onClick = {
                    if (originalEdition != null) {
                        val modeEnum = runCatching { TranslationMode.valueOf(translationMode) }.getOrDefault(TranslationMode.FULL_BOOK)
                        val start = rangeStartText.toIntOrNull()
                        val end = rangeEndText.toIntOrNull()

                        if (project != null) {
                            viewModel.updateTranslationProjectConfig(
                                projectId = project.id,
                                providerId = selectedProviderId,
                                modelName = selectedModel,
                                mode = modeEnum,
                                maxBatchChapters = batchSize,
                                rangeStart = start,
                                rangeEnd = end,
                                styleGuide = styleGuide
                            )
                        } else {
                            viewModel.configureEditionTranslation(
                                bookId = book.id,
                                sourceEditionId = originalEdition.id,
                                targetEditionId = targetEdition.id,
                                providerId = selectedProviderId,
                                modelName = selectedModel,
                                mode = modeEnum,
                                maxBatchChapters = batchSize,
                                rangeStart = start,
                                rangeEnd = end,
                                startImmediately = false
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun GlossaryManagementTab(
    book: BookEntity,
    project: TranslationProjectV2Entity?,
    lexicon: List<LexiconEntryEntity>,
    candidates: List<LexiconCandidateAggregateEntity>,
    storyMemory: List<StoryMemoryEntity>,
    onScanTerms: () -> Unit,
    onAddTerm: () -> Unit,
    viewModel: AppViewModel
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var selectedCandidateIds by remember(project?.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var editingCandidate by remember(project?.id) { mutableStateOf<LexiconCandidateReview?>(null) }
    var conflict by remember(project?.id) { mutableStateOf<CandidateImportConflict?>(null) }
    var pendingImport by remember(project?.id) { mutableStateOf<PendingCandidateImport?>(null) }

    val officialLexicon = lexicon.filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
    val candidateReviews = LexiconCandidateNoiseFilter.filterForReview(candidates)
        .map(LexiconCandidateVoting::review)
        .filter { review ->
            val matchCat = selectedCategory == "ALL" || review.winnerCategory.equals(selectedCategory, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                review.sourceTerm.contains(searchQuery, ignoreCase = true) ||
                review.winnerTargetTerm.contains(searchQuery, ignoreCase = true) ||
                review.winnerNotes.contains(searchQuery, ignoreCase = true)
            matchCat && matchQuery
        }

    LaunchedEffect(candidates) {
        val validIds = candidates.mapTo(mutableSetOf()) { it.id }
        selectedCandidateIds = selectedCandidateIds.intersect(validIds)
    }

    val filteredLexicon = remember(lexicon, searchQuery, selectedCategory) {
        officialLexicon.filter { entry ->
            val matchCat = selectedCategory == "ALL" || entry.category.equals(selectedCategory, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                entry.sourceTerm.contains(searchQuery, ignoreCase = true) ||
                entry.targetTerm.contains(searchQuery, ignoreCase = true) ||
                entry.notes.contains(searchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Smart Scanner Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "🔍 专有术语与人名智能扫描",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "AI 会完整扫描所选章节并生成待确认候选；只有人工确认后，术语才会进入翻译与 QA。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Button(onClick = onScanTerms) {
                        Text("一键扫描")
                    }
                }
            }
        }

        // Actions & Search Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索术语与人名...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = onAddTerm,
                    enabled = project != null
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加术语")
                }
            }
        }

        // Category Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to "全部 (${officialLexicon.size})",
                    "CHARACTER" to "人名",
                    "LOCATION" to "地名",
                    "SKILL" to "功法技能",
                    "LORE" to "势力/设定",
                    "ITEM" to "道具法宝",
                    "HONORIFIC" to "称谓头衔",
                    "CUSTOM" to "自定义"
                ).forEach { (cat, label) ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cat },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (candidateReviews.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI 候选审核 · ${candidateReviews.size}", fontWeight = FontWeight.Bold)
                                Text(
                                    "候选只保存证据，不会影响正式翻译；确认后才会进入术语约束。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            val batchableIds = candidateReviews
                                .filter { it.isHighConfidenceForBatch }
                                .map { it.id }
                                .filter { it in selectedCandidateIds }
                            TextButton(
                                onClick = {
                                    viewModel.confirmLexiconCandidatesBatch(batchableIds) { _, _ ->
                                        selectedCandidateIds = selectedCandidateIds - batchableIds.toSet()
                                    }
                                },
                                enabled = batchableIds.isNotEmpty()
                            ) {
                                Text("批量确认 (${batchableIds.size})")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectedCandidateIds = candidateReviews
                                    .filter { it.isHighConfidenceForBatch }
                                    .mapTo(mutableSetOf()) { it.id }
                            }) { Text("选择高一致候选") }
                            TextButton(onClick = { selectedCandidateIds = emptySet() }) { Text("清空选择") }
                        }
                    }
                }
            }
            items(candidateReviews, key = { "candidate_${it.id}" }) { review ->
                LexiconCandidateCard(
                    review = review,
                    selected = review.id in selectedCandidateIds,
                    onToggle = {
                        selectedCandidateIds = if (review.id in selectedCandidateIds) {
                            selectedCandidateIds - review.id
                        } else {
                            selectedCandidateIds + review.id
                        }
                    },
                    onConfirm = {
                        viewModel.confirmLexiconCandidate(review.id) { result ->
                            when (result) {
                                is CandidateImportResult.Conflict -> {
                                    pendingImport = PendingCandidateImport(
                                        targetTerm = review.winnerTargetTerm,
                                        category = review.winnerCategory,
                                        notes = review.winnerNotes
                                    )
                                    conflict = result.details
                                }
                                is CandidateImportResult.Failed -> viewModel.showMessage(result.message)
                                else -> Unit
                            }
                        }
                    },
                    onEdit = { editingCandidate = review },
                    onIgnore = { viewModel.ignoreLexiconCandidate(review.id) }
                )
            }
        }

        // Terms List
        if (filteredLexicon.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("暂无匹配的专有术语", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("点击上方【一键扫描】或【添加术语】快速建立专有名词库", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(filteredLexicon, key = { "lexicon_${it.id}" }) { entry ->
                LexiconEntryCard(
                    entry = entry,
                    onConfirm = { viewModel.confirmLexiconEntry(entry) },
                    onDelete = { viewModel.deleteLexiconEntry(entry.id) }
                )
            }
        }
    }

    if (editingCandidate != null) {
        val candidate = editingCandidate!!
        CandidateEditDialog(
            review = candidate,
            onDismiss = { editingCandidate = null },
            onConfirm = { target, category, notes ->
                viewModel.confirmLexiconCandidate(
                    candidateId = candidate.id,
                    targetTerm = target,
                    category = category,
                    notes = notes
                ) { result ->
                    when (result) {
                        is CandidateImportResult.Conflict -> {
                            pendingImport = PendingCandidateImport(target, category, notes)
                            conflict = result.details
                            editingCandidate = null
                        }
                        is CandidateImportResult.Failed -> viewModel.showMessage(result.message)
                        is CandidateImportResult.Imported -> {
                            editingCandidate = null
                            pendingImport = null
                        }
                        is CandidateImportResult.Skipped -> {
                            editingCandidate = null
                            pendingImport = null
                        }
                    }
                }
            }
        )
    }

    if (conflict != null) {
        val details = conflict!!
        val pending = pendingImport ?: PendingCandidateImport(
            details.candidate.winnerTargetTerm,
            details.candidate.winnerCategory,
            details.candidate.winnerNotes
        )
        AlertDialog(
            onDismissRequest = { conflict = null; pendingImport = null },
            title = { Text("正式术语冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原文：${details.candidate.sourceTerm}", fontWeight = FontWeight.Bold)
                    Text("现有正式译名：${details.existing.targetTerm} (${details.existing.category})")
                    Text("候选译名：${pending.targetTerm} (${pending.category})")
                    Text(
                        "请选择保留现有正式术语，或用候选内容覆盖；不会自动替你选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.confirmLexiconCandidate(
                        candidateId = details.candidate.id,
                        targetTerm = pending.targetTerm,
                        category = pending.category,
                        notes = pending.notes,
                        overwrite = true
                    ) {
                        conflict = null
                        pendingImport = null
                    }
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.skipLexiconCandidate(details.candidate.id) {
                            conflict = null
                            pendingImport = null
                        }
                    }) { Text("Skip") }
                    TextButton(onClick = { conflict = null; pendingImport = null }) { Text("取消") }
                }
            }
        )
    }
}

private data class PendingCandidateImport(
    val targetTerm: String,
    val category: String,
    val notes: String
)

@Composable
private fun LexiconCandidateCard(
    review: LexiconCandidateReview,
    selected: Boolean,
    onToggle: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onIgnore: () -> Unit
) {
    val targetVotes = review.targetVotes.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { LexiconCandidateVoting.normalizeSourceTerm(it.key) }
                .thenBy { it.key }
        )
    val categoryVotes = review.categoryVotes.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (review.hasTargetConflict || review.hasCategoryConflict) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
        } else null
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(review.sourceTerm, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.ArrowForward, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(review.winnerTargetTerm, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "${review.winnerCategory} · 出现 ${review.observationCount} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onConfirm) {
                    Icon(Icons.Default.Check, "确认", Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("确认")
                }
            }
            if (review.hasTargetConflict || review.hasCategoryConflict) {
                Text(
                    "存在译名冲突${if (review.hasCategoryConflict) " / 类别冲突" else ""}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (targetVotes.isNotEmpty()) {
                Text(
                    targetVotes.joinToString(" · ") { "${it.key} ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (categoryVotes.isNotEmpty()) {
                Text(
                    "类别票：" + categoryVotes.joinToString(" · ") { "${it.key} ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "首次：第 ${review.aggregate.firstSeenChapterIndex.takeIf { it > 0 } ?: "?"} 章 · 最近：第 ${review.aggregate.lastSeenChapterIndex.takeIf { it > 0 } ?: "?"} 章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (review.winnerNotes.isNotBlank()) {
                Text(review.winnerNotes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑后确认") }
                TextButton(onClick = onIgnore) { Text("忽略", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateEditDialog(
    review: LexiconCandidateReview,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var target by remember(review.id) { mutableStateOf(review.winnerTargetTerm) }
    var category by remember(review.id) { mutableStateOf(review.winnerCategory) }
    var notes by remember(review.id) { mutableStateOf(review.winnerNotes) }
    var expanded by remember(review.id) { mutableStateOf(false) }
    val categories = (LexiconCandidateVoting.aiCategories + "CUSTOM").sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑候选 · ${review.sourceTerm}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("规范译名") }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类别") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { category = option; expanded = false })
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("备注") }, maxLines = 3)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(target.trim(), category, notes.trim()) }, enabled = target.isNotBlank() && category.isNotBlank()) {
                Text("确认并导入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LexiconEntryCard(
    entry: LexiconEntryEntity,
    onConfirm: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        entry.sourceTerm,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Icon(Icons.Default.ArrowForward, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        entry.targetTerm,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            entry.category,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                    if (entry.source == LexiconSource.AI.name) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                if (entry.reviewStatus == ReviewStatus.CANDIDATE.name) "🤖 待确认" else "🤖 AI 术语",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp
                            )
                        }
                    }
                    if (entry.notes.isNotBlank()) {
                        Text(
                            entry.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (entry.reviewStatus == ReviewStatus.CANDIDATE.name) {
                TextButton(onClick = onConfirm) {
                    Icon(Icons.Default.Check, "确认术语", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("确认")
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsAndHistoryTab(
    book: BookEntity,
    project: TranslationProjectV2Entity?,
    allRuns: List<PlatformTranslationRunEntity>,
    systemLogs: List<SystemLogEntry>,
    viewModel: AppViewModel,
    debugModeEnabled: Boolean
) {
    var selectedSubTab by rememberSaveable { mutableStateOf(0) }
    var selectedRunId by rememberSaveable(book.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(allRuns) {
        if (selectedRunId == null || allRuns.none { it.id == selectedRunId }) selectedRunId = allRuns.firstOrNull()?.id
    }
    val selectedRun = allRuns.firstOrNull { it.id == selectedRunId }
    val requestLogs by remember(selectedRunId) {
        selectedRunId?.let(viewModel::observeRequestLogs) ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val systemTabIndex = if (debugModeEnabled) 3 else 2
    LaunchedEffect(debugModeEnabled) {
        if (!debugModeEnabled && selectedSubTab > 2) selectedSubTab = 2
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selectedSubTab, edgePadding = 8.dp) {
            Tab(selected = selectedSubTab == 0, onClick = { selectedSubTab = 0 }, text = { Text("任务执行历史 (${allRuns.size})") })
            Tab(selected = selectedSubTab == 1, onClick = { selectedSubTab = 1 }, text = { Text("模型调用明细 (${requestLogs.size})") })
            if (debugModeEnabled) {
                Tab(selected = selectedSubTab == 2, onClick = { selectedSubTab = 2 }, text = { Text("Debug 诊断") })
            }
            Tab(selected = selectedSubTab == systemTabIndex, onClick = { selectedSubTab = systemTabIndex }, text = { Text("系统全局日志") })
        }

        when (selectedSubTab) {
            0 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (allRuns.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无历史执行任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(allRuns, key = { "run_${it.id}" }) { run ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedRunId = run.id
                                    selectedSubTab = if (debugModeEnabled) 2 else 1
                                },
                                border = if (run.id == selectedRunId) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "任务 #${run.id} · ${run.providerName}/${run.modelName}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (run.state == "COMPLETED") Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                run.state,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Text(
                                        "完成 ${run.completedChapters} 章 · 消耗 ${TokenCalculator.formatTokenCount(run.promptTokens + run.completionTokens)} Tokens · 费用 $${String.format(java.util.Locale.US, "%.4f", run.totalCost)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!run.lastError.isNullOrBlank()) {
                                        Text(
                                            "异常: ${run.lastError}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (requestLogs.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无模型调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(requestLogs, key = { "req_${it.id}" }) { log ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(log.operation, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            if (log.isSuccess) "成功 (${log.durationMs}ms)" else "失败",
                                            color = if (log.isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        "Tokens: Prompt ${log.promptTokens}, Completion ${log.completionTokens} · 预估费用: $${String.format(java.util.Locale.US, "%.5f", log.estimatedCost)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!log.errorMessage.isNullOrBlank()) {
                                        Text(log.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> if (debugModeEnabled) {
                DebugDiagnosticsTab(
                    run = selectedRun,
                    requestLogs = requestLogs,
                    systemLogs = systemLogs.filter { it.projectId == project?.id },
                    viewModel = viewModel
                )
            } else {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    LiveProcessLogCard(projectId = project?.id, systemLogs = systemLogs)
                }
            }
            3 -> {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    LiveProcessLogCard(projectId = project?.id, systemLogs = systemLogs)
                }
            }
        }
    }
}

@Composable
private fun DebugDiagnosticsTab(
    run: PlatformTranslationRunEntity?,
    requestLogs: List<PlatformRequestLogSummary>,
    systemLogs: List<SystemLogEntry>,
    viewModel: AppViewModel
) {
    var expandedLogId by rememberSaveable(run?.id) { mutableStateOf<Long?>(null) }
    val runFlow = remember(run?.id, systemLogs) {
        if (run == null) emptyList() else systemLogs
            .filter { it.timestamp >= run.createdAt && it.timestamp <= run.updatedAt + 60_000 }
            .sortedBy { it.timestamp }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Debug 仅记录开启后的任务", fontWeight = FontWeight.Bold)
                    Text("内容可能包含小说正文、提示词和模型完整输出，请勿在公开场合直接分享。", style = MaterialTheme.typography.bodySmall)
                    if (run != null) {
                        Text("当前任务 #${run.id} · ${run.state} · ${run.providerName}/${run.modelName}", style = MaterialTheme.typography.bodySmall)
                        if (!run.lastError.isNullOrBlank()) Text("任务失败原因：${run.lastError}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (runFlow.isNotEmpty()) {
            item { Text("完整执行流程", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(runFlow, key = { "flow_${it.id}" }) { entry ->
                Text(
                    "${entry.formattedTime}  [${entry.tag}] ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        item { Text("API 交互与校验", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        if (requestLogs.isEmpty()) {
            item { Text("此任务没有 Debug API 内容；可能是在开启 Debug 模式之前执行的。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(requestLogs, key = { "debug_req_${it.id}" }) { log ->
                val expanded = expandedLogId == log.id
                val detail by produceState<PlatformRequestLogEntity?>(initialValue = null, log.id, expanded) {
                    if (expanded) value = viewModel.getRequestLogDetail(log.id)
                }
                ElevatedCard(Modifier.fillMaxWidth().clickable { expandedLogId = if (expanded) null else log.id }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log.operation, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (log.isSuccess) "成功" else "失败", color = if (log.isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                        }
                        Text("耗时 ${log.durationMs}ms · 尝试 ${log.attemptCount} 次 · ${log.promptTokens + log.completionTokens} Tokens", style = MaterialTheme.typography.bodySmall)
                        if (!log.isSuccess) {
                            Text("失败原因：${debugFailureExplanation(log)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (expanded) {
                            DebugTextBlock("重试轨迹", detail?.attemptTrace)
                            DebugTextBlock("System Prompt", detail?.systemPrompt)
                            DebugTextBlock("User Prompt", detail?.userPrompt)
                            DebugTextBlock("模型响应", detail?.responseText)
                            DebugTextBlock("错误原文", log.errorMessage)
                        } else {
                            Text("点击展开完整请求与响应", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTextBlock(title: String, value: String?) {
    if (value.isNullOrBlank()) return
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            value,
            Modifier.fillMaxWidth().padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun debugFailureExplanation(log: PlatformRequestLogSummary): String = when (log.errorCategory) {
    LlmErrorCategory.AUTHENTICATION.name -> "API 身份认证失败，请检查 Key、端点和请求协议。${log.errorMessage.orEmpty()}"
    LlmErrorCategory.RATE_LIMIT.name -> "供应商限流或额度不足，重试次数已耗尽。${log.errorMessage.orEmpty()}"
    LlmErrorCategory.CONTEXT_OVERFLOW.name -> "输入上下文超过模型限制，需要缩小章节批次。${log.errorMessage.orEmpty()}"
    LlmErrorCategory.TRUNCATED_OUTPUT.name -> "模型输出达到 Token 上限，续写或重分块未能恢复完整结果。${log.errorMessage.orEmpty()}"
    LlmErrorCategory.QUALITY_REJECTED.name -> "QA 校验发现缺段、图片标记变化、术语不一致或异常内容：${log.errorMessage.orEmpty()}"
    LlmErrorCategory.NETWORK_UNAVAILABLE.name, LlmErrorCategory.TIMEOUT.name -> "网络连接或请求超时，自动重试后仍未成功。${log.errorMessage.orEmpty()}"
    else -> log.errorMessage ?: log.errorCategory ?: "未返回更具体的失败信息"
}

// Dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEditionDialog(
    book: BookEntity,
    sourceEdition: EditionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, String) -> Unit
) {
    var targetLanguage by remember { mutableStateOf("中文 (简体)") }
    var editionName by remember { mutableStateOf("中文译本 (AI)") }
    var expandedLang by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建译本版本 (Edition)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "为《${book.title}》创建一个新的翻译目标版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = expandedLang,
                    onExpandedChange = { expandedLang = !expandedLang }
                ) {
                    OutlinedTextField(
                        value = targetLanguage,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("目标语言") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedLang) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedLang,
                        onDismissRequest = { expandedLang = false }
                    ) {
                        TARGET_LANGUAGE_OPTIONS.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.displayName) },
                                onClick = {
                                    targetLanguage = opt.displayName
                                    editionName = "${opt.defaultNameZh} (AI)"
                                    expandedLang = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editionName,
                    onValueChange = { editionName = it },
                    label = { Text("版本名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (sourceEdition != null && targetLanguage.isNotBlank()) {
                        onConfirm(sourceEdition.id, targetLanguage, editionName)
                    }
                }
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermScannerDialog(
    book: BookEntity,
    sourceEdition: EditionEntity?,
    totalChapters: Int,
    providers: List<ApiProviderEntity>,
    targetProjectId: Long?,
    onDismiss: () -> Unit,
    onStartScan: (Int, Int, ApiProviderEntity, String, (String) -> Unit, (Int) -> Unit) -> Unit
) {
    var startChText by remember { mutableStateOf("1") }
    var endChText by remember { mutableStateOf(minOf(10, totalChapters).toString()) }
    var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.id) }
    var targetLanguage by remember { mutableStateOf("中文") }

    var isScanning by remember { mutableStateOf(false) }
    var scanStatusMessage by remember { mutableStateOf("") }
    var scanCompletedCount by remember { mutableStateOf<Int?>(null) }

    val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()

    AlertDialog(
        onDismissRequest = { if (!isScanning) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Spellcheck, null, tint = MaterialTheme.colorScheme.primary)
                Text("专有术语与人名智能扫描")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isScanning) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(scanStatusMessage, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                } else if (scanCompletedCount != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(48.dp))
                        Text(
                            "扫描完成！共形成 $scanCompletedCount 个待审核候选。",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "候选已保存观察证据；请在审核区确认后，才会进入正式翻译约束与 QA。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    if (targetProjectId == null) {
                        Text(
                            "请先创建并选择一个翻译版本，扫描结果需要写入对应工程的术语库。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "AI 将分析指定章节正文，提取小说中的角色人名、地名、功法与专有名词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = startChText,
                            onValueChange = { startChText = it.filter { c -> c.isDigit() } },
                            label = { Text("起始章节") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endChText,
                            onValueChange = { endChText = it.filter { c -> c.isDigit() } },
                            label = { Text("结束章节") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (providers.isNotEmpty()) {
                        var expandedProvider by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expandedProvider,
                            onExpandedChange = { expandedProvider = !expandedProvider }
                        ) {
                            OutlinedTextField(
                                value = activeProvider?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("分析模型") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedProvider) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedProvider,
                                onDismissRequest = { expandedProvider = false }
                            ) {
                                providers.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            selectedProviderId = p.id
                                            expandedProvider = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (scanCompletedCount != null) {
                Button(onClick = onDismiss) { Text("完成") }
            } else if (!isScanning) {
                Button(
                    onClick = {
                        val start = startChText.toIntOrNull() ?: 1
                        val end = endChText.toIntOrNull() ?: totalChapters
                        if (activeProvider != null) {
                            isScanning = true
                            scanStatusMessage = "正在初始化扫描任务..."
                            onStartScan(
                                start,
                                end,
                                activeProvider,
                                targetLanguage,
                                { scanStatusMessage = it },
                                { count ->
                                    isScanning = false
                                    scanCompletedCount = count
                                }
                            )
                        }
                    },
                    enabled = activeProvider != null && targetProjectId != null && totalChapters > 0
                ) {
                    Text("开始扫描")
                }
            }
        },
        dismissButton = {
            if (!isScanning && scanCompletedCount == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLexiconEntryDialog(
    projectId: Long,
    onDismiss: () -> Unit,
    onConfirm: (LexiconEntryEntity) -> Unit
) {
    var sourceTerm by remember { mutableStateOf("") }
    var targetTerm by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("CHARACTER") }
    var notes by remember { mutableStateOf("") }
    var expandedCat by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加专有术语") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = sourceTerm,
                    onValueChange = { sourceTerm = it },
                    label = { Text("原文术语/人名 (如 林渊)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetTerm,
                    onValueChange = { targetTerm = it },
                    label = { Text("规范译名 (如 Lin Yuan)") },
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = expandedCat,
                    onExpandedChange = { expandedCat = !expandedCat }
                ) {
                    OutlinedTextField(
                        value = when (category) {
                            "CHARACTER" -> "👤 角色人名"
                            "LOCATION" -> "🗺️ 地理地名"
                            "SKILL" -> "⚔️ 功法技能"
                            "LORE" -> "🏛️ 宗门/设定"
                            "ITEM" -> "📜 道具法宝"
                            "HONORIFIC" -> "🎖️ 称谓头衔"
                            else -> "自定义"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类别") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedCat) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCat,
                        onDismissRequest = { expandedCat = false }
                    ) {
                        listOf(
                            "CHARACTER" to "👤 角色人名",
                            "LOCATION" to "🗺️ 地理地名",
                            "SKILL" to "⚔️ 功法技能",
                            "LORE" to "🏛️ 宗门/设定",
                            "ITEM" to "📜 道具法宝",
                            "HONORIFIC" to "🎖️ 称谓头衔",
                            "CUSTOM" to "自定义"
                        ).forEach { (cat, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    category = cat
                                    expandedCat = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注说明 (可选)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (sourceTerm.isNotBlank() && targetTerm.isNotBlank()) {
                        onConfirm(
                            LexiconEntryEntity(
                                translationProjectId = projectId,
                                sourceTerm = sourceTerm.trim(),
                                targetTerm = targetTerm.trim(),
                                category = category,
                                notes = notes.trim(),
                                source = LexiconSource.MANUAL.name,
                                reviewStatus = ReviewStatus.CONFIRMED.name
                            )
                        )
                    }
                },
                enabled = sourceTerm.isNotBlank() && targetTerm.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
