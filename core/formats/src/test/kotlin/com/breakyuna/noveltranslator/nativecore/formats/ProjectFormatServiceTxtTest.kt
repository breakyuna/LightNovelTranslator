package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.AssetImportPlan
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationKind
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationReceipt
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationStatus
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ManualBatchReceipt
import com.breakyuna.noveltranslator.nativecore.domain.ManualEditReceipt
import com.breakyuna.noveltranslator.nativecore.domain.ManualTextBatch
import com.breakyuna.noveltranslator.nativecore.domain.ManualTextEdit
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetPort
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectRecoveryReport
import com.breakyuna.noveltranslator.nativecore.domain.ProjectSettings
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RegisteredProject
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemDetails
import com.breakyuna.noveltranslator.nativecore.domain.TextItemPage
import com.breakyuna.noveltranslator.nativecore.domain.TextItemQuery
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextItemWindow
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectItem
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPage
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TxtEncoding
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectFormatServiceTxtTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val path = RelativePath("book.txt")

    @Test fun splitLinesRecognizesLgSeparatorsAndKeepsCrLfSemantics() = runBlocking {
        val assets = FakeAssets(byteArrayOf())
        val service = ProjectFormatService(assets, FakeItems(emptySummary()))
        val text = "A\r\nB\rC\nD\u000bE\u000cF\u001cG\u001dH\u001eI\u0085J\u2028K\u2029L"
        service.importAsset(CommandId("import"), ref, 0, path, text.toByteArray())
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"),
            assets.plan?.units?.map { it.source },
        )
    }

    @Test fun txtBudgetIsSharedWithTheAssetLimitAndOverLimitReportsLimit() = runBlocking {
        assertEquals(FormatTextCodec.MAX_BYTES, ProjectFormatService.MAX_INPUT_BYTES)
        val oversized = ByteArray(FormatTextCodec.MAX_BYTES + 1)
        try {
            FormatTextCodec.decode(oversized)
            fail("Oversized TXT input was accepted")
        } catch (failure: InvalidFormatText) {
            assertEquals(InvalidFormatText.Reason.LIMIT, failure.reason)
        }
        val service = ProjectFormatService(FakeAssets(byteArrayOf()), FakeItems(emptySummary()))
        try {
            service.importAsset(CommandId("too-large"), ref, 0, path, oversized)
            fail("Oversized TXT asset was accepted")
        } catch (failure: TextProjectException) {
            assertEquals(TextProjectException.Reason.LIMIT_EXCEEDED, failure.reason)
        }
    }

    @Test fun exportKeepsSourceCharsetAndBomWhenTheTranslationFits() = runBlocking {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Hello".toByteArray(Charsets.UTF_8)
        val output = export(original, "Hello", "Bonjour")
        assertEquals(0xEF, output[0].toInt() and 0xff)
        assertEquals(0xBB, output[1].toInt() and 0xff)
        assertEquals(0xBF, output[2].toInt() and 0xff)
        assertEquals("Bonjour", String(output, 3, output.size - 3, Charsets.UTF_8))
    }

    @Test fun exportFallsBackToACompleteUtf8FileWhenTheSourceCharsetCannotEncodeIt() = runBlocking {
        // A single half-width katakana byte decodes as Shift_JIS but is an incomplete GB18030
        // sequence, so the codec retains Shift_JIS as the source charset.
        val original = byteArrayOf(0xA1.toByte())
        val output = export(original, "｡", "😀")
        assertTrue(output.size >= 4)
        assertEquals("😀", String(output, Charsets.UTF_8))
        assertArrayEquals("😀".toByteArray(Charsets.UTF_8), output)
    }

    @Test fun exportKeepsAnExtraLineSeparatorWhenNothingIsTranslated() = runBlocking {
        val original = "A\u2028B\u2028".toByteArray(Charsets.UTF_8)
        val rows = listOf(
            textItem("A", "", TextItemStatus.NONE, id = 1, row = 0),
            textItem("B", "", TextItemStatus.NONE, id = 2, row = 1),
        )
        assertArrayEquals(original, export(original, rows, 2))
    }

    private suspend fun export(original: ByteArray, source: String, translation: String): ByteArray =
        export(original, listOf(textItem(source, translation)), 1)

    private suspend fun export(
        original: ByteArray,
        rows: List<TextProjectItem>,
        itemCount: Int,
    ): ByteArray {
        val asset = ProjectAssetSummary(1, path, ProjectAssetFormat.TXT, 0, true,
            original.size.toLong(), TextProjectFingerprint.bytes(original), itemCount)
        val service = ProjectFormatService(
            FakeAssets(original),
            FakeItems(TextProjectSummary(ref, "fixture", 5, path, itemCount, listOf(asset),
                ProjectSettings()), rows),
        )
        val output = ByteArrayOutputStream()
        service.exportAsset(ref, 1, FormatWriteOptions()) { output }
        return output.toByteArray()
    }

    private fun textItem(
        source: String,
        translation: String,
        status: TextItemStatus = TextItemStatus.PROCESSED,
        id: Long = 1,
        row: Int = 0,
    ): TextProjectItem {
        val itemId = ItemId(id)
        return TextProjectItem(
            itemId,
            row,
            source,
            translation,
            status,
            TextProjectFingerprint.item(itemId, row, source, translation, status,
                assetId = 1, slot = row),
            assetId = 1,
            slot = row,
        )
    }

    private fun emptySummary() = TextProjectSummary(
        ref, "fixture", 0, path, 0, emptyList(), ProjectSettings(),
    )

    private class FakeAssets(private val bytes: ByteArray) : ProjectAssetPort {
        var plan: AssetImportPlan? = null

        override suspend fun assets(ref: LocalProjectRef) = emptyList<ProjectAssetSummary>()
        override suspend fun projectSettings(ref: LocalProjectRef) = ProjectSettings()
        override suspend fun editProjectSettings(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, settings: ProjectSettings): AssetMutationReceipt = error("unused")
        override suspend fun importAsset(plan: AssetImportPlan): AssetMutationReceipt {
            this.plan = plan
            return AssetMutationReceipt(plan.commandId, plan.ref, AssetMutationKind.IMPORT,
                AssetMutationStatus.COMMITTED, plan.expectedRevision + 1, 1, 0)
        }
        override suspend fun removeAsset(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, assetId: Long): AssetMutationReceipt = error("unused")
        override suspend fun reorderAssets(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, assetIds: List<Long>): AssetMutationReceipt = error("unused")
        override suspend fun setAssetEnabled(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, assetId: Long, enabled: Boolean): AssetMutationReceipt =
            error("unused")
        override suspend fun resetAsset(commandId: CommandId, ref: LocalProjectRef,
            expectedRevision: Long, assetId: Long): AssetMutationReceipt = error("unused")
        override suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray = bytes
        override suspend fun exportAsset(ref: LocalProjectRef, assetId: Long,
            destination: () -> OutputStream) = error("unused")
    }

    private class FakeItems(
        private val summary: TextProjectSummary,
        private val rows: List<TextProjectItem> = emptyList(),
    ) : TextProjectPort {
        override suspend fun registeredProjects(limit: Int): List<RegisteredProject> = error("unused")
        override suspend fun createTxt(commandId: CommandId, name: String, path: RelativePath,
            source: ByteArray, encoding: TxtEncoding): TextProjectSummary = error("unused")
        override suspend fun open(ref: LocalProjectRef): TextProjectSummary = summary
        override suspend fun readPage(ref: LocalProjectRef, afterId: Long,
            limit: Int): TextProjectPage = error("unused")
        override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long,
            limit: Int): TextProjectPage = error("unused")
        override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery,
            window: TextItemWindow): TextProjectPage = error("unused")
        override suspend fun importProject(commandId: CommandId,
            source: () -> InputStream): TextProjectSummary = error("unused")
        override suspend fun copyProject(commandId: CommandId,
            ref: LocalProjectRef): TextProjectSummary = error("unused")
        override suspend fun recoverProjects(): ProjectRecoveryReport = error("unused")
        override suspend fun cleanupTemporaryOutputs(): Int = error("unused")
        override suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails? =
            error("unused")
        override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long,
            limit: Int): TextItemPage = TextItemPage(revision,
            rows.filter { it.id.value > afterId }.take(limit))
        override suspend fun edit(command: ManualTextEdit): ManualEditReceipt = error("unused")
        override suspend fun prepareBatch(commandId: CommandId, ref: LocalProjectRef,
            revision: Long, window: TextItemWindow,
            change: com.breakyuna.noveltranslator.nativecore.domain.TextBatchChange): ManualTextBatch =
            error("unused")
        override suspend fun editBatch(batch: ManualTextBatch): ManualBatchReceipt = error("unused")
        override suspend fun receipt(ref: LocalProjectRef,
            commandId: CommandId): ManualEditReceipt? = error("unused")
        override suspend fun saveProject(ref: LocalProjectRef,
            destination: () -> OutputStream) = error("unused")
        override suspend fun exportTxt(ref: LocalProjectRef,
            destination: () -> OutputStream) = error("unused")
        override suspend fun exportBilingual(ref: LocalProjectRef, deduplicateSame: Boolean,
            destination: () -> OutputStream) = error("unused")
        override suspend fun exportOriginal(ref: LocalProjectRef,
            destination: () -> OutputStream) = error("unused")
    }
}
