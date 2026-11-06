package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePdfPageThumbnailTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val asset = ProjectAssetSummary(9, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0,
        true, 10, "a".repeat(64), 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)

    private fun key(page: Int) = PdfPageThumbnailKey(ref, 5, asset.id, asset.sha256, page)

    @Test fun newerPageRejectsTheOlderLateResult() = runBlocking {
        supervisorScope {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstBytes = CompletableDeferred<ByteArray>()
        val secondBytes = CompletableDeferred<ByteArray>()
        val loader = NativePdfPageThumbnailLoader(PdfPageThumbnailSource { _, _, _, page ->
            if (page == 1) {
                firstStarted.complete(Unit)
                firstBytes.await()
            } else {
                secondStarted.complete(Unit)
                secondBytes.await()
            }
        })
        val first = async { loader.load(key(1), asset) { true } }
        firstStarted.await()
        val second = async { loader.load(key(2), asset) { true } }
        secondStarted.await()

        firstBytes.complete(png)
        assertStale(runCatching { first.await() }.exceptionOrNull())

        secondBytes.complete(png)
        assertTrue(second.await().contentEquals(png))
        assertNotNull(loader.cached(key(2)))
        }
    }

    @Test fun assetOrRevisionChangeRejectsTheLateResult() = runBlocking {
        supervisorScope {
        val current = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<ByteArray>()
        val loader = NativePdfPageThumbnailLoader(PdfPageThumbnailSource { _, _, _, _ ->
            started.complete(Unit)
            pending.await()
        })
        val request = async { loader.load(key(1), asset) { current.get() } }
        started.await()
        current.set(false)
        pending.complete(png)

        assertStale(runCatching { request.await() }.exceptionOrNull())
        assertNull(loader.cached(key(1)))
        }
    }

    @Test fun cacheIsBoundedAndAccessOrdered() = runBlocking {
        var calls = 0
        val loader = NativePdfPageThumbnailLoader(
            PdfPageThumbnailSource { _, _, _, _ -> calls++; png },
            maxEntries = 2,
            maxCacheBytes = 1_024,
        )
        loader.load(key(1), asset) { true }
        loader.load(key(1), asset) { true }
        assertEquals(1, calls)
        loader.load(key(2), asset) { true }
        assertNotNull(loader.cached(key(1)))
        loader.cached(key(1))
        loader.load(key(3), asset) { true }

        assertNotNull(loader.cached(key(1)))
        assertNull(loader.cached(key(2)))
        assertNotNull(loader.cached(key(3)))
    }

    @Test fun invalidBytesAndInactiveIdentityFailClosed() = runBlocking {
        val empty = NativePdfPageThumbnailLoader(PdfPageThumbnailSource { _, _, _, _ -> ByteArray(0) })
        val limit = runCatching { empty.load(key(1), asset) { true } }.exceptionOrNull()
        assertTrue(limit is TextProjectException)
        assertEquals(TextProjectException.Reason.LIMIT_EXCEEDED, (limit as TextProjectException).reason)

        val never = NativePdfPageThumbnailLoader(PdfPageThumbnailSource { _, _, _, _ ->
            error("source must not run")
        })
        val stale = runCatching { never.load(key(1), asset) { false } }.exceptionOrNull()
        assertStale(stale)
    }

    private fun assertStale(error: Throwable?) {
        assertTrue("Expected STALE_PAGE but was $error", error is TextProjectException)
        assertEquals(TextProjectException.Reason.STALE_PAGE, (error as TextProjectException).reason)
    }
}
