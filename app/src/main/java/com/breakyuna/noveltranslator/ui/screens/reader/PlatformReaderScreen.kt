package com.breakyuna.noveltranslator.ui.screens.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.breakyuna.noveltranslator.data.model.*
import com.breakyuna.noveltranslator.data.model.DisplayMode as ReaderDisplayMode
import com.breakyuna.noveltranslator.data.repository.ResolvedReaderSegment
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.components.ReaderRichContent
import com.breakyuna.noveltranslator.ui.components.ReaderTextStyle
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class ReaderPanel { NONE, DIRECTORY, SETTINGS }

private enum class ReaderPanelSide { LEFT, RIGHT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun PlatformReaderScreen(
    bookId: Long,
    initialLogicalChapterId: Long? = null,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenWorkbench: () -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    val resolvedContent by remember(bookId) { viewModel.bookPlatformRepo.observeReader(bookId) }
        .collectAsState(initial = emptyList())
    val stored by remember(bookId) { viewModel.bookPlatformRepo.observeProgress(bookId) }
        .collectAsState(initial = null)
    val chapters by remember(bookId) { viewModel.bookPlatformRepo.observeChapters(bookId) }
        .collectAsState(initial = emptyList())
    val projects by remember(bookId) { viewModel.bookPlatformRepo.observeTranslationProjects(bookId) }
        .collectAsState(initial = emptyList())
    val window = rememberWindowSize()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val imageDirectory = remember(bookId) { viewModel.bookImagesDir(bookId) }

    var controlsVisible by rememberSaveable(bookId) { mutableStateOf(true) }
    var activePanelName by rememberSaveable(bookId) { mutableStateOf(ReaderPanel.NONE.name) }
    var settings by remember(bookId) { mutableStateOf(ReaderSettingsState()) }
    var content by remember(bookId) { mutableStateOf<List<ResolvedReaderSegment>>(emptyList()) }
    var requestedLogicalChapter by remember(bookId) { mutableStateOf<Long?>(null) }
    var initialPositionApplied by remember(bookId) { mutableStateOf(false) }
    var storedPositionRestored by remember(bookId) { mutableStateOf(false) }
    var lastPagingMode by remember(bookId) { mutableStateOf<PagingMode?>(null) }
    var progressPreview by remember { mutableStateOf<Float?>(null) }
    var showResplitDialog by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { maxOf(1, content.size) })

    val activePanel = runCatching { ReaderPanel.valueOf(activePanelName) }.getOrDefault(ReaderPanel.NONE)
    val targetLogicalChapterId = initialLogicalChapterId ?: stored?.logicalChapterId

    LaunchedEffect(resolvedContent, listState.isScrollInProgress) {
        // Do not replace rows while the user is dragging through the continuous reader. Stable
        // segment keys keep a completed translation from moving the current viewport abruptly.
        if (!listState.isScrollInProgress) content = resolvedContent
    }

    LaunchedEffect(stored?.updatedAt) {
        stored?.let { settings = it.toReaderSettings() }
    }

    LaunchedEffect(content, targetLogicalChapterId, settings.pagingMode, requestedLogicalChapter, stored?.segmentOffset) {
        if (content.isEmpty()) return@LaunchedEffect
        val modeChanged = lastPagingMode != settings.pagingMode
        val requestedChapter = requestedLogicalChapter
        val savedSegmentId = stored?.logicalSegmentId
        val savedChapterId = stored?.logicalChapterId
        val needsStoredRestore = initialLogicalChapterId == null && stored != null && !storedPositionRestored
        if (requestedChapter == null && initialPositionApplied && !modeChanged && !needsStoredRestore) return@LaunchedEffect

        val index = when {
            requestedChapter != null -> content.indexOfFirst { it.logicalChapterId == requestedChapter }
                .takeIf { it >= 0 }
            initialLogicalChapterId != null -> content.indexOfFirst { it.logicalChapterId == initialLogicalChapterId }
                .takeIf { it >= 0 }
            savedSegmentId != null -> content.indexOfFirst { it.logicalSegmentId == savedSegmentId }
                .takeIf { it >= 0 }
            savedChapterId != null -> content.indexOfFirst { it.logicalChapterId == savedChapterId }
                .takeIf { it >= 0 }
            else -> null
        } ?: 0

        if (settings.pagingMode == PagingMode.CONTINUOUS) {
            val offset = if (requestedChapter != null || !initialPositionApplied) 0 else stored?.segmentOffset ?: 0
            if (!listState.isScrollInProgress) listState.scrollToItem(index, offset)
        } else if (pagerState.currentPage != index && index < content.size) {
            pagerState.scrollToPage(index)
        }

        requestedLogicalChapter = null
        initialPositionApplied = true
        lastPagingMode = settings.pagingMode
        if (initialLogicalChapterId == null && stored != null) storedPositionRestored = true
    }

    LaunchedEffect(bookId, listState, content, settings, stored?.preferredEditionId, initialPositionApplied) {
        if (!initialPositionApplied || settings.pagingMode != PagingMode.CONTINUOUS) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(350)
            .collect { (index, offset) ->
                content.getOrNull(index)?.let { row ->
                    viewModel.saveReaderProgress(
                        row.toProgress(
                            bookId = bookId,
                            preferredEditionId = stored?.preferredEditionId,
                            offset = offset,
                            settings = settings
                        )
                    )
                }
            }
    }

    LaunchedEffect(bookId, pagerState, content, settings, stored?.preferredEditionId, initialPositionApplied) {
        if (!initialPositionApplied || settings.pagingMode == PagingMode.CONTINUOUS) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                content.getOrNull(page)?.let { row ->
                    viewModel.saveReaderProgress(
                        row.toProgress(
                            bookId = bookId,
                            preferredEditionId = stored?.preferredEditionId,
                            offset = 0,
                            settings = settings
                        )
                    )
                }
            }
    }

    val visibleIndex = if (settings.pagingMode == PagingMode.CONTINUOUS) {
        listState.firstVisibleItemIndex
    } else {
        pagerState.currentPage
    }
    val visibleRow = content.getOrNull(visibleIndex)
    val currentChapterId = visibleRow?.logicalChapterId ?: targetLogicalChapterId
    val currentChapterPosition = chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
    val maxChapterPosition = chapters.lastIndex.coerceAtLeast(1)
    val readerBackground = when (settings.background) {
        ReaderBackground.SYSTEM -> MaterialTheme.colorScheme.background
        ReaderBackground.PAPER -> Color(0xFFFFF8E7)
        ReaderBackground.MINT -> Color(0xFFEAF4EE)
        ReaderBackground.NIGHT -> Color(0xFF121212)
    }
    val readerTextColor = when (settings.background) {
        ReaderBackground.SYSTEM -> MaterialTheme.colorScheme.onBackground
        ReaderBackground.PAPER -> Color(0xFF3D3529)
        ReaderBackground.MINT -> Color(0xFF20352A)
        ReaderBackground.NIGHT -> Color(0xFFE8E2E8)
    }

    BackHandler(enabled = activePanel != ReaderPanel.NONE) {
        activePanelName = ReaderPanel.NONE.name
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = readerBackground,
        contentColor = readerTextColor
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerWidth = maxWidth * (2f / 3f)

            Row(Modifier.fillMaxSize()) {
                if (settings.layoutMode == ReaderLayoutMode.WORKBENCH && !window.isCompact) {
                    ReaderWorkbenchSidebar(
                        chapters = chapters,
                        projects = projects.filter { it.targetEditionId == stored?.preferredEditionId },
                        onRun = viewModel::runBookTranslation,
                        onPause = viewModel::pauseBookTranslation,
                        onResume = viewModel::resumeBookTranslation,
                        onCancel = viewModel::cancelBookTranslation,
                        onChapter = { chapterId ->
                            settings = settings.copy(pagingMode = PagingMode.CONTINUOUS)
                            requestedLogicalChapter = chapterId
                            controlsVisible = true
                        }
                    )
                    VerticalDivider()
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(activePanel, controlsVisible, settings.pagingMode) {
                            detectTapGestures { tap ->
                                val topInset = 72.dp.toPx()
                                val bottomInset = if (controlsVisible) 152.dp.toPx() else 0f
                                if (activePanel == ReaderPanel.NONE && tap.y > topInset && tap.y < size.height - bottomInset) {
                                    controlsVisible = !controlsVisible
                                }
                            }
                        }
                ) {
                    when (settings.pagingMode) {
                        PagingMode.CONTINUOUS -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = if (controlsVisible) 76.dp else 28.dp,
                                bottom = if (controlsVisible) 156.dp else 28.dp,
                                start = settings.pageMarginDp.dp,
                                end = settings.pageMarginDp.dp
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(content, key = { _, row -> row.logicalSegmentId }) { index, row ->
                                val previous = content.getOrNull(index - 1)
                                ReaderUnit(
                                    row = row,
                                    showChapterTitle = previous?.logicalChapterId != row.logicalChapterId,
                                    displayMode = settings.displayMode,
                                    expanded = window.isExpanded,
                                    workbench = settings.layoutMode == ReaderLayoutMode.WORKBENCH,
                                    imageDirectory = imageDirectory,
                                    settings = settings,
                                    textColor = readerTextColor,
                                    onSave = { viewModel.saveManualRevision(row.editionSegmentId, it) }
                                )
                            }
                        }

                        PagingMode.HORIZONTAL, PagingMode.VERTICAL -> {
                            if (content.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (english) "No readable content" else "当前没有可读内容")
                                }
                            } else {
                                val page: @Composable (Int) -> Unit = { index ->
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(
                                                top = 70.dp,
                                                bottom = 64.dp,
                                                start = settings.pageMarginDp.dp,
                                                end = settings.pageMarginDp.dp
                                            )
                                            .graphicsLayer {
                                                val offset = kotlin.math.abs(
                                                    (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                                )
                                                when (settings.animation) {
                                                    PageAnimation.NONE, PageAnimation.SLIDE -> Unit
                                                    PageAnimation.FADE -> alpha = (1f - offset).coerceIn(.25f, 1f)
                                                    PageAnimation.CURL -> rotationY =
                                                        ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction) * 18f
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        content.getOrNull(index)?.let { row ->
                                            ReaderUnit(
                                                row = row,
                                                showChapterTitle = true,
                                                displayMode = settings.displayMode,
                                                expanded = window.isExpanded,
                                                workbench = settings.layoutMode == ReaderLayoutMode.WORKBENCH,
                                                imageDirectory = imageDirectory,
                                                settings = settings,
                                                textColor = readerTextColor,
                                                onSave = { viewModel.saveManualRevision(row.editionSegmentId, it) }
                                            )
                                        }
                                    }
                                }
                                if (settings.pagingMode == PagingMode.HORIZONTAL) {
                                    HorizontalPager(state = pagerState) { index -> page(index) }
                                } else {
                                    VerticalPager(state = pagerState) { index -> page(index) }
                                }
                            }
                        }
                    }

                    if (controlsVisible) {
                        TopAppBar(
                            title = {
                                Text(
                                    visibleRow?.chapterTitle ?: if (english) "Reader" else "阅读",
                                    maxLines = 1
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back)
                                }
                            },
                            actions = {
                                IconButton(onClick = onOpenWorkbench) {
                                    Icon(Icons.Default.DashboardCustomize, strings.openWorkbench)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = readerBackground.copy(alpha = .96f),
                                titleContentColor = readerTextColor,
                                navigationIconContentColor = readerTextColor,
                                actionIconContentColor = readerTextColor
                            ),
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }

                    if (controlsVisible) {
                        ReaderControlBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            chapters = chapters,
                            currentChapterPosition = currentChapterPosition,
                            progressValue = progressPreview ?: currentChapterPosition.toFloat(),
                            maxChapterPosition = maxChapterPosition,
                            currentChapterTitle = chapters.getOrNull(currentChapterPosition)?.canonicalTitle,
                            english = english,
                            onProgressChange = { progressPreview = it },
                            onProgressChangeFinished = {
                                val targetPosition = (progressPreview ?: currentChapterPosition.toFloat()).roundToInt()
                                progressPreview = null
                                chapters.getOrNull(targetPosition)?.let { chapter ->
                                    jumpToChapter(
                                        chapterId = chapter.id,
                                        content = content,
                                        pagingMode = settings.pagingMode,
                                        pagerState = pagerState,
                                        listState = listState,
                                        scope = scope,
                                        onChapterRequested = { requestedLogicalChapter = it }
                                    )
                                }
                            },
                            onPrevious = {
                                chapters.getOrNull(currentChapterPosition - 1)?.let { chapter ->
                                    jumpToChapter(
                                        chapterId = chapter.id,
                                        content = content,
                                        pagingMode = settings.pagingMode,
                                        pagerState = pagerState,
                                        listState = listState,
                                        scope = scope,
                                        onChapterRequested = { requestedLogicalChapter = it }
                                    )
                                }
                            },
                            onNext = {
                                chapters.getOrNull(currentChapterPosition + 1)?.let { chapter ->
                                    jumpToChapter(
                                        chapterId = chapter.id,
                                        content = content,
                                        pagingMode = settings.pagingMode,
                                        pagerState = pagerState,
                                        listState = listState,
                                        scope = scope,
                                        onChapterRequested = { requestedLogicalChapter = it }
                                    )
                                }
                            },
                            onOpenDirectory = { activePanelName = ReaderPanel.DIRECTORY.name },
                            onOpenSettings = { activePanelName = ReaderPanel.SETTINGS.name },
                            containerColor = readerBackground,
                            contentColor = readerTextColor
                        )
                    }
                }
            }

            ReaderSidePanel(
                visible = activePanel == ReaderPanel.DIRECTORY,
                side = ReaderPanelSide.LEFT,
                width = drawerWidth,
                onDismiss = { activePanelName = ReaderPanel.NONE.name }
            ) {
                ReaderDirectoryPanel(
                    chapters = chapters,
                    currentChapterId = currentChapterId,
                    english = english,
                    onChapter = { chapterId ->
                        activePanelName = ReaderPanel.NONE.name
                        jumpToChapter(
                            chapterId = chapterId,
                            content = content,
                            pagingMode = settings.pagingMode,
                            pagerState = pagerState,
                            listState = listState,
                            scope = scope,
                            onChapterRequested = { requestedLogicalChapter = it }
                        )
                    },
                    onResplit = { showResplitDialog = true }
                )
            }

            ReaderSidePanel(
                visible = activePanel == ReaderPanel.SETTINGS,
                side = ReaderPanelSide.RIGHT,
                width = drawerWidth,
                onDismiss = { activePanelName = ReaderPanel.NONE.name }
            ) {
                ReaderSettingsPanel(
                    settings = settings,
                    english = english,
                    workbenchAvailable = !window.isCompact,
                    onSettingsChanged = { settings = it },
                    onDismiss = { activePanelName = ReaderPanel.NONE.name },
                    textColor = readerTextColor
                )
            }
        }
    }

    if (showResplitDialog) {
        ReaderChapterResplitDialog(
            currentChapterCount = chapters.size,
            english = english,
            onDismiss = { showResplitDialog = false },
            onConfirm = { regex, cropTableOfContents ->
                showResplitDialog = false
                viewModel.reSplitBookChapters(bookId, regex, cropTableOfContents)
            }
        )
    }
}

private fun jumpToChapter(
    chapterId: Long,
    content: List<ResolvedReaderSegment>,
    pagingMode: PagingMode,
    pagerState: PagerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scope: CoroutineScope,
    onChapterRequested: (Long) -> Unit
) {
    val index = content.indexOfFirst { it.logicalChapterId == chapterId }
    if (index < 0) return
    if (pagingMode == PagingMode.CONTINUOUS) {
        onChapterRequested(chapterId)
        if (listState.firstVisibleItemIndex == index) {
            scope.launch { listState.animateScrollToItem(index) }
        }
    } else {
        scope.launch { pagerState.animateScrollToPage(index) }
    }
}

private fun ResolvedReaderSegment.toProgress(
    bookId: Long,
    preferredEditionId: Long?,
    offset: Int,
    settings: ReaderSettingsState
) = ReaderProgressEntity(
    bookId = bookId,
    preferredEditionId = preferredEditionId,
    logicalChapterId = logicalChapterId,
    logicalSegmentId = logicalSegmentId,
    segmentOffset = offset,
    displayMode = settings.displayMode.name,
    pagingMode = settings.pagingMode.name,
    readerLayoutMode = settings.layoutMode.name,
    pageAnimation = settings.animation.name,
    fontSizeSp = settings.fontSizeSp,
    fontFamily = settings.fontFamily.name,
    letterSpacingSp = settings.letterSpacingSp,
    lineSpacingMultiplier = settings.lineSpacingMultiplier,
    paragraphSpacingDp = settings.paragraphSpacingDp,
    pageMarginDp = settings.pageMarginDp,
    useTraditionalChinese = settings.useTraditionalChinese,
    readerBackground = settings.background.name
)

@Composable
private fun ReaderControlBar(
    chapters: List<LogicalChapterEntity>,
    currentChapterPosition: Int,
    progressValue: Float,
    maxChapterPosition: Int,
    currentChapterTitle: String?,
    english: Boolean,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = containerColor.copy(alpha = .98f),
        contentColor = contentColor,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = chapters.isNotEmpty() && currentChapterPosition > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = if (english) "Previous Chapter" else "上一章",
                        tint = if (chapters.isNotEmpty() && currentChapterPosition > 0) contentColor else contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Slider(
                        value = progressValue.coerceIn(0f, maxChapterPosition.toFloat()),
                        onValueChange = onProgressChange,
                        onValueChangeFinished = onProgressChangeFinished,
                        valueRange = 0f..maxChapterPosition.toFloat(),
                        steps = (chapters.size - 2).coerceAtLeast(0),
                        enabled = chapters.size > 1,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = contentColor.copy(alpha = 0.2f)
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            currentChapterTitle ?: if (english) "No chapter" else "暂无章节",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            if (chapters.isEmpty()) "0 / 0" else "${currentChapterPosition + 1} / ${chapters.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                IconButton(onClick = onNext, enabled = chapters.isNotEmpty() && currentChapterPosition < chapters.lastIndex) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = if (english) "Next Chapter" else "下一章",
                        tint = if (chapters.isNotEmpty() && currentChapterPosition < chapters.lastIndex) contentColor else contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenDirectory, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.FormatListNumbered, null, Modifier.size(18.dp), tint = contentColor)
                    Spacer(Modifier.width(5.dp))
                    Text(if (english) "Contents" else "目录", color = contentColor)
                }
                TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp), tint = contentColor)
                    Spacer(Modifier.width(5.dp))
                    Text(if (english) "Settings" else "设置", color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun ReaderSidePanel(
    visible: Boolean,
    side: ReaderPanelSide,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize().zIndex(20f),
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120))
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .34f))
                    .clickable(onClick = onDismiss)
            )
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier
                    .align(if (side == ReaderPanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(width),
                enter = if (side == ReaderPanelSide.LEFT) {
                    slideInHorizontally(tween(180)) { -it }
                } else {
                    slideInHorizontally(tween(180)) { it }
                },
                exit = if (side == ReaderPanelSide.LEFT) {
                    slideOutHorizontally(tween(150)) { -it }
                } else {
                    slideOutHorizontally(tween(150)) { it }
                }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 14.dp
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ReaderDirectoryPanel(
    chapters: List<LogicalChapterEntity>,
    currentChapterId: Long?,
    english: Boolean,
    onChapter: (Long) -> Unit,
    onResplit: () -> Unit
) {
    val listState = rememberLazyListState()
    var searchQuery by remember { mutableStateOf("") }
    val filteredChapters = remember(chapters, searchQuery) {
        if (searchQuery.isBlank()) chapters else chapters.filter {
            it.canonicalTitle.contains(searchQuery, ignoreCase = true) ||
                it.chapterIndex.toString().contains(searchQuery)
        }
    }

    LaunchedEffect(chapters, currentChapterId, filteredChapters) {
        val currentIndex = filteredChapters.indexOfFirst { it.id == currentChapterId }
        if (currentIndex >= 0) listState.scrollToItem(currentIndex)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FormatListNumbered, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(if (english) "Contents" else "目录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (english) "${chapters.size} chapters" else "共 ${chapters.size} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onResplit) {
                Icon(Icons.Default.Settings, if (english) "Chapter parsing" else "重新分章")
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            singleLine = true,
            label = { Text(if (english) "Search chapters" else "搜索章节") }
        )
        HorizontalDivider(Modifier.padding(top = 10.dp))
        if (filteredChapters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (english) "No matching chapters" else "没有匹配的章节", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filteredChapters, key = { it.id }) { chapter ->
                    ListItem(
                        modifier = Modifier.clickable { onChapter(chapter.id) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (chapter.id == currentChapterId) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)
                            } else {
                                Color.Transparent
                            }
                        ),
                        headlineContent = { Text(chapter.canonicalTitle, maxLines = 2) },
                        supportingContent = { Text(if (english) "Chapter ${chapter.chapterIndex}" else "第 ${chapter.chapterIndex} 章") }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsPanel(
    settings: ReaderSettingsState,
    english: Boolean,
    workbenchAvailable: Boolean,
    onSettingsChanged: (ReaderSettingsState) -> Unit,
    onDismiss: () -> Unit,
    textColor: Color
) {
    val update: (ReaderSettingsState) -> Unit = onSettingsChanged
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (english) "Reading settings" else "阅读设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, if (english) "Close" else "关闭") }
            }
        }
        item {
            Text(if (english) "Text" else "文字", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SettingSlider(
                label = if (english) "Font size" else "字号",
                valueLabel = "${settings.fontSizeSp.roundToInt()}sp",
                value = settings.fontSizeSp,
                range = 14f..32f,
                steps = 17,
                onValueChange = { update(settings.copy(fontSizeSp = it)) }
            )
            Text(if (english) "Font family" else "字体", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = settings.fontFamily == family,
                        onClick = { update(settings.copy(fontFamily = family)) },
                        label = { Text(fontFamilyLabel(family, english)) }
                    )
                }
            }
            SettingSlider(
                label = if (english) "Letter spacing" else "字间距",
                valueLabel = "${settings.letterSpacingSp.formatOneDecimal()}sp",
                value = settings.letterSpacingSp,
                range = 0f..3f,
                steps = 5,
                onValueChange = { update(settings.copy(letterSpacingSp = it)) }
            )
            SettingSlider(
                label = if (english) "Line spacing" else "行间距",
                valueLabel = "${settings.lineSpacingMultiplier.formatOneDecimal()}×",
                value = settings.lineSpacingMultiplier,
                range = 1.1f..2.4f,
                steps = 12,
                onValueChange = { update(settings.copy(lineSpacingMultiplier = it)) }
            )
            SettingSlider(
                label = if (english) "Paragraph spacing" else "段间距",
                valueLabel = "${settings.paragraphSpacingDp.roundToInt()}dp",
                value = settings.paragraphSpacingDp,
                range = 0f..32f,
                steps = 15,
                onValueChange = { update(settings.copy(paragraphSpacingDp = it)) }
            )
            SettingSlider(
                label = if (english) "Side margins" else "页侧边距",
                valueLabel = "${settings.pageMarginDp.roundToInt()}dp",
                value = settings.pageMarginDp,
                range = 8f..64f,
                steps = 13,
                onValueChange = { update(settings.copy(pageMarginDp = it)) }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (english) "Chinese variant" else "繁简字", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (english) {
                            if (settings.useTraditionalChinese) "Traditional Chinese" else "Simplified Chinese"
                        } else {
                            if (settings.useTraditionalChinese) "繁体中文" else "简体中文"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useTraditionalChinese,
                    onCheckedChange = { update(settings.copy(useTraditionalChinese = it)) }
                )
            }
        }
        item {
            Text(if (english) "Background" else "背景", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderBackground.entries.forEach { background ->
                    FilterChip(
                        selected = settings.background == background,
                        onClick = { update(settings.copy(background = background)) },
                        label = { Text(backgroundLabel(background, english)) }
                    )
                }
            }
        }
        item {
            Text(if (english) "Display" else "显示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ChoiceRow(
                label = if (english) "Mode" else "显示模式",
                values = ReaderDisplayMode.entries,
                selected = settings.displayMode,
                labelFor = { displayModeLabel(it, english) },
                onSelected = { update(settings.copy(displayMode = it)) }
            )
            ChoiceRow(
                label = if (english) "Paging" else "翻页方式",
                values = PagingMode.entries,
                selected = settings.pagingMode,
                labelFor = { pagingModeLabel(it, english) },
                onSelected = { update(settings.copy(pagingMode = it)) }
            )
            ChoiceRow(
                label = if (english) "Page animation" else "翻页动画",
                values = PageAnimation.entries,
                selected = settings.animation,
                labelFor = { animationLabel(it, english) },
                onSelected = { update(settings.copy(animation = it)) }
            )
            ChoiceRow(
                label = if (english) "Layout" else "布局",
                values = ReaderLayoutMode.entries,
                selected = settings.layoutMode,
                labelFor = { layoutLabel(it, english) },
                enabled = { it != ReaderLayoutMode.WORKBENCH || workbenchAvailable },
                onSelected = { update(settings.copy(layoutMode = it)) }
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    label: String,
    values: List<T>,
    selected: T,
    labelFor: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelected: (T) -> Unit
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                enabled = enabled(value),
                label = { Text(labelFor(value)) }
            )
        }
    }
}

private fun fontFamilyLabel(value: ReaderFontFamily, english: Boolean) = when (value) {
    ReaderFontFamily.SYSTEM -> if (english) "System" else "系统"
    ReaderFontFamily.SERIF -> if (english) "Serif" else "衬线"
    ReaderFontFamily.SANS_SERIF -> if (english) "Sans" else "无衬线"
    ReaderFontFamily.MONOSPACE -> if (english) "Mono" else "等宽"
}

private fun backgroundLabel(value: ReaderBackground, english: Boolean) = when (value) {
    ReaderBackground.SYSTEM -> if (english) "System" else "跟随主题"
    ReaderBackground.PAPER -> if (english) "Paper" else "纸张"
    ReaderBackground.MINT -> if (english) "Mint" else "薄荷"
    ReaderBackground.NIGHT -> if (english) "Night" else "夜间"
}

private fun displayModeLabel(value: ReaderDisplayMode, english: Boolean) = when (value) {
    ReaderDisplayMode.TRANSLATION -> if (english) "Translation" else "译文"
    ReaderDisplayMode.ORIGINAL -> if (english) "Original" else "原文"
    ReaderDisplayMode.BILINGUAL -> if (english) "Bilingual" else "原译对照"
    ReaderDisplayMode.QUICK_EDIT -> if (english) "Quick edit" else "快捷编辑"
}

private fun pagingModeLabel(value: PagingMode, english: Boolean) = when (value) {
    PagingMode.CONTINUOUS -> if (english) "Continuous" else "连续滚动"
    PagingMode.HORIZONTAL -> if (english) "Horizontal" else "左右分页"
    PagingMode.VERTICAL -> if (english) "Vertical" else "上下分页"
}

private fun animationLabel(value: PageAnimation, english: Boolean) = when (value) {
    PageAnimation.NONE -> if (english) "None" else "无"
    PageAnimation.SLIDE -> if (english) "Slide" else "平移"
    PageAnimation.FADE -> if (english) "Fade" else "淡入淡出"
    PageAnimation.CURL -> if (english) "Curl" else "仿真"
}

private fun layoutLabel(value: ReaderLayoutMode, english: Boolean) = when (value) {
    ReaderLayoutMode.CLEAN -> if (english) "Clean" else "纯净阅读"
    ReaderLayoutMode.STANDARD -> if (english) "Standard" else "标准阅读"
    ReaderLayoutMode.WORKBENCH -> if (english) "Workbench" else "工作台"
}

private fun Float.formatOneDecimal(): String = "%.1f".format(java.util.Locale.US, this)

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
            Text(
                if (english) "Create a translation version in the workbench first." else "请先在翻译工作台中创建译本版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
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
        Text(if (english) "Contents" else "目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(chapters, key = { it.id }) { chapter ->
                ListItem(
                    modifier = Modifier.clickable { onChapter(chapter.id) },
                    headlineContent = { Text(chapter.canonicalTitle, maxLines = 2) },
                    supportingContent = { Text(if (english) "Chapter ${chapter.chapterIndex}" else "第 ${chapter.chapterIndex} 章") }
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
    settings: ReaderSettingsState,
    textColor: Color,
    onSave: (String) -> Unit
) {
    val strings = platformUiStrings()
    val english = strings.bookshelf == "Bookshelf"
    val textStyle = ReaderTextStyle(
        fontSize = settings.fontSizeSp.sp,
        fontFamily = settings.fontFamily.toComposeFontFamily(),
        letterSpacing = settings.letterSpacingSp.sp,
        lineSpacingMultiplier = settings.lineSpacingMultiplier,
        paragraphSpacing = settings.paragraphSpacingDp.dp,
        color = textColor
    )
    Column(
        Modifier
            .widthIn(max = if (workbench && expanded) 1120.dp else 720.dp)
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        if (showChapterTitle) {
            Text(
                row.chapterTitle,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = (settings.fontSizeSp * 1.35f).sp,
                    color = textColor,
                    fontFamily = settings.fontFamily.toComposeFontFamily()
                ),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
            )
        }
        when (displayMode) {
            ReaderDisplayMode.ORIGINAL -> ReaderRichContent(
                row.originalText,
                imageDirectory,
                textStyle = textStyle,
                useTraditionalChinese = settings.useTraditionalChinese
            )
            ReaderDisplayMode.TRANSLATION -> {
                if (row.isFallback) Text(
                    if (english) "Original · replaced automatically when translation is ready" else "原文 · 译文生成后将自动替换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                ReaderRichContent(row.displayText, imageDirectory, textStyle = textStyle, useTraditionalChinese = settings.useTraditionalChinese)
            }
            ReaderDisplayMode.BILINGUAL -> if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (english) "Original" else "原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        ReaderRichContent(row.originalText, imageDirectory, textStyle = textStyle, useTraditionalChinese = settings.useTraditionalChinese)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (english) "Translation" else "译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        ReaderRichContent(row.translatedText ?: if (english) "(Not translated yet)" else "（尚未翻译）", imageDirectory, textStyle = textStyle, useTraditionalChinese = settings.useTraditionalChinese)
                    }
                }
            } else {
                Text(if (english) "Original" else "原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ReaderRichContent(row.originalText, imageDirectory, textStyle = textStyle, useTraditionalChinese = settings.useTraditionalChinese)
                Text(if (english) "Translation" else "译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ReaderRichContent(row.translatedText ?: if (english) "(Not translated yet)" else "（尚未翻译）", imageDirectory, textStyle = textStyle, useTraditionalChinese = settings.useTraditionalChinese)
            }
            ReaderDisplayMode.QUICK_EDIT -> {
                if (row.translatedText == null) {
                    ReaderRichContent(
                        if (english) "No translation for this segment yet. Showing original:\n${row.originalText}" else "当前段尚无译文，暂时显示原文：\n${row.originalText}",
                        imageDirectory,
                        textStyle = textStyle,
                        useTraditionalChinese = settings.useTraditionalChinese
                    )
                } else if (row.isCompositeMapping) {
                    ReaderRichContent(
                        if (english) "This content maps to multiple segments. Revise each item in the translation workbench.\n\n${row.translatedText}" else "当前内容由多个 Segment 组合映射，请在翻译工作台中逐项修订。\n\n${row.translatedText}",
                        imageDirectory,
                        textStyle = textStyle,
                        useTraditionalChinese = settings.useTraditionalChinese
                    )
                } else {
                    QuickEditor(row.translatedText, onSave)
                }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank() && value != initial) {
            Text(if (english) "Save revision" else "保存修订")
        }
    }
}
