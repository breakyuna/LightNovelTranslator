package com.breakyuna.noveltranslator.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSimulationRestoreTest {
    @Test fun matchingTokenRetainsTheWholeStrictlyIncreasingHistory() {
        val remembered = mutableListOf(0L, 20L, 40L)
        val restored = simulationCursors("run", "run", remembered, 40)
        remembered.clear()
        assertEquals(listOf(0L, 20L, 40L), restored)
    }

    @Test fun foreignTokenMissingHistoryOrInvalidCursorsFallBackToTheFixedRunPage() {
        assertEquals(listOf(0L, 40L), simulationCursors("old", "run", listOf(0, 20, 40), 40))
        for (remembered in listOf(null, emptyList(), listOf(20L, 40L), listOf(0L, 20L),
            listOf(0L, 40L, 40L), listOf(0L, -1L, 40L), listOf(0L, 30L, 20L, 40L))) {
            assertEquals(listOf(0L, 40L), simulationCursors("run", "run", remembered, 40))
        }
    }

    @Test fun firstPageFallbackDoesNotCreateADuplicateZeroOrAPreviousPage() {
        assertEquals(listOf(0L), simulationCursors(null, "run", null, 0))
        assertEquals(listOf(0L), simulationCursors("run", "run", listOf(0, 0), 0))
    }
}
