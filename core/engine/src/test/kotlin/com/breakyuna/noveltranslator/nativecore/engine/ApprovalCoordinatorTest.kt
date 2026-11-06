package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalDecision
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalMode
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalStatus
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApprovalCoordinatorTest {
    private val chat = ChatIdentity(ProjectId("project"), ChatId("chat"))
    private val otherChat = ChatIdentity(ProjectId("project"), ChatId("other"))
    private var now = 1_000L

    private fun coordinator(store: FakeApprovalStore) = ApprovalCoordinator(store) { now }

    @Test
    fun manualApprovalYieldsExactlyOneConsumableGrant() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        val pending = coordinator.request(id, changeSet(), ApprovalMode.MANUAL)
        assertEquals(ApprovalStatus.PENDING, pending.status)
        assertEquals(1, coordinator.pending(chat).size)

        expectGrantRejected(ApprovalGrantRejected.Reason.NOT_APPROVED) { coordinator.consume(id) }
        val decided = coordinator.decide(id, ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalStatus.APPROVED, decided.status)

        val grant = coordinator.consume(id)
        assertEquals(changeSet().digest, grant.changeSetDigest)
        assertEquals(changeSet().id, grant.changeSetId)
        assertEquals(1, store.consumeCount)
        assertEquals(ApprovalStatus.CONSUMED, store.status(id))
        expectGrantRejected(ApprovalGrantRejected.Reason.ALREADY_CONSUMED) { coordinator.consume(id) }
        assertTrue(coordinator.pending(chat).isEmpty())
    }

    @Test
    fun rejectionIsTerminalAndNeverProducesGrant() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        coordinator.request(id, changeSet(), ApprovalMode.MANUAL)
        assertEquals(ApprovalStatus.REJECTED, coordinator.decide(id, ApprovalDecision.REJECT).status)
        expectGrantRejected(ApprovalGrantRejected.Reason.NOT_APPROVED) { coordinator.consume(id) }
        expectUnavailable(ApprovalUnavailable.Reason.ALREADY_SETTLED) {
            coordinator.decide(id, ApprovalDecision.ALLOW_ONCE)
        }
        assertEquals(ApprovalStatus.REJECTED, store.status(id))
        assertEquals(0, store.consumeCount)
    }

    @Test
    fun secondDecisionCannotOverwriteTheFirstSettledOutcome() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        coordinator.request(id, changeSet(), ApprovalMode.MANUAL)
        coordinator.decide(id, ApprovalDecision.ALLOW_ONCE)
        val failure = expectUnavailable(ApprovalUnavailable.Reason.ALREADY_SETTLED) {
            coordinator.decide(id, ApprovalDecision.REJECT)
        }
        assertEquals(ApprovalStatus.APPROVED, failure.status)
        assertEquals(ApprovalStatus.APPROVED, store.status(id))
    }

    @Test
    fun clockExpiryBlocksAnApprovalAfterTheDeadline() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        coordinator.request(id, changeSet(), ApprovalMode.MANUAL, timeoutMillis = 10)
        now += 11
        val failure = expectUnavailable(ApprovalUnavailable.Reason.EXPIRED) {
            coordinator.decide(id, ApprovalDecision.ALLOW_ONCE)
        }
        assertEquals(ApprovalStatus.EXPIRED, failure.status)
        expectGrantRejected(ApprovalGrantRejected.Reason.NOT_APPROVED) { coordinator.consume(id) }
    }

    @Test
    fun expireOverdueOnlySettlesRequestsPastTheirOwnDeadline() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        coordinator.request(ApprovalRequestId("expiring"), changeSet(), ApprovalMode.MANUAL, timeoutMillis = 10)
        coordinator.request(
            ApprovalRequestId("open"),
            changeSet(id = "open-changes", chatId = ChatId("other")),
            ApprovalMode.MANUAL,
        )
        now += 11
        assertEquals(1, coordinator.expireOverdue(chat))
        assertEquals(0, coordinator.expireOverdue(otherChat))
        assertEquals(ApprovalStatus.EXPIRED, store.status(ApprovalRequestId("expiring")))
        assertEquals(ApprovalStatus.PENDING, store.status(ApprovalRequestId("open")))
    }

    @Test
    fun cancellingARunIgnoresOtherRuns() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        coordinator.request(id, changeSet(runId = RunId("run")), ApprovalMode.MANUAL)
        assertEquals(0, coordinator.cancelRun(chat, RunId("other-run")))
        assertEquals(1, coordinator.cancelRun(chat, RunId("run")))
        val failure = expectUnavailable(ApprovalUnavailable.Reason.ALREADY_SETTLED) {
            coordinator.decide(id, ApprovalDecision.ALLOW_ONCE)
        }
        assertEquals(ApprovalStatus.CANCELLED, failure.status)
    }

    @Test
    fun automaticModeApprovesWithoutAPendingDecision() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        val view = coordinator.request(id, changeSet(), ApprovalMode.AUTO)
        assertEquals(ApprovalStatus.APPROVED, view.status)
        assertEquals(ApprovalMode.AUTO, view.record.mode)
        assertTrue(coordinator.pending(chat).isEmpty())
        expectUnavailable(ApprovalUnavailable.Reason.ALREADY_SETTLED) {
            coordinator.decide(id, ApprovalDecision.REJECT)
        }
        assertNotNull(coordinator.consume(id))
    }

    @Test
    fun onlyOneDecisionCanBePendingPerChat() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        coordinator.request(ApprovalRequestId("first"), changeSet(), ApprovalMode.MANUAL)
        expectUnavailable(ApprovalUnavailable.Reason.ALREADY_PENDING) {
            coordinator.request(ApprovalRequestId("second"), changeSet(id = "second"), ApprovalMode.MANUAL)
        }
        coordinator.request(
            ApprovalRequestId("other-chat"),
            changeSet(id = "other-chat", chatId = ChatId("other")),
            ApprovalMode.MANUAL,
        )
    }

    @Test
    fun repeatingTheSameRequestIsIdempotentButDifferentContentConflicts() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        val id = ApprovalRequestId("approval")
        coordinator.request(id, changeSet(), ApprovalMode.MANUAL)
        assertEquals(ApprovalStatus.PENDING, coordinator.request(id, changeSet(), ApprovalMode.MANUAL).status)
        expectConflict { coordinator.request(id, changeSet(content = "b"), ApprovalMode.MANUAL) }
        assertEquals(ApprovalStatus.PENDING, coordinator.request(id, changeSet(), ApprovalMode.MANUAL).status)
    }

    @Test
    fun nonPositiveTimeoutIsRejectedBeforeAnyRecordExists() = runTest {
        val store = FakeApprovalStore()
        val coordinator = coordinator(store)
        try {
            coordinator.request(ApprovalRequestId("approval"), changeSet(), ApprovalMode.MANUAL, timeoutMillis = 0)
            fail("Expected an invalid timeout")
        } catch (_: IllegalArgumentException) {
            // Expected: a zero-length deadline would be expired on arrival.
        }
        assertTrue(store.pending(chat).isEmpty())
    }

    private suspend fun expectUnavailable(
        reason: ApprovalUnavailable.Reason,
        block: suspend () -> Unit,
    ): ApprovalUnavailable {
        try {
            block()
            fail("Expected ApprovalUnavailable $reason")
            throw IllegalStateException("unreachable")
        } catch (failure: ApprovalUnavailable) {
            assertEquals(reason, failure.reason)
            return failure
        }
    }

    private suspend fun expectGrantRejected(reason: ApprovalGrantRejected.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ApprovalGrantRejected $reason")
        } catch (failure: ApprovalGrantRejected) {
            assertEquals(reason, failure.reason)
        }
    }

    private suspend fun expectConflict(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ApprovalConflict")
        } catch (_: ApprovalConflict) {
            // Expected: same request identity, different content.
        }
    }
}
