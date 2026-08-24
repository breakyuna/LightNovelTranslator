package com.breakyuna.noveltranslator.core.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSegmentParserTest {
    @Test
    fun singleLineTextAndImagesBecomeStableSegments() {
        val first = StableSegmentParser.parse(7L, "第一行\n第二行\n[IMG:cover.jpg]")
        val second = StableSegmentParser.parse(7L, "第一行\n第二行\n[IMG:cover.jpg]")
        assertEquals(3, first.size)
        assertEquals(SegmentType.IMAGE, first[2].type)
        assertEquals(first.map { it.segmentId }, second.map { it.segmentId })
    }

    @Test
    fun repeatedParagraphsStillGetDifferentIds() {
        val segments = StableSegmentParser.parse(7L, "same\nsame")
        assertNotEquals(segments[0].segmentId, segments[1].segmentId)
        assertTrue(segments.all { it.segmentId.startsWith("seg_") })
    }

    @Test
    fun mergedTranslationIsExposedAsOneEditorSafeGroup() {
        val aligned = StableSegmentParser.align(9L, "原文一\n原文二\n原文三", "译文一\n译文二和三")
        assertEquals(2, aligned.size)
        assertEquals("原文二\n\n原文三", aligned[1].sourceText)
        assertEquals("译文二和三", aligned[1].translatedText)
        assertEquals("MANY_TO_ONE", aligned[1].relation)
        assertTrue(aligned[1].segmentId.startsWith("seg_"))
    }

    @Test
    fun persistedRelationsCarryStableIdsAndSourceHash() {
        val rows = StableSegmentParser.toPersistedRelations(12L, "原文一\n原文二", "译文一")
        assertEquals(2, rows.size)
        assertEquals("MANY_TO_ONE", rows[0].relation)
        assertEquals("MANY_TO_ONE", rows[1].relation)
        assertTrue(rows.all { it.stableKey.contains(it.sourceSegmentId) })
        assertEquals(rows[0].sourceHash, rows[1].sourceHash)
        assertEquals(1, rows.map { it.translatedSegmentId }.distinct().size)
        val collapsed = StableSegmentParser.alignPersisted(rows)
        assertEquals(1, collapsed.size)
        assertEquals("译文一", collapsed.single().translatedText)
    }
}
