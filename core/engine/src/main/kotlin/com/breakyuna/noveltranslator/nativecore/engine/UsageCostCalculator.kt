package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CostAmount
import com.breakyuna.noveltranslator.nativecore.domain.CostBasis
import com.breakyuna.noveltranslator.nativecore.domain.CostLine
import com.breakyuna.noveltranslator.nativecore.domain.CostQuote
import com.breakyuna.noveltranslator.nativecore.domain.ModelPricing
import com.breakyuna.noveltranslator.nativecore.domain.PriceLookup
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.PriceTableRef
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.TokenUsageTotal
import com.breakyuna.noveltranslator.nativecore.domain.UsageComponent
import com.breakyuna.noveltranslator.nativecore.domain.UsageCost
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import java.math.BigInteger

/**
 * Pure pricing adapter for receipts and durable totals. It does not call a model,
 * persist a ledger, or infer missing tokens. Actual and estimated projections are
 * built independently from the same price table; unknown receipt counts are carried
 * per component and never converted into a zero-token or zero-cost claim.
 *
 * Model lookup: exact key, then the table's explicit fallback entry. No match or an
 * invalid key returns [CostQuote.Unpriced] instead of a zero quote.
 */
class UsageCostCalculator(private val priceTable: PriceTable) {
    fun quote(modelKey: String?, usage: RequestUsage): CostQuote =
        quote(modelKey, Facts.from(usage))

    fun quote(modelKey: String?, totals: RequestUsageTotals): CostQuote =
        quote(modelKey, Facts.from(totals))

    private fun quote(modelKey: String?, facts: Facts): CostQuote {
        val reference = priceTable.ref()
        return when (val lookup = priceTable.lookup(modelKey)) {
            is PriceLookup.Missing -> CostQuote.Unpriced(modelKey, lookup.reason, reference)
            is PriceLookup.Matched -> CostQuote.Priced(
                requestedModelKey = modelKey,
                matchedModelKey = lookup.pricing.modelKey,
                match = lookup.match,
                priceTable = reference,
                actual = cost(CostBasis.ACTUAL, reference, lookup.pricing, facts),
                estimated = cost(CostBasis.ESTIMATED, reference, lookup.pricing, facts),
            )
        }
    }

    private fun cost(
        basis: CostBasis,
        reference: PriceTableRef,
        pricing: ModelPricing,
        facts: Facts,
    ): UsageCost {
        val lines = UsageComponent.entries.map { component ->
            val tokens = when (basis) {
                CostBasis.ACTUAL -> facts.actual.getValue(component)
                CostBasis.ESTIMATED -> facts.estimated.getValue(component)
            }
            val price = pricing.priceFor(component)
            CostLine(
                component = component,
                tokens = tokens,
                price = price,
                amount = CostAmount.ofTokens(tokens, price),
                unknownRequests = facts.unknown.getValue(component),
                rateSource = pricing.rateSourceFor(component),
            )
        }
        return UsageCost(basis, reference, lines)
    }

    /** Per-component normalized counts; maps contain all five components. */
    private class Facts(
        val actual: Map<UsageComponent, BigInteger>,
        val estimated: Map<UsageComponent, BigInteger>,
        val unknown: Map<UsageComponent, Long>,
    ) {
        init {
            val expected = UsageComponent.entries.toSet()
            require(actual.keys == expected && estimated.keys == expected && unknown.keys == expected)
            require(actual.values.all { it.signum() >= 0 } && estimated.values.all { it.signum() >= 0 })
            require(unknown.values.all { it >= 0L })
        }

        companion object {
            fun from(usage: RequestUsage): Facts {
                val actual = LinkedHashMap<UsageComponent, BigInteger>()
                val estimated = LinkedHashMap<UsageComponent, BigInteger>()
                val unknown = LinkedHashMap<UsageComponent, Long>()
                UsageComponent.entries.forEach { component ->
                    val measured = measurementFor(usage, component)
                    when (measured.source) {
                        UsageSource.ACTUAL -> {
                            actual[component] = BigInteger.valueOf(requireNotNull(measured.tokens))
                            estimated[component] = BigInteger.ZERO
                            unknown[component] = 0L
                        }
                        UsageSource.ESTIMATED -> {
                            actual[component] = BigInteger.ZERO
                            estimated[component] = BigInteger.valueOf(requireNotNull(measured.tokens))
                            unknown[component] = 0L
                        }
                        UsageSource.UNKNOWN -> {
                            actual[component] = BigInteger.ZERO
                            estimated[component] = BigInteger.ZERO
                            unknown[component] = 1L
                        }
                    }
                }
                return Facts(actual, estimated, unknown)
            }

            fun from(totals: RequestUsageTotals): Facts {
                val actual = LinkedHashMap<UsageComponent, BigInteger>()
                val estimated = LinkedHashMap<UsageComponent, BigInteger>()
                val unknown = LinkedHashMap<UsageComponent, Long>()
                UsageComponent.entries.forEach { component ->
                    val quantity = totalFor(totals, component)
                    actual[component] = quantity.actual
                    estimated[component] = quantity.estimated
                    unknown[component] = quantity.unknownRequests
                }
                return Facts(actual, estimated, unknown)
            }

            private fun measurementFor(usage: RequestUsage, component: UsageComponent): TokenMeasurement =
                when (component) {
                    UsageComponent.INPUT -> usage.input
                    UsageComponent.CACHE_READ -> usage.cacheRead
                    UsageComponent.CACHE_WRITE -> usage.cacheWrite
                    UsageComponent.THINKING -> usage.thinking
                    UsageComponent.OUTPUT -> usage.output
                }

            private fun totalFor(totals: RequestUsageTotals, component: UsageComponent): TokenUsageTotal =
                when (component) {
                    UsageComponent.INPUT -> totals.input
                    UsageComponent.CACHE_READ -> totals.cacheRead
                    UsageComponent.CACHE_WRITE -> totals.cacheWrite
                    UsageComponent.THINKING -> totals.thinking
                    UsageComponent.OUTPUT -> totals.output
                }
        }
    }
}
