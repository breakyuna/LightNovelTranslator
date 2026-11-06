package com.breakyuna.noveltranslator.nativecore.workspace

/**
 * Absolute ceilings no workspace configuration may exceed. These are safety limits for the
 * core contract, not measured device budgets (R06 still owns real memory/storage numbers).
 */
object WorkspaceHardLimits {
    const val MAX_MATERIALS: Int = 4_096
    const val MAX_SINGLE_BYTES: Long = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES: Long = 1024L * 1024 * 1024
    const val MAX_CHANGES: Int = 4_096
    const val MAX_CHANGE_BYTES: Long = 64L * 1024 * 1024
    const val MAX_ARTIFACTS: Int = 512
    const val MAX_ARTIFACT_BYTES: Long = 64L * 1024 * 1024
    const val MAX_OUTPUT_BYTES: Long = 16L * 1024 * 1024
    const val MAX_LOG_LINES: Int = 10_000
    const val MAX_LOG_LINE_CHARS: Int = 8_192
    const val MAX_TIMEOUT_MILLIS: Long = 30L * 60 * 1000
    const val MAX_RESIDENT_BYTES: Long = 1024L * 1024 * 1024
    const val MAX_PEAK_HEAP_BYTES: Long = 1536L * 1024 * 1024
}

/**
 * One workspace execution budget. Every field is validated at construction so a caller
 * cannot configure an unbounded run; [WorkspaceBudgetMeter] enforces the same class at run
 * time without giving scripts a mutable budget object.
 *
 * [maxResidentBytes] and [maxPeakHeapBytes] are cooperative observation limits, not an OS or
 * VM hard memory cap. They bound the used Java/Kotlin heap sampled by [WorkspaceMemoryMeter];
 * native allocations, the engine process, and an allocation between two samples are outside
 * that observation. Both fields default conservatively so existing constructions stay valid.
 */
class WorkspaceBudget(
    val maxMaterials: Int = 64,
    val maxMaterialBytes: Long = 32L * 1024 * 1024,
    val maxTotalMaterialBytes: Long = 128L * 1024 * 1024,
    val maxChanges: Int = 256,
    val maxChangeBytes: Long = 16L * 1024 * 1024,
    val maxTotalChangeBytes: Long = 32L * 1024 * 1024,
    val maxArtifacts: Int = 64,
    val maxArtifactBytes: Long = 16L * 1024 * 1024,
    val maxTotalArtifactBytes: Long = 64L * 1024 * 1024,
    val maxOutputBytes: Long = 1L * 1024 * 1024,
    val maxLogLines: Int = 2_000,
    val timeoutMillis: Long = 120_000,
    val maxResidentBytes: Long = 128L * 1024 * 1024,
    val maxPeakHeapBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxMaterials in 1..WorkspaceHardLimits.MAX_MATERIALS)
        require(maxMaterialBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES)
        require(maxTotalMaterialBytes in maxMaterialBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES)
        require(maxChanges in 1..WorkspaceHardLimits.MAX_CHANGES)
        require(maxChangeBytes in 1..WorkspaceHardLimits.MAX_CHANGE_BYTES)
        require(maxTotalChangeBytes in maxChangeBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES)
        require(maxArtifacts in 1..WorkspaceHardLimits.MAX_ARTIFACTS)
        require(maxArtifactBytes in 1..WorkspaceHardLimits.MAX_ARTIFACT_BYTES)
        require(maxTotalArtifactBytes in maxArtifactBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES)
        require(maxOutputBytes in 1..WorkspaceHardLimits.MAX_OUTPUT_BYTES)
        require(maxLogLines in 1..WorkspaceHardLimits.MAX_LOG_LINES)
        require(timeoutMillis in 1_000..WorkspaceHardLimits.MAX_TIMEOUT_MILLIS)
        require(maxResidentBytes in 1..WorkspaceHardLimits.MAX_RESIDENT_BYTES)
        require(maxPeakHeapBytes in 1..WorkspaceHardLimits.MAX_PEAK_HEAP_BYTES)
    }

    /**
     * Keeps the pre-memory-budget constructor shape available to already compiled callers.
     * New code should use the primary constructor and name only the fields it changes.
     */
    constructor(
        maxMaterials: Int,
        maxMaterialBytes: Long,
        maxTotalMaterialBytes: Long,
        maxChanges: Int,
        maxChangeBytes: Long,
        maxTotalChangeBytes: Long,
        maxArtifacts: Int,
        maxArtifactBytes: Long,
        maxTotalArtifactBytes: Long,
        maxOutputBytes: Long,
        maxLogLines: Int,
        timeoutMillis: Long,
    ) : this(
        maxMaterials = maxMaterials,
        maxMaterialBytes = maxMaterialBytes,
        maxTotalMaterialBytes = maxTotalMaterialBytes,
        maxChanges = maxChanges,
        maxChangeBytes = maxChangeBytes,
        maxTotalChangeBytes = maxTotalChangeBytes,
        maxArtifacts = maxArtifacts,
        maxArtifactBytes = maxArtifactBytes,
        maxTotalArtifactBytes = maxTotalArtifactBytes,
        maxOutputBytes = maxOutputBytes,
        maxLogLines = maxLogLines,
        timeoutMillis = timeoutMillis,
        maxResidentBytes = 128L * 1024 * 1024,
        maxPeakHeapBytes = 256L * 1024 * 1024,
    )
}

enum class WorkspaceBudgetKind {
    MATERIALS,
    MATERIAL_BYTES,
    TOTAL_MATERIAL_BYTES,
    CHANGES,
    CHANGE_BYTES,
    TOTAL_CHANGE_BYTES,
    ARTIFACTS,
    ARTIFACT_BYTES,
    TOTAL_ARTIFACT_BYTES,
    OUTPUT_BYTES,
    LOG_LINES,
    RESIDENT_BYTES,
    PEAK_HEAP_BYTES,
}

class WorkspaceBudgetExceeded(
    val kind: WorkspaceBudgetKind,
    val limit: Long,
    val actual: Long,
) : IllegalStateException("Workspace budget exceeded: $kind") {
    override fun toString(): String = "WorkspaceBudgetExceeded($kind, limit=$limit, actual=$actual)"
}

/**
 * Samples the used Java/Kotlin heap for one workspace run. It is intentionally a narrow,
 * synchronous observation point: it cannot reserve memory or force a garbage collection, and
 * the returned value is a sample rather than an allocation guarantee.
 */
fun interface WorkspaceMemorySampler {
    fun sampleUsedHeapBytes(): Long

    companion object {
        /** Used heap of the current process, the same value Android exposes through Runtime. */
        val HEAP: WorkspaceMemorySampler = WorkspaceMemorySampler {
            val runtime = Runtime.getRuntime()
            (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        }
    }
}

/**
 * Cooperative memory accounting for one run. [check] samples once and rejects a run whose
 * sample is above [WorkspaceBudget.maxResidentBytes] or whose observed peak is above
 * [WorkspaceBudget.maxPeakHeapBytes]. This is not a hard memory limit: a caller that allocates
 * between two checks can still exceed the budget before the next sample.
 */
class WorkspaceMemoryMeter(
    val budget: WorkspaceBudget,
    private val sampler: WorkspaceMemorySampler = WorkspaceMemorySampler.HEAP,
) {
    var observedPeakBytes: Long = 0
        private set

    fun check(): Long {
        val sample = sampler.sampleUsedHeapBytes().coerceAtLeast(0L)
        if (sample > observedPeakBytes) observedPeakBytes = sample
        if (sample > budget.maxResidentBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.RESIDENT_BYTES, budget.maxResidentBytes, sample)
        }
        if (observedPeakBytes > budget.maxPeakHeapBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.PEAK_HEAP_BYTES, budget.maxPeakHeapBytes, observedPeakBytes)
        }
        return sample
    }

    /** Samples without rejecting; used after an irreversible commit to keep terminal facts. */
    fun sample(): Long {
        val sample = sampler.sampleUsedHeapBytes().coerceAtLeast(0L)
        if (sample > observedPeakBytes) observedPeakBytes = sample
        return sample
    }
}

/**
 * Sendable limits for one script execution. Derived from [WorkspaceBudget] so the engine
 * sees only the allowances of its own run and never the host configuration object.
 */
class WorkspaceScriptLimits(
    val maxOutputBytes: Long,
    val maxLogLines: Int,
    val maxChanges: Int,
    val maxArtifacts: Int,
    val maxChangeBytes: Long,
    val maxArtifactBytes: Long,
    val maxMaterialReadBytes: Long = 8L * 1024 * 1024,
    val maxTotalMaterialReadBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxOutputBytes in 1..WorkspaceHardLimits.MAX_OUTPUT_BYTES)
        require(maxLogLines in 1..WorkspaceHardLimits.MAX_LOG_LINES)
        require(maxChanges in 1..WorkspaceHardLimits.MAX_CHANGES)
        require(maxArtifacts in 1..WorkspaceHardLimits.MAX_ARTIFACTS)
        require(maxChangeBytes in 1..WorkspaceHardLimits.MAX_CHANGE_BYTES)
        require(maxArtifactBytes in 1..WorkspaceHardLimits.MAX_ARTIFACT_BYTES)
        require(maxMaterialReadBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES)
        require(maxTotalMaterialReadBytes in maxMaterialReadBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES)
    }

    companion object {
        fun of(budget: WorkspaceBudget): WorkspaceScriptLimits = WorkspaceScriptLimits(
            maxOutputBytes = budget.maxOutputBytes,
            maxLogLines = budget.maxLogLines,
            maxChanges = budget.maxChanges,
            maxArtifacts = budget.maxArtifacts,
            maxChangeBytes = budget.maxChangeBytes,
            maxArtifactBytes = budget.maxArtifactBytes,
            maxMaterialReadBytes = budget.maxMaterialBytes,
            maxTotalMaterialReadBytes = budget.maxTotalMaterialBytes,
        )
    }
}

/**
 * Mutable accounting for exactly one run. It is owned by [WorkspaceRuntime], never exposed
 * to scripts, and checks before mutating so a rejected item is not counted as consumed.
 */
class WorkspaceBudgetMeter(val budget: WorkspaceBudget) {
    var materialCount: Int = 0
        private set
    var materialBytes: Long = 0
        private set
    var changeCount: Int = 0
        private set
    var changeBytes: Long = 0
        private set
    var artifactCount: Int = 0
        private set
    var artifactBytes: Long = 0
        private set
    var outputBytes: Long = 0
        private set
    var logLines: Int = 0
        private set

    fun recordMaterial(sizeBytes: Long) {
        require(sizeBytes >= 0) { "Negative material size" }
        if (materialCount + 1 > budget.maxMaterials) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.MATERIALS, budget.maxMaterials.toLong(), materialCount + 1L)
        }
        if (sizeBytes > budget.maxMaterialBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.MATERIAL_BYTES, budget.maxMaterialBytes, sizeBytes)
        }
        if (sizeBytes > budget.maxTotalMaterialBytes - materialBytes) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.TOTAL_MATERIAL_BYTES, budget.maxTotalMaterialBytes, materialBytes + sizeBytes,
            )
        }
        materialCount += 1
        materialBytes += sizeBytes
    }

    fun recordChange(sizeBytes: Long) {
        require(sizeBytes >= 0) { "Negative change size" }
        if (changeCount + 1 > budget.maxChanges) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.CHANGES, budget.maxChanges.toLong(), changeCount + 1L)
        }
        if (sizeBytes > budget.maxChangeBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.CHANGE_BYTES, budget.maxChangeBytes, sizeBytes)
        }
        if (sizeBytes > budget.maxTotalChangeBytes - changeBytes) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.TOTAL_CHANGE_BYTES, budget.maxTotalChangeBytes, changeBytes + sizeBytes,
            )
        }
        changeCount += 1
        changeBytes += sizeBytes
    }

    fun recordArtifact(sizeBytes: Long) {
        require(sizeBytes >= 0) { "Negative artifact size" }
        if (artifactCount + 1 > budget.maxArtifacts) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.ARTIFACTS, budget.maxArtifacts.toLong(), artifactCount + 1L)
        }
        if (sizeBytes > budget.maxArtifactBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.ARTIFACT_BYTES, budget.maxArtifactBytes, sizeBytes)
        }
        if (sizeBytes > budget.maxTotalArtifactBytes - artifactBytes) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.TOTAL_ARTIFACT_BYTES, budget.maxTotalArtifactBytes, artifactBytes + sizeBytes,
            )
        }
        artifactCount += 1
        artifactBytes += sizeBytes
    }

    fun recordLogLine(text: String) {
        if (logLines + 1 > budget.maxLogLines) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.LOG_LINES, budget.maxLogLines.toLong(), logLines + 1L)
        }
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > budget.maxOutputBytes - outputBytes) {
            throw WorkspaceBudgetExceeded(WorkspaceBudgetKind.OUTPUT_BYTES, budget.maxOutputBytes, outputBytes + bytes)
        }
        logLines += 1
        outputBytes += bytes
    }
}
