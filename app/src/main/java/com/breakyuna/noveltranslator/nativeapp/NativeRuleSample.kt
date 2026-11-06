package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidTextRule
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicy
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import com.breakyuna.noveltranslator.nativecore.engine.RuleMatchExplainer
import com.breakyuna.noveltranslator.nativecore.engine.TextPolicyProcessor
import kotlinx.coroutines.CancellationException

/** Local UI sample limit. The same bytes are never sent to a model or written to logs. */
internal const val NATIVE_RULE_SAMPLE_MAX_CHARS = 4_096

/** Maximum characters rendered from a matched excerpt or a processed result. */
internal const val NATIVE_RULE_PREVIEW_MAX_CHARS = 240

private const val SAMPLE_MATCH_LIMIT = 8
private const val SAMPLE_EXCERPT_LIMIT = 80
private const val SAMPLE_EXCERPT_COUNT = 4

internal data class BoundedRuleText(val text: String, val truncated: Boolean) {
    override fun toString(): String = "BoundedRuleText(length=${text.length}, truncated=$truncated)"
}

internal data class NativeRuleSampleMatch(
    val ruleId: String,
    val enabled: Boolean,
    val count: Int,
    val truncated: Boolean,
    val excerpts: List<String>,
) {
    override fun toString(): String = "NativeRuleSampleMatch(rule=$ruleId, count=$count)"
}

internal data class NativeRuleSampleOutcome(
    val matches: List<NativeRuleSampleMatch>,
    val errors: List<String>,
    val result: BoundedRuleText?,
    val failed: Boolean,
) {
    override fun toString(): String =
        "NativeRuleSampleOutcome(matches=${matches.size}, errors=${errors.size}, failed=$failed)"
}

/**
 * Escapes control characters, bounds the visible text and marks truncation. Newlines, tabs and
 * carriage returns are shown as text so a sample editor cannot silently hide line structure.
 */
internal fun boundRuleText(value: String, limit: Int = NATIVE_RULE_PREVIEW_MAX_CHARS): BoundedRuleText {
    require(limit in 1..NATIVE_RULE_SAMPLE_MAX_CHARS * 4)
    val output = StringBuilder(minOf(limit, 256))
    var index = 0
    var truncated = false
    while (index < value.length) {
        val token = when (val character = value[index]) {
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (character.isISOControl()) {
                "\\u" + character.code.toString(16).padStart(4, '0')
            } else {
                character.toString()
            }
        }
        if (output.length + token.length > limit) {
            truncated = true
            break
        }
        output.append(token)
        index++
    }
    if (index < value.length) truncated = true
    if (truncated) {
        if (output.length >= limit) output.setLength(limit - 1)
        output.append('…')
    }
    return BoundedRuleText(output.toString(), truncated)
}

/**
 * Runs the shared RE2/J matcher and, for pre/post replacement sections, the existing
 * [TextPolicyProcessor] over a user supplied local sample. Protection matches are explained only;
 * replacement sections use a policy containing enabled rules of that one section so the shown
 * result is attributable to the same rules persisted in the draft.
 *
 * The sample is bounded by [NATIVE_RULE_SAMPLE_MAX_CHARS] before this function is called. No rule
 * exception text is retained; failures expose only the stable rule identity.
 */
internal fun previewRuleSample(
    policy: ProjectTextPolicy,
    section: NativeRuleSection,
    sample: String,
    checkActive: () -> Unit = {},
): NativeRuleSampleOutcome {
    val kind = requireNotNull(section.kind) { "Prompt section has no rule sample" }
    require(sample.length <= NATIVE_RULE_SAMPLE_MAX_CHARS)
    checkActive()
    val rules = policy.rules.filter { it.kind == kind }
    val matches = mutableListOf<NativeRuleSampleMatch>()
    val errors = mutableListOf<String>()
    val explainer = RuleMatchExplainer()
    for (rule in rules) {
        checkActive()
        try {
            val explanation = explainer.explain(rule, sample, SAMPLE_MATCH_LIMIT, checkActive)
            if (explanation.matchCount > 0) matches.add(NativeRuleSampleMatch(rule.id, rule.enabled,
                explanation.matchCount, explanation.truncated || explanation.matchCount > SAMPLE_EXCERPT_COUNT,
                explanation.spans.take(SAMPLE_EXCERPT_COUNT).map {
                    boundRuleText(sample.substring(it.start, it.end), SAMPLE_EXCERPT_LIMIT).text
                }))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidTextRule) {
            errors.add(rule.id)
        } catch (_: Exception) {
            errors.add(rule.id)
        }
    }
    val enabled = rules.filter { it.enabled }
    var result: BoundedRuleText? = null
    var failed = false
    if (kind != TextRuleKind.PROTECT && enabled.isNotEmpty()) {
        try {
            val processor = TextPolicyProcessor(ProjectTextPolicy(enabled), checkActive)
            val prepared = processor.prepare(sample)
            val output = if (kind == TextRuleKind.PRE_REPLACE) prepared.text
                else processor.finish(prepared, sample)
            result = boundRuleText(output)
        } catch (error: InvalidTextRule) {
            failed = true
            error.ruleId?.let { if (it !in errors) errors.add(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    checkActive()
    return NativeRuleSampleOutcome(matches, errors, result, failed)
}
