package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class TextItemQueryTest {
    @Test fun literalQueriesKeepWhitespaceAndDoNotExposeSearchTextInDiagnostics() {
        val text = "  私密%_\\text  "
        val query = TextItemQuery(text = text, field = TextSearchField.TRANSLATION)
        assertEquals(text, query.text); assertTrue(query.active)
        assertFalse(query.toString().contains(text))
        assertFalse(TextItemQuery().active)
        assertTrue(TextItemQuery(status = TextItemStatus.NONE).active)
    }

    @Test fun translatablePendingIsASubsetAndDoesNotChangeStatusTotals() {
        assertEquals(6, TextItemStatistics(3, 2, 1, 0).total)
        assertThrows(IllegalArgumentException::class.java) { TextItemStatistics(3, 0, 0, 4) }
    }

    @Test fun windowsOwnTheirBoundedOrderedMembership() {
        val window = TextItemWindow(listOf(ItemId(1), ItemId(4)))
        assertEquals(listOf(ItemId(1), ItemId(4)), window.ids)
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(listOf(ItemId(4), ItemId(1))) }
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(listOf(ItemId(1), ItemId(1))) }
    }
}
