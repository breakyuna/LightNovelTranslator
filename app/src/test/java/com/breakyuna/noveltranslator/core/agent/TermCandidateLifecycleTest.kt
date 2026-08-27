package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.translation.DeterministicTranslationQa
import com.breakyuna.noveltranslator.core.translation.ContextPackage
import com.breakyuna.noveltranslator.core.translation.GlossaryQaStatus
import com.breakyuna.noveltranslator.core.translation.ParsedTranslationChapter
import com.breakyuna.noveltranslator.core.translation.ProtocolChapter
import com.breakyuna.noveltranslator.core.translation.ProtocolSegment
import com.breakyuna.noveltranslator.core.translation.TranslationProtocol
import com.breakyuna.noveltranslator.core.translator.TranslationQualityValidator
import com.breakyuna.noveltranslator.data.model.CandidateObservation
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateAggregateEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateNoiseFilter
import com.breakyuna.noveltranslator.data.model.LexiconCandidateState
import com.breakyuna.noveltranslator.data.model.LexiconCandidateStatePolicy
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryPolicy
import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.LexiconSource
import com.breakyuna.noveltranslator.data.model.LegacyGlossaryCandidateVoting
import com.breakyuna.noveltranslator.data.model.ReviewStatus
import com.breakyuna.noveltranslator.data.model.TermCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermCandidateLifecycleTest {
    @Test
    fun unknownAiCategoryIsRejectedInsteadOfBecomingCustom() {
        val parsed = TermExtractionAgent.parseTermsJsonWithValidation(
            projectId = 1L,
            rawText = "[{\"original\":\"Alice\",\"suggested\":\"爱丽丝\",\"category\":\"CUSTOM\"}]",
            sourceText = "Alice arrived."
        )

        assertTrue(parsed.terms.isEmpty())
        assertTrue(parsed.rejections.single().reason.contains("unsupported AI category"))
    }

    @Test
    fun sourceTermMustBeAnExactSubstringOfTheScanWindow() {
        val parsed = TermExtractionAgent.parseTermsJsonWithValidation(
            projectId = 1L,
            rawText = "[{\"original\":\"Bob\",\"suggested\":\"鲍勃\",\"category\":\"CHARACTER\"}]",
            sourceText = "Alice arrived."
        )

        assertTrue(parsed.terms.isEmpty())
        assertTrue(parsed.rejections.single().reason.contains("exact substring"))
    }

    @Test
    fun numericOnlyCandidateIsRejectedEvenWithPunctuation() {
        val parsed = TermExtractionAgent.parseTermsJsonWithValidation(
            projectId = 1L,
            rawText = "[{\"original\":\"12-34\",\"suggested\":\"一二三四\",\"category\":\"LORE\"}]",
            sourceText = "Code 12-34 appeared."
        )

        assertTrue(parsed.terms.isEmpty())
        assertTrue(parsed.rejections.single().reason.contains("numeric only"))
    }

    @Test
    fun unchangedNamedTokenIsAllowedWhenItIsAnExactSourceMatch() {
        val parsed = TermExtractionAgent.parseTermsJsonWithValidation(
            projectId = 1L,
            rawText = "[{\"original\":\"Dawnpiercer\",\"suggested\":\"Dawnpiercer\",\"category\":\"ITEM\"}]",
            sourceText = "Dawnpiercer was drawn."
        )

        assertEquals(1, parsed.terms.size)
        assertTrue(parsed.rejections.isEmpty())
    }

    @Test
    fun unchangedOrdinaryLookingWordIsRejectedCautiously() {
        val parsed = TermExtractionAgent.parseTermsJsonWithValidation(
            projectId = 1L,
            rawText = "[{\"original\":\"sword\",\"suggested\":\"sword\",\"category\":\"ITEM\"}]",
            sourceText = "The sword was drawn."
        )

        assertTrue(parsed.terms.isEmpty())
        assertTrue(parsed.rejections.single().reason.contains("ordinary-looking"))
    }

    @Test
    fun repeatedObservationsAccumulateVotesAndUseAStableWinner() {
        var aggregate: LexiconCandidateAggregateEntity? = null
        aggregate = LexiconCandidateVoting.merge(aggregate, observation("爱丽丝", "CHARACTER", 1, 100))
        aggregate = LexiconCandidateVoting.merge(aggregate, observation("爱丽丝", "CHARACTER", 5, 200))
        aggregate = LexiconCandidateVoting.merge(aggregate, observation("艾丽丝", "CHARACTER", 9, 300))

        assertNotNull(aggregate)
        assertEquals(3, aggregate!!.observationCount)
        assertEquals(mapOf("爱丽丝" to 2, "艾丽丝" to 1), LexiconCandidateVoting.decodeVotes(aggregate!!.targetVotesJson))
        assertEquals("爱丽丝", LexiconCandidateVoting.review(aggregate!!).winnerTargetTerm)
        assertEquals(3, LexiconCandidateVoting.review(aggregate!!).winnerCategory.let { LexiconCandidateVoting.decodeVotes(aggregate!!.categoryVotesJson)[it] ?: 0 })
    }

    @Test
    fun voteTieBreakIsDeterministicAndIndependentOfMapOrder() {
        val left = mapOf("Zed" to 1, "Alice" to 1)
        val right = linkedMapOf("Alice" to 1, "Zed" to 1)
        assertEquals(LexiconCandidateVoting.winner(left), LexiconCandidateVoting.winner(right))
        assertEquals("Alice", LexiconCandidateVoting.winner(left))
        assertEquals(LexiconCandidateVoting.encodeVotes(left), LexiconCandidateVoting.encodeVotes(right))
    }

    @Test
    fun activeCandidateIsReobservableButImportedAndIgnoredAreNot() {
        assertTrue(LexiconCandidateStatePolicy.acceptsObservation(LexiconCandidateState.ACTIVE.name))
        assertFalse(LexiconCandidateStatePolicy.acceptsObservation(LexiconCandidateState.IMPORTED.name))
        assertFalse(LexiconCandidateStatePolicy.acceptsObservation(LexiconCandidateState.IGNORED.name))
    }

    @Test
    fun onlyConfirmedEnabledEntriesAreEligibleForTranslation() {
        val candidate = LexiconEntryEntity(
            translationProjectId = 7L,
            sourceTerm = "Alice",
            targetTerm = "爱丽丝",
            source = LexiconSource.AI.name,
            reviewStatus = ReviewStatus.CANDIDATE.name
        )
        val confirmedDisabled = candidate.copy(reviewStatus = ReviewStatus.CONFIRMED.name, enabled = false)
        val confirmed = candidate.copy(reviewStatus = ReviewStatus.CONFIRMED.name, enabled = true)
        val confirmedWithoutTarget = confirmed.copy(targetTerm = "")

        assertFalse(LexiconEntryPolicy.isEligibleForTranslation(candidate))
        assertFalse(LexiconEntryPolicy.isEligibleForTranslation(confirmedDisabled))
        assertTrue(LexiconEntryPolicy.isEligibleForTranslation(confirmed))
        assertFalse(LexiconEntryPolicy.isEligibleForTranslation(confirmedWithoutTarget))
        assertTrue(LexiconEntryPolicy.confirmedSourceTerms(listOf(candidate)).isEmpty())
        assertEquals(setOf("alice"), LexiconEntryPolicy.confirmedSourceTerms(listOf(candidate, confirmedDisabled, confirmed)))
    }

    @Test
    fun translationProtocolInjectsOnlyTermsMatchedByTheCurrentRequest() {
        val alice = official("Alice", "爱丽丝")
        val bob = official("Bob", "鲍勃")
        val context = ContextPackage(
            stablePrefix = "Protocol version: 1",
            matchedLexicon = listOf(alice, bob),
            relatedStoryMemory = emptyList(),
            recentContext = "",
            fingerprint = "test"
        )
        val chapter = ProtocolChapter(1, 1L, 1, "Chapter", listOf(ProtocolSegment(1, 1L, "Alice waved.")))

        val prompt = TranslationProtocol.userPrompt(context, listOf(chapter))

        assertTrue(prompt.contains("Alice => 爱丽丝"))
        assertFalse(prompt.contains("Bob => 鲍勃"))
    }

    @Test
    fun legacyCandidateEvidenceSurvivesSerializationAndAccumulatesVotes() {
        val first = GlossaryEntity(0, 3L, "Alice", "爱丽丝", TermCategory.CHARACTER, "hero")
        var aggregate = LegacyGlossaryCandidateVoting.merge(null, first, chapterIndex = 1, observedAt = 100)
        aggregate = LegacyGlossaryCandidateVoting.merge(aggregate, first, chapterIndex = 5, observedAt = 200)
        aggregate = LegacyGlossaryCandidateVoting.merge(
            aggregate,
            first.copy(translatedTerm = "艾丽丝"),
            chapterIndex = 9,
            observedAt = 300
        )

        val restored = aggregate.copy()
        val evidence = LegacyGlossaryCandidateVoting.decode(restored)
        assertNotNull(evidence)
        assertEquals(3, evidence!!.observationCount)
        assertEquals(mapOf("爱丽丝" to 2, "艾丽丝" to 1), evidence.targetVotes)
        assertTrue(evidence.hasConflict)
        assertFalse(evidence.isHighConfidenceForBatch)
        assertEquals("爱丽丝", restored.translatedTerm)
        assertTrue(LegacyGlossaryCandidateVoting.isIgnored(LegacyGlossaryCandidateVoting.markIgnored(restored)))
    }

    @Test
    fun batchConfidenceRequiresRepeatedAndConsistentEvidence() {
        var aggregate = LexiconCandidateVoting.merge(null, observation("爱丽丝", "CHARACTER", 1, 100))
        assertFalse(LexiconCandidateVoting.review(aggregate).isHighConfidenceForBatch)
        aggregate = LexiconCandidateVoting.merge(aggregate, observation("爱丽丝", "CHARACTER", 2, 200))
        assertTrue(LexiconCandidateVoting.review(aggregate).isHighConfidenceForBatch)
        aggregate = LexiconCandidateVoting.merge(aggregate, observation("艾丽丝", "CHARACTER", 3, 300))
        assertFalse(LexiconCandidateVoting.review(aggregate).isHighConfidenceForBatch)
    }

    @Test
    fun translationPromptIgnoresUnconfirmedGlossaryRows() {
        val candidate = GlossaryEntity(
            projectId = 1L,
            originalTerm = "Alice",
            translatedTerm = "候选译名",
            reviewStatus = ReviewStatus.CANDIDATE.name
        )
        val confirmed = candidate.copy(translatedTerm = "爱丽丝", reviewStatus = ReviewStatus.CONFIRMED.name)
        val prompt = TranslationPrompts.buildUserPrompt(
            chapterTitle = "Chapter",
            chapterText = "Alice arrived.",
            glossary = listOf(candidate, confirmed)
        )

        assertTrue(prompt.contains("爱丽丝"))
        assertFalse(prompt.contains("候选译名"))
    }

    @Test
    fun substringNoiseFilterKeepsIndependentShortNames() {
        val onlyParent = LexiconCandidateAggregateEntity(
            id = 1L,
            translationProjectId = 7L,
            sourceTerm = "Irene",
            normalizedSourceTerm = "irene",
            observationCount = 1,
            sourceHitCount = 1,
            independentHitCount = 0,
            parentHitCount = 1
        )
        val independent = onlyParent.copy(id = 2L, independentHitCount = 1)

        assertTrue(LexiconCandidateNoiseFilter.filterForReview(listOf(onlyParent)).isEmpty())
        assertEquals(listOf(independent), LexiconCandidateNoiseFilter.filterForReview(listOf(independent)))
    }

    @Test
    fun overwritePreservesOfficialEntryIdentityAndUserSettings() {
        val aggregate = LexiconCandidateVoting.merge(null, observation("Alice", "CHARACTER", 1, 100))
        val review = LexiconCandidateVoting.review(aggregate)
        val existing = LexiconEntryEntity(
            id = 99L,
            translationProjectId = 7L,
            sourceTerm = "Alice",
            targetTerm = "旧译名",
            category = "CHARACTER",
            priority = 42,
            enabled = false,
            source = LexiconSource.MANUAL.name,
            reviewStatus = ReviewStatus.CONFIRMED.name
        )

        val overwritten = com.breakyuna.noveltranslator.data.model.LexiconCandidateImportPlanner.overwriteOfficialEntry(
            existing,
            review,
            targetTerm = "爱丽丝",
            category = "CHARACTER",
            notes = "confirmed"
        )
        assertEquals(99L, overwritten.id)
        assertEquals(42, overwritten.priority)
        assertFalse(overwritten.enabled)
        assertEquals("爱丽丝", overwritten.targetTerm)
        assertEquals(ReviewStatus.CONFIRMED.name, overwritten.reviewStatus)
    }

    @Test
    fun glossaryQaDistinguishesNoneAppliedPartialAndMissing() {
        val source = ProtocolChapter(
            shortId = 1,
            logicalChapterId = 1L,
            chapterIndex = 1,
            title = "Chapter",
            segments = listOf(
                ProtocolSegment(1, 1L, "Alice waved."),
                ProtocolSegment(2, 2L, "Bob left.")
            )
        )
        val alice = official("Alice", "爱丽丝")
        val bob = official("Bob", "鲍勃")
        val applied = ParsedTranslationChapter(1, mapOf(1 to "爱丽丝挥手。", 2 to "鲍勃离开了。"))
        val partial = ParsedTranslationChapter(1, mapOf(1 to "爱丽丝挥手。", 2 to "他离开了。"))
        val missing = ParsedTranslationChapter(1, mapOf(1 to "她挥手。", 2 to "他离开了。"))

        assertEquals(GlossaryQaStatus.NONE, DeterministicTranslationQa.validate(source, applied, emptyList()).glossaryStatus)
        assertEquals(GlossaryQaStatus.APPLIED, DeterministicTranslationQa.validate(source, applied, listOf(alice, bob)).glossaryStatus)
        assertEquals(GlossaryQaStatus.PARTIAL, DeterministicTranslationQa.validate(source, partial, listOf(alice, bob)).glossaryStatus)
        val missingResult = DeterministicTranslationQa.validate(source, missing, listOf(alice, bob))
        assertEquals(GlossaryQaStatus.MISSING, missingResult.glossaryStatus)
        assertTrue(missingResult.problems.any { it.startsWith("GLOSSARY_MISSING") })
        assertTrue(missingResult.problems.size >= 2)
    }

    @Test
    fun legacyGlossaryQaOnlyChecksConfirmedTerms() {
        val candidate = GlossaryEntity(
            projectId = 1L,
            originalTerm = "Alice",
            translatedTerm = "爱丽丝",
            source = LexiconSource.AI.name,
            reviewStatus = ReviewStatus.CANDIDATE.name
        )
        val confirmed = candidate.copy(reviewStatus = ReviewStatus.CONFIRMED.name)
        val result = TranslationQualityValidator.validate("Alice waved.", "她挥手了。", listOf(candidate, confirmed))
        assertEquals(GlossaryQaStatus.MISSING, result.glossaryStatus)
        assertTrue(result.problems.any { it.startsWith("GLOSSARY_MISSING") })
    }

    private fun observation(target: String, category: String, chapter: Int, timestamp: Long) = CandidateObservation(
        translationProjectId = 7L,
        sourceTerm = "Alice",
        targetTerm = target,
        category = category,
        chapterIndex = chapter,
        observedAt = timestamp,
        sourceHitCount = 1,
        independentHitCount = 1
    )

    private fun official(source: String, target: String) = LexiconEntryEntity(
        translationProjectId = 7L,
        sourceTerm = source,
        targetTerm = target,
        kind = LexiconKind.PROPER_NOUN.name,
        category = TermCategory.CHARACTER.name,
        source = LexiconSource.MANUAL.name,
        reviewStatus = ReviewStatus.CONFIRMED.name,
        enabled = true
    )
}
