package com.breakyuna.noveltranslator.ui.screens.bookshelf

import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.data.model.ShelfBook
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookShelfScreen(
    viewModel: AppViewModel,
    onOpenDetail: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = platformUiStrings()
    val books by viewModel.shelfBooks.collectAsState()
    val hiddenBooks by viewModel.hiddenBooks.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<ShelfBook?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var selectedBookIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedBookIds.isEmpty()) strings.bookshelf else strings.selectedBooks(selectedBookIds.size),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (selectedBookIds.isNotEmpty()) {
                        IconButton(onClick = { selectedBookIds = emptySet() }) {
                            Icon(Icons.Default.Close, strings.finishSelection)
                        }
                    }
                },
                actions = {
                    if (selectedBookIds.isNotEmpty()) {
                        if (selectedBookIds.size == 1) {
                            IconButton(onClick = {
                                editingBook = books.firstOrNull { it.id == selectedBookIds.first() }
                                selectedBookIds = emptySet()
                            }) { Icon(Icons.Default.Edit, strings.editBook) }
                        }
                    } else {
                        IconButton(onClick = { showHidden = true }) {
                            BadgedBox(badge = { if (hiddenBooks.isNotEmpty()) Badge { Text(hiddenBooks.size.toString()) } }) {
                                Icon(Icons.Default.Inventory2, strings.removedBooks)
                            }
                        }
                        IconButton(onClick = { editing = !editing }) {
                            Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, if (editing) strings.done else strings.editShelf)
                        }
                        IconButton(onClick = { showImport = true }) { Icon(Icons.Default.Add, strings.importNovel) }
                    }
                }
            )
        }
    ) { padding ->
        if (books.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.AutoStories, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .55f))
                Spacer(Modifier.height(18.dp))
                Text(strings.emptyShelfTitle, style = MaterialTheme.typography.titleLarge)
                Text(strings.emptyShelfDescription, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { showImport = true }) { Text(strings.importFirstBook) }
                TextButton(onClick = viewModel::createSampleBook) { Text(strings.loadSample) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCoverCard(
                        book = book,
                        editing = editing,
                        selected = book.id in selectedBookIds,
                        strings = strings,
                        onClick = {
                            when {
                                selectedBookIds.isNotEmpty() -> selectedBookIds = if (book.id in selectedBookIds) selectedBookIds - book.id else selectedBookIds + book.id
                                editing -> editingBook = book
                                else -> onOpenDetail(book.id)
                            }
                        },
                        onDoubleClick = { if (!editing && selectedBookIds.isEmpty()) onContinueReading(book.id) },
                        onLongClick = {
                            selectedBookIds = if (book.id in selectedBookIds) selectedBookIds - book.id else selectedBookIds + book.id
                        }
                    )
                }
            }
        }
    }

    if (showImport) PlatformImportDialog(viewModel, strings) { showImport = false }
    if (showHidden) {
        AlertDialog(
            onDismissRequest = { showHidden = false },
            title = { Text(strings.removedBooks) },
            text = {
                Column {
                    if (hiddenBooks.isEmpty()) Text(strings.noRemovedBooks)
                    hiddenBooks.forEach { book ->
                        ListItem(
                            headlineContent = { Text(book.title) },
                            trailingContent = { TextButton(onClick = { viewModel.restoreBookToShelf(book.id) }) { Text(strings.restore) } }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHidden = false }) { Text(strings.done) } }
        )
    }
    editingBook?.let { book ->
        EditShelfBookDialog(
            book = book,
            strings = strings,
            onDismiss = { editingBook = null },
            onRename = { viewModel.renameBook(book.id, it); editingBook = null },
            onCover = { viewModel.setBookCover(book.id, it) },
            onMove = { viewModel.moveBook(book.id, it) },
            onRemove = { viewModel.removeBookFromShelf(book.id); editingBook = null },
            onDelete = { viewModel.deleteBookPermanently(book.id); editingBook = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCoverCard(
    book: ShelfBook,
    editing: Boolean,
    selected: Boolean,
    strings: PlatformUiStrings,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cover = remember(book.coverPath) { book.coverPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    Column(Modifier.widthIn(max = 150.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) androidx.compose.foundation.Image(cover, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(book.title.take(2), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (book.hasTranslationProject) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.primary
                ) { Text(strings.translationBadge, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
            }
            if (selected) {
                Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Check, null, Modifier.padding(6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else if (editing) {
                Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                    Icon(Icons.Default.Edit, null, Modifier.padding(6.dp).size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(book.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformImportDialog(viewModel: AppViewModel, strings: PlatformUiStrings, onDismiss: () -> Unit) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected
        if (selected != null) {
            name = context.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: selected.lastPathSegment ?: "imported_novel.txt"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addToShelf) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !pasteMode, onClick = { pasteMode = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(strings.localFile) }
                    SegmentedButton(selected = pasteMode, onClick = { pasteMode = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(strings.pasteText) }
                }
                if (pasteMode) {
                    OutlinedTextField(name, { name = it }, label = { Text(strings.workTitle) }, singleLine = true)
                    OutlinedTextField(pasted, { pasted = it }, label = { Text(strings.bodyText) }, minLines = 7)
                } else {
                    OutlinedButton(onClick = { launcher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }, Modifier.fillMaxWidth()) {
                        Text(if (name.isBlank()) strings.chooseFile else name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(strings.importHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (pasteMode) pasted.isNotBlank() else uri != null,
                onClick = {
                    if (pasteMode) viewModel.importPastedBook(name, pasted) else viewModel.importBookFromUri(uri!!, name)
                    onDismiss()
                }
            ) { Text(strings.importAction) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun EditShelfBookDialog(
    book: ShelfBook,
    strings: PlatformUiStrings,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onCover: (Uri) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onCover) }
    if (!confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.editBook) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(strings.workTitle) }, singleLine = true)
                    TextButton(onClick = { coverLauncher.launch(arrayOf("image/*")) }) { Text(strings.changeCover) }
                    Row {
                        TextButton(onClick = { onMove(-1) }) { Text(strings.moveForward) }
                        TextButton(onClick = { onMove(1) }) { Text(strings.moveBackward) }
                    }
                    TextButton(onClick = onRemove) { Text(strings.removeFromShelf) }
                    TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.deletePermanently) }
                }
            },
            confirmButton = { TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank()) { Text(strings.save) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(strings.deleteBookTitle(book.title)) },
            text = { Text(strings.deleteBookMessage) },
            confirmButton = { TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.deletePermanently) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(strings.cancel) } }
        )
    }
}
