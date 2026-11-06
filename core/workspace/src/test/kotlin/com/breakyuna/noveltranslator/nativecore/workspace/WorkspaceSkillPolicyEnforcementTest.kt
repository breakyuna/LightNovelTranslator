package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSkillPolicyEnforcementTest {
    @Test
    fun analysisPlanIntersectsCapabilitiesAndNarrowsEveryPolicyBudget() {
        val policy = WorkspaceSkillPolicy(
            allowMaterials = true,
            allowArtifacts = true,
            allowSearch = false,
            allowFetch = false,
            allowProposeChanges = false,
            allowPrint = false,
            maxRunMillis = 5_000,
            maxOutputBytes = 8_192,
            maxArtifactBytes = 4_096,
            maxChangeBytes = 2_048,
        )

        val plan = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.WORKSPACE,
            allowFetch = true,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = true,
            policy = policy,
        )

        assertEquals(
            setOf(WorkspaceCapability.READ_MATERIALS, WorkspaceCapability.EMIT_ARTIFACTS),
            plan.capabilities,
        )
        assertTrue(plan.materialsEnabled)
        assertTrue(plan.artifactsEnabled)
        assertFalse(plan.proposalEnabled)
        assertFalse(plan.searchEnabled)
        assertFalse(plan.fetchEnabled)
        assertEquals(8_192L, plan.budget.maxOutputBytes)
        assertEquals(4_096L, plan.budget.maxArtifactBytes)
        assertEquals(2_048L, plan.budget.maxChangeBytes)
        assertEquals(5_000L, plan.timeoutMillis)
    }

    @Test
    fun callerAllowFetchFalseWinsEvenWhenThePolicyWouldGrantNetwork() {
        val permissive = WorkspaceSkillPolicy(
            allowSearch = true,
            allowFetch = true,
            allowProposeChanges = true,
        )

        val plan = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.NONE,
            allowFetch = false,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = false,
            policy = permissive,
        )

        assertEquals(setOf(WorkspaceCapability.READ_MATERIALS), plan.capabilities)
        assertFalse(plan.searchEnabled)
        assertFalse(plan.fetchEnabled)
    }

    @Test
    fun policyGrantNeverAddsCapabilitiesThatTheModeDidNotRequest() {
        val permissive = WorkspaceSkillPolicy(
            allowSearch = true,
            allowFetch = true,
            allowProposeChanges = true,
        )

        val analysisOnly = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.NONE,
            allowFetch = true,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = false,
            policy = permissive,
        )
        assertTrue(analysisOnly.searchEnabled)
        assertTrue(analysisOnly.fetchEnabled)
        assertFalse(analysisOnly.proposalEnabled)
        assertFalse(WorkspaceCapability.PROPOSE_CHANGES in analysisOnly.capabilities)

        val proposal = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.WORKSPACE,
            allowFetch = true,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = true,
            policy = permissive,
        )
        assertTrue(proposal.proposalEnabled)
        assertEquals(
            setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.PROPOSE_CHANGES,
                WorkspaceCapability.EMIT_ARTIFACTS,
                WorkspaceCapability.SEARCH,
                WorkspaceCapability.FETCH,
            ),
            proposal.capabilities,
        )
    }

    @Test
    fun noPolicyReproducesTheLegacyAnalysisRequest() {
        val plan = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.WORKSPACE,
            allowFetch = true,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = true,
            policy = null,
        )
        val base = projectWorkspaceAnalysisBaseBudget(
            ProjectWorkspaceAnalysisProposalMode.WORKSPACE,
            publisherAvailable = true,
        )

        assertEquals(
            setOf(
                WorkspaceCapability.READ_MATERIALS,
                WorkspaceCapability.PROPOSE_CHANGES,
                WorkspaceCapability.EMIT_ARTIFACTS,
                WorkspaceCapability.SEARCH,
                WorkspaceCapability.FETCH,
            ),
            plan.capabilities,
        )
        assertTrue(plan.searchEnabled)
        assertTrue(plan.fetchEnabled)
        assertEquals(base.maxOutputBytes, plan.budget.maxOutputBytes)
        assertEquals(base.maxChangeBytes, plan.budget.maxChangeBytes)
        assertEquals(base.maxArtifactBytes, plan.budget.maxArtifactBytes)
        assertEquals(120_000L, plan.timeoutMillis)
    }

    @Test
    fun noPolicyKeepsTheLegacyAnalyzeOutputAndArtifactBudgets() {
        val plan = planProjectWorkspaceAnalysis(
            proposalMode = ProjectWorkspaceAnalysisProposalMode.NONE,
            allowFetch = false,
            searchAvailable = true,
            fetchAvailable = true,
            publisherAvailable = true,
            policy = null,
        )

        assertEquals(setOf(WorkspaceCapability.READ_MATERIALS, WorkspaceCapability.EMIT_ARTIFACTS),
            plan.capabilities)
        assertEquals(64L * 1024, plan.budget.maxOutputBytes)
        assertEquals(1, plan.budget.maxChanges)
        assertEquals(64L * 1024, plan.budget.maxArtifactBytes)
        assertEquals(1024L * 1024, plan.budget.maxTotalArtifactBytes)
        assertEquals(120_000L, plan.timeoutMillis)
    }

    @Test
    fun policyCeilingsCanOnlyLowerAnAlreadySmallerBudget() {
        val base = WorkspaceBudget(
            maxOutputBytes = 1_024,
            maxChangeBytes = 512,
            maxTotalChangeBytes = 1_024,
            maxArtifactBytes = 256,
            maxTotalArtifactBytes = 512,
            timeoutMillis = 10_000,
        )
        val policy = WorkspaceSkillPolicy(
            maxOutputBytes = 4_096,
            maxArtifactBytes = 4_096,
            maxChangeBytes = 4_096,
            maxRunMillis = 20_000,
        )

        val effective = WorkspaceSkillPolicyEnforcement.budget(base, policy)

        assertEquals(1_024L, effective.maxOutputBytes)
        assertEquals(512L, effective.maxChangeBytes)
        assertEquals(256L, effective.maxArtifactBytes)
        assertEquals(10_000L, WorkspaceSkillPolicyEnforcement.timeoutMillis(base.timeoutMillis, policy))
    }

    @Test
    fun skillAndRunPoliciesMergeAsTwoCeilings() {
        val skill = WorkspaceSkillPolicy(allowFetch = true, maxRunMillis = 9_000)
        val run = WorkspaceSkillPolicy(allowFetch = false, maxRunMillis = 3_000)

        val merged = WorkspaceSkillPolicyEnforcement.merge(skill, run)

        assertFalse(merged!!.allowFetch)
        assertEquals(3_000, merged.maxRunMillis)
        assertEquals(skill, WorkspaceSkillPolicyEnforcement.merge(skill, null))
        assertEquals(run, WorkspaceSkillPolicyEnforcement.merge(null, run))
        assertNull(WorkspaceSkillPolicyEnforcement.merge(null, null))
    }
}
