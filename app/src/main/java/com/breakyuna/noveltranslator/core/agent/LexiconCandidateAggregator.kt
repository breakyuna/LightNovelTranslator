package com.breakyuna.noveltranslator.core.agent

import androidx.room.withTransaction
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.CandidateObservation
import com.breakyuna.noveltranslator.data.model.LexiconCandidateAggregateEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateStatePolicy
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconEntryPolicy

data class CandidateAggregationResult(
    val updated: List<LexiconCandidateAggregateEntity>,
    val rejected: List<TermValidationRejection> = emptyList()
)

/** Persists every successful scan window and combines repeated observations by source identity. */
class LexiconCandidateAggregator(private val database: AppDatabase) {
    private val dao = database.lexiconCandidateAggregateDao()

    suspend fun observeWindow(
        projectId: Long,
        chapterIndex: Int,
        sourceText: String,
        candidates: List<ExtractedTermCandidate>,
        observedAt: Long = System.currentTimeMillis()
    ): CandidateAggregationResult {
        val accepted = mutableListOf<ExtractedTermCandidate>()
        val rejected = mutableListOf<TermValidationRejection>()
        candidates.forEach { candidate ->
            when (
                val validation = TermCandidateValidator.validate(
                    original = candidate.originalTerm,
                    suggested = candidate.translatedTerm,
                    categoryRaw = candidate.category.name,
                    notes = candidate.notes,
                    sourceText = sourceText
                )
            ) {
                is TermValidationResult.Accepted -> accepted += candidate.copy(
                    originalTerm = validation.originalTerm,
                    translatedTerm = validation.translatedTerm,
                    category = validation.category,
                    notes = validation.notes
                )
                is TermValidationResult.Rejected -> rejected += validation.rejection
            }
        }
        val observations = buildObservations(
            projectId,
            chapterIndex,
            sourceText,
            accepted.distinctBy { LexiconCandidateVoting.normalizeSourceTerm(it.originalTerm) },
            observedAt
        )
        return observeObservations(observations).copy(rejected = rejected)
    }

    suspend fun observeObservations(observations: List<CandidateObservation>): CandidateAggregationResult =
        database.withTransaction {
            val confirmedSourcesByProject = observations.asSequence()
                .map { it.translationProjectId }
                .distinct()
                .associateWith { projectId ->
                    // Disabled official entries are not translation constraints, but they are still
                    // user-reviewed terms and must not reappear as ordinary AI candidates.
                    LexiconEntryPolicy.confirmedSourceTerms(database.lexiconV2Dao().getAll(projectId))
                }
            val updated = observations.mapNotNull { observation ->
                val normalized = LexiconCandidateVoting.normalizeSourceTerm(observation.sourceTerm)
                if (normalized.isBlank()) return@mapNotNull null
                if (normalized in confirmedSourcesByProject[observation.translationProjectId].orEmpty()) {
                    return@mapNotNull null
                }
                val existing = dao.getBySource(observation.translationProjectId, normalized)
                if (!LexiconCandidateStatePolicy.acceptsObservation(existing?.state)) return@mapNotNull null
                val merged = LexiconCandidateVoting.merge(existing, observation)
                val persistedId = dao.upsertObservation(merged)
                if (merged.id == 0L && persistedId > 0L) merged.copy(id = persistedId) else merged
            }
            CandidateAggregationResult(updated)
        }

    private fun buildObservations(
        projectId: Long,
        chapterIndex: Int,
        sourceText: String,
        candidates: List<ExtractedTermCandidate>,
        observedAt: Long
    ): List<CandidateObservation> {
        if (candidates.isEmpty()) return emptyList()
        val rangesBySource = candidates.associate { candidate ->
            candidate.originalTerm to findRanges(sourceText, candidate.originalTerm, caseSensitive = false)
        }
        return candidates.map { candidate ->
            val childRanges = rangesBySource[candidate.originalTerm].orEmpty()
            val parentRanges = candidates
                .asSequence()
                .filter { parent ->
                    parent !== candidate &&
                        parent.originalTerm.length > candidate.originalTerm.length &&
                        containsAsNamedPhrase(
                            parent = LexiconCandidateVoting.normalizeSourceTerm(parent.originalTerm),
                            child = LexiconCandidateVoting.normalizeSourceTerm(candidate.originalTerm)
                        )
                }
                .flatMap { parent -> rangesBySource[parent.originalTerm].orEmpty().asSequence() }
                .toList()
            val parentHitCount = childRanges.count { child -> parentRanges.any { parent -> parent.contains(child) } }
            CandidateObservation(
                translationProjectId = projectId,
                sourceTerm = candidate.originalTerm,
                targetTerm = candidate.translatedTerm,
                category = candidate.category.name,
                notes = candidate.notes,
                chapterIndex = chapterIndex,
                observedAt = observedAt,
                sourceHitCount = childRanges.size,
                independentHitCount = (childRanges.size - parentHitCount).coerceAtLeast(0),
                parentHitCount = parentHitCount,
                caseSensitive = false
            )
        }
    }

    private fun findRanges(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, startIndex = start, ignoreCase = !caseSensitive)
            if (index < 0) break
            ranges += index until (index + query.length)
            start = (index + query.length).coerceAtLeast(index + 1)
        }
        return ranges
    }

    /**
     * Treat a longer term as a parent only when the child is a phrase/token inside it. This keeps
     * a legitimate name such as Ann from being discarded merely because it occurs in Joanne.
     * For scripts without reliable word boundaries we stay conservative and keep both candidates.
     */
    private fun containsAsNamedPhrase(parent: String, child: String): Boolean {
        if (parent.isBlank() || child.isBlank() || parent == child) return false
        var offset = parent.indexOf(child)
        while (offset >= 0) {
            val before = parent.getOrNull(offset - 1)
            val after = parent.getOrNull(offset + child.length)
            val beforeBoundary = before == null || !before.isLetterOrDigit()
            val afterBoundary = after == null || !after.isLetterOrDigit()
            if (beforeBoundary && afterBoundary) return true
            offset = parent.indexOf(child, offset + 1)
        }
        return false
    }

    private fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last
}
