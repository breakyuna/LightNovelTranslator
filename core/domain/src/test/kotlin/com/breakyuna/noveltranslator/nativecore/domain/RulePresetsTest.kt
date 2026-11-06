package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class RulePresetsTest {
    private fun rule(
        id: String,
        source: String = "source",
        target: String = "target",
        kind: TextRuleKind = TextRuleKind.TERM,
        regex: Boolean = false,
    ) = ProjectTextRule(id, kind, source, target, "", true, regex, true)

    @Test
    fun presetRejectsInvalidRegexDuplicateRulesAndUnboundedMetadata() {
        try {
            RulePreset(id = "p", name = "n", rules = listOf(rule("r", "(", "", TextRuleKind.PROTECT, regex = true)))
            fail("Accepted invalid regex")
        } catch (_: InvalidTextRule) {
        }
        try {
            RulePreset(id = "p", name = "n", rules = listOf(rule("a", "same"), rule("b", "same")))
            fail("Accepted duplicate source identity")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RulePreset(id = "p", name = "n", rules = listOf(rule("a", "one"), rule("a", "two")))
            fail("Accepted duplicate rule id")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun replaceApplicationReturnsANewPolicyAndDiffWithoutMutatingTheProject() {
        val base = ProjectTextPolicy(
            listOf(rule("keep", "one", "old"), rule("drop", "two", "x", TextRuleKind.PROTECT)),
            "base",
            false,
        )
        val fingerprint = base.fingerprint()
        val preset = RulePreset(
            id = "preset",
            name = "Preset",
            description = "native",
            rules = listOf(rule("keep", "one", "new"), rule("add", "three", "y")),
            prompt = "preset-prompt",
            promptEnabled = true,
        )
        val application = preset.applyTo(base, RulePresetApplyMode.REPLACE)
        assertEquals(listOf("add"), application.diff.addedRuleIds)
        assertEquals(listOf("drop"), application.diff.removedRuleIds)
        assertEquals(listOf("keep"), application.diff.updatedRuleIds)
        assertTrue(application.diff.unchangedRuleIds.isEmpty())
        assertTrue(application.diff.rulesChanged)
        assertTrue(application.diff.promptChanged)
        assertTrue(application.diff.promptEnabledChanged)
        assertTrue(application.diff.changed)
        assertEquals(listOf("keep", "add"), application.policy.rules.map { it.id })
        assertEquals("new", application.policy.rules.first().target)
        assertEquals("preset-prompt", application.policy.prompt)
        assertTrue(application.policy.promptEnabled)
        assertEquals(fingerprint, base.fingerprint())
        assertEquals(2, base.rules.size)
        assertEquals("base", base.prompt)
        assertFalse(base.promptEnabled)
    }

    @Test
    fun mergeAppendsNewIdentitiesKeepsEquivalentRulesAndRejectsSourceOwnershipConflicts() {
        val base = ProjectTextPolicy(
            listOf(rule("keep", "one", "old"), rule("drop", "two", "x", TextRuleKind.PROTECT)),
            "base",
            false,
        )
        val preset = RulePreset(
            id = "merge",
            name = "Merge",
            rules = listOf(rule("keep", "one", "old"), rule("add", "three", "y")),
            prompt = "ignored",
            promptEnabled = true,
        )
        val application = preset.applyTo(base, RulePresetApplyMode.MERGE, includePrompt = false)
        assertEquals(listOf("add"), application.diff.addedRuleIds)
        assertTrue(application.diff.removedRuleIds.isEmpty())
        assertTrue(application.diff.updatedRuleIds.isEmpty())
        assertEquals(listOf("keep"), application.diff.unchangedRuleIds)
        assertFalse(application.diff.promptChanged)
        assertEquals(listOf("keep", "drop", "add"), application.policy.rules.map { it.id })
        assertEquals("base", application.policy.prompt)

        val conflicting = RulePreset(
            id = "conflict",
            name = "Conflict",
            rules = listOf(rule("other", "one", "different")),
        )
        try {
            conflicting.applyTo(base, RulePresetApplyMode.MERGE)
            fail("Merged a different stable identity onto an existing source")
        } catch (failure: RulePresetConflict) {
            assertEquals(RulePresetConflict.Reason.DUPLICATE_RULE, failure.reason)
        }
        assertEquals(2, base.rules.size)
    }

    @Test
    fun catalogRequiresUniqueIdentityAndCaseInsensitiveNames() {
        val first = RulePreset(id = "a", name = "Name")
        val second = RulePreset(id = "b", name = "name")
        assertEquals(first, RulePresetCatalog(listOf(first)).find("a"))
        try {
            RulePresetCatalog(listOf(first, second))
            fail("Accepted case-insensitive duplicate preset names")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RulePresetCatalog(listOf(first, RulePreset(id = "a", name = "Other")))
            fail("Accepted duplicate preset ids")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun presetCanBeProjectedFromANativeExchange() {
        val exchange = RuleExchange(listOf(rule("r", "one", "t")), "prompt", true)
        val preset = RulePreset.fromExchange("id", "Name", "description", exchange)
        assertEquals(listOf("r"), preset.rules.map { it.id })
        assertEquals("prompt", preset.prompt)
        assertTrue(preset.promptEnabled)
        val restored = preset.toExchange()
        assertEquals(exchange.rules.map { it.id }, restored.rules.map { it.id })
        assertEquals(exchange.prompt, restored.prompt)
    }
}
