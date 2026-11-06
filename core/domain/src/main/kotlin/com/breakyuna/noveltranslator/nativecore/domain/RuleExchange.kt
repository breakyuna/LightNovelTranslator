package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/**
 * A native rule and prompt exchange envelope, named `lnt-rule-exchange` so it is never
 * mistaken for a LinguaGacha preset.
 *
 * LG quality-rule files are a flat `src`/`dst`/`info`/`regex`/`case_sensitive` JSON array or
 * XLSX (`quality-rule-file-io.ts`); LG presets are per-kind files in their own directories.
 * This envelope intentionally reads and writes neither shape, and no CSV contract exists in
 * that source. Conversion is a separate, explicit step and must not be implied by this type.
 *
 * Rules must carry (or deterministically derive) stable IDs. Source order is a project fact and
 * is preserved. Unlike a preset, an envelope may contain two stable IDs that share one
 * kind/source/pattern identity: that lets an existing project round-trip without loss, and the
 * preview classifies the later identity as unchanged or conflicting rather than merging it.
 * Construction validates structural limits only; regex compilation belongs to
 * `RulePreviewService` in `:core:engine` because a preview must report a bad pattern instead of
 * failing the whole import.
 */
class RuleExchange(
    rules: List<ProjectTextRule> = emptyList(),
    val prompt: String = "",
    val promptEnabled: Boolean = false,
) {
    val rules: List<ProjectTextRule> = java.util.Collections.unmodifiableList(ArrayList(rules))

    init {
        require(this.rules.size <= MAX_RULES)
        require(this.rules.map { it.id }.distinct().size == this.rules.size)
        require(prompt.length <= MAX_PROMPT_CHARS && '\u0000' !in prompt)
        TextProjectFingerprint.utf8(prompt)
    }

    fun policy(): ProjectTextPolicy = ProjectTextPolicy(rules, prompt, promptEnabled)

    /**
     * Read-only preview against [current]. Nothing is written; the returned policy is a new
     * value and [current] is never mutated.
     *
     * A collision on the stable ID is a content update candidate: [RuleExchangeDuplicatePolicy.SKIP]
     * reports it as conflicting and leaves the current rule untouched, while
     * [RuleExchangeDuplicatePolicy.OVERWRITE] replaces the rule at its existing position.
     * A different stable ID that reuses an existing kind/source/pattern identity is always
     * conflicting because merging two identities silently would change project facts.
     *
     * [check] lets the engine supply regex/program validation without this module depending on
     * a compiler; a non-null result marks the entry conflicting for that reason. When
     * [includePrompt] is false the current prompt and its enable flag are kept.
     */
    fun preview(
        current: ProjectTextPolicy,
        duplicatePolicy: RuleExchangeDuplicatePolicy = RuleExchangeDuplicatePolicy.SKIP,
        includePrompt: Boolean = true,
        check: (ProjectTextRule) -> RuleExchangeConflictReason? = { null },
    ): RuleExchangePreview {
        val working = ArrayList(current.rules)
        val entries = ArrayList<RuleExchangeEntryPreview>(rules.size)
        for (rule in rules) {
            val rejected = check(rule)
            if (rejected != null) {
                entries.add(conflicting(rule, rejected, null))
                continue
            }
            val byId = working.indexOfFirst { it.id == rule.id }
            if (byId >= 0) {
                val existing = working[byId]
                val keyTaken = working.indices.any { index ->
                    index != byId && ruleSourceKey(working[index]) == ruleSourceKey(rule)
                }
                when {
                    sameRuleContent(existing, rule) -> entries.add(
                        RuleExchangeEntryPreview(rule.id, rule.kind, RuleExchangeDisposition.UNCHANGED, matchedRuleId = existing.id)
                    )
                    keyTaken -> entries.add(conflicting(rule, RuleExchangeConflictReason.DUPLICATE_SOURCE, existing.id))
                    duplicatePolicy == RuleExchangeDuplicatePolicy.OVERWRITE -> {
                        working[byId] = rule
                        entries.add(RuleExchangeEntryPreview(rule.id, rule.kind, RuleExchangeDisposition.UPDATED, matchedRuleId = existing.id))
                    }
                    else -> entries.add(conflicting(rule, RuleExchangeConflictReason.CONTENT_CONFLICT, existing.id))
                }
                continue
            }
            val bySource = working.indexOfFirst { ruleSourceKey(it) == ruleSourceKey(rule) }
            if (bySource >= 0) {
                val existing = working[bySource]
                entries.add(
                    if (sameRuleContent(existing, rule))
                        RuleExchangeEntryPreview(rule.id, rule.kind, RuleExchangeDisposition.UNCHANGED, matchedRuleId = existing.id)
                    else conflicting(rule, RuleExchangeConflictReason.DUPLICATE_SOURCE, existing.id)
                )
                continue
            }
            if (working.size >= MAX_RULES) {
                entries.add(conflicting(rule, RuleExchangeConflictReason.LIMIT, null))
                continue
            }
            working.add(rule)
            entries.add(RuleExchangeEntryPreview(rule.id, rule.kind, RuleExchangeDisposition.ADDED))
        }
        val nextPrompt = if (includePrompt) prompt else current.prompt
        val nextPromptEnabled = if (includePrompt) promptEnabled else current.promptEnabled
        return RuleExchangePreview(
            entries = entries,
            policy = ProjectTextPolicy(working, nextPrompt, nextPromptEnabled),
            duplicatePolicy = duplicatePolicy,
            promptChanged = nextPrompt != current.prompt,
            promptEnabledChanged = nextPromptEnabled != current.promptEnabled,
        )
    }

    override fun toString(): String = "RuleExchange(redacted)"

    private fun conflicting(
        rule: ProjectTextRule,
        reason: RuleExchangeConflictReason,
        matchedRuleId: String?,
    ): RuleExchangeEntryPreview = RuleExchangeEntryPreview(
        id = rule.id,
        kind = rule.kind,
        disposition = RuleExchangeDisposition.CONFLICTING,
        conflict = reason,
        matchedRuleId = matchedRuleId,
    )

    companion object {
        const val MAX_RULES = 256
        const val MAX_PROMPT_CHARS = 16_384

        fun of(policy: ProjectTextPolicy): RuleExchange =
            RuleExchange(policy.rules, policy.prompt, policy.promptEnabled)
    }
}

/** Stable rule identity used for duplicate detection: target/note/enable edits keep it. */
internal data class RuleSourceKey(
    val kind: TextRuleKind,
    val source: String,
    val regex: Boolean,
    val caseSensitive: Boolean,
) {
    companion object {
        fun of(rule: ProjectTextRule): RuleSourceKey =
            RuleSourceKey(rule.kind, rule.source, rule.regex, rule.caseSensitive)
    }
}

internal fun ruleSourceKey(rule: ProjectTextRule): RuleSourceKey = RuleSourceKey.of(rule)

internal fun sameRuleContent(left: ProjectTextRule, right: ProjectTextRule): Boolean =
    left.kind == right.kind && left.source == right.source && left.target == right.target &&
        left.note == right.note && left.enabled == right.enabled && left.regex == right.regex &&
        left.caseSensitive == right.caseSensitive

enum class RuleExchangeDuplicatePolicy { SKIP, OVERWRITE }

enum class RuleExchangeDisposition { ADDED, UPDATED, UNCHANGED, CONFLICTING }

enum class RuleExchangeConflictReason { INVALID_PATTERN, LIMIT, CONTENT_CONFLICT, DUPLICATE_SOURCE }

/** One preview row. Rule bodies are deliberately not copied into the preview result. */
data class RuleExchangeEntryPreview(
    val id: String,
    val kind: TextRuleKind,
    val disposition: RuleExchangeDisposition,
    val conflict: RuleExchangeConflictReason? = null,
    val matchedRuleId: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl))
        require((conflict != null) == (disposition == RuleExchangeDisposition.CONFLICTING))
        require(matchedRuleId == null || (matchedRuleId.isNotBlank() && matchedRuleId.length <= 128))
        require(matchedRuleId == null || disposition != RuleExchangeDisposition.ADDED)
    }
    override fun toString(): String =
        "RuleExchangeEntryPreview(id=$id, kind=$kind, disposition=$disposition, conflict=$conflict)"
}

/**
 * Immutable preview plus the policy that the selected duplicate policy would produce.
 * Conflicting entries are excluded from [policy]; callers must require [clean] (or otherwise
 * resolve them) before treating the preview as an applicable import.
 */
class RuleExchangePreview(
    entries: List<RuleExchangeEntryPreview>,
    val policy: ProjectTextPolicy,
    val duplicatePolicy: RuleExchangeDuplicatePolicy,
    val promptChanged: Boolean,
    val promptEnabledChanged: Boolean,
) {
    val entries: List<RuleExchangeEntryPreview> =
        java.util.Collections.unmodifiableList(ArrayList(entries))
    val added: List<RuleExchangeEntryPreview> =
        java.util.Collections.unmodifiableList(this.entries.filter { it.disposition == RuleExchangeDisposition.ADDED })
    val updated: List<RuleExchangeEntryPreview> =
        java.util.Collections.unmodifiableList(this.entries.filter { it.disposition == RuleExchangeDisposition.UPDATED })
    val unchanged: List<RuleExchangeEntryPreview> =
        java.util.Collections.unmodifiableList(this.entries.filter { it.disposition == RuleExchangeDisposition.UNCHANGED })
    val conflicting: List<RuleExchangeEntryPreview> =
        java.util.Collections.unmodifiableList(this.entries.filter { it.disposition == RuleExchangeDisposition.CONFLICTING })

    init {
        require(this.entries.size <= RuleExchange.MAX_RULES)
    }

    val clean: Boolean get() = conflicting.isEmpty()
    val changed: Boolean
        get() = added.isNotEmpty() || updated.isNotEmpty() || promptChanged || promptEnabledChanged

    override fun toString(): String =
        "RuleExchangePreview(added=${added.size}, updated=${updated.size}, unchanged=${unchanged.size}, " +
            "conflicting=${conflicting.size}, duplicatePolicy=$duplicatePolicy)"
}

/**
 * Strict version-1 native envelope codec.
 *
 * Unknown fields, wrong JSON types, duplicate JSON keys, excessive depth/size and duplicate rule
 * IDs are rejected. `id` may be omitted; a deterministic `rx1-` identity is derived from
 * kind/source/pattern flags so target, note and enable edits do not create a new rule identity.
 * Two rules without IDs that share those flags derive the same ID and are therefore rejected;
 * explicit stable IDs are required to represent a project's duplicate source identities. No LG
 * JSON/XLSX/CSV shape is accepted.
 */
object RuleExchangeCodec {
    const val FORMAT = "lnt-rule-exchange"
    const val VERSION = 1
    const val MAX_CHARS = 65_536

    private const val MAX_DEPTH = 8
    private val ROOT_KEYS = setOf("format", "version", "prompt", "prompt_enabled", "rules")
    private val RULE_KEYS = setOf("id", "kind", "source", "target", "note", "enabled", "regex", "case_sensitive")
    private val REQUIRED_RULE_KEYS = setOf("kind", "source")

    fun encode(exchange: RuleExchange): String {
        val text = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("prompt", exchange.prompt)
            put("prompt_enabled", exchange.promptEnabled)
            put("rules", JsonArray(exchange.rules.map { rule ->
                buildJsonObject {
                    put("id", rule.id)
                    put("kind", rule.kind.name)
                    put("source", rule.source)
                    put("target", rule.target)
                    put("note", rule.note)
                    put("enabled", rule.enabled)
                    put("regex", rule.regex)
                    put("case_sensitive", rule.caseSensitive)
                }
            }))
        }.toString()
        require(text.length <= MAX_CHARS)
        return text
    }

    fun encodePolicy(policy: ProjectTextPolicy): String = encode(RuleExchange.of(policy))

    fun decode(text: String): RuleExchange {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, MAX_DEPTH)).json
        require(root.keys == ROOT_KEYS)
        require(string(root, "format") == FORMAT)
        require(root.getValue("version") == JsonPrimitive(VERSION))
        val rulesElement = root.getValue("rules") as? JsonArray
            ?: throw IllegalArgumentException("Invalid rules")
        require(rulesElement.size <= RuleExchange.MAX_RULES)
        return RuleExchange(
            rules = rulesElement.map { decodeRule(objectOf(it)) },
            prompt = string(root, "prompt"),
            promptEnabled = boolean(root, "prompt_enabled"),
        )
    }

    private fun decodeRule(rule: JsonObject): ProjectTextRule {
        require(rule.keys.all { it in RULE_KEYS } && rule.keys.containsAll(REQUIRED_RULE_KEYS))
        val kind = try {
            TextRuleKind.valueOf(string(rule, "kind"))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid rule kind")
        }
        val source = string(rule, "source")
        val regex = optionalBoolean(rule, "regex") ?: false
        val caseSensitive = optionalBoolean(rule, "case_sensitive") ?: true
        val id = optionalString(rule, "id") ?: derivedId(kind, source, regex, caseSensitive)
        return ProjectTextRule(
            id = id,
            kind = kind,
            source = source,
            target = optionalString(rule, "target") ?: "",
            note = optionalString(rule, "note") ?: "",
            enabled = optionalBoolean(rule, "enabled") ?: true,
            regex = regex,
            caseSensitive = caseSensitive,
        )
    }

    /** Identity is derived from matching semantics only, never from editable output text. */
    private fun derivedId(kind: TextRuleKind, source: String, regex: Boolean, caseSensitive: Boolean): String {
        val canonical = buildJsonObject {
            put("version", 1)
            put("kind", kind.name)
            put("source", source)
            put("regex", regex)
            put("case_sensitive", caseSensitive)
        }.toString()
        return "rx1-" + TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(canonical)).substring(0, 24)
    }

    private fun string(value: JsonObject, key: String): String =
        optionalString(value, key) ?: throw IllegalArgumentException("Missing field")

    private fun optionalString(value: JsonObject, key: String): String? {
        val element = value[key] ?: return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException("Invalid field")
        require(primitive !is JsonNull && primitive.isString)
        return primitive.content
    }

    private fun boolean(value: JsonObject, key: String): Boolean =
        optionalBoolean(value, key) ?: throw IllegalArgumentException("Missing field")

    private fun optionalBoolean(value: JsonObject, key: String): Boolean? {
        val element = value[key] ?: return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException("Invalid field")
        require(!primitive.isString)
        return requireNotNull(primitive.booleanOrNull)
    }

    private fun objectOf(element: JsonElement): JsonObject =
        element as? JsonObject ?: throw IllegalArgumentException("Invalid rule")
}
