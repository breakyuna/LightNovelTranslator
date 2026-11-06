package com.breakyuna.noveltranslator.ui.screens.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.breakyuna.noveltranslator.data.model.ShelfBook
import com.breakyuna.noveltranslator.ui.components.BookCoverFill
import com.breakyuna.noveltranslator.ui.components.apple.AppBadge
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppIconBubble
import com.breakyuna.noveltranslator.ui.components.apple.AppLargeTitle
import com.breakyuna.noveltranslator.ui.components.apple.AppSecondaryButton
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

/**
 * Bookshelf: poster grid of covers with long-press drag reordering.
 *
 * Visual layer only — every state holder, gesture and ViewModel call is kept
 * exactly as before so behavior (drag to reorder, selection mode, import
 * pipeline) is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookShelfScreen(
    viewModel: AppViewModel,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = platformUiStrings()
    val books by viewModel.shelfBooks.collectAsState()
    val hiddenBooks by viewModel.hiddenBooks.collectAsState()
    val batchImportProgress by viewModel.batchImportProgress.collectAsState()

    var displayedBooks by remember { mutableStateOf<List<ShelfBook>>(emptyList()) }
    var draggingBookId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(books) {
        if (draggingBookId == null) {
            displayedBooks = books
        }
    }

    var selectedBookIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showHidden by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<ShelfBook?>(null) }

    // Dialogs for selection bottom bar actions
    var showCleanDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val batchImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importBooksFromUris(uris = uris)
        }
    }

    val launchFilePicker = {
        // Strictly restrict to .txt and .epub MIME types
        batchImportLauncher.launch(arrayOf("text/plain", "application/epub+zip"))
    }

    val inSelectionMode = selectedBookIds.isNotEmpty()
    val isAllSelected = books.isNotEmpty() && selectedBookIds.size == books.size
    val gridState = rememberLazyGridState()
    val currentDisplayedBooks by rememberUpdatedState(displayedBooks)
    val window = rememberWindowSize()
    val gridPadding = if (window.isCompact) Spacing.compactHorizontalPadding else Spacing.expandedHorizontalPadding

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AnimatedContent(
                targetState = inSelectionMode,
                transitionSpec = { fadeIn(tween(Motion.DurationMedium)) togetherWith fadeOut(tween(Motion.DurationFast)) },
                label = "topBarTransition"
            ) { selecting ->
                if (selecting) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                strings.selectedBooks(selectedBookIds.size),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            TextButton(onClick = { selectedBookIds = emptySet() }) {
                                Text(strings.cancel, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    selectedBookIds = if (isAllSelected) emptySet() else books.map { it.id }.toSet()
                                }
                            ) {
                                Text(
                                    if (isAllSelected) strings.deselectAllBooks else strings.selectAllBooks,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    AppLargeTitle(
                        title = strings.bookshelf,
                        subtitle = strings.bookshelfSubtitle,
                        trailingContent = {
                            IconButton(onClick = { showHidden = true }) {
                                BadgedBox(badge = { if (hiddenBooks.isNotEmpty()) Badge { Text(hiddenBooks.size.toString()) } }) {
                                    Icon(Icons.Default.Inventory2, strings.removedBooks)
                                }
                            }
                            IconButton(onClick = {
                                if (books.isNotEmpty()) {
                                    selectedBookIds = setOf(books.first().id)
                                }
                            }) {
                                Icon(Icons.Default.Edit, strings.editShelf)
                            }
                            FilledIconButton(
                                onClick = launchFilePicker,
                                modifier = Modifier.padding(start = Spacing.xs)
                            ) {
                                Icon(Icons.Default.Add, strings.importNovel)
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                AnimatedVisibility(
                    visible = batchImportProgress?.isImporting == true,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    batchImportProgress?.let { progress ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.compactHorizontalPadding, vertical = Spacing.s),
                            shape = GroupedCardShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            tonalElevation = 0.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.importingBooksProgress(progress.completed, progress.total),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${((progress.completed.toFloat() / maxOf(1, progress.total)) * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(Modifier.height(Spacing.s))
                                LinearProgressIndicator(
                                    progress = { (progress.completed.toFloat() / maxOf(1, progress.total)).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(PillShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
                                )
                                if (!progress.currentBookName.isNullOrBlank()) {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(
                                        text = progress.currentBookName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (books.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AppEmptyState(
                            icon = Icons.Default.AutoStories,
                            title = strings.emptyShelfTitle,
                            description = strings.emptyShelfDescription,
                            actions = {
                                AppSecondaryButton(
                                    text = strings.importFirstBook,
                                    onClick = launchFilePicker,
                                    icon = Icons.Default.Add
                                )
                                TextButton(onClick = viewModel::createSampleBook) {
                                    Text(strings.loadSample)
                                }
                            }
                        )
                    }
                } else {
                    // Bookshelf Grid with Drag & Drop
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(122.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = gridPadding,
                            end = gridPadding,
                            top = Spacing.s,
                            bottom = if (inSelectionMode) 96.dp else Spacing.xxl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
                    ) {
                        itemsIndexed(displayedBooks, key = { _, book -> book.id }) { index, book ->
                            val isDragging = draggingBookId == book.id
                            val isSelected = book.id in selectedBookIds

                            val cardModifier = if (isDragging) {
                                Modifier
                                    .zIndex(10f)
                                    .graphicsLayer {
                                        translationX = dragOffset.x
                                        translationY = dragOffset.y
                                        scaleX = 1.06f
                                        scaleY = 1.06f
                                        shadowElevation = 18f
                                    }
                            } else {
                                Modifier
                                    .zIndex(1f)
                                    .animateItemPlacement()
                            }

                            val dragGestureModifier = if (!inSelectionMode) {
                                Modifier.pointerInput(book.id) {
                                    var totalDistance = 0f
                                    var didReorder = false
                                    var pendingOrder = currentDisplayedBooks
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingBookId = book.id
                                            dragOffset = Offset.Zero
                                            totalDistance = 0f
                                            didReorder = false
                                            pendingOrder = currentDisplayedBooks
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount
                                            totalDistance += amount.getDistance()

                                            val currentList = pendingOrder
                                            val currentIdx = currentList.indexOfFirst { it.id == book.id }
                                            if (currentIdx < 0) return@detectDragGesturesAfterLongPress

                                            val visibleItems = gridState.layoutInfo.visibleItemsInfo
                                            val currentItemInfo = visibleItems.firstOrNull { it.key == book.id }
                                                ?: return@detectDragGesturesAfterLongPress
                                            val currentCenter = Offset(
                                                x = currentItemInfo.offset.x + currentItemInfo.size.width / 2f + dragOffset.x,
                                                y = currentItemInfo.offset.y + currentItemInfo.size.height / 2f + dragOffset.y
                                            )

                                            val targetItem = visibleItems.firstOrNull { item ->
                                                item.key != book.id &&
                                                    currentCenter.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                                    currentCenter.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                            }

                                            if (targetItem != null) {
                                                val targetIdx = currentList.indexOfFirst { it.id == targetItem.key }
                                                if (targetIdx >= 0 && targetIdx != currentIdx) {
                                                    val mutableList = currentList.toMutableList()
                                                    val item = mutableList.removeAt(currentIdx)
                                                    mutableList.add(targetIdx, item)
                                                    pendingOrder = mutableList
                                                    displayedBooks = mutableList
                                                    didReorder = true

                                                    dragOffset += Offset(
                                                        (currentItemInfo.offset.x - targetItem.offset.x).toFloat(),
                                                        (currentItemInfo.offset.y - targetItem.offset.y).toFloat()
                                                    )
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (didReorder) {
                                                val orderedIds = pendingOrder.map { it.id }
                                                viewModel.updateShelfOrderList(orderedIds)
                                            } else if (totalDistance < 15f) {
                                                selectedBookIds = setOf(book.id)
                                            }
                                            draggingBookId = null
                                            dragOffset = Offset.Zero
                                        },
                                        onDragCancel = {
                                            if (didReorder) {
                                                val orderedIds = pendingOrder.map { it.id }
                                                viewModel.updateShelfOrderList(orderedIds)
                                            }
                                            draggingBookId = null
                                            dragOffset = Offset.Zero
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }

                            Box(
                                modifier = cardModifier.then(dragGestureModifier)
                            ) {
                                BookCoverCard(
                                    book = book,
                                    inSelectionMode = inSelectionMode,
                                    selected = isSelected,
                                    strings = strings,
                                    onClick = {
                                        if (inSelectionMode) {
                                            selectedBookIds = if (isSelected) {
                                                selectedBookIds - book.id
                                            } else {
                                                selectedBookIds + book.id
                                            }
                                        } else {
                                            onOpenBook(book.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (inSelectionMode) {
                                            selectedBookIds = if (isSelected) {
                                                selectedBookIds - book.id
                                            } else {
                                                selectedBookIds + book.id
                                            }
                                        } else {
                                            selectedBookIds = setOf(book.id)
                                        }
                                    },
                                    onDoubleClick = null
                                )
                            }
                        }
                    }
                }
            }

            // Selection Mode Bottom Floating Bar
            AnimatedVisibility(
                visible = inSelectionMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.l)
                    .navigationBarsPadding(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomBarActionButton(
                            icon = Icons.Outlined.Autorenew,
                            label = strings.removeFromShelf,
                            onClick = { showCleanDialog = true }
                        )

                        BottomBarActionButton(
                            icon = Icons.Outlined.Delete,
                            label = strings.deletePermanently,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { showDeleteDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Action Dialogs
    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            shape = DialogShape,
            title = { Text(strings.cleanBooksDialogTitle) },
            text = {
                Text(strings.cleanBooksDialogMessage(selectedBookIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeBooksFromShelf(selectedBookIds)
                        selectedBookIds = emptySet()
                        showCleanDialog = false
                    }
                ) {
                    Text(strings.confirmCleanBooks)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = DialogShape,
            title = { Text(strings.deleteBooksDialogTitle) },
            text = {
                Text(strings.deleteBooksDialogMessage(selectedBookIds.size))
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.deleteBooksPermanently(selectedBookIds)
                        selectedBookIds = emptySet()
                        showDeleteDialog = false
                    }
                ) {
                    Text(strings.deletePermanently)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showHidden) {
        AlertDialog(
            onDismissRequest = { showHidden = false },
            shape = DialogShape,
            title = { Text(strings.removedBooks) },
            text = {
                Column {
                    if (hiddenBooks.isEmpty()) Text(strings.noRemovedBooks)
                    hiddenBooks.forEach { book ->
                        ListItem(
                            headlineContent = { Text(book.title) },
                            trailingContent = {
                                TextButton(onClick = { viewModel.restoreBookToShelf(book.id) }) {
                                    Text(strings.restore)
                                }
                            }
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

@Composable
private fun BottomBarActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppIconBubble(
            icon = icon,
            size = 40.dp,
            shape = CircleShape,
            containerColor = tint.copy(alpha = 0.10f),
            tint = tint
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCoverCard(
    book: ShelfBook,
    inSelectionMode: Boolean,
    selected: Boolean,
    strings: PlatformUiStrings,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (inSelectionMode) onLongClick else null,
                onDoubleClick = onDoubleClick
            )
    ) {
        // Poster cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
        ) {
            BookCoverFill(
                title = book.title,
                coverPath = book.coverPath,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selected) Modifier.border(
                            width = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CoverShape
                        ) else Modifier
                    )
            )

            // Translation badge
            if (book.hasTranslationProject) {
                AppBadge(
                    text = strings.translationBadge,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.s)
                )
            }

            // Selection indicator
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.s)
                        .size(24.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                                    .background(Color.Black.copy(alpha = 0.30f))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = strings.selectedBadge,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.s))
        Text(
            book.title,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            ),
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            strings.translationStatusLabel(book.hasTranslationProject),
            style = MaterialTheme.typography.labelSmall,
            color = if (book.hasTranslationProject) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
            shape = DialogShape,
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
            shape = DialogShape,
            title = { Text(strings.deleteBookTitle(book.title)) },
            text = { Text(strings.deleteBookMessage) },
            confirmButton = { TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.deletePermanently) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(strings.cancel) } }
        )
    }
}
