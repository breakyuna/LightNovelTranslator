package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class ItemProposalCodecTest {
    private val base = ObjectFingerprint("a".repeat(64))
    private val item = TextProjectItem(ItemId(7), 2, "source", "old", TextItemStatus.NONE, base,
        metadata = TextItemMetadata(nameSource = listOf("Alice"), nameTranslation = listOf("旧名")))

    @Test fun patchPreservesUnspecifiedNamesAndExplicitNullClearsOnlyTheChosenField() {
        val patch = ItemProposalCodec.patches("""{"items":[{"item_id":7,"fingerprint":"${base.sha256}","translation":"新译文","name_translation":null}]}""").single()
        val member = ItemProposalCodec.patch(item, patch)
        assertEquals("old", member.before.translation)
        assertEquals("新译文", member.after.translation)
        assertEquals(TextItemStatus.PROCESSED, member.after.status)
        assertEquals(listOf("Alice"), member.after.nameSource)
        assertNull(member.after.nameTranslation)
        assertEquals(member.json(), ItemProposalCodec.member(member.json().toString()).json())
    }

    @Test fun invalidTypesFractionsDuplicateTargetsAndUnknownFieldsAreRejected() {
        for (id in listOf("1.5", "true", "\"7\"", "9223372036854775808")) {
            try {
                ItemProposalCodec.patches("""{"items":[{"item_id":$id,"fingerprint":"${base.sha256}","translation":"x"}]}""")
                fail("Accepted invalid item identity")
            } catch (_: IllegalArgumentException) { }
        }
        val valid = """{"item_id":7,"fingerprint":"${base.sha256}","translation":"x"}"""
        for (json in listOf("""{"items":[$valid,$valid]}""", """{"items":[$valid],"extra":true}""")) {
            try { ItemProposalCodec.patches(json); fail("Accepted malformed batch") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun selectionAndConflictPolicyArePartOfTheSubmissionIdentity() {
        fun proposal(policy: ConflictPolicy) = ItemChangeProposal(ApprovalRequestId("proposal"),
            LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1)), ChatId("c"),
            RunId("r"), CommandId("command"), 3, policy,
            listOf(ItemProposalMember(item.id, item.row, item.assetId, base,
                ItemProposalValue("old", item.status, null, null), ItemProposalValue("new", TextItemStatus.PROCESSED, null, null))))
        val one = proposal(ConflictPolicy.REJECT_BATCH)
        val another = proposal(ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS)
        assertNotEquals(one.digest, another.digest)
        assertNotEquals(one.binding(setOf(item.id)), another.binding(setOf(item.id)))
        try { one.binding(setOf(ItemId(9))); fail("Accepted foreign selection") }
        catch (_: IllegalArgumentException) { }
    }
}
