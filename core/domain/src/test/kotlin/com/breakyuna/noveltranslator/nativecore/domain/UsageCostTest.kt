package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostTest {
    private val unitPrice = TokenPrice.ofCurrencyPerMillion(BigDecimal.ONE)
    private val table = PriceTable(CurrencyCode.USD, 1, listOf(ModelPricing(
        input = unitPrice,
        output = unitPrice,
        cacheRead = unitPrice,
        cacheWrite = unitPrice,
        modelKey = "model-a",
    )))
    private val reference = table.ref()

    private fun line(
        component: UsageComponent,
        tokens: Long = 1,
        unknownRequests: Long = 0,
        rateSource: UsageComponent = component,
    ): CostLine {
        val count = BigInteger.valueOf(tokens)
        return CostLine(
            component = component,
            tokens = count,
            price = unitPrice,
            amount = CostAmount.ofTokens(count, unitPrice),
            unknownRequests = unknownRequests,
            rateSource = rateSource,
        )
    }

    @Test fun usageCostKeepsUnknownRequestsSeparateFromKnownZero() {
        val cost = UsageCost(CostBasis.ACTUAL, reference, UsageComponent.entries.map { component ->
            if (component == UsageComponent.OUTPUT) {
                line(component, tokens = 0, unknownRequests = 2)
            } else {
                line(component)
            }
        })

        assertEquals(UsageComponent.entries.toList(), cost.lines.map { it.component })
        assertEquals("0.000004000000", cost.knownTotal.toPlainString())
        assertEquals(BigInteger.valueOf(4), cost.knownTokensTotal)
        assertEquals(2L, cost.unknownRequestsByComponent[UsageComponent.OUTPUT])
        assertEquals(0L, cost.unknownRequestsByComponent[UsageComponent.INPUT])
        assertEquals(CostStatus.INCOMPLETE_UNKNOWN, cost.status)
        assertFalse(cost.isComplete)
        assertTrue(cost.hasUnknownUsage)
        assertTrue(cost.line(UsageComponent.OUTPUT).amount.isZero)
        assertTrue(cost.line(UsageComponent.OUTPUT).hasUnknownRequests)
    }

    @Test fun usageCostWithoutUnknownsIsComplete() {
        val cost = UsageCost(CostBasis.ESTIMATED, reference, UsageComponent.entries.map { line(it) })
        assertEquals("0.000005000000", cost.knownTotal.toPlainString())
        assertEquals(CostStatus.COMPLETE, cost.status)
        assertTrue(cost.isComplete)
        assertFalse(cost.hasUnknownUsage)
    }

    @Test fun usageCostRejectsMissingComponentsAndInconsistentLines() {
        val lines = UsageComponent.entries.map { line(it) }
        assertThrows(IllegalArgumentException::class.java) {
            UsageCost(CostBasis.ACTUAL, reference, lines.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostLine(
                component = UsageComponent.INPUT,
                tokens = BigInteger.ONE,
                price = unitPrice,
                amount = CostAmount.ZERO,
                unknownRequests = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            line(UsageComponent.INPUT, rateSource = UsageComponent.OUTPUT)
        }
    }
}
