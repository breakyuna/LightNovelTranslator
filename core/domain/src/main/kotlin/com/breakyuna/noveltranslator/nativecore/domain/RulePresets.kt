package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Locale

/**
 * Native, self-contained rule/prompt preset. This is not an LG preset file: LG stores one
 * preset per rule kind in separate directories with its own file naming, and this type neither
 * reads nor writes that layout.
 *
 * A preset is immutable and bounded. Construction rejects duplicate stable IDs, duplicate
 * kind/source/pattern identities, invalid regex (including RE2/J program/aggregate limits) and
 * oversized metadata. Applying it never mutates the project policy or the preset.
 */
class RulePreset(
    val id: String,
    val name: String,
    val description: String = "",
    rules: List<ProjectTextRule> = emptyList(),
    val prompt: String = "",
    val promptEnabled: Boolean = false,
) {
    val rules: List<ProjectTextRule> = java.util.Collections.unmodifiableList(ArrayList(rules))

    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARS && id.none(Char::isISOControl))
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none(Char::isISOControl))
        require(description.length <= MAX_DESCRIPTION_CHARS && '\u0000' !in description)
        require(this.rules.size <= MAX_RULES)
        require(this.rules.map { it.id }.distinct().size == this.rules.size)
        require(this.rules.map(::ruleSourceKey).distinct().size == this.rules.size)
        require(prompt.length <= MAX_PROMPT_CHARS && '\u0000' !in prompt)
        for (text in listOf(id, name, description, prompt)) TextProjectFingerprint.utf8(text)
        // Compiles every rule (enabled or not) and enforces the shared policy/program budget.
        TextRuleValidation.validate(ProjectTextPolicy(this.rules, prompt, promptEnabled))
    }

    /**
     * Produces a new policy and a diff summary. [RulePresetApplyMode.REPLACE] makes the preset
     * the complete policy; [RulePresetApplyMode.MERGE] keeps existing rules and appends only
     * new identities. A merge that would assign an existing source/pattern identity to another
     * stable ID is rejected with [RulePresetConflict] instead of silently replacing it.
     */
    fun applyTo(
        policy: ProjectTextPolicy,
        mode: RulePresetApplyMode = RulePresetApplyMode.REPLACE,
        includePrompt: Boolean = true,
    ): RulePresetApplication {
        val result = ArrayList(policy.rules)
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        val updated = ArrayList<String>()
        val unchanged = ArrayList<String>()
        if (mode == RulePresetApplyMode.REPLACE) {
            val existingById = policy.rules.associateBy { it.id }
            val presetById = rules.associateBy { it.id }
            for (rule in rules) {
                val existing = existingById[rule.id]
                when {
                    existing == null -> added.add(rule.id)
                    sameRuleContent(existing, rule) -> unchanged.add(rule.id)
                    else -> updated.add(rule.id)
                }
            }
            for (rule in policy.rules) if (rule.id !in presetById) removed.add(rule.id)
            result.clear()
            result.addAll(rules)
        } else {
            for (rule in rules) {
                val byId = result.indexOfFirst { it.id == rule.id }
                if (byId >= 0) {
                    val existing = result[byId]
                    if (sameRuleContent(existing, rule)) {
                        unchanged.add(rule.id)
                    } else {
                        val keyTaken = result.indices.any { index ->
                            index != byId && ruleSourceKey(result[index]) == ruleSourceKey(rule)
                        }
                        if (keyTaken) throw RulePresetConflict(RulePresetConflict.Reason.DUPLICATE_RULE)
                        result[byId] = rule
                        updated.add(rule.id)
                    }
                    continue
                }
                val bySource = result.indexOfFirst { ruleSourceKey(it) == ruleSourceKey(rule) }
                if (bySource >= 0) {
                    if (sameRuleContent(result[bySource], rule)) {
                        // The existing stable ID wins; the diff always names an ID that exists.
                        unchanged.add(result[bySource].id)
                    } else {
                        throw RulePresetConflict(RulePresetConflict.Reason.DUPLICATE_RULE)
                    }
                    continue
                }
                if (result.size >= MAX_RULES) throw RulePresetConflict(RulePresetConflict.Reason.LIMIT)
                result.add(rule)
                added.add(rule.id)
            }
        }
        val nextPrompt = if (includePrompt) prompt else policy.prompt
        val nextPromptEnabled = if (includePrompt) promptEnabled else policy.promptEnabled
        val diff = RulePresetDiff(
            addedRuleIds = added,
            removedRuleIds = removed,
            updatedRuleIds = updated,
            unchangedRuleIds = unchanged,
            promptChanged = nextPrompt != policy.prompt,
            promptEnabledChanged = nextPromptEnabled != policy.promptEnabled,
        )
        return RulePresetApplication(
            presetId = id,
            mode = mode,
            policy = ProjectTextPolicy(result, nextPrompt, nextPromptEnabled),
            diff = diff,
        )
    }

    fun toExchange(): RuleExchange = RuleExchange(rules, prompt, promptEnabled)

    override fun toString(): String = "RulePreset(redacted)"

    companion object {
        const val MAX_ID_CHARS = 128
        const val MAX_NAME_CHARS = 128
        const val MAX_DESCRIPTION_CHARS = 1_024
        const val MAX_RULES = 256
        const val MAX_PROMPT_CHARS = 16_384

        fun fromExchange(
            id: String,
            name: String,
            description: String = "",
            exchange: RuleExchange,
        ): RulePreset = RulePreset(
            id = id,
            name = name,
            description = description,
            rules = exchange.rules,
            prompt = exchange.prompt,
            promptEnabled = exchange.promptEnabled,
        )
    }
}

enum class RulePresetApplyMode { REPLACE, MERGE }

class RulePresetConflict(val reason: Reason) : IllegalArgumentException("Rule preset conflict: $reason") {
    enum class Reason { DUPLICATE_RULE, LIMIT }
}

/** Rule-ID-only diff; no rule body is copied into diagnostics. */
class RulePresetDiff(
    addedRuleIds: List<String>,
    removedRuleIds: List<String>,
    updatedRuleIds: List<String>,
    unchangedRuleIds: List<String>,
    val promptChanged: Boolean,
    val promptEnabledChanged: Boolean,
) {
    val addedRuleIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(addedRuleIds))
    val removedRuleIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(removedRuleIds))
    val updatedRuleIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(updatedRuleIds))
    val unchangedRuleIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(unchangedRuleIds))

    init {
        for (ids in listOf(this.addedRuleIds, this.removedRuleIds, this.updatedRuleIds, this.unchangedRuleIds)) {
            require(ids.size <= RulePreset.MAX_RULES && ids.all { it.isNotBlank() && it.length <= RulePreset.MAX_ID_CHARS })
            require(ids.distinct().size == ids.size)
        }
    }

    val rulesChanged: Boolean
        get() = addedRuleIds.isNotEmpty() || removedRuleIds.isNotEmpty() || updatedRuleIds.isNotEmpty()
    val changed: Boolean get() = rulesChanged || promptChanged || promptEnabledChanged

    override fun toString(): String =
        "RulePresetDiff(added=${addedRuleIds.size}, removed=${removedRuleIds.size}, " +
            "updated=${updatedRuleIds.size}, unchanged=${unchangedRuleIds.size})"
}

class RulePresetApplication(
    val presetId: String,
    val mode: RulePresetApplyMode,
    val policy: ProjectTextPolicy,
    val diff: RulePresetDiff,
) {
    init {
        require(presetId.isNotBlank() && presetId.length <= RulePreset.MAX_ID_CHARS)
    }
    override fun toString(): String = "RulePresetApplication(preset=$presetId, mode=$mode, changed=${diff.changed})"
}

/**
 * Immutable, bounded preset collection with case-insensitive display-name uniqueness.
 * Order is the caller's order; no preset is reordered or hidden.
 */
class RulePresetCatalog(presets: List<RulePreset>) {
    val presets: List<RulePreset> = java.util.Collections.unmodifiableList(ArrayList(presets))

    init {
        require(this.presets.size <= MAX_PRESETS)
        require(this.presets.map { it.id }.distinct().size == this.presets.size)
        val names = this.presets.map { it.name.lowercase(Locale.ROOT) }
        require(names.distinct().size == names.size)
    }

    fun find(id: String): RulePreset? = presets.firstOrNull { it.id == id }

    override fun toString(): String = "RulePresetCatalog(size=${presets.size})"

    companion object {
        const val MAX_PRESETS = 128
    }
}

/** Lightweight library listing; complete rule/prompt bodies are loaded only after selection. */
data class RulePresetSummary(val id: String, val name: String, val description: String, val rules: Int)
