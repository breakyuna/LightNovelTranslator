package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceBudgetTest {
    @Test
    fun rejectsInvalidConfigurations() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBudget(maxMaterials = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBudget(maxChanges = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBudget(maxArtifacts = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBudget(maxLogLines = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBudget(timeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxMaterialBytes = 64, maxTotalMaterialBytes = 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxChangeBytes = 64, maxTotalChangeBytes = 32)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxMaterialBytes = WorkspaceHardLimits.MAX_SINGLE_BYTES + 1)
        }
    }

    @Test
    fun meterEnforcesMaterialCountSingleAndTotalBytes() {
        val budget = WorkspaceBudget(
            maxMaterials = 2,
            maxMaterialBytes = 10,
            maxTotalMaterialBytes = 15,
        )
        val meter = WorkspaceBudgetMeter(budget)
        meter.recordMaterial(10)
        assertEquals(1, meter.materialCount)
        assertEquals(10L, meter.materialBytes)

        val total = assertThrows(WorkspaceBudgetExceeded::class.java) { meter.recordMaterial(6) }
        assertEquals(WorkspaceBudgetKind.TOTAL_MATERIAL_BYTES, total.kind)

        val single = assertThrows(WorkspaceBudgetExceeded::class.java) {
            WorkspaceBudgetMeter(budget).recordMaterial(11)
        }
        assertEquals(WorkspaceBudgetKind.MATERIAL_BYTES, single.kind)

        val count = assertThrows(WorkspaceBudgetExceeded::class.java) {
            val other = WorkspaceBudgetMeter(WorkspaceBudget(maxMaterials = 1))
            other.recordMaterial(1)
            other.recordMaterial(1)
        }
        assertEquals(WorkspaceBudgetKind.MATERIALS, count.kind)
    }

    @Test
    fun meterEnforcesChangeArtifactAndLogBudgets() {
        val changeMeter = WorkspaceBudgetMeter(
            WorkspaceBudget(maxChanges = 2, maxChangeBytes = 4, maxTotalChangeBytes = 5),
        )
        changeMeter.recordChange(4)
        val total = assertThrows(WorkspaceBudgetExceeded::class.java) { changeMeter.recordChange(2) }
        assertEquals(WorkspaceBudgetKind.TOTAL_CHANGE_BYTES, total.kind)

        val single = assertThrows(WorkspaceBudgetExceeded::class.java) {
            WorkspaceBudgetMeter(WorkspaceBudget(maxChangeBytes = 4, maxTotalChangeBytes = 8)).recordChange(5)
        }
        assertEquals(WorkspaceBudgetKind.CHANGE_BYTES, single.kind)

        val artifactMeter = WorkspaceBudgetMeter(WorkspaceBudget(maxArtifacts = 1))
        artifactMeter.recordArtifact(1)
        val artifacts = assertThrows(WorkspaceBudgetExceeded::class.java) { artifactMeter.recordArtifact(1) }
        assertEquals(WorkspaceBudgetKind.ARTIFACTS, artifacts.kind)

        val artifactBytes = assertThrows(WorkspaceBudgetExceeded::class.java) {
            WorkspaceBudgetMeter(
                WorkspaceBudget(maxArtifactBytes = 4, maxTotalArtifactBytes = 8),
            ).recordArtifact(5)
        }
        assertEquals(WorkspaceBudgetKind.ARTIFACT_BYTES, artifactBytes.kind)

        val logMeter = WorkspaceBudgetMeter(WorkspaceBudget(maxLogLines = 1, maxOutputBytes = 4))
        logMeter.recordLogLine("abc")
        val lines = assertThrows(WorkspaceBudgetExceeded::class.java) { logMeter.recordLogLine("d") }
        assertEquals(WorkspaceBudgetKind.LOG_LINES, lines.kind)
        assertEquals(3L, logMeter.outputBytes)

        val output = assertThrows(WorkspaceBudgetExceeded::class.java) {
            WorkspaceBudgetMeter(WorkspaceBudget(maxOutputBytes = 3)).recordLogLine("abcd")
        }
        assertEquals(WorkspaceBudgetKind.OUTPUT_BYTES, output.kind)
    }

    @Test
    fun memoryLimitsAreConservativeAndValidated() {
        val defaults = WorkspaceBudget()
        assertEquals(128L * 1024 * 1024, defaults.maxResidentBytes)
        assertEquals(256L * 1024 * 1024, defaults.maxPeakHeapBytes)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxResidentBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxPeakHeapBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBudget(maxResidentBytes = 100, maxPeakHeapBytes = WorkspaceHardLimits.MAX_PEAK_HEAP_BYTES + 1)
        }
    }

    @Test
    fun memoryMeterRejectsResidentAndObservedPeakSamples() {
        var sample = 40L
        val meter = WorkspaceMemoryMeter(
            WorkspaceBudget(maxResidentBytes = 100, maxPeakHeapBytes = 60),
            WorkspaceMemorySampler { sample },
        )
        assertEquals(40L, meter.check())
        sample = 70L
        val peak = assertThrows(WorkspaceBudgetExceeded::class.java) { meter.check() }
        assertEquals(WorkspaceBudgetKind.PEAK_HEAP_BYTES, peak.kind)
        assertEquals(60L, peak.limit)
        assertEquals(70L, peak.actual)
        assertEquals(70L, meter.observedPeakBytes)

        val resident = WorkspaceMemoryMeter(
            WorkspaceBudget(maxResidentBytes = 32, maxPeakHeapBytes = 64),
            WorkspaceMemorySampler { 64L },
        )
        val exceeded = assertThrows(WorkspaceBudgetExceeded::class.java) { resident.check() }
        assertEquals(WorkspaceBudgetKind.RESIDENT_BYTES, exceeded.kind)
    }

    @Test
    fun scriptLimitsAreDerivedFromTheRunBudget() {
        val budget = WorkspaceBudget(
            maxOutputBytes = 2_048,
            maxLogLines = 10,
            maxChanges = 4,
            maxArtifacts = 3,
            maxChangeBytes = 1_024,
            maxArtifactBytes = 4_096,
            maxTotalArtifactBytes = 4_096,
        )
        val limits = WorkspaceScriptLimits.of(budget)
        assertEquals(2_048L, limits.maxOutputBytes)
        assertEquals(10, limits.maxLogLines)
        assertEquals(4, limits.maxChanges)
        assertEquals(3, limits.maxArtifacts)
        assertEquals(1_024L, limits.maxChangeBytes)
        assertEquals(4_096L, limits.maxArtifactBytes)
    }
}
