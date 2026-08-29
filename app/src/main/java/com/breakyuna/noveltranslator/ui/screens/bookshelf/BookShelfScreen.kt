package com.breakyuna.noveltranslator.ui.screens.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.breakyuna.noveltranslator.data.model.ShelfBook
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.breakyuna.noveltranslator.ui.components.rememberAsyncBookImage
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AnimatedContent(
                targetState = inSelectionMode,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "topBarTransition"
            ) { selecting ->
                if (selecting) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "已选择 ${selectedBookIds.size} 本图书",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            TextButton(onClick = { selectedBookIds = emptySet() }) {
                                Text("取消", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    selectedBookIds = if (isAllSelected) emptySet() else books.map { it.id }.toSet()
                                }
                            ) {
                                Text(
                                    if (isAllSelected) "全不选" else "全选",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(strings.bookshelf, fontWeight = FontWeight.Bold)
                        },
                        actions = {
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
                            IconButton(onClick = launchFilePicker) {
                                Icon(Icons.Default.Add, strings.importNovel)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
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
            if (books.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.AutoStories,
                        null,
                        Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = .55f)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(strings.emptyShelfTitle, style = MaterialTheme.typography.titleLarge)
                    Text(
                        strings.emptyShelfDescription,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = launchFilePicker) { Text(strings.importFirstBook) }
                    TextButton(onClick = viewModel::createSampleBook) { Text(strings.loadSample) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Subheader bar (stats / quick actions)
                    if (!inSelectionMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.AutoStories,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "共 ${books.size} 本图书",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        if (books.isNotEmpty()) {
                                            selectedBookIds = setOf(books.first().id)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("编辑", style = MaterialTheme.typography.labelLarge)
                                }
                                TextButton(
                                    onClick = launchFilePicker,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("导入", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }

                    // Bookshelf Grid with Drag & Drop
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(114.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = if (inSelectionMode) 88.dp else 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
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
                                            onOpenDetail(book.id)
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
                                    onDoubleClick = {
                                        if (!inSelectionMode) onContinueReading(book.id)
                                    }
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
                    .fillMaxWidth(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(68.dp)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 清理 (Clean / Archive)
                        BottomBarActionButton(
                            icon = Icons.Outlined.Autorenew,
                            label = "清理",
                            onClick = { showCleanDialog = true }
                        )

                        // 删除 (Delete)
                        BottomBarActionButton(
                            icon = Icons.Outlined.Delete,
                            label = "删除",
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
            title = { Text("清理图书") },
            text = {
                Text("确定将选中的 ${selectedBookIds.size} 本图书移入归档箱（移出书架）吗？图书数据不会丢失，可随时在右上角箱子中恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeBooksFromShelf(selectedBookIds)
                        selectedBookIds = emptySet()
                        showCleanDialog = false
                    }
                ) {
                    Text("确认清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("永久删除") },
            text = {
                Text("确定永久删除选中的 ${selectedBookIds.size} 本图书吗？包括所有章节和关联翻译版本数据，此操作无法撤销。")
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
                    Text("永久删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

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
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
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
    onDoubleClick: () -> Unit
) {
    val cover by rememberAsyncBookImage(book.coverPath, maxDimension = 480)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (inSelectionMode) onLongClick else null,
                onDoubleClick = onDoubleClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    cover!!,
                    book.title,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    book.title.take(3),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (book.hasTranslationProject) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        strings.translationBadge,
                        Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Selection Circle Indicator in bottom-right corner
            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(Color(0xFFE65100))
                            } else {
                                Modifier
                                    .background(Color.Black.copy(alpha = 0.25f))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选择",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Medium,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                book.hasTranslationProject && strings.bookshelf == "Bookshelf" -> "Translation configured"
                book.hasTranslationProject -> "已配置翻译"
                strings.bookshelf == "Bookshelf" -> "Translation not configured"
                else -> "未配置翻译"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
