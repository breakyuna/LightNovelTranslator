package com.breakyuna.noveltranslator.nativecore.workspace

import java.util.concurrent.CancellationException

/**
 * A skill script source that passed the skill's enabled check and policy ceiling.
 *
 * [policy] is the effective policy for this execution: the skill's stored policy narrowed by
 * any caller ceiling. It is a capability/resource ceiling only. It never approves a workspace
 * change and never authorizes a project write; the normal approval pipeline still applies.
 */
class WorkspaceSkillScriptExecution(
    val skillId: WorkspaceSkillId,
    val source: WorkspaceScriptSource,
    val policy: WorkspaceSkillPolicy,
    val fingerprint: WorkspaceDigest,
) {
    val capabilities: Set<WorkspaceCapability> get() = policy.grantedCapabilities

    override fun toString(): String =
        "WorkspaceSkillScriptExecution(skill=${skillId.value}, entry=${source.entryPoint}, " +
            "maxRunMillis=${policy.maxRunMillis})"
}

/**
 * A skill script request refused before execution because the skill is disabled or its policy
 * does not grant a capability the caller requires. The message contains only the opaque skill
 * id and capability names, never source text or filesystem paths.
 */
class WorkspaceSkillScriptPolicyDenied(
    val reason: Reason,
    skillId: WorkspaceSkillId? = null,
    capabilities: Set<WorkspaceCapability> = emptySet(),
) : SecurityException("Skill script policy denied: $reason") {
    val skillId: WorkspaceSkillId? = skillId
    val capabilities: Set<WorkspaceCapability> = workspaceFrozenSet(capabilities)

    enum class Reason {
        DISABLED,
        CAPABILITY_DENIED,
    }

    init {
        require(reason != Reason.CAPABILITY_DENIED || this.capabilities.isNotEmpty()) {
            "Capability denial requires at least one capability"
        }
    }

    override fun toString(): String = buildString {
        append("WorkspaceSkillScriptPolicyDenied(").append(reason)
        skillId?.let { append(", id=").append(it.value) }
        if (capabilities.isNotEmpty()) append(", capabilities=").append(capabilities)
        append(')')
    }
}

/**
 * Resolves a skill script through the normal [WorkspaceSkillScriptSource] checks (enabled state,
 * bounded UTF-8, private package path) and then applies the skill policy.
 *
 * Disabled skills are refused even if the caller only asks for the script body. The effective
 * policy is the stored skill policy restricted by [ceiling] when one is supplied; a caller can
 * narrow a skill but never elevate it. [requiredCapabilities] lets an execution entry point
 * declare what the script is about to do; a denied capability is reported as
 * [WorkspaceSkillScriptPolicyDenied] instead of silently running a no-op script.
 */
suspend fun WorkspaceSkillScriptSource.resolveForExecution(
    catalog: WorkspaceSkillCatalog,
    scriptId: String,
    entryPoint: String,
    ceiling: WorkspaceSkillPolicy? = null,
    requiredCapabilities: Set<WorkspaceCapability> = emptySet(),
): WorkspaceSkillScriptExecution {
    val id = try {
        WorkspaceSkillId(scriptId)
    } catch (_: IllegalArgumentException) {
        throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID)
    }
    val record = try {
        catalog.record(id)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.READ_FAILED)
    } ?: throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND)
    if (!record.summary.enabled) {
        throw WorkspaceSkillScriptPolicyDenied(WorkspaceSkillScriptPolicyDenied.Reason.DISABLED, id)
    }
    val policy = if (ceiling == null) {
        record.summary.policy
    } else {
        record.summary.policy.restrictedBy(ceiling)
    }
    val missing = requiredCapabilities.filterNot(policy::grants).toSet()
    if (missing.isNotEmpty()) {
        // Refuse before reading source bytes when the entry point already knows it needs a
        // capability the skill policy denies.
        throw WorkspaceSkillScriptPolicyDenied(
            reason = WorkspaceSkillScriptPolicyDenied.Reason.CAPABILITY_DENIED,
            skillId = id,
            capabilities = missing,
        )
    }
    // The source resolver rechecks enabled state and performs the actual package read.
    val source = resolve(scriptId, entryPoint)
    return WorkspaceSkillScriptExecution(id, source, policy, record.fingerprint)
}

/**
 * [WorkspaceScriptSourcePort] adapter that keeps every skill execution under its stored policy.
 * Existing callers can replace [WorkspaceSkillScriptSource] with this class to get policy
 * enforcement without changing the engine contract. The optional [ceiling] is an additional
 * caller limit, not a grant; [requiredCapabilities] is the minimum the execution entry needs.
 *
 * [resolve] can only reject a denied script; it cannot rewrite the engine's capability set.
 * Callers should use [resolveForExecution] and pass the returned policy to the analysis/runtime
 * entry point so the same ceiling also applies to capabilities, timeout and output budgets.
 */
class WorkspaceSkillPolicyScriptSource(
    private val catalog: WorkspaceSkillCatalog,
    private val files: WorkspaceSkillFilePort,
    private val ceiling: WorkspaceSkillPolicy? = null,
    requiredCapabilities: Set<WorkspaceCapability> = emptySet(),
) : WorkspaceScriptSourcePort {
    private val scripts = WorkspaceSkillScriptSource(catalog, files)
    private val requiredCapabilities: Set<WorkspaceCapability> = workspaceFrozenSet(requiredCapabilities)

    override suspend fun resolve(scriptId: String, entryPoint: String): WorkspaceScriptSource =
        resolveForExecution(scriptId, entryPoint).source

    suspend fun resolveForExecution(
        scriptId: String,
        entryPoint: String,
        requiredCapabilities: Set<WorkspaceCapability>? = null,
    ): WorkspaceSkillScriptExecution = scripts.resolveForExecution(
        catalog = catalog,
        scriptId = scriptId,
        entryPoint = entryPoint,
        ceiling = ceiling,
        requiredCapabilities = requiredCapabilities ?: this.requiredCapabilities,
    )
}

/**
 * Pure policy arithmetic shared by the analysis bridge and the runtime. It only narrows
 * capability sets and budgets; it never creates an approval or a project write permission.
 */
internal object WorkspaceSkillPolicyEnforcement {
    fun capabilities(
        requested: Set<WorkspaceCapability>,
        policy: WorkspaceSkillPolicy,
    ): Set<WorkspaceCapability> = workspaceFrozenSet(requested.intersect(policy.grantedCapabilities))

    fun budget(base: WorkspaceBudget, policy: WorkspaceSkillPolicy): WorkspaceBudget {
        val changeBytes = minOf(base.maxChangeBytes, policy.maxChangeBytes.toLong())
        val artifactBytes = minOf(base.maxArtifactBytes, policy.maxArtifactBytes.toLong())
        return WorkspaceBudget(
            maxMaterials = base.maxMaterials,
            maxMaterialBytes = base.maxMaterialBytes,
            maxTotalMaterialBytes = base.maxTotalMaterialBytes,
            maxChanges = base.maxChanges,
            maxChangeBytes = changeBytes,
            maxTotalChangeBytes = maxOf(base.maxTotalChangeBytes, changeBytes),
            maxArtifacts = base.maxArtifacts,
            maxArtifactBytes = artifactBytes,
            maxTotalArtifactBytes = maxOf(base.maxTotalArtifactBytes, artifactBytes),
            maxOutputBytes = minOf(base.maxOutputBytes, policy.maxOutputBytes.toLong()),
            maxLogLines = base.maxLogLines,
            // The timeout is carried separately because a skill policy may legally be below
            // WorkspaceBudget's one-second minimum. [timeoutMillis] applies the real ceiling.
            timeoutMillis = base.timeoutMillis,
            maxResidentBytes = base.maxResidentBytes,
            maxPeakHeapBytes = base.maxPeakHeapBytes,
        )
    }

    fun timeoutMillis(base: Long, policy: WorkspaceSkillPolicy): Long =
        minOf(base, policy.maxRunMillis.toLong())

    /** Both a skill policy and a run policy are ceilings, so the narrower value wins. */
    fun merge(skill: WorkspaceSkillPolicy?, run: WorkspaceSkillPolicy?): WorkspaceSkillPolicy? = when {
        skill == null -> run
        run == null -> skill
        else -> skill.restrictedBy(run)
    }
}
