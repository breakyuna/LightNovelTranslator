package com.breakyuna.noveltranslator.data.model

import org.json.JSONObject

/**
 * Persistent voting metadata for the still-reachable legacy project glossary.
 *
 * The legacy schema has no independent aggregate table. Candidate-only evidence is therefore
 * stored behind a sentinel in the notes column and removed when the user confirms the term.
 * Confirmed glossary rows always retain ordinary human-readable notes.
 */
data class LegacyGlossaryCandidateEvidence(
    val targetVotes: Map<String, Int>,
    val categoryVotes: Map<String, Int>,
    val notesVotes: Map<String, Int>,
    val observationCount: Int,
    val firstSeenChapterIndex: Int,
    val lastSeenChapterIndex: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val ignored: Boolean = false
) {
    val winnerTarget: String get() = LexiconCandidateVoting.winner(targetVotes).orEmpty()
    val winnerCategory: String get() = LexiconCandidateVoting.winner(categoryVotes).orEmpty()
    val winnerNotes: String get() = LexiconCandidateVoting.winner(notesVotes).orEmpty()
    val hasConflict: Boolean get() = targetVotes.keys.size > 1 || categoryVotes.keys.size > 1
    val isHighConfidenceForBatch: Boolean
        get() {
            if (observationCount < 2 || targetVotes.isEmpty() || categoryVotes.isEmpty()) return false
            val targetWinnerVotes = targetVotes[winnerTarget] ?: 0
            val categoryWinnerVotes = categoryVotes[winnerCategory] ?: 0
            return targetWinnerVotes >= 2 &&
                categoryWinnerVotes >= 2 &&
                targetWinnerVotes * 100 >= observationCount * 80 &&
                categoryWinnerVotes * 100 >= observationCount * 80
        }
}

object LegacyGlossaryCandidateVoting {
    private const val PREFIX = "[[AI_CANDIDATE_EVIDENCE_V1]]"

    fun decode(term: GlossaryEntity): LegacyGlossaryCandidateEvidence? {
        if (!term.notes.startsWith(PREFIX)) return null
        return runCatching {
            val root = JSONObject(term.notes.removePrefix(PREFIX))
            LegacyGlossaryCandidateEvidence(
                targetVotes = LexiconCandidateVoting.decodeVotes(root.optJSONObject("targetVotes")?.toString().orEmpty()),
                categoryVotes = LexiconCandidateVoting.decodeVotes(root.optJSONObject("categoryVotes")?.toString().orEmpty()),
                notesVotes = LexiconCandidateVoting.decodeVotes(root.optJSONObject("notesVotes")?.toString().orEmpty()),
                observationCount = root.optInt("observationCount", 0),
                firstSeenChapterIndex = root.optInt("firstSeenChapterIndex", 0),
                lastSeenChapterIndex = root.optInt("lastSeenChapterIndex", 0),
                firstSeenAt = root.optLong("firstSeenAt", 0L),
                lastSeenAt = root.optLong("lastSeenAt", 0L),
                ignored = root.optBoolean("ignored", false)
            )
        }.getOrNull()
    }

    fun merge(
        existing: GlossaryEntity?,
        observation: GlossaryEntity,
        chapterIndex: Int,
        observedAt: Long = System.currentTimeMillis()
    ): GlossaryEntity {
        val prior = existing?.let(::decode) ?: existing?.let { seed(it) }
        val targetVotes = prior?.targetVotes.orEmpty().toMutableMap()
        targetVotes[observation.translatedTerm.trim()] =
            (targetVotes[observation.translatedTerm.trim()] ?: 0) + 1
        val categoryVotes = prior?.categoryVotes.orEmpty().toMutableMap()
        categoryVotes[observation.category.name] = (categoryVotes[observation.category.name] ?: 0) + 1
        val notesVotes = prior?.notesVotes.orEmpty().toMutableMap()
        observation.notes.trim().takeIf(String::isNotBlank)?.let { notes ->
            notesVotes[notes] = (notesVotes[notes] ?: 0) + 1
        }
        val evidence = LegacyGlossaryCandidateEvidence(
            targetVotes = targetVotes,
            categoryVotes = categoryVotes,
            notesVotes = notesVotes,
            observationCount = (prior?.observationCount ?: 0) + 1,
            firstSeenChapterIndex = prior?.firstSeenChapterIndex?.takeIf { it > 0 }?.let {
                minOf(it, chapterIndex)
            } ?: chapterIndex,
            lastSeenChapterIndex = maxOf(prior?.lastSeenChapterIndex ?: 0, chapterIndex),
            firstSeenAt = prior?.firstSeenAt?.takeIf { it > 0L }?.let { minOf(it, observedAt) } ?: observedAt,
            lastSeenAt = maxOf(prior?.lastSeenAt ?: 0L, observedAt),
            ignored = false
        )
        val winnerCategory = evidence.winnerCategory
        return observation.copy(
            id = existing?.id ?: 0,
            projectId = existing?.projectId ?: observation.projectId,
            originalTerm = existing?.originalTerm?.takeIf(String::isNotBlank) ?: observation.originalTerm.trim(),
            translatedTerm = evidence.winnerTarget,
            category = TermCategory.valueOf(winnerCategory),
            notes = encode(evidence),
            isAutoExtracted = true,
            source = LexiconSource.AI.name,
            reviewStatus = ReviewStatus.CANDIDATE.name,
            createdAt = existing?.createdAt ?: observedAt
        )
    }

    fun markIgnored(term: GlossaryEntity): GlossaryEntity {
        val evidence = decode(term) ?: seed(term)
        return term.copy(notes = encode(evidence.copy(ignored = true)))
    }

    fun confirm(term: GlossaryEntity): GlossaryEntity {
        val evidence = decode(term)
        return term.copy(
            translatedTerm = evidence?.winnerTarget?.ifBlank { term.translatedTerm } ?: term.translatedTerm,
            category = evidence?.winnerCategory?.takeIf(String::isNotBlank)?.let(TermCategory::valueOf) ?: term.category,
            notes = evidence?.winnerNotes ?: term.notes,
            reviewStatus = ReviewStatus.CONFIRMED.name
        )
    }

    fun isIgnored(term: GlossaryEntity): Boolean = decode(term)?.ignored == true

    fun hasConflict(term: GlossaryEntity): Boolean = decode(term)?.hasConflict == true

    fun isHighConfidenceForBatch(term: GlossaryEntity): Boolean =
        decode(term)?.isHighConfidenceForBatch == true

    fun displayNotes(term: GlossaryEntity): String = decode(term)?.winnerNotes ?: term.notes

    fun evidenceSummary(term: GlossaryEntity): String? = decode(term)?.let { evidence ->
        val targets = evidence.targetVotes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" / ") { "${it.key} ${it.value}" }
        "出现 ${evidence.observationCount} 次 · $targets · 首次第 ${evidence.firstSeenChapterIndex} 章 · 最近第 ${evidence.lastSeenChapterIndex} 章"
    }

    private fun seed(term: GlossaryEntity): LegacyGlossaryCandidateEvidence =
        LegacyGlossaryCandidateEvidence(
            targetVotes = term.translatedTerm.trim().takeIf(String::isNotBlank)?.let { mapOf(it to 1) }.orEmpty(),
            categoryVotes = mapOf(term.category.name to 1),
            notesVotes = term.notes.trim().takeIf(String::isNotBlank)?.let { mapOf(it to 1) }.orEmpty(),
            observationCount = 1,
            firstSeenChapterIndex = 0,
            lastSeenChapterIndex = 0,
            firstSeenAt = term.createdAt,
            lastSeenAt = term.createdAt
        )

    private fun encode(evidence: LegacyGlossaryCandidateEvidence): String = PREFIX + buildString {
        append('{')
        append("\"targetVotes\":").append(LexiconCandidateVoting.encodeVotes(evidence.targetVotes)).append(',')
        append("\"categoryVotes\":").append(LexiconCandidateVoting.encodeVotes(evidence.categoryVotes)).append(',')
        append("\"notesVotes\":").append(LexiconCandidateVoting.encodeVotes(evidence.notesVotes)).append(',')
        append("\"observationCount\":").append(evidence.observationCount).append(',')
        append("\"firstSeenChapterIndex\":").append(evidence.firstSeenChapterIndex).append(',')
        append("\"lastSeenChapterIndex\":").append(evidence.lastSeenChapterIndex).append(',')
        append("\"firstSeenAt\":").append(evidence.firstSeenAt).append(',')
        append("\"lastSeenAt\":").append(evidence.lastSeenAt).append(',')
        append("\"ignored\":").append(evidence.ignored)
        append('}')
    }
}
