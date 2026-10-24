package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChangeTest {
    @Test
    fun changeSetRejectsDuplicateAndCaseCollidingPaths() {
        val duplicate = assertThrows(InvalidWorkspaceChangeSet::class.java) {
            changeSet(listOf(createChange("work/a.txt", "one"), createChange("work/a.txt", "two")))
        }
        assertEquals(InvalidWorkspaceChangeSet.Reason.DUPLICATE_PATH, duplicate.reason)

        val folded = assertThrows(InvalidWorkspaceChangeSet::class.java) {
            changeSet(
                listOf(createChange("work/A.txt", "one"), createChange("work/a.txt", "two")),
                PathPolicy(casePolicy = WorkspaceCasePolicy.FOLDED),
            )
        }
        assertEquals(InvalidWorkspaceChangeSet.Reason.DUPLICATE_PATH, folded.reason)

        changeSet(listOf(createChange("work/A.txt", "one"), createChange("work/a.txt", "two")))
    }

    @Test
    fun digestBindsExactBatchButIsStableForEqualContent() {
        val first = changeSet(listOf(createChange("work/a.txt", "one")))
        val same = changeSet(listOf(createChange("work/a.txt", "one")))
        val changed = changeSet(listOf(createChange("work/a.txt", "two")))
        val reordered = changeSet(
            listOf(createChange("work/a.txt", "one"), createChange("work/b.txt", "two")),
        )
        val opposite = changeSet(
            listOf(createChange("work/b.txt", "two"), createChange("work/a.txt", "one")),
        )

        assertEquals(first.digest, same.digest)
        assertNotEquals(first.digest, changed.digest)
        assertNotEquals(reordered.digest, opposite.digest)
        assertEquals(WorkspaceChangeSummary(1, 0, 0, 3), first.summary)
    }

    @Test
    fun preflightRejectsDuplicateStaleAlreadyExistingAndBudgetViolations() {
        val staleBase = digestOf("previous")
        val snapshots = FakeSnapshotPort()
        snapshots.fingerprints["work/exists.txt"] = digestOf("existing")
        snapshots.fingerprints["work/changed.txt"] = digestOf("newer")

        val report = runWorkspaceSuspend {
            WorkspaceChangePreflight.inspect(
                changes = listOf(
                    createChange("work/exists.txt", "overwrite"),
                    updateChange("work/changed.txt", "body", staleBase),
                    createChange("work/duplicate.txt", "one"),
                    createChange("work/duplicate.txt", "two"),
                ),
                materials = emptyMap(),
                snapshots = snapshots,
                budget = WorkspaceBudget(maxChanges = 3),
            )
        }

        assertTrue(report.hasConflicts)
        assertEquals(
            setOf(
                WorkspaceConflictReason.ALREADY_EXISTS,
                WorkspaceConflictReason.STALE_FINGERPRINT,
                WorkspaceConflictReason.DUPLICATE_PATH,
                WorkspaceConflictReason.BUDGET,
            ),
            report.conflicts.map { it.reason }.toSet(),
        )
        assertEquals(1, report.accepted.size)
        assertTrue(report.conflicts.any { it.reason == WorkspaceConflictReason.BUDGET })
    }

    @Test
    fun preflightMatchesMaterialSourceWithoutReadingItsBody() {
        val body = "material-body".toByteArray(Charsets.UTF_8)
        val summary = materialSummary("mat-1", body)
        val materialId = summary.id
        val change = WorkspaceChange(
            path = WorkspacePath("work/from-material.txt"),
            operation = WorkspaceChangeOperation.CREATE,
            expectedFingerprint = null,
            content = WorkspaceChangeContent.FromMaterial(
                materialId = materialId,
                sha256 = WorkspaceDigest(summary.sha256),
                sizeBytes = summary.sizeBytes,
            ),
        )
        val report = runWorkspaceSuspend {
            WorkspaceChangePreflight.inspect(
                changes = listOf(change),
                materials = mapOf(materialId to summary),
                snapshots = FakeSnapshotPort(),
                budget = WorkspaceBudget(),
            )
        }
        assertTrue(report.conflicts.isEmpty())
        assertEquals(listOf(change), report.accepted)

        val mismatch = WorkspaceChange(
            path = WorkspacePath("work/bad-source.txt"),
            operation = WorkspaceChangeOperation.CREATE,
            expectedFingerprint = null,
            content = WorkspaceChangeContent.FromMaterial(materialId, digestOf("other"), 3),
        )
        val bad = runWorkspaceSuspend {
            WorkspaceChangePreflight.inspect(
                changes = listOf(mismatch),
                materials = mapOf(materialId to summary),
                snapshots = FakeSnapshotPort(),
                budget = WorkspaceBudget(),
            )
        }
        assertEquals(listOf(WorkspaceConflictReason.MATERIAL_MISMATCH), bad.conflicts.map { it.reason })

        val unknown = WorkspaceChange(
            path = WorkspacePath("work/unknown.txt"),
            operation = WorkspaceChangeOperation.CREATE,
            expectedFingerprint = null,
            content = WorkspaceChangeContent.FromMaterial(WorkspaceMaterialId("missing"), digestOf("x"), 1),
        )
        val missing = runWorkspaceSuspend {
            WorkspaceChangePreflight.inspect(
                changes = listOf(unknown),
                materials = emptyMap(),
                snapshots = FakeSnapshotPort(),
                budget = WorkspaceBudget(),
            )
        }
        assertEquals(listOf(WorkspaceConflictReason.UNKNOWN_MATERIAL), missing.conflicts.map { it.reason })
    }

    @Test
    fun receiptStatusMustAgreeWithCommittedAndConflictLists() {
        val path = WorkspacePath("work/a.txt")
        val committed = WorkspaceCommittedChange(path, WorkspaceChangeOperation.CREATE, digestOf("a"))
        val conflict = WorkspaceConflict(path, WorkspaceConflictReason.STALE_FINGERPRINT)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceApplyReceipt(
                COMMAND_ID, CHANGE_SET_ID, digestOf("d"), WorkspaceApplyStatus.COMMITTED,
                listOf(committed), listOf(conflict), 1, 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceApplyReceipt(
                COMMAND_ID, CHANGE_SET_ID, digestOf("d"), WorkspaceApplyStatus.PARTIAL,
                emptyList(), listOf(conflict), 1, 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceApplyReceipt(
                COMMAND_ID, CHANGE_SET_ID, digestOf("d"), WorkspaceApplyStatus.REJECTED,
                listOf(committed), emptyList(), 1, 1,
            )
        }
        val partial = WorkspaceApplyReceipt(
            COMMAND_ID, CHANGE_SET_ID, digestOf("d"), WorkspaceApplyStatus.PARTIAL,
            listOf(committed), listOf(conflict), 1, 1,
        )
        assertEquals(WorkspaceApplyStatus.PARTIAL, partial.status)
        assertEquals(1, partial.committed.size)
        assertEquals(1, partial.conflicts.size)
    }

    @Test
    fun inlineContentCopiesInputAndKeepsItsDigestStable() {
        val source = "body".toByteArray(Charsets.UTF_8)
        val content = WorkspaceChangeContent.Inline(source)
        val digest = content.sha256
        source[0] = 'X'.code.toByte()
        assertEquals(digest, content.sha256)
        assertEquals("body", String(content.bytes, Charsets.UTF_8))
    }

    @Test
    fun deleteRequiresBaseFingerprintAndNoContent() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceChange(WorkspacePath("work/a.txt"), WorkspaceChangeOperation.DELETE, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceChange(
                WorkspacePath("work/a.txt"), WorkspaceChangeOperation.DELETE, digestOf("a"),
                WorkspaceChangeContent.Inline(byteArrayOf(1)),
            )
        }
        val delete = deleteChange("work/a.txt", digestOf("a"))
        assertEquals(0L, delete.sizeBytes)
    }

    @Test
    fun approvalRequestBindsChangeSetDigestAndIdentity() {
        val set = changeSet(listOf(createChange("work/a.txt", "one")))
        val request = WorkspaceApprovalRequest(
            commandId = COMMAND_ID,
            projectId = PROJECT_ID,
            chatId = CHAT_ID,
            runId = RUN_ID,
            changeSet = set,
            digest = set.digest,
            changeSummary = set.summary,
            artifacts = emptyList(),
            deadlineAtMillis = 2_000,
        )
        assertEquals(set.digest, request.digest)
        assertEquals(1, request.changeSummary.total)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceApprovalRequest(
                COMMAND_ID, PROJECT_ID, CHAT_ID, RUN_ID, set, digestOf("wrong"),
                set.summary, emptyList(), 2_000,
            )
        }
    }

    private fun changeSet(
        changes: List<WorkspaceChange>,
        policy: PathPolicy = PathPolicy.DEFAULT,
    ): WorkspaceChangeSet = WorkspaceChangeSet(
        id = CHANGE_SET_ID,
        projectId = PROJECT_ID,
        chatId = CHAT_ID,
        runId = RUN_ID,
        changes = changes,
        policy = policy,
    )
}
