package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.breakyuna.noveltranslator.legacy.LegacyExportFailure
import com.breakyuna.noveltranslator.legacy.LegacyExportFile
import com.breakyuna.noveltranslator.legacy.LegacyExportManifest
import com.breakyuna.noveltranslator.legacy.LegacyExportPhase
import com.breakyuna.noveltranslator.legacy.LegacyExportProgress
import com.breakyuna.noveltranslator.legacy.LegacyExportResult
import com.breakyuna.noveltranslator.legacy.LegacyExportSourceInfo
import java.io.File
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
class NativeLegacyExportPanelTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingActions : LegacyExportActions {
        var started: String? = null
        var cancels = 0
        override fun start(outputDirectory: String) { started = outputDirectory }
        override fun cancel() { cancels++ }
    }

    private val labels = NativeLegacyExportLabels(
        title = "Legacy export",
        description = "Read-only export",
        privacyNote = "No credentials are copied and legacy data is not deleted.",
        outputDirectory = "Output directory",
        chooseDirectory = "Choose directory",
        noDirectory = "No directory selected",
        start = "Start",
        cancel = "Cancel",
        close = "Close",
        idle = "Ready",
        running = "Running",
        completed = "Completed",
        failed = "Failed",
        cancelled = "Cancelled",
        failures = "Failures",
        noFailures = "No failures",
        progressText = { completed, total -> "$completed / $total" },
        phaseText = { phase -> phase.name },
        resultText = { files, bytes, failures -> "$files files, $bytes bytes, $failures failures" },
        failureText = { failure ->
            "${failure.scope}: ${failure.message}" + (failure.path?.let { " ($it)" } ?: "")
        },
    )

    @Test
    fun startRequiresAnExplicitDirectoryAndOnlyCallsTheAbstractAction() {
        val state = mutableStateOf(NativeLegacyExportState())
        val actions = RecordingActions()
        var picked = 0
        var dismissed = 0
        compose.setContent {
            NativeTheme {
                NativeLegacyExportPanel(state.value, actions, labels, { picked++ }, { dismissed++ })
            }
        }
        compose.onNodeWithTag("native-legacy-export-start").assertIsNotEnabled()
        compose.onNodeWithTag("native-legacy-export-choose").performClick()
        assertEquals(1, picked)
        compose.runOnIdle {
            state.value = state.value.copy(
                outputDirectory = "content://tree/export",
                outputDisplayName = "export",
            )
        }
        compose.onNodeWithTag("native-legacy-export-start").assertIsEnabled().performClick()
        assertEquals("content://tree/export", actions.started)
        assertEquals(0, actions.cancels)
        compose.onNodeWithTag("native-legacy-export-close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun runningProgressCanOnlyCancelAndCannotBeDismissed() {
        val state = NativeLegacyExportState(
            outputDirectory = "content://tree/export",
            outputDisplayName = "export",
            status = NativeLegacyExportStatus.RUNNING,
            progress = LegacyExportProgress(
                phase = LegacyExportPhase.BOOK,
                completed = 2,
                total = 5,
                path = "book.txt",
            ),
        )
        val actions = RecordingActions()
        var dismissed = 0
        compose.setContent {
            NativeTheme {
                NativeLegacyExportPanel(state, actions, labels, {}, { dismissed++ })
            }
        }
        compose.onNodeWithTag("native-legacy-export-progress").assertIsDisplayed()
        compose.onNodeWithText("BOOK · 2 / 5").assertIsDisplayed()
        compose.onNodeWithTag("native-legacy-export-start").assertDoesNotExist()
        compose.onNodeWithTag("native-legacy-export-close").assertDoesNotExist()
        compose.onNodeWithTag("native-legacy-export-cancel").performClick()
        assertEquals(1, actions.cancels)
        assertEquals(0, dismissed)
    }

    @Test
    fun completedResultShowsServiceFailuresAndOffersNoOverwritingRetry() {
        val failure = LegacyExportFailure(
            scope = "book",
            bookId = 7,
            editionId = 8,
            path = "exports/book.txt",
            message = "redacted",
        )
        val manifest = LegacyExportManifest(
            generatedAt = "2026-10-27T00:00:00.000Z",
            generatedAtEpochMillis = 0,
            sourceDatabase = LegacyExportSourceInfo("legacy.db", 3, 1_024, "a".repeat(64)),
            books = 1,
            editions = 1,
            files = listOf(
                LegacyExportFile("book.txt", "txt", 30, "b".repeat(64), 1),
                LegacyExportFile("book.epub", "epub", 12, "c".repeat(64), 1),
            ),
            failures = listOf(failure),
            warnings = emptyList(),
        )
        val state = NativeLegacyExportState(
            outputDirectory = "content://tree/export",
            outputDisplayName = "export",
            status = NativeLegacyExportStatus.COMPLETED,
            result = LegacyExportResult(File("/out/lnt-export"), manifest, File("/out/lnt-export/manifest.json")),
        )
        val actions = RecordingActions()
        var dismissed = 0
        compose.setContent {
            NativeTheme {
                NativeLegacyExportPanel(state, actions, labels, {}, { dismissed++ })
            }
        }
        compose.onNodeWithText("2 files, 42 bytes, 1 failures").assertIsDisplayed()
        compose.onNodeWithText("Output directory: export").assertIsDisplayed()
        compose.onNodeWithText("book: redacted (exports/book.txt)").assertIsDisplayed()
        compose.onNodeWithText("No credentials are copied and legacy data is not deleted.").assertIsDisplayed()
        compose.onNodeWithTag("native-legacy-export-start").assertDoesNotExist()
        compose.onNodeWithTag("native-legacy-export-choose").assertIsNotEnabled()
        compose.onNodeWithTag("native-legacy-export-close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, actions.cancels)
    }

    @Test
    fun aFailedExportKeepsTheDirectoryForExplicitRetry() {
        val state = NativeLegacyExportState(
            outputDirectory = "content://tree/export",
            outputDisplayName = "export",
            status = NativeLegacyExportStatus.FAILED,
            failures = listOf(LegacyExportFailure("book", message = "unreadable edition")),
            message = "Export failed; the legacy database was not changed.",
        )
        val actions = RecordingActions()
        compose.setContent {
            NativeTheme {
                NativeLegacyExportPanel(state, actions, labels, {}, {})
            }
        }
        compose.onNodeWithText("Export failed; the legacy database was not changed.").assertIsDisplayed()
        compose.onNodeWithText("book: unreadable edition").assertIsDisplayed()
        compose.onNodeWithTag("native-legacy-export-start").assertIsEnabled().performClick()
        assertEquals("content://tree/export", actions.started)
        assertEquals(0, actions.cancels)
    }
}
