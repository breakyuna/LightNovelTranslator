package com.breakyuna.noveltranslator.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private object BookImageCache {
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Synchronized fun get(key: String): Bitmap? = bitmaps.get(key)
    @Synchronized fun put(key: String, bitmap: Bitmap) = bitmaps.put(key, bitmap)
}

/** Decodes local covers and illustrations off the UI thread and keeps only a bounded cache. */
@Composable
fun rememberAsyncBookImage(path: String?, maxDimension: Int = 960): State<ImageBitmap?> {
    val fileStamp = path?.let { File(it).takeIf(File::isFile)?.lastModified() } ?: 0L
    return produceState<ImageBitmap?>(initialValue = null, path, fileStamp, maxDimension) {
        value = if (path.isNullOrBlank() || fileStamp == 0L) null else withContext(Dispatchers.IO) {
            val cacheKey = "$path:$fileStamp:$maxDimension"
            BookImageCache.get(cacheKey)?.asImageBitmap() ?: decodeSampled(path, maxDimension)?.let { bitmap ->
                BookImageCache.put(cacheKey, bitmap)
                bitmap.asImageBitmap()
            }
        }
    }
}

private fun decodeSampled(path: String, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}
