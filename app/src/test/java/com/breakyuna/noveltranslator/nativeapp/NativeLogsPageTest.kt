package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticCursor
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticPage
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticRecord
import com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticSummary
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
class NativeLogsPageTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_700_000_000_000L
    private val records = listOf(
        summary("new", NativeDiagnosticLevel.INFO, "MODEL", "newest model event", 4),
        summary("error", NativeDiagnosticLevel.ERROR, "AGENT", "agent failure event", 3),
        summary("warn", NativeDiagnosticLevel.WARN, "MODEL", "older warning event", 2),
        summary("old", NativeDiagnosticLevel.INFO, "IO", "oldest io event", 1),
    )

    private fun summary(id: String, level: NativeDiagnosticLevel, category: String, message: String,
                        sequence: Long) = NativeDiagnosticSummary(id, now + sequence, sequence, level,
        category, message, hasDetail = false)

    private fun page(cursor: NativeDiagnosticCursor?, level: NativeDiagnosticLevel?,
                     category: String?, size: Int = 2, failCursor: Boolean = false): NativeDiagnosticPage {
        if (failCursor && cursor != null) throw IllegalArgumentException("stale cursor")
        val filtered = records.filter { (level == null || it.level == level) &&
            (category == null || it.category == category) }
            .filter { cursor == null || it.sequence < cursor.beforeSequence }
        val visible = filtered.take(size)
        val next = if (filtered.size > size) NativeDiagnosticCursor("store", visible.last().sequence) else null
        return NativeDiagnosticPage(visible, next)
    }

    @Test fun listPaginatesAndDetailBackReturnsToTheSameList() {
        var loaded = 0
        var details = 0
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeLogsPage(
                        includeDetails = false, back = {},
                        loadPage = { cursor, level, category ->
                            loaded++
                            page(cursor, level, category, size = 2)
                        },
                        loadDetail = { id ->
                            details++
                            NativeDiagnosticRecord(id, now, 1, NativeDiagnosticLevel.ERROR,
                                "AGENT", "detail message", detail = null)
                        },
                        loadCategories = { listOf("AGENT", "MODEL") },
                        setIncludeDetails = {}, export = {}, clear = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { loaded == 1 }
        assertEquals(0, details)
        compose.onNodeWithTag("native-log-new").assertIsDisplayed()
        compose.onNodeWithTag("native-logs-page").performScrollToNode(hasTestTag("native-logs-more"))
        compose.onNodeWithTag("native-logs-more").performClick()
        compose.waitUntil(5_000) { loaded == 2 }
        compose.onNodeWithTag("native-logs-page").performScrollToNode(hasTestTag("native-log-warn"))
        compose.onNodeWithTag("native-log-warn").assertIsDisplayed()

        compose.onNodeWithTag("native-logs-page").performScrollToNode(hasTestTag("native-log-open-new"))
        compose.onNodeWithTag("native-log-open-new").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("detail message").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, details)
        compose.onNodeWithTag("native-logs-detail").assertIsDisplayed()
        compose.onNodeWithTag("native-logs-detail-back").performScrollTo().performClick()
        compose.onNodeWithTag("native-log-new").assertIsDisplayed()
    }

    @Test fun levelAndCategoryFiltersAreSentToTheBoundedLoader() {
        var loaded = 0
        var lastLevel: NativeDiagnosticLevel? = null
        var lastCategory: String? = null
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeLogsPage(
                        includeDetails = false, back = {},
                        loadPage = { cursor, level, category ->
                            loaded++
                            lastLevel = level
                            lastCategory = category
                            page(cursor, level, category)
                        },
                        loadDetail = { null }, loadCategories = { listOf("AGENT", "MODEL") },
                        setIncludeDetails = {}, export = {}, clear = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { loaded == 1 }
        compose.onNodeWithTag("native-logs-level-ERROR").performScrollTo().performClick()
        compose.waitUntil(5_000) { lastLevel == NativeDiagnosticLevel.ERROR }
        compose.onNodeWithTag("native-logs-level-ERROR").assertIsDisplayed()

        compose.onNodeWithTag("native-logs-category").performScrollTo().performClick()
        compose.onNodeWithTag("native-logs-category-AGENT").performClick()
        compose.waitUntil(5_000) { lastCategory == "AGENT" }
        compose.waitUntil(5_000) { loaded >= 3 }
    }

    @Test fun staleCursorOffersReloadInsteadOfRetryingTheOldToken() {
        var loaded = 0
        var allowCursorFailure = true
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeLogsPage(
                        includeDetails = false, back = {},
                        loadPage = { cursor, level, category ->
                            loaded++
                            page(cursor, level, category, size = 1, failCursor = allowCursorFailure)
                        },
                        loadDetail = { null }, loadCategories = { emptyList() },
                        setIncludeDetails = {}, export = {}, clear = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { loaded == 1 }
        compose.onNodeWithTag("native-logs-page").performScrollToNode(hasTestTag("native-logs-more"))
        compose.onNodeWithTag("native-logs-more").performClick()
        compose.waitUntil(5_000) { loaded == 2 }
        compose.onNodeWithTag("native-logs-page").performScrollToNode(hasTestTag("native-logs-reload"))
        compose.onNodeWithTag("native-logs-reload").assertIsDisplayed()
        val stale = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.native_logs_stale)
        compose.onNodeWithText(stale).assertIsDisplayed()

        allowCursorFailure = false
        compose.onNodeWithTag("native-logs-reload").performClick()
        compose.waitUntil(5_000) { loaded == 3 }
        compose.onNodeWithTag("native-logs-reload").assertDoesNotExist()
    }

    @Test fun debugDetailsRequireExplicitWarningAndExportExplainsScope() {
        var details by mutableStateOf(false)
        var exported = false
        var cleared = false
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeLogsPage(
                        includeDetails = details, back = {},
                        loadPage = { cursor, level, category -> page(cursor, level, category) },
                        loadDetail = { null }, loadCategories = { listOf("MODEL") },
                        setIncludeDetails = { details = it }, export = { exported = true },
                        clear = { cleared = true },
                    )
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag("native-logs-debug").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_logs_debug_warning)).assertIsDisplayed()
        compose.onNodeWithTag("native-logs-debug-confirm").performClick()
        compose.waitUntil(5_000) { details }
        compose.onNodeWithText(context.getString(R.string.native_logs_debug_enabled)).assertIsDisplayed()

        compose.onNodeWithTag("native-logs-export").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.native_logs_export_confirm_body)).assertIsDisplayed()
        compose.onNodeWithTag("native-logs-export-confirm").performClick()
        assertEquals(true, exported)

        compose.onNodeWithTag("native-logs-clear").performClick()
        compose.onNodeWithText(context.getString(R.string.native_logs_clear_confirm)).assertIsDisplayed()
        compose.onNodeWithTag("native-logs-clear-confirm").performClick()
        assertEquals(true, cleared)
    }

    @Test fun detailsUnavailableShowNoStoredDetail() {
        compose.setContent {
            NativeTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeLogsPage(
                        includeDetails = false, back = {},
                        loadPage = { cursor, level, category -> page(cursor, level, category, size = 1) },
                        loadDetail = { id -> NativeDiagnosticRecord(id, now, 4, NativeDiagnosticLevel.INFO,
                            "MODEL", "message", detail = null) },
                        loadCategories = { emptyList() }, setIncludeDetails = {}, export = {}, clear = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("newest model event").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-log-open-new").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.native_logs_detail_none)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("message").fetchSemanticsNodes().isNotEmpty())
    }
}
