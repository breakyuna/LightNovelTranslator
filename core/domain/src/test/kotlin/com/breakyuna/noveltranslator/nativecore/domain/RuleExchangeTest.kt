package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class RuleExchangeTest {
    private fun rule(
        id: String,
        source: String = "source",
        target: String = "target",
        kind: TextRuleKind = TextRuleKind.TERM,
        regex: Boolean = false,
        caseSensitive: Boolean = true,
    ) = ProjectTextRule(id, kind, source, target, "", true, regex, caseSensitive)

    @Test
    fun roundTripKeepsIdentityOrderFlagsAndPromptWithoutTextInToString() {
        val exchange = RuleExchange(
            rules = listOf(
                rule("term", "Alice", "アリス"),
                rule("protect", "<[^>]+>", "", TextRuleKind.PROTECT, regex = true, caseSensitive = false),
                rule("pre", "foo", "bar", TextRuleKind.PRE_REPLACE),
            ),
            prompt = "prompt-body",
            promptEnabled = true,
        )
        val restored = RuleExchangeCodec.decode(RuleExchangeCodec.encode(exchange))
        assertEquals(listOf("term", "protect", "pre"), restored.rules.map { it.id })
        assertEquals("prompt-body", restored.prompt)
        assertTrue(restored.promptEnabled)
        assertEquals(TextRuleKind.PROTECT, restored.rules[1].kind)
        assertFalse(restored.rules[1].caseSensitive)
        assertTrue(restored.rules[1].regex)
        assertFalse(exchange.toString().contains("prompt-body"))
        assertFalse(exchange.toString().contains("Alice"))
    }

    @Test
    fun decodeRejectsDuplicateKeysUnknownFieldsWrongTypesDepthAndSize() {
        val encoded = RuleExchangeCodec.encode(RuleExchange(listOf(rule("r"))))
        val duplicate = encoded.replaceFirst("\"version\":1", "\"version\":1,\"version\":1")
        try {
            RuleExchangeCodec.decode(duplicate)
            fail("Accepted duplicate JSON keys")
        } catch (failure: InvalidJsonPayload) {
            assertEquals(InvalidJsonPayload.Reason.DUPLICATE_KEY, failure.reason)
        }
        val invalid = listOf(
            encoded.replaceFirst("\"prompt_enabled\":false", "\"prompt_enabled\":\"false\""),
            encoded.replaceFirst("\"prompt\":", "\"extra\":0,\"prompt\":"),
            encoded.replaceFirst("\"id\":", "\"extra\":0,\"id\":"),
            encoded.replaceFirst("\"kind\":\"TERM\"", "\"kind\":\"UNKNOWN\""),
        )
        for (text in invalid) {
            try {
                RuleExchangeCodec.decode(text)
                fail("Accepted invalid envelope")
            } catch (_: IllegalArgumentException) {
            }
        }
        val deep = "{\"format\":\"lnt-rule-exchange\",\"version\":1,\"prompt\":" +
            "[".repeat(10) + "]".repeat(10) + ",\"prompt_enabled\":false,\"rules\":[]}"
        try {
            RuleExchangeCodec.decode(deep)
            fail("Accepted excessive depth")
        } catch (failure: InvalidJsonPayload) {
            assertEquals(InvalidJsonPayload.Reason.DEPTH, failure.reason)
        }
        try {
            RuleExchangeCodec.decode("x".repeat(RuleExchangeCodec.MAX_CHARS + 1))
            fail("Accepted oversized envelope")
        } catch (failure: InvalidJsonPayload) {
            assertEquals(InvalidJsonPayload.Reason.SIZE, failure.reason)
        }
    }

    @Test
    fun derivedIdsAreStableForEditableContentAndDuplicateSourcesAreRejected() {
        val withoutIds = "{\"format\":\"lnt-rule-exchange\",\"version\":1,\"prompt\":\"\"," +
            "\"prompt_enabled\":false,\"rules\":[{\"kind\":\"TERM\",\"source\":\"Alice\",\"target\":\"アリス\"}]}"
        val first = RuleExchangeCodec.decode(withoutIds).rules.single()
        val second = RuleExchangeCodec.decode(withoutIds).rules.single()
        assertTrue(first.id.startsWith("rx1-"))
        assertEquals(first.id, second.id)
        val edited = RuleExchangeCodec.decode(withoutIds.replace("アリス", "ありす")).rules.single()
        assertEquals(first.id, edited.id)
        val explicit = RuleExchangeCodec.decode(withoutIds.replace("\"kind\"", "\"id\":\"stable\",\"kind\"")).rules.single()
        assertEquals("stable", explicit.id)
        val duplicate = "{\"format\":\"lnt-rule-exchange\",\"version\":1,\"prompt\":\"\"," +
            "\"prompt_enabled\":false,\"rules\":[{\"kind\":\"TERM\",\"source\":\"Alice\"}," +
            "{\"kind\":\"TERM\",\"source\":\"Alice\"}]}"
        try {
            RuleExchangeCodec.decode(duplicate)
            fail("Accepted duplicate rule identity")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun skipKeepsExistingRuleAndOverwriteUpdatesOnlyTheSameIdentity() {
        val base = ProjectTextPolicy(listOf(rule("r1", "term", "old")), "base", false)
        val incoming = RuleExchange(listOf(rule("r1", "term", "new")), "base", false)

        val skip = incoming.preview(base, RuleExchangeDuplicatePolicy.SKIP)
        assertEquals(RuleExchangeDisposition.CONFLICTING, skip.entries.single().disposition)
        assertEquals(RuleExchangeConflictReason.CONTENT_CONFLICT, skip.entries.single().conflict)
        assertEquals("r1", skip.entries.single().matchedRuleId)
        assertEquals("old", skip.policy.rules.single().target)
        assertEquals(0, skip.updated.size)

        val overwrite = incoming.preview(base, RuleExchangeDuplicatePolicy.OVERWRITE)
        assertEquals(RuleExchangeDisposition.UPDATED, overwrite.entries.single().disposition)
        assertEquals("new", overwrite.policy.rules.single().target)
        assertEquals("old", base.rules.single().target)

        val foreign = RuleExchange(listOf(rule("r2", "term", "other")), "base", false)
            .preview(base, RuleExchangeDuplicatePolicy.OVERWRITE)
        assertEquals(RuleExchangeDisposition.CONFLICTING, foreign.entries.single().disposition)
        assertEquals(RuleExchangeConflictReason.DUPLICATE_SOURCE, foreign.entries.single().conflict)
        assertEquals(listOf("r1"), foreign.policy.rules.map { it.id })

        val equivalent = RuleExchange(listOf(rule("other", "term", "old")), "base", false)
            .preview(base, RuleExchangeDuplicatePolicy.SKIP)
        assertEquals(RuleExchangeDisposition.UNCHANGED, equivalent.entries.single().disposition)
        assertEquals("r1", equivalent.entries.single().matchedRuleId)
        assertEquals(1, equivalent.policy.rules.size)
    }

    @Test
    fun duplicateSourceIdentitiesRoundTripAndPreviewAsConflicts() {
        val exchange = RuleExchange(
            rules = listOf(rule("a", "same", "one"), rule("b", "same", "two")),
        )
        val restored = RuleExchangeCodec.decode(RuleExchangeCodec.encode(exchange))
        assertEquals(listOf("a", "b"), restored.rules.map { it.id })
        val preview = exchange.preview(ProjectTextPolicy(), RuleExchangeDuplicatePolicy.OVERWRITE)
        assertEquals(RuleExchangeDisposition.ADDED, preview.entries[0].disposition)
        assertEquals(RuleExchangeDisposition.CONFLICTING, preview.entries[1].disposition)
        assertEquals(RuleExchangeConflictReason.DUPLICATE_SOURCE, preview.entries[1].conflict)
        assertEquals(listOf("a"), preview.policy.rules.map { it.id })
    }

    @Test
    fun additionsAndPromptChangesArePreviewedWithoutMutatingTheBasePolicy() {
        val base = ProjectTextPolicy(listOf(rule("r1", "term", "old")), "base", false)
        val incoming = RuleExchange(
            rules = listOf(rule("r1", "term", "old"), rule("r2", "second", "x")),
            prompt = "next",
            promptEnabled = true,
        )
        val preview = incoming.preview(base, RuleExchangeDuplicatePolicy.SKIP)
        assertEquals(1, preview.added.size)
        assertEquals("r2", preview.added.single().id)
        assertEquals(1, preview.unchanged.size)
        assertTrue(preview.promptChanged)
        assertTrue(preview.promptEnabledChanged)
        assertTrue(preview.changed)
        assertTrue(preview.clean)
        assertEquals(listOf("r1", "r2"), preview.policy.rules.map { it.id })
        assertEquals("next", preview.policy.prompt)
        assertTrue(preview.policy.promptEnabled)
        assertEquals("base", base.prompt)
        assertFalse(base.promptEnabled)
        assertEquals(1, base.rules.size)

        val withoutPrompt = incoming.preview(base, RuleExchangeDuplicatePolicy.SKIP, includePrompt = false)
        assertEquals("base", withoutPrompt.policy.prompt)
        assertFalse(withoutPrompt.policy.promptEnabled)
        assertFalse(withoutPrompt.promptChanged)
        assertFalse(withoutPrompt.promptEnabledChanged)
    }
}
