package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChangeSetTest {
    @Test
    fun digestIsStableForTheSameBatch() {
        assertEquals(changeSet(update("item-1")).digest, changeSet(update("item-1")).digest)
    }

    @Test
    fun digestCoversContentIdentityAndProjectIdentity() {
        val base = changeSet(update("item-1", content = "a"))
        assertNotEquals(base.digest, changeSet(update("item-1", content = "b")).digest)
        assertNotEquals(base.digest, changeSet(update("item-1"), id = "other-set").digest)
        assertNotEquals(base.digest, changeSet(update("item-1"), runId = "other-run").digest)
        assertNotEquals(
            base.digest,
            PreparedChangeSet(
                ChangeSetId("set"), ProjectId("other-project"), ChatId("chat"), RunId("run"),
                listOf(update("item-1")),
            ).digest,
        )
    }

    @Test
    fun emptyBatchIsRejected() {
        expectInvalid(InvalidChangeSet.Reason.EMPTY) { changeSet() }
    }

    @Test
    fun duplicateTargetInOneBatchIsRejected() {
        expectInvalid(InvalidChangeSet.Reason.DUPLICATE_TARGET) {
            changeSet(update("item-1"), create("item-1"))
        }
    }

    @Test
    fun creationCannotCarryABaseRevisionAndExistingObjectsRequireOne() {
        assertThrowsIllegalArgument {
            PreparedChange(ChangeTarget(ChangeTargetKind.ITEM, "item-1"), ChangeOperation.CREATE,
                ObjectFingerprint(hex("a")), ObjectFingerprint(hex("b")))
        }
        assertThrowsIllegalArgument {
            PreparedChange(ChangeTarget(ChangeTargetKind.ITEM, "item-1"), ChangeOperation.UPDATE,
                null, ObjectFingerprint(hex("b")))
        }
    }

    @Test
    fun invalidUnicodeCannotBeHashed() {
        expectInvalid(InvalidChangeSet.Reason.INVALID_UNICODE) {
            changeSet(update("\uD800"))
        }
    }

    @Test
    fun summaryCountsEveryObjectFamily() {
        val summary = changeSet(
            create("item-1"), update("item-2"),
            PreparedChange(ChangeTarget(ChangeTargetKind.PDF_PAGE, "3"), ChangeOperation.UPDATE,
                ObjectFingerprint(hex("a")), ObjectFingerprint(hex("b"))),
            PreparedChange(ChangeTarget(ChangeTargetKind.GLOSSARY, "term"), ChangeOperation.CREATE,
                null, ObjectFingerprint(hex("b"))),
            PreparedChange(ChangeTarget(ChangeTargetKind.ASSET, "asset-1"), ChangeOperation.REORDER,
                ObjectFingerprint(hex("a")), ObjectFingerprint(hex("c"))),
        ).summary
        assertEquals(2, summary.items)
        assertEquals(1, summary.pages)
        assertEquals(1, summary.glossary)
        assertEquals(1, summary.assets)
        assertEquals(5, summary.total)
    }

    private fun changeSet(vararg changes: PreparedChange, id: String = "set", runId: String = "run") =
        PreparedChangeSet(ChangeSetId(id), ProjectId("project"), ChatId("chat"), RunId(runId), changes.toList())

    private fun create(id: String) = PreparedChange(
        ChangeTarget(ChangeTargetKind.ITEM, id), ChangeOperation.CREATE, null, ObjectFingerprint(hex("b")),
    )

    private fun update(id: String, content: String = "a") = PreparedChange(
        ChangeTarget(ChangeTargetKind.ITEM, id), ChangeOperation.UPDATE,
        ObjectFingerprint(hex("a")), ObjectFingerprint(hex(content)),
    )

    private fun hex(character: String) = character.repeat(64)

    private fun expectInvalid(reason: InvalidChangeSet.Reason, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected InvalidChangeSet $reason")
        } catch (failure: InvalidChangeSet) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: the change breaks the base-revision contract.
        }
    }
}
