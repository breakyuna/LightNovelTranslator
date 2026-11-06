package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceFailureReason
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceSnapshot
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure dialog/banner decisions shared by the Compose surfaces. */
class NativeSkillExecutionUiTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val request = ForegroundWorkspaceRequest(
        ref = ref,
        identity = RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("command")),
        skillId = "skill-one",
        entryPoint = "scripts/main.js",
        scriptSha256 = "a".repeat(64),
        scriptSizeBytes = 10,
        projectRevision = 3,
    )
    private fun record(id: String = "skill-one") = WorkspaceSkillRecord(
        WorkspaceSkillSummary(
            id = WorkspaceSkillId(id),
            name = "Guide",
            description = "Existing",
            enabled = true,
            order = 0,
            source = WorkspaceSkillSource.USER,
        ),
        WorkspaceDigest("b".repeat(64)),
    )
    private fun snapshot(phase: ForegroundWorkspacePhase, failure: ForegroundWorkspaceFailureReason? = null) =
        ForegroundWorkspaceSnapshot(1, "token", request, phase, failure = failure)

    @Test fun onlyAnActiveRunOfTheSameSkillLocksItsResources() {
        assertTrue(skillExecutionLocksResources(snapshot(ForegroundWorkspacePhase.RUNNING), record()))
        assertFalse(skillExecutionLocksResources(snapshot(ForegroundWorkspacePhase.RUNNING), record("other")))
        assertFalse(skillExecutionLocksResources(snapshot(ForegroundWorkspacePhase.COMPLETED), record()))
        assertFalse(skillExecutionLocksResources(snapshot(ForegroundWorkspacePhase.CANCELLED), record()))
        assertFalse(skillExecutionLocksResources(null, record()))
    }

    @Test fun phaseLabelsNeverPretendAFailedOrInterruptedRunIsRunning() {
        assertEquals(R.string.native_skill_execution_running,
            skillExecutionPhaseLabel(snapshot(ForegroundWorkspacePhase.RUNNING))!!)
        assertEquals(R.string.native_skill_execution_stopping,
            skillExecutionPhaseLabel(snapshot(ForegroundWorkspacePhase.STOPPING))!!)
        assertEquals(R.string.native_skill_execution_timeout,
            skillExecutionPhaseLabel(snapshot(ForegroundWorkspacePhase.FAILED,
                ForegroundWorkspaceFailureReason.EXECUTION_TIMEOUT))!!)
        assertEquals(R.string.native_skill_execution_interrupted,
            skillExecutionPhaseLabel(snapshot(ForegroundWorkspacePhase.INTERRUPTED))!!)
        assertEquals(R.string.native_skill_execution_cancelled,
            skillExecutionPhaseLabel(snapshot(ForegroundWorkspacePhase.CANCELLED))!!)
        assertNull(skillExecutionPhaseLabel(null))
    }
}
