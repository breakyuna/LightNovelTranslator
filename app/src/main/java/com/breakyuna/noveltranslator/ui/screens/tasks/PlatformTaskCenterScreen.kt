package com.breakyuna.noveltranslator.ui.screens.tasks

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.components.TARGET_LANGUAGE_OPTIONS
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.breakyuna.noveltranslator.ui.components.rememberAsyncBookImage
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformTaskCenterScreen(
    viewModel: AppViewModel,
    initialBookId: Long? = null,
    onBack: (() -> Unit)? = null,
    onOpenBook: (Long) -> Unit = {},
    onOpenReader: (Long, Long?) -> Unit = { _, _ -> },
    onOpenEdition: (Long, Long) -> Unit = { _, _ -> },
    onOpenBookWorkbench: (Long) -> Unit = {}
) {
    val strings = platformUiStrings()
    val window = rememberWindowSize()
    val allBooks by viewModel.allPlatformBooks.collectAsState()
    val allEditions by viewModel.allPlatformEditions.collectAsState()
    val allProjects by viewModel.platformTranslationProjects.collectAsState()
    val allRuns by viewModel.platformTaskRuns.collectAsState()

    // State for expanded book unit cards: if initialBookId is provided, pre-expand that book
    val expandedBookIds = remember {
        mutableStateMapOf<Long, Boolean>().apply {
            if (initialBookId != null && initialBookId > 0) {
                put(initialBookId, true)
            }
        }
    }

    // Auto expand initial book if passed later
    LaunchedEffect(initialBookId) {
        if (initialBookId != null && initialBookId > 0) {
            expandedBookIds[initialBookId] = true
        }
    }

    // Filter & Search states
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterStatus by rememberSaveable { mutableStateOf("ALL") } // ALL, RUNNING, PAUSED, COMPLETED
    var showSearchBar by rememberSaveable { mutableStateOf(false) }

    // Dialog states
    var createEditionForBook by remember { mutableStateOf<BookEntity?>(null) }
    var activeLogRunId by remember { mutableStateOf<Long?>(null) }
    var activeGlossaryProjectId by remember { mutableStateOf<Long?>(null) }

    // Books that have at least one translation edition or translation project
    val booksWithTranslations = remember(allBooks, allEditions, allProjects) {
        allBooks.filter { book ->
            val hasTranslationEdition = allEditions.any { it.bookId == book.id && it.type != EditionType.IMPORTED.name }
            val hasTranslationProject = allProjects.any { it.bookId == book.id }
            hasTranslationEdition || hasTranslationProject
        }
    }

    // Computed overview statistics
    val totalTranslationBooksCount = booksWithTranslations.size
    val runningRunsCount = allRuns.count { it.state == "RUNNING" }
    val pausedRunsCount = allRuns.count { it.state == "PAUSED" }
    val completedRunsCount = allRuns.count {
        it.state in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_ERRORS")
    }

    // Filtered books
    val filteredBooks = remember(booksWithTranslations, allProjects, allRuns, searchQuery, filterStatus) {
        booksWithTranslations.filter { book ->
            val matchesSearch = searchQuery.isBlank() ||
                    book.title.contains(searchQuery, ignoreCase = true) ||
                    book.author.contains(searchQuery, ignoreCase = true)

            if (!matchesSearch) return@filter false

            val bookProjects = allProjects.filter { it.bookId == book.id }
            val bookRuns = allRuns.filter { it.bookId == book.id }

            when (filterStatus) {
                "RUNNING" -> bookRuns.any { it.state == "RUNNING" } || bookProjects.any { it.state == "RUNNING" }
                "PAUSED" -> bookRuns.any { it.state == "PAUSED" } || bookProjects.any { it.state == "PAUSED" }
                "COMPLETED" -> (bookRuns.isNotEmpty() || bookProjects.isNotEmpty()) &&
                        (bookRuns.any { it.state in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_ERRORS") } ||
                            bookProjects.any { it.state in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_ERRORS") }) &&
                        bookRuns.none { it.state in setOf("RUNNING", "PAUSED") } &&
                        bookProjects.none { it.state in setOf("RUNNING", "PAUSED") }
                else -> true
            }
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to initial book if needed
    LaunchedEffect(initialBookId, filteredBooks) {
        if (initialBookId != null && initialBookId > 0) {
            val targetIdx = filteredBooks.indexOfFirst { it.id == initialBookId }
            if (targetIdx >= 0) {
                listState.animateScrollToItem(targetIdx)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                strings.workbench,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (runningRunsCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier
                                                .size(6.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$runningRunsCount 个进行中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "以书籍为单位管理翻译项目与任务队列",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "搜索书籍"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = if (window.isCompact) 16.dp else 24.dp,
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Search Bar (if toggled)
            if (showSearchBar) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索书名、作者...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, "清空")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                    )
                }
            }

            // 2. Filter Chips Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 840.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = filterStatus == "ALL",
                        onClick = { filterStatus = "ALL" },
                        label = { Text("全部 ($totalTranslationBooksCount)", maxLines = 1) }
                    )
                    FilterChip(
                        selected = filterStatus == "RUNNING",
                        onClick = { filterStatus = "RUNNING" },
                        label = { Text("翻译中 ($runningRunsCount)", maxLines = 1) },
                        leadingIcon = if (filterStatus == "RUNNING") {
                            { Icon(Icons.Default.Sync, null, Modifier.size(14.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = filterStatus == "PAUSED",
                        onClick = { filterStatus = "PAUSED" },
                        label = { Text("已暂停 ($pausedRunsCount)", maxLines = 1) }
                    )
                    FilterChip(
                        selected = filterStatus == "COMPLETED",
                        onClick = { filterStatus = "COMPLETED" },
                        label = { Text("已完成 ($completedRunsCount)", maxLines = 1) }
                    )
                }
            }

            // 3. Books List (Books as Units with Expandable Tasks)
            if (filteredBooks.isEmpty()) {
                item {
                    EmptyWorkspaceState(
                        hasAnyBooks = booksWithTranslations.isNotEmpty(),
                        strings = strings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                    )
                }
            } else {
                items(filteredBooks, key = { it.id }) { book ->
                    val bookProjects = allProjects.filter { it.bookId == book.id }
                    val bookRuns = allRuns.filter { it.bookId == book.id }
                    val isExpanded = expandedBookIds[book.id] == true

                    val bookChapters by viewModel.bookPlatformRepo.observeChapters(book.id).collectAsState(initial = emptyList())

                    BookWorkspaceUnitCard(
                        book = book,
                        projects = bookProjects,
                        runs = bookRuns,
                        totalChaptersCount = bookChapters.size,
                        isExpanded = isExpanded,
                        strings = strings,
                        viewModel = viewModel,
                        onToggleExpand = {
                            expandedBookIds[book.id] = !isExpanded
                        },
                        onOpenWorkbench = { onOpenBookWorkbench(book.id) },
                        onOpenBook = { onOpenBook(book.id) },
                        onCreateTranslation = { createEditionForBook = book },
                        onOpenReader = { chapterId -> onOpenReader(book.id, chapterId) },
                        onOpenEdition = { editionId -> onOpenEdition(book.id, editionId) },
                        onViewLogs = { runId -> activeLogRunId = runId },
                        onViewGlossary = { projId -> activeGlossaryProjectId = projId },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                    )
                }
            }
        }
    }

    // Dialog: Create Translation for selected book
    createEditionForBook?.let { book ->
        val editions by viewModel.bookPlatformRepo.observeEditions(book.id).collectAsState(initial = emptyList())
        if (editions.isNotEmpty()) {
            CreateTranslationEditionDialog(
                bookTitle = book.title,
                editions = editions,
                strings = strings,
                onDismiss = { createEditionForBook = null },
                onCreate = { sourceEdition, targetLanguage, editionName ->
                    viewModel.createTranslationEdition(book.id, sourceEdition.id, targetLanguage, editionName) {
                        expandedBookIds[book.id] = true
                    }
                    createEditionForBook = null
                }
            )
        }
    }

    // Dialog: Live Logs modal
    activeLogRunId?.let { runId ->
        LiveLogDialog(
            runId = runId,
            currency = allRuns.firstOrNull { it.id == runId }?.currency ?: "USD",
            strings = strings,
            viewModel = viewModel,
            onDismiss = { activeLogRunId = null }
        )
    }

    // Dialog: Glossary & Story Memory modal
    activeGlossaryProjectId?.let { projId ->
        GlossaryMemoryDialog(
            projectId = projId,
            strings = strings,
            viewModel = viewModel,
            onDismiss = { activeGlossaryProjectId = null }
        )
    }
}

/**
 * 以书籍为大单位的主卡片（支持展开下属任务）
 */
@Composable
private fun BookWorkspaceUnitCard(
    book: BookEntity,
    projects: List<TranslationProjectV2Entity>,
    runs: List<PlatformTranslationRunEntity>,
    totalChaptersCount: Int,
    isExpanded: Boolean,
    strings: PlatformUiStrings,
    viewModel: AppViewModel,
    onToggleExpand: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenBook: () -> Unit,
    onCreateTranslation: () -> Unit,
    onOpenReader: (Long?) -> Unit,
    onOpenEdition: (Long) -> Unit,
    onViewLogs: (Long) -> Unit,
    onViewGlossary: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val cover by rememberAsyncBookImage(book.coverPath, maxDimension = 320)

    val isRunning = runs.any { it.state == "RUNNING" } || projects.any { it.state == "RUNNING" }
    val isPaused = runs.any { it.state == "PAUSED" } || projects.any { it.state == "PAUSED" }
    val isCompleted = (runs.isNotEmpty() || projects.isNotEmpty()) &&
            (projects.any { it.state in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_ERRORS") } ||
                runs.any { it.state in setOf("COMPLETED", "SUCCESS", "COMPLETED_WITH_ERRORS") }) &&
            projects.none { it.state in setOf("RUNNING", "PAUSED") } &&
            runs.none { it.state in setOf("RUNNING", "PAUSED") }

    val taskCount = projects.size.coerceAtLeast(runs.size)
    val orphanedRuns = runs.filter { run -> projects.none { it.id == run.translationProjectId } }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // --- 1. Book Header Row ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Book Cover Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        Image(
                            cover!!,
                            book.title,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            book.title.take(2),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Book Metadata & Status
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Status Badge
                        when {
                            isRunning -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Sync,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "翻译中",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            isPaused -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFFFF3E0)
                                ) {
                                    Text(
                                        "已暂停",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100)
                                    )
                                }
                            }
                            isCompleted -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE8F5E9)
                                ) {
                                    Text(
                                        "已完成",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                            taskCount == 0 -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "未开始",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            else -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "就绪",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            book.author.takeUnless { it.isBlank() || it == "Unknown" } ?: "未知作者",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            book.originalLanguage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        "$taskCount 个翻译版本与任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Expand / Collapse Chevron
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起任务" else "展开任务",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Actions Bar under Book Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onOpenWorkbench,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Dashboard, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("工作台", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = onOpenBook,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("阅读", fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = onCreateTranslation,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建译本", fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = onToggleExpand,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (isExpanded) "收起任务" else "展开任务 ($taskCount)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // --- 2. Expandable Task / Project Details Panel (书籍下展开任务) ---
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "下属翻译任务与进度",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (projects.isNotEmpty()) {
                            Text(
                                "共 ${projects.size} 个项目",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (projects.isEmpty() && runs.isEmpty()) {
                        // Empty tasks state for this book
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                                Text(
                                    "暂无翻译任务与译本",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "点击下方按钮，基于原版章节创建 AI 批处理翻译任务",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = onCreateTranslation) {
                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("创建此书的翻译任务")
                                }
                            }
                        }
                    } else {
                        // Keep the potentially long project/run list lazy and bounded. This card
                        // itself lives inside the workspace LazyColumn, so the nested list gets a
                        // finite viewport instead of composing every historical run at once.
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(projects, key = { "project-${it.id}" }) { project ->
                                val projectRun = runs.firstOrNull { it.translationProjectId == project.id }
                                TranslationProjectTaskItem(
                                    project = project,
                                    run = projectRun,
                                    totalChapters = totalChaptersCount,
                                    strings = strings,
                                    viewModel = viewModel,
                                    onOpenEdition = { onOpenEdition(project.targetEditionId) },
                                    onOpenReader = {
                                        viewModel.selectReadingEdition(book.id, project.targetEditionId) {
                                            onOpenReader(null)
                                        }
                                    },
                                    onViewLogs = { projectRun?.id?.let(onViewLogs) },
                                    onViewGlossary = { onViewGlossary(project.id) }
                                )
                            }
                            items(orphanedRuns, key = { "run-${it.id}" }) { orphanedRun ->
                                OrphanedTaskRunItem(
                                    run = orphanedRun,
                                    strings = strings,
                                    onViewLogs = { onViewLogs(orphanedRun.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个翻译任务/项目卡片
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationProjectTaskItem(
    project: TranslationProjectV2Entity,
    run: PlatformTranslationRunEntity?,
    totalChapters: Int,
    strings: PlatformUiStrings,
    viewModel: AppViewModel,
    onOpenEdition: () -> Unit,
    onOpenReader: () -> Unit,
    onViewLogs: () -> Unit,
    onViewGlossary: () -> Unit
) {
    val currentState = run?.state ?: project.state
    val completedChapters = run?.completedChapters ?: 0
    val failedChapters = run?.failedChapters ?: 0
    val progressFraction = if (totalChapters > 0) {
        (completedChapters.toFloat() / totalChapters).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Task Target Language & Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "${project.sourceLanguage} → ${project.targetLanguage}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text(project.modelName, fontSize = 10.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                }

                AssistChip(
                    onClick = {},
                    label = { Text(localizedState(currentState, strings), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            statusIcon(currentState),
                            null,
                            tint = statusColor(currentState),
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    modifier = Modifier.height(26.dp)
                )
            }

            // Progress Bar & Info
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (totalChapters > 0) "已完成 $completedChapters / $totalChapters 章" else "等待开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (totalChapters > 0) {
                        Text(
                            "${(progressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (currentState == "RUNNING") {
                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { if (currentState in setOf("COMPLETED", "SUCCESS")) 1f else progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = statusColor(currentState)
                    )
                }
            }

            // Token count & Error info
            if (run != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${TokenCalculator.formatTokenCount(run.promptTokens + run.completionTokens)} Tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (failedChapters > 0) {
                        Text(
                            "$failedChapters 章失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!run.lastError.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "异常: ${run.lastError}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary actions: logs, glossary
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (run != null) {
                        OutlinedButton(
                            onClick = onViewLogs,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Terminal, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("日志", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onViewGlossary,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Spellcheck, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("术语与记忆", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenReader,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("阅读译文", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenEdition,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("译本详情", fontSize = 12.sp)
                    }
                }

                // Primary state control buttons
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (currentState) {
                        "RUNNING" -> {
                            FilledTonalButton(
                                onClick = { viewModel.pauseBookTranslation(project.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Pause, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.pause, fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = { viewModel.cancelBookTranslation(project.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Stop, strings.stop, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                        "PAUSED" -> {
                            Button(
                                onClick = { viewModel.resumeBookTranslation(project.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.resume, fontSize = 12.sp)
                            }
                        }
                        else -> {
                            Button(
                                onClick = { viewModel.runBookTranslation(project.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.start, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrphanedTaskRunItem(
    run: PlatformTranslationRunEntity,
    strings: PlatformUiStrings,
    onViewLogs: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "任务 #${run.id} · ${run.providerName}/${run.modelName} · Prompt v${run.promptProfileVersion}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                AssistChip(
                    onClick = {},
                    label = { Text(localizedState(run.state, strings), fontSize = 11.sp) }
                )
            }
            Text(
                "已完成 ${run.completedChapters} 章 · ${TokenCalculator.formatTokenCount(run.promptTokens + run.completionTokens)} Tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onViewLogs,
                modifier = Modifier.align(Alignment.End).height(30.dp)
            ) {
                Text("查看日志", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceState(
    hasAnyBooks: Boolean,
    strings: PlatformUiStrings,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Text(
                if (hasAnyBooks) "没有符合筛选条件的书籍" else strings.emptyShelfTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (hasAnyBooks) "尝试切换状态筛选或搜索关键词" else strings.emptyShelfDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 实时日志弹窗
 */
@Composable
private fun LiveLogDialog(
    runId: Long,
    currency: String,
    strings: PlatformUiStrings,
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val logs by remember(runId) { viewModel.observePlatformRequestLogs(runId) }.collectAsState(initial = emptyList())
    val batches by remember(runId) { viewModel.observePlatformTaskBatches(runId) }.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) } // 0: 日志, 1: 批次

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("任务 #$runId 详细日志与批次", fontWeight = FontWeight.Bold)
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("请求日志 (${logs.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("批次分段 (${batches.size})") }
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 460.dp)
            ) {
                if (selectedTab == 0) {
                    if (logs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(strings.noRequestLogs, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(logs.take(40), key = { it.id }) { log ->
                                RequestLogItem(log, strings)
                            }
                        }
                    }
                } else {
                    if (batches.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无批次数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(batches, key = { it.id }) { batch ->
                                BatchItem(batch, strings, currency)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun RequestLogItem(log: PlatformRequestLogSummary, strings: PlatformUiStrings) {
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val errorText = listOfNotNull(log.errorCategory, log.errorMessage).filter { it.isNotBlank() }.joinToString(": ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                if (log.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                null,
                tint = if (log.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        log.operation,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatter.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${strings.attempt(log.attemptCount)} · ${strings.duration(log.durationMs)} · ${TokenCalculator.formatTokenCount(log.promptTokens + log.completionTokens)} Tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorText.isNotBlank()) {
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BatchItem(batch: PlatformTranslationBatchEntity, strings: PlatformUiStrings, currency: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val batchLabel = if (batch.batchIndex >= 1_000_000) "二次审校" else "#${batch.batchIndex}"
                Text(
                    "$batchLabel · ${strings.chapterNumber(batch.firstChapterIndex)}–${batch.lastChapterIndex}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    localizedState(batch.state, strings),
                    color = statusColor(batch.state),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${TokenCalculator.formatTokenCount(batch.promptTokens + batch.completionTokens)} Tokens · ${TokenCalculator.formatCost(batch.cost, currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val err = batch.errorMessage
            if (!err.isNullOrBlank()) {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Run costs are recorded in the provider currency. Never add values from different currencies
 * together; show one converted-looking total per currency until an exchange-rate service exists.
 */
private fun formatRunCostSummary(runs: List<PlatformTranslationRunEntity>): String {
    val totalsByCurrency = runs
        .groupBy { it.currency.trim().uppercase(Locale.ROOT).ifBlank { "UNKNOWN" } }
        .mapValues { (_, rows) -> rows.sumOf { it.totalCost } }
        .toSortedMap()
    if (totalsByCurrency.isEmpty()) return TokenCalculator.formatCost(0.0, "USD")
    return totalsByCurrency.entries.joinToString(" · ") { (currency, amount) ->
        TokenCalculator.formatCost(amount, currency)
    }
}

/**
 * 术语库与记忆弹窗
 */
@Composable
private fun GlossaryMemoryDialog(
    projectId: Long,
    strings: PlatformUiStrings,
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val allLexicon by remember(projectId) { viewModel.bookPlatformRepo.observeLexicon(projectId) }.collectAsState(initial = emptyList())
    val lexicon = allLexicon.filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
    val storyMemory by remember(projectId) { viewModel.bookPlatformRepo.observeStoryMemory(projectId) }.collectAsState(initial = emptyList())
    val chapterMemory by remember(projectId) { viewModel.bookPlatformRepo.observeChapterMemory(projectId) }.collectAsState(initial = emptyList())

    var tab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("项目术语库与故事记忆", fontWeight = FontWeight.Bold)
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("术语表 (${lexicon.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("章节记忆 (${chapterMemory.size})") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("设定事实 (${storyMemory.size})") })
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 460.dp)
            ) {
                when (tab) {
                    0 -> {
                        if (lexicon.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(strings.noGlossary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(lexicon, key = { it.id }) { item ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text("${item.sourceTerm} → ${item.targetTerm}", fontWeight = FontWeight.SemiBold)
                                            if (item.notes.isNotBlank()) {
                                                Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        if (chapterMemory.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无章节摘要记忆", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(chapterMemory, key = { it.id }) { mem ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(strings.chapterNumber(mem.chapterIndex), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Text(mem.summary, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        if (storyMemory.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无故事设定记忆", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(storyMemory, key = { it.id }) { fact ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(fact.factKey, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text(fact.factValue, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 创建翻译版本弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTranslationEditionDialog(
    bookTitle: String,
    editions: List<EditionEntity>,
    strings: PlatformUiStrings,
    onDismiss: () -> Unit,
    onCreate: (EditionEntity, String, String) -> Unit
) {
    var source by remember { mutableStateOf(editions.first()) }
    var language by remember { mutableStateOf("Chinese") }
    var name by remember { mutableStateOf(if (strings.bookshelf == "Bookshelf") "English translation" else "中文译本") }
    var sourceMenu by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("为《$bookTitle》创建翻译", fontWeight = FontWeight.Bold)
                Text("基于已有源版本建立新的译本与翻译项目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1. Source edition selector
                ExposedDropdownMenuBox(
                    expanded = sourceMenu,
                    onExpandedChange = { sourceMenu = it }
                ) {
                    OutlinedTextField(
                        value = source.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.sourceEdition) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceMenu) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = sourceMenu,
                        onDismissRequest = { sourceMenu = false }
                    ) {
                        editions.forEach {
                            DropdownMenuItem(
                                text = { Text(it.name) },
                                onClick = { source = it; sourceMenu = false }
                            )
                        }
                    }
                }

                // 2. Edition Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.editionName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. Target Language with scrollable dropdown
                ExposedDropdownMenuBox(
                    expanded = langMenu,
                    onExpandedChange = { langMenu = it }
                ) {
                    OutlinedTextField(
                        value = language,
                        onValueChange = { language = it },
                        label = { Text(strings.targetLanguage) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langMenu) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = langMenu,
                        onDismissRequest = { langMenu = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        TARGET_LANGUAGE_OPTIONS.forEach { opt ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(opt.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(opt.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    language = opt.code
                                    val isZh = strings.bookshelf != "Bookshelf"
                                    name = if (isZh) opt.defaultNameZh else opt.defaultNameEn
                                    langMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(source, language.trim().ifBlank { "Chinese" }, name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(strings.create)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

private fun statusColor(state: String): Color = when (state) {
    "FAILED", "ERROR", "COMPLETED_WITH_ERRORS" -> Color(0xFFD32F2F)
    "RUNNING" -> Color(0xFF1976D2)
    "COMPLETED", "SUCCESS" -> Color(0xFF2E7D32)
    "PAUSED" -> Color(0xFFE65100)
    else -> Color(0xFF757575)
}

private fun statusIcon(state: String): androidx.compose.ui.graphics.vector.ImageVector = when (state) {
    "FAILED", "ERROR", "COMPLETED_WITH_ERRORS" -> Icons.Default.Error
    "RUNNING" -> Icons.Default.Sync
    "COMPLETED", "SUCCESS" -> Icons.Default.CheckCircle
    "PAUSED" -> Icons.Default.PauseCircle
    else -> Icons.Default.Schedule
}

private fun localizedState(state: String, strings: PlatformUiStrings): String {
    val english = strings.bookshelf == "Bookshelf"
    return if (english) state.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar(Char::uppercase)
    else when (state) {
        "QUEUED", "PENDING" -> "等待中"
        "RUNNING" -> "运行中"
        "PAUSED" -> "已暂停"
        "COMPLETED", "SUCCESS" -> "已完成"
        "COMPLETED_WITH_ERRORS" -> "完成但有错误"
        "FAILED", "ERROR" -> "失败"
        "CANCELLED" -> "已取消"
        "INTERRUPTED" -> "已中断"
        else -> state
    }
}
