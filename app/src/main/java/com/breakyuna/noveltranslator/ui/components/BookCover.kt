package com.breakyuna.noveltranslator.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.ui.theme.CoverShape
import com.breakyuna.noveltranslator.ui.theme.LocalAppColors

/**
 * The one cover renderer for the whole app: decodes artwork off the UI thread
 * via [rememberAsyncBookImage], falls back to a tonal placeholder with the
 * title, and paints the spine shading that gives covers their poster feel.
 */
@Composable
fun BookCover(
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp = width * 4f / 3f,
    shape: Shape = CoverShape
) {
    val cover by rememberAsyncBookImage(coverPath, maxDimension = (width.value * 2.4f).toInt().coerceIn(240, 720))
    val appColors = LocalAppColors.current

    Surface(
        modifier = modifier
            .width(width)
            .height(height),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            appColors.coverPlaceholderStart,
                            appColors.coverPlaceholderEnd
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = title.take(4),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = appColors.onCoverPlaceholder,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Spine shading + crease highlight.
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(height)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.30f),
                                Color.Black.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(height)
                    .align(Alignment.CenterStart)
                    .padding(start = 9.dp)
                    .background(Color.White.copy(alpha = 0.16f))
            )
        }
    }
}

/** Small cover thumbnail for list rows (history / workbench / tasks). */@Composable
fun BookCoverThumb(
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
    width: Dp = 54.dp,
    height: Dp = 74.dp
) {
    val cover by rememberAsyncBookImage(coverPath, maxDimension = 320)
    val appColors = LocalAppColors.current

    Surface(
        modifier = modifier
            .width(width)
            .height(height),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            appColors.coverPlaceholderStart,
                            appColors.coverPlaceholderEnd
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = appColors.onCoverPlaceholder,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(height)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent)
                        )
                    )
            )
        }
    }
}

/**
 * Cover that fills its parent slot; the caller controls the aspect ratio
 * (bookshelf posters use 0.72 via [androidx.compose.foundation.layout.aspectRatio]).
 */
@Composable
fun BookCoverFill(
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
    shape: Shape = CoverShape
) {
    val cover by rememberAsyncBookImage(coverPath, maxDimension = 720)
    val appColors = LocalAppColors.current

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            appColors.coverPlaceholderStart,
                            appColors.coverPlaceholderEnd
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = title.take(4),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = appColors.onCoverPlaceholder,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(10.dp)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.30f),
                                Color.Black.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 9.dp)
                    .background(Color.White.copy(alpha = 0.16f))
            )
        }
    }
}
