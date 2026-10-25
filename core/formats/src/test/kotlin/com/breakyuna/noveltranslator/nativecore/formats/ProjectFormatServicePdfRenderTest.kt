package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectFormatServicePdfRenderTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val bytes = "%PDF-1.7\npreview fixture".toByteArray()
    private val sha = TextProjectFingerprint.bytes(bytes)
    private val asset = ProjectAssetSummary(7, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0,
        true, bytes.size.toLong(), sha, 3)

    @Test fun onePageIsRenderedAfterRevisionAssetAndHashChecksWithoutWrites() = runBlocking {
        val items = FakeItems(summary(asset))
        val assets = FakeAssets(bytes)
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        val service = ProjectFormatService(assets, items, PdfPageImageRenderer { input, page, count ->
            assertEquals(bytes.toList(), input.toList())
            calls.add(Triple(page, count, input.size))
            png
        })

        val result = service.renderPdfPage(ref, 5, asset, 2)

        assertTrue(result.contentEquals(png))
        assertEquals(listOf(Triple(2, 3, bytes.size)), calls)
        assertEquals(1, assets.reads)
        assertEquals(2, items.opens)
        assertEquals(0, assets.writes)
        assertEquals(0, items.writes)
    }

    @Test fun staleRevisionStopsBeforeReadingTheAsset() = runBlocking {
        val items = FakeItems(summary(asset).copy(revision = 6))
        val assets = FakeAssets(bytes)
        val service = ProjectFormatService(assets, items, renderer { _, _, _ -> error("renderer must not run") })

        assertFailure(TextProjectException.Reason.STALE_PAGE) {
            service.renderPdfPage(ref, 5, asset, 1)
        }
        assertEquals(0, assets.reads)
    }

    @Test fun changedAssetSummaryStopsBeforeReadingTheAsset() = runBlocking {
        val items = FakeItems(summary(asset.copy(sha256 = "b".repeat(64))))
        val assets = FakeAssets(bytes)
        val service = ProjectFormatService(assets, items, renderer { _, _, _ -> error("renderer must not run") })

        assertFailure(TextProjectException.Reason.STALE_PAGE) {
            service.renderPdfPage(ref, 5, asset, 1)
        }
        assertEquals(0, assets.reads)
    }

    @Test fun assetBytesMustStillMatchTheFrozenSha() = runBlocking {
        val items = FakeItems(summary(asset))
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val assets = FakeAssets(changed)
        val service = ProjectFormatService(assets, items, renderer { _, _, _ -> error("renderer must not run") })

        assertFailure(TextProjectException.Reason.CORRUPT_PROJECT) {
            service.renderPdfPage(ref, 5, asset, 1)
        }
        assertEquals(1, assets.reads)
    }

    @Test fun pageOutsideTheAssetAndNonPdfAssetsAreRejected() = runBlocking {
        val items = FakeItems(summary(asset))
        val assets = FakeAssets(bytes)
        val service = ProjectFormatService(assets, items, renderer { _, _, _ -> byteArrayOf(1) })

        assertFailure(TextProjectException.Reason.LIMIT_EXCEEDED) {
            service.renderPdfPage(ref, 5, asset, 4)
        }
        assertFailure(TextProjectException.Reason.LIMIT_EXCEEDED) {
            service.renderPdfPage(ref, 5, asset, 0)
        }
        assertFailure(TextProjectException.Reason.INVALID_FORMAT) {
            service.renderPdfPage(ref, 5, asset.copy(format = ProjectAssetFormat.TXT), 1)
        }
        assertEquals(0, assets.reads)
    }

    @Test fun oversizedPngIsRejectedAfterTheRevisionCheck() = runBlocking {
        val items = FakeItems(summary(asset))
        val assets = FakeAssets(bytes)
        val service = ProjectFormatService(assets, items, renderer { _, _, _ ->
            ByteArray(PdfPageRenderBounds.MAX_PNG_BYTES + 1)
        })

        assertFailure(TextProjectException.Reason.LIMIT_EXCEEDED) {
            service.renderPdfPage(ref, 5, asset, 1)
        }
        assertEquals(2, items.opens)
    }

    @Test fun rendererFailureIsTypedAndNoLateRevisionIsAccepted() = runBlocking {
        val items = FakeItems(summary(asset))
        val assets = FakeAssets(bytes)
        val failed = ProjectFormatService(assets, items, PdfPageImageRenderer { _, _, _ ->
            throw PdfFormatException(PdfFormatException.Reason.RENDER, 2)
        })
        val failure = try {
            failed.renderPdfPage(ref, 5, asset, 2)
            null
        } catch (error: PdfFormatException) {
            error
        }
        assertEquals(PdfFormatException.Reason.RENDER, failure?.reason)
        assertEquals(2, failure?.page)

        val lateItems = FakeItems(summary(asset))
        val late = ProjectFormatService(assets, lateItems, renderer { _, _, _ ->
            lateItems.summary = lateItems.summary.copy(revision = 6)
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        })
        assertFailure(TextProjectException.Reason.STALE_PAGE) {
            late.renderPdfPage(ref, 5, asset, 1)
        }
    }

    private fun summary(asset: ProjectAssetSummary) = TextProjectSummary(
        ref, "fixture", 5, asset.path, asset.itemCount, listOf(asset), ProjectSettings(),
    )

    private fun renderer(block: (ByteArray, Int, Int) -> ByteArray) = PdfPageImageRenderer(block)

    private fun assertFailure(reason: TextProjectException.Reason, block: suspend () -> Unit) {
        runBlocking {
            try {
                block()
                fail("Expected TextProjectException($reason)")
            } catch (failure: TextProjectException) {
                assertEquals(reason, failure.reason)
            }
        }
    }

    private class FakeAssets(private val bytes: ByteArray) : ProjectAssetPort {
        var reads = 0
        var writes = 0
        override suspend fun assets(ref: LocalProjectRef) = error("unused")
        override suspend fun projectSettings(ref: LocalProjectRef) = error("unused")
        override suspend fun editProjectSettings(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, settings: ProjectSettings): AssetMutationReceipt = error("unused")
        override suspend fun importAsset(plan: AssetImportPlan): AssetMutationReceipt = error("unused")
        override suspend fun removeAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
            assetId: Long): AssetMutationReceipt = error("unused")
        override suspend fun reorderAssets(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
            assetIds: List<Long>): AssetMutationReceipt = error("unused")
        override suspend fun setAssetEnabled(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
            assetId: Long, enabled: Boolean): AssetMutationReceipt = error("unused")
        override suspend fun resetAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
            assetId: Long): AssetMutationReceipt = error("unused")
        override suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray {
            reads++
            return bytes
        }
        override suspend fun exportAsset(ref: LocalProjectRef, assetId: Long, destination: () -> OutputStream) =
            error("unused")
    }

    private class FakeItems(var summary: TextProjectSummary) : TextProjectPort {
        var opens = 0
        var writes = 0
        override suspend fun registeredProjects(limit: Int): List<RegisteredProject> = error("unused")
        override suspend fun createTxt(commandId: CommandId, name: String, path: RelativePath, source: ByteArray,
            encoding: TxtEncoding): TextProjectSummary = error("unused")
        override suspend fun open(ref: LocalProjectRef): TextProjectSummary {
            opens++
            return summary
        }
        override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage = error("unused")
        override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long,
            limit: Int): TextProjectPage = error("unused")
        override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery,
            window: TextItemWindow): TextProjectPage = error("unused")
        override suspend fun importProject(commandId: CommandId,
            source: () -> InputStream): TextProjectSummary = error("unused")
        override suspend fun copyProject(commandId: CommandId, ref: LocalProjectRef): TextProjectSummary = error("unused")
        override suspend fun recoverProjects(): ProjectRecoveryReport = error("unused")
        override suspend fun cleanupTemporaryOutputs(): Int = error("unused")
        override suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails? = error("unused")
        override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage =
            error("unused")
        override suspend fun edit(command: ManualTextEdit): ManualEditReceipt = error("unused")
        override suspend fun prepareBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
            window: TextItemWindow, change: TextBatchChange): ManualTextBatch? = error("unused")
        override suspend fun editBatch(command: ManualTextBatch): ManualBatchReceipt = error("unused")
        override suspend fun receipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? = error("unused")
        override suspend fun saveProject(ref: LocalProjectRef, destination: () -> OutputStream) = error("unused")
        override suspend fun exportTxt(ref: LocalProjectRef, destination: () -> OutputStream) = error("unused")
        override suspend fun exportBilingual(ref: LocalProjectRef, deduplicateSame: Boolean,
            destination: () -> OutputStream) = error("unused")
        override suspend fun exportOriginal(ref: LocalProjectRef, destination: () -> OutputStream) = error("unused")
    }
}
