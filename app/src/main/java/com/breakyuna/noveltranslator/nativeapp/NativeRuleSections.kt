package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextRule
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import java.util.Locale

/**
 * The five rule/prompt destinations reachable from More and from the rules page itself.
 *
 * The page keeps these as one routed page so an unsaved policy draft survives every section
 * switch. Each non-prompt section is backed by exactly one native [TextRuleKind]; [PROMPTS]
 * edits the single project prompt body (there is no separate prompt identity in the domain).
 */
enum class NativeRuleSection(val kind: TextRuleKind?) {
    GLOSSARY(TextRuleKind.TERM),
    TEXT_PRESERVE(TextRuleKind.PROTECT),
    PRE_REPLACE(TextRuleKind.PRE_REPLACE),
    POST_REPLACE(TextRuleKind.POST_REPLACE),
    PROMPTS(null);

    /** ASCII-only identity for test tags and file names. */
    val slug: String get() = name.lowercase(Locale.ROOT)
}

/** Short navigation label. Existing native policy labels are reused where they are exact. */
internal fun NativeRuleSection.label(): Int = when (this) {
    NativeRuleSection.GLOSSARY -> R.string.native_policy_terms
    NativeRuleSection.TEXT_PRESERVE -> R.string.native_policy_protect
    NativeRuleSection.PRE_REPLACE -> R.string.native_policy_pre
    NativeRuleSection.POST_REPLACE -> R.string.native_policy_post
    NativeRuleSection.PROMPTS -> R.string.native_rules_prompts
}

/** Full page title. It deliberately differs from the short navigation label. */
internal fun NativeRuleSection.title(): Int = when (this) {
    NativeRuleSection.GLOSSARY -> R.string.native_rules_title_glossary
    NativeRuleSection.TEXT_PRESERVE -> R.string.native_rules_title_preserve
    NativeRuleSection.PRE_REPLACE -> R.string.native_rules_title_pre
    NativeRuleSection.POST_REPLACE -> R.string.native_rules_title_post
    NativeRuleSection.PROMPTS -> R.string.native_rules_title_prompts
}

internal fun TextRuleKind.section(): NativeRuleSection = when (this) {
    TextRuleKind.TERM -> NativeRuleSection.GLOSSARY
    TextRuleKind.PROTECT -> NativeRuleSection.TEXT_PRESERVE
    TextRuleKind.PRE_REPLACE -> NativeRuleSection.PRE_REPLACE
    TextRuleKind.POST_REPLACE -> NativeRuleSection.POST_REPLACE
}

/**
 * Glossary rules have no persisted name/body scope field in the native domain. All term rules
 * therefore apply to body text in the translation pipeline; this UI must not invent a second
 * storage model or claim a name-only scope. See the glossary note rendered by the page.
 *
 * The visible order is the project rule order filtered by kind. Filtering is local and bounded
 * by the policy limit (256 rules), so it is safe to evaluate while composing the list.
 */
internal fun filterPolicyRules(
    rules: List<ProjectTextRule>,
    section: NativeRuleSection,
    query: String,
): List<ProjectTextRule> {
    val kind = section.kind ?: return emptyList()
    val needle = query.trim()
    return rules.filter { rule ->
        rule.kind == kind && (needle.isEmpty() || rule.source.contains(needle, ignoreCase = true) ||
            rule.target.contains(needle, ignoreCase = true) || rule.note.contains(needle, ignoreCase = true))
    }
}

/** Batch enable/disable keeps every rule and its order; only the selected identities change. */
internal fun setRulesEnabled(
    rules: List<ProjectTextRule>,
    ids: Set<String>,
    enabled: Boolean,
): List<ProjectTextRule> = rules.map { rule ->
    if (rule.id in ids) rule.copy(enabled = enabled) else rule
}
