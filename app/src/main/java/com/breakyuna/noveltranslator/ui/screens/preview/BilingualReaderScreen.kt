package com.breakyuna.noveltranslator.ui.screens.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.model.ChapterStatus
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
    SERIF("Serif", FontFamily.Serif),
    SANS("Sans", FontFamily.SansSerif),
    MONO("Mono", FontFamily.Monospace)
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
    LaunchedEffect(currentChapter?.id, currentChapter?.updatedAt, project?.id) {
        val activeProject = project
        val activeChapter = currentChapter
        if (activeProject == null || activeChapter == null) {
            rawOriginal = ""
            rawTranslated = ""
        } else {
            val loaded = withContext(Dispatchers.IO) {
                viewModel.fileManager.readOriginalChapter(activeProject.id, activeChapter.originalFileName) to
                    viewModel.fileManager.readTranslatedChapter(activeProject.id, activeChapter.translatedFileName)
            }
            rawOriginal = loaded.first
            rawTranslated = loaded.second
        }
    }

    // Split strictly by paragraphs without sentence-level splitting
    val origParagraphs = remember(rawOriginal) {
        rawOriginal.split(Regex("\n{2,}|\r\n\r\n")).map { it.trim() }.filter { it.isNotBlank() }
    }

    val transParagraphs = remember(rawTranslated) {
        rawTranslated.split(Regex("\n{2,}|\r\n\r\n")).map { it.trim() }.filter { it.isNotBlank() }
    }

    val maxParagraphCount = maxOf(origParagraphs.size, transParagraphs.size)

    val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
    val prevChapter = if (currentIdx > 0) chapters.getOrNull(currentIdx - 1) else null
    val nextChapter = if (currentIdx != -1 && currentIdx < chapters.size - 1) chapters.getOrNull(currentIdx + 1) else null

    val origWords = remember(rawOriginal) { TxtParser.countWords(rawOriginal) }
    val transWords = remember(rawTranslated) { TxtParser.countWords(rawTranslated) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentChapter?.title ?: strings.readerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${strings.chapterPrefix}${currentChapter?.chapterIndex ?: 1}/${chapters.size} • ${origWords}${strings.wordsUnit} / ${transWords}${strings.wordsUnit}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
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
                            imageVector = Icons.Default.AutoStories,
                            contentDescription = strings.extractNewTermsAction,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Table of Contents / Chapter Switcher
                    IconButton(
                        onClick = { showTocSheet = true },
                        modifier = Modifier.testTag("toc_button")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = strings.tocSheetTitle)
                    }

                    // Reader Settings (Themes, Fonts, Indent)
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("reader_settings_button")
                    ) {
                        Icon(Icons.Default.FormatSize, contentDescription = strings.settingsTitle)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = readerTheme.bg)
            )
        },
        bottomBar = {
            Surface(
                color = readerTheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    // View Mode Tabs: Novel Reader vs Translated vs Bilingual Parallel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = viewMode == ViewMode.NOVEL_READER,
                            onClick = { viewMode = ViewMode.NOVEL_READER },
                            label = { Text(strings.novelReaderTitle, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = viewMode == ViewMode.TRANSLATED_ONLY,
                            onClick = { viewMode = ViewMode.TRANSLATED_ONLY },
                            label = { Text(strings.translatedChaptersCount, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = viewMode == ViewMode.BILINGUAL_PARALLEL,
                            onClick = { viewMode = ViewMode.BILINGUAL_PARALLEL },
                            label = { Text(strings.toggleBilingual, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.VerticalSplit, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Prev / Next Chapter Navigation
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
                            Text(strings.prevChapterBtn)
                        }

                        Text(
                            text = "${currentIdx + 1} / ${chapters.size}",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = readerTheme.text
                        )

                        TextButton(
                            onClick = { nextChapter?.let { currentChapterId = it.id } },
                            enabled = nextChapter != null,
                            modifier = Modifier.testTag("next_chapter_button")
                        ) {
                            Text(strings.nextChapterBtn)
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                        }
                    }
                }
            }
        },
        modifier = modifier
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
                        color = readerTheme.text
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (viewMode == ViewMode.NOVEL_READER) 20.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (viewMode == ViewMode.NOVEL_READER) 16.dp else 12.dp),
                    contentPadding = PaddingValues(vertical = 18.dp)
                ) {
                    // Chapter Title Header
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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
                            HorizontalDivider(color = readerTheme.text.copy(alpha = 0.15f))
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
                                        Spacer(modifier = Modifier.height(12.dp))
                                        if (defaultProvider != null && currentChapter != null) {
                                            Button(
                                                onClick = {
                                                    viewModel.translateSingleChapter(currentChapter.id, defaultProvider)
                                                }
                                            ) {
                                                Icon(Icons.Default.Translate, contentDescription = null)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(strings.startAutoTranslationBtn)
                                            }
                                        }
                                    }
                                }
                            } else {
                                itemsIndexed(transParagraphs) { index, para ->
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
                                            .clickable {
                                                selectedParagraphIndex = index
                                                selectedOriginalParagraph = origParagraphs.getOrElse(index) { "" }
                                                selectedTranslatedParagraph = para
                                                showEditDialog = true
                                            }
                                    )
                                }
                            }
                        }

                        ViewMode.TRANSLATED_ONLY -> {
                            // Translated Paragraphs with Card Containers
                            if (transParagraphs.isEmpty()) {
                                item {
                                    Text(
                                        text = strings.chapterNotTranslatedYet,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = readerTheme.text.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                itemsIndexed(transParagraphs) { index, para ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedParagraphIndex = index
                                                selectedOriginalParagraph = origParagraphs.getOrElse(index) { "" }
                                                selectedTranslatedParagraph = para
                                                showEditDialog = true
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = readerTheme.surface.copy(alpha = 0.7f)),
                                        border = BorderStroke(0.5.dp, readerTheme.text.copy(alpha = 0.12f))
                                    ) {
                                        Text(
                                            text = if (enableIndent) "　　$para" else para,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = fontSizeSp.sp,
                                                lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
                                                fontFamily = readerFont.family
                                            ),
                                            color = readerTheme.text,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        ViewMode.BILINGUAL_PARALLEL -> {
                            // Bilingual Comparison Mode
                            items(maxParagraphCount) { index ->
                                val origP = origParagraphs.getOrElse(index) { "" }
                                val transP = transParagraphs.getOrElse(index) { "" }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("bilingual_row_$index"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = readerTheme.surface),
                                    border = BorderStroke(1.dp, readerTheme.text.copy(alpha = 0.12f))
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
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
                                            Spacer(modifier = Modifier.height(8.dp))
                                            HorizontalDivider(color = readerTheme.text.copy(alpha = 0.1f))
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }

                                        // Translated Text
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

                                            Row {
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
                                                        imageVector = Icons.Default.AutoFixHigh,
                                                        contentDescription = strings.aiRetranslateAction,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(16.dp)
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
                                                        imageVector = Icons.Default.Edit,
                                                        contentDescription = strings.editInPlaceAction,
                                                        tint = readerTheme.text.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(16.dp)
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
    }

    // Reader Settings Bottom Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = strings.novelReaderTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )

                // Theme selection
                Text(text = strings.themeSettingsTitle, style = MaterialTheme.typography.labelMedium)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { ReaderThemeChip(strings.themeLight, ReaderTheme.LIGHT, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip(strings.themeSepia, ReaderTheme.SEPIA, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip(strings.themeMint, ReaderTheme.MINT, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip(strings.themeSlate, ReaderTheme.SLATE, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip(strings.themeDark, ReaderTheme.DARK, readerTheme) { readerTheme = it } }
                    item { ReaderThemeChip(strings.themeAmoled, ReaderTheme.AMOLED, readerTheme) { readerTheme = it } }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Font Family selection
                Text(text = strings.fontSerif + " / " + strings.fontSans, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderFont.values().forEach { f ->
                        FilterChip(
                            selected = readerFont == f,
                            onClick = { readerFont = f },
                            label = { Text(f.label) },
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
                    Text("Text size: ${fontSizeSp}sp", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fontSizeSp = (fontSizeSp - 1).coerceAtLeast(12) }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease text size")
                        }
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
                    Text(text = "${strings.lineHeightLabel}: ${lineHeightMultiplier}x", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = lineHeightMultiplier == 1.5f, onClick = { lineHeightMultiplier = 1.5f }, label = { Text("1.5x") })
                        FilterChip(selected = lineHeightMultiplier == 1.7f, onClick = { lineHeightMultiplier = 1.7f }, label = { Text("1.7x") })
                        FilterChip(selected = lineHeightMultiplier == 2.0f, onClick = { lineHeightMultiplier = 2.0f }, label = { Text("2.0x") })
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = strings.indentLabel, style = MaterialTheme.typography.bodyMedium)
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
            onDismissRequest = { showTocSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "${strings.tocSheetTitle} (${chapters.size})",
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
                            color = if (isCurr) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${ch.chapterIndex}. ${ch.title}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Normal),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (ch.status == ChapterStatus.COMPLETED) strings.filterDone else strings.filterPending,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (ch.status == ChapterStatus.COMPLETED) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
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
                    }
                ) {
                    Text(strings.saveEditBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // AI Polish & Re-translate Single Paragraph Dialog
    if (showPolishDialog && currentChapter != null) {
        var polishInstruction by remember { mutableStateOf("Make the phrasing more vivid and literary") }
        var isPolishing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isPolishing) showPolishDialog = false },
            title = { Text(strings.aiRetranslateParaTitle) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${strings.originalTermLabel}: \"${selectedOriginalParagraph.take(120)}...\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = polishInstruction,
                        onValueChange = { polishInstruction = it },
                        label = { Text(strings.instructionToneLabel) },
                        placeholder = { Text(strings.instructionPlaceholder) },
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
                                provider = defaultProvider
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
                    enabled = !isPolishing
                ) {
                    Text(strings.retranslateBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPolishDialog = false }, enabled = !isPolishing) {
                    Text(strings.cancel)
                }
            }
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
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f)),
        modifier = Modifier.clickable { onSelect(theme) }
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = theme.text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
