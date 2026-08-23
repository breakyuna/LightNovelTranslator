package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.llm.TokenCalculator
import com.example.data.model.ChapterEntity
import com.example.data.model.ChapterStatus
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.RoseAccent

@Composable
fun ChapterItemCard(
    chapter: ChapterEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTranslate: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    val statusColor = when (chapter.status) {
        ChapterStatus.COMPLETED -> EmeraldAccent
        ChapterStatus.TRANSLATING -> MaterialTheme.colorScheme.secondary
        ChapterStatus.ERROR -> RoseAccent
        ChapterStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val statusText = when (chapter.status) {
        ChapterStatus.COMPLETED -> strings.chapterStatusTranslated
        ChapterStatus.TRANSLATING -> strings.chapterStatusTranslating
        ChapterStatus.ERROR -> strings.chapterStatusError
        ChapterStatus.PENDING -> strings.chapterStatusPending
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("chapter_item_${chapter.chapterIndex}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected)
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chapter Number Badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${chapter.chapterIndex}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColor
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${chapter.originalWordCount} ${strings.wordsUnit}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    if (chapter.status == ChapterStatus.COMPLETED && chapter.estimatedCost > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = TokenCalculator.formatCost(chapter.estimatedCost),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = EmeraldAccent
                            )
                        )
                    }
                }
            }

            // Action Buttons
            Row {
                IconButton(
                    onClick = onPreview,
                    modifier = Modifier.testTag("preview_chapter_${chapter.chapterIndex}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = strings.previewRead,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onTranslate,
                    modifier = Modifier.testTag("translate_chapter_${chapter.chapterIndex}")
                ) {
                    Icon(
                        imageVector = if (chapter.status == ChapterStatus.COMPLETED) Icons.Default.Replay else Icons.Default.Translate,
                        contentDescription = if (chapter.status == ChapterStatus.COMPLETED) strings.reTranslateChapter else strings.translateChapter,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
