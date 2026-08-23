package com.example.ui.screens.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChapterEntity
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.*
import com.example.ui.viewmodel.AppViewModel

enum class ReaderTheme(val bg: Color, val text: Color, val surface: Color) {
    LIGHT(BackgroundLight, OnBackgroundLight, SurfaceLight),
    SEPIA(SepiaBackground, SepiaOnBackground, SepiaSurface),
    DARK(BackgroundDark, OnBackgroundDark, SurfaceDark),
    SLATE(SlateBackground, SlateOnBackground, SlateSurface)
}

enum class ViewMode {
    TRANSLATED_ONLY,
    BILINGUAL_PARALLEL
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
    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()

    var currentChapterId by remember(chapterId) { mutableStateOf(chapterId) }
    val currentChapter = chapters.find { it.id == currentChapterId } ?: chapters.firstOrNull()

    var viewMode by remember { mutableStateOf(ViewMode.BILINGUAL_PARALLEL) }
    var readerTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
    var fontSizeSp by remember { mutableStateOf(16) }

    var showEditDialog by remember { mutableStateOf(false) }
    var showPolishDialog by remember { mutableStateOf(false) }
    var selectedParagraphIndex by remember { mutableStateOf(-1) }
    var selectedOriginalParagraph by remember { mutableStateOf("") }
    var selectedTranslatedParagraph by remember { mutableStateOf("") }

    val rawOriginal = remember(currentChapter, project) {
        if (project != null && currentChapter != null) {
            viewModel.fileManager.readOriginalChapter(project!!.id, currentChapter.originalFileName)
        } else ""
    }

    val rawTranslated = remember(currentChapter, project) {
        if (project != null && currentChapter != null) {
            viewModel.fileManager.readTranslatedChapter(project!!.id, currentChapter.translatedFileName)
        } else ""
    }

    val origParagraphs = remember(rawOriginal) {
        rawOriginal.split(Regex("\n{2,}|\r\n\r\n")).filter { it.isNotBlank() }
    }

    val transParagraphs = remember(rawTranslated) {
        rawTranslated.split(Regex("\n{2,}|\r\n\r\n")).filter { it.isNotBlank() }
    }

    val maxParagraphCount = maxOf(origParagraphs.size, transParagraphs.size)

    val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
    val prevChapter = if (currentIdx > 0) chapters.getOrNull(currentIdx - 1) else null
    val nextChapter = if (currentIdx != -1 && currentIdx < chapters.size - 1) chapters.getOrNull(currentIdx + 1) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentChapter?.title ?: strings.readerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = "${strings.chapterPrefix}${currentChapter?.chapterIndex ?: 1} ${strings.ofChapter} ${chapters.size}",
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
                    // Toggle Bilingual vs Translated
                    IconButton(
                        onClick = {
                            viewMode = if (viewMode == ViewMode.BILINGUAL_PARALLEL) ViewMode.TRANSLATED_ONLY else ViewMode.BILINGUAL_PARALLEL
                        },
                        modifier = Modifier.testTag("toggle_view_mode_button")
                    ) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.BILINGUAL_PARALLEL) Icons.Default.VerticalSplit else Icons.Default.Article,
                            contentDescription = strings.toggleBilingualDesc,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Font Size -
                    IconButton(
                        onClick = { if (fontSizeSp > 12) fontSizeSp -= 2 },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("A-", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    // Font Size +
                    IconButton(
                        onClick = { if (fontSizeSp < 28) fontSizeSp += 2 },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("A+", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
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
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Theme Switcher Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReaderThemeChip(strings.themeLight, ReaderTheme.LIGHT, readerTheme) { readerTheme = it }
                        ReaderThemeChip(strings.themeSepia, ReaderTheme.SEPIA, readerTheme) { readerTheme = it }
                        ReaderThemeChip(strings.themeDark, ReaderTheme.DARK, readerTheme) { readerTheme = it }
                        ReaderThemeChip(strings.themeSlate, ReaderTheme.SLATE, readerTheme) { readerTheme = it }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Prev / Next Chapter Buttons
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
                            Icon(Icons.Default.NavigateBefore, contentDescription = null)
                            Text(strings.prevChapterBtn)
                        }

                        Text(
                            text = "${currentIdx + 1} / ${chapters.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = readerTheme.text
                        )

                        TextButton(
                            onClick = { nextChapter?.let { currentChapterId = it.id } },
                            enabled = nextChapter != null,
                            modifier = Modifier.testTag("next_chapter_button")
                        ) {
                            Text(strings.nextChapterBtn)
                            Icon(Icons.Default.NavigateNext, contentDescription = null)
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
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    item {
                        Text(
                            text = currentChapter?.title ?: "",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = (fontSizeSp + 4).sp,
                                fontFamily = FontFamily.Serif
                            ),
                            color = readerTheme.text,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        HorizontalDivider(color = readerTheme.text.copy(alpha = 0.2f))
                    }

                    if (viewMode == ViewMode.TRANSLATED_ONLY) {
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
                                    colors = CardDefaults.cardColors(containerColor = readerTheme.surface.copy(alpha = 0.6f)),
                                    border = BorderStroke(0.5.dp, readerTheme.text.copy(alpha = 0.1f))
                                ) {
                                    Text(
                                        text = para,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp * 1.65).sp,
                                            fontFamily = FontFamily.Serif
                                        ),
                                        color = readerTheme.text,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    } else {
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
                                                lineHeight = (fontSizeSp * 1.6).sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = FontFamily.Serif,
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

