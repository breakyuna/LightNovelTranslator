package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
internal fun NativePricingDialog(table: PriceTable,
    save: suspend (String, CurrencyCode, ModelPricing) -> PriceTable, dismiss: () -> Unit) {
    var current by remember { mutableStateOf(table) }
    var model by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(table.currency.code) }
    var rates by remember { mutableStateOf(List(5) { "" }) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val labels = listOf(R.string.native_usage_input, R.string.native_usage_cache_read,
        R.string.native_usage_cache_write, R.string.native_usage_thinking, R.string.native_usage_output)
    fun select(key: String) {
        model = key
        rates = current.entries.firstOrNull { it.modelKey == key }?.let { price ->
            UsageComponent.entries.map { price.priceFor(it).currencyPerMillion().toPlainString() }
        } ?: List(5) { "" }
        failed = false
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.native_prices_title)) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.native_prices_notice))
            for (entry in current.entries) entry.modelKey?.let { key ->
                TextButton(onClick = { select(key) }, enabled = !busy) { Text(key) }
            }
            OutlinedTextField(model, { select(it) }, label = { Text(stringResource(R.string.native_model_name)) },
                enabled = !busy, singleLine = true)
            OutlinedTextField(currency, { currency = it }, label = { Text(stringResource(R.string.native_prices_currency)) },
                enabled = !busy && current.entries.isEmpty(), singleLine = true)
            labels.forEachIndexed { index, label ->
                OutlinedTextField(rates[index], { value -> rates = rates.toMutableList().also { it[index] = value } },
                    label = { Text(stringResource(label)) }, enabled = !busy, singleLine = true)
            }
            if (failed) Text(stringResource(R.string.native_prices_error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !busy && model.isNotBlank() && rates.all { it.isNotBlank() },
            onClick = { scope.launch {
                busy = true; failed = false
                try {
                    val parsed = rates.map { value ->
                        require(value.length <= 32 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
                        TokenPrice.ofCurrencyPerMillion(BigDecimal(value))
                    }
                    current = save(current.fingerprint, CurrencyCode.of(currency), ModelPricing(
                        parsed[0], parsed[4], parsed[1], parsed[2], ThinkingPricing.Separate(parsed[3]), model))
                    dismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            } }) { Text(stringResource(R.string.native_save)) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.native_cancel)) } })
}
