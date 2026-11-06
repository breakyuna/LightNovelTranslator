package com.breakyuna.noveltranslator.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSimulationRestoreTest {
    @Test fun matchingTokenRetainsTheWholeStrictlyIncreasingHistory() {
        val restored = simulationCursors("run", "run", listOf(0L, 20L, 40L), 40)
        assertEquals(listOf(0L, 20L, 40L), restored)
    }

    @Test fun foreignTokenMissingHistoryOrInvalidCursorsFallBackToTheFixedRunPage() {
        assertEquals(listOf(0L, 40L), simulationCursors("old", "run", listOf(0, 20, 40), 40))
        assertEquals(listOf(0L, 40L), simulationCursors("run", "run", null, 40))
        assertEquals(listOf(0L, 40L), simulationCursors("run", "run", listOf(0L, 30L, 20L, 40L), 40))
    }

    @Test fun firstPageFallbackDoesNotCreateADuplicateZeroOrAPreviousPage() {
        assertEquals(listOf(0L), simulationCursors(null, "run", null, 0))
        assertEquals(listOf(0L), simulationCursors("run", "run", listOf(0, 0), 0))
    }
}
