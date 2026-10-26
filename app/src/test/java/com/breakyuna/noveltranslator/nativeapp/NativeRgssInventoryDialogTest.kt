package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssProject
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssProjectFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeRgssInventoryDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val asset = ProjectAssetSummary(2, RelativePath("game.rgss3a"),
        ProjectAssetFormat.RPGMAKER_RGSS, 0, true, 100, "a".repeat(64), 0)

    private fun sampleProject() = RgssProject(3, listOf(
        RgssProjectFile("Scripts.rvdata2", ByteArray(6), true),
        RgssProjectFile("Data/Actors.rvdata2", ByteArray(4), true),
        RgssProjectFile("Graphics/Pictures/bg.png", ByteArray(2)),
        RgssProjectFile("Scripts.custom", ByteArray(1)),
    ))

    @Test fun inventorySortsPathsAndClassifiesMembers() {
        val inventory = rgssInventoryOf(sampleProject(), 77L)
        assertEquals(3, inventory.version)
        assertEquals(listOf("Data/Actors.rvdata2", "Graphics/Pictures/bg.png",
            "Scripts.custom", "Scripts.rvdata2"), inventory.entries.map { it.path })
        assertEquals(2, inventory.scriptCount)
        assertEquals(1, inventory.dataCount)
        assertEquals(1, inventory.otherCount)
        assertEquals(77L, inventory.archiveBytes)
        assertEquals(4L, inventory.entries.first().size)
        assertTrue(inventory.entries.first().compressed)
        assertEquals(NativeRgssEntryKind.SCRIPT, rgssEntryKind("nested/Scripts.rxdata"))
        assertEquals(NativeRgssEntryKind.DATA, rgssEntryKind("Data/System.rvdata"))
        assertEquals(NativeRgssEntryKind.OTHER, rgssEntryKind("Data/readme.txt"))
        assertTrue(inventory.entries.filter { it.kind == NativeRgssEntryKind.SCRIPT }
            .all { it.compressed })
    }

    @Test fun dialogShowsCountsMembersAndReadOnlyNotice() {
        val inventory = rgssInventoryOf(sampleProject(), 77L)
        var requested: Pair<LocalProjectRef, Long>? = null
        var closed = 0
        compose.setContent {
            NativeTheme {
                NativeRgssInventoryDialog(ref, asset, { actualRef, assetId ->
                    requested = actualRef to assetId
                    inventory
                }, { closed++ })
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(NativeRgssInventoryTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(NativeRgssInventoryDialogTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_version, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_entries, 4)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_script_count, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_data_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_other_count, 1)).assertIsDisplayed()
        compose.onNodeWithText("Data/Actors.rvdata2").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_close)).performClick()
        compose.runOnIdle {
            assertEquals(ref, requested?.first)
            assertEquals(2L, requested?.second)
            assertEquals(1, closed)
        }
    }

    @Test fun loadFailureShowsReadableErrorWithoutMemberContent() {
        compose.setContent {
            NativeTheme {
                NativeRgssInventoryDialog(ref, asset, { _, _ ->
                    throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
                }, {})
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(NativeRgssFailedTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(NativeRgssFailedTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_rgss_error_invalid)).assertIsDisplayed()
        compose.onNodeWithTag(NativeRgssInventoryTag).assertDoesNotExist()
    }
}
