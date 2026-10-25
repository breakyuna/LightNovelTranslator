package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativePdfPagesDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editUsesFrozenPageIdentityAndUnsavedDispositionsDisableIt() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), ProjectSettings())
        val edits = mutableListOf<Int>()
        var closed = 0
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, { closed++ }, { error("Unexpected save") },
                editPage = { actualRef, revision, actualAsset, page ->
                    assertEquals(ref, actualRef); assertEquals(7L, revision); assertEquals(asset, actualAsset)
                    edits += page
                })
        } }
        compose.onNodeWithTag(pdfPageEditTag(2)).performClick()
        compose.runOnIdle { assertEquals(listOf(2), edits); assertEquals(1, closed) }
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_default))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.native_pdf_omit)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_pdf_apply_range)).performClick()
        compose.onNodeWithTag(pdfPageEditTag(2)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.native_pdf_edit_settings_first)).assertIsDisplayed()
    }

    @Test fun textPreviewLoadsOnlyTheSelectedPageAndDoesNotSaveDecisions() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), ProjectSettings())
        val requests = mutableListOf<Int>()
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, {}, { error("Preview cannot save") },
                loadPage = { actual, revision, source, page ->
                    assertEquals(ref, actual); assertEquals(7L, revision); assertEquals(asset.sha256, source.sha256)
                    requests.add(page)
                    TextProjectItem(ItemId(20), page, "page body", "saved target", TextItemStatus.PROCESSED,
                        ObjectFingerprint("b".repeat(64)), assetId = asset.id)
                })
        } }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_preview))[1].performClick()
        compose.onNodeWithText("page body").assertIsDisplayed()
        compose.onNodeWithText("saved target").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2), requests) }
    }

    @Test fun rangeChoiceRemainsDraftUntilExplicitSaveAndPreservesOtherSettings() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val settings = ProjectSettings("zh", "ja", false, true, true)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), settings)
        var submitted: NativeProjectSettingsChange? = null
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, {}, { submitted = it })
        } }
        val context: Context = ApplicationProvider.getApplicationContext()
        val save = context.getString(R.string.native_asset_confirm)
        compose.onNodeWithText(save).assertIsNotEnabled()
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_default))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.native_pdf_omit)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_pdf_apply_range)).performClick()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText(save).performClick()
        compose.runOnIdle {
            val result = requireNotNull(submitted)
            assertEquals(ref, result.ref); assertEquals(7L, result.revision)
            assertEquals("ooo", result.settings.pdfPages.single().decisions)
            assertEquals("zh", result.settings.targetLanguage)
            assertTrue(result.settings.promptEnabled)
            assertTrue(result.settings.writeTranslatedNames)
        }
    }

    @Test fun originalThumbnailLoadsOnlyThePreviewedPageAndKeepsTextPreview() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), ProjectSettings())
        val pages = mutableListOf<Int>()
        val texts = mutableListOf<Int>()
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, {}, { error("Preview cannot save") },
                loadPage = { _, _, _, page ->
                    texts.add(page)
                    TextProjectItem(ItemId(20), page, "page body $page", "saved target", TextItemStatus.PROCESSED,
                        ObjectFingerprint("b".repeat(64)), assetId = asset.id)
                },
                loadThumbnail = { actual, revision, source, page ->
                    assertEquals(ref, actual); assertEquals(7L, revision); assertEquals(asset, source)
                    pages.add(page)
                    pngBytes()
                })
        } }
        compose.runOnIdle { assertTrue(pages.isEmpty()) }
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_preview))[0].performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(PdfThumbnailImageTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("page body 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1), pages)
            assertEquals(listOf(1), texts)
        }
    }

    @Test fun thumbnailLoadingStateIsVisibleUntilTheRenderReturns() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), ProjectSettings())
        val pending = CompletableDeferred<ByteArray>()
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, {}, { error("Preview cannot save") },
                loadThumbnail = { _, _, _, _ -> pending.await() })
        } }
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_preview))[0].performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(PdfThumbnailLoadingTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(PdfThumbnailLoadingTag).assertIsDisplayed()
        pending.complete(pngBytes())
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(PdfThumbnailImageTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun thumbnailFailureDoesNotHideTheTextPreview() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
        val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 0, true, 10, "a".repeat(64), 3)
        val project = TextProjectSummary(ref, "test", 7, asset.path, 3, listOf(asset), ProjectSettings())
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { NativeTheme {
            NativePdfPagesDialog(NativeProjectUiState(project = project), asset, {}, { error("Preview cannot save") },
                loadPage = { _, _, _, page ->
                    TextProjectItem(ItemId(20), page, "page body", "saved target", TextItemStatus.PROCESSED,
                        ObjectFingerprint("b".repeat(64)), assetId = asset.id)
                },
                loadThumbnail = { _, _, _, _ -> error("render failed") })
        } }
        compose.onAllNodesWithText(context.getString(R.string.native_pdf_preview))[0].performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(PdfThumbnailFailedTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(PdfThumbnailFailedTag).assertIsDisplayed()
        compose.onNodeWithText("page body").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_pdf_thumbnail_failed)).assertIsDisplayed()
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
