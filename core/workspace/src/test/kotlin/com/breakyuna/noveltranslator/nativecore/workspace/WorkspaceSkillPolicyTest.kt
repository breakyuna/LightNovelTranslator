package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceSkillPolicyTest {
    private lateinit var userRoot: File
    private lateinit var builtInRoot: File
    private lateinit var store: WorkspaceSkillStore

    @Before fun setup() {
        userRoot = tempRoot("policy-user")
        builtInRoot = tempRoot("policy-builtin")
        store = WorkspaceSkillStore.withBuiltInRoot(userRoot, builtInRoot)
    }

    @After fun cleanup() {
        userRoot.deleteRecursively()
        builtInRoot.deleteRecursively()
    }

    @Test fun legacyMetadataWithoutPolicyResolvesToTheSafeDefault() {
        writeUserSkill("legacy", "Legacy", "body", policyJson = null)

        val record = runWorkspaceSuspend { store.record(WorkspaceSkillId("legacy")) }!!
        val policy = record.summary.policy
        assertEquals(WorkspaceSkillPolicy.DEFAULT, policy)
        assertTrue(policy.allowMaterials)
        assertTrue(policy.allowArtifacts)
        assertTrue(policy.allowPrint)
        assertTrue(policy.allowAutoSelect)
        assertFalse(policy.allowSearch)
        assertFalse(policy.allowFetch)
        assertFalse(policy.allowProposeChanges)
        assertEquals(sha256Of("body"), record.summary.contentSha256)

        val reloaded = runWorkspaceSuspend { store.list() }.single()
        assertEquals(WorkspaceSkillPolicy.DEFAULT, reloaded.summary.policy)
        assertEquals(record.fingerprint, reloaded.fingerprint)
    }

    @Test fun unknownOrInvalidPolicyFieldsAreRejected() {
        assertInvalidPolicy(
            defaultPolicyJson().replace("\"allowAutoSelect\":", "\"unexpected\":true,\"allowAutoSelect\":"),
        )
        assertInvalidPolicy(
            defaultPolicyJson().replace(",\"allowAutoSelect\":true", ""),
        )
        assertInvalidPolicy(
            defaultPolicyJson().replace("\"allowFetch\":false", "\"allowFetch\":1"),
        )
        assertInvalidPolicy(
            defaultPolicyJson().replace("\"maxRunMillis\":30000", "\"maxRunMillis\":0"),
        )
        assertInvalidPolicy(
            defaultPolicyJson().replace("\"maxRunMillis\":30000", "\"maxRunMillis\":\"30000\""),
        )
    }

    @Test fun policyParticipatesInTheFingerprintAndRoundTripsThroughCas() {
        val id = WorkspaceSkillId("alpha")
        val strict = WorkspaceSkillPolicy(maxRunMillis = 5_000, maxOutputBytes = 8_192)
        val created = runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(id, "Alpha", content = "body", policy = strict))
        }
        assertEquals(strict, created.summary.policy)

        val reloaded = runWorkspaceSuspend { store.record(id) }!!
        assertEquals(strict, reloaded.summary.policy)
        assertEquals(created.fingerprint, reloaded.fingerprint)

        val changed = runWorkspaceSuspend {
            store.update(
                id,
                WorkspaceSkillDraft(id, "Alpha", content = "body", policy = strict.copy(maxRunMillis = 6_000)),
                created.fingerprint,
            )
        }
        assertEquals(6_000, changed.summary.policy.maxRunMillis)
        assertNotEquals(created.fingerprint, changed.fingerprint)
        assertEquals(
            changed.fingerprint,
            runWorkspaceSuspend { store.record(id) }!!.fingerprint,
        )
        val encoded = String(WorkspaceSkillMetadataCodec.encode(changed.summary), Charsets.UTF_8)
        assertTrue("\"policy\"" in encoded)
        assertTrue("\"allowFetch\"" in encoded)
    }

    @Test fun builtInPolicyIsReadOnlyAndNeverElevatedByAUserEdit() {
        val restrictive = WorkspaceSkillPolicy(
            allowSearch = false,
            allowFetch = false,
            allowProposeChanges = false,
            maxRunMillis = 4_000,
        )
        writeBuiltIn("builtin-1", "Built In", "built body", restrictive)
        val id = WorkspaceSkillId("builtin-1")
        val builtIn = runWorkspaceSuspend { store.record(id) }!!
        assertEquals(restrictive, builtIn.summary.policy)

        val elevated = WorkspaceSkillDraft(
            id = id,
            name = "Built In",
            content = "changed",
            policy = WorkspaceSkillPolicy(allowFetch = true, allowProposeChanges = true),
        )
        assertStoreReason(WorkspaceSkillStoreException.Reason.READ_ONLY) {
            store.update(id, elevated, builtIn.fingerprint)
        }

        val disabled = runWorkspaceSuspend { store.setEnabled(id, false, builtIn.fingerprint) }
        assertFalse(disabled.summary.enabled)
        assertEquals(restrictive, disabled.summary.policy)
        val reloaded = runWorkspaceSuspend { WorkspaceSkillStore.withBuiltInRoot(userRoot, builtInRoot).record(id) }!!
        assertFalse(reloaded.summary.enabled)
        assertEquals(restrictive, reloaded.summary.policy)

        val userOverride = runWorkspaceSuspend {
            store.create(
                WorkspaceSkillDraft(
                    id = id,
                    name = "Built In",
                    content = "user body",
                    policy = WorkspaceSkillPolicy(allowFetch = true),
                ),
            )
        }
        assertEquals(WorkspaceSkillSource.USER, userOverride.summary.source)
        assertTrue(userOverride.summary.policy.allowFetch)
        val shadowed = runWorkspaceSuspend { store.list() }.single()
        assertEquals(WorkspaceSkillSource.USER, shadowed.summary.source)

        runWorkspaceSuspend { store.delete(id, userOverride.fingerprint) }
        val restored = runWorkspaceSuspend { store.record(id) }!!
        assertEquals(WorkspaceSkillSource.BUILT_IN, restored.summary.source)
        assertEquals(restrictive, restored.summary.policy)
        assertFalse(restored.summary.enabled)
    }

    @Test fun defaultPolicyMapsOnlyLocalCapabilitiesAndIntersectsWithACeiling() {
        val policy = WorkspaceSkillPolicy.DEFAULT
        assertEquals(
            setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.EMIT_ARTIFACTS,
                WorkspaceCapability.PRINT,
            ),
            policy.grantedCapabilities,
        )
        assertFalse(policy.grants(WorkspaceCapability.SEARCH))
        assertFalse(policy.grants(WorkspaceCapability.FETCH))
        assertFalse(policy.grants(WorkspaceCapability.PROPOSE_CHANGES))

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSkillPolicy(maxRunMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSkillPolicy(maxOutputBytes = WorkspaceSkillPolicy.MAX_OUTPUT_BYTES + 1)
        }

        val effective = WorkspaceSkillPolicy(
            allowSearch = true,
            allowMaterials = true,
            maxRunMillis = 9_000,
        ).restrictedBy(WorkspaceSkillPolicy(allowSearch = false, allowMaterials = false, maxRunMillis = 3_000))
        assertFalse(effective.allowSearch)
        assertFalse(effective.allowMaterials)
        assertEquals(3_000, effective.maxRunMillis)
    }

    private fun assertInvalidPolicy(policyJson: String) {
        val id = "invalid"
        writeUserSkill(id, "Invalid", "body", policyJson)
        assertStoreReason(WorkspaceSkillStoreException.Reason.INVALID_METADATA) { store.list() }
        File(userRoot, id).deleteRecursively()
    }

    private fun writeUserSkill(id: String, name: String, content: String, policyJson: String?) {
        val directory = File(userRoot, id).apply { mkdirs() }
        File(directory, CONTENT_FILE).writeText(content)
        val metadata = buildString {
            append("{\"name\":\"").append(name)
            append("\",\"description\":\"\",\"enabled\":true,\"order\":0,")
            append("\"languages\":[],\"tags\":[],\"contentSha256\":\"").append(sha256Of(content))
            append('"')
            if (policyJson != null) append(",\"policy\":").append(policyJson)
            append('}')
        }
        File(directory, METADATA_FILE).writeText(metadata)
    }

    private fun writeBuiltIn(
        id: String,
        name: String,
        content: String,
        policy: WorkspaceSkillPolicy,
    ) {
        val directory = File(builtInRoot, id).apply { mkdirs() }
        File(directory, CONTENT_FILE).writeText(content)
        val summary = WorkspaceSkillSummary(
            id = WorkspaceSkillId(id),
            name = name,
            description = "built in",
            enabled = true,
            order = 0,
            source = WorkspaceSkillSource.BUILT_IN,
            contentPath = WorkspacePath("$id/$CONTENT_FILE"),
            contentSha256 = sha256Of(content),
            policy = policy,
        )
        File(directory, METADATA_FILE).writeBytes(WorkspaceSkillMetadataCodec.encode(summary))
    }

    private fun defaultPolicyJson(): String = buildString {
        append("{\"allowMaterials\":true,\"allowArtifacts\":true,\"allowSearch\":false,")
        append("\"allowFetch\":false,\"allowProposeChanges\":false,\"allowPrint\":true,")
        append("\"maxRunMillis\":30000,\"maxOutputBytes\":262144,\"maxArtifactBytes\":262144,")
        append("\"maxChangeBytes\":131072,\"allowAutoSelect\":true}")
    }

    private fun assertStoreReason(
        reason: WorkspaceSkillStoreException.Reason,
        block: suspend () -> Unit,
    ) {
        val failure = assertThrows(WorkspaceSkillStoreException::class.java) { runWorkspaceSuspend { block() } }
        assertEquals(reason, failure.reason)
    }

    private fun tempRoot(kind: String): File =
        File(System.getProperty("java.io.tmpdir"), "lnt-skill-$kind-" + UUID.randomUUID()).apply { mkdirs() }
}
