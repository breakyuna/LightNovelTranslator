package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class ProjectTextPolicyTest {
    private fun rule(id: String = "id", source: String = "source") = ProjectTextRule(id, TextRuleKind.TERM, source, "target")
    @Test fun canonicalRoundTripKeepsOrderIdentityFlagsAndPromptWithoutMutableAliases() {
        val policy = ProjectTextPolicy(listOf(rule("one"), rule("two")), "prompt", true)
        val fingerprint = policy.fingerprint()
        val restored = ProjectTextPolicyCodec.decode(ProjectTextPolicyCodec.encode(policy))
        assertEquals(listOf("one", "two"), restored.rules.map { it.id })
        assertEquals(fingerprint, restored.fingerprint()); assertTrue(restored.promptEnabled)
        assertFalse(policy.toString().contains("prompt")); assertFalse(policy.rules.first().toString().contains("source"))
    }
    @Test fun rejectsDuplicatesBadBooleansUnknownFieldsAndAnotherInterchangeFormat() {
        try { ProjectTextPolicy(listOf(rule(), rule())); fail("Accepted duplicate rule ids") } catch (_: IllegalArgumentException) { }
        val encoded = ProjectTextPolicyCodec.encode(ProjectTextPolicy(listOf(rule())))
        for (invalid in listOf(encoded.replace("lnt-text-policy", "lg-preset"), encoded.replace("\"enabled\":true", "\"enabled\":\"true\""),
            encoded.replace("\"version\":1", "\"version\":1,\"extra\":0"))) {
            try { ProjectTextPolicyCodec.decode(invalid); fail("Accepted invalid policy") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun policyAndPatternLimitsFailBeforeAnExecutableRuleIsStored() {
        try { ProjectTextPolicyCodec.encode(ProjectTextPolicy((1..256).map { rule(it.toString(), "x".repeat(4096)) })); fail("Ignored policy budget") }
        catch (_: IllegalArgumentException) { }
        for (pattern in listOf("(?" + "U)source", "(?x) source", "(", "(".repeat(33) + "x" + ")".repeat(33))) {
            try { TextRuleValidation.validate(ProjectTextPolicy(listOf(ProjectTextRule("regex", TextRuleKind.PROTECT, pattern, regex = true)))); fail("Accepted unsupported pattern") }
            catch (_: InvalidTextRule) { }
        }
    }
    @Test fun commandDigestBindsProjectGenerationGlobalRevisionAndPolicy() {
        val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("local"), RecoveryGeneration(1))
        val policy = ProjectTextPolicy(listOf(rule())); val base = ProjectTextPolicy().fingerprint()
        val first = ProjectPolicyEdit(CommandId("command"), ref, 2, base, policy).digest()
        assertNotEquals(first, ProjectPolicyEdit(CommandId("command"), ref.copy(generation = RecoveryGeneration(2)), 2, base, policy).digest())
        assertNotEquals(first, ProjectPolicyEdit(CommandId("command"), ref, 3, base, policy).digest())
        assertNotEquals(first, ProjectPolicyEdit(CommandId("command"), ref, 2, base, ProjectTextPolicy()).digest())
    }
    @Test fun termScopeRoundTripsAndDefaultPayloadFingerprintStaysStable() {
        val body = ProjectTextRule("id", TextRuleKind.TERM, "source", "target")
        val legacy = ProjectTextPolicyCodec.encode(ProjectTextPolicy(listOf(body)))
        assertFalse(legacy.contains("\"scope\""))
        assertEquals(TextRuleScope.BODY, ProjectTextPolicyCodec.decode(legacy).rules.single().scope)
        for (scope in listOf(TextRuleScope.NAME, TextRuleScope.BOTH)) {
            val policy = ProjectTextPolicy(listOf(body.copy(scope = scope)))
            assertEquals(scope, ProjectTextPolicyCodec.decode(ProjectTextPolicyCodec.encode(policy)).rules.single().scope)
        }
        try {
            ProjectTextRule("bad", TextRuleKind.PROTECT, "x", scope = TextRuleScope.NAME)
            fail("Non-term rule accepted a name scope")
        } catch (_: IllegalArgumentException) { }
    }
}
