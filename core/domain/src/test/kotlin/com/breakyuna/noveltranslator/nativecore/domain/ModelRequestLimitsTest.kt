package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ModelRequestLimitsTest {
    @Test fun fixedLimitsRejectUnsupportedRanges() {
        for ((concurrency, rpm) in listOf(0 to 0, 17 to 0, 1 to -1, 1 to 60_001)) {
            try { ModelRequestLimits(concurrency, rpm); fail("Accepted invalid limits") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(ModelRequestLimits(4, 0), ModelRequestLimits())
        assertEquals(60_000, ModelRequestLimits(16, 60_000).requestsPerMinute)
    }
}
