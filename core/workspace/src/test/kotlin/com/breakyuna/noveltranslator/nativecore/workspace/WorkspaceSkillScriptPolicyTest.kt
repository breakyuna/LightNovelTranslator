package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceSkillScriptPolicyTest {
    private lateinit var userRoot: File
    private lateinit var builtInRoot: File
    private lateinit var store: WorkspaceSkillStore

    @Before fun setup() {
        userRoot = tempRoot("script-policy-user")
        builtInRoot = tempRoot("script-policy-builtin")
        store = WorkspaceSkillStore.withBuiltInRoot(userRoot, builtInRoot)
    }

    @After fun cleanup() {
        userRoot.deleteRecursively()
        builtInRoot.deleteRecursively()
    }

    @Test fun disabledSkillIsRefusedEvenWithoutACallerCeiling() {
        val id = WorkspaceSkillId("disabled")
        runWorkspaceSuspend {
            val record = store.create(WorkspaceSkillDraft(id, "Disabled", content = "body"))
            store.writeResource(id, "scripts/main.js", "lg.log('disabled')", record.fingerprint, null)
            store.setEnabled(id, false, record.fingerprint)
        }
        val scripts = WorkspaceSkillScriptSource(store, store)

        val denied = assertThrows(WorkspaceSkillScriptPolicyDenied::class.java) {
            runWorkspaceSuspend { scripts.resolveForExecution(store, id.value, "scripts/main.js") }
        }
        assertEquals(WorkspaceSkillScriptPolicyDenied.Reason.DISABLED, denied.reason)
        assertEquals(id, denied.skillId)

        // The legacy resolver still reports its original visible source error.
        val unavailable = assertThrows(WorkspaceScriptSourceUnavailable::class.java) {
            runWorkspaceSuspend { scripts.resolve(id.value, "scripts/main.js") }
        }
        assertEquals(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND, unavailable.reason)
    }

    @Test fun callerCeilingCanNarrowButNeverElevateTheStoredPolicy() {
        val id = WorkspaceSkillId("narrowed")
        val record = runWorkspaceSuspend {
            val created = store.create(
                WorkspaceSkillDraft(
                    id,
                    "Narrowed",
                    content = "body",
                    policy = WorkspaceSkillPolicy(
                        allowFetch = true,
                        allowSearch = true,
                        maxRunMillis = 9_000,
                        maxOutputBytes = 8_192,
                    ),
                ),
            )
            store.writeResource(id, "scripts/main.js", "lg.log('narrowed')", created.fingerprint, null)
            created
        }
        val scripts = WorkspaceSkillScriptSource(store, store)

        val execution = runWorkspaceSuspend {
            scripts.resolveForExecution(
                catalog = store,
                scriptId = id.value,
                entryPoint = "scripts/main.js",
                ceiling = WorkspaceSkillPolicy(
                    allowFetch = false,
                    allowSearch = true,
                    maxRunMillis = 3_000,
                    maxOutputBytes = 4_096,
                ),
            )
        }

        assertEquals("lg.log('narrowed')", execution.source.text)
        assertEquals(record.fingerprint, execution.fingerprint)
        assertFalse(execution.policy.allowFetch)
        assertTrue(execution.policy.allowSearch)
        assertEquals(3_000, execution.policy.maxRunMillis)
        assertEquals(4_096, execution.policy.maxOutputBytes)
    }

    @Test fun aPermissiveCeilingCannotReEnableAFetchDeniedByTheSkill() {
        val id = WorkspaceSkillId("no-fetch")
        runWorkspaceSuspend {
            val record = store.create(
                WorkspaceSkillDraft(id, "No Fetch", content = "body", policy = WorkspaceSkillPolicy(allowFetch = false)),
            )
            store.writeResource(id, "scripts/main.js", "lg.log('local')", record.fingerprint, null)
        }
        val scripts = WorkspaceSkillScriptSource(store, store)

        val execution = runWorkspaceSuspend {
            scripts.resolveForExecution(
                catalog = store,
                scriptId = id.value,
                entryPoint = "scripts/main.js",
                ceiling = WorkspaceSkillPolicy(allowFetch = true, allowSearch = true),
            )
        }

        assertFalse(execution.policy.allowFetch)
    }

    @Test fun requiredCapabilityDenialIsReportedInsteadOfRunningANoOp() {
        val id = WorkspaceSkillId("no-network")
        runWorkspaceSuspend {
            val record = store.create(
                WorkspaceSkillDraft(
                    id,
                    "No Network",
                    content = "body",
                    policy = WorkspaceSkillPolicy(allowSearch = false, allowFetch = false),
                ),
            )
            store.writeResource(id, "scripts/main.js", "lg.log('local')", record.fingerprint, null)
        }
        val scripts = WorkspaceSkillScriptSource(store, store)

        val denied = assertThrows(WorkspaceSkillScriptPolicyDenied::class.java) {
            runWorkspaceSuspend {
                scripts.resolveForExecution(
                    catalog = store,
                    scriptId = id.value,
                    entryPoint = "scripts/main.js",
                    requiredCapabilities = setOf(
                        WorkspaceCapability.SEARCH,
                        WorkspaceCapability.FETCH,
                    ),
                )
            }
        }

        assertEquals(WorkspaceSkillScriptPolicyDenied.Reason.CAPABILITY_DENIED, denied.reason)
        assertEquals(id, denied.skillId)
        assertEquals(
            setOf(WorkspaceCapability.SEARCH, WorkspaceCapability.FETCH),
            denied.capabilities,
        )
        assertTrue(denied.message!!.contains("CAPABILITY_DENIED"))
    }

    @Test fun policyAwareSourcePortAppliesItsConfiguredRequirement() {
        val id = WorkspaceSkillId("port-policy")
        runWorkspaceSuspend {
            val record = store.create(WorkspaceSkillDraft(id, "Port Policy", content = "body"))
            store.writeResource(id, "scripts/main.js", "lg.log('port')", record.fingerprint, null)
        }
        val port = WorkspaceSkillPolicyScriptSource(
            catalog = store,
            files = store,
            requiredCapabilities = setOf(WorkspaceCapability.FETCH),
        )

        val denied = assertThrows(WorkspaceSkillScriptPolicyDenied::class.java) {
            runWorkspaceSuspend { port.resolve(id.value, "scripts/main.js") }
        }
        assertEquals(WorkspaceSkillScriptPolicyDenied.Reason.CAPABILITY_DENIED, denied.reason)

        val allowed = WorkspaceSkillPolicyScriptSource(
            catalog = store,
            files = store,
            requiredCapabilities = setOf(WorkspaceCapability.READ_MATERIALS),
        )
        val source = runWorkspaceSuspend { allowed.resolve(id.value, "scripts/main.js") }
        assertEquals("lg.log('port')", source.text)
    }

    @Test fun policyAwareSourcePortStillRefusesDisabledSkill() {
        val id = WorkspaceSkillId("port-disabled")
        runWorkspaceSuspend {
            val record = store.create(WorkspaceSkillDraft(id, "Port Disabled", content = "body"))
            store.writeResource(id, "scripts/main.js", "lg.log('port-disabled')", record.fingerprint, null)
            store.setEnabled(id, false, record.fingerprint)
        }
        val port = WorkspaceSkillPolicyScriptSource(store, store)

        val denied = assertThrows(WorkspaceSkillScriptPolicyDenied::class.java) {
            runWorkspaceSuspend { port.resolve(id.value, "scripts/main.js") }
        }

        assertEquals(WorkspaceSkillScriptPolicyDenied.Reason.DISABLED, denied.reason)
        assertEquals(id, denied.skillId)
    }

    @Test fun missingOrInvalidScriptsStillFailVisibly() {
        val scripts = WorkspaceSkillScriptSource(store, store)

        val missing = assertThrows(WorkspaceScriptSourceUnavailable::class.java) {
            runWorkspaceSuspend { scripts.resolveForExecution(store, "missing", "scripts/main.js") }
        }
        assertEquals(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND, missing.reason)

        val invalid = assertThrows(WorkspaceScriptSourceUnavailable::class.java) {
            runWorkspaceSuspend { scripts.resolveForExecution(store, "../escape", "scripts/main.js") }
        }
        assertEquals(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID, invalid.reason)
    }

    private fun tempRoot(kind: String): File =
        File(System.getProperty("java.io.tmpdir"), "lnt-$kind-" + UUID.randomUUID()).apply { mkdirs() }
}
