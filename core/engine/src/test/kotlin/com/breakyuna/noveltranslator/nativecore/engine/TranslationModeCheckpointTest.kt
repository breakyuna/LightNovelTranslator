package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import org.junit.Assert.*
import org.junit.Test

class TranslationModeCheckpointTest {
    private val REF = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun row(
        id: Long,
        source: String,
        status: TextItemStatus = TextItemStatus.NONE,
        assetId: Long = 1,
    ): TranslationItemSnapshot {
        val row = id.toInt()
        return TranslationItemSnapshot(ItemId(id), assetId, row, source, status,
            TextProjectFingerprint.item(ItemId(id), row, source, "", status, assetId = assetId, slot = row))
    }

    private fun snapshot(items: List<TranslationItemSnapshot>, revision: Long = 3): TranslationTargetSnapshot =
        TranslationTargetSnapshot(REF, revision, items)

    private fun command(
        mode: TranslationRunMode = TranslationRunMode.CONTINUE,
        revision: Long = 3,
        scope: TranslationCommandScope = TranslationCommandScope.all(),
    ): TranslationModeCommand = TranslationModeCommand(REF, mode, revision, scope)

    private fun planFingerprint(): ObjectFingerprint =
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8("plan")))

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Accepted an invalid checkpoint")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun itemScopesAreSortedFrozenAndDuplicateFree() {
        val scope = TranslationCommandScope.items(listOf(ItemId(3), ItemId(1), ItemId(2)))
        assertEquals(listOf(1L, 2L, 3L), scope.itemIds.map { it.value })
        assertEquals(TranslationCommandScopeKind.ITEMS, scope.kind)
        assertTrue(scope.contains(ItemId(2)))
        assertFalse(scope.contains(ItemId(9)))
        assertTrue(TranslationCommandScope.all().contains(ItemId(9)))
        assertTrue(TranslationCommandScope.all().itemIds.isEmpty())
        rejects { TranslationCommandScope.items(listOf(ItemId(1), ItemId(1))) }
        rejects { TranslationCommandScope.items(emptyList<ItemId>()) }
    }

    @Test fun modesSelectExpectedStatusesAndActions() {
        val items = listOf(
            row(1, "one", TextItemStatus.NONE),
            row(2, "two", TextItemStatus.ERROR),
            row(3, "three", TextItemStatus.PROCESSED),
            row(4, "four", TextItemStatus.EXCLUDED),
            row(5, "five", TextItemStatus.RULE_SKIPPED),
            row(6, "six", TextItemStatus.LANGUAGE_SKIPPED),
            row(7, "seven", TextItemStatus.DUPLICATED),
        )
        val target = snapshot(items)

        val newResult = command(TranslationRunMode.NEW).applyTo(target)
        assertEquals(TranslationModeStatus.APPLICABLE, newResult.status)
        assertEquals(listOf(1L), newResult.targets.map { it.id.value })
        assertEquals(TranslationTargetAction.TRANSLATE, newResult.targets.single().action)
        assertEquals(TranslationSelectionReason.FAILED_ITEM, newResult.exclusions.first { it.id == ItemId(2) }.reason)
        assertEquals(TranslationSelectionReason.ALREADY_TRANSLATED, newResult.exclusions.first { it.id == ItemId(3) }.reason)
        assertEquals(TranslationSelectionReason.EXCLUDED, newResult.exclusions.first { it.id == ItemId(4) }.reason)
        assertEquals(TranslationSelectionReason.RULE_SKIPPED, newResult.exclusions.first { it.id == ItemId(5) }.reason)
        assertEquals(TranslationSelectionReason.LANGUAGE_SKIPPED, newResult.exclusions.first { it.id == ItemId(6) }.reason)
        assertEquals(TranslationSelectionReason.DUPLICATED, newResult.exclusions.first { it.id == ItemId(7) }.reason)

        val continueResult = command(TranslationRunMode.CONTINUE).applyTo(target)
        assertEquals(listOf(1L, 2L), continueResult.targets.map { it.id.value })

        val resetResult = command(TranslationRunMode.RESET).applyTo(target)
        assertEquals(listOf(1L, 2L, 3L), resetResult.targets.map { it.id.value })
        assertTrue(resetResult.targets.all { it.action == TranslationTargetAction.RETRANSLATE })
    }

    @Test fun itemScopeRestrictsTargetsAndUnknownIdsAreRefused() {
        val items = listOf(row(1, "one"), row(2, "two"))
        val scoped = command(scope = TranslationCommandScope.items(listOf(ItemId(2)))).applyTo(snapshot(items))
        assertEquals(listOf(2L), scoped.targets.map { it.id.value })
        assertEquals(TranslationSelectionReason.NOT_IN_SCOPE, scoped.exclusions.first { it.id == ItemId(1) }.reason)

        val unknown = command(scope = TranslationCommandScope.items(listOf(ItemId(1), ItemId(9))))
            .applyTo(snapshot(items))
        assertEquals(TranslationModeStatus.UNKNOWN_SCOPE_ITEMS, unknown.status)
        assertTrue(unknown.targets.isEmpty())
        assertEquals(listOf(ItemId(9)), unknown.unknownScopeItemIds)
    }

    @Test fun staleIdentityAndRevisionAreRefusedBeforeSelection() {
        val items = listOf(row(1, "one"))
        val staleRevision = command(revision = 2).applyTo(snapshot(items, revision = 3))
        assertEquals(TranslationModeStatus.STALE_REVISION, staleRevision.status)
        assertTrue(staleRevision.targets.isEmpty())

        val otherRef = LocalProjectRef(ProjectId("other"), ProjectInstanceId("instance"), RecoveryGeneration(1))
        val staleIdentity = TranslationModeCommand(otherRef, TranslationRunMode.CONTINUE, 3)
            .applyTo(snapshot(items, revision = 3))
        assertEquals(TranslationModeStatus.STALE_IDENTITY, staleIdentity.status)
        assertTrue(staleIdentity.targets.isEmpty())
    }

    @Test fun checkpointRoundTripsWithAllScope() {
        val checkpoint = TranslationCheckpoint(RunId("run-1"), REF, TranslationRunMode.CONTINUE,
            TranslationCommandScope.all(), 7, 0, emptyList(), 0, planFingerprint())
        val encoded = TranslationCheckpointCodec.encode(checkpoint)
        val decoded = TranslationCheckpointCodec.decode(encoded)
        assertEquals(checkpoint, decoded)
        assertEquals(encoded, TranslationCheckpointCodec.encode(decoded))
        assertFalse(encoded.contains("source"))
        assertEquals(TranslationCommandScope.all(), decoded.scope)
    }

    @Test fun checkpointRoundTripsWithItemScopeAndAttemptTail() {
        val checkpoint = TranslationCheckpoint(RunId("run-1"), REF, TranslationRunMode.RESET,
            TranslationCommandScope.items(listOf(ItemId(10), ItemId(11))), 7, 11,
            listOf(ItemId(2), ItemId(5), ItemId(11)), 2, planFingerprint())
        val encoded = TranslationCheckpointCodec.encode(checkpoint)
        val decoded = TranslationCheckpointCodec.decode(encoded)
        assertEquals(checkpoint, decoded)
        assertEquals(TranslationRunMode.RESET, decoded.mode)
        assertEquals(listOf(10L, 11L), decoded.scope.itemIds.map { it.value })
        assertEquals(listOf(2L, 5L, 11L), decoded.attemptedItemIds.map { it.value })
        assertEquals(2, decoded.committedItems)
        assertEquals(11L, decoded.cursor)
    }

    @Test fun tamperedOrNonCanonicalCheckpointsAreRejected() {
        val checkpoint = TranslationCheckpoint(RunId("run-1"), REF, TranslationRunMode.CONTINUE,
            TranslationCommandScope.items(listOf(ItemId(10), ItemId(11))), 7, 5,
            listOf(ItemId(2), ItemId(5)), 1, planFingerprint())
        val encoded = TranslationCheckpointCodec.encode(checkpoint)

        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"version\":1", "\"version\":2")) }
        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"mode\":\"CONTINUE\"", "\"mode\":\"PAUSE\"")) }
        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"kind\":\"ITEMS\"", "\"kind\":\"OTHER\"")) }
        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"committed_items\":1", "\"committed_items\":-1")) }
        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"cursor\":5,", "")) }
        rejects { TranslationCheckpointCodec.decode(encoded.dropLast(1) + ",\"source\":\"leak\"}") }
        rejects { TranslationCheckpointCodec.decode(encoded.dropLast(1) + ",\"mode\":\"RESET\"}") }
        rejects { TranslationCheckpointCodec.decode(encoded.replace("\"attempted_item_ids\":[2,5]",
            "\"attempted_item_ids\":[5,2]")) }
        rejects { TranslationCheckpointCodec.decode(encoded.replace(planFingerprint().sha256,
            "z".repeat(64))) }
        rejects { TranslationCheckpointCodec.decode("{\"format\":\"" + "x".repeat(70_000)) }
    }

    @Test fun checkpointBoundsAttemptsAndRequiresConsistentCursor() {
        rejects {
            TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE, TranslationCommandScope.all(),
                1, 4, listOf(ItemId(5)), 0, planFingerprint())
        }
        rejects {
            TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE, TranslationCommandScope.all(),
                1, 5, listOf(ItemId(2), ItemId(2)), 0, planFingerprint())
        }
        rejects {
            TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE, TranslationCommandScope.all(),
                1, 5, (1L..201L).map { ItemId(it) }, 0, planFingerprint())
        }
        rejects {
            TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE, TranslationCommandScope.all(),
                1, 5, emptyList(), -1, planFingerprint())
        }
        rejects {
            TranslationCheckpoint(RunId("x".repeat(2_049)), REF, TranslationRunMode.CONTINUE,
                TranslationCommandScope.all(), 1, 5, emptyList(), 0, planFingerprint())
        }

        val checkpoint = TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE,
            TranslationCommandScope.items(listOf(ItemId(10), ItemId(11))), 1, 5,
            listOf(ItemId(2), ItemId(5)), 1, planFingerprint())
        val encoded = TranslationCheckpointCodec.encode(checkpoint)
        val tooManyAttempts = (1L..201L).joinToString(",")
        rejects {
            TranslationCheckpointCodec.decode(encoded.replace("\"attempted_item_ids\":[2,5]",
                "\"attempted_item_ids\":[$tooManyAttempts]"))
        }
    }

    @Test fun attemptTailKeepsOnlyTheMostRecentBoundedIdentities() {
        val initial = TranslationCheckpoint(RunId("run"), REF, TranslationRunMode.CONTINUE,
            TranslationCommandScope.all(), 1, 200, (1L..200L).map { ItemId(it) }, 200, planFingerprint())
        val advanced = initial.withAttemptedItemIds(listOf(ItemId(201)), cursor = 201, committedItems = 200)
        assertEquals(TranslationCheckpoint.MAX_ATTEMPTED_ITEMS, advanced.attemptedItemIds.size)
        assertEquals(2L, advanced.attemptedItemIds.first().value)
        assertEquals(201L, advanced.attemptedItemIds.last().value)
        assertEquals(201L, advanced.cursor)
    }
}
