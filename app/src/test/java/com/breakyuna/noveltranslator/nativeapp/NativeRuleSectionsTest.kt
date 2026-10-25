package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicyCodec
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextRule
import com.breakyuna.noveltranslator.nativecore.domain.TextRuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure section/filter/sample contracts; no storage, model or project scan is involved. */
class NativeRuleSectionsTest {
    @Test fun filteringMatchesSourceTargetAndNoteForOnlyTheSelectedKind() {
        val rules = listOf(
            ProjectTextRule("t1", TextRuleKind.TERM, "Alice", "アリス", note = "Main cast"),
            ProjectTextRule("t2", TextRuleKind.TERM, "Bob", "ボブ"),
            ProjectTextRule("p1", TextRuleKind.PROTECT, "Alice", "", note = "Alice protection"),
        )
        assertEquals(listOf("t1", "t2"),
            filterPolicyRules(rules, NativeRuleSection.GLOSSARY, "").map { it.id })
        assertEquals(listOf("t1"),
            filterPolicyRules(rules, NativeRuleSection.GLOSSARY, "main").map { it.id })
        assertEquals(listOf("t2"),
            filterPolicyRules(rules, NativeRuleSection.GLOSSARY, "ボ").map { it.id })
        assertEquals(listOf("t1"),
            filterPolicyRules(rules, NativeRuleSection.GLOSSARY, "ALICE").map { it.id })
        assertEquals(listOf("p1"),
            filterPolicyRules(rules, NativeRuleSection.TEXT_PRESERVE, "alice").map { it.id })
        assertTrue(filterPolicyRules(rules, NativeRuleSection.PROMPTS, "alice").isEmpty())
    }

    @Test fun batchEnableAndDisableChangeOnlySelectedIdentitiesAndKeepOrder() {
        val rules = listOf(
            ProjectTextRule("a", TextRuleKind.TERM, "A", "甲"),
            ProjectTextRule("b", TextRuleKind.TERM, "B", "乙", enabled = false),
            ProjectTextRule("c", TextRuleKind.TERM, "C", "丙"),
        )
        val disabled = setRulesEnabled(rules, setOf("a", "c"), false)
        assertEquals(listOf("a", "b", "c"), disabled.map { it.id })
        assertFalse(disabled.first { it.id == "a" }.enabled)
        assertFalse(disabled.first { it.id == "b" }.enabled)
        assertFalse(disabled.first { it.id == "c" }.enabled)
        val enabled = setRulesEnabled(disabled, setOf("a", "c"), true)
        assertTrue(enabled.first { it.id == "a" }.enabled)
        assertFalse(enabled.first { it.id == "b" }.enabled)
        assertTrue(enabled.first { it.id == "c" }.enabled)
        assertEquals(listOf("a", "b", "c"), enabled.map { it.id })
    }

    @Test fun boundedTextEscapesControlsAndNeverExceedsTheDisplayLimit() {
        val bounded = boundRuleText("a\nb\tc\\d\u0001", 64)
        assertTrue(bounded.text.length <= 64)
        assertTrue(bounded.text.contains("\\n"))
        assertTrue(bounded.text.contains("\\t"))
        assertTrue(bounded.text.contains("\\\\"))
        assertTrue(bounded.text.contains("\\u0001"))
        assertFalse(bounded.text.contains('\n'))
        val truncated = boundRuleText("abcdef", 4)
        assertTrue(truncated.truncated)
        assertEquals(4, truncated.text.length)
        assertTrue(truncated.text.endsWith("…"))
        val exact = boundRuleText("abc", 3)
        assertFalse(exact.truncated)
        assertEquals("abc", exact.text)
        assertThrows(IllegalArgumentException::class.java) { boundRuleText("x", 0); Unit }
    }

    @Test fun protectionSampleShowsMatchesAndIdentifiesAnInvalidRule() {
        val policy = ProjectTextPolicy(listOf(
            ProjectTextRule("protect", TextRuleKind.PROTECT, "\\[[0-9]+\\]", regex = true),
            ProjectTextRule("bad", TextRuleKind.PROTECT, "(", regex = true),
            ProjectTextRule("disabled", TextRuleKind.PROTECT, "off", enabled = false),
        ))
        val outcome = previewRuleSample(policy, NativeRuleSection.TEXT_PRESERVE, "[42] off")
        assertEquals(listOf("protect", "disabled"), outcome.matches.map { it.ruleId })
        assertEquals(1, outcome.matches.first { it.ruleId == "protect" }.count)
        assertEquals(listOf("[42]"), outcome.matches.first { it.ruleId == "protect" }.excerpts)
        assertFalse(outcome.matches.first { it.ruleId == "disabled" }.enabled)
        assertEquals(listOf("bad"), outcome.errors)
        assertFalse(outcome.failed)
    }

    @Test fun replacementSampleUsesBackreferencesNewlinesAndTheEmptyTarget() {
        val policy = ProjectTextPolicy(listOf(
            ProjectTextRule("swap", TextRuleKind.PRE_REPLACE, "(\\w+)-(\\w+)", "\\2 \\1", regex = true),
            ProjectTextRule("literal", TextRuleKind.PRE_REPLACE, "cat", "猫"),
            ProjectTextRule("newline", TextRuleKind.PRE_REPLACE, "NL", "\\n", regex = true),
            ProjectTextRule("empty", TextRuleKind.PRE_REPLACE, "drop", ""),
        ))
        val outcome = previewRuleSample(policy, NativeRuleSection.PRE_REPLACE, "cat a-b NL drop")
        val result = requireNotNull(outcome.result)
        assertEquals("猫 b a \\n ", result.text)
        assertFalse(outcome.failed)
    }

    @Test fun postReplacementUsesTheEngineWithoutATranslationRequest() {
        val policy = ProjectTextPolicy(listOf(
            ProjectTextRule("post", TextRuleKind.POST_REPLACE, "cat", "猫"),
        ))
        val outcome = previewRuleSample(policy, NativeRuleSection.POST_REPLACE, "cat")
        assertEquals("猫", outcome.result!!.text)
        assertFalse(outcome.failed)
    }

    @Test fun replacementTargetsRoundTripEmptyValuesNewlinesAndBackreferences() {
        val target = "\\1\n\\g<name>"
        val policy = ProjectTextPolicy(listOf(
            ProjectTextRule("named", TextRuleKind.PRE_REPLACE, "(?<name>a)", target, regex = true),
            ProjectTextRule("empty", TextRuleKind.POST_REPLACE, "remove", ""),
        ))
        val decoded = ProjectTextPolicyCodec.decode(ProjectTextPolicyCodec.encode(policy))
        assertEquals(target, decoded.rules.first { it.id == "named" }.target)
        assertEquals("", decoded.rules.first { it.id == "empty" }.target)
    }

    @Test fun sampleSizeIsBoundedBeforeAnyMatchingWork() {
        val policy = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "a", "甲")))
        assertThrows(IllegalArgumentException::class.java) {
            previewRuleSample(policy, NativeRuleSection.GLOSSARY, "a".repeat(NATIVE_RULE_SAMPLE_MAX_CHARS + 1))
            Unit
        }
    }
}
