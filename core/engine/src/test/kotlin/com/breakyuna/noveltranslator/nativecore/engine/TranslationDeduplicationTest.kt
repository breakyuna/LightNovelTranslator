package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import org.junit.Assert.*
import org.junit.Test

class TranslationDeduplicationTest {
    private fun entry(
        id: Long,
        source: String,
        status: TextItemStatus = TextItemStatus.NONE,
        assetId: Long = 1,
    ): TranslationItemSnapshot {
        val row = id.toInt()
        return TranslationItemSnapshot(ItemId(id), assetId, row, source, status,
            TextProjectFingerprint.item(ItemId(id), row, source, "", status, assetId = assetId, slot = row))
    }

    @Test fun sameFileDuplicatesKeepTheSmallestIdAndDeriveTheRest() {
        val plan = TranslationDeduplication.coordinate(listOf(entry(1, "same"), entry(2, "same")))
        val group = plan.groups.single()
        assertEquals(ItemId(1), group.primaryId)
        assertEquals(listOf(ItemId(2)), group.derivedIds)
        assertEquals(listOf(TranslationDuplicateChange(ItemId(2), TextItemStatus.NONE,
            TextItemStatus.DUPLICATED)), plan.changes)
        assertEquals(ItemId(1), plan.primaryFor(ItemId(2)))
        assertEquals(listOf(ItemId(2)), plan.derivedFor(ItemId(1)))
        assertEquals(TextItemStatus.NONE, plan.statusAfter(ItemId(1), TextItemStatus.NONE))
        assertEquals(TextItemStatus.DUPLICATED, plan.statusAfter(ItemId(2), TextItemStatus.NONE))
    }

    @Test fun sameTextInAnotherFileStaysIndependent() {
        val items = listOf(entry(1, "same", assetId = 1), entry(2, "same", assetId = 2))
        val sameFile = TranslationDeduplication.coordinate(items)
        assertTrue(sameFile.groups.isEmpty())
        assertTrue(sameFile.changes.isEmpty())

        val project = TranslationDeduplication.coordinate(items, TranslationDuplicateScope.PROJECT)
        assertEquals(listOf(ItemId(2)), project.changes.map { it.itemId })
    }

    @Test fun exactTextIsNotNormalized() {
        val items = listOf(
            entry(1, "Hello", assetId = 1),
            entry(2, "Hello", assetId = 1),
            entry(3, "hello", assetId = 1),
            entry(4, "hello", assetId = 1),
            entry(5, "Hello ", assetId = 1),
            entry(6, "Hello ", assetId = 1),
        )
        val plan = TranslationDeduplication.coordinate(items)
        assertEquals(3, plan.groups.size)
        assertEquals(3, plan.groups.map { it.key }.toSet().size)
        assertEquals(listOf(ItemId(2), ItemId(4), ItemId(6)), plan.changes.map { it.itemId })
    }

    @Test fun processedAndErrorRowsAnchorTheirGroups() {
        val processed = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.PROCESSED),
            entry(2, "same"),
            entry(3, "same"),
        ))
        val processedGroup = processed.groups.single()
        assertEquals(listOf(ItemId(1)), processedGroup.anchorIds)
        assertEquals(ItemId(1), processedGroup.primaryId)
        assertEquals(listOf(ItemId(2), ItemId(3)), processedGroup.derivedIds)
        assertEquals(listOf(ItemId(2), ItemId(3)), processed.changes.map { it.itemId })

        val failed = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.ERROR),
            entry(2, "same"),
        ))
        assertEquals(ItemId(1), failed.groups.single().primaryId)
        assertEquals(listOf(ItemId(2)), failed.changes.map { it.itemId })
    }

    @Test fun primaryStatusChangesAreReconciledWithoutMutatingInput() {
        val initial = listOf(entry(1, "same"), entry(2, "same"))
        val first = TranslationDeduplication.coordinate(initial)
        assertEquals(listOf(ItemId(2)), first.changes.map { it.itemId })
        assertEquals(TextItemStatus.NONE, initial[0].status)
        assertEquals(TextItemStatus.NONE, initial[1].status)

        val promoted = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.PROCESSED),
            entry(2, "same", TextItemStatus.DUPLICATED),
        ))
        assertTrue(promoted.changes.isEmpty())
        assertEquals(ItemId(1), promoted.groups.single().primaryId)

        val orphaned = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.EXCLUDED),
            entry(2, "same", TextItemStatus.DUPLICATED),
        ))
        assertEquals(listOf(TranslationDuplicateChange(ItemId(2), TextItemStatus.DUPLICATED,
            TextItemStatus.NONE)), orphaned.changes)
        assertEquals(ItemId(2), orphaned.groups.single().primaryId)

        val restoredPrimary = TranslationDeduplication.coordinate(listOf(
            entry(1, "same"),
            entry(2, "same", TextItemStatus.DUPLICATED),
        ))
        assertTrue(restoredPrimary.changes.isEmpty())
        assertEquals(ItemId(1), restoredPrimary.groups.single().primaryId)

        val failedPrimary = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.ERROR),
            entry(2, "same", TextItemStatus.DUPLICATED),
        ))
        assertTrue(failedPrimary.changes.isEmpty())
        assertEquals(ItemId(1), failedPrimary.groups.single().primaryId)
    }

    @Test fun disablingDedupRestoresOnlyCoordinatorOwnedStatuses() {
        val plan = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.PROCESSED),
            entry(2, "same", TextItemStatus.DUPLICATED),
            entry(3, "same", TextItemStatus.DUPLICATED),
            entry(4, "same", TextItemStatus.EXCLUDED),
        ), enabled = false)
        assertEquals(listOf(ItemId(2), ItemId(3)), plan.changes.map { it.itemId })
        assertTrue(plan.changes.all { it.to == TextItemStatus.NONE })
        assertTrue(plan.groups.single().derivedIds.isEmpty())
        assertEquals(TextItemStatus.EXCLUDED, plan.statusAfter(ItemId(4), TextItemStatus.EXCLUDED))
    }

    @Test fun affectedItemsOnlyRecomputeTheirOwnGroups() {
        val items = listOf(
            entry(1, "alpha", assetId = 1),
            entry(2, "alpha", assetId = 1),
            entry(3, "beta", assetId = 2),
            entry(4, "beta", assetId = 2),
        )
        val plan = TranslationDeduplication.coordinate(items, affectedItemIds = setOf(ItemId(2)))
        assertEquals(1, plan.groups.size)
        assertEquals(listOf(ItemId(2)), plan.changes.map { it.itemId })
        assertNull(plan.primaryFor(ItemId(4)))
    }

    @Test fun filteredOrExcludedRowsNeverAnchorAGroup() {
        val plan = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.RULE_SKIPPED),
            entry(2, "same"),
            entry(3, "same"),
        ))
        val group = plan.groups.single()
        assertTrue(group.anchorIds.isEmpty())
        assertEquals(ItemId(2), group.primaryId)
        assertEquals(listOf(ItemId(3)), group.derivedIds)
        assertEquals(TextItemStatus.RULE_SKIPPED, plan.statusAfter(ItemId(1), TextItemStatus.RULE_SKIPPED))
    }

    @Test fun multipleAnchorsUseTheLowestIdentityWithoutChanges() {
        val plan = TranslationDeduplication.coordinate(listOf(
            entry(1, "same", TextItemStatus.PROCESSED),
            entry(2, "same", TextItemStatus.ERROR),
            entry(3, "same"),
        ))
        val group = plan.groups.single()
        assertEquals(listOf(ItemId(1), ItemId(2)), group.anchorIds)
        assertEquals(ItemId(1), group.primaryId)
        assertEquals(listOf(ItemId(3)), group.derivedIds)
        assertEquals(listOf(ItemId(3)), plan.changes.map { it.itemId })
    }

    @Test fun applyingTheSamePlanTwiceIsIdempotent() {
        val change = TranslationDuplicateChange(ItemId(2), TextItemStatus.NONE, TextItemStatus.DUPLICATED)
        val plan = TranslationDeduplication.coordinate(listOf(entry(1, "same"), entry(2, "same")))
        assertEquals(TextItemStatus.DUPLICATED, plan.statusAfter(change.itemId, change.from))
        assertEquals(TextItemStatus.DUPLICATED, plan.statusAfter(change.itemId, change.to))
    }
}
