package com.breakyuna.noveltranslator.ui.screens.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.repository.ResolvedReaderSegment
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.components.ReaderRichContent
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditionDetailScreen(
    bookId: Long,
    editionId: Long,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onRead: (Long?) -> Unit,
    onDeleted: () -> Unit = {}
) {
    val strings = platformUiStrings()
    val edition by viewModel.bookPlatformRepo.observeEdition(editionId).collectAsState(initial = null)
    val chapters by viewModel.bookPlatformRepo.observeChapters(bookId).collectAsState(initial = emptyList())
    val preview by viewModel.bookPlatformRepo.observeEditionPreview(bookId, editionId).collectAsState(initial = emptyList())
    val projects by viewModel.bookPlatformRepo.observeTranslationProjectsForEdition(editionId).collectAsState(initial = emptyList())
    val providers by viewModel.allProviders.collectAsState()
    val window = rememberWindowSize()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showSetup by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val jumpToChapter: (Long) -> Unit = { chapterId ->
        val index = preview.indexOfFirst { it.logicalChapterId == chapterId }.coerceAtLeast(0)
        scope.launch {
            listState.animateScrollToItem(index)
            drawerState.close()
        }
    }
    val sidebar: @Composable () -> Unit = {
        EditionOperationSidebar(
            edition = edition,
            chapters = chapters,
            projects = projects,
            onConfigure = { showSetup = true },
            onRun = viewModel::runBookTranslation,
            onPause = viewModel::pauseBookTranslation,
            onResume = viewModel::resumeBookTranslation,
            onCancel = viewModel::cancelBookTranslation,
            onDelete = { showDeleteConfirmation = true },
            onRead = {
                viewModel.selectReadingEdition(bookId, editionId) { onRead(null) }
            },
            onChapter = jumpToChapter
        )
    }
    val previewPane: @Composable (Modifier) -> Unit = { modifier ->
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(edition?.name ?: "Edition", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            edition?.let { Text("${it.language} · ${if (it.isComplete) strings.done else strings.editionCreating}", style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back) } },
                    actions = {
                        if (window.isCompact) IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Tune, strings.openWorkbench) }
                    }
                )
            }
        ) { padding ->
            EditionPreview(
                rows = preview,
                imageDirectory = viewModel.bookImagesDir(bookId),
                modifier = Modifier.fillMaxSize().padding(padding),
                listState = listState
            )
        }
    }

    if (window.isCompact) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet(Modifier.widthIn(max = 340.dp)) { sidebar() } }
        ) { previewPane(Modifier.fillMaxSize()) }
    } else {
        Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(310.dp).fillMaxHeight(), tonalElevation = 2.dp) { sidebar() }
            VerticalDivider()
            previewPane(Modifier.weight(1f).fillMaxHeight())
        }
    }

    if (showSetup && edition?.sourceEditionId != null) {
        TranslationSetupDialog(
            providers = providers,
            chapterCount = chapters.size,
            onDismiss = { showSetup = false },
            onCreate = { provider, mode, batch, start, end, ahead ->
                viewModel.configureEditionTranslation(
                    bookId = bookId,
                    sourceEditionId = edition!!.sourceEditionId!!,
                    targetEditionId = editionId,
                    providerId = provider?.id,
                    modelName = provider?.selectedModel.orEmpty(),
                    mode = mode,
                    maxBatchChapters = batch,
                    rangeStart = start,
                    rangeEnd = end,
                    seamlessAheadChapters = ahead
                )
                showSetup = false
            }
        )
    }

    if (showDeleteConfirmation && edition != null && edition!!.type != EditionType.IMPORTED.name) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(if (strings.bookshelf == "Bookshelf") "Delete Edition" else "删除译本") },
            text = {
                Text(
                    if (strings.bookshelf == "Bookshelf") {
                        "Delete this Edition, its translation task, revisions and files? The original Edition will remain."
                    } else {
                        "确定删除此译本、翻译任务、修订记录和文件吗？原始版本不会受影响。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEdition(bookId, editionId) {
                            showDeleteConfirmation = false
                            onDeleted()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(if (strings.bookshelf == "Bookshelf") "Delete" else "删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text(if (strings.bookshelf == "Bookshelf") "Cancel" else "取消") } }
        )
    }
}

@Composable
private fun EditionPreview(
    rows: List<ResolvedReaderSegment>,
    imageDirectory: java.io.File,
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    if (rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (english) "This Edition has no generated content yet. Original text fallback will appear here." else "当前 Edition 尚无内容，原文回退内容将在此直接预览。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(rows, key = { _, row -> row.logicalSegmentId }) { index, row ->
            val previous = rows.getOrNull(index - 1)
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(vertical = 8.dp)) {
                if (previous?.logicalChapterId != row.logicalChapterId) {
                    Text(row.chapterTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
                }
                if (row.isFallback) Text(if (english) "Original fallback · replaced automatically when translation is ready" else "原文回退 · 译文生成后自动替换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                ReaderRichContent(row.displayText, imageDirectory)
            }
        }
    }
}

@Composable
private fun EditionOperationSidebar(
    edition: EditionEntity?,
    chapters: List<LogicalChapterEntity>,
    projects: List<TranslationProjectV2Entity>,
    onConfigure: () -> Unit,
    onRun: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: () -> Unit,
    onRead: () -> Unit,
    onChapter: (Long) -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(if (english) "Translation controls" else "翻译操作", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (edition?.sourceEditionId == null) {
            Text(if (english) "The original Edition does not require a translation task." else "原始 Edition 无需配置翻译任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddTask, null)
                Spacer(Modifier.width(7.dp))
                Text(if (projects.isEmpty()) { if (english) "Configure translation" else "配置翻译" } else { if (english) "Edit translation task" else "编辑翻译任务" })
            }
            projects.take(3).forEach { project ->
                Text("${project.translationMode} · ${project.state}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (project.state) {
                        "RUNNING" -> TextButton(onClick = { onPause(project.id) }) { Text(strings.pause) }
                        "PAUSED" -> TextButton(onClick = { onResume(project.id) }) { Text(strings.resume) }
                        else -> TextButton(onClick = { onRun(project.id) }) { Text(strings.start) }
                    }
                    if (project.state in setOf("RUNNING", "PAUSED")) TextButton(onClick = { onCancel(project.id) }) { Text(strings.stop) }
                }
            }
        }
        if (edition != null && edition.type != EditionType.IMPORTED.name) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteOutline, null)
                Spacer(Modifier.width(7.dp))
                Text(if (english) "Delete Edition" else "删除此译本")
            }
        }
        OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text(if (english) "Set as reading Edition and open" else "设为阅读版本并打开") }
        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text(strings.tableOfContents, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(chapters, key = { it.id }) { chapter ->
                ListItem(
                    modifier = Modifier.clickable { onChapter(chapter.id) },
                    headlineContent = { Text(chapter.canonicalTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(strings.chapterNumber(chapter.chapterIndex)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TranslationSetupDialog(
    providers: List<ApiProviderEntity>,
    chapterCount: Int,
    onDismiss: () -> Unit,
    onCreate: (ApiProviderEntity?, TranslationMode, Int, Int?, Int?, Int) -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    var provider by remember { mutableStateOf(providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()) }
    LaunchedEffect(providers) {
        provider = provider?.id?.let { id -> providers.firstOrNull { it.id == id } }
            ?: providers.firstOrNull { it.isDefault }
            ?: providers.firstOrNull()
    }
    var providerMenu by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(TranslationMode.FULL_BOOK) }
    var batch by remember { mutableIntStateOf(1) }
    var rangeStart by remember { mutableStateOf("1") }
    var rangeEnd by remember(chapterCount) { mutableStateOf(chapterCount.coerceAtLeast(1).toString()) }
    var ahead by remember { mutableIntStateOf(5) }
    val rangeStartValue = rangeStart.toIntOrNull()
    val rangeEndValue = rangeEnd.toIntOrNull()
    val rangeValid = mode != TranslationMode.CHAPTER_RANGE ||
        (rangeStartValue != null && rangeEndValue != null && rangeStartValue > 0 &&
            rangeEndValue >= rangeStartValue && rangeEndValue <= chapterCount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (english) "Configure Edition translation" else "配置 Edition 翻译") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(providerMenu, { providerMenu = it }) {
                    OutlinedTextField(provider?.name ?: if (english) "Select a provider" else "请选择 Provider", {}, readOnly = true, label = { Text("Provider") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(providerMenu, { providerMenu = false }) { providers.forEach { item -> DropdownMenuItem({ Text("${item.name} · ${item.selectedModel}") }, { provider = item; providerMenu = false }) } }
                }
                Text(if (english) "Translation mode" else "翻译模式")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationMode.entries.forEach { item -> FilterChip(mode == item, { mode = item }, { Text(when (item) { TranslationMode.FULL_BOOK -> if (english) "Full book" else "全书"; TranslationMode.CHAPTER_RANGE -> if (english) "Chapter range" else "指定范围"; TranslationMode.SEAMLESS -> if (english) "Seamless" else "无感翻译" }) }) }
                }
                if (mode == TranslationMode.CHAPTER_RANGE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rangeStart, { rangeStart = it.filter(Char::isDigit) }, label = { Text(if (english) "Start chapter" else "起始章") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(rangeEnd, { rangeEnd = it.filter(Char::isDigit) }, label = { Text(if (english) "End chapter" else "结束章") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (mode == TranslationMode.CHAPTER_RANGE && !rangeValid) {
                    Text(
                        if (english) "Enter a valid range within the $chapterCount available chapters." else "请输入有效范围，不能超过当前 $chapterCount 章。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (mode == TranslationMode.SEAMLESS) {
                    Text(if (english) "Buffer $ahead chapters ahead" else "提前缓冲 $ahead 章")
                    Slider(ahead.toFloat(), { ahead = it.toInt().coerceIn(1, 20) }, valueRange = 1f..20f, steps = 18)
                }
                Text(if (english) "Up to $batch chapters per batch" else "每批最多 $batch 章")
                Slider(batch.toFloat(), { batch = it.toInt().coerceIn(1, 5) }, valueRange = 1f..5f, steps = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(provider, mode, batch, rangeStartValue, rangeEndValue, ahead) }, enabled = provider != null && rangeValid) { Text(if (english) "Create and start" else "创建并启动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}
