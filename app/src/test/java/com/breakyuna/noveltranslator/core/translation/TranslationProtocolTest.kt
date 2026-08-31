package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.RevisionType
import com.breakyuna.noveltranslator.data.model.revisionPriority
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
    fun parser_keeps_complete_translation_when_optional_meta_is_truncated() {
        val parsed = TranslationProtocol.parse(
            """
            <TRANSLATION><C id="1"><S id="1">完整译文</S></C></TRANSLATION>
            <META>{"chapterMemory":[
            """.trimIndent()
        )

        assertTrue(parsed.isTruncated)
        assertFalse(parsed.translationTruncated)
        assertTrue(parsed.metadataTruncated)
        assertEquals("完整译文", parsed.chapters.single().segments[1])
    }

    @Test
    fun parser_records_duplicate_segment_ids_for_repair() {
        val parsed = TranslationProtocol.parse(
            """
            <TRANSLATION><C id="1"><S id="1">甲</S><S id="1">乙</S></C></TRANSLATION>
            """.trimIndent()
        )

        assertEquals(setOf(1), parsed.chapters.single().duplicateSegmentIds)
        assertEquals("甲", parsed.chapters.single().segments[1])
    }

    @Test
    fun parser_ignores_numeric_ids_that_do_not_fit_the_local_int_protocol() {
        val parsed = TranslationProtocol.parse(
            "<TRANSLATION><C id=\"999999999999999999999\"><S id=\"1\">ignored</S></C></TRANSLATION>"
        )

        assertTrue(parsed.chapters.isEmpty())
    }

    @Test
    fun parser_decodes_entities_in_one_pass_without_double_decoding_literals() {
        val parsed = TranslationProtocol.parse(
            "<TRANSLATION><C id=\"1\"><S id=\"1\">&amp;quot; &amp;amp; &lt;ok&gt;</S></C></TRANSLATION>"
        )

        assertEquals("&quot; &amp; <ok>", parsed.chapters.single().segments[1])
    }

    @Test
    fun parser_acceptsHarmlessXmlFormattingVariantsAndNumericEntities() {
        val parsed = TranslationProtocol.parse(
            "<TRANSLATION version='1' ><C title='chapter' id = '1'><S id = '1'>Tom&#39;s answer</S ></C ></TRANSLATION >"
        )

        assertFalse(parsed.isTruncated)
        assertEquals("Tom's answer", parsed.chapters.single().segments[1])
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
    fun deterministicQa_doesNotRejectLocalizedNumberWords_withoutDigits() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "He waited 12 days."))
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "他等了十二天。"))

        val result = DeterministicTranslationQa.validate(source, translated)

        assertTrue(result.accepted)
        assertTrue(result.issues.any { it.code == "NUMERIC_CONTENT_UNCERTAIN" })
        assertTrue(result.commitAllowed())
    }

    @Test
    fun deterministicQa_doesNotRejectPartiallyLocalizedNumbers() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "Class 4, group 1 met."))
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "Class 4, group one met."))

        val result = DeterministicTranslationQa.validate(source, translated)

        assertTrue(result.accepted)
        assertTrue(result.issues.any { it.code == "NUMERIC_CONTENT_UNCERTAIN" })
        assertFalse(result.issues.any { it.code == "NUMERIC_CONTENT_CHANGED" })
    }

    @Test
    fun deterministicQa_rejectsSameCountChangedNumbers() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "He waited 12 days."))
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "He waited 13 days."))

        val result = DeterministicTranslationQa.validate(source, translated)

        assertFalse(result.accepted)
        assertTrue(result.problems.any { it.startsWith("NUMERIC_CONTENT_CHANGED") })
        assertFalse(result.commitAllowed())
    }

    @Test
    fun deterministicQa_allowsRepeatedTranslationForRepeatedSource() {
        val repeatedSource = "The same epigraph appears in every edition of the story."
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, repeatedSource),
                ProtocolSegment(2, 101, repeatedSource)
            )
        )
        val repeatedTarget = "The same translated epigraph appears in every edition of the story."
        val translated = ParsedTranslationChapter(1, mapOf(1 to repeatedTarget, 2 to repeatedTarget))

        val result = DeterministicTranslationQa.validate(source, translated)

        assertTrue(result.accepted)
        assertTrue(result.issues.any { it.code == "REPEATED_TRANSLATED_SOURCE" })
        assertTrue(result.hasWarnings())
        assertTrue(result.commitAllowed())
    }

    @Test
    fun deterministicQa_repeatedDifferentSourceTargetsOnlyDuplicateSegmentForRepair() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "Alice entered the room and looked around carefully."),
                ProtocolSegment(2, 101, "Bob entered the hall and listened to the silence.")
            )
        )
        val repeatedTarget = "The character entered the room and looked around carefully before speaking."
        val translated = ParsedTranslationChapter(1, mapOf(1 to repeatedTarget, 2 to repeatedTarget))

        val result = DeterministicTranslationQa.validate(source, translated)
        val scope = DeterministicTranslationQa.repairScope(source, translated, result)

        assertFalse(result.accepted)
        assertTrue(result.problems.any { it.contains("segment 2") })
        assertTrue(result.commitAllowed())
        assertTrue(result.hasWarnings())
        assertEquals(QaRepairMode.LOCAL_SEGMENTS, scope.mode)
        assertEquals(setOf(2), scope.segmentIds)
    }

    @Test
    fun repairScope_doesNotExpandForNonBlockingNumericDiagnostic() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "A complete paragraph with enough text to trigger a real duplicate check."),
                ProtocolSegment(2, 101, "Class 4, group 1 met in the hall."),
                ProtocolSegment(3, 102, "A third paragraph remains complete and independent.")
            )
        )
        val translated = ParsedTranslationChapter(
            1,
            mapOf(
                1 to "第一段完整译文，且长度足够长。",
                2 to "Class 4, group one met in the hall.",
                3 to ""
            )
        )
        val qa = DeterministicTranslationQa.validate(source, translated)

        val scope = DeterministicTranslationQa.repairScope(source, translated, qa)

        assertEquals(QaRepairMode.LOCAL_SEGMENTS, scope.mode)
        assertEquals(setOf(3), scope.segmentIds)
        assertTrue(qa.issues.any { it.code == "NUMERIC_CONTENT_UNCERTAIN" && it.segmentId == 2 })
    }

    @Test
    fun chunkPrompt_marksTailAsReferenceData() {
        val context = ContextPackage(
            stablePrefix = "Protocol version: 2",
            matchedLexicon = emptyList(),
            relatedStoryMemory = emptyList(),
            recentContext = "",
            fingerprint = "test"
        )
        val chapter = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "source"))
        )

        val prompt = TranslationProtocol.translationUserPrompt(
            TranslationProtocol.defaultPromptProfile(),
            context,
            listOf(chapter),
            previousChunkTranslationTail = "tail",
            chunkLabel = "Chunk 2/3"
        )

        assertTrue(prompt.contains("[CURRENT_CHUNK]"))
        assertTrue(prompt.contains("Chunk 2/3"))
        assertTrue(prompt.contains("must never be copied into output"))
    }

    @Test
    fun deterministicQa_recognizesUnicodeDigits() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "等待１２天。"))
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "等待１２天。"))

        assertTrue(DeterministicTranslationQa.validate(source, translated).accepted)
    }

    @Test
    fun repairScope_includesEmptyAndMissingSegments_insteadOfKeepingAnEmptyValue() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, "first"),
                ProtocolSegment(2, 101, "missing"),
                ProtocolSegment(3, 102, "empty")
            )
        )
        val translated = ParsedTranslationChapter(1, mapOf(1 to "第一", 3 to ""))
        val qa = DeterministicTranslationQa.validate(source, translated)

        val scope = DeterministicTranslationQa.repairScope(source, translated, qa)

        assertEquals(QaRepairMode.LOCAL_SEGMENTS, scope.mode)
        assertEquals(setOf(2, 3), scope.segmentIds)
    }

    @Test
    fun repairScope_usesWholeChapterWhenIdsAreDuplicatedOrUnexpected() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(ProtocolSegment(1, 100, "first"), ProtocolSegment(2, 101, "second"))
        )
        val duplicate = ParsedTranslationChapter(1, mapOf(1 to "第一", 2 to "第二"), duplicateSegmentIds = setOf(1))
        val qa = QaResult(
            accepted = false,
            problems = listOf("STRUCTURE_DUPLICATE_SEGMENTS"),
            issues = listOf(QaIssue("STRUCTURE_DUPLICATE_SEGMENTS", "duplicate response ids: 1"))
        )

        val scope = DeterministicTranslationQa.repairScope(source, duplicate, qa)

        assertEquals(QaRepairMode.FULL_CHAPTER, scope.mode)
    }

    @Test
    fun polishPrompt_keepsSourceAndCurrentTranslationSeparateAndMasksProtectedText() {
        val sourceText = "Alice raised her sword [IMG:hero.png]."
        val protected = TranslationTextProtection.protect(sourceText)
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 10,
            chapterIndex = 1,
            title = "chapter",
            segments = listOf(
                ProtocolSegment(1, 100, protected.masked, sourceText, protected.tokens)
            )
        )
        val current = ParsedTranslationChapter(1, mapOf(1 to "爱丽丝举起了剑 [IMG:hero.png]。"))
        val context = ContextPackage(
            stablePrefix = "Protocol version: 2",
            matchedLexicon = emptyList(),
            relatedStoryMemory = emptyList(),
            recentContext = "",
            fingerprint = "test"
        )

        val prompt = TranslationProtocol.polishUserPrompt(context, source, current)

        assertTrue(prompt.contains("[SOURCE]"))
        assertTrue(prompt.contains("[CURRENT_TRANSLATION]"))
        assertTrue(prompt.contains("__LNT_PROTECTED_0__"))
        assertTrue(TranslationProtocol.polishSystemPrompt("English", "Chinese").contains("not a fresh translation"))
    }

    @Test
    fun editablePromptProfile_rendersRuntimeValuesAndKeepsProtocolBody() {
        val profile = TranslationProtocol.defaultPromptProfile()

        val system = TranslationProtocol.translationSystemPrompt(
            profile,
            sourceLanguage = "English",
            targetLanguage = "Chinese",
            styleGuide = "保持冷峻克制"
        )
        val user = TranslationProtocol.renderUserPromptTemplate(
            "请先遵守这条额外规则。\n${TranslationProtocol.PROMPT_BODY_PLACEHOLDER}",
            "[SOURCE]\n<C id=\"1\">正文</C>"
        )

        assertTrue(system.contains("from English to Chinese"))
        assertFalse(system.contains(TranslationProtocol.SOURCE_LANGUAGE_PLACEHOLDER))
        assertTrue(user.contains("请先遵守这条额外规则。"))
        assertTrue(user.contains("[SOURCE]"))
        assertTrue(system.contains("NON_NEGOTIABLE_PROTOCOL"))
    }

    @Test
    fun aiPolishRevision_keepsAiPriorityAndIsRecognizedByRevisionPriority() {
        assertEquals(200, revisionPriority(RevisionType.AI_TRANSLATION.name))
        assertEquals(210, revisionPriority(RevisionType.AI_POLISH.name))
        assertTrue(revisionPriority(RevisionType.MANUAL_EDIT.name) > revisionPriority(RevisionType.AI_POLISH.name))
        assertTrue(RevisionType.AI_POLISH.name in RevisionType.values().map { it.name })
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
