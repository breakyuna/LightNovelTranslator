package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimePolicyTest {
    @Test
    fun skillPolicyIntersectsCapabilitiesButNeverReplacesApproval() {
        val summary = materialSummary("mat-policy")
        val fixture = RuntimeFixture(listOf(summary), mapOf(summary.id to "material-body".toByteArray()))
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        val policy = WorkspaceSkillPolicy(
            allowMaterials = true,
            allowArtifacts = false,
            allowSearch = false,
            allowFetch = false,
            allowProposeChanges = true,
            allowPrint = false,
        )
        val request = runRequest(
            materialIds = listOf(summary.id),
            grantedCapabilities = setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.PROPOSE_CHANGES,
                WorkspaceCapability.EMIT_ARTIFACTS,
                WorkspaceCapability.SEARCH,
                WorkspaceCapability.FETCH,
                WorkspaceCapability.PRINT,
            ),
        )

        val outcome = runWorkspaceSuspend { fixture.runtime.run(request, policy) }

        assertTrue(outcome is WorkspaceRunOutcome.Completed)
        assertEquals(
            setOf(WorkspaceCapability.READ_MATERIALS, WorkspaceCapability.PROPOSE_CHANGES),
            fixture.engine.lastRequest!!.grantedCapabilities,
        )
        assertEquals(listOf(summary.id), fixture.engine.lastRequest!!.materials.map { it.id })
        assertEquals(1, fixture.approvals.calls)
        assertEquals(1, fixture.apply.calls)
    }

    @Test
    fun policyCanNeverEnableNetworkThatTheCallerDidNotRequest() {
        val fixture = RuntimeFixture()
        val policy = WorkspaceSkillPolicy(
            allowSearch = true,
            allowFetch = true,
            allowProposeChanges = true,
        )
        val request = runRequest(grantedCapabilities = setOf(WorkspaceCapability.READ_MATERIALS))

        runWorkspaceSuspend { fixture.runtime.run(request, policy) }

        assertEquals(setOf(WorkspaceCapability.READ_MATERIALS), fixture.engine.lastRequest!!.grantedCapabilities)
    }

    @Test
    fun deniedMaterialsAreNotLoadedOrExposedToTheScript() {
        val summary = materialSummary("mat-denied")
        val fixture = RuntimeFixture(listOf(summary), mapOf(summary.id to "material-body".toByteArray()))
        val policy = WorkspaceSkillPolicy(allowMaterials = false)

        runWorkspaceSuspend { fixture.runtime.run(runRequest(materialIds = listOf(summary.id)), policy) }

        assertTrue(fixture.engine.lastRequest!!.materials.isEmpty())
        assertFalse(WorkspaceCapability.READ_MATERIALS in fixture.engine.lastRequest!!.grantedCapabilities)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun deniedProposalCapabilityRejectsScriptChangesVisibly() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        val policy = WorkspaceSkillPolicy(allowProposeChanges = false)
        val request = runRequest(
            grantedCapabilities = setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.PROPOSE_CHANGES,
            ),
        )

        val outcome = runWorkspaceSuspend { fixture.runtime.run(request, policy) }

        assertTrue(outcome is WorkspaceRunOutcome.ScriptRejected)
        assertEquals(
            WorkspaceOutputViolation.CHANGES_WITHOUT_CAPABILITY,
            (outcome as WorkspaceRunOutcome.ScriptRejected).violation,
        )
        assertFalse(WorkspaceCapability.PROPOSE_CHANGES in fixture.engine.lastRequest!!.grantedCapabilities)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun deniedArtifactCapabilityRejectsScriptArtifactsVisibly() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, emptyList(), listOf(artifact("uploads/report.md", "report")))
        }
        val policy = WorkspaceSkillPolicy(allowArtifacts = false)
        val request = runRequest(
            grantedCapabilities = setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.EMIT_ARTIFACTS,
            ),
        )

        val outcome = runWorkspaceSuspend { fixture.runtime.run(request, policy) }

        assertTrue(outcome is WorkspaceRunOutcome.ScriptRejected)
        assertEquals(
            WorkspaceOutputViolation.ARTIFACTS_WITHOUT_CAPABILITY,
            (outcome as WorkspaceRunOutcome.ScriptRejected).violation,
        )
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.artifacts.calls)
    }

    @Test
    fun policyCapsTheRunDeadline() {
        val fixture = RuntimeFixture()
        val policy = WorkspaceSkillPolicy(maxRunMillis = 5_000)
        fixture.clock.now = 1_000
        fixture.engine.onExecute = { fixture.clock.now = 6_001 }

        val outcome = runWorkspaceSuspend { fixture.runtime.run(runRequest(), policy) }

        assertTrue(outcome is WorkspaceRunOutcome.TimedOut)
        assertEquals(WorkspaceRunStage.SCRIPT, (outcome as WorkspaceRunOutcome.TimedOut).stage)
        assertEquals(6_000L, fixture.engine.lastRequest!!.deadlineAtMillis)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun policyCapsOutputBytesBeforeApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(createChange("work/a.txt", "body")),
                emptyList(),
                logs = listOf(WorkspaceLogLine(WorkspaceLogStream.STDOUT, "0123456789")),
            )
        }
        val policy = WorkspaceSkillPolicy(maxOutputBytes = 8, allowProposeChanges = true)

        val outcome = runWorkspaceSuspend { fixture.runtime.run(runRequest(), policy) }

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        val blocked = outcome as WorkspaceRunOutcome.BudgetBlocked
        assertEquals(WorkspaceBudgetKind.OUTPUT_BYTES, blocked.kind)
        assertEquals(WorkspaceRunStage.OUTPUT_VALIDATION, blocked.stage)
        assertEquals(8L, fixture.engine.lastRequest!!.limits.maxOutputBytes)
        assertEquals(0, fixture.approvals.calls)
    }

    @Test
    fun policyCapsSingleChangeBytesBeforeApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "12345")), emptyList())
        }
        val policy = WorkspaceSkillPolicy(maxChangeBytes = 4, allowProposeChanges = true)

        val outcome = runWorkspaceSuspend { fixture.runtime.run(runRequest(), policy) }

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        assertEquals(WorkspaceBudgetKind.CHANGE_BYTES, (outcome as WorkspaceRunOutcome.BudgetBlocked).kind)
        assertEquals(4L, fixture.engine.lastRequest!!.limits.maxChangeBytes)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun policyCapsSingleArtifactBytesBeforeApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, emptyList(), listOf(artifact("uploads/report.md", "12345")))
        }
        val policy = WorkspaceSkillPolicy(maxArtifactBytes = 4)

        val outcome = runWorkspaceSuspend { fixture.runtime.run(runRequest(), policy) }

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        assertEquals(WorkspaceBudgetKind.ARTIFACT_BYTES, (outcome as WorkspaceRunOutcome.BudgetBlocked).kind)
        assertEquals(4L, fixture.engine.lastRequest!!.limits.maxArtifactBytes)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.artifacts.calls)
    }

    @Test
    fun requestCarriedPolicyIsAppliedByTheLegacyRunOverload() {
        val fixture = RuntimeFixture()
        val policy = WorkspaceSkillPolicy(
            allowProposeChanges = false,
            allowFetch = false,
            maxRunMillis = 7_000,
        )
        val budget = WorkspaceBudget(timeoutMillis = 120_000)
        val request = requestWith(
            budget = budget,
            capabilities = setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.PROPOSE_CHANGES,
                WorkspaceCapability.FETCH,
            ),
            skillPolicy = policy,
        )
        fixture.clock.now = 2_000

        runWorkspaceSuspend { fixture.runtime.run(request) }

        assertEquals(setOf(WorkspaceCapability.READ_MATERIALS), fixture.engine.lastRequest!!.grantedCapabilities)
        assertEquals(9_000L, fixture.engine.lastRequest!!.deadlineAtMillis)
    }

    @Test
    fun requestPolicyAndRunPolicyAreBothCeilings() {
        val fixture = RuntimeFixture()
        fixture.clock.now = 1_000
        val request = requestWith(
            budget = WorkspaceBudget(),
            capabilities = setOf(WorkspaceCapability.READ_MATERIALS, WorkspaceCapability.PROPOSE_CHANGES),
            skillPolicy = WorkspaceSkillPolicy(allowProposeChanges = true, maxRunMillis = 9_000),
        )

        runWorkspaceSuspend {
            fixture.runtime.run(
                request,
                WorkspaceSkillPolicy(allowProposeChanges = false, maxRunMillis = 3_000),
            )
        }

        assertEquals(setOf(WorkspaceCapability.READ_MATERIALS), fixture.engine.lastRequest!!.grantedCapabilities)
        assertEquals(4_000L, fixture.engine.lastRequest!!.deadlineAtMillis)
    }

    @Test
    fun legacyRunWithoutAnyPolicyKeepsEveryRequestedCapabilityAndBudget() {
        val fixture = RuntimeFixture()
        val budget = WorkspaceBudget(
            maxOutputBytes = 512,
            maxChanges = 3,
            maxChangeBytes = 128,
            maxTotalChangeBytes = 256,
            maxArtifacts = 2,
            maxArtifactBytes = 64,
            maxTotalArtifactBytes = 128,
            timeoutMillis = 2_000,
        )
        val capabilities = setOf(
            WorkspaceCapability.READ_MATERIALS,
            WorkspaceCapability.PROPOSE_CHANGES,
            WorkspaceCapability.EMIT_ARTIFACTS,
            WorkspaceCapability.SEARCH,
            WorkspaceCapability.FETCH,
            WorkspaceCapability.PRINT,
        )
        val request = requestWith(budget = budget, capabilities = capabilities, skillPolicy = null)
        fixture.clock.now = 10_000
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }

        runWorkspaceSuspend { fixture.runtime.run(request) }

        val script = fixture.engine.lastRequest!!
        assertEquals(capabilities, script.grantedCapabilities)
        assertEquals(512L, script.limits.maxOutputBytes)
        assertEquals(3, script.limits.maxChanges)
        assertEquals(128L, script.limits.maxChangeBytes)
        assertEquals(2, script.limits.maxArtifacts)
        assertEquals(64L, script.limits.maxArtifactBytes)
        assertEquals(12_000L, script.deadlineAtMillis)
        assertNull(request.skillPolicy)
        assertEquals(1, fixture.approvals.calls)
    }

    private fun requestWith(
        budget: WorkspaceBudget,
        capabilities: Set<WorkspaceCapability>,
        skillPolicy: WorkspaceSkillPolicy?,
    ): WorkspaceRunRequest = WorkspaceRunRequest(
        commandId = COMMAND_ID,
        projectId = PROJECT_ID,
        chatId = CHAT_ID,
        runId = RUN_ID,
        changeSetId = CHANGE_SET_ID,
        scriptId = "test-script",
        entryPoint = "main.js",
        grantedCapabilities = capabilities,
        budget = budget,
        skillPolicy = skillPolicy,
    )

    private fun artifact(path: String, text: String): WorkspaceArtifactDraft =
        WorkspaceArtifactDraft(WorkspacePath(path), "text/plain", text.toByteArray(Charsets.UTF_8))
}
