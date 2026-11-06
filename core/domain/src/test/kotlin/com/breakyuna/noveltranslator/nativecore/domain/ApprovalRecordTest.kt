package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalRecordTest {
    private val changeSet = PreparedChangeSet(
        ChangeSetId("set"), ProjectId("project"), ChatId("chat"), RunId("run"),
        listOf(
            PreparedChange(
                ChangeTarget(ChangeTargetKind.ITEM, "item-1"), ChangeOperation.UPDATE,
                ObjectFingerprint("a".repeat(64)), ObjectFingerprint("b".repeat(64)),
            ),
        ),
    )

    @Test
    fun automaticApprovalCannotCarryAPendingDeadline() {
        expectIllegalArgument {
            ApprovalRecord(ApprovalRequestId("approval"), changeSet, ApprovalMode.AUTO, 1_000, 2_000)
        }
        val automatic = ApprovalRecord(ApprovalRequestId("approval"), changeSet, ApprovalMode.AUTO, 1_000, null)
        assertEquals(ApprovalMode.AUTO, automatic.mode)
    }

    @Test
    fun manualDeadlineMustFollowTheRequestTime() {
        expectIllegalArgument {
            ApprovalRecord(ApprovalRequestId("approval"), changeSet, ApprovalMode.MANUAL, 1_000, 1_000)
        }
        expectIllegalArgument {
            ApprovalRecord(ApprovalRequestId("approval"), changeSet, ApprovalMode.MANUAL, -1, null)
        }
        val manual = ApprovalRecord(ApprovalRequestId("approval"), changeSet, ApprovalMode.MANUAL, 1_000, 1_001)
        assertEquals(1_001L, manual.deadlineMillis)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: the record breaks an approval invariant.
        }
    }
}
