package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.breakyuna.noveltranslator.nativecore.domain.*
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
class NativeUsageEntriesDialogTest {
    @get:Rule val compose = createComposeRule()
    private val query = UsageLedgerQuery()
    private fun entry(id: String) = UsageLedgerEntry(id, UsageReceiptChannel.TRANSLATION, RequestAttemptId(id), null,
        RunIdentity(ProjectId("p"), null, RunId("run"), CommandId("command")), ModelConfigurationRef("model", 1),
        "fixture-model", UsageReceiptState.DISPATCHED, null, null, null)
    @Test fun failedOlderPageKeepsVisibleFactsAndRetriesTheSameCursor() {
        var calls = 0
        compose.setContent { NativeTheme {
            NativeUsageEntriesDialog(query, { selected, cursor ->
                assertEquals(query, selected)
                if (cursor == null) UsageLedgerPage(listOf(entry("first")), UsageLedgerCursor(query, 20))
                else {
                    assertEquals(20L, cursor.before); calls++
                    if (calls == 1) error("Read unavailable")
                    UsageLedgerPage(listOf(entry("second")), null)
                }
            }, {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-entry-first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-ledger-more").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-details-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-ledger-entry-first").assertExists()
        compose.onNodeWithTag("native-ledger-more").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-entry-second").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-ledger-entry-first").assertExists()
        compose.onNodeWithTag("native-ledger-more").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
