package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rendered page bytes are kept out of [NativeProjectUiState]; only the selected page is cached. */
internal data class PdfPageThumbnailKey(
    val ref: LocalProjectRef,
    val revision: Long,
    val assetId: Long,
    val assetSha256: String,
    val page: Int,
)

internal fun interface PdfPageThumbnailSource {
    suspend fun load(
        ref: LocalProjectRef,
        revision: Long,
        asset: ProjectAssetSummary,
        page: Int,
    ): ByteArray
}

/**
 * On-demand original-page thumbnails for the PDF page dialog.
 *
 * Only the page the UI asks for is rendered. At most three PNGs and 16 MiB are retained in an
 * access-ordered LRU, so a large document never accumulates every page in memory. A request that
 * is superseded by another page or whose project/asset identity no longer matches is rejected with
 * [TextProjectException.Reason.STALE_PAGE] after the background work returns.
 */
internal class NativePdfPageThumbnailLoader(
    private val source: PdfPageThumbnailSource,
    private val maxEntries: Int = 3,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
) {
    init {
        require(maxEntries in 1..8)
        require(maxCacheBytes in 1L..(MAX_THUMBNAIL_BYTES.toLong() * 8))
    }

    private val lock = Any()
    private val cache = LinkedHashMap<PdfPageThumbnailKey, ByteArray>(8, 0.75f, true)
    private var cachedBytes = 0L
    private var activeRequest: PdfPageThumbnailKey? = null

    suspend fun load(
        key: PdfPageThumbnailKey,
        asset: ProjectAssetSummary,
        current: () -> Boolean,
    ): ByteArray {
        require(key.assetId == asset.id && key.assetSha256 == asset.sha256 && key.page in 1..asset.itemCount)
        if (!current()) throw stale()
        synchronized(lock) {
            // Any new request, including a cache hit, supersedes an older in-flight page.
            activeRequest = key
            cache[key]?.let { return it }
        }
        val bytes = withContext(Dispatchers.IO) {
            source.load(key.ref, key.revision, asset, key.page)
        }
        if (bytes.isEmpty() || bytes.size > MAX_THUMBNAIL_BYTES) {
            throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
        if (!current()) throw stale()
        synchronized(lock) {
            if (activeRequest != key) throw stale()
            val previous = cache.put(key, bytes)
            if (previous != null) cachedBytes -= previous.size
            cachedBytes += bytes.size
            while (cache.size > maxEntries || cachedBytes > maxCacheBytes) {
                val oldest = cache.entries.iterator()
                if (!oldest.hasNext()) break
                val removed = oldest.next()
                oldest.remove()
                cachedBytes -= removed.value.size
            }
        }
        return bytes
    }

    internal fun cached(key: PdfPageThumbnailKey): ByteArray? = synchronized(lock) { cache[key] }

    private fun stale() = TextProjectException(TextProjectException.Reason.STALE_PAGE)

    companion object {
        const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024
        const val MAX_CACHE_BYTES = 16L * 1024 * 1024
    }
}
