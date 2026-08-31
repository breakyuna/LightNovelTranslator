package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.data.model.TranslationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationScopePlannerTest {
    private val chapters = listOf(
        chapter(id = 30, index = 30),
        chapter(id = 10, index = 10),
        chapter(id = 20, index = 20),
        chapter(id = 40, index = 40),
        chapter(id = 50, index = 50)
    )

    @Test
    fun chapterRangeUsesUserFacingPositionsInsteadOfPersistedIndexes() {
        val selected = TranslationScopePlanner.select(
            chapters = chapters,
            mode = TranslationMode.CHAPTER_RANGE.name,
            rangeStart = 2,
            rangeEnd = 4,
            currentChapterId = null,
            seamlessAheadChapters = 5
        )

        assertEquals(listOf(20L, 30L, 40L), selected.map { it.id })
    }

    @Test
    fun seamlessScopeStartsAtCurrentChapterAndNeverBackfillsEarlierChapters() {
        val selected = TranslationScopePlanner.select(
            chapters = chapters,
            mode = TranslationMode.SEAMLESS.name,
            rangeStart = null,
            rangeEnd = null,
            currentChapterId = 30,
            seamlessAheadChapters = 2
        )

        assertEquals(listOf(30L, 40L, 50L), selected.map { it.id })
    }

    @Test
    fun invalidRangeDoesNotSilentlyFallBackToWholeBook() {
        val selected = TranslationScopePlanner.select(
            chapters = chapters,
            mode = TranslationMode.CHAPTER_RANGE.name,
            rangeStart = 4,
            rangeEnd = 9,
            currentChapterId = null,
            seamlessAheadChapters = 5
        )

        assertEquals(emptyList<LogicalChapterEntity>(), selected)
    }

    private fun chapter(id: Long, index: Int) = LogicalChapterEntity(
        id = id,
        bookId = 1,
        chapterIndex = index,
        canonicalTitle = "Chapter $index"
    )
}
