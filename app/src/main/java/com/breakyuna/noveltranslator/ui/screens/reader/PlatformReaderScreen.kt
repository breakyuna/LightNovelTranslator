package com.breakyuna.noveltranslator.ui.screens.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.model.DisplayMode as ReaderDisplayMode
import com.breakyuna.noveltranslator.data.repository.ResolvedReaderSegment
import com.breakyuna.noveltranslator.ui.components.ReaderRichContent
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun PlatformReaderScreen(
    bookId: Long,
    initialLogicalChapterId: Long? = null,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    val resolvedContent by viewModel.bookPlatformRepo.observeReader(bookId).collectAsState(initial = emptyList())
    val stored by viewModel.bookPlatformRepo.observeProgress(bookId).collectAsState(initial = null)
    val chapters by viewModel.bookPlatformRepo.observeChapters(bookId).collectAsState(initial = emptyList())
    val projects by viewModel.bookPlatformRepo.observeTranslationProjects(bookId).collectAsState(initial = emptyList())
    val window = rememberWindowSize()
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var displayMode by rememberSaveable { mutableStateOf(ReaderDisplayMode.TRANSLATION) }
    var pagingMode by rememberSaveable { mutableStateOf(PagingMode.CONTINUOUS) }
    var layoutMode by rememberSaveable { mutableStateOf(ReaderLayoutMode.CLEAN) }
    var animation by rememberSaveable { mutableStateOf(PageAnimation.SLIDE) }
    val listState = rememberLazyListState()
    val imageDirectory = remember(bookId) { viewModel.bookImagesDir(bookId) }
    var content by remember { mutableStateOf<List<ResolvedReaderSegment>>(emptyList()) }
    var requestedLogicalChapter by remember { mutableStateOf<Long?>(null) }
    val targetLogical = initialLogicalChapterId ?: stored?.logicalChapterId

    LaunchedEffect(resolvedContent, listState.isScrollInProgress) {
        // Keep the visible logical anchor stable while the user is actively dragging.
        if (!listState.isScrollInProgress) content = resolvedContent
    }

    LaunchedEffect(stored?.updatedAt) {
        stored?.let {
            displayMode = runCatching { ReaderDisplayMode.valueOf(it.displayMode) }.getOrDefault(ReaderDisplayMode.TRANSLATION)
            pagingMode = runCatching { PagingMode.valueOf(it.pagingMode) }.getOrDefault(PagingMode.CONTINUOUS)
            layoutMode = runCatching { ReaderLayoutMode.valueOf(it.readerLayoutMode) }.getOrDefault(ReaderLayoutMode.CLEAN)
            animation = runCatching { PageAnimation.valueOf(it.pageAnimation) }.getOrDefault(PageAnimation.SLIDE)
        }
    }
    LaunchedEffect(controlsVisible, layoutMode) {
        if (controlsVisible && layoutMode == ReaderLayoutMode.CLEAN) {
            delay(2_500)
            if (!listState.isScrollInProgress) controlsVisible = false
        }
    }
    LaunchedEffect(content.size, targetLogical, pagingMode, requestedLogicalChapter) {
        if (pagingMode == PagingMode.CONTINUOUS && content.isNotEmpty()) {
            val requested = requestedLogicalChapter
            val index = if (requested != null) {
                content.indexOfFirst { it.logicalChapterId == requested }
            } else if (initialLogicalChapterId != null) {
                content.indexOfFirst { it.logicalChapterId == initialLogicalChapterId }
            } else {
                content.indexOfFirst { it.logicalSegmentId == stored?.logicalSegmentId || it.logicalChapterId == stored?.logicalChapterId }
            }.coerceAtLeast(0)
            if (!listState.isScrollInProgress) listState.scrollToItem(index, stored?.segmentOffset ?: 0)
            requestedLogicalChapter = null
        }
    }
    LaunchedEffect(listState, content, displayMode, pagingMode, layoutMode, animation) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(400).collect { (index, offset) ->
                content.getOrNull(index)?.let { row ->
                    viewModel.saveReaderProgress(
                        ReaderProgressEntity(
                            bookId = bookId,
                            preferredEditionId = stored?.preferredEditionId,
                            logicalChapterId = row.logicalChapterId,
                            logicalSegmentId = row.logicalSegmentId,
                            segmentOffset = offset,
                            displayMode = displayMode.name,
                            pagingMode = pagingMode.name,
                            readerLayoutMode = layoutMode.name,
                            pageAnimation = animation.name
                        )
                    )
                }
            }
    }

    Row(Modifier.fillMaxSize()) {
        if (layoutMode == ReaderLayoutMode.WORKBENCH && !window.isCompact) {
            ReaderWorkbenchSidebar(
                chapters = chapters,
                projects = projects.filter { it.targetEditionId == stored?.preferredEditionId },
                onRun = viewModel::runBookTranslation,
                onPause = viewModel::pauseBookTranslation,
                onResume = viewModel::resumeBookTranslation,
                onCancel = viewModel::cancelBookTranslation,
                onChapter = {
                    pagingMode = PagingMode.CONTINUOUS
                    requestedLogicalChapter = it
                }
            )
            VerticalDivider()
        }
        Box(Modifier.weight(1f).fillMaxHeight().clickable { controlsVisible = !controlsVisible }) {
        when (pagingMode) {
            PagingMode.CONTINUOUS -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = if (controlsVisible) 72.dp else 28.dp, bottom = 80.dp, start = 18.dp, end = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(content.size, key = { content[it].logicalSegmentId }) { index ->
                    val previous = content.getOrNull(index - 1)
                    ReaderUnit(
                        row = content[index],
                        showChapterTitle = previous?.logicalChapterId != content[index].logicalChapterId,
                        displayMode = displayMode,
                        expanded = window.isExpanded,
                        workbench = layoutMode == ReaderLayoutMode.WORKBENCH,
                        imageDirectory = imageDirectory,
                        onSave = { viewModel.saveManualRevision(content[index].editionSegmentId, it) }
                    )
                }
            }
            PagingMode.HORIZONTAL, PagingMode.VERTICAL -> {
                if (content.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (english) "No readable content" else "当前没有可读内容") }
                } else {
                val initialPage = if (initialLogicalChapterId != null) {
                    content.indexOfFirst { it.logicalChapterId == initialLogicalChapterId }
                } else {
                    content.indexOfFirst { it.logicalSegmentId == stored?.logicalSegmentId || it.logicalChapterId == stored?.logicalChapterId }
                }.coerceAtLeast(0)
                val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { content.size })
                LaunchedEffect(pagerState, content, displayMode, pagingMode, layoutMode, animation) {
                    snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
                        content.getOrNull(page)?.let { row ->
                            viewModel.saveReaderProgress(
                                ReaderProgressEntity(bookId, stored?.preferredEditionId, row.logicalChapterId, row.logicalSegmentId, 0, displayMode.name, pagingMode.name, layoutMode.name, animation.name)
                            )
                        }
                    }
                }
                val page: @Composable (Int) -> Unit = { index ->
                    Box(
                        Modifier.fillMaxSize().padding(top = 64.dp, bottom = 56.dp, start = 26.dp, end = 26.dp)
                            .graphicsLayer {
                                val offset = kotlin.math.abs((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction)
                                when (animation) {
                                    PageAnimation.NONE -> Unit
                                    PageAnimation.SLIDE -> Unit
                                    PageAnimation.FADE -> alpha = (1f - offset).coerceIn(.25f, 1f)
                                    PageAnimation.CURL -> rotationY = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction) * 18f
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        content.getOrNull(index)?.let { ReaderUnit(it, true, displayMode, window.isExpanded, layoutMode == ReaderLayoutMode.WORKBENCH, imageDirectory) { text -> viewModel.saveManualRevision(it.editionSegmentId, text) } }
                    }
                }
                if (pagingMode == PagingMode.HORIZONTAL) {
                    HorizontalPager(state = pagerState) { index -> page(index) }
                } else {
                    VerticalPager(state = pagerState) { index -> page(index) }
                }
                }
            }
        }
        if (controlsVisible) {
            TopAppBar(
                title = { Text(content.getOrNull(listState.firstVisibleItemIndex)?.chapterTitle ?: if (english) "Reader" else "阅读") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back) } },
                actions = { IconButton(onClick = { settingsVisible = true }) { Icon(Icons.Default.Settings, if (english) "Reading settings" else "阅读设置") } },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        }
    }
    if (settingsVisible) {
        ModalBottomSheet(onDismissRequest = { settingsVisible = false }) {
            ReaderSettings(displayMode, { displayMode = it }, pagingMode, { pagingMode = it }, layoutMode, { layoutMode = it }, animation, { animation = it }, !window.isCompact)
        }
    }
}

@Composable
private fun ReaderWorkbenchSidebar(
    chapters: List<LogicalChapterEntity>,
    projects: List<TranslationProjectV2Entity>,
    onRun: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onChapter: (Long) -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    Column(Modifier.width(300.dp).fillMaxHeight().padding(16.dp)) {
        Text(if (english) "Translation controls" else "翻译操作", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (projects.isEmpty()) {
            Text(if (english) "Configure a translation task from the Edition details first." else "请先在 Edition 详情页配置翻译任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        } else {
            projects.take(3).forEach { project ->
                Text("${project.targetLanguage} · ${project.state}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (project.state) {
                        "RUNNING" -> TextButton(onClick = { onPause(project.id) }) { Text(strings.pause) }
                        "PAUSED" -> TextButton(onClick = { onResume(project.id) }) { Text(strings.resume) }
                        else -> TextButton(onClick = { onRun(project.id) }) { Icon(Icons.Default.PlayArrow, null); Text(strings.start) }
                    }
                    if (project.state in setOf("RUNNING", "PAUSED")) TextButton(onClick = { onCancel(project.id) }) { Text(strings.stop) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(strings.tableOfContents, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(chapters, key = { it.id }) { chapter ->
                ListItem(
                    modifier = Modifier.clickable { onChapter(chapter.id) },
                    headlineContent = { Text(chapter.canonicalTitle, maxLines = 2) },
                    supportingContent = { Text(strings.chapterNumber(chapter.chapterIndex)) }
                )
            }
        }
    }
}

@Composable
private fun ReaderUnit(
    row: ResolvedReaderSegment,
    showChapterTitle: Boolean,
    displayMode: ReaderDisplayMode,
    expanded: Boolean,
    workbench: Boolean,
    imageDirectory: java.io.File,
    onSave: (String) -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    Column(Modifier.widthIn(max = if (workbench && expanded) 1120.dp else 720.dp).fillMaxWidth().padding(vertical = 9.dp)) {
        if (showChapterTitle) {
            Text(row.chapterTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
        }
        when (displayMode) {
            ReaderDisplayMode.ORIGINAL -> ReaderRichContent(row.originalText, imageDirectory)
            ReaderDisplayMode.TRANSLATION -> {
                if (row.isFallback) Text(if (english) "Original · replaced automatically when translation is ready" else "原文 · 译文生成后将自动替换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                ReaderRichContent(row.displayText, imageDirectory)
            }
            ReaderDisplayMode.BILINGUAL -> if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { Text(if (english) "Original" else "原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); ReaderRichContent(row.originalText, imageDirectory) }
                    Column(Modifier.weight(1f)) { Text(if (english) "Translation" else "译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); ReaderRichContent(row.translatedText ?: if (english) "(Not translated yet)" else "（尚未翻译）", imageDirectory) }
                }
            } else {
                Text(if (english) "Original" else "原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); ReaderRichContent(row.originalText, imageDirectory)
                Spacer(Modifier.height(7.dp)); Text(if (english) "Translation" else "译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); ReaderRichContent(row.translatedText ?: if (english) "(Not translated yet)" else "（尚未翻译）", imageDirectory)
            }
            ReaderDisplayMode.QUICK_EDIT -> {
                if (row.translatedText == null) ReaderRichContent(if (english) "No translation for this segment yet. Showing original:\n${row.originalText}" else "当前段尚无译文，暂时显示原文：\n${row.originalText}", imageDirectory)
                else if (row.isCompositeMapping) ReaderRichContent(if (english) "This content maps to multiple segments. Revise each item in the translation workbench.\n\n${row.translatedText}" else "当前内容由多个 Segment 组合映射，请在翻译工作台中逐项修订。\n\n${row.translatedText}", imageDirectory)
                else QuickEditor(row.translatedText, onSave)
            }
        }
    }
}

@Composable
private fun QuickEditor(initial: String, onSave: (String) -> Unit) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    var value by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), minLines = 4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank() && value != initial) { Text(if (english) "Save revision" else "保存修订") } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettings(
    display: ReaderDisplayMode, onDisplay: (ReaderDisplayMode) -> Unit,
    paging: PagingMode, onPaging: (PagingMode) -> Unit,
    layout: ReaderLayoutMode, onLayout: (ReaderLayoutMode) -> Unit,
    animation: PageAnimation, onAnimation: (PageAnimation) -> Unit,
    workbenchAvailable: Boolean
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (english) "Reading settings" else "阅读设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (english) "Display mode" else "显示模式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReaderDisplayMode.entries.forEach { FilterChip(display == it, { onDisplay(it) }, { Text(when (it) { ReaderDisplayMode.TRANSLATION -> if (english) "Translation" else "译文"; ReaderDisplayMode.ORIGINAL -> if (english) "Original" else "原文"; ReaderDisplayMode.BILINGUAL -> if (english) "Bilingual" else "原译对照"; ReaderDisplayMode.QUICK_EDIT -> if (english) "Quick edit" else "快捷编辑" }) }) } }
        Text(if (english) "Paging" else "翻页方式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PagingMode.entries.forEach { FilterChip(paging == it, { onPaging(it) }, { Text(when (it) { PagingMode.CONTINUOUS -> if (english) "Continuous" else "连续滚动"; PagingMode.HORIZONTAL -> if (english) "Horizontal" else "左右分页"; PagingMode.VERTICAL -> if (english) "Vertical" else "上下分页" }) }) } }
        Text(if (english) "Page animation" else "翻页动画")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PageAnimation.entries.forEach { FilterChip(animation == it, { onAnimation(it) }, { Text(when (it) { PageAnimation.NONE -> if (english) "None" else "无"; PageAnimation.SLIDE -> if (english) "Slide" else "平移"; PageAnimation.FADE -> if (english) "Fade" else "淡入淡出"; PageAnimation.CURL -> if (english) "Curl" else "仿真" }) }) } }
        Text(if (english) "Layout" else "布局")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReaderLayoutMode.entries.forEach { item -> FilterChip(layout == item, { onLayout(item) }, { Text(when (item) { ReaderLayoutMode.CLEAN -> if (english) "Clean" else "纯净阅读"; ReaderLayoutMode.STANDARD -> if (english) "Standard" else "标准阅读"; ReaderLayoutMode.WORKBENCH -> strings.openWorkbench }) }, enabled = item != ReaderLayoutMode.WORKBENCH || workbenchAvailable) } }
        Spacer(Modifier.height(20.dp))
    }
}
