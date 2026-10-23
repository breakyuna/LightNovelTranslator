package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class TextProofreadingInspectorTest {
    private fun policy(vararg rules: ProjectTextRule) = ProjectTextPolicy(rules.toList())
    private fun inspect(source: String, target: String, policy: ProjectTextPolicy = ProjectTextPolicy(),
        from: ProofreadingLanguage = ProofreadingLanguage.EN, to: ProofreadingLanguage = ProofreadingLanguage.ZH,
        status: TextItemStatus = TextItemStatus.PROCESSED) = TextProofreadingInspector(policy)
        .inspect(source, target, status, TextProofreadingOptions(from, to))

    @Test fun unprocessedExcludedAndBlankTranslationsAreExplicitlyNotEvaluated() {
        for (status in listOf(TextItemStatus.NONE, TextItemStatus.EXCLUDED)) {
            val result = inspect("hello", "hello", status = status)
            assertFalse(result.evaluated); assertTrue(result.codes.isEmpty())
        }
        assertFalse(inspect("source", " \n\t").evaluated)
        assertTrue(inspect("", "译文").evaluated)
    }
    @Test fun originalLineCountsNormalizeCrLfButRetainTrailingEmptyLines() {
        assertFalse(TextWarningCode.LINE_COUNT_MISMATCH in inspect("a\r\nb\rc", "甲\n乙\n丙").codes)
        assertTrue(TextWarningCode.LINE_COUNT_MISMATCH in inspect("a\n", "甲").codes)
    }
    @Test fun preservedValuesAreComparedByContentOrderAndOriginalLine() {
        val rules = policy(ProjectTextRule("protect", TextRuleKind.PROTECT, "<[^>]+>", regex = true))
        assertFalse(TextWarningCode.TEXT_PRESERVE in inspect("<a> hello\n<b>", "<a> 你好\n<b>", rules).codes)
        for (target in listOf("<b> 你好\n<a>", "你好\n<a><b>", "<a> 你好\n<c>", "<a><a> 你好\n<b>"))
            assertTrue(TextWarningCode.TEXT_PRESERVE in inspect("<a> hello\n<b>", target, rules).codes)
    }
    @Test fun protectedTextIsNotMistakenForForeignResidueOrPunctuation() {
        val rules = policy(ProjectTextRule("protect", TextRuleKind.PROTECT, "<[^>]+>", regex = true))
        val result = inspect("<ABC(> hello", "<ABC(> 你好", rules)
        assertFalse(TextWarningCode.FOREIGN_CHAR_RESIDUE in result.codes)
        assertFalse(TextWarningCode.PUNCTUATION_MISMATCH in result.codes)
    }
    @Test fun sourcePreparationUsesPreRulesWhileTargetIsNeverReplaced() {
        val rules = policy(ProjectTextRule("pre", TextRuleKind.PRE_REPLACE, "hello", "你好"),
            ProjectTextRule("post", TextRuleKind.POST_REPLACE, "译文", "(译文)"))
        assertTrue(TextWarningCode.SIMILARITY in inspect("hello", "你好", rules).codes)
        assertFalse(TextWarningCode.PUNCTUATION_MISMATCH in inspect("hello", "译文", rules).codes)
        val cleared = policy(ProjectTextRule("clear", TextRuleKind.PRE_REPLACE, "hello", ""))
        assertTrue(inspect("hello", "译文", cleared).evaluated)
    }
    @Test fun glossaryMatchesOriginalSourceAndChecksAllEnabledNonemptyTargets() {
        val rules = policy(ProjectTextRule("alice", TextRuleKind.TERM, "ALICE", "艾莉丝", caseSensitive = false),
            ProjectTextRule("disabled", TextRuleKind.TERM, "Alice", "missing", enabled = false),
            ProjectTextRule("note-only", TextRuleKind.TERM, "Alice"),
            ProjectTextRule("pre", TextRuleKind.PRE_REPLACE, "Alice", "person"))
        val result = inspect("Alice", "她", rules)
        assertEquals(listOf("alice"), result.missingTermRuleIds)
        assertTrue(TextWarningCode.GLOSSARY in result.codes)
        assertFalse(TextWarningCode.GLOSSARY in inspect("Alice", "艾莉丝", rules).codes)
        assertEquals(rules.fingerprint(), result.policyFingerprint)
    }
    @Test fun punctuationUsesOrderedEquivalentGroupsAndBracketDirections() {
        assertFalse(TextWarningCode.PUNCTUATION_MISMATCH in inspect("(\"word\")", "（「词」）").codes)
        assertTrue(TextWarningCode.PUNCTUATION_MISMATCH in inspect("([word])", "［（词）］").codes)
        assertTrue(TextWarningCode.PUNCTUATION_MISMATCH in inspect("word", "（词）").codes)
    }
    @Test fun residueRespectsTargetScriptAndExemptsSingleLatinAndShortAcronyms() {
        assertFalse(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "甲 A CPU ・").codes)
        assertTrue(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "甲 hello").codes)
        assertTrue(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "甲あ").codes)
        assertTrue(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "甲한").codes)
        assertFalse(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "翻訳かな", to = ProofreadingLanguage.JA).codes)
        assertFalse(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "Übersetzung", to = ProofreadingLanguage.DE).codes)
        assertTrue(TextWarningCode.FOREIGN_CHAR_RESIDUE in inspect("hello", "翻訳", to = ProofreadingLanguage.EN).codes)
    }
    @Test fun similarityUsesContainmentAndJaccardWithSharedHanException() {
        assertTrue(TextWarningCode.SIMILARITY in inspect("hello world", "hello").codes)
        assertTrue(TextWarningCode.SIMILARITY in inspect("abcdef", "fedcba").codes)
        assertFalse(TextWarningCode.SIMILARITY in inspect("世界", "世界", from = ProofreadingLanguage.JA).codes)
        assertTrue(TextWarningCode.SIMILARITY in inspect("世界あ", "世界あ", from = ProofreadingLanguage.JA).codes)
    }
    @Test fun cancellationLimitsAndInvalidRulesNeverBecomeClearResults() {
        var ticks = 0
        try {
            TextProofreadingInspector(ProjectTextPolicy()) { if (++ticks > 4) throw java.util.concurrent.CancellationException() }
                .inspect("a".repeat(4096), "b".repeat(4096), TextItemStatus.PROCESSED)
            fail("Ignored cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
        try { inspect("x".repeat(65_537), "译文"); fail("Accepted oversized source") }
        catch (_: IllegalArgumentException) { }
        try { inspect("source", "译文", policy(ProjectTextRule("broken", TextRuleKind.PROTECT, "(", regex = true))); fail("Ignored rule") }
        catch (failure: InvalidTextRule) { assertEquals("broken", failure.ruleId) }
    }
}
