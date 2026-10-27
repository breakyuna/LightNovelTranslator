package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceExecutionResult
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRunner
import com.breakyuna.noveltranslator.nativecore.engine.WorkspaceExecutionPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host token arbitration only; real notification/foreground behavior needs a device. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeWorkspaceExecutionHostTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun request(command: String = "run") = ForegroundWorkspaceRequest(
        ref = ref,
        identity = RunIdentity(ref.projectId, ChatId("chat"), RunId(command), CommandId(command)),
        skillId = "fixture-skill",
        entryPoint = "scripts/main.js",
        scriptSha256 = "a".repeat(64),
        scriptSizeBytes = 12,
        projectRevision = 1,
    )

    @Test fun foreignAndNullTokensNeverAcknowledgeOrStopTheOwnedRun() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = ForegroundWorkspaceRunner(this, WorkspaceExecutionPort { _, _ ->
            entered.complete(Unit)
            release.await()
            ForegroundWorkspaceExecutionResult()
        })
        val promoted = mutableListOf<String>()
        val dismissed = mutableListOf<Int>()
        val host = NativeWorkspaceExecutionHost(runner, this,
            promote = { promoted += it.token },
            update = {},
            dismiss = { dismissed += it })
        val handle = runner.prepare(request())

        host.command(NativeWorkspaceExecutionHost.Command.START, "foreign", 1)
        host.command(NativeWorkspaceExecutionHost.Command.STOP, null, 2)
        assertTrue(dismissed.containsAll(listOf(1, 2)))
        assertTrue(promoted.isEmpty())
        assertEquals(ForegroundWorkspacePhase.PREPARING, runner.state.value!!.phase)

        host.command(NativeWorkspaceExecutionHost.Command.START, handle.token, 3)
        entered.await(); runCurrent()
        assertEquals(listOf(handle.token), promoted)
        assertTrue(runner.accepts(handle.token))
        host.command(NativeWorkspaceExecutionHost.Command.STOP, "foreign", 4)
        assertEquals(ForegroundWorkspacePhase.RUNNING, runner.state.value!!.phase)
        assertTrue(runner.accepts(handle.token))

        host.command(NativeWorkspaceExecutionHost.Command.STOP, handle.token, 5)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertFalse(runner.accepts(handle.token))
        assertFalse(runner.state.value!!.phase.active)
        host.destroy()
    }
}
