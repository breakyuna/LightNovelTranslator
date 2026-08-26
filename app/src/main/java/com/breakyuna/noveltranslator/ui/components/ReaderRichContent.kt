package com.breakyuna.noveltranslator.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import android.webkit.WebView

private val imageMarker = Regex("\\[IMG:([^]]+)]", RegexOption.IGNORE_CASE)

/** Renders normalized reader text while resolving EPUB image markers from book-scoped storage. */
@Composable
fun ReaderRichContent(text: String, imageDirectory: File, modifier: Modifier = Modifier) {
    val parts = remember(text) {
        buildList {
            var cursor = 0
            imageMarker.findAll(text).forEach { match ->
                if (match.range.first > cursor) add(RichPart.Text(text.substring(cursor, match.range.first)))
                add(RichPart.Image(match.groupValues[1]))
                cursor = match.range.last + 1
            }
            if (cursor < text.length) add(RichPart.Text(text.substring(cursor)))
        }
    }
    Column(modifier) {
        parts.forEach { part ->
            when (part) {
                is RichPart.Text -> if (part.value.isNotBlank()) {
                    Text(
                        part.value.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f
                    )
                }
                is RichPart.Image -> {
                    val file = remember(imageDirectory, part.fileName) { File(imageDirectory, File(part.fileName).name) }
                    Spacer(Modifier.height(10.dp))
                    if (file.isFile && file.extension.equals("svg", true)) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    settings.allowFileAccess = true
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    loadUrl(file.toURI().toString())
                                }
                            },
                            update = { view ->
                                if (view.url != file.toURI().toString()) view.loadUrl(file.toURI().toString())
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 720.dp)
                        )
                    } else {
                        val bitmap by rememberAsyncBookImage(file.absolutePath, maxDimension = 1600)
                        if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = "小说插图",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
                            contentScale = ContentScale.Fit
                        )
                        } else {
                            Text("插图缺失：${part.fileName}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

private sealed interface RichPart {
    data class Text(val value: String) : RichPart
    data class Image(val fileName: String) : RichPart
}
