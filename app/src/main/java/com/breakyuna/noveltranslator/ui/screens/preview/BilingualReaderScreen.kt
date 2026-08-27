package com.breakyuna.noveltranslator.ui.screens.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.core.translator.StableSegmentParser
import com.breakyuna.noveltranslator.data.model.ChapterSegmentEntity
import com.breakyuna.noveltranslator.data.model.ChapterStatus
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ReaderTheme(val bg: Color, val text: Color, val surface: Color) {
    LIGHT(BackgroundLight, OnBackgroundLight, SurfaceLight),
    SEPIA(SepiaBackground, SepiaOnBackground, SepiaSurface),
    MINT(MintBackground, MintOnBackground, MintSurface),
    SLATE(SlateBackground, SlateOnBackground, SlateSurface),
    DARK(BackgroundDark, OnBackgroundDark, SurfaceDark),
    AMOLED(AmoledBackground, AmoledOnBackground, AmoledSurface)
}

enum class ViewMode {
    NOVEL_READER,
    TRANSLATED_ONLY,
    BILINGUAL_PARALLEL
}

enum class ReaderFont(val label: String, val family: FontFamily) {
    SERIF("宋体/衬线", FontFamily.Serif),
    SANS("黑体/无衬线", FontFamily.SansSerif),
    MONO("等宽字体", FontFamily.Monospace)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilingualReaderScreen(
    chapterId: Long,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val context = LocalContext.current
    val readerPrefs = remember { context.getSharedPreferences("reader_preferences", 0) }
    val defaultProvider = providers.firstOrNull { it.id == project?.defaultProviderId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()

    var currentChapterId by remember(chapterId) { mutableStateOf(chapterId) }
    val currentChapter = chapters.find { it.id == currentChapterId } ?: chapters.firstOrNull()

    var viewMode by remember { mutableStateOf(enumPreference(readerPrefs.getString("view_mode", null), ViewMode.NOVEL_READER)) }
    var readerTheme by remember { mutableStateOf(enumPreference(readerPrefs.getString("theme", null), ReaderTheme.SEPIA)) }
    var fontSizeSp by remember { mutableStateOf(readerPrefs.getInt("font_size", 17).coerceIn(12, 30)) }
    var lineHeightMultiplier by remember { mutableStateOf(readerPrefs.getFloat("line_height", 1.7f).coerceIn(1.2f, 2.4f)) }
    var readerFont by remember { mutableStateOf(enumPreference(readerPrefs.getString("font", null), ReaderFont.SERIF)) }
    var enableIndent by remember { mutableStateOf(readerPrefs.getBoolean("indent", true)) }

    LaunchedEffect(viewMode, readerTheme, fontSizeSp, lineHeightMultiplier, readerFont, enableIndent) {
        readerPrefs.edit()
            .putString("view_mode", viewMode.name)
            .putString("theme", readerTheme.name)
            .putInt("font_size", fontSizeSp)
            .putFloat("line_height", lineHeightMultiplier)
            .putString("font", readerFont.name)
            .putBoolean("indent", enableIndent)
            .apply()
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }

    var showEditDialog by remember { mutableStateOf(false) }
    var showPolishDialog by remember { mutableStateOf(false) }
    var selectedParagraphIndex by remember { mutableStateOf(-1) }
    var selectedOriginalParagraph by remember { mutableStateOf("") }
    var selectedTranslatedParagraph by remember { mutableStateOf("") }

    var rawOriginal by remember { mutableStateOf("") }
    var rawTranslated by remember { mutableStateOf("") }
    var persistedSegments by remember { mutableStateOf<List<ChapterSegmentEntity>>(emptyList()) }

    LaunchedEffect(currentChapter?.id, currentChapter?.updatedAt, project?.id) {
        val activeProject = project
        val activeChapter = currentChapter
        if (activeProject == null || activeChapter == null) {
            rawOriginal = ""
            rawTranslated = ""
            persistedSegments = emptyList()
        } else {
            val loaded = withContext(Dispatchers.IO) {
                viewModel.fileManager.readOriginalChapter(activeProject.id, activeChapter.originalFileName) to
                        viewModel.fileManager.readTranslatedChapter(activeProject.id, activeChapter.translatedFileName)
            }
            rawOriginal = loaded.first
            rawTranslated = loaded.second
            persistedSegments = viewModel.getChapterSegments(activeChapter.id)
        }
    }

    val alignedSegments = remember(rawOriginal, rawTranslated, currentChapterId, persistedSegments) {
        if (persistedSegments.isNotEmpty()) {
            StableSegmentParser.alignPersisted(persistedSegments)
        } else {
            StableSegmentParser.align(currentChapterId, rawOriginal, rawTranslated)
        }
    }
    val origParagraphs = alignedSegments.map { it.sourceText }
    val transParagraphs = alignedSegments.map { it.translatedText }
    val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
    val prevChapter = if (currentIdx > 0) chapters.getOrNull(currentIdx - 1) else null
    val nextChapter = if (currentIdx != -1 && currentIdx < chapters.size - 1) chapters.getOrNull(currentIdx + 1) else null

    val origWords = remember(rawOriginal) { TxtParser.countWords(rawOriginal) }
    val transWords = remember(rawTranslated) { TxtParser.countWords(rawTranslated) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = readerTheme.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentChapter?.title ?: strings.readerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = readerTheme.text
                        )
                        Text(
                            text = "${strings.chapterPrefix}${currentChapter?.chapterIndex ?: 1}/${chapters.size} · 原文 ${origWords}字 / 译文 ${transWords}字",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = readerTheme.text.copy(alpha = 0.65f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel, tint = readerTheme.text)
                    }
                },
                actions = {
                    // Extract Terms from this Chapter
                    IconButton(
                        onClick = {
                            if (currentChapter != null && defaultProvider != null) {
                                viewModel.extractTermsFromChapter(currentChapter.id, defaultProvider)
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        modifier = Modifier.testTag("extract_terms_chapter_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = strings.extractNewTermsAction,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Table of Contents
                    IconButton(
                        onClick = { showTocSheet = true },
                        modifier = Modifier.testTag("toc_button")
                    ) {
                        Icon(Icons.Outlined.FormatListBulleted, contentDescription = strings.tocSheetTitle, tint = readerTheme.text)
                    }

                    // Reader Settings
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("reader_settings_button")
                    ) {
                        Icon(Icons.Outlined.FormatSize, contentDescription = strings.settingsTitle, tint = readerTheme.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = readerTheme.bg)
            )
        },
        bottomBar = {
            Surface(
                color = readerTheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    // View Mode Segmented Switcher
                    AppSegmentedControl(
                        items = listOf(strings.novelReaderTitle, "译文卡片", strings.toggleBilingual),
                        selectedIndex = when (viewMode) {
                            ViewMode.NOVEL_READER -> 0
                            ViewMode.TRANSLATED_ONLY -> 1
                            ViewMode.BILINGUAL_PARALLEL -> 2
                        },
                        onItemSelected = {
                            viewMode = when (it) {
                                0 -> ViewMode.NOVEL_READER
                                1 -> ViewMode.TRANSLATED_ONLY
                                else -> ViewMode.BILINGUAL_PARALLEL
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Prev / Next Navigation Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { prevChapter?.let { currentChapterId = it.id } },
                            enabled = prevChapter != null,
                            modifier = Modifier.testTag("prev_chapter_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.prevChapterBtn)
                        }

                        Text(
                            text = "${currentIdx + 1} / ${chapters.size}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = readerTheme.text
                        )

                        TextButton(
                            onClick = { nextChapter?.let { currentChapterId = it.id } },
                            enabled = nextChapter != null,
                            modifier = Modifier.testTag("next_chapter_button")
                        ) {
                            Text(strings.nextChapterBtn)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(readerTheme.bg)
                .padding(padding)
        ) {
            if (rawOriginal.isBlank() && rawTranslated.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = strings.emptyChapterState,
                        color = readerTheme.text.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (viewMode == ViewMode.NOVEL_READER) 20.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (viewMode == ViewMode.NOVEL_READER) 16.dp else 12.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                ) {
                    // Chapter Title Header
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Text(
                                text = currentChapter?.title ?: "",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (fontSizeSp + 5).sp,
                                    fontFamily = readerFont.family
                                ),
                                color = readerTheme.text,
                                textAlign = if (viewMode == ViewMode.NOVEL_READER) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = readerTheme.text.copy(alpha = 0.12f))
                        }
                    }

                    when (viewMode) {
                        ViewMode.NOVEL_READER -> {
                            // Immersive Novel Reading Mode
                            if (transParagraphs.isEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = strings.chapterNotTranslatedYet,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = readerTheme.text.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                        if (defaultProvider != null && currentChapter != null) {
                                            AppPrimaryButton(
                                                text = strings.startAutoTranslationBtn,
                                                onClick = {
                                                    viewModel.translateSingleChapter(currentChapter.id, defaultProvider)
                                                },
                                                icon = Icons.Default.Translate,
                                                modifier = Modifier.widthIn(max = 200.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = alignedSegments,
                                    key = { _, segment -> segment.segmentId }
                                ) { index, segment ->
                                    val para = segment.translatedText
                                    val formattedPara = if (enableIndent) "　　$para" else para
                                    Text(
                                        text = formattedPara,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
                                            fontFamily = readerFont.family,
                                            color = readerTheme.text
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable {
                                                selectedParagraphIndex = index
                                                selectedOriginalParagraph = origParagraphs.getOrElse(index) { "" }
                                                selectedTranslatedParagraph = para
                                                showEditDialog = true
                                            }
                                            .padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        ViewMode.TRANSLATED_ONLY -> {
                            // Translated Paragraphs with Grouped Cards
                            if (transParagraphs.isEmpty()) {
                                item {
                                    Text(
                                        text = strings.chapterNotTranslatedYet,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = readerTheme.text.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = alignedSegments,
                                    key = { _, segment -> segment.segmentId }
                                ) { index, segment ->
                                    val para = segment.translatedText
                                    AppGroupedSurface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedParagraphIndex = index
                                                selectedOriginalParagraph = origParagraphs.getOrElse(index) { "" }
                                                selectedTranslatedParagraph = para
                                                showEditDialog = true
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(12.dp)
                                    ) {
                                        Text(
                                            text = if (enableIndent) "　　$para" else para,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = fontSizeSp.sp,
                                                lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
                                                fontFamily = readerFont.family
                                            ),
                                            color = readerTheme.text
                                        )
                                    }
                                }
                            }
                        }

                        ViewMode.BILINGUAL_PARALLEL -> {
                            // Bilingual Parallel Comparison Mode (Apple Style Rows)
                            itemsIndexed(
                                items = alignedSegments,
                                key = { _, segment -> segment.segmentId }
                            ) { index, segment ->
                                val origP = segment.sourceText
                                val transP = segment.translatedText

                                AppGroupedSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("bilingual_row_$index"),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(14.dp)
                                ) {
                                    // Original Text
                                    if (origP.isNotBlank()) {
                                        Text(
                                            text = origP,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = (fontSizeSp - 1).sp,
                                                lineHeight = ((fontSizeSp - 1) * 1.5).sp,
                                                color = readerTheme.text.copy(alpha = 0.65f)
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = readerTheme.text.copy(alpha = 0.08f))
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }

                                    // Translated Text with Inline Polish Actions
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (transP.isNotBlank()) transP else strings.pendingTranslation,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = fontSizeSp.sp,
                                                lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = readerFont.family,
                                                color = if (transP.isNotBlank()) readerTheme.text else readerTheme.text.copy(alpha = 0.4f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )

                                        Row(modifier = Modifier.padding(start = 8.dp)) {
                                            IconButton(
                                                onClick = {
                                                    selectedParagraphIndex = index
                                                    selectedOriginalParagraph = origP
                                                    selectedTranslatedParagraph = transP
                                                    showPolishDialog = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.AutoFixHigh,
                                                    contentDescription = strings.aiRetranslateAction,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    selectedParagraphIndex = index
                                                    selectedOriginalParagraph = origP
                                                    selectedTranslatedParagraph = transP
                                                    showEditDialog = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Edit,
                                                    contentDescription = strings.editInPlaceAction,
                                                    tint = readerTheme.text.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(17.dp)
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
        }
    }

    // Reader Settings Bottom Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "阅读偏好设置",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )

                // Theme selection
                Text(text = strings.themeSettingsTitle, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { ReaderThemeChip("明亮", ReaderTheme.LIGHT, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip("羊皮纸", ReaderTheme.SEPIA, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip("薄荷", ReaderTheme.MINT, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip("板岩灰", ReaderTheme.SLATE, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip("深邃暗夜", ReaderTheme.DARK, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip("极黑 AMOLED", ReaderTheme.AMOLED, readerTheme) { readerTheme = it } }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Font Family selection
                Text(text = "排版字体", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderFont.values().forEach { f ->
                        FilterChip(
                            selected = readerFont == f,
                            onClick = { readerFont = f },
                            label = { Text(f.label) },
                            shape = SmallControlShape,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Font Size & Line Height & Indent Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("字号大小: ${fontSizeSp}sp", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12) }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease text size")
                        }
                        Text("${fontSizeSp}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { fontSizeSp = (fontSizeSp + 1).coerceAtMost(30) }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase text size")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "行间距: ${lineHeightMultiplier}x", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = lineHeightMultiplier == 1.5f, onClick = { lineHeightMultiplier = 1.5f }, label = { Text("1.5x") }, shape = SmallControlShape)
                        FilterChip(selected = lineHeightMultiplier == 1.7f, onClick = { lineHeightMultiplier = 1.7f }, label = { Text("1.7x") }, shape = SmallControlShape)
                        FilterChip(selected = lineHeightMultiplier == 2.0f, onClick = { lineHeightMultiplier = 2.0f }, label = { Text("2.0x") }, shape = SmallControlShape)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "首行缩进两个字符", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = enableIndent,
                        onCheckedChange = { enableIndent = it }
                    )
                }
            }
        }
    }

    // Table of Contents (TOC) Bottom Sheet
    if (showTocSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTocSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "${strings.tocSheetTitle} (${chapters.size} 章)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(0.6f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chapters, key = { it.id }) { ch ->
                        val isCurr = ch.id == currentChapterId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurr) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (isCurr) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentChapterId = ch.id
                                    showTocSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 ${ch.chapterIndex} 章 · ${ch.title}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Normal),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (ch.status == ChapterStatus.COMPLETED) "已完成" else "待翻译",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (ch.status == ChapterStatus.COMPLETED) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Manual In-place Edit Dialog
    if (showEditDialog && currentChapter != null) {
        var editText by remember { mutableStateOf(selectedTranslatedParagraph) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("${strings.editParagraphTitle} (${strings.paragraphLabel} #${selectedParagraphIndex + 1})") },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedOriginalParagraph.isNotBlank()) {
                        Text(
                            text = "${strings.originalTermLabel}: ${selectedOriginalParagraph.take(100)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        maxLines = 8,
                        shape = SmallControlShape,
                        label = { Text(strings.revisedTranslationLabel) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newParagraphs = transParagraphs.toMutableList()
                        if (selectedParagraphIndex in newParagraphs.indices) {
                            newParagraphs[selectedParagraphIndex] = editText.trim()
                        } else {
                            newParagraphs.add(editText.trim())
                        }
                        val merged = newParagraphs.joinToString("\n\n")
                        viewModel.saveChapterTranslationDirectly(currentChapter.id, merged)
                        showEditDialog = false
                    },
                    shape = ButtonShape
                ) {
                    Text(strings.saveEditBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }

    // AI Polish & Re-translate Single Paragraph Dialog
    if (showPolishDialog && currentChapter != null) {
        var polishInstruction by remember { mutableStateOf("使文字表达更加地道优美、符合当代中文小说语感") }
        var isPolishing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isPolishing) showPolishDialog = false },
            title = { Text(strings.aiRetranslateParaTitle) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "原文段落: \"${selectedOriginalParagraph.take(120)}...\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = polishInstruction,
                        onValueChange = { polishInstruction = it },
                        label = { Text("润色微调要求") },
                        placeholder = { Text(strings.instructionPlaceholder) },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isPolishing) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.retranslatingWithLlm, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (defaultProvider != null && selectedOriginalParagraph.isNotBlank()) {
                            isPolishing = true
                            viewModel.reTranslateParagraph(
                                chapterId = currentChapter.id,
                                paragraphIndex = selectedParagraphIndex,
                                originalParagraph = selectedOriginalParagraph,
                                customInstruction = polishInstruction,
                                provider = defaultProvider,
                                segmentId = alignedSegments.getOrNull(selectedParagraphIndex)?.segmentId
                            ) { newPara ->
                                val newParagraphs = transParagraphs.toMutableList()
                                if (selectedParagraphIndex in newParagraphs.indices) {
                                    newParagraphs[selectedParagraphIndex] = newPara
                                } else {
                                    newParagraphs.add(newPara)
                                }
                                val merged = newParagraphs.joinToString("\n\n")
                                viewModel.saveChapterTranslationDirectly(currentChapter.id, merged)
                                isPolishing = false
                                showPolishDialog = false
                            }
                        } else {
                            viewModel.showMessage(strings.noProvidersConfigured)
                        }
                    },
                    enabled = !isPolishing,
                    shape = ButtonShape
                ) {
                    Text(strings.retranslateBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPolishDialog = false }, enabled = !isPolishing) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }
}

private inline fun <reified T : Enum<T>> enumPreference(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

@Composable
fun ReaderThemeChip(name: String, theme: ReaderTheme, currentTheme: ReaderTheme, onSelect: (ReaderTheme) -> Unit) {
    val isSelected = theme == currentTheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.bg,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f)),
        modifier = Modifier.clickable { onSelect(theme) }
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = theme.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
