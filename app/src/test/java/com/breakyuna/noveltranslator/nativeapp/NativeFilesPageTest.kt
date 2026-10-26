package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeFilesPageTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("project-identity"), ProjectInstanceId("local-instance-secret"),
        RecoveryGeneration(7))
    private val sha = "0123456789abcdef".repeat(4)
    private val asset = ProjectAssetSummary(1, RelativePath("source/book.epub"), ProjectAssetFormat.EPUB,
        0, true, 1_234, sha, 9)
    private val summary = TextProjectSummary(ref, "fixture", 3, RelativePath("primary.txt"), 9,
        listOf(asset))
    private val usage = NativeStorageUsage(projectBytes = 1_000, workspaceMaterialBytes = 2_000,
        workspaceArtifactBytes = 3_000, otherFilesBytes = 4_000, cacheBytes = 5_000)

    private fun state() = NativeProjectUiState(project = summary, page = NativePage.FILES, storageUsage = usage)

    @Test fun inventoryShowsAssetFactsAndFullShaOnDemand() {
        var exported = 0L
        var bilingual = 0L
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeFilesPage(state(), back = {}, exportAsset = { exported = it },
                        exportBilingual = { bilingual = it }, cleanup = {}, refreshStorage = {})
                }
            }
        }
        compose.onNodeWithTag("native-file-1").assertIsDisplayed()
        compose.onNodeWithText("source/book.epub").assertIsDisplayed()
        compose.onNodeWithTag("native-file-sha-1").assertTextContains("SHA-256: 0123456789ab…", substring = true)
        compose.onNodeWithTag("native-file-sha-toggle-1").performClick()
        compose.onNodeWithTag("native-file-sha-1").assertTextContains(sha, substring = true)
        compose.onNodeWithTag("native-file-export-1").performClick()
        compose.onNodeWithTag("native-file-export-bilingual-1").performClick()
        assertEquals(1L, exported)
        assertEquals(1L, bilingual)
    }

    @Test fun storageBreakdownAndBackupBoundariesAreExplicit() {
        var back = 0
        var cleanup = 0
        var refreshes = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeFilesPage(state(), back = { back++ }, exportAsset = {}, cleanup = { cleanup++ },
                        refreshStorage = { refreshes++ })
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithTag("native-files-page").performScrollToNode(hasTestTag("native-files-storage"))
        compose.onNodeWithTag("native-files-storage").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_files_storage_project)).assertExists()
        compose.onNodeWithText(context.getString(R.string.native_files_storage_materials)).assertExists()
        compose.onNodeWithText(context.getString(R.string.native_files_storage_artifacts)).assertExists()
        compose.onNodeWithText(context.getString(R.string.native_files_storage_cache)).assertExists()
        compose.onNodeWithText(context.getString(R.string.native_files_storage_total)).assertExists()
        compose.onNodeWithTag("native-files-page").performScrollToNode(hasTestTag("native-files-retention"))
        compose.onNodeWithTag("native-files-cleanup").performClick()
        assertEquals(1, cleanup)

        compose.onNodeWithTag("native-files-page").performScrollToNode(hasTestTag("native-files-backup-boundaries"))
        compose.onNodeWithText(context.getString(R.string.native_files_backup_warning)).assertExists()
        for (tag in listOf("native-files-boundary-lnt", "native-files-boundary-project",
            "native-files-boundary-all", "native-files-boundary-app")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.native_files_backup_all_body)).assertExists()
        compose.onNodeWithText(context.getString(R.string.native_files_backup_app_body)).assertExists()
        compose.onNodeWithTag("native-files-page").performScrollToNode(hasTestTag("native-files-back"))
        compose.onNodeWithTag("native-files-back").performClick()
        assertEquals(1, back)
        // The page reloads the projection when the project identity changes or the page opens.
        assertEquals(1, refreshes)
    }

    @Test fun pageNeverShowsInternalInstanceOrGenerationIdentity() {
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeFilesPage(state(), back = {}, exportAsset = {}, cleanup = {}, refreshStorage = {})
                }
            }
        }
        compose.onAllNodesWithText(ref.instanceId.value, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("generation 7", substring = true).assertCountEquals(0)
    }

    @Test fun rgssArchiveOffersReadOnlyInventoryWhileOtherFormatsDoNot() {
        val rgss = ProjectAssetSummary(2, RelativePath("game.rgss3a"), ProjectAssetFormat.RPGMAKER_RGSS,
            0, true, 50, sha, 0)
        val txt = ProjectAssetSummary(3, RelativePath("notes.txt"), ProjectAssetFormat.TXT,
            1, true, 10, sha, 1)
        val project = TextProjectSummary(ref, "fixture", 3, RelativePath("primary.txt"), 2,
            listOf(rgss, txt))
        var requestedRef: LocalProjectRef? = null
        var requestedId: Long? = null
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeFilesPage(NativeProjectUiState(project = project, page = NativePage.FILES,
                            storageUsage = usage), back = {}, exportAsset = {}, cleanup = {},
                        refreshStorage = {}, browseRgss = { actualRef, assetId ->
                            requestedRef = actualRef
                            requestedId = assetId
                        })
                }
            }
        }
        compose.onNodeWithTag("native-file-rgss-browse-2").performClick()
        compose.runOnIdle {
            assertEquals(ref, requestedRef)
            assertEquals(2L, requestedId)
        }
        compose.onNodeWithTag("native-files-page").performScrollToNode(hasTestTag("native-file-3"))
        compose.onNodeWithTag("native-file-rgss-browse-3").assertDoesNotExist()
    }

    @Test fun storageProjectionSumsDisjointPrivateCategories() {
        assertEquals(10_000, usage.filesBytes)
        assertEquals(5_000, usage.workspaceBytes)
        assertEquals(15_000, usage.totalPrivateBytes)
    }

    @Test fun workbenchOffersTheSameRoutedFilesPage() {
        var opened = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    Workbench(NativeProjectUiState(project = summary), cleanup = {}, export = {},
                        addAsset = {}, exportAsset = {}, openFiles = { opened++ })
                }
            }
        }
        compose.onNodeWithTag("native-workbench-files").performClick()
        assertEquals(1, opened)
    }
}
