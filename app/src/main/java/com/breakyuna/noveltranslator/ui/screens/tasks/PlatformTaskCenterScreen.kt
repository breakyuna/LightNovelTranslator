package com.breakyuna.noveltranslator.ui.screens.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformTaskCenterScreen(
    viewModel: AppViewModel,
    bookId: Long? = null,
    onBack: (() -> Unit)? = null,
    onOpenBookWorkbench: (Long) -> Unit = {}
) {
    val strings = platformUiStrings()
    val window = rememberWindowSize()
    val allBooks by viewModel.allPlatformBooks.collectAsState()
    val allRuns by viewModel.platformTaskRuns.collectAsState()
    val book = allBooks.firstOrNull { it.id == bookId }
    val runs = remember(allRuns, bookId) { if (bookId == null) allRuns else allRuns.filter { it.bookId == bookId } }
    val projectsFlow = remember(bookId) {
        bookId?.let(viewModel.bookPlatformRepo::observeTranslationProjects)
            ?: flowOf<List<TranslationProjectV2Entity>>(emptyList())
    }
    val projects by projectsFlow.collectAsState(initial = emptyList())
    var selectedProjectId by rememberSaveable(bookId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(projects) {
        if (selectedProjectId !in projects.map { it.id }) selectedProjectId = projects.firstOrNull()?.id
    }
    val lexicon by remember(selectedProjectId) {
        selectedProjectId?.let(viewModel.bookPlatformRepo::observeLexicon)
            ?: flowOf<List<LexiconEntryEntity>>(emptyList())
    }.collectAsState(initial = emptyList())
    val storyMemory by remember(selectedProjectId) {
        selectedProjectId?.let(viewModel.bookPlatformRepo::observeStoryMemory)
            ?: flowOf<List<StoryMemoryEntity>>(emptyList())
    }.collectAsState(initial = emptyList())
    val chapterMemory by remember(selectedProjectId) {
        selectedProjectId?.let(viewModel.bookPlatformRepo::observeChapterMemory)
            ?: flowOf<List<ChapterMemoryEntity>>(emptyList())
    }.collectAsState(initial = emptyList())
    var expandedRunId by rememberSaveable(bookId) { mutableStateOf<Long?>(runs.firstOrNull { it.state == "RUNNING" }?.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.workbench, fontWeight = FontWeight.SemiBold)
                        if (book != null) Text(book.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back) }
                }
            )
        }
    ) { padding ->
        if (bookId == null) {
            GlobalWorkbench(
                runs = runs,
                books = allBooks,
                strings = strings,
                viewModel = viewModel,
                expandedRunId = expandedRunId,
                onExpandRun = { expandedRunId = if (expandedRunId == it) null else it },
                onOpenBookWorkbench = onOpenBookWorkbench,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (window.isExpanded) {
            Row(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                LazyColumn(
                    Modifier.widthIn(min = 300.dp, max = 390.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item { WorkOverviewCard(book, projects, strings) }
                    if (projects.size > 1) item { ProjectSelector(projects, selectedProjectId, strings) { selectedProjectId = it } }
                    item { GlossaryCard(lexicon, strings) }
                    item { MemoryCard(storyMemory, chapterMemory, strings) }
                    item { ProjectsCard(projects, strings, viewModel) }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item { WorkbenchSectionTitle(Icons.Default.TaskAlt, strings.tasks, strings.selectTaskHint) }
                    if (runs.isEmpty()) item { EmptyTasks(strings) }
                    items(runs, key = { it.id }) { run ->
                        TaskRunCard(run, book?.title, strings, viewModel, expandedRunId == run.id) {
                            expandedRunId = if (expandedRunId == run.id) null else run.id
                        }
                    }
                    runs.firstOrNull()?.let { latest -> item { LiveLogPanel(latest.id, strings, viewModel) } }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { WorkOverviewCard(book, projects, strings) }
                if (projects.size > 1) item { ProjectSelector(projects, selectedProjectId, strings) { selectedProjectId = it } }
                item { GlossaryCard(lexicon, strings) }
                item { MemoryCard(storyMemory, chapterMemory, strings) }
                item { ProjectsCard(projects, strings, viewModel) }
                item { WorkbenchSectionTitle(Icons.Default.TaskAlt, strings.tasks, strings.selectTaskHint) }
                if (runs.isEmpty()) item { EmptyTasks(strings) }
                items(runs, key = { it.id }) { run ->
                    TaskRunCard(run, book?.title, strings, viewModel, expandedRunId == run.id) {
                        expandedRunId = if (expandedRunId == run.id) null else run.id
                    }
                }
                runs.firstOrNull()?.let { latest -> item { LiveLogPanel(latest.id, strings, viewModel) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectSelector(
    projects: List<TranslationProjectV2Entity>, selectedProjectId: Long?, strings: PlatformUiStrings,
    onSelect: (Long) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.translationProjects, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                projects.forEach { project ->
                    FilterChip(
                        selected = project.id == selectedProjectId,
                        onClick = { onSelect(project.id) },
                        label = { Text("${project.sourceLanguage} → ${project.targetLanguage}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalWorkbench(
    runs: List<PlatformTranslationRunEntity>, books: List<BookEntity>, strings: PlatformUiStrings,
    viewModel: AppViewModel, expandedRunId: Long?, onExpandRun: (Long) -> Unit,
    onOpenBookWorkbench: (Long) -> Unit, modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            WorkbenchSectionTitle(Icons.Default.DashboardCustomize, strings.workspaceOverview, strings.selectTaskHint, Modifier.widthIn(max = 960.dp).fillMaxWidth())
        }
        if (runs.isEmpty()) item { Box(Modifier.widthIn(max = 960.dp).fillMaxWidth()) { EmptyTasks(strings) } }
        items(runs, key = { it.id }) { run ->
            val title = books.firstOrNull { it.id == run.bookId }?.title
            Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskRunCard(run, title, strings, viewModel, expandedRunId == run.id) { onExpandRun(run.id) }
                TextButton(onClick = { onOpenBookWorkbench(run.bookId) }, modifier = Modifier.align(Alignment.End)) {
                    Text(strings.openBook)
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun WorkOverviewCard(book: BookEntity?, projects: List<TranslationProjectV2Entity>, strings: PlatformUiStrings) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Text(book?.title ?: strings.allBooks, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(book?.author?.takeUnless { it.isBlank() || it == "Unknown" } ?: strings.unknownAuthor, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${projects.size} · ${strings.translationProjects}") }, leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(16.dp)) })
                book?.originalLanguage?.let { SuggestionChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }
}

@Composable
private fun GlossaryCard(entries: List<LexiconEntryEntity>, strings: PlatformUiStrings) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchSectionTitle(Icons.Default.Spellcheck, strings.glossary, strings.glossaryCount(entries.size))
            if (entries.isEmpty()) Text(strings.noGlossary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.take(8).forEach {
                Column {
                    Text("${it.sourceTerm} → ${it.targetTerm}", fontWeight = FontWeight.Medium)
                    if (it.notes.isNotBlank()) Text(it.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(story: List<StoryMemoryEntity>, chapters: List<ChapterMemoryEntity>, strings: PlatformUiStrings) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            WorkbenchSectionTitle(Icons.Default.Psychology, strings.storySummary, strings.memoryCount(story.size + chapters.size))
            if (story.isEmpty() && chapters.isEmpty()) Text(strings.noMemory, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chapters.takeLast(3).reversed().forEach {
                Column {
                    Text(strings.chapterNumber(it.chapterIndex), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(it.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            story.take(4).forEach {
                Text("${it.factKey}: ${it.factValue}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProjectsCard(projects: List<TranslationProjectV2Entity>, strings: PlatformUiStrings, viewModel: AppViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchSectionTitle(Icons.Default.Build, strings.translationProjects, projects.size.toString())
            if (projects.isEmpty()) Text(strings.noProjects, color = MaterialTheme.colorScheme.onSurfaceVariant)
            projects.forEach { project ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${project.sourceLanguage} → ${project.targetLanguage}", fontWeight = FontWeight.SemiBold)
                        Text("${project.modelName} · ${localizedState(project.state, strings)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (project.state) {
                        "RUNNING" -> IconButton(onClick = { viewModel.pauseBookTranslation(project.id) }) { Icon(Icons.Default.Pause, strings.pause) }
                        "PAUSED" -> IconButton(onClick = { viewModel.resumeBookTranslation(project.id) }) { Icon(Icons.Default.PlayArrow, strings.resume) }
                        else -> IconButton(onClick = { viewModel.runBookTranslation(project.id) }) { Icon(Icons.Default.PlayArrow, strings.start) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRunCard(
    run: PlatformTranslationRunEntity, bookTitle: String?, strings: PlatformUiStrings,
    viewModel: AppViewModel, expanded: Boolean, onToggle: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIcon(run.state), null, tint = statusColor(run.state))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bookTitle ?: "Book #${run.bookId}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${run.providerName} · ${run.modelName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(onClick = onToggle, label = { Text(localizedState(run.state, strings)) })
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                when (run.state) {
                    "RUNNING" -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    "COMPLETED", "COMPLETED_WITH_ERRORS" -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                }
                Text(strings.completedFailed(run.completedChapters, run.failedChapters))
                Text(
                    "${TokenCalculator.formatTokenCount(run.promptTokens + run.completionTokens)} Tokens · ${TokenCalculator.formatCost(run.totalCost, run.currency)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!run.lastError.isNullOrBlank()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(strings.failureReason, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(run.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                if (run.state in setOf("RUNNING", "PAUSED")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            if (run.state == "PAUSED") viewModel.resumeBookTranslation(run.translationProjectId)
                            else viewModel.pauseBookTranslation(run.translationProjectId)
                        }) { Text(if (run.state == "PAUSED") strings.resume else strings.pause) }
                        TextButton(
                            onClick = { viewModel.cancelBookTranslation(run.translationProjectId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(strings.stop) }
                    }
                }
            }
            if (expanded) {
                HorizontalDivider()
                TaskDetails(run.id, strings, viewModel)
            }
        }
    }
}

@Composable
private fun TaskDetails(runId: Long, strings: PlatformUiStrings, viewModel: AppViewModel) {
    val batches by remember(runId) { viewModel.observePlatformTaskBatches(runId) }.collectAsState(initial = emptyList())
    val logs by remember(runId) { viewModel.observePlatformRequestLogs(runId) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.taskDetails, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(strings.batches, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        batches.forEach { batch ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        Text("#${batch.batchIndex} · ${strings.chapterNumber(batch.firstChapterIndex)}–${batch.lastChapterIndex}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(localizedState(batch.state, strings), color = statusColor(batch.state))
                    }
                    Text("${TokenCalculator.formatTokenCount(batch.promptTokens + batch.completionTokens)} Tokens · ${TokenCalculator.formatCost(batch.cost, "USD")}", style = MaterialTheme.typography.bodySmall)
                    batch.errorMessage?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text(strings.requests, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (logs.isEmpty()) Text(strings.noRequestLogs, color = MaterialTheme.colorScheme.onSurfaceVariant)
        logs.take(30).forEach { RequestLogRow(it, strings) }
    }
}

@Composable
private fun LiveLogPanel(runId: Long, strings: PlatformUiStrings, viewModel: AppViewModel) {
    val logs by remember(runId) { viewModel.observePlatformRequestLogs(runId) }.collectAsState(initial = emptyList())
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            WorkbenchSectionTitle(Icons.Default.Terminal, strings.liveLogs, strings.latestActivity)
            if (logs.isEmpty()) Text(strings.noRequestLogs, color = MaterialTheme.colorScheme.onSurfaceVariant)
            logs.take(12).forEach { RequestLogRow(it, strings) }
        }
    }
}

@Composable
private fun RequestLogRow(log: PlatformRequestLogEntity, strings: PlatformUiStrings) {
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val errorText = listOfNotNull(log.errorCategory, log.errorMessage).filter { it.isNotBlank() }.joinToString(": ")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(if (log.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (log.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(log.operation, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatter.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${strings.attempt(log.attemptCount)} · ${strings.duration(log.durationMs)} · ${TokenCalculator.formatTokenCount(log.promptTokens + log.completionTokens)} Tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else log.finishReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EmptyTasks(strings: PlatformUiStrings) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CloudDone, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .55f))
            Text(strings.noTasks, style = MaterialTheme.typography.titleMedium)
            Text(strings.noTasksDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkbenchSectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun statusColor(state: String) = when (state) {
    "FAILED", "ERROR", "COMPLETED_WITH_ERRORS" -> MaterialTheme.colorScheme.error
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "COMPLETED", "SUCCESS" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusIcon(state: String) = when (state) {
    "FAILED", "ERROR", "COMPLETED_WITH_ERRORS" -> Icons.Default.Error
    "RUNNING" -> Icons.Default.Sync
    "COMPLETED", "SUCCESS" -> Icons.Default.CheckCircle
    "PAUSED" -> Icons.Default.PauseCircle
    else -> Icons.Default.Schedule
}

private fun localizedState(state: String, strings: PlatformUiStrings): String {
    val english = strings.bookshelf == "Bookshelf"
    return if (english) state.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
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
