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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.logger.LogLevel
import com.breakyuna.noveltranslator.core.logger.SystemLogEntry
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.core.translation.TranslationProtocol
import com.breakyuna.noveltranslator.core.translation.TranslationScopePlanner
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.components.BookCoverThumb
import com.breakyuna.noveltranslator.ui.components.TARGET_LANGUAGE_OPTIONS
import com.breakyuna.noveltranslator.ui.components.apple.AppCard
import com.breakyuna.noveltranslator.ui.components.apple.AppIconBubble
import com.breakyuna.noveltranslator.ui.components.apple.AppSectionHeader
import com.breakyuna.noveltranslator.ui.i18n.agentEntryStrings
import com.breakyuna.noveltranslator.ui.i18n.engineConfigStrings
import com.breakyuna.noveltranslator.ui.i18n.workbenchUiStrings
import com.breakyuna.noveltranslator.ui.i18n.WorkbenchUiStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.flowOf

enum class WorkbenchTab(val icon: ImageVector) {
    TASKS(Icons.Default.Dashboard),
    GLOSSARY(Icons.Default.Spellcheck),
    LOGS(Icons.Default.Terminal)
}

@Composable
private fun WorkbenchTab.label(wb: WorkbenchUiStrings): String = when (this) {
    WorkbenchTab.TASKS -> wb.tabTasks
    WorkbenchTab.GLOSSARY -> wb.tabGlossary
    WorkbenchTab.LOGS -> wb.tabLogs
}

@Composable
private fun WorkbenchTab.description(wb: WorkbenchUiStrings): String = when (this) {
    WorkbenchTab.TASKS -> wb.tabTasksDescription
    WorkbenchTab.GLOSSARY -> wb.tabGlossaryDescription
    WorkbenchTab.LOGS -> wb.tabLogsDescription
}

@Composable
private fun stateLabel(state: String, wb: WorkbenchUiStrings): String = when (state) {
    "RUNNING" -> wb.stateRunning
    "PAUSED" -> wb.statePaused
    "QUEUED" -> wb.stateQueued
    "COMPLETED" -> wb.stateCompleted
    "COMPLETED_WITH_WARNINGS" -> wb.stateCompletedWithWarnings
    "FAILED" -> wb.stateFailed
    "COMPLETED_WITH_ERRORS" -> wb.stateCompletedWithErrors
    else -> wb.stateIdle
}

@Composable
private fun stateContainerColor(state: String): Color = when (state) {
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "PAUSED" -> MaterialTheme.colorScheme.tertiary
    // A queued worker holds no provider slot yet; show it as waiting rather than running.
    "QUEUED" -> MaterialTheme.colorScheme.secondaryContainer
    "COMPLETED" -> LocalAppColors.current.success
    "COMPLETED_WITH_WARNINGS" -> LocalAppColors.current.warning
    "FAILED", "COMPLETED_WITH_ERRORS" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun stateContentColor(state: String): Color = when (state) {
    "RUNNING", "COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED", "COMPLETED_WITH_ERRORS" -> Color.White
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun stateBadge(state: String, wb: WorkbenchUiStrings, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = stateContainerColor(state)
    ) {
        Text(
            text = stateLabel(state, wb),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = stateContentColor(state),
            maxLines = 1
        )
    }
}

@Composable
private fun statusTint(state: String): Color = when (state) {
    "FAILED", "ERROR", "COMPLETED_WITH_ERRORS" -> MaterialTheme.colorScheme.error
    "COMPLETED_WITH_WARNINGS", "PARTIAL" -> LocalAppColors.current.warning
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "COMPLETED", "SUCCESS" -> LocalAppColors.current.success
    "PAUSED" -> LocalAppColors.current.warning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookWorkbenchDetailScreen(
    bookId: Long,
    viewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onOpenReader: (Long, Long?) -> Unit,
    onOpenAgent: () -> Unit = {}
) {
    val wb = workbenchUiStrings()
    val agentEntry = agentEntryStrings()
    val appColors = LocalAppColors.current
    val windowSize = rememberWindowSize()
    val useWideWorkbench = windowSize.isExpanded &&
        windowSize.screenWidthDp > windowSize.screenHeightDp

    val book by remember(bookId) { viewModel.bookPlatformRepo.observeBook(bookId) }
        .collectAsState(initial = null)
    val editions by remember(bookId) { viewModel.bookPlatformRepo.observeEditions(bookId) }
        .collectAsState(initial = emptyList())
    val chapters by remember(bookId) { viewModel.bookPlatformRepo.observeChapters(bookId) }
        .collectAsState(initial = emptyList())
    val readerProgress by remember(bookId) { viewModel.bookPlatformRepo.observeProgress(bookId) }
        .collectAsState(initial = null)
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
        val selectedEditionStillExists = selectedTargetEditionId?.let { selectedId ->
            editions.any { it.id == selectedId }
        } == true
        if (!selectedEditionStillExists && editions.isNotEmpty()) {
            val transEdition = editions.firstOrNull { it.type == EditionType.AI_TRANSLATION.name }
            val preferred = editions.firstOrNull { it.id == book?.preferredReadingEditionId }
            selectedTargetEditionId = preferred?.id ?: transEdition?.id ?: editions.last().id
        }
    }

    val currentTargetEdition = editions.firstOrNull { it.id == selectedTargetEditionId }
    val currentProject = translationProjects
        .filter { it.targetEditionId == selectedTargetEditionId }
        .maxWithOrNull(compareBy<TranslationProjectV2Entity> { it.updatedAt }.thenBy { it.id })
    val taskScopeChapters = remember(chapters, currentProject, readerProgress?.logicalChapterId) {
        currentProject?.let { project ->
            TranslationScopePlanner.select(
                chapters = chapters,
                mode = project.translationMode,
                rangeStart = project.rangeStart,
                rangeEnd = project.rangeEnd,
                currentChapterId = readerProgress?.logicalChapterId,
                seamlessAheadChapters = project.seamlessAheadChapters
            )
        } ?: chapters
    }
    val promptProfile by remember(currentProject?.id) {
        if (currentProject != null) viewModel.observePromptProfile(currentProject.id)
        else flowOf(null)
    }.collectAsState(initial = null)

    val activeRuns by remember(currentProject?.id) {
        if (currentProject != null) viewModel.observeRunsByProject(currentProject.id)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val latestRun = activeRuns
        .maxWithOrNull(compareBy<PlatformTranslationRunEntity> { it.createdAt }.thenBy { it.id })
        ?: allRuns
            .filter { it.translationProjectId == currentProject?.id }
            .maxWithOrNull(compareBy<PlatformTranslationRunEntity> { it.createdAt }.thenBy { it.id })

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

    var showCreateEditionDialog by rememberSaveable { mutableStateOf(false) }
    var showTermScannerDialog by rememberSaveable { mutableStateOf(false) }
    var showAddTermDialog by rememberSaveable { mutableStateOf(false) }
    var editionToDelete by remember(bookId) { mutableStateOf<EditionEntity?>(null) }
    var chapterActionTarget by remember { mutableStateOf<LogicalChapterEntity?>(null) }
    var aiSplitProvider by remember(bookId) { mutableStateOf<ApiProviderEntity?>(null) }
    var aiSplitPreview by remember(bookId) { mutableStateOf<List<ChapterSplitPreview>?>(null) }
    var aiSplitRunning by remember(bookId) { mutableStateOf(false) }

    val currentBook = book
    if (currentBook == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(wb.workbenchTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, wb.back)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    // The reader route accepts a logical chapter id, not an Edition id. Persist the selected
    // Edition first, then open the reader at the saved position so the workbench selection is
    // honored on both compact and wide layouts.
    val openSelectedReader = {
        val selectedEditionId = currentTargetEdition?.id
        if (selectedEditionId == null) {
            onOpenReader(currentBook.id, null)
        } else {
            viewModel.selectReadingEdition(currentBook.id, selectedEditionId) {
                onOpenReader(currentBook.id, null)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${wb.workbenchTitle} · ${currentBook.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            wb.headerSummary(chapters.size, editions.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, wb.back)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAgent) {
                        Icon(Icons.Outlined.SmartToy, agentEntry.entryOpenDescription)
                    }
                    IconButton(onClick = openSelectedReader) {
                        Icon(Icons.Default.MenuBook, wb.readTranslation)
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
                                totalTaskChapters = taskScopeChapters.size,
                                targetEdition = currentTargetEdition,
                                project = currentProject,
                                promptProfile = promptProfile,
                                run = latestRun,
                                systemLogs = systemLogs,
                                providers = allProviders,
                                viewModel = viewModel,
                                wb = wb,
                                appColors = appColors,
                                onCreateEdition = { showCreateEditionDialog = true },
                                onScanTerms = { showTermScannerDialog = true },
                                onOpenReader = openSelectedReader,
                                onRequestAiSplit = { aiSplitProvider = it },
                                onSelectChapterAction = { chapterActionTarget = it }
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
                                viewModel = viewModel,
                                wb = wb,
                                appColors = appColors
                            )
                        }
                        WorkbenchTab.LOGS -> {
                            LogsAndHistoryTab(
                                book = currentBook,
                                project = currentProject,
                                allRuns = allRuns,
                                systemLogs = systemLogs,
                                viewModel = viewModel,
                                debugModeEnabled = debugModeEnabled,
                                wb = wb,
                                appColors = appColors
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
                    promptProfile = promptProfile,
                    providers = allProviders,
                    run = latestRun,
                    selectedTab = selectedTab,
                    chapterCount = taskScopeChapters.size,
                    lexiconCount = projectLexicon.count { it.reviewStatus == ReviewStatus.CONFIRMED.name },
                    onSelectTab = { selectedTab = it },
                    onSelectEdition = { selectedTargetEditionId = it },
                    onCreateEdition = { showCreateEditionDialog = true },
                    onDeleteEdition = { editionToDelete = it },
                    onScanTerms = { showTermScannerDialog = true },
                    onOpenReader = openSelectedReader,
                    viewModel = viewModel,
                    wb = wb,
                    appColors = appColors
                )
                VerticalDivider()
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WideWorkspaceHeader(
                        tab = selectedTab,
                        edition = currentTargetEdition,
                        project = currentProject,
                        run = latestRun,
                        wb = wb
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
                WorkbenchHeroSummary(
                    book = currentBook,
                    editions = editions,
                    selectedEdition = currentTargetEdition,
                    project = currentProject,
                    run = latestRun,
                    promptProfile = promptProfile,
                    providers = allProviders,
                    viewModel = viewModel,
                    wb = wb,
                    appColors = appColors,
                    onSelectEdition = { selectedTargetEditionId = it },
                    onCreateEdition = { showCreateEditionDialog = true },
                    onDeleteEdition = { editionToDelete = it }
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
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(tab.icon, null, modifier = Modifier.size(16.dp))
                                    Text(tab.label(wb), maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
            wb = wb,
            onDismiss = { showCreateEditionDialog = false },
            onConfirm = { sourceId, targetLang, name ->
                viewModel.createTranslationEdition(currentBook.id, sourceId, targetLang, name) { newEditionId ->
                    selectedTargetEditionId = newEditionId
                    showCreateEditionDialog = false
                    selectedTab = WorkbenchTab.TASKS
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
            wb = wb,
            onDismiss = { showTermScannerDialog = false },
            onCancelScan = { viewModel.cancelGlossaryScan(currentBook.id) },
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
                        onComplete = { result ->
                            onComplete(result)
                        }
                    )
                }
            }
        )
    }

    if (showAddTermDialog && currentProject != null) {
        AddLexiconEntryDialog(
            projectId = currentProject.id,
            wb = wb,
            onDismiss = { showAddTermDialog = false },
            onConfirm = { entry ->
                viewModel.upsertLexiconEntry(entry)
                showAddTermDialog = false
            }
        )
    }

    if (aiSplitProvider != null) {
        val provider = aiSplitProvider!!
        AlertDialog(
            onDismissRequest = { aiSplitProvider = null },
            shape = DialogShape,
            title = { Text(wb.dialogs.aiSplitIntroTitle) },
            text = {
                Text(wb.dialogs.aiSplitIntroBody)
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiSplitProvider = null
                        aiSplitPreview = null
                        aiSplitRunning = true
                        viewModel.previewAgentBookChapterSplit(
                            bookId = currentBook.id,
                            provider = provider,
                            onPreview = { preview -> aiSplitPreview = preview },
                            onFinished = { aiSplitRunning = false }
                        )
                    }
                ) { Text(wb.dialogs.aiSplitStart) }
            },
            dismissButton = { TextButton(onClick = { aiSplitProvider = null }) { Text(wb.dialogs.cancel) } }
        )
    }

    if (aiSplitRunning) {
        AlertDialog(
            onDismissRequest = {},
            shape = DialogShape,
            title = { Text(wb.dialogs.aiSplitRunningTitle) },
            text = { Text(wb.dialogs.aiSplitRunningBody) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelAgentBookChapterSplit(currentBook.id)
                    aiSplitRunning = false
                }) { Text(wb.dialogs.aiSplitStop) }
            }
        )
    }

    aiSplitPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                aiSplitPreview = null
                viewModel.discardAgentBookChapterSplit(currentBook.id)
            },
            shape = DialogShape,
            title = { Text(wb.dialogs.aiSplitPreviewTitle(preview.size)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(preview.take(80), key = { it.index }) { chapter ->
                        Text(
                            "${chapter.index}. ${chapter.title}",
                            modifier = Modifier.padding(vertical = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (preview.size > 80) {
                        item { Text(wb.dialogs.aiSplitPreviewMore(preview.size - 80), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiSplitPreview = null
                        viewModel.applyAgentBookChapterSplit(currentBook.id)
                    }
                ) { Text(wb.dialogs.aiSplitApply) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        aiSplitPreview = null
                        viewModel.discardAgentBookChapterSplit(currentBook.id)
                    }
                ) { Text(wb.dialogs.aiSplitDiscard) }
            }
        )
    }

    editionToDelete?.let { edition ->
        AlertDialog(
            onDismissRequest = { editionToDelete = null },
            shape = DialogShape,
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(wb.dialogs.deleteEditionTitle) },
            text = {
                Text(wb.dialogs.deleteEditionMessage(edition.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = edition
                        editionToDelete = null
                        viewModel.deleteEdition(currentBook.id, toDelete.id) {
                            if (selectedTargetEditionId == toDelete.id) {
                                val remaining = editions.filter { it.id != toDelete.id }
                                selectedTargetEditionId = remaining.firstOrNull { it.type == EditionType.AI_TRANSLATION.name }?.id
                                    ?: remaining.firstOrNull()?.id
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(wb.dialogs.confirmDelete)
                }
            },
            dismissButton = {
                TextButton(onClick = { editionToDelete = null }) {
                    Text(wb.dialogs.cancel)
                }
            }
        )
    }

    if (chapterActionTarget != null && currentTargetEdition != null) {
        val targetCh = chapterActionTarget!!
        AlertDialog(
            onDismissRequest = { chapterActionTarget = null },
            shape = DialogShape,
            title = { Text(wb.dialogs.chapterActionTitle(targetCh.canonicalTitle)) },
            text = {
                Text(wb.dialogs.chapterActionBody(targetCh.chapterIndex))
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val selectedEditionId = currentTargetEdition.id
                            val chapterId = targetCh.id
                            chapterActionTarget = null
                            viewModel.selectReadingEdition(currentBook.id, selectedEditionId) {
                                onOpenReader(currentBook.id, chapterId)
                            }
                        }
                    ) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(wb.dialogs.previewChapter)
                    }
                    Button(
                        onClick = {
                            viewModel.retranslateChapter(
                                editionId = currentTargetEdition.id,
                                logicalChapterId = targetCh.id,
                                projectId = currentProject?.id
                            )
                            chapterActionTarget = null
                        }
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(wb.dialogs.retranslateChapter)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { chapterActionTarget = null }) {
                    Text(wb.dialogs.cancel)
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
    promptProfile: PromptProfileEntity?,
    run: PlatformTranslationRunEntity?,
    providers: List<ApiProviderEntity>,
    selectedTab: WorkbenchTab,
    chapterCount: Int,
    lexiconCount: Int,
    onSelectTab: (WorkbenchTab) -> Unit,
    onSelectEdition: (Long) -> Unit,
    onCreateEdition: () -> Unit,
    onDeleteEdition: (EditionEntity) -> Unit,
    onScanTerms: () -> Unit,
    onOpenReader: () -> Unit,
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    appColors: AppColors
) {
    var editionMenuExpanded by remember { mutableStateOf(false) }
    val currentState = effectiveTranslationState(project, run)
    val completedChapters = if (currentState in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_WARNINGS")) {
        chapterCount
    } else {
        run?.completedChapters ?: 0
    }
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
                    BookCoverThumb(
                        title = book.title,
                        coverPath = book.coverPath,
                        width = 54.dp,
                        height = 76.dp
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            book.author.ifBlank { wb.unknownAuthor },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            wb.chaptersEditionsCount(chapterCount, editions.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { editionMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Layers, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(wb.currentEdition, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    selectedEdition?.name ?: wb.selectTargetEdition,
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
                        onDismissRequest = { editionMenuExpanded = false },
                        modifier = Modifier.widthIn(min = 260.dp, max = 340.dp)
                    ) {
                        Text(
                            text = wb.editionList(editions.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        HorizontalDivider()

                        editions.forEach { edition ->
                            val isSelected = edition.id == selectedEdition?.id
                            val isImported = edition.type == EditionType.IMPORTED.name
                            val isDeletable = !isImported

                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                edition.name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = if (isImported) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    if (isImported) wb.importedEdition else wb.aiEdition,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    color = if (isImported) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                        null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = if (isDeletable) {
                                    {
                                        IconButton(
                                            onClick = {
                                                editionMenuExpanded = false
                                                onDeleteEdition(edition)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = wb.deleteEdition,
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else null,
                                onClick = {
                                    onSelectEdition(edition.id)
                                    editionMenuExpanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(wb.createTranslationEdition, color = MaterialTheme.colorScheme.primary) },
                            leadingIcon = { Icon(Icons.Default.AddCircleOutline, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                editionMenuExpanded = false
                                onCreateEdition()
                            }
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    wb.workspaceSection,
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
                                    Text(tab.label(wb), modifier = Modifier.weight(1f), maxLines = 1)
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
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                ModelStyleConfigurationCard(
                    book = book,
                    editions = editions,
                    targetEdition = selectedEdition,
                    project = project,
                    run = run,
                    promptProfile = promptProfile,
                    providers = providers,
                    viewModel = viewModel,
                    compact = true,
                    wb = wb,
                    appColors = appColors
                )
            }

            HorizontalDivider()
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (project != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(wb.taskProgress, style = MaterialTheme.typography.labelMedium)
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
                            if (project != null) {
                                when (currentState) {
                                    "RUNNING", "QUEUED" -> viewModel.pauseBookTranslation(project.id)
                                    "PAUSED" -> viewModel.resumeBookTranslation(project.id)
                                    else -> viewModel.runBookTranslation(project.id)
                                }
                            }
                        },
                        enabled = project != null,
                        shape = ButtonShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            when (currentState) {
                                "RUNNING", "QUEUED" -> Icons.Default.Pause
                                "PAUSED" -> Icons.Default.PlayArrow
                                else -> Icons.Default.PlayArrow
                            },
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                project == null -> wb.saveModelConfigFirst
                                currentState == "RUNNING" || currentState == "QUEUED" -> wb.pause
                                currentState == "PAUSED" -> wb.resume
                                else -> wb.startTranslation
                            },
                            maxLines = 1
                        )
                    }
                    if (project != null && currentState in listOf("RUNNING", "PAUSED", "QUEUED")) {
                        IconButton(onClick = { viewModel.cancelBookTranslation(project.id) }) {
                            Icon(Icons.Default.StopCircle, wb.terminateTask, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScanTerms, modifier = Modifier.weight(1f), shape = ButtonShape) {
                        Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(wb.scanTerms, maxLines = 1)
                    }
                    OutlinedButton(onClick = onOpenReader, modifier = Modifier.weight(1f), shape = ButtonShape) {
                        Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(wb.read, maxLines = 1)
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
    project: TranslationProjectV2Entity?,
    run: PlatformTranslationRunEntity?,
    wb: WorkbenchUiStrings
) {
    val currentState = effectiveTranslationState(project, run)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(tab.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(21.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(tab.label(wb), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(tab.description(wb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    color = stateContainerColor(currentState),
                    modifier = Modifier.size(9.dp)
                ) {}
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorkbenchHeroSummary(
    book: BookEntity,
    editions: List<EditionEntity>,
    selectedEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    run: PlatformTranslationRunEntity?,
    promptProfile: PromptProfileEntity?,
    providers: List<ApiProviderEntity>,
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    appColors: AppColors,
    onSelectEdition: (Long) -> Unit,
    onCreateEdition: () -> Unit,
    onDeleteEdition: (EditionEntity) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            val showConfigBesideBook = maxWidth >= 720.dp
            if (showConfigBesideBook) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    BookHeroSummary(
                        book = book,
                        editions = editions,
                        selectedEdition = selectedEdition,
                        wb = wb,
                        onSelectEdition = onSelectEdition,
                        onCreateEdition = onCreateEdition,
                        onDeleteEdition = onDeleteEdition,
                        modifier = Modifier.weight(1f)
                    )
                    ModelStyleConfigurationCard(
                        book = book,
                        editions = editions,
                        targetEdition = selectedEdition,
                        project = project,
                        run = run,
                        promptProfile = promptProfile,
                        providers = providers,
                        viewModel = viewModel,
                        compact = false,
                        wb = wb,
                        appColors = appColors,
                        modifier = Modifier.widthIn(min = 300.dp, max = 420.dp)
                    )
                }
            } else {
                BookHeroSummary(
                    book = book,
                    editions = editions,
                    selectedEdition = selectedEdition,
                    wb = wb,
                    onSelectEdition = onSelectEdition,
                    onCreateEdition = onCreateEdition,
                    onDeleteEdition = onDeleteEdition,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BookHeroSummary(
    book: BookEntity,
    editions: List<EditionEntity>,
    selectedEdition: EditionEntity?,
    wb: WorkbenchUiStrings,
    onSelectEdition: (Long) -> Unit,
    onCreateEdition: () -> Unit,
    onDeleteEdition: (EditionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var editionMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCoverThumb(
            title = book.title,
            coverPath = book.coverPath,
            width = 52.dp,
            height = 72.dp
        )

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
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        if (selectedEdition?.type == EditionType.AI_TRANSLATION.name) wb.aiEdition else wb.importedEdition,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                "${book.author.ifBlank { wb.unknownAuthor }} · ${book.originalLanguage.ifBlank { "Auto" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { editionMenuExpanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${wb.currentEdition}: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            selectedEdition?.name ?: wb.selectTargetEdition,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (editionMenuExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = editionMenuExpanded,
                    onDismissRequest = { editionMenuExpanded = false },
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
                ) {
                    Text(
                        text = wb.editionList(editions.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    HorizontalDivider()

                    editions.forEach { edition ->
                        val isSelected = edition.id == selectedEdition?.id
                        val isImported = edition.type == EditionType.IMPORTED.name
                        val isDeletable = !isImported

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            edition.name,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = if (isImported) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                if (isImported) wb.importedEdition else wb.aiEdition,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                color = if (isImported) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    if (edition.language.isNotBlank() && !isImported) {
                                        Text(
                                            edition.language,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (isDeletable) {
                                {
                                    IconButton(
                                        onClick = {
                                            editionMenuExpanded = false
                                            onDeleteEdition(edition)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = wb.deleteEdition,
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            } else null,
                            onClick = {
                                onSelectEdition(edition.id)
                                editionMenuExpanded = false
                            }
                        )
                    }

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = {
                            Text(
                                wb.createTranslationEdition,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            editionMenuExpanded = false
                            onCreateEdition()
                        }
                    )
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
    totalTaskChapters: Int,
    targetEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    promptProfile: PromptProfileEntity?,
    run: PlatformTranslationRunEntity?,
    systemLogs: List<SystemLogEntry>,
    providers: List<ApiProviderEntity>,
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    appColors: AppColors,
    onCreateEdition: () -> Unit,
    onScanTerms: () -> Unit,
    onOpenReader: () -> Unit,
    onRequestAiSplit: (ApiProviderEntity) -> Unit,
    onSelectChapterAction: (LogicalChapterEntity) -> Unit
) {
    val totalChapters = totalTaskChapters
    val currentState = effectiveTranslationState(project, run)
    val completedChapters = if (currentState in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_WARNINGS")) {
        totalChapters
    } else {
        (run?.completedChapters ?: 0).coerceAtMost(totalChapters)
    }
    val progress = if (totalChapters > 0) (completedChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
    val reviewRunning = project?.highQualityReview == true && currentState == "RUNNING" && completedChapters >= totalChapters

    var taskMonitorExpanded by rememberSaveable(project?.id) { mutableStateOf(true) }
    var chapterMatrixExpanded by rememberSaveable(project?.id) { mutableStateOf(true) }
    // 引擎配置草稿：工程已存在时由分区自己保存；尚未建工程时先在这里缓存，
    // 创建任务时随 configureEditionTranslation 一并写入。
    var engineDraft by remember(project?.id, targetEdition?.id) {
        mutableStateOf(EngineConfigDraft.fromProject(project))
    }
    val engineEditable = effectiveTranslationState(project, run) !in setOf("RUNNING", "PAUSED")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (targetEdition == null || project == null) {
            val isAiTranslationEdition = targetEdition?.type == EditionType.AI_TRANSLATION.name
            if (isAiTranslationEdition && targetEdition != null) {
                item {
                    ModelStyleConfigurationCard(
                        book = book,
                        editions = editions,
                        targetEdition = targetEdition,
                        project = null,
                        promptProfile = promptProfile,
                        providers = providers,
                        viewModel = viewModel,
                        compact = false,
                        engineConfig = engineDraft,
                        wb = wb,
                        appColors = appColors
                    )
                }

                // 引擎开关在创建任务前就能选好，建工程时一并写入。
                item {
                    EngineConfigurationSection(
                        project = null,
                        viewModel = viewModel,
                        strings = engineConfigStrings(),
                        canEdit = true,
                        onDraftChange = { engineDraft = it }
                    )
                }
            } else {
                item {
                    AppCard(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = HeroCardShape,
                        contentPadding = PaddingValues(Spacing.xl)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AppIconBubble(
                                icon = Icons.Default.Tune,
                                size = 56.dp,
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (targetEdition == null) wb.noEditionSelected else wb.originalEditionSelected(targetEdition.name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                wb.needAiEditionBody,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onCreateEdition,
                                    modifier = Modifier.weight(1f),
                                    shape = ButtonShape
                                ) {
                                    Icon(Icons.Default.AddCircleOutline, null, Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(wb.createAiEdition)
                                }
                                val splitProvider = providers.firstOrNull()
                                if (splitProvider != null && targetEdition?.type != EditionType.AI_TRANSLATION.name) {
                                    OutlinedButton(
                                        onClick = { onRequestAiSplit(splitProvider) },
                                        modifier = Modifier.weight(1f),
                                        shape = ButtonShape
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(wb.aiRecognizeChapters)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Task Monitor & Controls Card
            item {
                AppCard(shape = GroupedCardShape) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { taskMonitorExpanded = !taskMonitorExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    wb.taskRunStatus,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    wb.modelModeReview(
                                        project.modelName.ifBlank { wb.modelName },
                                        project.translationMode,
                                        wb.onOff(project.highQualityReview)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                stateBadge(currentState, wb)
                                IconButton(onClick = { taskMonitorExpanded = !taskMonitorExpanded }) {
                                    Icon(
                                        if (taskMonitorExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (taskMonitorExpanded) wb.collapse else wb.expand
                                    )
                                }
                            }
                        }

                        if (taskMonitorExpanded) {
                            // Visual Progress Bar
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (reviewRunning) wb.draftDoneReviewing else wb.progressLabel(completedChapters, totalChapters),
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatBox(
                                    title = wb.promptTokens,
                                    value = TokenCalculator.formatTokenCount(run?.promptTokens ?: 0L),
                                    modifier = Modifier.weight(1f)
                                )
                                StatBox(
                                    title = wb.completionTokens,
                                    value = TokenCalculator.formatTokenCount(run?.completionTokens ?: 0L),
                                    modifier = Modifier.weight(1f)
                                )
                                StatBox(
                                    title = wb.estimatedCost,
                                    value = TokenCalculator.formatCost(run?.totalCost ?: 0.0, run?.currency ?: "USD"),
                                    modifier = Modifier.weight(1f)
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
                                            shape = ButtonShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                        ) {
                                            Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(wb.pauseTask)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.cancelBookTranslation(project.id) },
                                            shape = ButtonShape,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(wb.terminate)
                                        }
                                    }
                                    "PAUSED" -> {
                                        Button(
                                            onClick = { viewModel.resumeBookTranslation(project.id) },
                                            modifier = Modifier.weight(1f),
                                            shape = ButtonShape
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(wb.resumeTranslation)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.cancelBookTranslation(project.id) },
                                            shape = ButtonShape,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(wb.terminate)
                                        }
                                    }
                                    else -> {
                                        Button(
                                            onClick = { viewModel.runBookTranslation(project.id) },
                                            modifier = Modifier.weight(1f),
                                            shape = ButtonShape
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                when {
                                                    completedChapters > 0 -> wb.continueRemaining
                                                    project.translationMode == TranslationMode.CHAPTER_RANGE.name -> wb.startRangeTranslation
                                                    project.translationMode == TranslationMode.SEAMLESS.name -> wb.startSeamlessTranslation
                                                    else -> wb.startFullBookTranslation
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Quick actions row: Scan terms, Read
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onScanTerms,
                                    modifier = Modifier.weight(1f),
                                    shape = ButtonShape
                                ) {
                                    Icon(Icons.Default.Spellcheck, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(wb.scanTerms, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onOpenReader,
                                    modifier = Modifier.weight(1f),
                                    shape = ButtonShape
                                ) {
                                    Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(wb.readTranslation, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // Model & Style Configuration Card
            item {
                ModelStyleConfigurationCard(
                    book = book,
                    editions = editions,
                    targetEdition = targetEdition,
                    project = project,
                    run = run,
                    promptProfile = promptProfile,
                    providers = providers,
                    viewModel = viewModel,
                    compact = false,
                    wb = wb,
                    appColors = appColors
                )
            }

            item {
                TaskScaleConfigurationCard(
                    project = project,
                    totalChapters = totalChapters,
                    viewModel = viewModel,
                    run = run,
                    wb = wb
                )
            }

            // Translation engine switches (parallel chapters, continuity, metadata, prompt cache)
            item {
                EngineConfigurationSection(
                    project = project,
                    viewModel = viewModel,
                    strings = engineConfigStrings(),
                    canEdit = engineEditable,
                    onDraftChange = { engineDraft = it }
                )
            }

            // Chapter Matrix / Status Grid
            item {
                AppCard(shape = GroupedCardShape) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chapterMatrixExpanded = !chapterMatrixExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    wb.chapterMatrixTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    wb.totalChapters(chapters.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { chapterMatrixExpanded = !chapterMatrixExpanded }) {
                                Icon(
                                    if (chapterMatrixExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (chapterMatrixExpanded) wb.collapse else wb.expand
                                )
                            }
                        }

                        if (chapterMatrixExpanded) {
                            ChapterStatusMatrix(
                                chapters = chapters,
                                completedChapters = completedChapters,
                                appColors = appColors,
                                wb = wb,
                                onSelectChapter = onSelectChapterAction
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelStyleConfigurationCard(
    book: BookEntity,
    editions: List<EditionEntity>,
    targetEdition: EditionEntity?,
    project: TranslationProjectV2Entity?,
    promptProfile: PromptProfileEntity?,
    providers: List<ApiProviderEntity>,
    viewModel: AppViewModel,
    compact: Boolean,
    wb: WorkbenchUiStrings,
    appColors: AppColors,
    modifier: Modifier = Modifier,
    run: PlatformTranslationRunEntity? = null,
    engineConfig: EngineConfigDraft = EngineConfigDraft.fromProject(project)
) {
    val originalEdition = editions.firstOrNull { it.id == book.primaryEditionId }
        ?: editions.firstOrNull { it.type == EditionType.IMPORTED.name }
        ?: editions.firstOrNull()
    val projectProviderId = project?.providerId
    var selectedProviderId by rememberSaveable(project?.id, project?.updatedAt, targetEdition?.id) {
        mutableStateOf(project?.providerId ?: providers.firstOrNull()?.id)
    }
    var selectedModel by rememberSaveable(project?.id, project?.updatedAt, targetEdition?.id) {
        mutableStateOf(
            project?.modelName?.ifBlank {
                providers.firstOrNull { it.id == projectProviderId }?.selectedModel
                    ?: providers.firstOrNull()?.selectedModel.orEmpty()
            } ?: providers.firstOrNull()?.selectedModel.orEmpty()
        )
    }
    var styleGuide by rememberSaveable(project?.id, project?.updatedAt, targetEdition?.id) {
        mutableStateOf(project?.styleGuide?.ifBlank { wb.styleGuideLiterary } ?: wb.styleGuideLiterary)
    }
    val sourceLanguage = project?.sourceLanguage
        ?: originalEdition?.language?.takeIf { it.isNotBlank() }
        ?: book.originalLanguage
    val targetLanguage = project?.targetLanguage
        ?: targetEdition?.language?.takeIf { it.isNotBlank() }
        ?: wb.targetLanguage
    val defaultPromptProfile = remember(sourceLanguage, targetLanguage) {
        TranslationProtocol.defaultPromptProfile()
    }
    var promptDraft by remember(project?.id, promptProfile?.version, targetEdition?.id, sourceLanguage, targetLanguage) {
        mutableStateOf(promptProfile?.asDraft() ?: defaultPromptProfile)
    }
    var showPromptEditor by remember(project?.id, targetEdition?.id) { mutableStateOf(false) }
    var highQualityReview by rememberSaveable(project?.id, project?.updatedAt, targetEdition?.id) {
        mutableStateOf(project?.highQualityReview ?: false)
    }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var isTestingProvider by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()
    LaunchedEffect(providers, project?.id, targetEdition?.id) {
        val providerStillExists = selectedProviderId?.let { id -> providers.any { it.id == id } } == true
        if (!providerStillExists) {
            selectedProviderId = project?.providerId?.takeIf { id -> providers.any { it.id == id } }
                ?: providers.firstOrNull()?.id
        }
        if (selectedModel.isBlank()) {
            selectedModel = providers.firstOrNull { it.id == selectedProviderId }?.selectedModel
                ?: providers.firstOrNull()?.selectedModel.orEmpty()
        }
    }
    val canEdit = effectiveTranslationState(project, run) !in setOf("RUNNING", "PAUSED")
    val canCreateProject = project != null || (
        targetEdition != null &&
            originalEdition != null &&
            targetEdition.id != originalEdition.id
        )
    val canSave = targetEdition != null && canCreateProject &&
        activeProvider != null

    var isExpanded by rememberSaveable(project?.id, targetEdition?.id) { mutableStateOf(true) }

    AppCard(
        modifier = modifier,
        shape = GroupedCardShape
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconBubble(icon = Icons.Default.Memory, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(wb.modelConfig, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (project == null) wb.modelConfigSubtitleNew else wb.modelConfigSubtitleExisting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) wb.collapse else wb.expand
                    )
                }
            }

            if (isExpanded) {
            if (providers.isEmpty()) {
                Text(
                    wb.noProviderHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { if (canEdit) providerMenuExpanded = !providerMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = activeProvider?.let { "${it.name} (${it.providerType.displayName})" }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(wb.aiProvider) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        enabled = canEdit
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text("${provider.name} (${provider.providerType.displayName})") },
                                onClick = {
                                    selectedProviderId = provider.id
                                    selectedModel = provider.selectedModel
                                    providerMenuExpanded = false
                                    testResult = null
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = { selectedModel = it.take(200) },
                    label = { Text(wb.modelName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = canEdit
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            activeProvider?.let { provider ->
                                isTestingProvider = true
                                testResult = null
                                viewModel.testProvider(provider) { success, message ->
                                    isTestingProvider = false
                                    testResult = if (success) wb.connectionSuccess else wb.connectionFailed(message)
                                }
                            }
                        },
                        enabled = activeProvider != null && !isTestingProvider,
                        shape = ButtonShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (isTestingProvider) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, null, Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(wb.testConnection)
                    }
                    if (!testResult.isNullOrBlank()) {
                        Text(
                            testResult.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult == wb.connectionSuccess) appColors.success else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                    }
                }
            }

            if (project == null && !canCreateProject) {
                Text(
                    wb.createEditionFirstHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconBubble(icon = Icons.Default.AutoAwesome, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(wb.styleAndPrompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        wb.styleAndPromptSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(
                    wb.stylePresetXianxia to wb.styleGuideXianxia,
                    wb.stylePresetWestern to wb.styleGuideWestern,
                    wb.stylePresetLightNovel to wb.styleGuideLightNovel,
                    wb.stylePresetLiterary to wb.styleGuideLiterary
                ).forEach { (preset, guide) ->
                    AssistChip(
                        onClick = { styleGuide = guide },
                        enabled = canEdit,
                        shape = SmallControlShape,
                        label = { Text(preset, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }

            OutlinedTextField(
                value = styleGuide,
                onValueChange = { styleGuide = it.take(2_000) },
                label = { Text(wb.literaryStyleGuide) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (compact) 2 else 3,
                maxLines = if (compact) 4 else 6,
                enabled = canEdit
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        wb.translationPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (promptProfile == null) {
                            wb.defaultPromptProfileHint
                        } else {
                            wb.promptProfileVersion(promptProfile.version)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { showPromptEditor = true },
                    enabled = canEdit,
                    shape = ButtonShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.EditNote, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(wb.previewEdit)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(wb.highQualityReview, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (highQualityReview) {
                            wb.highQualityReviewOnHint
                        } else {
                            wb.highQualityReviewOffHint
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = highQualityReview,
                    onCheckedChange = { highQualityReview = it },
                    enabled = effectiveTranslationState(project, run) !in setOf("RUNNING", "PAUSED")
                )
            }

            Button(
                onClick = {
                    if (targetEdition != null) {
                        if (project != null) {
                            val mode = runCatching { TranslationMode.valueOf(project.translationMode) }
                                .getOrDefault(TranslationMode.FULL_BOOK)
                            viewModel.updateTranslationProjectConfig(
                                projectId = project.id,
                                providerId = activeProvider?.id ?: selectedProviderId,
                                modelName = selectedModel,
                                mode = mode,
                                maxBatchChapters = project.maxBatchChapters,
                                rangeStart = project.rangeStart,
                                rangeEnd = project.rangeEnd,
                                seamlessAheadChapters = project.seamlessAheadChapters,
                                styleGuide = styleGuide,
                                highQualityReview = highQualityReview,
                                promptProfile = promptDraft
                            )
                        } else if (originalEdition != null) {
                            viewModel.configureEditionTranslation(
                                bookId = book.id,
                                sourceEditionId = originalEdition.id,
                                targetEditionId = targetEdition.id,
                                providerId = activeProvider?.id ?: selectedProviderId,
                                modelName = selectedModel,
                                mode = TranslationMode.FULL_BOOK,
                                maxBatchChapters = 1,
                                styleGuide = styleGuide,
                                highQualityReview = highQualityReview,
                                parallelChapters = engineConfig.parallelChapters,
                                continuityMode = engineConfig.continuityMode,
                                metadataEnabled = engineConfig.metadataEnabled,
                                promptCacheEnabled = engineConfig.promptCacheEnabled,
                                promptProfile = promptDraft,
                                startImmediately = false
                            )
                        }
                    }
                },
                enabled = canSave && canEdit,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 7.dp)
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (project == null) wb.saveAndCreateTask else wb.saveModelAndStyle)
            }
            }
        }
    }

    if (showPromptEditor) {
        PromptProfileEditorDialog(
            draft = promptDraft,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            styleGuide = styleGuide,
            version = promptProfile?.version ?: 1,
            canEdit = canEdit,
            projectExists = project != null,
            wb = wb,
            onDismiss = { showPromptEditor = false },
            onSave = { updated ->
                promptDraft = updated
                showPromptEditor = false
                project?.let { viewModel.savePromptProfile(it.id, updated) }
            }
        )
    }
}

@Composable
private fun PromptProfileEditorDialog(
    draft: PromptProfileDraft,
    sourceLanguage: String,
    targetLanguage: String,
    styleGuide: String,
    version: Int,
    canEdit: Boolean,
    projectExists: Boolean,
    wb: WorkbenchUiStrings,
    onDismiss: () -> Unit,
    onSave: (PromptProfileDraft) -> Unit
) {
    var selectedPrompt by rememberSaveable { mutableStateOf(0) }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var workingDraft by remember(draft) { mutableStateOf(draft) }
    val isTranslation = selectedPrompt == 0
    val systemPrompt = if (isTranslation) {
        workingDraft.translationSystemPrompt
    } else {
        workingDraft.polishSystemPrompt
    }
    val userPromptTemplate = if (isTranslation) {
        workingDraft.translationUserPromptTemplate
    } else {
        workingDraft.polishUserPromptTemplate
    }
    val fallbackSystemPrompt = if (isTranslation) {
        TranslationProtocol.systemPrompt(sourceLanguage, targetLanguage)
    } else {
        TranslationProtocol.polishSystemPrompt(sourceLanguage, targetLanguage)
    }
    val renderedSystemPrompt = TranslationProtocol.renderSystemPrompt(
        template = systemPrompt,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        styleGuide = styleGuide,
        fallback = fallbackSystemPrompt
    )
    val previewBody = if (isTranslation) {
        """
        [TRANSLATION_PROTOCOL]
        Protocol version: 2
        Style guide: $styleGuide

        [SOURCE]
        <C id="1" title="示例章节">
        <S id="1">这里会注入当前批次的原文段落。</S>
        </C>
        """.trimIndent()
    } else {
        """
        [POLISH_PROTOCOL]
        Protocol version: 2

        [SOURCE]
        <C id="1"><S id="1">这里会注入当前章节原文。</S></C>

        [CURRENT_TRANSLATION]
        <C id="1"><S id="1">这里会注入已经通过质检的初稿。</S></C>
        """.trimIndent()
    }
    val renderedUserPrompt = TranslationProtocol.renderUserPromptTemplate(userPromptTemplate, previewBody)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wb.promptProfileTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (projectExists) wb.promptProfileVersionHint(version) else wb.promptProfileNewHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = wb.close)
                    }
                }

                TabRow(selectedTabIndex = selectedPrompt) {
                    Tab(
                        selected = isTranslation,
                        onClick = { selectedPrompt = 0 },
                        text = { Text(wb.translationPromptTab) },
                        icon = { Icon(Icons.Default.Translate, null, Modifier.size(17.dp)) }
                    )
                    Tab(
                        selected = !isTranslation,
                        onClick = { selectedPrompt = 1 },
                        text = { Text(wb.polishPromptTab) },
                        icon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(17.dp)) }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { previewMode = !previewMode }, shape = ButtonShape) {
                        Icon(
                            if (previewMode) Icons.Default.Edit else Icons.Default.Visibility,
                            null,
                            Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (previewMode) wb.editTemplate else wb.previewFinalPrompt)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (previewMode) {
                        Text(
                            wb.systemPromptPreview,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        PromptPreviewBlock(renderedSystemPrompt)
                        Text(
                            wb.userPromptPreview,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        PromptPreviewBlock(renderedUserPrompt)
                    } else {
                        Text(
                            wb.systemPromptTemplate,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { value ->
                                val updated = value.take(24_000)
                                workingDraft = if (isTranslation) {
                                    workingDraft.copy(translationSystemPrompt = updated)
                                } else {
                                    workingDraft.copy(polishSystemPrompt = updated)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            maxLines = 16,
                            enabled = canEdit,
                            supportingText = {
                                Text(wb.systemPromptVariablesHint)
                            }
                        )
                        Text(
                            wb.userPromptTemplate,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = userPromptTemplate,
                            onValueChange = { value ->
                                val updated = value.take(24_000)
                                workingDraft = if (isTranslation) {
                                    workingDraft.copy(translationUserPromptTemplate = updated)
                                } else {
                                    workingDraft.copy(polishUserPromptTemplate = updated)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            maxLines = 12,
                            enabled = canEdit,
                            supportingText = {
                                Text(wb.userPromptVariablesHint)
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        wb.templateCharLimit,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onDismiss) { Text(wb.dialogs.cancel) }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onSave(workingDraft) }, enabled = canEdit, shape = ButtonShape) {
                        Icon(Icons.Default.Save, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (projectExists) wb.saveNewVersion else wb.applyToNewTask)
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptPreviewBlock(value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            value,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TaskScaleConfigurationCard(
    project: TranslationProjectV2Entity,
    totalChapters: Int,
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    run: PlatformTranslationRunEntity? = null
) {
    var translationMode by rememberSaveable(project.id, project.updatedAt) {
        mutableStateOf(project.translationMode)
    }
    var batchSize by rememberSaveable(project.id, project.updatedAt) {
        mutableStateOf(project.maxBatchChapters.coerceIn(1, 5))
    }
    var rangeStartText by rememberSaveable(project.id, project.updatedAt) {
        mutableStateOf(project.rangeStart?.toString() ?: "1")
    }
    var rangeEndText by rememberSaveable(project.id, project.updatedAt) {
        mutableStateOf(project.rangeEnd?.toString() ?: totalChapters.coerceAtLeast(1).toString())
    }

    val rangeStart = rangeStartText.toIntOrNull()
    val rangeEnd = rangeEndText.toIntOrNull()
    val rangeIsValid = translationMode != TranslationMode.CHAPTER_RANGE.name ||
        (rangeStart != null && rangeEnd != null && rangeStart > 0 && rangeEnd >= rangeStart && rangeEnd <= totalChapters)
    // The engine captures one immutable configuration snapshot for a run. Do not let a paused
    // run appear editable because those changes would silently be ignored on resume.
    val canEdit = effectiveTranslationState(project, run) !in setOf("RUNNING", "PAUSED")
    var isExpanded by rememberSaveable(project.id) { mutableStateOf(true) }

    AppCard(shape = GroupedCardShape) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(wb.taskScopeTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        wb.taskScopeSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            wb.maxChaptersBadge(totalChapters),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) wb.collapse else wb.expand
                        )
                    }
                }
            }

            if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    TranslationMode.FULL_BOOK.name to wb.modeFullBook,
                    TranslationMode.CHAPTER_RANGE.name to wb.modeChapterRange,
                    TranslationMode.SEAMLESS.name to wb.modeSeamless
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = translationMode == mode,
                        onClick = { if (canEdit) translationMode = mode },
                        enabled = canEdit,
                        shape = SmallControlShape,
                        label = { Text(label, maxLines = 1) }
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
                        onValueChange = { if (canEdit) rangeStartText = it.filter(Char::isDigit) },
                        label = { Text(wb.startChapter) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = canEdit,
                        isError = rangeStartText.isNotBlank() && rangeStart == null
                    )
                    OutlinedTextField(
                        value = rangeEndText,
                        onValueChange = { if (canEdit) rangeEndText = it.filter(Char::isDigit) },
                        label = { Text(wb.endChapter) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = canEdit,
                        isError = rangeEndText.isNotBlank() && rangeEnd == null
                    )
                }
                if (!rangeIsValid) {
                    Text(
                        wb.rangeInvalidHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(wb.batchChaptersPerRequest, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        wb.chaptersPerBatch(batchSize),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = batchSize.toFloat(),
                    onValueChange = { if (canEdit) batchSize = it.toInt().coerceIn(1, 5) },
                    valueRange = 1f..5f,
                    steps = 3,
                    enabled = canEdit
                )
                if (!canEdit) {
                    Text(
                        wb.batchEditLockedHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    wb.batchRecommendation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    val mode = runCatching { TranslationMode.valueOf(translationMode) }
                        .getOrDefault(TranslationMode.FULL_BOOK)
                    viewModel.updateTranslationProjectConfig(
                        projectId = project.id,
                        providerId = project.providerId,
                        modelName = project.modelName,
                        mode = mode,
                        maxBatchChapters = batchSize,
                        rangeStart = if (mode == TranslationMode.CHAPTER_RANGE) rangeStart else null,
                        rangeEnd = if (mode == TranslationMode.CHAPTER_RANGE) rangeEnd else null,
                        seamlessAheadChapters = project.seamlessAheadChapters,
                        styleGuide = project.styleGuide
                    )
                },
                enabled = canEdit && rangeIsValid,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(wb.saveTaskParams)
            }
            }
        }
    }
}

@Composable
private fun ChapterStatusMatrix(
    chapters: List<LogicalChapterEntity>,
    completedChapters: Int,
    appColors: AppColors,
    wb: WorkbenchUiStrings,
    onSelectChapter: (LogicalChapterEntity) -> Unit
) {
    if (chapters.isEmpty()) {
        Text(
            wb.noChapters,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellMinWidth = 48.dp
        val cellSpacing = 4.dp
        val columnCount = ((maxWidth + cellSpacing) / (cellMinWidth + cellSpacing))
            .toInt()
            .coerceAtLeast(1)
        val rowCount = (chapters.size + columnCount - 1) / columnCount
        val gridHeight = 36.dp * rowCount.toFloat() + cellSpacing * (rowCount - 1).coerceAtLeast(0).toFloat()

        // Fixed height lets the outer LazyColumn own vertical scrolling while the grid still
        // lazily composes visible cells. No chapters are truncated at an arbitrary count.
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            horizontalArrangement = Arrangement.spacedBy(cellSpacing),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
            userScrollEnabled = false
        ) {
            items(chapters, key = { it.id }) { chapter ->
                val isDone = chapter.chapterIndex <= completedChapters
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectChapter(chapter) },
                    color = if (isDone) appColors.success.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isDone) BorderStroke(1.dp, appColors.success) else null
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "${chapter.chapterIndex}",
                            fontSize = 12.sp,
                            fontWeight = if (isDone) FontWeight.Bold else FontWeight.Normal,
                            color = if (isDone) appColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun borderModifier(color: Color) = BorderStroke(1.dp, color)

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun LiveProcessLogCard(
    projectId: Long?,
    systemLogs: List<SystemLogEntry>,
    wb: WorkbenchUiStrings,
    modifier: Modifier = Modifier,
    expandToAvailableSpace: Boolean = false
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
                "TRANSLATION" -> entry.tag in listOf("TRANSLATION", "BATCH", "AI_POLISH")
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

    AppCard(
        modifier = modifier,
        shape = GroupedCardShape,
        containerColor = ConsoleBackground,
        border = BorderStroke(0.8.dp, ConsoleBackground),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandToAvailableSpace) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Terminal, null, tint = ConsoleSuccess, modifier = Modifier.size(18.dp))
                    Text(
                        wb.liveLogTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ConsoleOnSurface
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val text = filteredLogs.joinToString("\n") { "[${it.formattedDate}] [${it.tag}] ${it.message}" }
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, wb.logsCopied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, wb.copyLogs, tint = ConsoleOnSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { SystemLogger.clearLogs() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, wb.clearLogs, tint = ConsoleOnSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "ALL" to wb.filterAll,
                    "TRANSLATION" to wb.filterFlow,
                    "LLM_API" to wb.filterModelApi,
                    "GLOSSARY" to wb.filterGlossary,
                    "ERROR" to wb.filterError
                ).forEach { (tag, label) ->
                    val isSelected = selectedTagFilter == tag
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { selectedTagFilter = tag },
                        color = if (isSelected) MaterialTheme.colorScheme.primary else ConsoleBackgroundLight
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else ConsoleOnSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Log Console Box
            val listState = rememberLazyListState()
            LaunchedEffect(filteredLogs.lastOrNull()?.id, filteredLogs.size, selectedTagFilter, searchQuery) {
                if (filteredLogs.isNotEmpty()) {
                    listState.animateScrollToItem(filteredLogs.size - 1)
                }
            }

            Surface(
                modifier = if (expandToAvailableSpace) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 360.dp)
                },
                shape = RoundedCornerShape(8.dp),
                color = ConsoleSurface
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            wb.noMatchingLogs,
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleOnSurfaceVariant,
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
                                LogLevel.ERROR -> ConsoleError
                                LogLevel.WARN -> ConsoleWarning
                                else -> when (log.tag) {
                                    "TRANSLATION", "BATCH" -> ConsoleSuccess
                                    "LLM_API" -> ConsoleInfo
                                    "GLOSSARY" -> ConsoleAccent
                                    "QA_CHECK" -> ConsoleWarning
                                    else -> ConsoleOnSurface
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
                                        color = ConsoleOnSurfaceVariant
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
                                        color = ConsoleOnSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (!log.details.isNullOrBlank()) {
                                    Text(
                                        "  └ ${log.details}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = ConsoleOnSurfaceVariant,
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

@Composable
private fun GlossaryManagementTab(
    book: BookEntity,
    project: TranslationProjectV2Entity?,
    lexicon: List<LexiconEntryEntity>,
    candidates: List<LexiconCandidateAggregateEntity>,
    storyMemory: List<StoryMemoryEntity>,
    onScanTerms: () -> Unit,
    onAddTerm: () -> Unit,
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    appColors: AppColors
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
    val highConfidenceReviews = candidateReviews.filter { it.isHighConfidenceForBatch }
    val selectedHighConfidenceIds = highConfidenceReviews
        .filter { it.id in selectedCandidateIds }
        .map { it.id }

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
            AppCard(
                shape = HeroCardShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                border = null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            wb.glossaryScanBannerTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            wb.glossaryScanBannerBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Button(onClick = onScanTerms, shape = ButtonShape) {
                        Text(wb.scanNow)
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
                    placeholder = { Text(wb.searchTerms) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.weight(1f),
                    shape = ButtonShape,
                    singleLine = true
                )
                Button(
                    onClick = onAddTerm,
                    enabled = project != null,
                    shape = ButtonShape
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(wb.addTerm)
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
                    "ALL" to wb.categoryAll(officialLexicon.size),
                    "CHARACTER" to wb.categoryCharacter,
                    "LOCATION" to wb.categoryLocation,
                    "SKILL" to wb.categorySkill,
                    "LORE" to wb.categoryLore,
                    "ITEM" to wb.categoryItem,
                    "HONORIFIC" to wb.categoryHonorific,
                    "CUSTOM" to wb.categoryCustom
                ).forEach { (cat, label) ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = cat },
                        shape = SmallControlShape,
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (candidateReviews.isNotEmpty()) {
            item {
                AppCard(
                    shape = GroupedCardShape,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    border = null
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(wb.candidateReviewTitle(candidateReviews.size), fontWeight = FontWeight.Bold)
                                Text(
                                    wb.candidateReviewBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Button(
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = ButtonShape,
                                onClick = {
                                    viewModel.confirmLexiconCandidatesBatch(selectedHighConfidenceIds) { _, _ ->
                                        selectedCandidateIds = selectedCandidateIds - selectedHighConfidenceIds.toSet()
                                    }
                                },
                                enabled = selectedHighConfidenceIds.isNotEmpty()
                            ) {
                                Text(wb.batchConfirm(selectedHighConfidenceIds.size))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = {
                                if (highConfidenceReviews.isEmpty()) {
                                    viewModel.showMessage(wb.noHighConfidenceHint)
                                } else {
                                    selectedCandidateIds = highConfidenceReviews.mapTo(mutableSetOf()) { it.id }
                                }
                            }) { Text(wb.selectHighConfidence(highConfidenceReviews.size)) }
                            TextButton(onClick = { selectedCandidateIds = emptySet() }) { Text(wb.clearSelection) }
                            Text(
                                wb.selectedHighConfidence(selectedHighConfidenceIds.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
            items(candidateReviews, key = { it.id }) { review ->
                LexiconCandidateCard(
                    review = review,
                    selected = review.id in selectedCandidateIds,
                    wb = wb,
                    onToggle = { checked ->
                        selectedCandidateIds = if (checked) {
                            selectedCandidateIds + review.id
                        } else {
                            selectedCandidateIds - review.id
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
                        Text(wb.noMatchingTerms, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(wb.noMatchingTermsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(filteredLexicon, key = { "lexicon_${it.id}" }) { entry ->
                LexiconEntryCard(
                    entry = entry,
                    wb = wb,
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
            wb = wb,
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
            shape = DialogShape,
            title = { Text(wb.glossaryConflictTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(wb.glossaryConflictSource(details.candidate.sourceTerm), fontWeight = FontWeight.Bold)
                    Text(wb.glossaryConflictExisting(details.existing.targetTerm, details.existing.category))
                    Text(wb.glossaryConflictCandidate(pending.targetTerm, pending.category))
                    Text(
                        wb.glossaryConflictBody,
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
                }, shape = ButtonShape) { Text(wb.overwrite) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.skipLexiconCandidate(details.candidate.id) {
                            conflict = null
                            pendingImport = null
                        }
                    }) { Text(wb.skip) }
                    TextButton(onClick = { conflict = null; pendingImport = null }) { Text(wb.dialogs.cancel) }
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
    wb: WorkbenchUiStrings,
    onToggle: (Boolean) -> Unit,
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
    AppCard(
        shape = GroupedCardShape,
        border = if (review.hasTargetConflict || review.hasCategoryConflict) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
        } else null
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(review.sourceTerm, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.ArrowForward, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(review.winnerTargetTerm, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "${review.winnerCategory} · ${review.observationCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (review.isHighConfidenceForBatch) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        if (review.isHighConfidenceForBatch) wb.candidateHighConfidence else wb.candidateManualReview,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (review.isHighConfidenceForBatch) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = onConfirm) {
                    Icon(Icons.Default.Check, wb.confirm, Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(wb.confirm)
                }
            }
            if (review.hasTargetConflict || review.hasCategoryConflict) {
                Text(
                    if (review.hasCategoryConflict && review.hasTargetConflict) {
                        "${wb.candidateConflictTarget} / ${wb.candidateConflictCategory}"
                    } else if (review.hasCategoryConflict) {
                        wb.candidateConflictCategory
                    } else {
                        wb.candidateConflictTarget
                    },
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
                    wb.candidateVotes(categoryVotes.joinToString(" · ") { "${it.key} ${it.value}" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                wb.firstLastSeen(
                    review.aggregate.firstSeenChapterIndex.takeIf { it > 0 } ?: 0,
                    review.aggregate.lastSeenChapterIndex.takeIf { it > 0 } ?: 0
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (review.winnerNotes.isNotBlank()) {
                Text(review.winnerNotes, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(wb.editThenConfirm) }
                TextButton(onClick = onIgnore) { Text(wb.ignore, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateEditDialog(
    review: LexiconCandidateReview,
    wb: WorkbenchUiStrings,
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
        shape = DialogShape,
        title = { Text(wb.editCandidateTitle(review.sourceTerm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(wb.standardTranslation) }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(wb.category) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { category = option; expanded = false })
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(wb.notes) }, maxLines = 3)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(target.trim(), category, notes.trim()) }, enabled = target.isNotBlank() && category.isNotBlank(), shape = ButtonShape) {
                Text(wb.confirmAndImport)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(wb.dialogs.cancel) } }
    )
}

@Composable
private fun LexiconEntryCard(
    entry: LexiconEntryEntity,
    wb: WorkbenchUiStrings,
    onConfirm: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard(shape = GroupedCardShape) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        shape = RoundedCornerShape(5.dp),
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
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                if (entry.reviewStatus == ReviewStatus.CANDIDATE.name) wb.aiTermPending else wb.aiTermConfirmed,
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
                    Icon(Icons.Default.Check, wb.confirmTerm, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(wb.confirm)
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, wb.deleteTerm, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
    debugModeEnabled: Boolean,
    wb: WorkbenchUiStrings,
    appColors: AppColors
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
            Tab(selected = selectedSubTab == 0, onClick = { selectedSubTab = 0 }, text = { Text(wb.runHistoryTab(allRuns.size)) })
            Tab(selected = selectedSubTab == 1, onClick = { selectedSubTab = 1 }, text = { Text(wb.modelCallsTab(requestLogs.size)) })
            if (debugModeEnabled) {
                Tab(selected = selectedSubTab == 2, onClick = { selectedSubTab = 2 }, text = { Text(wb.debugDiagnosticsTab) })
            }
            Tab(selected = selectedSubTab == systemTabIndex, onClick = { selectedSubTab = systemTabIndex }, text = { Text(wb.systemLogsTab) })
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
                                Text(wb.noRunHistory, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(allRuns, key = { "run_${it.id}" }) { run ->
                            AppCard(
                                shape = GroupedCardShape,
                                border = if (run.id == selectedRunId) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                                onClick = {
                                    selectedRunId = run.id
                                    selectedSubTab = if (debugModeEnabled) 2 else 1
                                }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            wb.taskMeta(run.id, run.providerName, run.modelName, run.promptProfileVersion),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        stateBadge(run.state, wb)
                                    }
                                    Text(
                                        wb.runTokenCostSummary(
                                            run.completedChapters,
                                            TokenCalculator.formatTokenCount(run.promptTokens + run.completionTokens),
                                            TokenCalculator.formatCost(run.totalCost, run.currency)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!run.lastError.isNullOrBlank()) {
                                        Text(
                                            "${wb.taskError}: ${run.lastError}",
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
                                Text(wb.noModelCalls, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(requestLogs, key = { "req_${it.id}" }) { log ->
                            val status = requestLogStatus(log)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(log.operation, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            when (status) {
                                                RequestLogStatus.SUCCESS -> wb.statusSuccessMs(log.durationMs)
                                                RequestLogStatus.WARNING -> wb.statusNeedsRepair
                                                RequestLogStatus.INFO -> wb.statusInfo
                                                RequestLogStatus.FAILURE -> wb.statusFailure
                                            },
                                            color = requestLogStatusColor(status, appColors),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        "Tokens: ${log.promptTokens} / ${log.completionTokens} · ${TokenCalculator.formatCost(log.estimatedCost, selectedRun?.currency ?: "USD")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!log.errorMessage.isNullOrBlank()) {
                                        Text(log.errorMessage, color = requestLogStatusColor(status, appColors), style = MaterialTheme.typography.bodySmall)
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
                    viewModel = viewModel,
                    wb = wb,
                    appColors = appColors
                )
            } else {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    LiveProcessLogCard(
                        projectId = project?.id,
                        systemLogs = systemLogs,
                        wb = wb,
                        modifier = Modifier.fillMaxSize(),
                        expandToAvailableSpace = true
                    )
                }
            }
            3 -> {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    LiveProcessLogCard(
                        projectId = project?.id,
                        systemLogs = systemLogs,
                        wb = wb,
                        modifier = Modifier.fillMaxSize(),
                        expandToAvailableSpace = true
                    )
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
    viewModel: AppViewModel,
    wb: WorkbenchUiStrings,
    appColors: AppColors
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
            AppCard(
                shape = GroupedCardShape,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                border = null
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(wb.debugNoticeTitle, fontWeight = FontWeight.Bold)
                    Text(wb.debugNoticeBody, style = MaterialTheme.typography.bodySmall)
                    if (run != null) {
                        Text(
                            wb.debugRunHeader(run.id, run.state, run.providerName, run.modelName, run.promptProfileVersion),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!run.lastError.isNullOrBlank()) Text(wb.debugRunFailure(run.lastError), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (runFlow.isNotEmpty()) {
            item { AppSectionHeader(wb.debugFlowTitle) }
            items(runFlow, key = { "flow_${it.id}" }) { entry ->
                Text(
                    "${entry.formattedTime}  [${entry.tag}] ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        item { AppSectionHeader(wb.debugApiSectionTitle) }
        if (requestLogs.isEmpty()) {
            item { Text(wb.debugNoApiContent, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(requestLogs, key = { "debug_req_${it.id}" }) { log ->
                val status = requestLogStatus(log)
                val expanded = expandedLogId == log.id
                val detail by produceState<PlatformRequestLogEntity?>(initialValue = null, log.id, expanded) {
                    if (expanded) value = viewModel.getRequestLogDetail(log.id)
                }
                AppCard(
                    shape = GroupedCardShape,
                    onClick = { expandedLogId = if (expanded) null else log.id }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log.operation, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                when (status) {
                                    RequestLogStatus.SUCCESS -> wb.statusSuccessMs(log.durationMs)
                                    RequestLogStatus.WARNING -> wb.statusNeedsRepair
                                    RequestLogStatus.INFO -> wb.statusInfo
                                    RequestLogStatus.FAILURE -> wb.statusFailure
                                },
                                color = requestLogStatusColor(status, appColors)
                            )
                        }
                        Text(
                            wb.debugDurationAttempts(
                                log.durationMs,
                                log.attemptCount,
                                log.promptTokens + log.completionTokens
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (status != RequestLogStatus.SUCCESS) {
                            Text(
                                if (status == RequestLogStatus.WARNING) {
                                    wb.debugDiagnosis(debugFailureExplanation(log, wb))
                                } else {
                                    wb.debugFailureReason(debugFailureExplanation(log, wb))
                                },
                                color = requestLogStatusColor(status, appColors),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (expanded) {
                            DebugTextBlock(wb.debugAttemptTrace, detail?.attemptTrace)
                            DebugTextBlock(wb.debugSystemPrompt, detail?.systemPrompt)
                            DebugTextBlock(wb.debugUserPrompt, detail?.userPrompt)
                            DebugTextBlock(wb.debugModelResponse, detail?.responseText)
                            DebugTextBlock(wb.debugErrorRaw, log.errorMessage)
                        } else {
                            Text(wb.debugExpandHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            value,
            Modifier.fillMaxWidth().padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun debugFailureExplanation(log: PlatformRequestLogSummary, wb: WorkbenchUiStrings): String = when (log.errorCategory) {
    LlmErrorCategory.AUTHENTICATION.name -> wb.failureAuth(log.errorMessage.orEmpty())
    LlmErrorCategory.RATE_LIMIT.name -> wb.failureRateLimit(log.errorMessage.orEmpty())
    LlmErrorCategory.CONTEXT_OVERFLOW.name -> wb.failureContextOverflow(log.errorMessage.orEmpty())
    LlmErrorCategory.TRUNCATED_OUTPUT.name -> wb.failureTruncated(log.errorMessage.orEmpty())
    LlmErrorCategory.QUALITY_REJECTED.name -> wb.failureQuality(log.errorMessage.orEmpty())
    LlmErrorCategory.NETWORK_UNAVAILABLE.name, LlmErrorCategory.TIMEOUT.name -> wb.failureNetwork(log.errorMessage.orEmpty())
    else -> wb.failureUnknown(log.errorMessage, log.errorCategory)
}

private fun requestLogStatus(log: PlatformRequestLogSummary): RequestLogStatus {
    // Older rows predate the status column. Infer repair diagnostics from their operation name so
    // an existing QA_REPAIR_TRIGGERED record does not remain a red failure after migration.
    if (log.operation.contains("TRIGGERED", ignoreCase = true) ||
        log.operation.contains("SKIPPED", ignoreCase = true) ||
        log.operation.contains("FALLBACK", ignoreCase = true)
    ) return RequestLogStatus.WARNING
    val parsed = runCatching { RequestLogStatus.valueOf(log.status) }.getOrNull()
    return if (parsed == RequestLogStatus.SUCCESS && !log.isSuccess) RequestLogStatus.FAILURE
    else parsed ?: if (log.isSuccess) RequestLogStatus.SUCCESS else RequestLogStatus.FAILURE
}

/**
 * The project row is normally the source of truth, but a terminal Run can be written a few
 * milliseconds before its project row is observed by a screen. Prefer that terminal state over
 * a stale active project state so controls cannot keep showing a finished task as RUNNING.
 */
private fun effectiveTranslationState(
    project: TranslationProjectV2Entity?,
    run: PlatformTranslationRunEntity?
): String {
    val projectState = project?.state
    val runState = run?.state
    val terminalRunStates = setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_WARNINGS", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED")
    val activeProjectStates = setOf("RUNNING", "PAUSED", "QUEUED", "INTERRUPTED")
    return when {
        projectState == null -> runState ?: "IDLE"
        projectState in activeProjectStates && runState?.let { it in terminalRunStates } == true -> runState
        else -> projectState
    }
}

@Composable
private fun requestLogStatusColor(status: RequestLogStatus, appColors: AppColors): Color = when (status) {
    RequestLogStatus.SUCCESS -> appColors.success
    RequestLogStatus.WARNING -> appColors.warning
    RequestLogStatus.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    RequestLogStatus.FAILURE -> MaterialTheme.colorScheme.error
}

// Dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEditionDialog(
    book: BookEntity,
    sourceEdition: EditionEntity?,
    wb: WorkbenchUiStrings,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, String) -> Unit
) {
    var targetLanguage by remember {
        mutableStateOf(TARGET_LANGUAGE_OPTIONS.first().displayName)
    }
    var editionName by remember {
        val first = TARGET_LANGUAGE_OPTIONS.first()
        mutableStateOf("${if (wb.workbenchTitle == "Workbench") first.defaultNameEn else first.defaultNameZh} (AI)")
    }
    var expandedLang by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        title = { Text(wb.createEditionDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    wb.createEditionDialogBody(book.title),
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
                        label = { Text(wb.targetLanguage) },
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
                                    editionName = "${if (wb.workbenchTitle == "Workbench") opt.defaultNameEn else opt.defaultNameZh} (AI)"
                                    expandedLang = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editionName,
                    onValueChange = { editionName = it },
                    label = { Text(wb.editionVersionName) },
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
                },
                shape = ButtonShape
            ) {
                Text(wb.create)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(wb.dialogs.cancel) }
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
    wb: WorkbenchUiStrings,
    onDismiss: () -> Unit,
    onCancelScan: () -> Unit,
    onStartScan: (Int, Int, ApiProviderEntity, String, (String) -> Unit, (Result<Int>) -> Unit) -> Unit
) {
    var startChText by remember { mutableStateOf("1") }
    var endChText by remember { mutableStateOf(minOf(10, totalChapters).toString()) }
    var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.id) }
    var targetLanguage by remember { mutableStateOf("中文") }

    var isScanning by remember { mutableStateOf(false) }
    var scanStatusMessage by remember { mutableStateOf("") }
    var scanCompletedCount by remember { mutableStateOf<Int?>(null) }
    var scanErrorMessage by remember { mutableStateOf<String?>(null) }

    val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()

    AlertDialog(
        onDismissRequest = { if (!isScanning) onDismiss() },
        shape = DialogShape,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Spellcheck, null, tint = MaterialTheme.colorScheme.primary)
                Text(wb.termScannerTitle)
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
                    val completedCount = scanCompletedCount ?: 0
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = LocalAppColors.current.success, modifier = Modifier.size(48.dp))
                        Text(
                            wb.scanCompleteTitle(completedCount),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            wb.scanCompleteBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (scanErrorMessage != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Text(
                            wb.scanFailedTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            scanErrorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    if (targetProjectId == null) {
                        Text(
                            wb.termScannerNeedProject,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        wb.termScannerBody,
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
                            label = { Text(wb.startChapter) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endChText,
                            onValueChange = { endChText = it.filter { c -> c.isDigit() } },
                            label = { Text(wb.endChapter) },
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
                                label = { Text(wb.analysisModel) },
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
            if (isScanning) {
                TextButton(onClick = {
                    onCancelScan()
                    isScanning = false
                    onDismiss()
                }) { Text(wb.stopScan) }
            } else if (scanCompletedCount != null) {
                Button(onClick = onDismiss, shape = ButtonShape) { Text(wb.done) }
            } else if (scanErrorMessage != null) {
                Button(onClick = { scanErrorMessage = null }, shape = ButtonShape) { Text(wb.retry) }
            } else if (!isScanning) {
                Button(
                    onClick = {
                        val start = startChText.toIntOrNull() ?: 1
                        val end = endChText.toIntOrNull() ?: totalChapters
                        if (activeProvider != null) {
                            isScanning = true
                            scanStatusMessage = wb.initializingScan
                            onStartScan(
                                start,
                                end,
                                activeProvider,
                                targetLanguage,
                                { scanStatusMessage = it },
                                { result ->
                                    isScanning = false
                                    result.onSuccess { count ->
                                        scanErrorMessage = null
                                        scanCompletedCount = count
                                    }.onFailure { error ->
                                        scanCompletedCount = null
                                        scanErrorMessage = error.localizedMessage?.takeIf { it.isNotBlank() }
                                            ?: wb.scanFailedHint
                                    }
                                }
                            )
                        }
                    },
                    enabled = activeProvider != null && sourceEdition != null && targetProjectId != null && totalChapters > 0,
                    shape = ButtonShape
                ) {
                    Text(wb.startScan)
                }
            }
        },
        dismissButton = {
            if (!isScanning && scanCompletedCount == null) {
                TextButton(onClick = onDismiss) { Text(wb.dialogs.cancel) }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLexiconEntryDialog(
    projectId: Long,
    wb: WorkbenchUiStrings,
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
        shape = DialogShape,
        title = { Text(wb.addTermTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = sourceTerm,
                    onValueChange = { sourceTerm = it },
                    label = { Text(wb.sourceTermLabel) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetTerm,
                    onValueChange = { targetTerm = it },
                    label = { Text(wb.targetTermLabel) },
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = expandedCat,
                    onExpandedChange = { expandedCat = !expandedCat }
                ) {
                    OutlinedTextField(
                        value = when (category) {
                            "CHARACTER" -> wb.categoryCharacter
                            "LOCATION" -> wb.categoryLocation
                            "SKILL" -> wb.categorySkill
                            "LORE" -> wb.categoryLore
                            "ITEM" -> wb.categoryItem
                            "HONORIFIC" -> wb.categoryHonorific
                            else -> wb.categoryCustom
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(wb.category) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedCat) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCat,
                        onDismissRequest = { expandedCat = false }
                    ) {
                        listOf(
                            "CHARACTER" to wb.categoryCharacter,
                            "LOCATION" to wb.categoryLocation,
                            "SKILL" to wb.categorySkill,
                            "LORE" to wb.categoryLore,
                            "ITEM" to wb.categoryItem,
                            "HONORIFIC" to wb.categoryHonorific,
                            "CUSTOM" to wb.categoryCustom
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
                    label = { Text(wb.notesLabel) },
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
                enabled = sourceTerm.isNotBlank() && targetTerm.isNotBlank(),
                shape = ButtonShape
            ) {
                Text(wb.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(wb.dialogs.cancel) }
        }
    )
}
