package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

fun interface WorkspaceClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: WorkspaceClock = WorkspaceClock { System.currentTimeMillis() }
    }
}

/**
 * Bridges structured-coroutine cancellation into a workspace token. The handler is invoked
 * only for a non-null completion cause, so normal completion never cancels a token.
 */
internal fun linkWorkspaceCancellation(
    cancellation: WorkspaceCancellation,
    job: Job?,
): DisposableHandle? = job?.invokeOnCompletion { failure ->
    if (failure != null) cancellation.cancel()
}

/**
 * Disposes a bridge after a normal run. A cancelled job keeps its handler until completion so
 * the completion callback can still mark the workspace token; the job releases it afterwards.
 */
internal fun releaseWorkspaceCancellation(handle: DisposableHandle?, job: Job?) {
    if (job == null || !job.isCancelled) handle?.dispose()
}

/** Stage at which a run stopped; used by cancellation, timeout and budget outcomes. */
enum class WorkspaceRunStage {
    MATERIALS,
    SCRIPT,
    OUTPUT_VALIDATION,
    PREFLIGHT,
    APPROVAL,
    APPLY,
    ARTIFACTS,
}

enum class WorkspaceOutputViolation {
    TRUNCATED_OUTPUT,
    CHANGES_WITHOUT_CAPABILITY,
    ARTIFACTS_WITHOUT_CAPABILITY,
    INVALID_ARTIFACT_PATH,
    INVALID_CHANGE_SET,
}

enum class WorkspaceApplyIndeterminateReason {
    STORE_EXCEPTION,
    RECEIPT_MISMATCH,
    CANCELLED_DURING_APPLY,
    TIMED_OUT_DURING_APPLY,
}

enum class WorkspaceArtifactFailureReason { CANCELLED, TIMED_OUT, SAVE_FAILED }

/**
 * One run request. Identity fields are supplied by the caller (command/run ledger), so a
 * repeated command can be deduplicated by the apply adapter instead of writing twice.
 */
class WorkspaceRunRequest(
    val commandId: CommandId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    val changeSetId: WorkspaceChangeSetId,
    val scriptId: String,
    val entryPoint: String,
    arguments: Map<String, String> = emptyMap(),
    materialIds: List<WorkspaceMaterialId> = emptyList(),
    grantedCapabilities: Set<WorkspaceCapability> = setOf(
        WorkspaceCapability.READ_MATERIALS,
        WorkspaceCapability.PROPOSE_CHANGES,
        WorkspaceCapability.EMIT_ARTIFACTS,
    ),
    val budget: WorkspaceBudget = WorkspaceBudget(),
    val conflictPolicy: WorkspaceConflictPolicy = WorkspaceConflictPolicy.REJECT_BATCH,
    /**
     * Optional skill policy carried with the run. It narrows [grantedCapabilities] and the
     * timeout/output/artifact/change ceilings; it is never an approval or a write grant.
     */
    val skillPolicy: WorkspaceSkillPolicy? = null,
) {
    val arguments: Map<String, String> = workspaceFrozenMap(arguments)
    val materialIds: List<WorkspaceMaterialId> = workspaceFrozenList(materialIds)
    val grantedCapabilities: Set<WorkspaceCapability> = workspaceFrozenSet(grantedCapabilities)

    init {
        require(scriptId.isNotBlank() && scriptId.length <= 256) { "Invalid script identity" }
        require(scriptId.none { it.isISOControl() }) { "Control character in script identity" }
        require(entryPoint.isNotBlank() && entryPoint.length <= 512) { "Invalid entry point" }
        require(entryPoint.none { it.isISOControl() }) { "Control character in entry point" }
        require(this.arguments.size <= 64) { "Too many script arguments" }
        this.arguments.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= 128) { "Invalid script argument" }
            require(key.none { it.isISOControl() }) { "Control character in script argument" }
            require(value.length <= 4_096 && '\u0000' !in value) { "Invalid script argument value" }
        }
        require(this.materialIds.size <= WorkspaceHardLimits.MAX_MATERIALS) { "Too many materials" }
        require(this.materialIds.toSet().size == this.materialIds.size) { "Duplicate material identity" }
        require(this.materialIds.isEmpty() || WorkspaceCapability.READ_MATERIALS in this.grantedCapabilities) {
            "Materials require the read capability"
        }
    }

    override fun toString(): String =
        "WorkspaceRunRequest(run=${runId.value}, script=$scriptId, materials=${materialIds.size})"
}

sealed interface WorkspaceRunOutcome {
    /**
     * Terminal success, possibly with no project change. A null [receipt] means nothing was
     * submitted; a non-null [receipt] is durable evidence of what was committed.
     */
    class Completed(
        val runId: RunId,
        val changeSetId: WorkspaceChangeSetId?,
        val digest: WorkspaceDigest?,
        val receipt: WorkspaceApplyReceipt?,
        artifacts: List<WorkspaceArtifactSummary>,
        val artifactFailure: WorkspaceArtifactFailureReason?,
        logs: List<WorkspaceLogLine>,
        val outputTruncated: Boolean,
    ) : WorkspaceRunOutcome {
        val artifacts: List<WorkspaceArtifactSummary> = workspaceFrozenList(artifacts)
        val logs: List<WorkspaceLogLine> = workspaceFrozenList(logs)

        init {
            require((changeSetId == null) == (digest == null)) { "Incomplete change set identity" }
            val applied = receipt
            require(applied == null || (applied.changeSetId == changeSetId && applied.digest == digest)) {
                "Receipt does not belong to this run"
            }
        }
    }

    class ScriptFailed(
        val runId: RunId,
        val exitCode: Int,
        logs: List<WorkspaceLogLine>,
    ) : WorkspaceRunOutcome {
        val logs: List<WorkspaceLogLine> = workspaceFrozenList(logs)
        init { require(exitCode != 0) { "A successful script does not fail" } }
    }

    class ScriptRejected(val runId: RunId, val violation: WorkspaceOutputViolation) : WorkspaceRunOutcome

    class Rejected(
        val runId: RunId,
        val approvalRequestId: ApprovalRequestId?,
        val reason: WorkspaceRejectionReason,
    ) : WorkspaceRunOutcome

    class StoreRejected(val runId: RunId, val receipt: WorkspaceApplyReceipt) : WorkspaceRunOutcome {
        init { require(receipt.status == WorkspaceApplyStatus.REJECTED) { "Not a rejected receipt" } }
    }

    class Conflicts(val runId: RunId, conflicts: List<WorkspaceConflict>) : WorkspaceRunOutcome {
        val conflicts: List<WorkspaceConflict> = workspaceFrozenList(conflicts)
        init { require(this.conflicts.isNotEmpty()) { "An empty conflict list is not a conflict" } }
    }

    class BudgetBlocked(
        val runId: RunId,
        val kind: WorkspaceBudgetKind,
        val stage: WorkspaceRunStage,
    ) : WorkspaceRunOutcome

    class TimedOut(val runId: RunId, val stage: WorkspaceRunStage) : WorkspaceRunOutcome

    class Cancelled(val runId: RunId, val stage: WorkspaceRunStage) : WorkspaceRunOutcome

    class MaterialUnavailable(val runId: RunId, val materialId: WorkspaceMaterialId) : WorkspaceRunOutcome

    class EngineFailed(val runId: RunId) : WorkspaceRunOutcome

    class SnapshotFailed(val runId: RunId) : WorkspaceRunOutcome

    class ApprovalUnavailable(
        val runId: RunId,
        val reason: WorkspaceApprovalUnavailableReason,
    ) : WorkspaceRunOutcome

    class ApprovalMismatch(val runId: RunId) : WorkspaceRunOutcome

    class ApplyIndeterminate(
        val runId: RunId,
        val reason: WorkspaceApplyIndeterminateReason,
    ) : WorkspaceRunOutcome
}

/**
 * Orchestrates one workspace run:
 *
 * materials (metadata only) -> bounded script execution -> output validation -> fingerprint
 * preflight -> approval -> single apply through [WorkspaceApplyPort] -> artifact publication.
 *
 * No step writes project data on its own. Every pre-commit stage checks cancellation, the
 * deadline and the budget, including a cooperative used-heap sample; that sample is not a hard
 * memory cap. A failure before [WorkspaceApplyPort.apply] cannot mutate the project. If the
 * apply adapter throws, the outcome is [WorkspaceRunOutcome.ApplyIndeterminate] rather than a
 * claim that nothing was committed. Post-commit sampling only observes memory so a durable
 * receipt is never replaced by a late budget outcome. Timeout/cancellation of a non-cooperative
 * engine cannot be enforced by this class; a late engine result is discarded without approval.
 *
 * Artifact-only runs still require approval (their manifest digest is approved) but skip the
 * apply port because they produce no project mutation. An optional [WorkspaceSkillPolicy] is a
 * capability/resource ceiling only: it never replaces approval and never grants a project write.
 */
class WorkspaceRuntime(
    private val materials: WorkspaceMaterialReadPort,
    private val engine: WorkspaceScriptEngine,
    private val snapshots: WorkspaceSnapshotPort,
    private val approvals: WorkspaceApprovalPort,
    private val applyPort: WorkspaceApplyPort,
    private val artifactSink: WorkspaceArtifactSink,
    private val clock: WorkspaceClock = WorkspaceClock.SYSTEM,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val memorySampler: WorkspaceMemorySampler = WorkspaceMemorySampler.HEAP,
) {
    /** Keeps the pre-memory-budget constructor shape available to already compiled callers. */
    constructor(
        materials: WorkspaceMaterialReadPort,
        engine: WorkspaceScriptEngine,
        snapshots: WorkspaceSnapshotPort,
        approvals: WorkspaceApprovalPort,
        applyPort: WorkspaceApplyPort,
        artifactSink: WorkspaceArtifactSink,
        clock: WorkspaceClock,
        policy: PathPolicy,
    ) : this(
        materials = materials,
        engine = engine,
        snapshots = snapshots,
        approvals = approvals,
        applyPort = applyPort,
        artifactSink = artifactSink,
        clock = clock,
        policy = policy,
        memorySampler = WorkspaceMemorySampler.HEAP,
    )

    /** Legacy entry point; behavior is unchanged when no skill policy is supplied. */
    suspend fun run(
        request: WorkspaceRunRequest,
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
    ): WorkspaceRunOutcome = run(request, null, cancellation)

    /**
     * Runs one request under an additional [policy] ceiling. The request's own
     * [WorkspaceRunRequest.skillPolicy] is also honored; when both exist the narrower one wins.
     * The policy is applied before materials/script execution and never replaces approval.
     */
    suspend fun run(
        request: WorkspaceRunRequest,
        policy: WorkspaceSkillPolicy?,
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
    ): WorkspaceRunOutcome {
        val job = currentCoroutineContext()[Job]
        val bridge = linkWorkspaceCancellation(cancellation, job)
        try {
            return runInternal(request, policy, cancellation)
        } finally {
            releaseWorkspaceCancellation(bridge, job)
        }
    }

    /**
     * Applies the skill policy before any material read or script execution. A denied
     * READ_MATERIALS capability drops material descriptors so the script cannot observe them;
     * other denied capabilities simply disappear from the script request.
     */
    private fun prepare(
        request: WorkspaceRunRequest,
        runPolicy: WorkspaceSkillPolicy?,
    ): PreparedRun {
        val effectivePolicy = WorkspaceSkillPolicyEnforcement.merge(request.skillPolicy, runPolicy)
        val capabilities = if (effectivePolicy == null) {
            request.grantedCapabilities
        } else {
            WorkspaceSkillPolicyEnforcement.capabilities(request.grantedCapabilities, effectivePolicy)
        }
        val materialIds = if (WorkspaceCapability.READ_MATERIALS in capabilities) {
            request.materialIds
        } else {
            emptyList<WorkspaceMaterialId>()
        }
        val budget = if (effectivePolicy == null) {
            request.budget
        } else {
            WorkspaceSkillPolicyEnforcement.budget(request.budget, effectivePolicy)
        }
        val timeoutMillis = if (effectivePolicy == null) {
            request.budget.timeoutMillis
        } else {
            WorkspaceSkillPolicyEnforcement.timeoutMillis(request.budget.timeoutMillis, effectivePolicy)
        }
        return PreparedRun(capabilities, materialIds, budget, timeoutMillis)
    }

    private class PreparedRun(
        val capabilities: Set<WorkspaceCapability>,
        val materialIds: List<WorkspaceMaterialId>,
        val budget: WorkspaceBudget,
        val timeoutMillis: Long,
    )

    private suspend fun runInternal(
        request: WorkspaceRunRequest,
        runPolicy: WorkspaceSkillPolicy?,
        cancellation: WorkspaceCancellation,
    ): WorkspaceRunOutcome {
        val prepared = prepare(request, runPolicy)
        val deadline = saturatingAdd(clock.nowMillis(), prepared.timeoutMillis)
        val meter = WorkspaceBudgetMeter(prepared.budget)
        val memory = WorkspaceMemoryMeter(prepared.budget, memorySampler)
        fun guard(stage: WorkspaceRunStage): WorkspaceRunOutcome? {
            if (cancellation.isCancelled) return WorkspaceRunOutcome.Cancelled(request.runId, stage)
            if (expired(deadline)) return WorkspaceRunOutcome.TimedOut(request.runId, stage)
            return try {
                memory.check()
                null
            } catch (failure: WorkspaceBudgetExceeded) {
                WorkspaceRunOutcome.BudgetBlocked(request.runId, failure.kind, stage)
            }
        }
        fun sampleOnly() {
            // Observation after a commit must never replace the durable receipt with an error.
            try {
                memory.sample()
            } catch (_: Exception) {
                // A sampler failure is not evidence about the committed files.
            }
        }
        val knownMaterials = LinkedHashMap<WorkspaceMaterialId, WorkspaceMaterialSummary>()
        val descriptors = ArrayList<WorkspaceMaterialDescriptor>(prepared.materialIds.size)

        guard(WorkspaceRunStage.MATERIALS)?.let { return it }

        prepared.materialIds.forEach { id ->
            if (cancellation.isCancelled) return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.MATERIALS)
            if (expired(deadline)) return WorkspaceRunOutcome.TimedOut(request.runId, WorkspaceRunStage.MATERIALS)
            val summary = try {
                materials.summary(id)
            } catch (failure: CancellationException) {
                return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.MATERIALS)
            } catch (failure: Exception) {
                return WorkspaceRunOutcome.MaterialUnavailable(request.runId, id)
            } ?: return WorkspaceRunOutcome.MaterialUnavailable(request.runId, id)
            try {
                meter.recordMaterial(summary.sizeBytes)
            } catch (failure: WorkspaceBudgetExceeded) {
                return WorkspaceRunOutcome.BudgetBlocked(request.runId, failure.kind, WorkspaceRunStage.MATERIALS)
            }
            knownMaterials[summary.id] = summary
            descriptors += summary.descriptor()
        }

        guard(WorkspaceRunStage.MATERIALS)?.let { return it }
        guard(WorkspaceRunStage.SCRIPT)?.let { return it }

        val scriptRequest = WorkspaceScriptRequest(
            commandId = request.commandId,
            projectId = request.projectId,
            chatId = request.chatId,
            runId = request.runId,
            scriptId = request.scriptId,
            entryPoint = request.entryPoint,
            arguments = request.arguments,
            materials = descriptors,
            grantedCapabilities = prepared.capabilities,
            limits = WorkspaceScriptLimits.of(prepared.budget),
            deadlineAtMillis = deadline,
            cancellation = cancellation,
        )
        val result = try {
            engine.execute(scriptRequest)
        } catch (failure: CancellationException) {
            return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.SCRIPT)
        } catch (failure: WorkspaceScriptTimeoutException) {
            return WorkspaceRunOutcome.TimedOut(request.runId, WorkspaceRunStage.SCRIPT)
        } catch (failure: Exception) {
            return WorkspaceRunOutcome.EngineFailed(request.runId)
        }
        guard(WorkspaceRunStage.SCRIPT)?.let { return it }
        if (result.exitCode != 0) {
            return WorkspaceRunOutcome.ScriptFailed(request.runId, result.exitCode, result.logs)
        }
        if (result.outputTruncated) {
            return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.TRUNCATED_OUTPUT)
        }
        if (result.changes.isNotEmpty() && WorkspaceCapability.PROPOSE_CHANGES !in prepared.capabilities) {
            return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.CHANGES_WITHOUT_CAPABILITY)
        }
        if (result.artifacts.isNotEmpty() && WorkspaceCapability.EMIT_ARTIFACTS !in prepared.capabilities) {
            return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.ARTIFACTS_WITHOUT_CAPABILITY)
        }
        guard(WorkspaceRunStage.OUTPUT_VALIDATION)?.let { return it }
        val changePaths = result.changes.map { policy.identityKey(it.path) }.toSet()
        val artifactPaths = HashSet<String>(result.artifacts.size * 2)
        result.artifacts.forEach { artifact ->
            if (cancellation.isCancelled) {
                return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.OUTPUT_VALIDATION)
            }
            try {
                policy.parse(artifact.path.value)
            } catch (failure: InvalidWorkspacePath) {
                return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.INVALID_ARTIFACT_PATH)
            }
            val artifactKey = policy.identityKey(artifact.path)
            if (!artifactPaths.add(artifactKey) || artifactKey in changePaths) {
                return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.INVALID_ARTIFACT_PATH)
            }
        }
        try {
            result.changes.forEach {
                if (cancellation.isCancelled) {
                    return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.OUTPUT_VALIDATION)
                }
                meter.recordChange(it.sizeBytes)
            }
            result.artifacts.forEach {
                if (cancellation.isCancelled) {
                    return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.OUTPUT_VALIDATION)
                }
                meter.recordArtifact(it.sizeBytes)
            }
            result.logs.forEach {
                if (cancellation.isCancelled) {
                    return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.OUTPUT_VALIDATION)
                }
                meter.recordLogLine(it.text)
            }
        } catch (failure: WorkspaceBudgetExceeded) {
            return WorkspaceRunOutcome.BudgetBlocked(request.runId, failure.kind, WorkspaceRunStage.OUTPUT_VALIDATION)
        }

        guard(WorkspaceRunStage.OUTPUT_VALIDATION)?.let { return it }
        guard(WorkspaceRunStage.PREFLIGHT)?.let { return it }

        val preflight = try {
            WorkspaceChangePreflight.inspect(
                changes = result.changes,
                materials = knownMaterials,
                snapshots = snapshots,
                budget = prepared.budget,
                policy = policy,
                cancellation = cancellation,
            )
        } catch (failure: CancellationException) {
            return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.PREFLIGHT)
        } catch (failure: WorkspacePreflightFailure) {
            return WorkspaceRunOutcome.SnapshotFailed(request.runId)
        } catch (failure: Exception) {
            return WorkspaceRunOutcome.SnapshotFailed(request.runId)
        }
        if (preflight.hasConflicts) {
            return WorkspaceRunOutcome.Conflicts(request.runId, preflight.conflicts)
        }
        guard(WorkspaceRunStage.PREFLIGHT)?.let { return it }

        val changeSet = if (result.changes.isEmpty()) null else {
            try {
                WorkspaceChangeSet(
                    id = request.changeSetId,
                    projectId = request.projectId,
                    chatId = request.chatId,
                    runId = request.runId,
                    changes = result.changes,
                    policy = policy,
                )
            } catch (failure: InvalidWorkspaceChangeSet) {
                return WorkspaceRunOutcome.ScriptRejected(request.runId, WorkspaceOutputViolation.INVALID_CHANGE_SET)
            }
        }

        if (changeSet == null && result.artifacts.isEmpty()) {
            return WorkspaceRunOutcome.Completed(
                runId = request.runId,
                changeSetId = null,
                digest = null,
                receipt = null,
                artifacts = emptyList(),
                artifactFailure = null,
                logs = result.logs,
                outputTruncated = false,
            )
        }

        guard(WorkspaceRunStage.APPROVAL)?.let { return it }

        val approvalDigest = changeSet?.digest ?: artifactManifestDigest(request, result.artifacts)
        val approvalRequest = WorkspaceApprovalRequest(
            commandId = request.commandId,
            projectId = request.projectId,
            chatId = request.chatId,
            runId = request.runId,
            changeSet = changeSet,
            digest = approvalDigest,
            changeSummary = changeSet?.summary ?: WorkspaceChangeSummary(0, 0, 0, 0),
            artifacts = result.artifacts.map { it.path },
            deadlineAtMillis = deadline,
        )
        val decision = try {
            approvals.request(approvalRequest, cancellation)
        } catch (failure: CancellationException) {
            return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.APPROVAL)
        } catch (failure: Exception) {
            return WorkspaceRunOutcome.ApprovalUnavailable(request.runId, WorkspaceApprovalUnavailableReason.PERSISTENCE)
        }
        guard(WorkspaceRunStage.APPROVAL)?.let { return it }
        val approved = when (decision) {
            is WorkspaceApprovalDecision.Rejected ->
                return WorkspaceRunOutcome.Rejected(request.runId, decision.requestId, decision.reason)
            is WorkspaceApprovalDecision.Unavailable ->
                return WorkspaceRunOutcome.ApprovalUnavailable(request.runId, decision.reason)
            is WorkspaceApprovalDecision.Approved -> {
                if (decision.digest != approvalDigest) return WorkspaceRunOutcome.ApprovalMismatch(request.runId)
                if (changeSet == null) null else ApprovedWorkspaceChangeSet(
                    commandId = request.commandId,
                    changeSet = changeSet,
                    approvalRequestId = decision.requestId,
                    approvedAtMillis = decision.approvedAtMillis,
                )
            }
        }

        var receipt: WorkspaceApplyReceipt? = null
        if (changeSet != null) {
            guard(WorkspaceRunStage.APPLY)?.let { return it }
            val applied = try {
                val port = applyPort
                if (port is WorkspaceApplyCancellationPort) {
                    port.apply(approved!!, request.conflictPolicy, cancellation, deadline)
                } else {
                    port.apply(approved!!, request.conflictPolicy)
                }
            } catch (failure: WorkspaceScriptTimeoutException) {
                return WorkspaceRunOutcome.ApplyIndeterminate(
                    request.runId, WorkspaceApplyIndeterminateReason.TIMED_OUT_DURING_APPLY,
                )
            } catch (failure: CancellationException) {
                return WorkspaceRunOutcome.ApplyIndeterminate(
                    request.runId, WorkspaceApplyIndeterminateReason.CANCELLED_DURING_APPLY,
                )
            } catch (failure: Exception) {
                return WorkspaceRunOutcome.ApplyIndeterminate(
                    request.runId, WorkspaceApplyIndeterminateReason.STORE_EXCEPTION,
                )
            }
            // A committed receipt is a terminal fact. Post-stage observation cannot turn it
            // into a budget/timeout failure that would hide the commit.
            sampleOnly()
            if (applied.commandId != request.commandId ||
                applied.changeSetId != changeSet.id ||
                applied.digest != changeSet.digest
            ) {
                return WorkspaceRunOutcome.ApplyIndeterminate(
                    request.runId, WorkspaceApplyIndeterminateReason.RECEIPT_MISMATCH,
                )
            }
            if (applied.status == WorkspaceApplyStatus.REJECTED) {
                return WorkspaceRunOutcome.StoreRejected(request.runId, applied)
            }
            receipt = applied
        }

        var savedArtifacts: List<WorkspaceArtifactSummary> = emptyList()
        var artifactFailure: WorkspaceArtifactFailureReason? = null
        if (result.artifacts.isNotEmpty()) {
            if (receipt == null) {
                guard(WorkspaceRunStage.ARTIFACTS)?.let { return it }
            } else {
                sampleOnly()
            }
            if (cancellation.isCancelled) {
                if (receipt == null) return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.ARTIFACTS)
                artifactFailure = WorkspaceArtifactFailureReason.CANCELLED
            } else if (expired(deadline)) {
                if (receipt == null) return WorkspaceRunOutcome.TimedOut(request.runId, WorkspaceRunStage.ARTIFACTS)
                artifactFailure = WorkspaceArtifactFailureReason.TIMED_OUT
            } else {
                savedArtifacts = try {
                    artifactSink.save(request.runId, result.artifacts)
                } catch (failure: CancellationException) {
                    if (receipt == null) return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.ARTIFACTS)
                    artifactFailure = WorkspaceArtifactFailureReason.CANCELLED
                    emptyList()
                } catch (failure: Exception) {
                    artifactFailure = WorkspaceArtifactFailureReason.SAVE_FAILED
                    emptyList()
                }
                if (artifactFailure == null && cancellation.isCancelled) {
                    if (receipt == null) return WorkspaceRunOutcome.Cancelled(request.runId, WorkspaceRunStage.ARTIFACTS)
                    artifactFailure = WorkspaceArtifactFailureReason.CANCELLED
                    savedArtifacts = emptyList()
                } else if (artifactFailure == null && expired(deadline)) {
                    if (receipt == null) return WorkspaceRunOutcome.TimedOut(request.runId, WorkspaceRunStage.ARTIFACTS)
                    artifactFailure = WorkspaceArtifactFailureReason.TIMED_OUT
                    savedArtifacts = emptyList()
                }
                if (artifactFailure == null && savedArtifacts.size != result.artifacts.size) {
                    artifactFailure = WorkspaceArtifactFailureReason.SAVE_FAILED
                    savedArtifacts = emptyList()
                }
            }
            sampleOnly()
        }
        sampleOnly()
        return WorkspaceRunOutcome.Completed(
            runId = request.runId,
            changeSetId = changeSet?.id,
            digest = changeSet?.digest,
            receipt = receipt,
            artifacts = savedArtifacts,
            artifactFailure = artifactFailure,
            logs = result.logs,
            outputTruncated = false,
        )
    }

    private fun expired(deadline: Long): Boolean = clock.nowMillis() > deadline

    private fun artifactManifestDigest(
        request: WorkspaceRunRequest,
        artifacts: List<WorkspaceArtifactDraft>,
    ): WorkspaceDigest {
        val builder = StringBuilder(128 + artifacts.size * 96)
        builder.append("lnt-workspace-artifacts-v1\n")
        builder.append(request.runId.value).append('\n')
        builder.append(request.commandId.value).append('\n')
        artifacts.forEach { artifact ->
            builder.append(artifact.path.value).append('\u0000')
            builder.append(artifact.sha256).append('\u0000')
            builder.append(artifact.sizeBytes).append('\n')
        }
        return WorkspaceDigest(sha256Hex(builder.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
