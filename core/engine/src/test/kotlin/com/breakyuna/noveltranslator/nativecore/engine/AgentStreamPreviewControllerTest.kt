package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.*

class AgentStreamPreviewControllerTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val run = RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("send"))
    private fun key(run: RunIdentity = this.run, attempt: String = "attempt") = AgentRequestKey(run, BranchId("main"), RequestAttemptId(attempt))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun sparseTailFlushesWithoutAnotherNetworkEventAndCannotCrossRuns() { runTest {
        var now = 0L
        val controller = AgentStreamPreviewController(backgroundScope) { now }
        val session = controller.open(ref, run)
        session.previewStarted(key())
        session.preview(key(), AgentModelEvent.TextDelta("first"))
        now = 1
        session.preview(key(), AgentModelEvent.TextDelta(" tail"))
        assertEquals("first", controller.state.value.preview!!.text)
        runCurrent(); advanceTimeBy(16); runCurrent()
        assertEquals("first tail", controller.state.value.preview!!.text)
        session.preview(key(), AgentModelEvent.TextDelta(" stale"))
        controller.open(ref, run.copy(runId = RunId("replacement")))
        advanceTimeBy(32); runCurrent()
        assertNull(controller.state.value.preview)
    } }

    @Test fun throttledDeltasAreCoalescedAndFinalFlushContainsAllTextAndThinking() { runTest {
        var now = 0L; val controller = AgentStreamPreviewController(backgroundScope) { now }
        val session = controller.open(ref, run); val key = key()
        session.previewStarted(key); session.preview(key, AgentModelEvent.TextDelta("one"))
        val first = controller.state.value.revision
        now = 1; session.preview(key, AgentModelEvent.TextDelta("two")); session.preview(key, AgentModelEvent.ThinkingDelta("thinking"))
        assertEquals(first, controller.state.value.revision)
        session.previewCompleted(key, MessageId("final")); session.previewFinished(key)
        val preview = controller.state.value.preview!!
        assertEquals("onetwo", preview.text); assertEquals("thinking", preview.thinking)
        assertEquals(AgentPreviewPhase.RECORDING, preview.phase)
        session.previewCommitted(key); assertNull(controller.state.value.preview)
        session.close()
    } }

    @Test fun staleDeltasAndFinallyCannotEraseANewerExecution() {
        val controller = AgentStreamPreviewController(); val old = controller.open(ref, run)
        old.previewStarted(key()); old.preview(key(), AgentModelEvent.TextDelta("old"))
        val nextRun = run.copy(runId = RunId("next")); val next = controller.open(ref, nextRun)
        val nextKey = key(nextRun)
        next.previewStarted(nextKey); next.preview(nextKey, AgentModelEvent.TextDelta("new"))
        old.previewFinished(key()); old.close(); old.preview(key(), AgentModelEvent.TextDelta("late"))
        assertEquals("new", controller.state.value.preview!!.text)
        assertEquals(nextRun, controller.state.value.preview!!.key.run)
    }

    @Test fun activeLongReplyRetainsItsBeginningUntilTheInterruptedTailIsNeeded() {
        val controller = AgentStreamPreviewController()
        val session = controller.open(ref, run)
        val request = key()
        session.previewStarted(request)
        val text = "beginning " + "x".repeat(20_000)
        session.preview(request, AgentModelEvent.TextDelta(text))
        assertEquals(text, controller.state.value.preview!!.text)
        assertFalse(controller.state.value.preview!!.clipped)
        session.close(keepPartial = true)
        assertEquals(8192, controller.state.value.preview!!.text.length)
        assertTrue(controller.state.value.preview!!.clipped)
    }

    @Test fun interruptionRetainsOnlyABoundedEphemeralTailAndRejectsLateFragments() {
        val controller = AgentStreamPreviewController(); val session = controller.open(ref, run); val key = key()
        session.previewStarted(key); session.preview(key, AgentModelEvent.TextDelta("x".repeat(20_000)))
        session.previewFinished(key); session.close(keepPartial = true)
        val preview = controller.state.value.preview!!
        assertEquals(8192, preview.text.length); assertTrue(preview.clipped); assertEquals(AgentPreviewPhase.INTERRUPTED, preview.phase)
        session.preview(key, AgentModelEvent.TextDelta("late")); assertEquals(preview.text, controller.state.value.preview!!.text)
        controller.clear(); assertNull(controller.state.value.preview)
    }

    @Test fun attemptsAreIsolatedAndOnlyFullCanonicalCommitClearsAPreview() {
        val controller = AgentStreamPreviewController(); val session = controller.open(ref, run); val first = key()
        session.previewStarted(first); session.preview(first, AgentModelEvent.ToolCallDelta(0)); session.previewFinished(first)
        session.previewCommitted(first)
        val second = key(attempt = "second"); session.previewStarted(second)
        session.preview(second, AgentModelEvent.TextDelta("second")); session.previewCommitted(first)
        assertEquals("second", controller.state.value.preview!!.text)
        session.close(keepPartial = true)
        assertEquals(AgentPreviewPhase.INTERRUPTED, controller.state.value.preview!!.phase)
    }
}
