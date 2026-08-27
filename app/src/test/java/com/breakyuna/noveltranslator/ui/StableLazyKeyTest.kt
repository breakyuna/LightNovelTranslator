package com.breakyuna.noveltranslator.ui

import com.breakyuna.noveltranslator.core.translator.StableSegmentParser
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.TermExtractionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableLazyKeyTest {
    @Test
    fun duplicateTermValuesStillReceiveDistinctReviewKeys() {
        val duplicate = GlossaryEntity(
            projectId = 3L,
            originalTerm = "Alice",
            translatedTerm = "爱丽丝"
        )
        val first = TermExtractionCandidate(term = duplicate)
        val second = TermExtractionCandidate(term = duplicate)

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun repeatedParagraphsStillReceiveUniqueStableSegmentKeys() {
        val aligned = StableSegmentParser.align(
            chapterId = 12L,
            sourceText = "same paragraph\nsame paragraph\nlast paragraph",
            translatedText = "相同段落\n相同段落\n末段"
        )

        assertEquals(aligned.size, aligned.map { it.segmentId }.toSet().size)
        assertEquals(
            aligned.map { it.segmentId },
            StableSegmentParser.align(
                chapterId = 12L,
                sourceText = "same paragraph\nsame paragraph\nlast paragraph",
                translatedText = "相同段落\n相同段落\n末段"
            ).map { it.segmentId }
        )
    }
}
