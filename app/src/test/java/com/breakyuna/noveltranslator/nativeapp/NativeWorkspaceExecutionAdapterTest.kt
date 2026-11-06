package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunJournalHandle
import com.breakyuna.noveltranslator.nativecore.domain.RunJournalPort
import com.breakyuna.noveltranslator.nativecore.domain.RunCheckpoint
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.StoredRun
import com.breakyuna.noveltranslator.nativecore.domain.StoredRunState
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.engine.DurableRunExecutor
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceArtifact
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceObserver
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePolicy
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRequest
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysisRun
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceLogLine
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceLogStream
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceScriptResult
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceScriptSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillScriptExecution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter validation, policy, cancellation and observer behavior; no Android service/device. */
class NativeWorkspaceExecutionAdapterTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val script = "module.exports = () => 1"
    private val scriptSha = TextProjectFingerprint.bytes(script.toByteArray(Charsets.UTF_8))
    private val identity = RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("command"))

    private class Journal : RunJournalPort {
        val terminals = mutableListOf<StoredRunState>()
        var began = 0
        override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle {
            began++
            return RunJournalHandle("execution-$began", ref, run)
        }
        override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint) = Unit
        override suspend fun finish(handle: RunJournalHandle, state: StoredRunState) {
            terminals.add(state)
        }
        override suspend fun recoverInterrupted() = Unit
        override suspend fun find(ref: LocalProjectRef, run: RunIdentity): StoredRun? = null
        override suspend fun recent(ref: LocalProjectRef, limit: Int) = emptyList<StoredRun>()
    }

    private fun request(
        input: String = "{}",
        allowFetch: Boolean = false,
        policy: ForegroundWorkspacePolicy = ForegroundWorkspacePolicy(),
    ) = ForegroundWorkspaceRequest(
        ref = ref,
        identity = identity,
        skillId = "fixture-skill",
        entryPoint = "scripts/main.js",
        scriptSha256 = scriptSha,
        scriptSizeBytes = script.toByteArray(Charsets.UTF_8).size.toLong(),
        projectRevision = 7,
        policy = policy,
        inputJson = input,
        allowFetch = allowFetch,
    )

    private fun execution(policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy()) =
        WorkspaceSkillScriptExecution(
            WorkspaceSkillId("fixture-skill"),
            WorkspaceScriptSource("fixture-skill", "scripts/main.js", script),
            policy,
            WorkspaceDigest("b".repeat(64)),
        )

    private fun adapter(
        journal: Journal = Journal(),
        revision: Long = 7,
        source: NativeWorkspaceSkillSource = NativeWorkspaceSkillSource { _, _ -> execution() },
        analysis: NativeWorkspaceAnalysisPort = NativeWorkspaceAnalysisPort { _, _, _, _, _, _, _, _ ->
            ProjectWorkspaceAnalysisRun(
                revision = 7,
                result = WorkspaceScriptResult(
                    exitCode = 0,
                    changes = emptyList(),
                    artifacts = emptyList(),
                    logs = listOf(WorkspaceLogLine(WorkspaceLogStream.STDOUT, "done")),
                ),
                artifacts = emptyList(),
            )
        },
    ): NativeWorkspaceExecutionAdapter {
        val runtime = RuntimeCoordinator()
        return NativeWorkspaceExecutionAdapter(
            runtime = runtime,
            runs = DurableRunExecutor(runtime, journal),
            projectRevision = { revision },
            skills = source,
            analysis = analysis,
        )
    }

    private class Observer : ForegroundWorkspaceObserver {
        val logs = mutableListOf<String>()
        val artifacts = mutableListOf<ForegroundWorkspaceArtifact>()
        override fun log(line: String) { logs += line }
        override fun artifact(artifact: ForegroundWorkspaceArtifact) { artifacts += artifact }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected the adapter to refuse the request")
    }

    @Test fun changedProjectRevisionIsRefusedBeforeAnyRunOrOperation() = runBlocking {
        val journal = Journal()
        val failure = failure { adapter(journal = journal, revision = 8).execute(request(), Observer()) }
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, journal.began)
    }

    @Test fun changedScriptFingerprintAndDisabledSkillAreRefused() = runBlocking {
        val journal = Journal()
        val source = NativeWorkspaceSkillSource { _, _ ->
            execution().let {
                WorkspaceSkillScriptExecution(it.skillId, WorkspaceScriptSource(
                    "fixture-skill", "scripts/main.js", "$script\n",
                ), it.policy, it.fingerprint)
            }
        }
        assertTrue(failure {
            adapter(journal = journal, source = source).execute(request(), Observer())
        } is IllegalArgumentException)
        assertEquals(0, journal.began)

        val disabled = NativeWorkspaceSkillSource { _, _ ->
            throw com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillScriptPolicyDenied(
                com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillScriptPolicyDenied.Reason.DISABLED,
                WorkspaceSkillId("fixture-skill"),
            )
        }
        assertTrue(failure {
            adapter(journal = journal, source = disabled).execute(request(), Observer())
        } is SecurityException)
        assertEquals(0, journal.began)
    }

    @Test fun fetchRequiresTheFrozenSkillPolicyAndNeverStartsAPaidRun() = runBlocking {
        val source = NativeWorkspaceSkillSource { _, _ -> execution(WorkspaceSkillPolicy(allowFetch = false)) }
        val failure = failure {
            adapter(source = source).execute(
                request(allowFetch = true, policy = ForegroundWorkspacePolicy(allowFetch = true)),
                Observer(),
            )
        }
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun analysisReceivesTheFrozenPolicyAndTheRunSwitchOnlyNarrowsFetch() = runBlocking {
        var seen: Pair<Boolean, WorkspaceSkillPolicy>? = null
        val source = NativeWorkspaceSkillSource { _, ceiling ->
            execution(WorkspaceSkillPolicy(allowFetch = true, maxRunMillis = 9_000)
                .restrictedBy(ceiling))
        }
        val analysis = NativeWorkspaceAnalysisPort { _, _, _, _, allowFetch, _, _, policy ->
            seen = allowFetch to policy
            ProjectWorkspaceAnalysisRun(
                revision = 7,
                result = WorkspaceScriptResult(
                    exitCode = 0,
                    changes = emptyList(),
                    artifacts = emptyList(),
                    logs = emptyList(),
                ),
                artifacts = emptyList(),
            )
        }
        val requestPolicy = ForegroundWorkspacePolicy(allowFetch = true, maxRunMillis = 8_000)

        adapter(source = source, analysis = analysis).execute(
            request(allowFetch = false, policy = requestPolicy),
            Observer(),
        )
        val narrowed = requireNotNull(seen)
        assertFalse(narrowed.first)
        assertTrue(narrowed.second.allowFetch)
        assertEquals(8_000, narrowed.second.maxRunMillis)

        adapter(source = source, analysis = analysis).execute(
            request(allowFetch = true, policy = requestPolicy),
            Observer(),
        )
        assertTrue(requireNotNull(seen).first)
    }

    @Test fun successfulRunReportsLogsAndArtifactsThenSettles() = runBlocking {
        val journal = Journal()
        val observer = Observer()
        val result = adapter(journal = journal).execute(request(), observer)
        assertEquals(listOf("done"), observer.logs)
        assertEquals("exit=0, artifacts=0", result.summary)
        assertEquals(listOf(StoredRunState.SUCCEEDED), journal.terminals)
    }

    @Test fun cancellingTheExecutionMirrorsTheCooperativeWorkspaceToken() = runBlocking {
        val journal = Journal()
        val entered = CompletableDeferred<Unit>()
        var observed: WorkspaceCancellation? = null
        val analysis = NativeWorkspaceAnalysisPort { _, _, _, _, _, _, cancellation, _ ->
            observed = cancellation
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val job = launch { adapter(journal = journal, analysis = analysis).execute(request(), Observer()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(requireNotNull(observed).isCancelled)
        assertTrue(journal.terminals.lastOrNull() == StoredRunState.CANCELLED)
    }

    @Test fun policyMappingKeepsFetchAndBudgetCeilings() {
        val mapped = ForegroundWorkspacePolicy(
            allowFetch = true,
            maxRunMillis = 1_234,
            maxOutputBytes = 4_096,
        ).toWorkspaceSkillPolicy()
        assertTrue(mapped.allowFetch)
        assertEquals(1_234, mapped.maxRunMillis)
        assertEquals(4_096, mapped.maxOutputBytes)
        assertFalse(mapped.allowProposeChanges)
    }
}
