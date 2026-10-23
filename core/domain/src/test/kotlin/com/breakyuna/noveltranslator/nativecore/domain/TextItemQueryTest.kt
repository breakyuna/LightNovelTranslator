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

    @Test fun oversizedNullAndMalformedUnicodeQueriesAreRejectedBeforeDatabaseUse() {
        assertEquals(256, TextItemQuery(text = "a".repeat(256)).text.length)
        assertThrows(IllegalArgumentException::class.java) { TextItemQuery(text = "a".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { TextItemQuery(text = "a\u0000b") }
        assertThrows(TextProjectException::class.java) { TextItemQuery(text = "\uD800") }
    }

    @Test fun translatablePendingIsASubsetAndDoesNotChangeStatusTotals() {
        assertEquals(6, TextItemStatistics(3, 2, 1, 0).total)
        assertThrows(IllegalArgumentException::class.java) { TextItemStatistics(3, 0, 0, 4) }
        assertThrows(IllegalArgumentException::class.java) { TextItemStatistics(3, 0, 0, -1) }
    }

    @Test fun windowsOwnTheirBoundedOrderedMembership() {
        val ids = mutableListOf(ItemId(1), ItemId(4))
        val window = TextItemWindow(ids); ids.clear()
        assertEquals(listOf(ItemId(1), ItemId(4)), window.ids)
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(listOf(ItemId(4), ItemId(1))) }
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow(listOf(ItemId(1), ItemId(1))) }
        assertThrows(IllegalArgumentException::class.java) { TextItemWindow((1L..101L).map(::ItemId)) }
    }
}
