package com.breakyuna.noveltranslator.ui.screens.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.data.model.ReadingHistoryItem
import com.breakyuna.noveltranslator.ui.components.rememberAsyncBookImage
import com.breakyuna.noveltranslator.ui.i18n.EnglishStrings
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingHistoryScreen(
    viewModel: AppViewModel,
    onContinueReading: (Long, Long?) -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val history by viewModel.readingHistory.collectAsState()
    val english = LocalAppStrings.current === EnglishStrings

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(if (english) "Reading history" else "阅读历史", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.History, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .55f))
                Spacer(Modifier.height(16.dp))
                Text(if (english) "No reading history" else "暂无阅读历史", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (english) "Books you open will appear here." else "打开并阅读书籍后，会在这里记录最近进度。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history, key = ReadingHistoryItem::bookId) { item ->
                    ReadingHistoryCard(item, english, onContinueReading, onOpenBook)
                }
            }
        }
    }
}

@Composable
private fun ReadingHistoryCard(
    item: ReadingHistoryItem,
    english: Boolean,
    onContinueReading: (Long, Long?) -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val cover by rememberAsyncBookImage(item.coverPath, maxDimension = 320)
    ElevatedCard(Modifier.fillMaxWidth().clickable { onContinueReading(item.bookId, item.logicalChapterId) }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(58.dp, 82.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) Image(cover!!, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.author.isNotBlank() && item.author != "Unknown") {
                    Text(item.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    item.chapterTitle?.let { title ->
                        if (english) "Chapter ${item.chapterIndex ?: "-"} · $title" else "第 ${item.chapterIndex ?: "-"} 章 · $title"
                    } ?: if (english) "Reading position saved" else "已保存阅读位置",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onOpenBook(item.bookId) }) { Text(if (english) "Details" else "详情") }
        }
    }
}
