package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSteerTest {
    private val project = ProjectId("project")
    private val chat = ChatIdentity(project, ChatId("chat"))
    private val run = RunIdentity(project, ChatId("chat"), RunId("run"), CommandId("run-command"))

    @Test
    fun steerIsAcceptedDuringTheRunButInjectedOnlyAtAnExplicitToolLoopBoundary() {
        var state = state()
        val accepted = state.accept(command(text = "focus on chapter two"))
        state = accepted.first
        assertEquals(AgentSteerStatus.ACCEPTED, accepted.second.status)
        assertEquals(AgentRunStage.MODEL_REQUEST, accepted.second.submittedDuring)

        val blocked = state.injectAtToolBoundary()
        assertTrue(blocked is AgentSteerInjectionResult.Rejected)
        val blockedResult = blocked as AgentSteerInjectionResult.Rejected
        assertEquals(AgentSteerFailure.NOT_AT_TOOL_BOUNDARY, blockedResult.reason)
        assertEquals(AgentSteerStatus.ACCEPTED, blockedResult.state.accepted.single().status)

        state = state.move(AgentRunStage.TOOL_EXECUTION).move(AgentRunStage.TOOL_LOOP_BOUNDARY)
        val injected = state.injectAtToolBoundary()
        assertTrue(injected is AgentSteerInjectionResult.Injected)
        val effect = injected as AgentSteerInjectionResult.Injected
        assertEquals(1, effect.injections.size)
        assertEquals("focus on chapter two", effect.injections.single().text)
        assertEquals(AgentRunStage.TOOL_LOOP_BOUNDARY, effect.injections.single().boundary)
        assertEquals(AgentSteerStatus.INJECTED, effect.state.attempts.single().status)
        assertEquals(AgentRunStage.TOOL_LOOP_BOUNDARY, effect.state.attempts.single().injectedDuring)
        assertTrue(effect.state.accepted.isEmpty())

        val again = effect.state.injectAtToolBoundary()
        assertTrue(again is AgentSteerInjectionResult.NothingToInject)
    }

    @Test
    fun duplicateCommandIsDeduplicatedAndChangedContentUnderTheSameIdIsRejected() {
        val accepted = state().accept(command())
        var state = accepted.first
        val duplicate = state.submit(command())
        assertTrue(duplicate is AgentSteerSubmitResult.Duplicate)
        assertEquals(accepted.second, (duplicate as AgentSteerSubmitResult.Duplicate).attempt)
        assertEquals(1, state.attempts.size)

        val conflict = state.submit(command(text = "different instruction"))
        assertTrue(conflict is AgentSteerSubmitResult.Rejected)
        val conflictResult = conflict as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.DUPLICATE_COMMAND_CONFLICT, conflictResult.reason)
        assertEquals("different instruction", conflictResult.attempt.text)
        assertEquals(1, state.attempts.size)
        assertEquals("steer", state.attempts.single().text)

        state = state.move(AgentRunStage.TOOL_LOOP_BOUNDARY)
        val injected = (state.injectAtToolBoundary() as AgentSteerInjectionResult.Injected).state
        val duplicateAfterInjection = injected.submit(command())
        assertTrue(duplicateAfterInjection is AgentSteerSubmitResult.Duplicate)
        assertEquals(
            AgentSteerStatus.INJECTED,
            (duplicateAfterInjection as AgentSteerSubmitResult.Duplicate).attempt.status,
        )
        assertEquals(1, injected.attempts.size)
    }

    @Test
    fun stopCancelsAcceptedInputsWithoutLosingTextAndRejectsLateSubmissions() {
        var state = state()
        state = state.accept(command(id = "first", text = "first instruction")).first
        state = state.accept(command(id = "second", text = "second instruction")).first

        val stopped = state.stop()
        assertTrue(stopped is AgentSteerStageResult.Applied)
        val stopping = (stopped as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.STOPPING, stopping.stage)
        assertEquals(2, stopping.cancelled.size)
        assertEquals(listOf("first instruction", "second instruction"), stopping.cancelled.map { it.text })
        assertTrue(stopping.cancelled.all { it.status == AgentSteerStatus.CANCELLED })
        assertTrue(stopping.cancelled.all { it.reason == AgentSteerFailure.RUN_STOPPING })

        val injectionAfterStop = stopping.injectAtToolBoundary()
        assertTrue(injectionAfterStop is AgentSteerInjectionResult.Rejected)
        assertEquals(
            AgentSteerFailure.RUN_STOPPING,
            (injectionAfterStop as AgentSteerInjectionResult.Rejected).reason,
        )

        val rejected = stopping.submit(command(id = "third", text = "third instruction"))
        assertTrue(rejected is AgentSteerSubmitResult.Rejected)
        val rejectedResult = rejected as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.RUN_STOPPING, rejectedResult.reason)
        assertEquals("third instruction", rejectedResult.attempt.text)
        assertEquals(2, stopping.attempts.size)

        val markStopped = stopping.markStopped()
        assertTrue(markStopped is AgentSteerStageResult.Applied)
        val stoppedState = (markStopped as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.STOPPED, stoppedState.stage)
        val late = stoppedState.submit(command(id = "fourth", text = "fourth instruction"))
        assertTrue(late is AgentSteerSubmitResult.Rejected)
        val lateResult = late as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.RUN_STOPPED, lateResult.reason)
        assertEquals("fourth instruction", lateResult.attempt.text)
        assertEquals(2, stoppedState.cancelled.size)
    }

    @Test
    fun finishAndFailureCancelAcceptedSteersAndRejectLaterInputs() {
        val finished = state().accept(command(id = "finish", text = "finish instruction")).first.finish()
        assertTrue(finished is AgentSteerStageResult.Applied)
        val finishedState = (finished as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.FINISHED, finishedState.stage)
        assertEquals(AgentSteerFailure.RUN_FINISHED, finishedState.cancelled.single().reason)
        assertEquals("finish instruction", finishedState.cancelled.single().text)
        val afterFinish = finishedState.submit(command(id = "late", text = "late instruction"))
        assertTrue(afterFinish is AgentSteerSubmitResult.Rejected)
        assertEquals(
            AgentSteerFailure.RUN_FINISHED,
            (afterFinish as AgentSteerSubmitResult.Rejected).reason,
        )

        val failed = state().accept(command(id = "fail", text = "fail instruction")).first.fail()
        assertTrue(failed is AgentSteerStageResult.Applied)
        val failedState = (failed as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.FAILED, failedState.stage)
        assertEquals(AgentSteerFailure.RUN_FAILED, failedState.cancelled.single().reason)
        val afterFailure = failedState.submit(command(id = "after-failure", text = "retry instruction"))
        assertTrue(afterFailure is AgentSteerSubmitResult.Rejected)
        val afterFailureResult = afterFailure as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.RUN_FAILED, afterFailureResult.reason)
        assertEquals("retry instruction", afterFailureResult.attempt.text)
    }

    @Test
    fun steerIdentityNeverCrossesRunOrChat() {
        val state = state()
        val otherProject = ProjectId("other-project")
        val otherChat = ChatIdentity(otherProject, ChatId("other-chat"))
        val otherRun = RunIdentity(
            otherProject,
            ChatId("other-chat"),
            RunId("other-run"),
            CommandId("other-command"),
        )
        val wrongChat = state.submit(
            AgentSteerCommand(CommandId("wrong-chat"), otherRun, otherChat, "wrong chat instruction"),
        )
        assertTrue(wrongChat is AgentSteerSubmitResult.Rejected)
        val wrongChatResult = wrongChat as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.WRONG_CHAT, wrongChatResult.reason)
        assertEquals("wrong chat instruction", wrongChatResult.attempt.text)
        assertTrue(state.attempts.isEmpty())

        val sameChatOtherRun = RunIdentity(
            project,
            ChatId("chat"),
            RunId("other-run"),
            CommandId("other-command"),
        )
        val wrongRun = state.submit(
            AgentSteerCommand(CommandId("wrong-run"), sameChatOtherRun, chat, "wrong run instruction"),
        )
        assertTrue(wrongRun is AgentSteerSubmitResult.Rejected)
        val wrongRunResult = wrongRun as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.WRONG_RUN, wrongRunResult.reason)
        assertEquals("wrong run instruction", wrongRunResult.attempt.text)
        assertTrue(state.attempts.isEmpty())
    }

    @Test
    fun activeStageTransitionsAreExplicitAndTerminalStatesAreFinal() {
        val boundary = state().move(AgentRunStage.TOOL_LOOP_BOUNDARY)
        assertEquals(AgentRunStage.TOOL_LOOP_BOUNDARY, boundary.stage)
        val invalidMove = boundary.moveTo(AgentRunStage.STOPPING)
        assertTrue(invalidMove is AgentSteerStageResult.Rejected)
        assertEquals(
            AgentSteerFailure.INVALID_STAGE_TRANSITION,
            (invalidMove as AgentSteerStageResult.Rejected).reason,
        )

        val stopping = (boundary.stop() as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.STOPPING, stopping.stage)
        val moveAfterStop = stopping.moveTo(AgentRunStage.MODEL_REQUEST)
        assertTrue(moveAfterStop is AgentSteerStageResult.Rejected)
        assertEquals(
            AgentSteerFailure.INVALID_STAGE_TRANSITION,
            (moveAfterStop as AgentSteerStageResult.Rejected).reason,
        )

        val stopped = (stopping.markStopped() as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.STOPPED, stopped.stage)
        assertTrue(stopped.stop() is AgentSteerStageResult.Applied)
        assertTrue(stopped.finish() is AgentSteerStageResult.Rejected)
        assertTrue(stopped.moveTo(AgentRunStage.TOOL_LOOP_BOUNDARY) is AgentSteerStageResult.Rejected)

        val failed = (state().fail() as AgentSteerStageResult.Applied).state
        assertEquals(AgentRunStage.FAILED, failed.stage)
        assertTrue(failed.fail() is AgentSteerStageResult.Applied)
        assertTrue(failed.finish() is AgentSteerStageResult.Rejected)
    }

    @Test
    fun steerLedgerHasAnExplicitAttemptBudgetAndRejectsWithoutLosingInput() {
        var state = state()
        repeat(AgentSteerState.MAX_ATTEMPTS) { index ->
            state = state.accept(command(id = "budget-$index", text = "instruction $index")).first
        }
        assertEquals(AgentSteerState.MAX_ATTEMPTS, state.attempts.size)
        val rejected = state.submit(command(id = "over-budget", text = "over budget instruction"))
        assertTrue(rejected is AgentSteerSubmitResult.Rejected)
        val rejectedResult = rejected as AgentSteerSubmitResult.Rejected
        assertEquals(AgentSteerFailure.STEER_BUDGET_EXCEEDED, rejectedResult.reason)
        assertEquals("over budget instruction", rejectedResult.attempt.text)
        assertEquals(AgentSteerState.MAX_ATTEMPTS, state.attempts.size)
    }

    @Test
    fun injectedSteerSurvivesStopAsAnInLoopFactButNoNewSteerIsAccepted() {
        val boundary = state().accept(command(id = "injected", text = "in loop")).first
            .move(AgentRunStage.TOOL_LOOP_BOUNDARY)
        val injected = (boundary.injectAtToolBoundary() as AgentSteerInjectionResult.Injected).state
        val stopped = (injected.stop() as AgentSteerStageResult.Applied).state
        assertEquals(1, stopped.injected.size)
        assertTrue(stopped.cancelled.isEmpty())
        assertTrue(stopped.accepted.isEmpty())
        val duplicate = stopped.submit(command(id = "injected", text = "in loop"))
        assertTrue(duplicate is AgentSteerSubmitResult.Duplicate)
        assertEquals(
            AgentSteerStatus.INJECTED,
            (duplicate as AgentSteerSubmitResult.Duplicate).attempt.status,
        )
    }

    private fun state(stage: AgentRunStage = AgentRunStage.MODEL_REQUEST): AgentSteerState =
        AgentSteerState.start(run, chat, stage)

    private fun command(id: String = "steer-command", text: String = "steer"): AgentSteerCommand =
        AgentSteerCommand(CommandId(id), run, chat, text)

    private fun AgentSteerState.accept(command: AgentSteerCommand): Pair<AgentSteerState, AgentSteerAttempt> {
        val result = submit(command)
        assertTrue(result is AgentSteerSubmitResult.Accepted)
        val accepted = result as AgentSteerSubmitResult.Accepted
        return accepted.state to accepted.attempt
    }

    private fun AgentSteerState.move(stage: AgentRunStage): AgentSteerState {
        val result = moveTo(stage)
        assertTrue(result is AgentSteerStageResult.Applied)
        return (result as AgentSteerStageResult.Applied).state
    }
}
