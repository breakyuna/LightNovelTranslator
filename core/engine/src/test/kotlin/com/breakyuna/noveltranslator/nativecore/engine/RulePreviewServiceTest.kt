package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.InvalidTextRule
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextRule
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchange
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeCodec
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeConflictReason
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeDisposition
import com.breakyuna.noveltranslator.nativecore.domain.RuleExchangeDuplicatePolicy
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import org.junit.Assert.*
import org.junit.Test

class RulePreviewServiceTest {
    private val service = RulePreviewService()

    private fun rule(
        id: String,
        source: String = "source",
        target: String = "target",
        kind: TextRuleKind = TextRuleKind.TERM,
        regex: Boolean = false,
    ) = ProjectTextRule(id, kind, source, target, "", true, regex, true)

    @Test
    fun invalidRegexIsReportedAsAConflictAndNeverReachesThePreviewedPolicy() {
        val current = ProjectTextPolicy()
        val exchange = RuleExchange(
            rules = listOf(
                rule("bad", "(", "", TextRuleKind.PROTECT, regex = true),
                rule("good", "term"),
            ),
            prompt = "prompt",
            promptEnabled = true,
        )
        val result = service.preview(current, exchange)
        assertEquals(2, result.entries.size)
        assertEquals(RuleExchangeDisposition.CONFLICTING, result.entries[0].disposition)
        assertEquals(RuleExchangeConflictReason.INVALID_PATTERN, result.entries[0].conflict)
        assertEquals(RuleExchangeDisposition.ADDED, result.entries[1].disposition)
        assertEquals(listOf("good"), result.preview.policy.rules.map { it.id })
        assertEquals("prompt", result.preview.policy.prompt)
        assertFalse(result.clean)
        assertTrue(result.programSize > 0)
        assertTrue(result.withinProgramBudget)
        assertTrue(result.withinEncodedBudget)
        assertTrue(current.rules.isEmpty())
    }

    @Test
    fun skipAndOverwriteAreDeterministicAndNeverMutateTheCommittedPolicy() {
        val base = ProjectTextPolicy(listOf(rule("r1", "term", "old")), "base", false)
        val incoming = RuleExchange(listOf(rule("r1", "term", "new")), "base", false)

        val skip = service.preview(base, incoming, RuleExchangeDuplicatePolicy.SKIP)
        assertEquals(RuleExchangeDisposition.CONFLICTING, skip.entries.single().disposition)
        assertEquals(RuleExchangeConflictReason.CONTENT_CONFLICT, skip.entries.single().conflict)
        assertEquals("old", skip.preview.policy.rules.single().target)

        val first = service.preview(base, incoming, RuleExchangeDuplicatePolicy.OVERWRITE)
        val second = service.preview(base, incoming, RuleExchangeDuplicatePolicy.OVERWRITE)
        assertEquals(RuleExchangeDisposition.UPDATED, first.entries.single().disposition)
        assertEquals("new", first.preview.policy.rules.single().target)
        assertEquals(first.entries, second.entries)
        assertEquals(first.programSize, second.programSize)
        assertEquals(
            RuleExchangeCodec.encode(RuleExchange.of(first.preview.policy)),
            RuleExchangeCodec.encode(RuleExchange.of(second.preview.policy)),
        )
        assertEquals("old", base.rules.single().target)
        assertEquals("base", base.prompt)
    }

    @Test
    fun duplicateSourceIdentitiesRemainConflictingEvenUnderOverwrite() {
        val base = ProjectTextPolicy(listOf(rule("r1", "term", "old")))
        val incoming = RuleExchange(listOf(rule("r2", "term", "new")))
        val result = service.preview(base, incoming, RuleExchangeDuplicatePolicy.OVERWRITE)
        assertEquals(RuleExchangeDisposition.CONFLICTING, result.entries.single().disposition)
        assertEquals(RuleExchangeConflictReason.DUPLICATE_SOURCE, result.entries.single().conflict)
        assertEquals(listOf("r1"), result.preview.policy.rules.map { it.id })
    }

    @Test
    fun oversizedAndInvalidTextRulesAreBoundedBeforeCompilation() {
        val base = ProjectTextPolicy()
        val exchange = RuleExchange(
            rules = listOf(ProjectTextRule("r", TextRuleKind.PROTECT, "(".repeat(33) + "x" + ")".repeat(33), regex = true)),
        )
        val result = service.preview(base, exchange)
        assertEquals(RuleExchangeDisposition.CONFLICTING, result.entries.single().disposition)
        assertEquals(RuleExchangeConflictReason.LIMIT, result.entries.single().conflict)
        assertFalse(result.clean)
    }
}
