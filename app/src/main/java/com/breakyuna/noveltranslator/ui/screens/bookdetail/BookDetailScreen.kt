package com.breakyuna.noveltranslator.ui.screens.bookdetail

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = if (window.isCompact) 16.dp else 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BookHero(book = book, strings = strings, compact = window.isCompact)
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
                if (window.isExpanded) {
                    Row(
                        Modifier.widthIn(max = 1120.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        EditionsPanel(editions, strings, onOpenEdition, { exportEdition = it }, { showCreateTranslation = true }, Modifier.weight(.9f))
                        DirectoryPanel(chapters, directoryExpanded, strings, { directoryExpanded = !directoryExpanded }, onReadChapter, Modifier.weight(1.1f))
                    }
                } else {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditionsPanel(editions, strings, onOpenEdition, { exportEdition = it }, { showCreateTranslation = true })
                        DirectoryPanel(chapters, directoryExpanded, strings, { directoryExpanded = !directoryExpanded }, onReadChapter)
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
    val cover = remember(book?.coverPath) { book?.coverPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
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
                if (cover != null) Image(cover, book?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
    editions: List<EditionEntity>, strings: PlatformUiStrings, onDismiss: () -> Unit,
    onCreate: (EditionEntity, String, String) -> Unit
) {
    var source by remember { mutableStateOf(editions.first()) }
    var language by remember { mutableStateOf("Chinese") }
    var name by remember { mutableStateOf(if (strings.bookshelf == "Bookshelf") "English translation" else "中文译本") }
    var sourceMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createEditionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(sourceMenu, { sourceMenu = it }) {
                    OutlinedTextField(
                        source.name, {}, readOnly = true, label = { Text(strings.sourceEdition) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sourceMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(sourceMenu, { sourceMenu = false }) {
                        editions.forEach { DropdownMenuItem({ Text(it.name) }, { source = it; sourceMenu = false }) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(strings.editionName) }, singleLine = true)
                OutlinedTextField(language, { language = it }, label = { Text(strings.targetLanguage) }, singleLine = true)
                Text(strings.editionConfigurationHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(source, language.trim().ifBlank { "Chinese" }, name.trim()) }, enabled = name.isNotBlank()) { Text(strings.create) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}
