package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class LexiconCandidateState { ACTIVE, IMPORTED, IGNORED }

/**
 * Durable evidence collected from AI terminology observations. It is deliberately separate
 * from [LexiconEntryEntity]: an observation can be reviewed without becoming a translation rule.
 */
@Entity(
    tableName = "lexicon_candidate_aggregates",
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectV2Entity::class,
            parentColumns = ["id"],
            childColumns = ["translationProjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("translationProjectId"),
        Index(value = ["translationProjectId", "normalizedSourceTerm"], unique = true),
        Index(value = ["translationProjectId", "state"])
    ]
)
data class LexiconCandidateAggregateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val translationProjectId: Long,
    val sourceTerm: String,
    val normalizedSourceTerm: String,
    val targetVotesJson: String = "{}",
    val categoryVotesJson: String = "{}",
    val notesVotesJson: String = "{}",
    /** Cached deterministic winners make legacy migration and list rendering resilient. */
    val winnerTargetTerm: String = "",
    val winnerCategory: String = "",
    val winnerNotes: String = "",
    val observationCount: Int = 0,
    val firstSeenChapterIndex: Int = 0,
    val lastSeenChapterIndex: Int = 0,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val sourceHitCount: Int = 0,
    val independentHitCount: Int = 0,
    val parentHitCount: Int = 0,
    val caseSensitive: Boolean = false,
    val state: String = LexiconCandidateState.ACTIVE.name
)

data class CandidateObservation(
    val translationProjectId: Long,
    val sourceTerm: String,
    val targetTerm: String,
    val category: String,
    val notes: String = "",
    val chapterIndex: Int,
    val observedAt: Long,
    val sourceHitCount: Int = 0,
    val independentHitCount: Int = 0,
    val parentHitCount: Int = 0,
    val caseSensitive: Boolean = false
)

data class LexiconCandidateReview(
    val aggregate: LexiconCandidateAggregateEntity,
    val winnerTargetTerm: String,
    val winnerCategory: String,
    val winnerNotes: String,
    val targetVotes: Map<String, Int>,
    val categoryVotes: Map<String, Int>,
    val notesVotes: Map<String, Int>
) {
    val hasTargetConflict: Boolean get() = targetVotes.keys.size > 1
    val hasCategoryConflict: Boolean get() = categoryVotes.keys.size > 1
    val isHighConfidenceForBatch: Boolean
        get() {
            if (observationCount < 2 || targetVotes.isEmpty() || categoryVotes.isEmpty()) return false
            val targetWinnerVotes = targetVotes[winnerTargetTerm] ?: 0
            val categoryWinnerVotes = categoryVotes[winnerCategory] ?: 0
            return targetWinnerVotes >= 2 &&
                categoryWinnerVotes >= 2 &&
                targetWinnerVotes * 100 >= observationCount * 80 &&
                categoryWinnerVotes * 100 >= observationCount * 80
        }
    val id: Long get() = aggregate.id
    val sourceTerm: String get() = aggregate.sourceTerm
    val observationCount: Int get() = aggregate.observationCount
}

data class CandidateImportConflict(
    val candidate: LexiconCandidateReview,
    val existing: LexiconEntryEntity
)

sealed interface CandidateImportResult {
    data class Imported(val candidateId: Long, val overwritten: Boolean) : CandidateImportResult
    data class Conflict(val details: CandidateImportConflict) : CandidateImportResult
    data class Skipped(val candidateId: Long) : CandidateImportResult
    data class Failed(val message: String) : CandidateImportResult
}

/** JSON vote storage and deterministic winner selection shared by the repository and tests. */
object LexiconCandidateVoting {
    val aiCategories: Set<String> = setOf(
        "CHARACTER",
        "LOCATION",
        "LORE",
        "SKILL",
        "ITEM",
        "HONORIFIC"
    )

    fun normalizeSourceTerm(value: String): String = Normalizer.normalize(
        value.trim().replace(Regex("\\s+"), " "),
        Normalizer.Form.NFKC
    ).lowercase(Locale.ROOT)

    fun decodeVotes(json: String): Map<String, Int> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val objectJson = JSONObject(json)
            val values = mutableMapOf<String, Int>()
            objectJson.keys().forEach { key ->
                val value = objectJson.optInt(key, 0)
                if (key.isNotBlank() && value > 0) values[key] = value
            }
            values.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Stable key ordering avoids making winners or diffs depend on JSONObject iteration order. */
    fun encodeVotes(votes: Map<String, Int>): String = buildString {
        append('{')
        votes.filter { it.key.isNotBlank() && it.value > 0 }
            .entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { normalizeSourceTerm(it.key) }.thenBy { it.key })
            .forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(JSONObject.quote(entry.key)).append(':').append(entry.value)
            }
        append('}')
    }

    fun winner(votes: Map<String, Int>): String? = votes.entries
        .filter { it.key.isNotBlank() && it.value > 0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { normalizeSourceTerm(it.key) }
                .thenBy { it.key }
        )
        .firstOrNull()
        ?.key

    fun review(aggregate: LexiconCandidateAggregateEntity): LexiconCandidateReview {
        val targetVotes = decodeVotes(aggregate.targetVotesJson)
        val categoryVotes = decodeVotes(aggregate.categoryVotesJson)
        val notesVotes = decodeVotes(aggregate.notesVotesJson)
        return LexiconCandidateReview(
            aggregate = aggregate,
            winnerTargetTerm = winner(targetVotes) ?: aggregate.winnerTargetTerm,
            winnerCategory = winner(categoryVotes) ?: aggregate.winnerCategory,
            winnerNotes = winner(notesVotes) ?: aggregate.winnerNotes,
            targetVotes = targetVotes,
            categoryVotes = categoryVotes,
            notesVotes = notesVotes
        )
    }

    fun merge(
        existing: LexiconCandidateAggregateEntity?,
        observation: CandidateObservation
    ): LexiconCandidateAggregateEntity {
        val normalized = normalizeSourceTerm(observation.sourceTerm)
        require(normalized.isNotBlank()) { "Candidate source term must not be blank" }
        require(observation.targetTerm.isNotBlank()) { "Candidate target term must not be blank" }
        require(observation.category in aiCategories) { "Unsupported AI candidate category: ${observation.category}" }

        val targetVotes = decodeVotes(existing?.targetVotesJson.orEmpty()).toMutableMap()
        targetVotes[observation.targetTerm] = (targetVotes[observation.targetTerm] ?: 0) + 1
        val categoryVotes = decodeVotes(existing?.categoryVotesJson.orEmpty()).toMutableMap()
        categoryVotes[observation.category] = (categoryVotes[observation.category] ?: 0) + 1
        val notesVotes = decodeVotes(existing?.notesVotesJson.orEmpty()).toMutableMap()
        if (observation.notes.isNotBlank()) {
            notesVotes[observation.notes] = (notesVotes[observation.notes] ?: 0) + 1
        }

        val firstChapter = when {
            existing == null || existing.firstSeenChapterIndex == 0 -> observation.chapterIndex
            else -> minOf(existing.firstSeenChapterIndex, observation.chapterIndex)
        }
        val firstAt = when {
            existing == null || existing.firstSeenAt == 0L -> observation.observedAt
            else -> minOf(existing.firstSeenAt, observation.observedAt)
        }
        val sourceTerm = existing?.sourceTerm?.takeIf { it.isNotBlank() } ?: observation.sourceTerm.trim()
        return LexiconCandidateAggregateEntity(
            id = existing?.id ?: 0,
            translationProjectId = existing?.translationProjectId ?: observation.translationProjectId,
            sourceTerm = sourceTerm,
            normalizedSourceTerm = existing?.normalizedSourceTerm?.ifBlank { normalized } ?: normalized,
            targetVotesJson = encodeVotes(targetVotes),
            categoryVotesJson = encodeVotes(categoryVotes),
            notesVotesJson = encodeVotes(notesVotes),
            winnerTargetTerm = winner(targetVotes).orEmpty(),
            winnerCategory = winner(categoryVotes).orEmpty(),
            winnerNotes = winner(notesVotes).orEmpty(),
            observationCount = (existing?.observationCount ?: 0) + 1,
            firstSeenChapterIndex = firstChapter,
            lastSeenChapterIndex = maxOf(existing?.lastSeenChapterIndex ?: 0, observation.chapterIndex),
            firstSeenAt = firstAt,
            lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observation.observedAt),
            sourceHitCount = (existing?.sourceHitCount ?: 0) + observation.sourceHitCount,
            independentHitCount = (existing?.independentHitCount ?: 0) + observation.independentHitCount,
            parentHitCount = (existing?.parentHitCount ?: 0) + observation.parentHitCount,
            caseSensitive = existing?.caseSensitive == true || observation.caseSensitive,
            state = existing?.state ?: LexiconCandidateState.ACTIVE.name
        )
    }
}

/**
 * Removes only candidates proven to be meaningless sub-items of a longer candidate. A short
 * name with at least one independent occurrence remains reviewable.
 */
object LexiconCandidateNoiseFilter {
    fun filterForReview(aggregates: List<LexiconCandidateAggregateEntity>): List<LexiconCandidateAggregateEntity> {
        val active = aggregates.filter { it.state == LexiconCandidateState.ACTIVE.name }
        return active.filter { candidate ->
            val hasIndependentEvidence = candidate.independentHitCount > 0 || candidate.sourceHitCount == 0
            val hasParentEvidence = candidate.parentHitCount > 0
            !(hasParentEvidence && !hasIndependentEvidence)
        }
    }
}

object LexiconCandidateStatePolicy {
    fun acceptsObservation(state: String?): Boolean =
        state.isNullOrBlank() || state == LexiconCandidateState.ACTIVE.name
}

object LexiconEntryPolicy {
    fun isEligibleForTranslation(entry: LexiconEntryEntity): Boolean =
        entry.enabled &&
            entry.reviewStatus == ReviewStatus.CONFIRMED.name &&
            entry.sourceTerm.isNotBlank() &&
            entry.targetTerm.isNotBlank()

    fun confirmedSourceTerms(entries: Iterable<LexiconEntryEntity>): Set<String> = entries
        .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
        .mapTo(mutableSetOf()) { LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) }
}

object LexiconCandidateImportPlanner {
    private val importableCategories = LexiconCandidateVoting.aiCategories + TermCategory.CUSTOM.name

    fun isImportableCategory(category: String): Boolean =
        category.trim().uppercase(Locale.ROOT) in importableCategories

    fun createOfficialEntry(
        review: LexiconCandidateReview,
        targetTerm: String = review.winnerTargetTerm,
        category: String = review.winnerCategory,
        notes: String = review.winnerNotes,
        now: Long = System.currentTimeMillis()
    ): LexiconEntryEntity {
        val normalizedCategory = category.trim().uppercase(Locale.ROOT)
        require(targetTerm.trim().isNotBlank()) { "Official glossary target must not be blank" }
        require(isImportableCategory(normalizedCategory)) { "Unsupported official glossary category: $category" }
        return LexiconEntryEntity(
            translationProjectId = review.aggregate.translationProjectId,
            sourceTerm = review.sourceTerm,
            targetTerm = targetTerm.trim(),
            kind = if (normalizedCategory == TermCategory.SKILL.name || normalizedCategory == TermCategory.ITEM.name) {
                LexiconKind.TERMINOLOGY.name
            } else {
                LexiconKind.PROPER_NOUN.name
            },
            category = normalizedCategory,
            notes = notes.trim(),
            caseSensitive = review.aggregate.caseSensitive,
            source = LexiconSource.AI.name,
            reviewStatus = ReviewStatus.CONFIRMED.name,
            createdAt = now,
            updatedAt = now
        )
    }

    fun overwriteOfficialEntry(
        existing: LexiconEntryEntity,
        review: LexiconCandidateReview,
        targetTerm: String = review.winnerTargetTerm,
        category: String = review.winnerCategory,
        notes: String = review.winnerNotes,
        now: Long = System.currentTimeMillis()
    ): LexiconEntryEntity {
        val normalizedCategory = category.trim().uppercase(Locale.ROOT)
        require(targetTerm.trim().isNotBlank()) { "Official glossary target must not be blank" }
        require(isImportableCategory(normalizedCategory)) { "Unsupported official glossary category: $category" }
        return existing.copy(
            targetTerm = targetTerm.trim(),
            category = normalizedCategory,
            notes = notes.trim(),
            updatedAt = now,
            reviewStatus = ReviewStatus.CONFIRMED.name
        )
    }
}
