package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class RequestUsageTotalsTest {
    private fun usage(value: TokenMeasurement) = RequestUsage(value, value, value, value, value)
    private val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)

    @Test fun unknownAndEstimatedResponsesNeverEraseActualFacts() {
        val totals = RequestUsageTotals()
            .add(usage(TokenMeasurement(12, UsageSource.ACTUAL)))
            .add(usage(unknown))
            .add(usage(TokenMeasurement(7, UsageSource.ESTIMATED)))
        assertEquals(3L, totals.responses)
        for (quantity in listOf(totals.input, totals.cacheRead, totals.cacheWrite, totals.thinking, totals.output)) {
            assertEquals(BigInteger.valueOf(12), quantity.actual)
            assertEquals(BigInteger.valueOf(7), quantity.estimated)
            assertEquals(1L, quantity.unknownRequests)
        }
    }

    @Test fun totalsDoNotOverflowOrMutateEarlierSnapshots() {
        val first = RequestUsageTotals().add(usage(TokenMeasurement(Long.MAX_VALUE, UsageSource.ACTUAL)))
        val second = first.add(usage(TokenMeasurement(Long.MAX_VALUE, UsageSource.ACTUAL)))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), first.input.actual)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)), second.input.actual)
        assertEquals(0L, second.input.unknownRequests)
    }

    @Test fun oneMissingComponentDoesNotTurnOtherComponentsUnknown() {
        val measured = TokenMeasurement(0, UsageSource.ACTUAL)
        val totals = RequestUsageTotals().add(RequestUsage(measured, unknown, unknown, measured, measured))
        assertEquals(0L, totals.input.unknownRequests)
        assertEquals(BigInteger.ZERO, totals.input.actual)
        assertEquals(1L, totals.cacheRead.unknownRequests)
        assertEquals(1L, totals.cacheWrite.unknownRequests)
        assertEquals(0L, totals.output.unknownRequests)
    }
}
