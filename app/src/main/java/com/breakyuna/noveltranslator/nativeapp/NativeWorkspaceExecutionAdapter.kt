package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.engine.DurableRunExecutor
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceArtifact
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceExecutionResult
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceObserver
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePolicy
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceStage
import com.breakyuna.noveltranslator.nativecore.engine.WorkspaceExecutionPort
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysisRun
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillScriptExecution
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Narrow testable projection of the production workspace analysis service. */
internal fun interface NativeWorkspaceAnalysisPort {
    suspend fun analyze(
        ref: LocalProjectRef,
        lease: RunLease,
        script: String,
        expectedRevision: Long,
        allowFetch: Boolean,
        inputJson: String,
        cancellation: WorkspaceCancellation,
        policy: WorkspaceSkillPolicy,
    ): ProjectWorkspaceAnalysisRun
}

/** Resolves and revalidates the exact enabled skill/script already frozen by the request. */
internal fun interface NativeWorkspaceSkillSource {
    suspend fun resolve(
        request: ForegroundWorkspaceRequest,
        ceiling: WorkspaceSkillPolicy,
    ): WorkspaceSkillScriptExecution
}

/**
 * Executes one directly requested skill script through the existing durable Run executor and the
 * existing project workspace analysis bridge.
 *
 * The request is fully fixed before the Service is started: project/instance/generation, Run and
 * command identity, skill/entry point, script SHA-256 and size, project revision, policy, input and
 * fetch permission. The adapter re-reads the current project revision, re-resolves the enabled
 * skill through its stored policy narrowed by the frozen request policy, and rechecks the exact
 * script fingerprint before reserving or running anything. It never calls a model, never writes
 * project data and never replays a completed Run.
 */
internal class NativeWorkspaceExecutionAdapter(
    private val runtime: RuntimePort,
    private val runs: DurableRunExecutor,
    private val projectRevision: suspend (LocalProjectRef) -> Long,
    private val skills: NativeWorkspaceSkillSource,
    private val analysis: NativeWorkspaceAnalysisPort,
) : WorkspaceExecutionPort {
    override suspend fun execute(
        request: ForegroundWorkspaceRequest,
        observer: ForegroundWorkspaceObserver,
    ): ForegroundWorkspaceExecutionResult {
        val currentRevision = projectRevision(request.ref)
        require(currentRevision == request.projectRevision) { "Project revision changed" }
        val ceiling = request.policy.toWorkspaceSkillPolicy()
        val execution = skills.resolve(request, ceiling)
        val source = execution.source
        require(source.scriptId == request.skillId) { "Skill identity changed" }
        require(source.entryPoint == request.entryPoint) { "Skill entry point changed" }
        require(source.sha256 == request.scriptSha256) { "Skill script changed" }
        require(source.sizeBytes == request.scriptSizeBytes) { "Skill script size changed" }
        require(!request.allowFetch || execution.policy.allowFetch) {
            "Network access was not granted by the skill policy"
        }
        val chat = requireNotNull(request.identity.chatId) { "A skill Run requires a Chat" }
        val cancellation = WorkspaceCancellation()
        observer.stage(ForegroundWorkspaceStage.PREFLIGHT)
        runtime.selectChat(ChatIdentity(request.ref.projectId, chat))
        try {
            return runs.execute(request.ref, request.identity, RuntimeOwner.AGENT) { lease, _ ->
                val operation = runtime.beginOperation(lease, OperationKind.SCRIPT)
                try {
                    observer.stage(ForegroundWorkspaceStage.SCRIPT)
                    val run = withCancellationToken(cancellation) {
                        analysis.analyze(
                            ref = request.ref,
                            lease = lease,
                            script = source.text,
                            expectedRevision = request.projectRevision,
                            allowFetch = request.allowFetch,
                            inputJson = request.inputJson,
                            cancellation = cancellation,
                            policy = execution.policy,
                        )
                    }
                    observer.stage(ForegroundWorkspaceStage.OUTPUT_VALIDATION)
                    run.result.logs.forEach { observer.log(it.text) }
                    if (run.result.exitCode != 0) {
                        throw IllegalStateException("Skill exited with ${run.result.exitCode}")
                    }
                    check(!run.result.outputTruncated) { "Skill output was truncated" }
                    observer.stage(ForegroundWorkspaceStage.ARTIFACTS)
                    val artifacts = run.artifacts.map { artifact ->
                        ForegroundWorkspaceArtifact(
                            path = artifact.path.value,
                            sizeBytes = artifact.sizeBytes,
                            sha256 = artifact.sha256,
                        ).also(observer::artifact)
                    }
                    observer.stage(ForegroundWorkspaceStage.FINISHING)
                    ForegroundWorkspaceExecutionResult(
                        summary = "exit=${run.result.exitCode}, artifacts=${artifacts.size}",
                        artifacts = artifacts,
                    )
                } finally {
                    runtime.endOperation(operation)
                }
            }
        } finally {
            try {
                if (runtime.snapshot().activeRun == null) runtime.selectChat(null)
            } catch (_: Exception) {
                // A racing reservation owns the selection; it must not be cleared here.
            }
        }
    }

    /**
     * Mirrors cancellation of the execution coroutine into the cooperative workspace token. The
     * production analysis already links its own job, but an injected adapter/test port must still
     * observe the same explicit cancellation contract.
     */
    private suspend fun <T> withCancellationToken(
        cancellation: WorkspaceCancellation,
        block: suspend () -> T,
    ): T = coroutineScope {
        val bridge = launch {
            try {
                awaitCancellation()
            } finally {
                cancellation.cancel()
            }
        }
        try {
            block()
        } finally {
            bridge.cancel()
        }
    }
}

internal fun ForegroundWorkspacePolicy.toWorkspaceSkillPolicy(): WorkspaceSkillPolicy = WorkspaceSkillPolicy(
    allowMaterials = allowMaterials,
    allowArtifacts = allowArtifacts,
    allowSearch = allowSearch,
    allowFetch = allowFetch,
    allowProposeChanges = allowProposeChanges,
    allowPrint = allowPrint,
    maxRunMillis = maxRunMillis,
    maxOutputBytes = maxOutputBytes,
    maxArtifactBytes = maxArtifactBytes,
    maxChangeBytes = maxChangeBytes,
)

internal fun WorkspaceSkillPolicy.toForegroundWorkspacePolicy(): ForegroundWorkspacePolicy =
    ForegroundWorkspacePolicy(
        allowMaterials = allowMaterials,
        allowArtifacts = allowArtifacts,
        allowSearch = allowSearch,
        allowFetch = allowFetch,
        allowProposeChanges = allowProposeChanges,
        allowPrint = allowPrint,
        maxRunMillis = maxRunMillis,
        maxOutputBytes = maxOutputBytes,
        maxArtifactBytes = maxArtifactBytes,
        maxChangeBytes = maxChangeBytes,
    )
