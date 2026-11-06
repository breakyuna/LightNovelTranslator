package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.InvalidTextRule
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicyCodec
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchange
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeConflictReason
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeDuplicatePolicy
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeEntryPreview
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangePreview
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleValidation

/**
 * Pure, deterministic preview for an imported native [RuleExchange] against a committed policy.
 *
 * Regex validation uses the same RE2/J implementation as the runtime (`TextRuleValidation`), so a
 * pattern that cannot execute is reported as a conflicting entry instead of reaching the
 * previewed policy. There is no file, database, network or log access, and the current policy is
 * never mutated. A clean preview still goes through the normal save-time policy validation; this
 * service only adds per-rule attribution.
 */
class RulePreviewService {
    fun preview(
        current: ProjectTextPolicy,
        exchange: RuleExchange,
        duplicatePolicy: RuleExchangeDuplicatePolicy = RuleExchangeDuplicatePolicy.SKIP,
        includePrompt: Boolean = true,
        checkActive: () -> Unit = {},
    ): RulePreviewResult {
        checkActive()
        val preview = exchange.preview(
            current = current,
            duplicatePolicy = duplicatePolicy,
            includePrompt = includePrompt,
        ) { rule ->
            checkActive()
            try {
                TextRuleValidation.compile(rule)
                null
            } catch (failure: InvalidTextRule) {
                if (failure.reason == InvalidTextRule.Reason.LIMIT) RuleExchangeConflictReason.LIMIT
                else RuleExchangeConflictReason.INVALID_PATTERN
            }
        }
        var programSize = 0
        var withinProgramBudget = true
        for (rule in preview.policy.rules) {
            checkActive()
            if (!rule.enabled) continue
            val compiled = try {
                TextRuleValidation.compile(rule)
            } catch (_: InvalidTextRule) {
                // A committed policy should already be valid; never hide a bad existing rule.
                withinProgramBudget = false
                continue
            }
            programSize += compiled.pattern.programSize()
            if (programSize > MAX_PROGRAM_SIZE) withinProgramBudget = false
        }
        val withinEncodedBudget = try {
            ProjectTextPolicyCodec.encode(preview.policy)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        return RulePreviewResult(preview, programSize, withinProgramBudget, withinEncodedBudget)
    }

    companion object {
        /** Mirrors the shared enabled-rule program budget used by TextRuleValidation. */
        const val MAX_PROGRAM_SIZE = 65_536
    }
}

class RulePreviewResult(
    val preview: RuleExchangePreview,
    val programSize: Int,
    val withinProgramBudget: Boolean,
    val withinEncodedBudget: Boolean,
) {
    init {
        require(programSize >= 0)
    }

    val entries: List<RuleExchangeEntryPreview> get() = preview.entries
    val added: List<RuleExchangeEntryPreview> get() = preview.added
    val updated: List<RuleExchangeEntryPreview> get() = preview.updated
    val unchanged: List<RuleExchangeEntryPreview> get() = preview.unchanged
    val conflicting: List<RuleExchangeEntryPreview> get() = preview.conflicting

    /** True when no conflicts remain and both the policy size and program budgets hold. */
    val clean: Boolean get() = preview.clean && withinProgramBudget && withinEncodedBudget

    override fun toString(): String =
        "RulePreviewResult(added=${added.size}, updated=${updated.size}, " +
            "unchanged=${unchanged.size}, conflicting=${conflicting.size}, " +
            "programSize=$programSize, withinProgramBudget=$withinProgramBudget, " +
            "withinEncodedBudget=$withinEncodedBudget)"
}
