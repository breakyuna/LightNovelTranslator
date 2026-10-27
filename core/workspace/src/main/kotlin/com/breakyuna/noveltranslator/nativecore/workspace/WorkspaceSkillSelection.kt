package com.breakyuna.noveltranslator.nativecore.workspace

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

/** Why an enabled skill entered the automatic context selection; these signals are not grants. */
enum class WorkspaceSkillSelectionReason {
    EXPLICIT_REFERENCE,
    LANGUAGE_MATCH,
    TAG_MATCH,
    NAME_MATCH,
    DESCRIPTION_MATCH,
    ORDER_FALLBACK,
}

/** One selected skill and the bounded body excerpt that may be injected into the prompt. */
class WorkspaceSkillSelectionEntry(
    val summary: WorkspaceSkillSummary,
    val score: Int,
    selectionReasons: List<WorkspaceSkillSelectionReason>,
    val text: String,
    val truncated: Boolean,
) {
    val reasons: List<WorkspaceSkillSelectionReason> = workspaceFrozenList(selectionReasons)

    init {
        require(score >= 0) { "Invalid skill selection score" }
        require(reasons.isNotEmpty()) { "Selected skill has no reason" }
    }

    val id: WorkspaceSkillId get() = summary.id
    val name: String get() = summary.name
    val order: Int get() = summary.order
    val textChars: Int get() = text.length

    override fun toString(): String =
        "WorkspaceSkillSelectionEntry(id=${id.value}, score=$score, chars=${text.length}, " +
            "truncated=$truncated, reasons=$reasons)"
}

/** One enabled skill whose body could not be read; the failure is reported, never hidden. */
class WorkspaceSkillSelectionFailure(
    val id: WorkspaceSkillId,
    val reason: Reason,
    val errorType: String? = null,
) {
    enum class Reason { NOT_FOUND, READ_FAILED }

    init {
        require(reason != Reason.READ_FAILED || !errorType.isNullOrBlank()) {
            "Read failure requires a type"
        }
    }

    override fun toString(): String =
        "WorkspaceSkillSelectionFailure(id=${id.value}, reason=$reason" +
            (errorType?.let { ", errorType=$it" } ?: "") + ")"
}

/**
 * Result of automatic selection. [selected] contains only bodies that were read successfully;
 * [truncated] means a configured skill/character cap cut content or skipped a lower-ranked
 * candidate. [failures] are visible to the caller and are not counted as truncation.
 */
class WorkspaceSkillSelectionResult(
    selected: List<WorkspaceSkillSelectionEntry>,
    failures: List<WorkspaceSkillSelectionFailure>,
    val candidateCount: Int,
    val autoSelectExcludedCount: Int,
    val truncated: Boolean,
) {
    val selected: List<WorkspaceSkillSelectionEntry> = workspaceFrozenList(selected)
    val failures: List<WorkspaceSkillSelectionFailure> = workspaceFrozenList(failures)
    val selectedIds: List<WorkspaceSkillId> = workspaceFrozenList(selected.map { it.id })
    val injectedChars: Int = selected.fold(0) { total, entry -> total + entry.text.length }

    override fun toString(): String =
        "WorkspaceSkillSelectionResult(selected=${selectedIds.map { it.value }}, " +
            "failures=${failures.size}, chars=$injectedChars, truncated=$truncated)"
}

/**
 * Automatic context selection only. It filters enabled skills through [SkillRegistry.list], ranks
 * explicit references, language, tag, name/description signals and display order, then reads at
 * most [DEFAULT_MAX_SKILLS] bounded bodies. It never executes a skill and never changes metadata.
 *
 * A skill with `policy.allowAutoSelect = false` is excluded unless the caller explicitly references
 * it. Read failures are returned in [WorkspaceSkillSelectionResult.failures]; cancellation always
 * propagates.
 */
object WorkspaceSkillSelection {
    const val DEFAULT_MAX_SKILLS: Int = 4
    const val DEFAULT_MAX_TOTAL_CHARS: Int = 8_192
    const val DEFAULT_MAX_SKILL_CHARS: Int = 2_048

    /** Automatic context injection never selects more than four skills. */
    const val MAX_SELECTED_SKILLS: Int = DEFAULT_MAX_SKILLS
    const val MAX_SELECTION_CHARS: Int = 256 * 1024
    const val MAX_TASK_CHARS: Int = 64 * 1024

    private const val EXPLICIT_SCORE = 10_000
    private const val LANGUAGE_SCORE = 1_000
    private const val TAG_SCORE = 500
    private const val NAME_SCORE = 300
    private const val TAG_TOKEN_SCORE = 100
    private const val DESCRIPTION_SCORE = 50

    suspend fun select(
        registry: SkillRegistry,
        documents: WorkspaceSkillDocumentPort,
        task: String,
        language: String? = null,
        tag: String? = null,
        explicitSkillIds: Set<WorkspaceSkillId> = emptySet(),
        maxSkills: Int = DEFAULT_MAX_SKILLS,
        maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
        maxSkillChars: Int = DEFAULT_MAX_SKILL_CHARS,
    ): WorkspaceSkillSelectionResult {
        require(maxSkills in 1..MAX_SELECTED_SKILLS) { "Invalid skill selection count" }
        require(maxTotalChars in 1..MAX_SELECTION_CHARS) { "Invalid skill selection budget" }
        require(maxSkillChars in 1..MAX_SELECTION_CHARS) { "Invalid skill body budget" }
        require(task.length <= MAX_TASK_CHARS) { "Skill selection task too long" }
        requireWellFormedUnicode(task)
        require(explicitSkillIds.size <= 64) { "Too many explicit skills" }

        val normalizedLanguage = normalizeFilter(language, "language")
        val normalizedTag = normalizeFilter(tag, "tag")
        val candidates = registry.list(
            enabledOnly = true,
            language = normalizedLanguage,
            tag = normalizedTag,
        )
        val normalizedTask = Normalizer.normalize(task, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val taskTokens = tokens(normalizedTask)
        val explicit = LinkedHashSet<String>(explicitSkillIds.size + 4)
        explicitSkillIds.forEach { explicit += it.value }
        explicit += explicitMentions(task)

        val ranked = candidates
            .map { skill ->
                scoreSkill(skill, normalizedTask, taskTokens, normalizedLanguage, normalizedTag, explicit)
            }
            .filter { it.skill.policy.allowAutoSelect || it.skill.id.value in explicit }
            .sortedWith(RANKING)

        val selected = ArrayList<WorkspaceSkillSelectionEntry>(minOf(ranked.size, maxSkills))
        val failures = ArrayList<WorkspaceSkillSelectionFailure>()
        var remaining = maxTotalChars
        var contentTruncated = false
        var omittedByBudget = false
        for (candidate in ranked) {
            if (selected.size >= maxSkills) {
                omittedByBudget = true
                break
            }
            if (remaining == 0) {
                omittedByBudget = true
                break
            }
            val limit = minOf(maxSkillChars, remaining)
            val loaded = try {
                documents.read(candidate.skill.id, limit + 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += WorkspaceSkillSelectionFailure(
                    id = candidate.skill.id,
                    reason = WorkspaceSkillSelectionFailure.Reason.READ_FAILED,
                    errorType = errorTypeOf(failure),
                )
                continue
            }
            if (loaded == null) {
                failures += WorkspaceSkillSelectionFailure(
                    id = candidate.skill.id,
                    reason = WorkspaceSkillSelectionFailure.Reason.NOT_FOUND,
                )
                continue
            }
            val cut = loaded.length > limit
            val text = truncateChars(loaded, limit)
            selected += WorkspaceSkillSelectionEntry(
                summary = candidate.skill,
                score = candidate.score,
                selectionReasons = candidate.reasons,
                text = text,
                truncated = cut,
            )
            remaining -= text.length
            if (cut) contentTruncated = true
        }

        return WorkspaceSkillSelectionResult(
            selected = selected,
            failures = failures,
            candidateCount = candidates.size,
            autoSelectExcludedCount = candidates.size - ranked.size,
            truncated = contentTruncated || omittedByBudget,
        )
    }

    private fun scoreSkill(
        skill: WorkspaceSkillSummary,
        normalizedTask: String,
        taskTokens: Set<String>,
        language: String?,
        tag: String?,
        explicit: Set<String>,
    ): ScoredSkill {
        var score = 0
        val reasons = ArrayList<WorkspaceSkillSelectionReason>(4)
        if (skill.id.value in explicit) {
            score += EXPLICIT_SCORE
            reasons += WorkspaceSkillSelectionReason.EXPLICIT_REFERENCE
        }
        if (language != null && language in skill.languages) {
            score += LANGUAGE_SCORE
            reasons += WorkspaceSkillSelectionReason.LANGUAGE_MATCH
        }
        if (tag != null && tag in skill.tags) {
            score += TAG_SCORE
            reasons += WorkspaceSkillSelectionReason.TAG_MATCH
        }
        val normalizedName = skill.name.lowercase(Locale.ROOT)
        if (normalizedName.length >= 2 && normalizedTask.contains(normalizedName)) {
            score += NAME_SCORE
            reasons += WorkspaceSkillSelectionReason.NAME_MATCH
        }
        val skillTagTokens = LinkedHashSet<String>()
        skill.tags.forEach { skillTagTokens += tokens(it.lowercase(Locale.ROOT)) }
        skillTagTokens.retainAll(taskTokens)
        if (skillTagTokens.isNotEmpty()) {
            score += minOf(skillTagTokens.size, 2) * TAG_TOKEN_SCORE
            if (WorkspaceSkillSelectionReason.TAG_MATCH !in reasons) {
                reasons += WorkspaceSkillSelectionReason.TAG_MATCH
            }
        }
        val descriptionTokens = LinkedHashSet(tokens(skill.description.lowercase(Locale.ROOT)))
        descriptionTokens.retainAll(taskTokens)
        if (descriptionTokens.isNotEmpty()) {
            score += minOf(descriptionTokens.size, 3) * DESCRIPTION_SCORE
            reasons += WorkspaceSkillSelectionReason.DESCRIPTION_MATCH
        }
        if (reasons.isEmpty()) reasons += WorkspaceSkillSelectionReason.ORDER_FALLBACK
        return ScoredSkill(skill, score, reasons)
    }

    /** Lower display order wins ties; name and id keep the order deterministic across catalogs. */
    private val RANKING: Comparator<ScoredSkill> = compareByDescending<ScoredSkill> { it.score }
        .thenBy { it.skill.order }
        .thenBy { it.skill.conflictNameKey }
        .thenBy { it.skill.id.value }

    private class ScoredSkill(
        val skill: WorkspaceSkillSummary,
        val score: Int,
        val reasons: List<WorkspaceSkillSelectionReason>,
    )

    private fun normalizeFilter(value: String?, label: String): String? {
        if (value == null) return null
        val normalized = value.trim().lowercase(Locale.ROOT)
        require(normalized.isNotEmpty()) { "Empty skill $label filter" }
        require(normalized.length <= 64) { "Skill $label filter too long" }
        return normalized
    }

    private fun tokens(value: String): Set<String> {
        val result = LinkedHashSet<String>()
        val current = StringBuilder()
        for (character in value) {
            if (character.isLetterOrDigit()) {
                current.append(character)
            } else {
                if (current.length >= 2) result += current.toString()
                current.clear()
            }
        }
        if (current.length >= 2) result += current.toString()
        return result
    }

    /** `@skill-id` mentions are an additional explicit-reference source; the id remains opaque. */
    private fun explicitMentions(task: String): Set<String> {
        val result = LinkedHashSet<String>()
        var index = task.indexOf('@')
        while (index >= 0) {
            var end = index + 1
            while (end < task.length && isReferenceCharacter(task[end])) end++
            if (end > index + 1) result += task.substring(index + 1, end)
            index = task.indexOf('@', end)
        }
        return result
    }

    private fun isReferenceCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_' ||
            character == '.' || character == ':'

    private fun truncateChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
        return text.substring(0, end)
    }

    private fun errorTypeOf(failure: Exception): String {
        val name = failure.javaClass.simpleName
        return if (name.isNullOrBlank()) "Exception" else name
    }
}
