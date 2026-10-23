package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import org.junit.Assert.*
import org.junit.Test

class TranslationRetryPlannerTest {
    private fun item(id: Long): TranslationItemSnapshot {
        val itemId = ItemId(id)
        val row = id.toInt()
        return TranslationItemSnapshot(itemId, 1, row, "source $id", TextItemStatus.NONE,
            TextProjectFingerprint.item(itemId, row, "source $id", "", TextItemStatus.NONE))
    }

    private fun context(ids: IntRange, attempts: Int = 0, splitDepth: Int = 0): TranslationRetryContext =
        TranslationRetryContext(ids.map { item(it.toLong()) }, attempts, splitDepth)

    @Test fun transientFailuresRetryTheSameBatchWithBoundedBackoff() {
        val planner = TranslationRetryPlanner(TranslationRetryBudget(
            maxAttempts = 3, baseBackoffMillis = 500, maxBackoffMillis = 1_500,
        ))
        val first = planner.decide(context(1..3), ModelFailureKind.NETWORK)
        assertEquals(TranslationRetryOutcome.RETRY, first.outcome)
        assertEquals(TranslationRetryRule.RETRY_TRANSIENT, first.rule)
        assertEquals(500L, first.backoffMillis)
        val firstBlock = first.retryBlocks.single()
        assertEquals(1, firstBlock.attempts)
        assertEquals(listOf(1L, 2L, 3L), firstBlock.itemIds().map { it.value })

        assertEquals(1_000L, planner.decide(context(1..3, attempts = 1), ModelFailureKind.TIMEOUT).backoffMillis)
        assertEquals(1_500L, planner.decide(context(1..3, attempts = 2), ModelFailureKind.RATE_LIMIT).backoffMillis)

        val exhausted = planner.decide(context(1..3, attempts = 3), ModelFailureKind.SERVER)
        assertEquals(TranslationRetryOutcome.PERMANENT, exhausted.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_ATTEMPTS_EXHAUSTED, exhausted.rule)
        assertEquals(listOf(1L, 2L, 3L), exhausted.permanentItemIds.map { it.value })
        assertTrue(exhausted.retryBlocks.isEmpty())
    }

    @Test fun everyTransientKindAndEmptyResponseUsesTheRetryPath() {
        val planner = TranslationRetryPlanner()
        for (kind in listOf(ModelFailureKind.NETWORK, ModelFailureKind.TIMEOUT,
            ModelFailureKind.RATE_LIMIT, ModelFailureKind.SERVER, ModelFailureKind.EMPTY_RESPONSE)) {
            val decision = planner.decide(context(1..2), kind)
            assertEquals(kind.name, TranslationRetryOutcome.RETRY, decision.outcome)
        }
    }

    @Test fun contextOverflowHalvesTheBatchWithoutDroppingItems() {
        val planner = TranslationRetryPlanner()
        val decision = planner.decide(context(1..4), ModelFailureKind.CONTEXT_OVERFLOW)
        assertEquals(TranslationRetryOutcome.SPLIT, decision.outcome)
        assertEquals(TranslationRetryRule.SPLIT_BATCH, decision.rule)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)),
            decision.retryBlocks.map { block -> block.itemIds().map { it.value } })
        assertTrue(decision.retryBlocks.all { it.splitDepth == 1 && it.attempts == 1 })
        assertTrue(decision.permanentItemIds.isEmpty())
        assertEquals(0L, decision.backoffMillis)

        val single = planner.decide(context(1..1), ModelFailureKind.CONTEXT_OVERFLOW)
        assertEquals(TranslationRetryOutcome.PERMANENT, single.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_CONTEXT_OVERFLOW, single.rule)
    }

    @Test fun protocolFailuresSplitThenRetryOneSingleItemOnce() {
        val planner = TranslationRetryPlanner()
        val split = planner.decide(context(1..2), ModelFailureKind.PARSING)
        assertEquals(TranslationRetryOutcome.SPLIT, split.outcome)
        assertTrue(split.retryBlocks.all { it.items.size == 1 && it.attempts == 0 && it.splitDepth == 1 })

        val block = split.retryBlocks.first()
        val retry = planner.decide(TranslationRetryContext(block.items, block.attempts, block.splitDepth),
            ModelFailureKind.PARSING)
        assertEquals(TranslationRetryOutcome.RETRY, retry.outcome)
        assertEquals(TranslationRetryRule.RETRY_SINGLE_ITEM, retry.rule)
        assertEquals(1, retry.retryBlocks.single().attempts)
        assertEquals(500L, retry.backoffMillis)

        val exhausted = planner.decide(TranslationRetryContext(block.items, 1, block.splitDepth),
            ModelFailureKind.TRUNCATED)
        assertEquals(TranslationRetryOutcome.PERMANENT, exhausted.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_ATTEMPTS_EXHAUSTED, exhausted.rule)
    }

    @Test fun truncatedResponsesSplitLargeBatches() {
        val planner = TranslationRetryPlanner()
        val decision = planner.decide(context(1..3), ModelFailureKind.TRUNCATED)
        assertEquals(TranslationRetryOutcome.SPLIT, decision.outcome)
        assertEquals(2, decision.retryBlocks.size)
        assertEquals(3, decision.retryItemIds().size)
    }

    @Test fun authenticationAndContentFilterArePermanentImmediately() {
        val planner = TranslationRetryPlanner()
        for (kind in listOf(ModelFailureKind.AUTHENTICATION, ModelFailureKind.CONTENT_FILTERED)) {
            val decision = planner.decide(context(1..4), kind)
            assertEquals(kind.name, TranslationRetryOutcome.PERMANENT, decision.outcome)
            assertTrue(decision.retryBlocks.isEmpty())
            assertEquals(listOf(1L, 2L, 3L, 4L), decision.permanentItemIds.map { it.value })
        }
        assertEquals(TranslationRetryRule.PERMANENT_AUTHENTICATION,
            planner.decide(context(1..1), ModelFailureKind.AUTHENTICATION).rule)
    }

    @Test fun unknownAndContentFailuresDoNotBlindlyRetry() {
        val planner = TranslationRetryPlanner()
        val unknown = planner.decide(context(1..2), ModelFailureKind.UNKNOWN)
        assertEquals(TranslationRetryOutcome.PERMANENT, unknown.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_UNKNOWN, unknown.rule)

        val multiContent = planner.decide(context(1..2), ModelFailureKind.CONTENT)
        assertEquals(TranslationRetryOutcome.SPLIT, multiContent.outcome)

        val singleContent = planner.decide(context(1..1), ModelFailureKind.CONTENT)
        assertEquals(TranslationRetryOutcome.PERMANENT, singleContent.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_CONTENT, singleContent.rule)
    }

    @Test fun committedItemsAreNeverRetried() {
        val planner = TranslationRetryPlanner()
        val decision = planner.decide(context(1..4), ModelFailureKind.PARSING,
            setOf(ItemId(2), ItemId(3)))
        assertEquals(listOf(1L, 4L), decision.retryItemIds().map { it.value })
        assertEquals(listOf(2L, 3L), decision.committedItemIds.map { it.value })
        assertTrue(decision.permanentItemIds.isEmpty())
        assertTrue(decision.retryItemIds().none { it.value == 2L || it.value == 3L })

        val complete = planner.decide(context(1..4), ModelFailureKind.NETWORK,
            setOf(ItemId(1), ItemId(2), ItemId(3), ItemId(4)))
        assertEquals(TranslationRetryOutcome.COMPLETE, complete.outcome)
        assertEquals(TranslationRetryRule.COMPLETE, complete.rule)
        assertTrue(complete.retryBlocks.isEmpty())
        assertEquals(4, complete.committedItemIds.size)
    }

    @Test fun committedIdentitiesOutsideTheContextAreRejected() {
        val planner = TranslationRetryPlanner()
        try {
            planner.decide(context(1..2), ModelFailureKind.NETWORK, setOf(ItemId(9)))
            fail("Accepted a committed identity outside the context")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun splitBudgetStopsFurtherSplitting() {
        val planner = TranslationRetryPlanner(TranslationRetryBudget(maxSplitDepth = 0))
        val decision = planner.decide(context(1..4), ModelFailureKind.CONTEXT_OVERFLOW)
        assertEquals(TranslationRetryOutcome.PERMANENT, decision.outcome)
        assertEquals(TranslationRetryRule.PERMANENT_SPLIT_EXHAUSTED, decision.rule)
        assertEquals(4, decision.permanentItemIds.size)
    }

    @Test fun everyItemIsAccountedForExactlyOnce() {
        val decision = TranslationRetryPlanner().decide(context(1..5, attempts = 1),
            ModelFailureKind.PARSING)
        val accounted = decision.retryItemIds() + decision.permanentItemIds + decision.committedItemIds
        assertEquals(5, accounted.size)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), accounted.map { it.value }.toSet())
    }
}
