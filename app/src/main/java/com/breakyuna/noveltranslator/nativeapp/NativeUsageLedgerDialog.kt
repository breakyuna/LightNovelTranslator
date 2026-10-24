package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException

/** Reads explicit snapshots only while open; no model calls or unconditional high-frequency subscription. */
@Composable
internal fun NativeUsageLedgerDialog(ref: LocalProjectRef?, chat: ChatId?,
    load: suspend (UsageLedgerQuery) -> DurableUsageSummary, dismiss: () -> Unit,
    loadEntries: (suspend (UsageLedgerQuery, UsageLedgerCursor?) -> UsageLedgerPage)? = null,
    export: ((UsageLedgerQuery) -> Unit)? = null,
    loadPrices: (suspend () -> PriceTable)? = null,
    savePrice: (suspend (String, CurrencyCode, ModelPricing) -> PriceTable)? = null) {
    var scope by rememberSaveable(ref, chat) { mutableIntStateOf(if (ref == null) 0 else 1) }
    var refresh by remember { mutableIntStateOf(0) }
    var prices by remember { mutableStateOf<PriceTable?>(null) }
    var pricesFailed by remember { mutableStateOf(false) }
    var pricingVisible by remember { mutableStateOf(false) }
    LaunchedEffect(pricingVisible) {
        try { prices = loadPrices?.invoke(); pricesFailed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { pricesFailed = true }
    }
    if (pricingVisible && prices != null && savePrice != null) {
        NativePricingDialog(prices!!, savePrice, { pricingVisible = false })
        return
    }
    var details by remember(ref, chat) { mutableStateOf(false) }
    val query = when (scope) {
        2 -> UsageLedgerQuery(ref, chat)
        1 -> UsageLedgerQuery(ref)
        else -> UsageLedgerQuery()
    }
    var summary by remember(query, refresh) { mutableStateOf<DurableUsageSummary?>(null) }
    var failed by remember(query, refresh) { mutableStateOf(false) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(query, refresh) {
        try { summary = loader(query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    if (details && loadEntries != null) {
        NativeUsageEntriesDialog(query, loadEntries, { details = false }, prices)
        return
    }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_ledger_title)) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                TextButton(onClick = { scope = 0 }, enabled = scope != 0) { Text(stringResource(R.string.native_ledger_all)) }
                if (ref != null) TextButton(onClick = { scope = 1 }, enabled = scope != 1) { Text(stringResource(R.string.native_ledger_project)) }
                if (ref != null && chat != null) TextButton(onClick = { scope = 2 }, enabled = scope != 2) {
                    Text(stringResource(R.string.native_ledger_chat))
                }
            }
            when {
                failed -> Text(stringResource(R.string.native_ledger_error), Modifier.testTag("native-ledger-error"))
                summary == null -> Text(stringResource(R.string.native_working))
                else -> summary?.let { value ->
                    Text(stringResource(R.string.native_ledger_counts, value.prepared, value.dispatched,
                        value.totals.responses, value.unresolved), Modifier.testTag("native-ledger-counts"))
                    for ((label, quantity) in listOf(R.string.native_usage_input to value.totals.input,
                        R.string.native_usage_cache_read to value.totals.cacheRead,
                        R.string.native_usage_cache_write to value.totals.cacheWrite,
                        R.string.native_usage_thinking to value.totals.thinking,
                        R.string.native_usage_output to value.totals.output)) {
                        Text(stringResource(R.string.native_usage_quantity, stringResource(label), quantity.actual.toString(),
                            quantity.estimated.toString(), quantity.unknownRequests), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (loadPrices != null && savePrice != null) TextButton(onClick = { pricingVisible = true },
                enabled = prices != null && !pricesFailed) { Text(stringResource(R.string.native_prices_title)) }
            if (pricesFailed) Text(stringResource(R.string.native_prices_error))
            Text(stringResource(R.string.native_ledger_notice), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { export?.invoke(query) }, enabled = export != null,
                modifier = Modifier.testTag("native-ledger-export")) { Text(stringResource(R.string.native_ledger_export)) }
            if (loadEntries != null) TextButton(onClick = { details = true }, Modifier.testTag("native-ledger-details")) {
                Text(stringResource(R.string.native_ledger_details))
            }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(onClick = { refresh++ }, enabled = failed || summary != null) {
            Text(stringResource(R.string.native_refresh))
        } },
    )
}
