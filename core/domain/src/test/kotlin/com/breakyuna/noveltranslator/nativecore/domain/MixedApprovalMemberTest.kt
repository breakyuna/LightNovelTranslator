package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedApprovalMemberTest {
    private fun fp(value: Char) = ObjectFingerprint(value.toString().repeat(64))

    private fun member(selection: List<String>, fingerprints: List<ObjectFingerprint>) =
        MixedApprovalMember(MixedApprovalMemberKind.ITEM, "child", fp('a'),
            CommandId("child-command"), selection, fingerprints, null)

    private fun request(member: MixedApprovalMember) = MixedApprovalRequest(
        ApprovalRequestId("parent"), CommandId("parent-command"),
        LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1)),
        ChatId("chat"), RunId("run"), 0, ConflictPolicy.REJECT_BATCH, listOf(member), 1_000,
    )

    @Test fun reorderedSelectionsKeepTheirFingerprintsAndApprovalDigests() {
        for (fingerprints in listOf(emptyList<ObjectFingerprint>(), listOf(fp('b'), fp('c')))) {
            val reversed = member(listOf("2", "1"), fingerprints)
            val canonical = member(listOf("1", "2"), fingerprints.reversed())

            assertEquals(listOf("1", "2"), reversed.selection)
            assertEquals(fingerprints.reversed(), reversed.expectedFingerprints)
            assertTrue(reversed.hasSameReferences(canonical))
            assertEquals(canonical.selectionDigest, reversed.selectionDigest)
            assertEquals(request(canonical).digest, request(reversed).digest)
            assertEquals(reversed.selectionDigest, reversed.asPending().selectionDigest)
            assertEquals(reversed.selectionDigest,
                reversed.withOutcome(MixedApprovalMemberState.INDETERMINATE).selectionDigest)
        }
        val prepared = member(listOf("2", "1"), listOf(fp('b'), fp('c')))
        val changed = member(listOf("1", "3"), prepared.expectedFingerprints)
        assertNotEquals(prepared.selectionDigest, changed.selectionDigest)
        assertNotEquals(request(prepared).digest, request(changed).digest)
    }
}
