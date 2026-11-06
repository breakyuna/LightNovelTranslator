package com.breakyuna.noveltranslator.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.data.model.ReadingHistoryItem
import com.breakyuna.noveltranslator.ui.components.BookCoverThumb
import com.breakyuna.noveltranslator.ui.components.apple.AppCard
import com.breakyuna.noveltranslator.ui.components.apple.AppEmptyState
import com.breakyuna.noveltranslator.ui.components.apple.AppLargeTitle
import com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings
import com.breakyuna.noveltranslator.ui.theme.Spacing
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Reading history: recent books with their saved chapter position.
 * Visual layer only — data flow and callbacks are unchanged.
 */
@Composable
fun ReadingHistoryScreen(
    viewModel: AppViewModel,
    onContinueReading: (Long, Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val history by viewModel.readingHistory.collectAsState()
    val strings = platformUiStrings()

    Box(modifier = modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppEmptyState(
                    icon = Icons.Default.History,
                    title = strings.noHistoryTitle,
                    description = strings.noHistoryDescription
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(
                    start = Spacing.compactHorizontalPadding,
                    end = Spacing.compactHorizontalPadding,
                    top = Spacing.s,
                    bottom = Spacing.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                item {
                    AppLargeTitle(
                        title = strings.readingHistoryTitle,
                        subtitle = strings.latestActivity
                    )
                }
                items(history, key = ReadingHistoryItem::bookId) { item ->
                    ReadingHistoryCard(item, strings, onContinueReading)
                }
            }
        }
    }
}

@Composable
private fun ReadingHistoryCard(
    item: ReadingHistoryItem,
    strings: PlatformUiStrings,
    onContinueReading: (Long, Long?) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentPadding = PaddingValues(Spacing.m),
        onClick = { onContinueReading(item.bookId, item.logicalChapterId) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverThumb(
                title = item.title,
                coverPath = item.coverPath,
                width = 52.dp,
                height = 72.dp
            )

            Spacer(modifier = Modifier.width(Spacing.m))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    item.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.author.isNotBlank() && item.author != "Unknown") {
                    Text(
                        item.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        strings.historyChapterLabel(item.chapterIndex, item.chapterTitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
