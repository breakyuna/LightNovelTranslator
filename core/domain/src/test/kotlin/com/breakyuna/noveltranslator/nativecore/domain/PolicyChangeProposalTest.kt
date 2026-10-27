package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PolicyChangeProposalTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private fun proposal(before: ProjectTextPolicy, after: ProjectTextPolicy, run: RunId = RunId("r")) =
        PolicyChangeProposal(ApprovalRequestId("approval"), ref, ChatId("chat"), run, CommandId("command"), 3, before, after)

    @Test fun partialSelectionSeparatesRuleContentsOrderAndPrompt() {
        val a = ProjectTextRule("a", TextRuleKind.TERM, "a", "A")
        val b = ProjectTextRule("b", TextRuleKind.TERM, "b", "B")
        val c = ProjectTextRule("c", TextRuleKind.TERM, "c", "C")
        val before = ProjectTextPolicy(listOf(a, b), "old", true)
        val after = ProjectTextPolicy(listOf(c, a.copy(target = "new")), "new", false)
        val fixed = proposal(before, after)
        val partial = fixed.selectedPolicy(setOf("a"), false, false)
        assertEquals(listOf("a", "b"), partial.rules.map { it.id })
        assertEquals("new", partial.rules.first().target)
        assertEquals("B", partial.rules.last().target)
        assertEquals("old", partial.prompt)
        assertTrue(partial.promptEnabled)
        assertEquals(after.fingerprint(), fixed.selectedPolicy(setOf("a", "b", "c"), true, true).fingerprint())
        try { fixed.selectedPolicy(setOf("unknown"), false, false); fail("Accepted unknown rule") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun wholePolicyIdentityIncludesOrderFlagsPromptAndExecution() {
        val first = ProjectTextRule("a", TextRuleKind.TERM, "cat", "猫")
        val second = ProjectTextRule("b", TextRuleKind.PROTECT, "[0-9]+", regex = true)
        val before = ProjectTextPolicy(listOf(first, second), "old", true)
        val reordered = ProjectTextPolicy(listOf(second, first), "old", true)
        assertNotEquals(proposal(before, reordered).digest, proposal(before, before.changed(prompt = "new")).digest)
        assertNotEquals(proposal(before, reordered).digest, proposal(before, reordered, RunId("other")).digest)
        val intent = proposal(before, reordered).intent()
        assertEquals(3L, intent.expectedRevision)
        assertEquals(before.fingerprint(), intent.base)
        assertEquals(reordered.fingerprint(), intent.policy.fingerprint())
    }

    @Test fun summarySeparatesRuleEditsOrderAndPromptWithoutBodies() {
        val a = ProjectTextRule("a", TextRuleKind.TERM, "a", "A")
        val b = ProjectTextRule("b", TextRuleKind.PROTECT, "b")
        val c = ProjectTextRule("c", TextRuleKind.POST_REPLACE, "c", "C")
        val before = ProjectTextPolicy(listOf(a, b, c))
        val after = ProjectTextPolicy(listOf(b.copy(enabled = false), a, ProjectTextRule("d", TextRuleKind.TERM, "d", "D")), "prompt", true)
        val summary = proposal(before, after).summary(TextApprovalState.PENDING)
        assertEquals(1, summary.added); assertEquals(1, summary.updated); assertEquals(1, summary.deleted)
        assertTrue(summary.reordered); assertTrue(summary.promptChanged)
    }

    @Test(expected = IllegalArgumentException::class) fun unchangedPolicyCannotCreateAnApproval() {
        proposal(ProjectTextPolicy(), ProjectTextPolicy())
    }
}
