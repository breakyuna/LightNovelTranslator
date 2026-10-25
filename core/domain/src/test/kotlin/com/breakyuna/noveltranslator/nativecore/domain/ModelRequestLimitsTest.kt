package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun codecRoundTripsAndFingerprintsCanonically() {
        val limits = ModelRequestLimits(2, 120)
        val encoded = ModelRequestLimitsCodec.encode(limits)
        assertEquals(limits, ModelRequestLimitsCodec.decode(encoded))
        assertEquals(ModelRequestLimitsCodec.fingerprint(limits), ModelRequestLimitsCodec.fingerprint(ModelRequestLimitsCodec.decode(encoded)))
        assertEquals(64, ModelRequestLimitsCodec.fingerprint(limits).length)
    }

    @Test fun codecRejectsUnknownIllTypedAndOutOfRangeValues() {
        val encoded = ModelRequestLimitsCodec.encode(ModelRequestLimits(2, 120))
        val cases = listOf(
            encoded.replace("\"concurrency\":2", "\"concurrency\":\"2\""),
            encoded.replace("\"concurrency\":2", "\"concurrency\":2.5"),
            encoded.replace("\"concurrency\":2", "\"concurrency\":0"),
            encoded.replace("\"concurrency\":2", "\"concurrency\":17"),
            encoded.replace("\"requestsPerMinute\":120", "\"requestsPerMinute\":-1"),
            encoded.replace("\"requestsPerMinute\":120", "\"requestsPerMinute\":60001"),
            encoded.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            encoded.dropLast(1) + ",\"model\":\"secret\"}",
            "broken",
        )
        for (case in cases) {
            assertThrows(IllegalArgumentException::class.java) { ModelRequestLimitsCodec.decode(case) }
        }
    }
}
