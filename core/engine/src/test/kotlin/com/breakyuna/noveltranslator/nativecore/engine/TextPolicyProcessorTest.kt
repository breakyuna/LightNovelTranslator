package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class TextPolicyProcessorTest {
    private fun rule(id: String, kind: TextRuleKind, source: String, target: String = "", regex: Boolean = false,
        sensitive: Boolean = true, enabled: Boolean = true) = ProjectTextRule(id, kind, source, target, enabled = enabled,
            regex = regex, caseSensitive = sensitive)
    private fun rejects(reason: InvalidTextRule.Reason, action: () -> Unit) {
        try { action(); fail("Accepted invalid rule text") } catch (failure: InvalidTextRule) { assertEquals(reason, failure.reason) }
    }
    @Test fun literalReplacementsAreOrderedAndDoNotInterpretCaptureSyntax() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("a", TextRuleKind.PRE_REPLACE, "one", "two"),
            rule("b", TextRuleKind.PRE_REPLACE, "two", "three"), rule("c", TextRuleKind.POST_REPLACE, "three", "\$1\\1"))))
        val prepared = processor.prepare("one one")
        assertEquals("three three", prepared.text)
        assertEquals("\$1\\1 \$1\\1", processor.finish(prepared, prepared.text))
    }
    @Test fun regexNumericAndNamedBackslashCapturesUseOrdinalGroupsOnApi24() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("replace", TextRuleKind.PRE_REPLACE,
            "(?<first>a)(b)?", "\\g<first>-\\2-\\g<missing>-\\n", regex = true))))
        assertEquals("a-b--\n", processor.prepare("ab").text)
        assertEquals("a---\n", processor.prepare("a").text)
    }
    @Test fun protectionSurvivesBothReplacementStagesWithoutExposingControlText() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("protect", TextRuleKind.PROTECT, "<[^>]+>", regex = true),
            rule("pre", TextRuleKind.PRE_REPLACE, "Alice", "A"), rule("post", TextRuleKind.POST_REPLACE, "Alice", "B"))))
        val prepared = processor.prepare("Alice <Alice>!")
        assertFalse(prepared.text.contains("<Alice>"))
        assertEquals("A <Alice>!", processor.finish(prepared, prepared.text))
        assertEquals("B <Alice>!", processor.finish(prepared, prepared.text.replace("A ", "Alice ")))
    }
    @Test fun missingDuplicatedAndUnknownProtectionTokensRejectTheWholeResult() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("protect", TextRuleKind.PROTECT, "[name]"))))
        val prepared = processor.prepare("hello [name]")
        val token = prepared.text.substringAfter("hello ")
        rejects(InvalidTextRule.Reason.PROTECTION) { processor.finish(prepared, "hello") }
        rejects(InvalidTextRule.Reason.PROTECTION) { processor.finish(prepared, token + token) }
        rejects(InvalidTextRule.Reason.PROTECTION) { processor.finish(prepared, token.replace("_0]", "_8]")) }
        assertEquals("hello [name]", processor.finish(prepared, prepared.text))
    }
    @Test fun overlappingProtectionUsesEarliestStartThenRuleOrderAndKeepsTheWholeSpan() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("first", TextRuleKind.PROTECT, "abcd"),
            rule("second", TextRuleKind.PROTECT, "abc"), rule("overlap", TextRuleKind.PROTECT, "cdef"))))
        val prepared = processor.prepare("abcdef")
        assertTrue(prepared.text.endsWith("ef"))
        assertEquals("abcdef", processor.finish(prepared, prepared.text))
    }
    @Test fun termsMatchOriginalTextWithCasePolicyAndDisabledRulesHaveNoEffect() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("term", TextRuleKind.TERM, "ALICE", "艾莉丝", sensitive = false),
            rule("absent", TextRuleKind.TERM, "BOB", "鲍勃"), rule("disabled", TextRuleKind.PRE_REPLACE, "Alice", "gone", enabled = false))))
        val prepared = processor.prepare("Alice")
        assertEquals("Alice", prepared.text)
        assertEquals("艾莉丝", prepared.terms.single().target)
    }
    @Test fun nestedQuantifiersUseLinearMatchingAndOutputExpansionIsBounded() {
        val processor = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("slow", TextRuleKind.PRE_REPLACE, "(a+)+$", "b", regex = true))))
        assertEquals("a".repeat(200) + "!", processor.prepare("a".repeat(200) + "!").text)
        val expansion = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("grow", TextRuleKind.PRE_REPLACE, "x", "z".repeat(4096)))))
        rejects(InvalidTextRule.Reason.LIMIT) { expansion.prepare("x".repeat(20)) }
    }
    @Test fun cancellationChecksRunBeforeWorkAndAnEmptyPreparedSourceIsNotDispatched() {
        var cancelled = false
        val processor = TextPolicyProcessor(ProjectTextPolicy()) { if (cancelled) throw java.util.concurrent.CancellationException() }
        val prepared = processor.prepare("source"); cancelled = true
        try { processor.finish(prepared, "translated"); fail("Ignored cancellation") }
        catch (_: java.util.concurrent.CancellationException) { }
        val empty = TextPolicyProcessor(ProjectTextPolicy(listOf(rule("clear", TextRuleKind.PRE_REPLACE, "source", ""))))
        rejects(InvalidTextRule.Reason.PROTECTION) { empty.prepare("source") }
    }
}
