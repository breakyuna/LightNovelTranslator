package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInputQueueTest {
    private val project = ProjectId("project")
    private val chat = ChatIdentity(project, ChatId("chat"))
    private val agentRun = RunIdentity(project, ChatId("chat"), RunId("run"), CommandId("run-command"))

    @Test
    fun consumesOnlyTheHeadAndOnlyAfterTheHistoryAppendIsConfirmed() {
        val initial = queue()
            .withDraft(draft("a", "first"))
            .withDraft(draft("b", "second"))
        assertEquals(listOf("a", "b"), initial.drafts.map { it.id.value })

        val started = initial.start("attempt-1")
        assertEquals(listOf("a", "b"), started.first.drafts.map { it.id.value })
        assertEquals("a", started.first.pendingAppend?.draft?.id?.value)
        assertEquals("first", started.second.text)
        val secondStart = started.first.beginAppend(agentRun, RequestAttemptId("attempt-2"))
        assertTrue(secondStart is AgentInputAppendStartResult.Rejected)
        assertEquals(
            AgentInputQueueFailure.APPEND_PENDING,
            (secondStart as AgentInputAppendStartResult.Rejected).reason,
        )

        val consumed = started.first.acknowledge(started.second, "message-1")
        assertEquals(listOf("b"), consumed.queue.drafts.map { it.id.value })
        assertNull(consumed.queue.pendingAppend)
        assertEquals(MessageId("message-1"), consumed.messageId)
        assertEquals("a", consumed.appended.draft.id.value)
        assertEquals("second", consumed.queue.head?.text)
        assertEquals(listOf("a", "b"), initial.drafts.map { it.id.value })
        assertNull(initial.pendingAppend)
    }

    @Test
    fun editingIsIdentityPreservingAndBlockedForThePendingDraft() {
        val queue = queue()
            .withDraft(draft("a", "first"))
            .withDraft(draft("b", "second"))
        val edited = queue.edit(CommandId("b"), "edited")
        assertTrue(edited is AgentInputQueueUpdate.Applied)
        val editedQueue = (edited as AgentInputQueueUpdate.Applied).queue
        val draftB = editedQueue.drafts.single { it.id == CommandId("b") }
        assertEquals("edited", draftB.text)
        assertEquals(2L, draftB.revision)
        assertEquals(CommandId("b"), draftB.id)
        assertEquals("second", queue.drafts.single { it.id == CommandId("b") }.text)

        val started = editedQueue.start("attempt-1")
        expectUpdateFailure(AgentInputQueueFailure.DRAFT_IN_FLIGHT, started.first.edit(CommandId("a"), "changed"))
        expectUpdateFailure(AgentInputQueueFailure.DRAFT_IN_FLIGHT, started.first.delete(CommandId("a")))

        val withoutB = started.first.delete(CommandId("b"))
        assertTrue(withoutB is AgentInputQueueUpdate.Applied)
        assertEquals(listOf("a"), (withoutB as AgentInputQueueUpdate.Applied).queue.drafts.map { it.id.value })
    }

    @Test
    fun failedAppendKeepsTheDraftAndOldReceiptsCannotConsumeItsNewRevision() {
        val initial = queue().withDraft(draft("a", "first"))
        val first = initial.start("attempt-1")
        val released = first.first.release(CommandId("a"), "attempt-1")
        assertEquals(listOf("a"), released.drafts.map { it.id.value })
        assertNull(released.pendingAppend)

        val edited = (released.edit(CommandId("a"), "edited") as AgentInputQueueUpdate.Applied).queue
        assertEquals(2L, edited.drafts.single().revision)

        val second = edited.start("attempt-2")
        val oldReceipt = receipt(first.second, "message-old")
        assertEquals(
            AgentInputAppendFailure.STALE_ATTEMPT,
            expectIgnored(second.first, oldReceipt),
        )
        assertEquals("a", second.first.pendingAppend?.draft?.id?.value)

        val staleRevision = AgentInputAppendReceipt(
            draftId = second.second.draft.id,
            chat = chat,
            run = agentRun,
            attemptId = second.second.attemptId,
            draftRevision = 1,
            messageId = MessageId("message-stale"),
        )
        assertEquals(
            AgentInputAppendFailure.STALE_REVISION,
            expectIgnored(second.first, staleRevision),
        )
        assertEquals("edited", second.first.pendingAppend?.draft?.text)

        val current = AgentInputAppendReceipt(
            draftId = second.second.draft.id,
            chat = chat,
            run = agentRun,
            attemptId = second.second.attemptId,
            draftRevision = second.second.draft.revision,
            messageId = MessageId("message-current"),
        )
        val consumed = second.first.acknowledgeAppended(current)
        assertTrue(consumed is AgentInputAppendResult.Consumed)
        assertTrue((consumed as AgentInputAppendResult.Consumed).queue.drafts.isEmpty())
    }

    @Test
    fun pauseBlocksStartUntilResumed() {
        val paused = (queue().withDraft(draft("a", "first")).pause() as AgentInputQueueUpdate.Applied).queue
        assertTrue(paused.paused)
        val blocked = paused.beginAppend(agentRun, RequestAttemptId("attempt-1"))
        assertTrue(blocked is AgentInputAppendStartResult.Rejected)
        assertEquals(
            AgentInputQueueFailure.QUEUE_PAUSED,
            (blocked as AgentInputAppendStartResult.Rejected).reason,
        )

        val resumed = (paused.resume() as AgentInputQueueUpdate.Applied).queue
        assertFalse(resumed.paused)
        val started = resumed.start("attempt-2")
        assertEquals("a", started.first.pendingAppend?.draft?.id?.value)
    }

    @Test
    fun lateAndOldReceiptsNeverClearANewerDraft() {
        val first = queue()
            .withDraft(draft("a", "first"))
            .withDraft(draft("b", "second"))
            .start("attempt-1")
        val oldReceipt = receipt(first.second, "message-old")
        val afterA = first.first.acknowledgeAppended(oldReceipt)
        assertTrue(afterA is AgentInputAppendResult.Consumed)
        var queue = (afterA as AgentInputAppendResult.Consumed).queue
        assertEquals(listOf("b"), queue.drafts.map { it.id.value })

        // A duplicate receipt after consumption must not clear the next queued draft.
        assertEquals(
            AgentInputAppendFailure.NO_PENDING_APPEND,
            expectIgnored(queue, oldReceipt),
        )
        assertEquals(listOf("b"), queue.drafts.map { it.id.value })

        queue = queue.withDraft(draft("c", "third"))
        val second = queue.start("attempt-2")
        // A late receipt for the already-consumed A must not consume B or erase C.
        assertEquals(AgentInputAppendFailure.WRONG_DRAFT, expectIgnored(second.first, oldReceipt))
        assertEquals(listOf("b", "c"), second.first.drafts.map { it.id.value })
        assertEquals("b", second.first.pendingAppend?.draft?.id?.value)
        assertEquals("second", second.first.drafts.first().text)

        val afterB = second.first.acknowledge(second.second, "message-b")
        assertEquals(listOf("c"), afterB.queue.drafts.map { it.id.value })
        assertEquals("third", afterB.queue.head?.text)
        assertEquals(AgentInputAppendFailure.NO_PENDING_APPEND, expectIgnored(afterB.queue, oldReceipt))
        assertEquals("third", afterB.queue.head?.text)
    }

    @Test
    fun queueIdentityNeverCrossesProjectOrChat() {
        val otherProject = ProjectId("other-project")
        val otherChat = ChatIdentity(otherProject, ChatId("chat"))
        val otherRun = RunIdentity(otherProject, ChatId("chat"), RunId("other-run"), CommandId("other-command"))
        val sameProjectOtherChat = ChatIdentity(project, ChatId("other-chat"))

        val queue = queue().withDraft(draft("a", "first"))
        expectUpdateFailure(
            AgentInputQueueFailure.WRONG_CHAT,
            queue.enqueue(AgentInputDraft.create(CommandId("x"), otherChat, "other")),
        )
        expectUpdateFailure(
            AgentInputQueueFailure.WRONG_CHAT,
            queue.enqueue(AgentInputDraft.create(CommandId("y"), sameProjectOtherChat, "other chat")),
        )
        assertEquals(listOf("a"), queue.drafts.map { it.id.value })

        val wrongRun = queue.beginAppend(otherRun, RequestAttemptId("attempt-x"))
        assertTrue(wrongRun is AgentInputAppendStartResult.Rejected)
        assertEquals(
            AgentInputQueueFailure.WRONG_CHAT,
            (wrongRun as AgentInputAppendStartResult.Rejected).reason,
        )

        val started = queue.start("attempt-1")
        val foreignReceipt = AgentInputAppendReceipt(
            draftId = CommandId("a"),
            chat = otherChat,
            run = otherRun,
            attemptId = RequestAttemptId("attempt-1"),
            draftRevision = 1,
            messageId = MessageId("foreign-message"),
        )
        assertEquals(
            AgentInputAppendFailure.WRONG_CHAT,
            expectIgnored(started.first, foreignReceipt),
        )
        assertEquals("a", started.first.pendingAppend?.draft?.id?.value)
        assertEquals(1, started.first.drafts.size)
    }

    @Test
    fun draftCountAndTextBudgetsAreExplicitAndReclaimable() {
        val countLimited = AgentInputQueueLimits(maxDrafts = 2, maxDraftChars = 5, maxTotalChars = 10)
        val almostFull = queue(countLimited)
            .withDraft(draft("a", "12345"))
            .withDraft(draft("b", "1234"))
        expectUpdateFailure(AgentInputQueueFailure.QUEUE_FULL, almostFull.enqueue(draft("c", "1")))

        val totalLimited = AgentInputQueueLimits(maxDrafts = 3, maxDraftChars = 5, maxTotalChars = 7)
        val one = queue(totalLimited).withDraft(draft("a", "12345"))
        expectUpdateFailure(AgentInputQueueFailure.TOTAL_CHARS_EXCEEDED, one.enqueue(draft("b", "123")))
        val full = one.withDraft(draft("b", "12"))
        assertEquals(7, full.totalChars)
        expectUpdateFailure(AgentInputQueueFailure.TOTAL_CHARS_EXCEEDED, full.edit(CommandId("b"), "123"))
        assertEquals("12", full.drafts.single { it.id == CommandId("b") }.text)
        expectUpdateFailure(AgentInputQueueFailure.TEXT_EMPTY, full.edit(CommandId("b"), "   "))

        val oversized = AgentInputDraft.create(CommandId("too-long"), chat, "123456")
        val shortLimit = AgentInputQueueLimits(maxDrafts = 3, maxDraftChars = 5, maxTotalChars = 10)
        expectUpdateFailure(AgentInputQueueFailure.TEXT_TOO_LONG, queue(shortLimit).enqueue(oversized))

        expectUpdateFailure(AgentInputQueueFailure.DUPLICATE_DRAFT, full.enqueue(full.drafts.first()))
        expectUpdateFailure(
            AgentInputQueueFailure.DRAFT_ID_CONFLICT,
            full.enqueue(draft("a", "other")),
        )

        val reclaimed = (full.delete(CommandId("a")) as AgentInputQueueUpdate.Applied).queue
        assertEquals(2, reclaimed.totalChars)
        assertEquals("b", reclaimed.head?.id?.value)
    }

    @Test
    fun runBindingRejectsAnotherRunAndEndRunKeepsQueuedDrafts() {
        val runA = RunIdentity(project, ChatId("chat"), RunId("run-a"), CommandId("run-command-a"))
        val runB = RunIdentity(project, ChatId("chat"), RunId("run-b"), CommandId("run-command-b"))
        val queue = queue().withDraft(draft("a", "first", runA))

        val wrongRun = queue.beginAppend(runB, RequestAttemptId("attempt-b"))
        assertTrue(wrongRun is AgentInputAppendStartResult.Rejected)
        assertEquals(
            AgentInputQueueFailure.WRONG_RUN,
            (wrongRun as AgentInputAppendStartResult.Rejected).reason,
        )

        val started = queue.start("attempt-a", runA)
        assertEquals(runA, started.first.run)
        assertEquals(runA, started.first.pendingAppend?.run)
        expectUpdateFailure(AgentInputQueueFailure.WRONG_RUN, started.first.endRun(runB))
        expectUpdateFailure(AgentInputQueueFailure.RUN_HAS_PENDING_APPEND, started.first.endRun(runA))

        val released = started.first.release(CommandId("a"), "attempt-a")
        val ended = (released.endRun(runA) as AgentInputQueueUpdate.Applied).queue
        assertNull(ended.run)
        assertNull(ended.drafts.single().run)
        assertEquals("a", ended.head?.id?.value)

        val rebound = ended.start("attempt-b", runB)
        assertEquals(runB, rebound.first.run)
        assertEquals(runB, rebound.first.drafts.single().run)
        assertEquals("first", rebound.second.text)
    }

    @Test
    fun emptyAndSettledStatesReportExplicitFailures() {
        val empty = queue()
        val noDraft = empty.beginAppend(agentRun, RequestAttemptId("attempt-1"))
        assertTrue(noDraft is AgentInputAppendStartResult.Rejected)
        assertEquals(
            AgentInputQueueFailure.EMPTY_QUEUE,
            (noDraft as AgentInputAppendStartResult.Rejected).reason,
        )
        expectUpdateFailure(
            AgentInputQueueFailure.NO_PENDING_APPEND,
            empty.releaseAppend(RequestAttemptId("attempt-1")),
        )

        val started = empty.withDraft(draft("a", "first")).start("attempt-1")
        expectUpdateFailure(
            AgentInputQueueFailure.STALE_ATTEMPT,
            started.first.releaseAppend(RequestAttemptId("attempt-2")),
        )
        assertEquals("a", started.first.pendingAppend?.draft?.id?.value)
        expectUpdateFailure(AgentInputQueueFailure.UNKNOWN_DRAFT, started.first.edit(CommandId("missing"), "text"))
        expectUpdateFailure(AgentInputQueueFailure.UNKNOWN_DRAFT, started.first.delete(CommandId("missing")))
    }

    private fun queue(limits: AgentInputQueueLimits = AgentInputQueueLimits.DEFAULT): AgentInputQueue =
        AgentInputQueue.empty(chat, limits)

    private fun draft(
        id: String,
        text: String,
        run: RunIdentity? = null,
        limits: AgentInputQueueLimits = AgentInputQueueLimits.DEFAULT,
    ): AgentInputDraft = AgentInputDraft.create(CommandId(id), chat, text, run, limits)

    private fun AgentInputQueue.withDraft(draft: AgentInputDraft): AgentInputQueue {
        val update = enqueue(draft)
        assertTrue(update is AgentInputQueueUpdate.Applied)
        return (update as AgentInputQueueUpdate.Applied).queue
    }

    private fun AgentInputQueue.start(
        attempt: String,
        run: RunIdentity = agentRun,
    ): Pair<AgentInputQueue, AgentInputAppend> {
        val result = beginAppend(run, RequestAttemptId(attempt))
        assertTrue(result is AgentInputAppendStartResult.Started)
        val started = result as AgentInputAppendStartResult.Started
        return started.queue to started.append
    }

    private fun AgentInputQueue.release(id: CommandId, attempt: String): AgentInputQueue {
        val result = releaseAppend(RequestAttemptId(attempt))
        assertTrue(result is AgentInputQueueUpdate.Applied)
        val queue = (result as AgentInputQueueUpdate.Applied).queue
        assertNull(queue.pendingAppend)
        assertEquals(id, queue.head?.id)
        return queue
    }

    private fun AgentInputQueue.acknowledge(
        append: AgentInputAppend,
        message: String,
    ): AgentInputAppendResult.Consumed {
        val result = acknowledgeAppended(receipt(append, message))
        assertTrue(result is AgentInputAppendResult.Consumed)
        return result as AgentInputAppendResult.Consumed
    }

    private fun receipt(append: AgentInputAppend, message: String): AgentInputAppendReceipt =
        AgentInputAppendReceipt(
            draftId = append.draft.id,
            chat = append.draft.chat,
            run = append.run,
            attemptId = append.attemptId,
            draftRevision = append.draft.revision,
            messageId = MessageId(message),
        )

    private fun expectUpdateFailure(
        expected: AgentInputQueueFailure,
        update: AgentInputQueueUpdate,
    ) {
        assertTrue(update is AgentInputQueueUpdate.Rejected)
        assertEquals(expected, (update as AgentInputQueueUpdate.Rejected).reason)
    }

    private fun expectIgnored(queue: AgentInputQueue, receipt: AgentInputAppendReceipt): AgentInputAppendFailure {
        val result = queue.acknowledgeAppended(receipt)
        assertTrue(result is AgentInputAppendResult.Ignored)
        return (result as AgentInputAppendResult.Ignored).reason
    }
}
