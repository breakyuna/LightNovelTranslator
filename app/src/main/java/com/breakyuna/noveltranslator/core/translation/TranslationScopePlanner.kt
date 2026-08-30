package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.data.model.TranslationMode

/** Resolves user-facing chapter positions to one deterministic, ordered translation scope. */
object TranslationScopePlanner {
    fun select(
        chapters: List<LogicalChapterEntity>,
        mode: String,
        rangeStart: Int?,
        rangeEnd: Int?,
        currentChapterId: Long?,
        seamlessAheadChapters: Int
    ): List<LogicalChapterEntity> {
        val ordered = chapters.sortedWith(compareBy<LogicalChapterEntity> { it.chapterIndex }.thenBy { it.id })
        if (ordered.isEmpty()) return emptyList()

        val parsedMode = runCatching { TranslationMode.valueOf(mode) }.getOrNull()
        return when (parsedMode) {
            TranslationMode.FULL_BOOK -> ordered
            TranslationMode.CHAPTER_RANGE -> {
                val start = rangeStart ?: return emptyList()
                val end = rangeEnd ?: return emptyList()
                if (start !in 1..ordered.size || end < start || end > ordered.size) return emptyList()
                ordered.subList(start - 1, end)
            }
            TranslationMode.SEAMLESS -> {
                val currentPosition = ordered.indexOfFirst { it.id == currentChapterId }
                    .takeIf { it >= 0 }
                    ?: 0
                val endExclusive = (currentPosition + 1 + seamlessAheadChapters.coerceAtLeast(1))
                    .coerceAtMost(ordered.size)
                ordered.subList(currentPosition, endExclusive)
            }
            null -> emptyList()
        }
    }
}
