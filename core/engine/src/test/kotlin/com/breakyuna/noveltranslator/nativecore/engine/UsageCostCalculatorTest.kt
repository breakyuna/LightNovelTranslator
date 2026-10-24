package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CostBasis
import com.breakyuna.noveltranslator.nativecore.domain.CostQuote
import com.breakyuna.noveltranslator.nativecore.domain.CostStatus
import com.breakyuna.noveltranslator.nativecore.domain.CostUnavailableReason
import com.breakyuna.noveltranslator.nativecore.domain.CurrencyCode
import com.breakyuna.noveltranslator.nativecore.domain.ModelPricing
import com.breakyuna.noveltranslator.nativecore.domain.PriceMatch
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.ThinkingPricing
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.TokenPrice
import com.breakyuna.noveltranslator.nativecore.domain.UsageComponent
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostCalculatorTest {
    private fun price(amount: String): TokenPrice = TokenPrice.ofCurrencyPerMillion(BigDecimal(amount))

    private fun pricing(
        modelKey: String?,
        thinking: ThinkingPricing = ThinkingPricing.Separate(price("20")),
    ): ModelPricing = ModelPricing(
        input = price("3"),
        output = price("15"),
        cacheRead = price("0.30"),
        cacheWrite = price("3.75"),
        thinking = thinking,
        modelKey = modelKey,
    )

    private val table = PriceTable(CurrencyCode.USD, 7, listOf(
        pricing("model-a"),
        ModelPricing(
            input = price("1"),
            output = price("2"),
            cacheRead = price("0.10"),
            cacheWrite = price("0.20"),
            thinking = ThinkingPricing.AsOutput,
            modelKey = null,
        ),
    ))
    private val calculator = UsageCostCalculator(table)

    private val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)

    private fun actual(tokens: Long): TokenMeasurement = TokenMeasurement(tokens, UsageSource.ACTUAL)
    private fun estimated(tokens: Long): TokenMeasurement = TokenMeasurement(tokens, UsageSource.ESTIMATED)

    private fun usage(
        input: TokenMeasurement = actual(0),
        cacheRead: TokenMeasurement = actual(0),
        cacheWrite: TokenMeasurement = actual(0),
        thinking: TokenMeasurement = actual(0),
        output: TokenMeasurement = actual(0),
    ): RequestUsage = RequestUsage(input, cacheRead, cacheWrite, thinking, output)

    private fun priced(quote: CostQuote): CostQuote.Priced {
        assertTrue("Expected a priced quote but got $quote", quote is CostQuote.Priced)
        return quote as CostQuote.Priced
    }

    @Test fun allFiveComponentsHaveTheirOwnActualAndEstimatedRates() {
        val totals = RequestUsageTotals()
            .add(usage(
                input = actual(1_000_000),
                cacheRead = actual(1_000_000),
                cacheWrite = actual(1_000_000),
                thinking = actual(1_000_000),
                output = actual(1_000_000),
            ))
            .add(usage(
                input = estimated(2_000_000),
                cacheRead = estimated(2_000_000),
                cacheWrite = estimated(2_000_000),
                thinking = estimated(2_000_000),
                output = estimated(2_000_000),
            ))

        val quote = priced(calculator.quote("model-a", totals))

        assertEquals("3.000000000000", quote.actual.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals("0.300000000000", quote.actual.line(UsageComponent.CACHE_READ).amount.toPlainString())
        assertEquals("3.750000000000", quote.actual.line(UsageComponent.CACHE_WRITE).amount.toPlainString())
        assertEquals("20.000000000000", quote.actual.line(UsageComponent.THINKING).amount.toPlainString())
        assertEquals("15.000000000000", quote.actual.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("42.050000000000", quote.actual.knownTotal.toPlainString())

        assertEquals("6.000000000000", quote.estimated.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals("0.600000000000", quote.estimated.line(UsageComponent.CACHE_READ).amount.toPlainString())
        assertEquals("7.500000000000", quote.estimated.line(UsageComponent.CACHE_WRITE).amount.toPlainString())
        assertEquals("40.000000000000", quote.estimated.line(UsageComponent.THINKING).amount.toPlainString())
        assertEquals("30.000000000000", quote.estimated.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("84.100000000000", quote.estimated.knownTotal.toPlainString())

        assertEquals(UsageComponent.THINKING, quote.actual.line(UsageComponent.THINKING).rateSource)
        assertEquals(CostStatus.COMPLETE, quote.status)
    }

    @Test fun actualAndEstimatedAreNeverAddedTogether() {
        val totals = RequestUsageTotals()
            .add(usage(input = actual(1_000_000), output = actual(1_000_000)))
            .add(usage(input = estimated(2_000_000), output = estimated(2_000_000)))

        val quote = priced(calculator.quote("model-a", totals))

        assertEquals(CostBasis.ACTUAL, quote.actual.basis)
        assertEquals(CostBasis.ESTIMATED, quote.estimated.basis)
        assertEquals("18.000000000000", quote.actual.knownTotal.toPlainString())
        assertEquals("36.000000000000", quote.estimated.knownTotal.toPlainString())
        assertEquals("15.000000000000", quote.actual.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("30.000000000000", quote.estimated.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertNotEquals(quote.actual.knownTotal, quote.estimated.knownTotal)
    }

    @Test fun cacheReadAndWriteUseCacheRatesNotInputOrOutput() {
        val totals = RequestUsageTotals()
            .add(usage(cacheRead = actual(1_000_000), cacheWrite = actual(1_000_000)))
        val cost = priced(calculator.quote("model-a", totals)).actual

        assertEquals("0.300000000000", cost.line(UsageComponent.CACHE_READ).amount.toPlainString())
        assertEquals("3.750000000000", cost.line(UsageComponent.CACHE_WRITE).amount.toPlainString())
        assertEquals("4.050000000000", cost.knownTotal.toPlainString())
        assertTrue(cost.line(UsageComponent.INPUT).amount.isZero)
        assertTrue(cost.line(UsageComponent.OUTPUT).amount.isZero)
    }

    @Test fun thinkingUsesSeparateRateOrExplicitOutputRateButIsNeverFree() {
        val separate = priced(calculator.quote("model-a", usage(thinking = actual(1_000_000))))
        assertEquals("20.000000000000", separate.actual.line(UsageComponent.THINKING).amount.toPlainString())
        assertEquals(UsageComponent.THINKING, separate.actual.line(UsageComponent.THINKING).rateSource)

        val asOutputTable = PriceTable(CurrencyCode.USD, 1, listOf(
            ModelPricing(
                input = price("3"),
                output = price("15"),
                cacheRead = price("0.30"),
                cacheWrite = price("3.75"),
                thinking = ThinkingPricing.AsOutput,
                modelKey = "model-a",
            ),
        ))
        val asOutput = priced(UsageCostCalculator(asOutputTable)
            .quote("model-a", usage(thinking = actual(1_000_000))))
        val thinking = asOutput.actual.line(UsageComponent.THINKING)
        assertEquals("15.000000000000", thinking.amount.toPlainString())
        assertEquals(price("15"), thinking.price)
        assertEquals(UsageComponent.OUTPUT, thinking.rateSource)
        assertFalse(thinking.amount.isZero)
    }

    @Test fun singleRequestKeepsActualEstimatedAndUnknownPerComponent() {
        val quote = priced(calculator.quote("model-a", usage(
            input = actual(1_000),
            cacheRead = unknown,
            cacheWrite = estimated(2_000),
            thinking = actual(0),
            output = estimated(2_000_000),
        )))

        assertEquals("0.003000000000", quote.actual.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals("0.000000000000", quote.actual.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("0.030000000000", quote.estimated.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("0.007500000000", quote.estimated.line(UsageComponent.CACHE_WRITE).amount.toPlainString())
        assertEquals("0.003000000000", quote.actual.knownTotal.toPlainString())
        assertEquals("30.007500000000", quote.estimated.knownTotal.toPlainString())

        assertEquals(1L, quote.actual.unknownRequestsByComponent[UsageComponent.CACHE_READ])
        assertEquals(1L, quote.estimated.unknownRequestsByComponent[UsageComponent.CACHE_READ])
        assertEquals(0L, quote.actual.unknownRequestsByComponent[UsageComponent.OUTPUT])
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, quote.status)
        assertFalse(quote.actual.isComplete)
        assertFalse(quote.estimated.isComplete)
    }

    @Test fun unknownTotalsAreCarriedWithoutFabricatingZeroCost() {
        val totals = RequestUsageTotals()
            .add(usage(input = actual(1_000_000)))
            .add(usage(input = estimated(2_000_000)))
            .add(usage(input = unknown))

        val quote = priced(calculator.quote("model-a", totals))

        assertEquals("3.000000000000", quote.actual.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals("6.000000000000", quote.estimated.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals(1L, quote.actual.unknownRequestsByComponent[UsageComponent.INPUT])
        assertEquals(1L, quote.estimated.unknownRequestsByComponent[UsageComponent.INPUT])
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, quote.actual.status)
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, quote.estimated.status)
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, quote.status)
    }

    @Test fun allUnknownUsageReportsUnknownInsteadOfACompleteZeroQuote() {
        val totals = RequestUsageTotals().add(usage(
            input = unknown,
            cacheRead = unknown,
            cacheWrite = unknown,
            thinking = unknown,
            output = unknown,
        ))
        val quote = priced(calculator.quote("model-a", totals))
        val cost = quote.actual

        assertEquals("0.000000000000", cost.knownTotal.toPlainString())
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, cost.status)
        assertFalse(cost.isComplete)
        assertTrue(cost.hasUnknownUsage)
        UsageComponent.entries.forEach { component ->
            assertEquals(1L, cost.unknownRequestsByComponent[component])
            assertTrue(cost.line(component).amount.isZero)
        }
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, quote.estimated.status)
    }

    @Test fun subMicroPricesStayExact() {
        val precise = PriceTable(CurrencyCode.USD, 1, listOf(ModelPricing(
            input = price("0.35"),
            output = price("3.123456"),
            cacheRead = price("0"),
            cacheWrite = price("0"),
            thinking = ThinkingPricing.AsOutput,
            modelKey = "precise",
        )))
        val quote = priced(UsageCostCalculator(precise).quote("precise",
            usage(input = actual(1), output = actual(1))))

        assertEquals("0.000000350000", quote.actual.line(UsageComponent.INPUT).amount.toPlainString())
        assertEquals("0.000003123456", quote.actual.line(UsageComponent.OUTPUT).amount.toPlainString())
        assertEquals("0.000003473456", quote.actual.knownTotal.toPlainString())
    }

    @Test fun missingAndInvalidModelKeysReturnExplicitUnpricedQuotes() {
        val exactOnly = PriceTable(CurrencyCode.USD, 7, listOf(pricing("model-a")))
        val exactCalculator = UsageCostCalculator(exactOnly)

        val missing = exactCalculator.quote("absent", usage()) as CostQuote.Unpriced
        assertEquals(CostUnavailableReason.NO_MATCHING_PRICE, missing.reason)
        assertEquals("absent", missing.requestedModelKey)
        assertEquals(7L, missing.priceTable.version)
        assertEquals(exactOnly.fingerprint, missing.priceTable.fingerprint.sha256)

        val invalid = exactCalculator.quote(" ", usage()) as CostQuote.Unpriced
        assertEquals(CostUnavailableReason.INVALID_MODEL_KEY, invalid.reason)

        val defaulted = priced(calculator.quote("absent", usage()))
        assertEquals(PriceMatch.DEFAULT, defaulted.match)
        assertNull(defaulted.matchedModelKey)
        assertEquals("model-a", priced(calculator.quote("model-a", usage())).matchedModelKey)
    }
}
