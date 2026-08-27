package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconKind
import org.junit.Assert.*
import org.junit.Test

class TranslationProtocolTest {
    @Test
    fun parser_keeps_completed_chapters_when_last_chapter_is_truncated() {
        val parsed = TranslationProtocol.parse(
            """
            <TRANSLATION>
              <C id="1"><S id="1">第一章</S></C>
              <C id="2"><S id="1">第二章</S></C>
              <C id="3"><S id="1">已完成的小段</S><S id="2">未完成
            """.trimIndent()
        )
        assertTrue(parsed.isTruncated)
        // Complete chapter boundaries remain usable; only the incomplete last chapter is retried.
        assertEquals(listOf(1, 2, 3), parsed.chapters.map { it.shortId })
        assertEquals(mapOf(1 to "已完成的小段"), parsed.chapters.last().segments)
    }

    @Test
    fun parser_returns_each_complete_chapter_and_compact_meta() {
        val parsed = TranslationProtocol.parse(
            """
            <TRANSLATION>
              <C id="1"><S id="1">甲</S><S id="2">乙</S></C>
              <C id="2"><S id="1">丙</S></C>
            </TRANSLATION>
            <META>{"chapterMemory":[]}</META>
            """.trimIndent()
        )
        assertEquals(2, parsed.chapters.size)
        assertEquals("乙", parsed.chapters[0].segments[2])
        assertEquals("{\"chapterMemory\":[]}", parsed.metaJson)
    }

    @Test
    fun deterministicQa_rejects_missing_segment_and_changed_image_marker() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "hello [IMG:a.png]"),
                ProtocolSegment(2, 101, "world")
            )
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "你好 [IMG:b.png]"))
        val result = DeterministicTranslationQa.validate(source, translated)
        assertFalse(result.accepted)
        assertTrue(result.problems.any { it.contains("segment ids") })
        assertTrue(result.problems.any { it.contains("image markers") })
    }

    @Test
    fun deterministicQa_accepts_complete_segments_regardlessOfMapIterationOrder() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "first paragraph"),
                ProtocolSegment(2, 101, "second paragraph")
            )
        )
        val translated = ParsedTranslationChapter(1, linkedMapOf(2 to "第二段", 1 to "第一段"))

        assertTrue(DeterministicTranslationQa.validate(source, translated).accepted)
    }

    @Test
    fun deterministicQa_doesNotMatchShortAsciiTermInsideLongerWord() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "The annual festival began."))
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "庆典开始了。"))
        val term = LexiconEntryEntity(
            translationProjectId = 1,
            sourceTerm = "Ann",
            targetTerm = "安"
        )

        assertTrue(DeterministicTranslationQa.validate(source, translated, listOf(term)).accepted)
    }

    @Test
    fun deterministicQa_checksTermAcrossChapterAndAllowsPunctuationVariation() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "The Holy Sword shone."),
                ProtocolSegment(2, 101, "The weapon answered its bearer.")
            )
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "那把剑闪耀起来。", 2 to "圣·剑回应了持有者。"))
        val term = LexiconEntryEntity(
            translationProjectId = 1,
            sourceTerm = "Holy Sword",
            targetTerm = "圣剑",
            kind = LexiconKind.TERMINOLOGY.name
        )

        assertTrue(DeterministicTranslationQa.validate(source, translated, listOf(term)).accepted)
    }

    @Test
    fun budget_never_exceeds_user_batch_limit_and_only_chunks_oversized_single_chapter() {
        val ordinary = TokenBudgetPlanner.plan(
            maxContextTokens = 32_768,
            userMaxBatchSize = 3,
            sourceTokenEstimates = listOf(1500, 1500, 1500, 1500),
            fixedContextTokens = 3000
        )
        assertEquals(3, ordinary.actualBatchSize)
        assertFalse(ordinary.requiresSingleChapterChunking)

        val ordered = TokenBudgetPlanner.plan(
            maxContextTokens = 12_000,
            userMaxBatchSize = 3,
            sourceTokenEstimates = listOf(1_000, 5_000, 100),
            fixedContextTokens = 2_000
        )
        assertEquals(1, ordered.actualBatchSize)

        val oversized = TokenBudgetPlanner.plan(
            maxContextTokens = 8_192,
            userMaxBatchSize = 5,
            sourceTokenEstimates = listOf(6000, 100),
            fixedContextTokens = 1500
        )
        assertEquals(0, oversized.actualBatchSize)
        assertTrue(oversized.requiresSingleChapterChunking)
    }
}
