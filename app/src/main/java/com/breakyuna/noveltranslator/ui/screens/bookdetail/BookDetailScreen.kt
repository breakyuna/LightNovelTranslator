package com.breakyuna.noveltranslator.ui.screens.bookdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.EditionType
import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.breakyuna.noveltranslator.ui.components.rememberAsyncBookImage

data class TargetLanguageOption(
    val code: String,
    val displayName: String,
    val defaultNameZh: String,
    val defaultNameEn: String
)

val TARGET_LANGUAGE_OPTIONS = listOf(
    TargetLanguageOption("Chinese", "简体中文 (Simplified Chinese)", "中文译本", "Chinese translation"),
    TargetLanguageOption("Traditional Chinese", "繁體中文 (Traditional Chinese)", "繁体中文译本", "Traditional Chinese translation"),
    TargetLanguageOption("English", "英语 (English)", "英文译本", "English translation"),
    TargetLanguageOption("Japanese", "日语 (Japanese / 日本語)", "日文译本", "Japanese translation"),
    TargetLanguageOption("Korean", "韩语 (Korean / 한국어)", "韩文译本", "Korean translation"),
    TargetLanguageOption("French", "法语 (French / Français)", "法文译本", "French translation"),
    TargetLanguageOption("German", "德语 (German / Deutsch)", "德文译本", "German translation"),
    TargetLanguageOption("Spanish", "西班牙语 (Spanish / Español)", "西班牙文译本", "Spanish translation"),
    TargetLanguageOption("Russian", "俄语 (Russian / Русский)", "俄文译本", "Russian translation"),
    TargetLanguageOption("Italian", "意大利语 (Italian / Italiano)", "意大利文译本", "Italian translation"),
    TargetLanguageOption("Portuguese", "葡萄牙语 (Portuguese / Português)", "葡萄牙文译本", "Portuguese translation"),
    TargetLanguageOption("Vietnamese", "越南语 (Vietnamese / Tiếng Việt)", "越南文译本", "Vietnamese translation"),
    TargetLanguageOption("Thai", "泰语 (Thai / ไทย)", "泰文译本", "Thai translation"),
    TargetLanguageOption("Indonesian", "印尼语 (Indonesian / Bahasa Indonesia)", "印尼文译本", "Indonesian translation"),
    TargetLanguageOption("Arabic", "阿拉伯语 (Arabic / العربية)", "阿拉伯文译本", "Arabic translation")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onContinueReading: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onReadChapter: (Long) -> Unit,
    onOpenEdition: (Long) -> Unit
) {
    val strings = platformUiStrings()
    val window = rememberWindowSize()
    val book by viewModel.bookPlatformRepo.observeBook(bookId).collectAsState(initial = null)
    val editions by viewModel.bookPlatformRepo.observeEditions(bookId).collectAsState(initial = emptyList())
    val chapters by viewModel.bookPlatformRepo.observeChapters(bookId).collectAsState(initial = emptyList())
    val projects by viewModel.bookPlatformRepo.observeTranslationProjects(bookId).collectAsState(initial = emptyList())
    var directoryExpanded by rememberSaveable(bookId) { mutableStateOf(false) }
    var showCreateTranslation by remember { mutableStateOf(false) }
    var exportEdition by remember { mutableStateOf<EditionEntity?>(null) }
    var showEditInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.bookDetails) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back) } },
                actions = {
                    IconButton(onClick = { showEditInfo = true }, enabled = book != null) {
                        Icon(Icons.Default.Edit, strings.editBookInfo)
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val isWideScreen = maxWidth >= 720.dp

            if (isWideScreen) {
                // Wide Screen Layout: Left side has Book info, Actions & Editions; Right side has Table of Contents
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Left Column (Other Content)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            BookHero(book = book, strings = strings, compact = false)
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = onContinueReading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.MenuBook, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.continueReading, maxLines = 1)
                                }
                                FilledTonalButton(
                                    onClick = onOpenWorkbench,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.DashboardCustomize, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.openWorkbench, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (projects.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge { Text(projects.size.toString()) }
                                    }
                                }
                            }
                        }
                        item {
                            EditionsPanel(
                                editions = editions,
                                strings = strings,
                                onOpenEdition = onOpenEdition,
                                onExport = { exportEdition = it },
                                onCreate = { showCreateTranslation = true }
                            )
                        }
                    }

                    // Right Column (Catalog / 目录)
                    ElevatedCard(
                        modifier = Modifier
                            .weight(1.25f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        WideDirectoryPanel(
                            chapters = chapters,
                            strings = strings,
                            onReadChapter = onReadChapter
                        )
                    }
                }
            } else {
                // Mobile / Compact Screen: Single Column
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Column(
                            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            BookHero(book = book, strings = strings, compact = true)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onContinueReading, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.MenuBook, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.continueReading, maxLines = 1)
                                }
                                FilledTonalButton(onClick = onOpenWorkbench, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.DashboardCustomize, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.openWorkbench, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (projects.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Badge { Text(projects.size.toString()) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(
                            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            EditionsPanel(
                                editions = editions,
                                strings = strings,
                                onOpenEdition = onOpenEdition,
                                onExport = { exportEdition = it },
                                onCreate = { showCreateTranslation = true }
                            )
                            DirectoryPanel(
                                chapters = chapters,
                                expanded = directoryExpanded,
                                strings = strings,
                                onToggle = { directoryExpanded = !directoryExpanded },
                                onReadChapter = onReadChapter
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateTranslation && editions.isNotEmpty()) {
        CreateEditionDialog(editions, strings, { showCreateTranslation = false }) { source, language, name ->
            viewModel.createTranslationEdition(bookId, source.id, language, name, onOpenEdition)
            showCreateTranslation = false
        }
    }
    exportEdition?.let { edition ->
        AlertDialog(
            onDismissRequest = { exportEdition = null },
            title = { Text("${strings.exportEdition} · ${edition.name}") },
            text = { Text(strings.exportMessage) },
            confirmButton = { TextButton(onClick = { viewModel.exportEdition(bookId, edition.id, true); exportEdition = null }) { Text("EPUB") } },
            dismissButton = { TextButton(onClick = { viewModel.exportEdition(bookId, edition.id, false); exportEdition = null }) { Text("TXT") } }
        )
    }
    if (showEditInfo && book != null) {
        EditBookInfoDialog(book!!, strings, { showEditInfo = false }) { title, author, description, language ->
            viewModel.updateBookMetadata(book!!.id, title, author, description, language)
            showEditInfo = false
        }
    }
}

@Composable
private fun BookHero(book: BookEntity?, strings: PlatformUiStrings, compact: Boolean) {
    val cover by rememberAsyncBookImage(book?.coverPath, maxDimension = 640)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(if (compact) 14.dp else 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.width(if (compact) 96.dp else 126.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) Image(cover!!, book?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(book?.title?.take(2).orEmpty(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book?.title.orEmpty(), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(book?.author?.takeUnless { it.isBlank() || it == "Unknown" } ?: strings.unknownAuthor, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SuggestionChip(onClick = {}, label = { Text(book?.originalLanguage.orEmpty()) })
                Text(
                    book?.description?.takeIf { it.isNotBlank() } ?: strings.noDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 4 else 7,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EditionsPanel(
    editions: List<EditionEntity>, strings: PlatformUiStrings, onOpenEdition: (Long) -> Unit,
    onExport: (EditionEntity) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            SectionHeader(Icons.Default.Layers, strings.editions, editions.size.toString(), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            editions.forEachIndexed { index, edition ->
                ListItem(
                    modifier = Modifier.clickable { onOpenEdition(edition.id) },
                    headlineContent = { Text(edition.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${edition.language} · ${editionTypeLabel(edition.type, strings)}${if (!edition.isComplete) " · ${strings.editionCreating}" else ""}") },
                    leadingContent = { Icon(if (edition.type == EditionType.AI_TRANSLATION.name) Icons.Default.Translate else Icons.Default.Article, null) },
                    trailingContent = { IconButton(onClick = { onExport(edition) }) { Icon(Icons.Default.Download, strings.exportEdition) } }
                )
                if (index != editions.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            FilledTonalButton(
                onClick = onCreate, enabled = editions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(strings.createEdition)
            }
        }
    }
}

@Composable
private fun WideDirectoryPanel(
    chapters: List<LogicalChapterEntity>,
    strings: PlatformUiStrings,
    onReadChapter: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredChapters = remember(chapters, searchQuery) {
        if (searchQuery.isBlank()) chapters
        else chapters.filter {
            it.canonicalTitle.contains(searchQuery, ignoreCase = true) ||
            it.chapterIndex.toString().contains(searchQuery)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FormatListNumbered, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                strings.tableOfContents,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Badge { Text("${chapters.size} 章") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索章节名称或序号...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        if (filteredChapters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isBlank()) "暂无章节" else "无匹配章节",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                items(filteredChapters, key = { it.id }) { chapter ->
                    ListItem(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onReadChapter(chapter.id) },
                        headlineContent = {
                            Text(chapter.canonicalTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(strings.chapterNumber(chapter.chapterIndex), style = MaterialTheme.typography.labelSmall)
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun DirectoryPanel(
    chapters: List<LogicalChapterEntity>, expanded: Boolean, strings: PlatformUiStrings,
    onToggle: () -> Unit, onReadChapter: (Long) -> Unit, modifier: Modifier = Modifier
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FormatListNumbered, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(strings.tableOfContents, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(strings.chapterCount(chapters.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) strings.collapse else strings.expand, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(chapters, key = { it.id }) { chapter ->
                        ListItem(
                            modifier = Modifier.clickable { onReadChapter(chapter.id) },
                            headlineContent = { Text(chapter.canonicalTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(strings.chapterNumber(chapter.chapterIndex)) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Badge { Text(count) }
    }
}

private fun editionTypeLabel(type: String, strings: PlatformUiStrings) = when (type) {
    EditionType.IMPORTED.name -> strings.importedEdition
    EditionType.AI_TRANSLATION.name -> strings.translatedEdition
    EditionType.MANUAL.name -> strings.manualEdition
    else -> type
}

@Composable
private fun EditBookInfoDialog(
    book: BookEntity, strings: PlatformUiStrings, onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var description by remember(book.id) { mutableStateOf(book.description) }
    var language by remember(book.id) { mutableStateOf(book.originalLanguage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.editInfoTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(strings.workTitle) }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text(strings.author) }, singleLine = true)
                OutlinedTextField(language, { language = it }, label = { Text(strings.originalLanguage) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(strings.description) }, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, author, description, language) }, enabled = title.isNotBlank()) { Text(strings.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEditionDialog(
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
        title = { Text(strings.createEditionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1. Source Edition Dropdown
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

                // 3. Target Language with Scrollable Dropdown Menu
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

                Text(
                    strings.editionConfigurationHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
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
