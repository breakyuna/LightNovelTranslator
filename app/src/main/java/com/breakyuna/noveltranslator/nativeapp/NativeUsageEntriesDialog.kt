package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeUsageEntriesDialog(query: UsageLedgerQuery,
    load: suspend (UsageLedgerQuery, UsageLedgerCursor?) -> UsageLedgerPage, dismiss: () -> Unit, prices: PriceTable? = null) {
    var refresh by remember(query) { mutableIntStateOf(0) }
    var entries by remember(query, refresh) { mutableStateOf<List<UsageLedgerEntry>>(emptyList()) }
    var cursor by remember(query, refresh) { mutableStateOf<UsageLedgerCursor?>(null) }
    var loading by remember(query, refresh) { mutableStateOf(false) }
    var failed by remember(query, refresh) { mutableStateOf(false) }
    val loader by rememberUpdatedState(load)
    val scope = rememberCoroutineScope()
    suspend fun fetch(append: Boolean) {
        if (loading) return
        loading = true; failed = false
        try {
            val next = loader(query, if (append) cursor else null)
            val nextCursor = next.next
            require(nextCursor == null || nextCursor.query == query && (!append || nextCursor.before < requireNotNull(cursor).before))
            entries = ((if (append) entries else emptyList()) + next.entries).distinctBy { it.id }
            cursor = nextCursor
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    LaunchedEffect(query, refresh) { fetch(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.native_ledger_details)) },
        text = { Column {
            Text(stringResource(R.string.native_ledger_details_notice), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth().testTag("native-ledger-entry-${entry.id}")) { Column(Modifier.padding(8.dp)) {
                        Text(entry.model ?: stringResource(R.string.native_ledger_model_unknown))
                        Text(stringResource(if (entry.channel == UsageReceiptChannel.AGENT) R.string.native_ledger_agent else R.string.native_ledger_translation))
                        Text(stringResource(when (entry.state) {
                            UsageReceiptState.PREPARED -> R.string.native_ledger_prepared
                            UsageReceiptState.DISPATCHED -> R.string.native_ledger_uncertain
                            UsageReceiptState.RECEIVED -> R.string.native_ledger_received
                        }))
                        if (entry.failure != null) Text(stringResource(R.string.native_ledger_response_failed))
                        entry.usage?.let { usage ->
                            val quote = remember(prices, entry.id, usage) { prices?.let {
                                com.breakyuna.noveltranslator.nativecore.engine.UsageCostCalculator(it).quote(entry.model, usage)
                            } }
                            when (quote) {
                                is CostQuote.Priced -> {
                                    Text(stringResource(R.string.native_prices_quote, quote.priceTable.currency.code,
                                        quote.actual.knownTotal.toPlainString(), quote.estimated.knownTotal.toPlainString(),
                                        quote.priceTable.version))
                                    if (quote.hasUnknownUsage) Text(stringResource(R.string.native_prices_incomplete))
                                }
                                else -> Text(stringResource(R.string.native_prices_missing))
                            }
                            val totals = RequestUsageTotals().add(usage)
                            for ((label, quantity) in listOf(R.string.native_usage_input to totals.input,
                                R.string.native_usage_cache_read to totals.cacheRead, R.string.native_usage_cache_write to totals.cacheWrite,
                                R.string.native_usage_thinking to totals.thinking, R.string.native_usage_output to totals.output)) {
                                Text(stringResource(R.string.native_usage_quantity, stringResource(label), quantity.actual.toString(),
                                    quantity.estimated.toString(), quantity.unknownRequests), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } }
                }
                if (failed) item { Text(stringResource(R.string.native_ledger_error), Modifier.testTag("native-ledger-details-error")) }
                if (!loading && !failed && entries.isEmpty()) item { Text(stringResource(R.string.native_ledger_empty)) }
                if (loading || failed || cursor != null) item(key = "ledger-pagination") {
                    TextButton(onClick = { scope.launch { fetch(entries.isNotEmpty()) } }, enabled = !loading,
                        modifier = Modifier.testTag("native-ledger-more")) {
                        Text(stringResource(if (loading) R.string.native_working else if (failed) R.string.native_tasks_retry_load else R.string.native_ledger_more))
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
        dismissButton = { TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.native_refresh)) } })
}
