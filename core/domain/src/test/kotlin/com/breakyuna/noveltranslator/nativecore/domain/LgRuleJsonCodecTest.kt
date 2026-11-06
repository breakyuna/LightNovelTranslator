package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class LgRuleJsonCodecTest {
    @Test fun flatRulesRoundTripWithoutLosingWhitespaceOrFlags() {
        val original = ProjectTextPolicy(listOf(ProjectTextRule("r", TextRuleKind.PRE_REPLACE,
            " a ", " b\n", "note", regex = true, caseSensitive = true)))
        val restored = LgRuleJsonCodec.decode(LgRuleJsonCodec.encode(original, TextRuleKind.PRE_REPLACE),
            TextRuleKind.PRE_REPLACE, original)
        assertEquals(original.rules, restored.rules)
    }

    @Test fun existingIdentityAndDisabledStateSurviveExplicitOverwrite() {
        val old = ProjectTextRule("user-id", TextRuleKind.TERM, "Alice", "old", enabled = false, caseSensitive = false)
        val current = ProjectTextPolicy(listOf(old), "keep", true)
        val decoded = LgRuleJsonCodec.decode("""[{"src":"Alice","dst":"new"}]""", TextRuleKind.TERM, current)
        assertEquals(1, decoded.preview(current, includePrompt = false).conflicting.size)
        val applied = decoded.preview(current, RuleExchangeDuplicatePolicy.OVERWRITE, includePrompt = false)
        assertTrue(applied.clean)
        assertEquals(old.copy(target = "new"), applied.policy.rules.single())
        assertEquals("keep", applied.policy.prompt)
        assertTrue(applied.policy.promptEnabled)
    }

    @Test fun dictionaryAndActorNamesUseExplicitKindAndStableIdentities() {
        val dict = LgRuleJsonCodec.decode("""{"a":"b","c":null}""", TextRuleKind.POST_REPLACE)
        assertEquals(listOf("b", ""), dict.rules.map { it.target })
        val actors = """[null,{"id":1,"name":"Alice","nickname":"Al","ignored":[]}]"""
        val first = LgRuleJsonCodec.decode(actors, TextRuleKind.TERM)
        assertEquals(listOf("\\n[1]", "\\N[1]", "\\nn[1]", "\\NN[1]"), first.rules.map { it.source })
        assertEquals(first.rules, LgRuleJsonCodec.decode(actors, TextRuleKind.TERM).rules)
    }

    @Test fun malformedTypesDuplicateKeysAndUnrepresentableRulesAreRejected() {
        for (text in listOf("""[{"src":"a","regex":"false"}]""", """{"a":"b","a":"c"}""",
            """[{"src":"a"},{"src":"a"}]""", """[null,{"id":1.5,"name":"a"}]""",
            """{"a":{}}""", """[],"extra":0""", """[true]""")) {
            try { LgRuleJsonCodec.decode(text, TextRuleKind.TERM); fail("Accepted invalid document") }
            catch (_: IllegalArgumentException) { }
        }
        try {
            LgRuleJsonCodec.encode(ProjectTextPolicy(listOf(ProjectTextRule("r", TextRuleKind.TERM, "a", enabled = false))), TextRuleKind.TERM)
            fail("Export silently enabled disabled rule")
        } catch (_: IllegalArgumentException) { }
        val tooMany = JsonArrayText(257)
        try { LgRuleJsonCodec.decode(tooMany, TextRuleKind.TERM); fail("Accepted excessive rows") }
        catch (_: IllegalArgumentException) { }
    }

    private fun JsonArrayText(count: Int) = (1..count).joinToString(prefix = "[", postfix = "]") { "{\"src\":\"s$it\"}" }

    @Test fun externalRowsCannotChooseBetweenAmbiguousExistingIdentities() {
        val original = ProjectTextPolicy(listOf(ProjectTextRule("a", TextRuleKind.TERM, "source", caseSensitive = false),
            ProjectTextRule("b", TextRuleKind.TERM, "source", caseSensitive = false)))
        try {
            LgRuleJsonCodec.decode("""[{"src":"source"}]""", TextRuleKind.TERM, original)
            fail("Selected one ambiguous existing rule")
        } catch (_: IllegalArgumentException) { }
    }
}
