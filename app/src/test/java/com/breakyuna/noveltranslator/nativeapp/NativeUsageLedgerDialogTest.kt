package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.UsageCostGroup
import com.breakyuna.noveltranslator.nativecore.engine.UsageCostSummary
import java.math.BigDecimal
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
                export = { exported = it },
                loadCosts = { UsageCostSummary(emptyList(), 0) })
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
            }, {}, loadCosts = { UsageCostSummary(emptyList(), 0) })
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
            }, {}, loadCosts = { UsageCostSummary(emptyList(), 0) })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-ledger-counts").assertDoesNotExist()
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-ledger-counts").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun costsAreGroupedByCurrencyAndUnknownOrMissingVersionsNeverShowZero() {
        fun amount(value: String) = CostAmount.ofCurrency(BigDecimal(value))
        val summary = UsageCostSummary(listOf(
            UsageCostGroup(CurrencyCode.USD, UsageCostState.KNOWN, amount("1.5"), amount("2.25"),
                2, listOf(2, 3)),
            UsageCostGroup(CurrencyCode.of("EUR"), UsageCostState.KNOWN, amount("9"), amount("0"),
                1, listOf(4)),
            UsageCostGroup(null, UsageCostState.UNKNOWN, null, null, 2, emptyList()),
            UsageCostGroup(CurrencyCode.USD, UsageCostState.MISSING_VERSION, null, null, 1, listOf(99)),
        ), 6)
        compose.setContent { NativeTheme {
            NativeUsageLedgerDialog(null, null, { DurableUsageSummary(0, 0, 0, RequestUsageTotals()) }, {},
                loadCosts = { summary })
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-ledger-cost-USD-KNOWN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-ledger-cost-USD-KNOWN")
            .assertTextContains("USD", substring = true)
            .assertTextContains("1.500000000000", substring = true)
            .assertTextContains("v2, v3", substring = true)
        compose.onNodeWithTag("native-ledger-cost-EUR-KNOWN")
            .assertTextContains("EUR", substring = true)
            .assertTextContains("9.000000000000", substring = true)
        compose.onNodeWithTag("native-ledger-cost-none-UNKNOWN")
            .assertTextContains("Currency unknown", substring = true)
            .assertTextContains("no version", substring = true)
        compose.onNodeWithTag("native-ledger-cost-USD-MISSING_VERSION")
            .assertTextContains("price version missing", substring = true)
            .assertTextContains("v99", substring = true)
        // Currencies are separate buckets; no combined total is shown.
        compose.onNodeWithText("10.500000000000", substring = true).assertDoesNotExist()
    }

    @Test fun costReadFailureIsVisibleAndRetryShowsTheEmptyStateWithoutModelCalls() {
        var calls = 0
        compose.setContent { NativeTheme {
            NativeUsageLedgerDialog(null, null, { DurableUsageSummary(0, 0, 0, RequestUsageTotals()) }, {},
                loadCosts = {
                    calls++
                    if (calls == 1) error("Synthetic cost storage failure")
                    UsageCostSummary(emptyList(), 0)
                })
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-ledger-cost-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-ledger-cost-empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
