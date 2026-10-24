package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class WorkspaceRuntimeTest {
    @Test
    fun happyPathApprovesAppliesThenSavesArtifacts() {
        val summary = materialSummary("mat-1")
        val fixture = RuntimeFixture(listOf(summary), mapOf(summary.id to "material-body".toByteArray()))
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                exitCode = 0,
                changes = listOf(createChange("work/analysis.txt", "hello")),
                artifacts = listOf(artifact("uploads/report.md", "report")),
                logs = listOf(WorkspaceLogLine(WorkspaceLogStream.STDOUT, "done")),
            )
        }

        val outcome = fixture.run(runRequest(materialIds = listOf(summary.id)))

        val completed = outcome as WorkspaceRunOutcome.Completed
        assertNotNull(completed.receipt)
        assertEquals(WorkspaceApplyStatus.COMMITTED, completed.receipt!!.status)
        assertEquals(1, completed.artifacts.size)
        assertEquals(listOf("approval", "apply", "artifact"), fixture.events)
        assertEquals(1, fixture.approvals.calls)
        assertEquals(1, fixture.apply.calls)
        assertEquals(1, fixture.artifacts.calls)
        assertEquals(0, fixture.materials.readCount)
        assertEquals(listOf(summary.id), fixture.engine.lastRequest!!.materials.map { it.id })
        assertEquals(WorkspacePath("work/analysis.txt"), completed.receipt!!.committed.single().path)
    }

    @Test
    fun withoutApprovalTheProjectIsNeverSubmitted() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        fixture.approvals.decisionFactory = {
            WorkspaceApprovalDecision.Rejected(ApprovalRequestId("approval-1"), WorkspaceRejectionReason.USER_REJECTED)
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.Rejected)
        assertEquals(WorkspaceRejectionReason.USER_REJECTED, (outcome as WorkspaceRunOutcome.Rejected).reason)
        assertEquals(0, fixture.apply.calls)
        assertEquals(0, fixture.artifacts.calls)
    }

    @Test
    fun approvalDigestMismatchIsRefusedBeforeApply() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        fixture.approvals.decisionFactory = {
            WorkspaceApprovalDecision.Approved(
                ApprovalRequestId("approval-1"), digestOf("other-batch"), approvedAtMillis = 1_000,
            )
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.ApprovalMismatch)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun cancellationWhileWaitingForApprovalWinsOverAnApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        fixture.approvals.onRequest = { _, cancellation -> cancellation.cancel() }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.Cancelled)
        assertEquals(WorkspaceRunStage.APPROVAL, (outcome as WorkspaceRunOutcome.Cancelled).stage)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun cancellationBeforeStartStopsEverything() {
        val fixture = RuntimeFixture()
        val token = WorkspaceCancellation()
        token.cancel()

        val outcome = fixture.run(cancellation = token)

        assertTrue(outcome is WorkspaceRunOutcome.Cancelled)
        assertEquals(WorkspaceRunStage.MATERIALS, (outcome as WorkspaceRunOutcome.Cancelled).stage)
        assertEquals(0, fixture.engine.calls)
        assertEquals(0, fixture.approvals.calls)
    }

    @Test
    fun lateScriptResultIsDiscardedWhenTheDeadlinePassed() {
        val fixture = RuntimeFixture()
        val budget = WorkspaceBudget(timeoutMillis = 1_000)
        fixture.engine.onExecute = { fixture.clock.now = 5_000 }
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }

        val outcome = fixture.run(runRequest(budget = budget))

        assertTrue(outcome is WorkspaceRunOutcome.TimedOut)
        assertEquals(WorkspaceRunStage.SCRIPT, (outcome as WorkspaceRunOutcome.TimedOut).stage)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun engineFailureDoesNotTouchTheProject() {
        val fixture = RuntimeFixture()
        fixture.engine.failure = IllegalStateException("engine crashed")

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.EngineFailed)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun staleFingerprintBlocksApproval() {
        val fixture = RuntimeFixture()
        fixture.snapshots.fingerprints["work/a.txt"] = digestOf("newer")
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(updateChange("work/a.txt", "mine", digestOf("older"))),
                emptyList(),
            )
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.Conflicts)
        assertEquals(
            listOf(WorkspaceConflictReason.STALE_FINGERPRINT),
            (outcome as WorkspaceRunOutcome.Conflicts).conflicts.map { it.reason },
        )
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun duplicateOutputPathsAreRejectedByPreflight() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(createChange("work/a.txt", "one"), createChange("work/a.txt", "two")),
                emptyList(),
            )
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.Conflicts)
        assertTrue(
            (outcome as WorkspaceRunOutcome.Conflicts).conflicts.any {
                it.reason == WorkspaceConflictReason.DUPLICATE_PATH
            },
        )
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun logBudgetOverflowIsRejectedBeforeApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(createChange("work/a.txt", "body")),
                emptyList(),
                logs = listOf(
                    WorkspaceLogLine(WorkspaceLogStream.STDOUT, "first"),
                    WorkspaceLogLine(WorkspaceLogStream.STDERR, "second"),
                ),
            )
        }

        val outcome = fixture.run(runRequest(budget = WorkspaceBudget(maxLogLines = 1)))

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        assertEquals(WorkspaceBudgetKind.LOG_LINES, (outcome as WorkspaceRunOutcome.BudgetBlocked).kind)
        assertEquals(WorkspaceRunStage.OUTPUT_VALIDATION, outcome.stage)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun changeCountBeyondBudgetIsRejectedBeforeApproval() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(
                    createChange("work/a.txt", "one"),
                    createChange("work/b.txt", "two"),
                    createChange("work/c.txt", "three"),
                ),
                emptyList(),
            )
        }

        val outcome = fixture.run(runRequest(budget = WorkspaceBudget(maxChanges = 2)))

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        assertEquals(WorkspaceBudgetKind.CHANGES, (outcome as WorkspaceRunOutcome.BudgetBlocked).kind)
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun materialBudgetOverflowIsRejectedBeforeScriptExecution() {
        val summary = materialSummary("mat-big", ByteArray(32))
        val fixture = RuntimeFixture(listOf(summary))
        val budget = WorkspaceBudget(maxMaterialBytes = 16, maxTotalMaterialBytes = 16)

        val outcome = fixture.run(runRequest(budget = budget, materialIds = listOf(summary.id)))

        assertTrue(outcome is WorkspaceRunOutcome.BudgetBlocked)
        assertEquals(WorkspaceBudgetKind.MATERIAL_BYTES, (outcome as WorkspaceRunOutcome.BudgetBlocked).kind)
        assertEquals(WorkspaceRunStage.MATERIALS, outcome.stage)
        assertEquals(0, fixture.engine.calls)
    }

    @Test
    fun truncatedOutputIsNeverApproved() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0, listOf(createChange("work/a.txt", "body")), emptyList(), outputTruncated = true,
            )
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.ScriptRejected)
        assertEquals(
            WorkspaceOutputViolation.TRUNCATED_OUTPUT,
            (outcome as WorkspaceRunOutcome.ScriptRejected).violation,
        )
        assertEquals(0, fixture.approvals.calls)
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun changesWithoutTheCapabilityAreRejected() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }

        val outcome = fixture.run(runRequest(grantedCapabilities = setOf(WorkspaceCapability.READ_MATERIALS)))

        assertTrue(outcome is WorkspaceRunOutcome.ScriptRejected)
        assertEquals(
            WorkspaceOutputViolation.CHANGES_WITHOUT_CAPABILITY,
            (outcome as WorkspaceRunOutcome.ScriptRejected).violation,
        )
        assertEquals(0, fixture.apply.calls)
    }

    @Test
    fun artifactFailureAfterCommitKeepsTheDurableReceipt() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(createChange("work/a.txt", "body")),
                listOf(artifact("uploads/report.md", "report")),
            )
        }
        fixture.artifacts.failure = IllegalStateException("disk full")

        val outcome = fixture.run()

        val completed = outcome as WorkspaceRunOutcome.Completed
        assertNotNull(completed.receipt)
        assertEquals(WorkspaceArtifactFailureReason.SAVE_FAILED, completed.artifactFailure)
        assertTrue(completed.artifacts.isEmpty())
        assertEquals(1, fixture.apply.calls)
    }

    @Test
    fun applyExceptionIsIndeterminateAndNeverReportedAsCancelled() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        fixture.apply.failure = IllegalStateException("store unavailable")

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.ApplyIndeterminate)
        assertEquals(
            WorkspaceApplyIndeterminateReason.STORE_EXCEPTION,
            (outcome as WorkspaceRunOutcome.ApplyIndeterminate).reason,
        )
    }

    @Test
    fun cancellationDuringApplyIsIndeterminateNotASuccessClaim() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }
        fixture.apply.failure = CancellationException("stopped")

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.ApplyIndeterminate)
        assertEquals(
            WorkspaceApplyIndeterminateReason.CANCELLED_DURING_APPLY,
            (outcome as WorkspaceRunOutcome.ApplyIndeterminate).reason,
        )
    }

    @Test
    fun partialApplyReceiptIsReturnedWithItsConflicts() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0,
                listOf(createChange("work/a.txt", "one"), createChange("work/b.txt", "two")),
                emptyList(),
            )
        }
        fixture.apply.receiptFactory = { approved ->
            WorkspaceApplyReceipt(
                commandId = approved.commandId,
                changeSetId = approved.changeSet.id,
                digest = approved.changeSet.digest,
                status = WorkspaceApplyStatus.PARTIAL,
                committed = listOf(
                    WorkspaceCommittedChange(
                        approved.changeSet.changes.first().path,
                        WorkspaceChangeOperation.CREATE,
                        approved.changeSet.changes.first().content!!.sha256,
                    ),
                ),
                conflicts = listOf(
                    WorkspaceConflict(
                        approved.changeSet.changes.last().path,
                        WorkspaceConflictReason.STALE_FINGERPRINT,
                    ),
                ),
                revision = 2,
                committedAtMillis = 1_000,
            )
        }

        val outcome = fixture.run()

        val completed = outcome as WorkspaceRunOutcome.Completed
        assertEquals(WorkspaceApplyStatus.PARTIAL, completed.receipt!!.status)
        assertEquals(1, completed.receipt!!.committed.size)
        assertEquals(1, completed.receipt!!.conflicts.size)
    }

    @Test
    fun artifactOnlyRunsStillRequireApprovalButSkipApply() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0, emptyList(), listOf(artifact("uploads/report.md", "report")),
            )
        }

        val outcome = fixture.run()

        val completed = outcome as WorkspaceRunOutcome.Completed
        assertNull(completed.receipt)
        assertNull(completed.changeSetId)
        assertEquals(1, completed.artifacts.size)
        assertEquals(1, fixture.approvals.calls)
        assertNull(fixture.approvals.lastRequest!!.changeSet)
        assertEquals(0, fixture.apply.calls)
        assertEquals(listOf("approval", "artifact"), fixture.events)
    }

    @Test
    fun materialBodyStaysLazyUntilExplicitlyRead() {
        val summary = materialSummary("mat-1")
        val fixture = RuntimeFixture(listOf(summary), mapOf(summary.id to "material-body".toByteArray()))
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, listOf(createChange("work/a.txt", "body")), emptyList())
        }

        fixture.run(runRequest(materialIds = listOf(summary.id)))
        assertEquals(0, fixture.materials.readCount)

        val content = runWorkspaceSuspend { fixture.materials.read(summary.id, 1_024) }
        assertEquals("material-body", String(content.bytes))
        assertEquals(1, fixture.materials.readCount)
    }

    @Test
    fun invalidArtifactPathIsRejectedByPolicy() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(
                0, emptyList(), listOf(artifact("CON", "report")),
            )
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.ScriptRejected)
        assertEquals(
            WorkspaceOutputViolation.INVALID_ARTIFACT_PATH,
            (outcome as WorkspaceRunOutcome.ScriptRejected).violation,
        )
    }

    @Test
    fun missingMaterialFailsWithoutRunningTheScript() {
        val fixture = RuntimeFixture()
        val outcome = fixture.run(runRequest(materialIds = listOf(WorkspaceMaterialId("missing"))))

        assertTrue(outcome is WorkspaceRunOutcome.MaterialUnavailable)
        assertEquals(WorkspaceMaterialId("missing"), (outcome as WorkspaceRunOutcome.MaterialUnavailable).materialId)
        assertEquals(0, fixture.engine.calls)
    }

    @Test
    fun rejectedArtifactOnlyRunSavesNoFile() {
        val fixture = RuntimeFixture()
        fixture.engine.resultFactory = {
            WorkspaceScriptResult(0, emptyList(), listOf(artifact("uploads/report.md", "report")))
        }
        fixture.approvals.decisionFactory = {
            WorkspaceApprovalDecision.Rejected(ApprovalRequestId("approval-1"), WorkspaceRejectionReason.USER_REJECTED)
        }

        val outcome = fixture.run()

        assertTrue(outcome is WorkspaceRunOutcome.Rejected)
        assertEquals(0, fixture.artifacts.calls)
    }

    private fun artifact(path: String, text: String): WorkspaceArtifactDraft = WorkspaceArtifactDraft(
        path = WorkspacePath(path),
        mediaType = "text/markdown",
        bytes = text.toByteArray(Charsets.UTF_8),
    )
}
