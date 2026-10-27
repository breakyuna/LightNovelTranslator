package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PdfPageChangeProposalTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val sha = "a".repeat(64)

    @Test fun pageRequestsRejectStringsDuplicatesRangesAndUnknownDecisions() {
        assertEquals(listOf(PdfPageChangeTarget(1, PdfPageMode.KEEP), PdfPageChangeTarget(4, PdfPageMode.TRANSLATE)),
            PdfPageChangeProposalCodec.targets("""{"pages":[{"page":4,"decision":"TRANSLATE"},{"page":1,"decision":"KEEP"}]}"""))
        for (json in listOf(
            """{"pages":[{"page":"1","decision":"KEEP"}]}""",
            """{"pages":[{"page":0,"decision":"KEEP"}]}""",
            """{"pages":[{"page":1,"decision":"keep"}]}""",
            """{"pages":[{"page":1,"decision":"KEEP"},{"page":1,"decision":"OMIT"}]}""",
            """{"pages":[{"page":1,"decision":"KEEP","source":"text"}]}""",
        )) {
            assertThrows(json, IllegalArgumentException::class.java) { PdfPageChangeProposalCodec.targets(json) }
        }
    }

    @Test fun malformedSelectionsAndReceiptsUseArgumentErrors() {
        for (json in listOf("""{"pages":null}""", """{"pages":[{}]}""",
            """{"pages":["1"]}""", """{"pages":[0]}""", """{"pages":[1,1]}""")) {
            assertThrows(json, IllegalArgumentException::class.java) { PdfPageChangeProposalCodec.selection(json) }
        }
        val receipt = PdfPageChangeProposalCodec.receipt(PdfPageProposalReceipt(CommandId("c"),
            ObjectFingerprint("b".repeat(64)), 0, listOf(1), emptyMap()))
        for (json in listOf(
            receipt.replace("\"accepted\":[1]", "\"accepted\":null"),
            receipt.replace("\"accepted\":[1]", "\"accepted\":[{}]"),
            receipt.replace("\"rejected\":[]", "\"rejected\":null"),
            receipt.replace("\"rejected\":[]", "\"rejected\":[null]"),
        )) {
            assertNotEquals(receipt, json)
            assertThrows(json, IllegalArgumentException::class.java) { PdfPageChangeProposalCodec.receipt(json) }
        }
    }

    @Test fun proposalBindsAssetRevisionAndActualSelectionWithoutSourceText() {
        val proposal = PdfPageChangeProposal(ApprovalRequestId("approval"), ref, ChatId("chat"), RunId("run"),
            CommandId("command"), 7, 3, sha, 8, ConflictPolicy.REJECT_BATCH,
            listOf(PdfPageChangeMember(5, PdfPageMode.KEEP, PdfPageMode.OMIT),
                PdfPageChangeMember(2, PdfPageMode.TRANSLATE, PdfPageMode.KEEP)))
        assertEquals(listOf(2, 5), proposal.members.map { it.page })
        assertFalse(proposal.payload.contains("source text"))
        assertNotEquals(proposal.binding(setOf(2)), proposal.binding(setOf(2, 5)))
        val restored = PdfPageChangeProposalCodec.proposal(proposal.payload)
        assertEquals(proposal.payload, restored.payload)
        assertEquals(proposal.digest, restored.digest)
        assertThrows(IllegalArgumentException::class.java) { proposal.binding(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { proposal.binding(setOf(9)) }
    }

    @Test fun selectionAndReceiptRoundTripWithStableConflictStates() {
        val binding = ObjectFingerprint("b".repeat(64))
        assertEquals(setOf(1, 3), PdfPageChangeProposalCodec.selection(
            PdfPageChangeProposalCodec.selection(setOf(3, 1))))
        val partial = PdfPageProposalReceipt(CommandId("c"), binding, 9, listOf(3),
            mapOf(1 to PdfPageProposalRejection.NOT_SELECTED, 4 to PdfPageProposalRejection.STALE_PAGE), 11)
        assertEquals(PdfPageProposalState.PARTIAL, partial.state)
        val restored = PdfPageChangeProposalCodec.receipt(PdfPageChangeProposalCodec.receipt(partial))
        assertEquals(partial.accepted, restored.accepted)
        assertEquals(partial.rejected, restored.rejected)
        assertEquals(partial.state, restored.state)
        assertEquals(PdfPageProposalState.COMMITTED,
            PdfPageProposalReceipt(CommandId("c"), binding, 9, listOf(1, 2), emptyMap()).state)
        assertEquals(PdfPageProposalState.CONFLICT,
            PdfPageProposalReceipt(CommandId("c"), binding, 9, emptyList(),
                mapOf(1 to PdfPageProposalRejection.STALE_ASSET)).state)
        assertThrows(IllegalArgumentException::class.java) {
            PdfPageProposalReceipt(CommandId("c"), binding, 9, listOf(1),
                mapOf(1 to PdfPageProposalRejection.NOT_SELECTED))
        }
    }
}
