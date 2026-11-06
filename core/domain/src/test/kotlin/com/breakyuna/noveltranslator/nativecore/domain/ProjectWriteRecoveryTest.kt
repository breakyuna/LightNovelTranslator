package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWriteRecoveryTest {
    @Test
    fun recoveryTableCoversEveryApprovalContractWindow() {
        assertEquals(RecoveryAction.REQUIRE_USER_RECONCILIATION, WriteRecovery.classify(facts(digestConsistent = false)))
        assertEquals(RecoveryAction.REAUTHORIZE, WriteRecovery.classify(facts(sameInstanceAndGeneration = false)))
        assertEquals(RecoveryAction.RETURN_STORED_RECEIPT, WriteRecovery.classify(facts(projectReceiptPresent = true)))
        assertEquals(RecoveryAction.TERMINATE_UNCOMMITTED, WriteRecovery.classify(facts(approvalApproved = false)))
        assertEquals(
            RecoveryAction.BIND_AND_EXECUTE,
            WriteRecovery.classify(facts(approvalConsumed = false, commandBound = false)),
        )
        assertEquals(
            RecoveryAction.TERMINATE_UNCOMMITTED,
            WriteRecovery.classify(facts(approvalConsumed = false, commandBound = false, runActive = false)),
        )
        assertEquals(
            RecoveryAction.REQUIRE_USER_RECONCILIATION,
            WriteRecovery.classify(facts(commandBound = false)),
        )
        assertEquals(
            RecoveryAction.REQUIRE_USER_RECONCILIATION,
            WriteRecovery.classify(facts(executorFinished = false)),
        )
        assertEquals(RecoveryAction.RESUME_SAME_COMMAND, WriteRecovery.classify(facts()))
        assertEquals(RecoveryAction.TERMINATE_UNCOMMITTED, WriteRecovery.classify(facts(runActive = false)))
    }

    @Test
    fun instanceAndDigestChecksWinOverReceiptAndApprovalFacts() {
        assertEquals(
            RecoveryAction.REAUTHORIZE,
            WriteRecovery.classify(facts(sameInstanceAndGeneration = false, projectReceiptPresent = true)),
        )
        assertEquals(
            RecoveryAction.REQUIRE_USER_RECONCILIATION,
            WriteRecovery.classify(facts(digestConsistent = false, approvalApproved = false)),
        )
    }

    @Test
    fun payloadReferenceRequiresVersionAndOpaqueStorageHandle() {
        expectIllegalArgument { ChangePayloadRef(ChangeSetId("set"), digest(), 0, "payload/1") }
        expectIllegalArgument { ChangePayloadRef(ChangeSetId("set"), digest(), 1, " ") }
    }

    @Test
    fun receiptRejectsOverlappingOrEmptyResults() {
        val target = ChangeTarget(ChangeTargetKind.ITEM, "item-1")
        expectIllegalArgument {
            WriteReceipt(
                CommandId("c"), ChangeSetId("set"), digest(), 3, 10,
                listOf(target), listOf(RejectedTarget(target, RejectReason.STALE_FINGERPRINT)),
            )
        }
        expectIllegalArgument {
            WriteReceipt(CommandId("c"), ChangeSetId("set"), digest(), 3, 10, emptyList(), emptyList())
        }
        val receipt = WriteReceipt(
            CommandId("c"), ChangeSetId("set"), digest(), 3, 10,
            listOf(target), listOf(RejectedTarget(ChangeTarget(ChangeTargetKind.ITEM, "item-2"), RejectReason.STALE_FINGERPRINT)),
        )
        assertEquals(1, receipt.accepted.size)
        assertEquals(1, receipt.rejected.size)
    }

    @Test
    fun outcomeTypesKeepRejectionAndUncertaintyDistinct() {
        val committed: WriteOutcome = WriteOutcome.Committed(
            WriteReceipt(CommandId("c"), ChangeSetId("set"), digest(), 1, 2,
                listOf(ChangeTarget(ChangeTargetKind.ITEM, "item-1")), emptyList()),
        )
        assertTrue(committed is WriteOutcome.Committed)
        val rejected: WriteOutcome = WriteOutcome.Rejected(
            RejectReason.STALE_FINGERPRINT, listOf(ChangeTarget(ChangeTargetKind.ITEM, "item-1")),
        )
        assertTrue(rejected is WriteOutcome.Rejected)
        val indeterminate: WriteOutcome = WriteOutcome.Indeterminate(IndeterminateReason.STORE_UNAVAILABLE)
        assertTrue(indeterminate is WriteOutcome.Indeterminate)
    }

    private fun digest() = ChangeSetDigest("a".repeat(64))

    private fun facts(
        approvalApproved: Boolean = true,
        approvalConsumed: Boolean = true,
        commandBound: Boolean = true,
        projectReceiptPresent: Boolean = false,
        digestConsistent: Boolean = true,
        sameInstanceAndGeneration: Boolean = true,
        executorFinished: Boolean = true,
        runActive: Boolean = true,
    ) = WriteRecoveryFacts(
        approvalApproved, approvalConsumed, commandBound, projectReceiptPresent,
        digestConsistent, sameInstanceAndGeneration, executorFinished, runActive,
    )

    private fun expectIllegalArgument(body: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { body() }
    }
}
