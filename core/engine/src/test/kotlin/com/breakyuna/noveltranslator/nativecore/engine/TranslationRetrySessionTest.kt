package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class TranslationRetrySessionTest {
    private fun item(id: Long): TranslationItemSnapshot {
        val key = ItemId(id)
        return TranslationItemSnapshot(key, 1, id.toInt(), "source $id", TextItemStatus.NONE,
            TextProjectFingerprint.item(key, id.toInt(), "source $id", "", TextItemStatus.NONE))
    }
    @Test fun receiptRecoveryUsesTheSameDecisionAsLiveExecutionForEveryFailureAndBudget() {
        val items = (1L..3L).map(::item)
        for (budget in 0..5) for (failure in ModelFailureKind.entries) {
            val live = TranslationRetrySession.start(items, budget)
            live.begin(RequestAttemptId("response"))
            val active = live.checkpoint
            live.failed(failure, 100)
            val recovered = DefaultTranslationRetryRecoveryPolicy.afterFailure(active, failure, 100)
            assertEquals(live.checkpoint.targets, recovered.targets)
            assertEquals(live.checkpoint.remainingAdditional, recovered.remainingAdditional)
            assertEquals(live.checkpoint.terminalFailure, recovered.terminalFailure)
            assertEquals(live.checkpoint.attempt, recovered.attempt)
            assertEquals(live.checkpoint.notBeforeMillis, recovered.notBeforeMillis)
            assertEquals(live.checkpoint.pending.map { Triple(it.ids, it.attempts, it.splitDepth) },
                recovered.pending.map { Triple(it.ids, it.attempts, it.splitDepth) })
            assertEquals(live.checkpoint.active?.ids, recovered.active?.ids)
        }
    }

    @Test fun splitChildrenShareTheOriginalBudgetAcrossRestore() {
        val items = (1L..4L).map(::item)
        val original = TranslationRetrySession.start(items, 3)
        original.begin(RequestAttemptId("parent"))
        original.failed(ModelFailureKind.PARSING, 100)
        assertEquals(1, original.checkpoint.remainingAdditional)
        val restored = TranslationRetrySession.restore(items, original.checkpoint)
        assertEquals(listOf(1L, 2L), restored.next().items.map { it.id.value })
        restored.begin(RequestAttemptId("first-child")); restored.succeeded()
        assertEquals(listOf(3L, 4L), restored.next().items.map { it.id.value })
        restored.begin(RequestAttemptId("second-child"))
        restored.failed(ModelFailureKind.PARSING, 200)
        assertEquals(ModelFailureKind.PARSING, restored.checkpoint.terminalFailure)
        assertEquals(1, restored.checkpoint.remainingAdditional)
        assertThrows(IllegalStateException::class.java) { restored.next() }
    }
    @Test fun restoreRetainsBackoffAndDoesNotReplenishSpentRequests() {
        val items = listOf(item(1))
        val original = TranslationRetrySession.start(items, 1)
        original.begin(RequestAttemptId("first"))
        original.failed(ModelFailureKind.NETWORK, 1_000)
        val restored = TranslationRetrySession.restore(items, original.checkpoint)
        assertEquals(1_500L, restored.checkpoint.notBeforeMillis)
        assertEquals(0, restored.checkpoint.remainingAdditional)
        assertEquals(1, restored.next().attempts)
        restored.begin(RequestAttemptId("second"))
        restored.failed(ModelFailureKind.NETWORK, 2_000)
        assertEquals(ModelFailureKind.NETWORK, restored.checkpoint.terminalFailure)
    }
    @Test fun activeAttemptCannotBeDispatchedAgainWithoutReceiptReconciliation() {
        val items = listOf(item(1)); val original = TranslationRetrySession.start(items, 5)
        original.begin(RequestAttemptId("uncertain"))
        val restored = TranslationRetrySession.restore(items, original.checkpoint)
        assertEquals(RequestAttemptId("uncertain"), restored.checkpoint.attempt)
        assertThrows(IllegalStateException::class.java) { restored.next() }
        assertThrows(IllegalStateException::class.java) { restored.begin(RequestAttemptId("replay")) }
    }
    @Test fun snapshotRejectsDuplicateMembershipChangedTargetsAndOverlappingBlocks() {
        val items = arrayListOf(item(1), item(2))
        val session = TranslationRetrySession.start(items, 2)
        items.clear()
        assertEquals(2, session.next().items.size)
        val state = session.checkpoint
        assertThrows(IllegalArgumentException::class.java) { TranslationRetrySession.restore(listOf(item(1)), state) }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRetryCheckpoint(state.targets, 2, 3, state.pending)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationRetryCheckpoint(state.targets, 2, 2, state.pending + state.pending)
        }
    }
    @Test fun successfulUnitFinishesWithoutCreatingMoreBudgetOrQueueEntries() {
        val session = TranslationRetrySession.start(listOf(item(1)), 5)
        session.begin(RequestAttemptId("one")); session.succeeded()
        assertFalse(session.pending)
        assertNull(session.checkpoint.active)
        assertEquals(5, session.checkpoint.remainingAdditional)
        assertThrows(IllegalStateException::class.java) { session.succeeded() }
    }
}
