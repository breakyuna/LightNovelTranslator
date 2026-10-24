package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class RulePresetCodecTest {
    @Test fun nativeEnvelopeRoundTripsIdentityRulesAndPromptWithoutClaimingLgCompatibility() {
        val preset = RulePreset("id/with/slashes", "My preset", "description",
            listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝")), "Preserve names", true)
        val text = RulePresetCodec.encode(preset)
        val restored = RulePresetCodec.decode(text)
        assertTrue(text.contains("lnt-rule-preset"))
        assertEquals(preset.id, restored.id)
        assertEquals(preset.name, restored.name)
        assertEquals(preset.description, restored.description)
        assertEquals(preset.rules, restored.rules)
        assertEquals(preset.prompt, restored.prompt)
        assertEquals(preset.promptEnabled, restored.promptEnabled)
        assertThrows(IllegalArgumentException::class.java) { RulePresetCodec.decode(text.replace("\"version\":1", "\"version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { RulePresetCodec.decode(text.dropLast(1) + ",\"unknown\":true}") }
    }
}
