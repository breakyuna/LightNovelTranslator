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
class NativeUsageLedgerDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    @Test fun exportUsesTheScopeSelectedBeforeOpeningTheSystemPicker() {
        var exported: UsageLedgerQuery? = null
        compose.setContent { NativeTheme {
            NativeUsageLedgerDialog(ref, ChatId("chat"), { DurableUsageSummary(0, 0, 0, RequestUsageTotals()) }, {},
                export = { exported = it })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-counts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Chat", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("native-ledger-export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UsageLedgerQuery(ref, ChatId("chat")), exported) }
    }

    @Test fun ledgerUsesExplicitProjectAndChatScopesAndNeverRunsModels() {
        val queries = java.util.concurrent.CopyOnWriteArrayList<UsageLedgerQuery>()
        compose.setContent { NativeTheme {
            NativeUsageLedgerDialog(ref, ChatId("chat"), { query ->
                queries.add(query)
                DurableUsageSummary(0, 1, 1, RequestUsageTotals())
            }, {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-counts").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(UsageLedgerQuery(ref), queries.last()) }
        compose.onNodeWithText("Chat", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { queries.last().chat == ChatId("chat") }
        compose.runOnIdle { assertEquals(UsageLedgerQuery(ref, ChatId("chat")), queries.last()) }
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { queries.last().ref == null }
        compose.runOnIdle { assertEquals(UsageLedgerQuery(), queries.last()) }
    }
    @Test fun failedReadsShowErrorInsteadOfZeroTotalsAndRetryReadsSavedFacts() {
        var calls = 0
        compose.setContent { NativeTheme {
            NativeUsageLedgerDialog(null, null, {
                calls++
                if (calls == 1) error("Synthetic storage failure")
                DurableUsageSummary(0, 0, 0, RequestUsageTotals())
            }, {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-ledger-counts").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-counts").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
