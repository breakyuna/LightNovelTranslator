package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompactionPlannerTest {
    private val policy = AgentCompactionPolicy(maxContextChars = 4_096, maxContextBytes = 8_192,
        minUserTurns = 3, retainUserTurns = 2, maxSummaryChars = 1_000)

    private fun json() = JsonObjectValue.parse("{}", JsonInputLimits(1024, 4))
    private fun user(id: String, text: String = "request") =
        AgentMessage.User(MessageId(id), listOf(UserPart.Text(text)))
    private fun assistant(id: String, text: String = "reply") =
        AgentMessage.Assistant(MessageId(id), listOf(AssistantPart.Text(text)))
    private fun toolCall(id: String, name: String = "project_summary") =
        AgentToolCall(ToolCallId(id + "-call"), name, json())
    private fun toolResult(id: String, call: AgentToolCall) =
        AgentMessage.ToolResult(MessageId(id), call.id, call.name, json(), false)

    @Test fun belowEitherBudgetIsNotCompacted() {
        val history = listOf(user("u1", "short"), assistant("a1"), user("u2"), assistant("a2"))
        assertTrue(AgentCompactionPlanner.plan(history, policy) is AgentCompactionDecision.NotNeeded)
        val bytes = AgentCompactionPolicy(maxContextChars = 4_096, maxContextBytes = 4_096,
            minUserTurns = 3, retainUserTurns = 2, maxSummaryChars = 1_000)
        assertTrue(AgentCompactionPlanner.plan(history, bytes) is AgentCompactionDecision.NotNeeded)
    }

    @Test fun cutKeepsTheLastCompleteTurnsAndNeverSeparatesAToolPair() {
        val call = toolCall("first")
        val history = listOf(
            user("u1", "x".repeat(5_000)),
            AgentMessage.Assistant(MessageId("a1"), listOf(AssistantPart.ToolCall(call))),
            toolResult("r1", call),
            user("u2"),
            assistant("a2"),
            user("u3"),
            assistant("a3"),
        )
        val decision = AgentCompactionPlanner.plan(history, policy)
        assertTrue(decision is AgentCompactionDecision.Ready)
        val plan = (decision as AgentCompactionDecision.Ready).plan
        assertEquals(3, plan.cut.index)
        assertEquals(3, plan.cut.archivedMessages)
        assertEquals(4, plan.cut.retainedMessages)
        assertEquals(1, plan.cut.archivedUserTurns)
        assertEquals(2, plan.cut.retainedUserTurns)
        assertEquals(MessageId("r1"), plan.cut.boundary)
        assertEquals(MessageId("u2"), plan.cut.firstRetained)
        assertTrue(plan.archivedChars >= 5_000)
    }

    @Test fun leadingSummaryIsNotCountedAsAnArchivedConversationMessage() {
        val history = listOf(
            AgentMessage.System(MessageId("summary"), "Earlier goals and decisions"),
            user("u1", "x".repeat(5_000)),
            assistant("a1"),
            user("u2"),
            assistant("a2"),
            user("u3"),
            assistant("a3"),
        )
        val plan = (AgentCompactionPlanner.plan(history, policy) as AgentCompactionDecision.Ready).plan
        assertEquals(3, plan.cut.index)
        assertEquals(2, plan.cut.archivedMessages)
        assertEquals(MessageId("a1"), plan.cut.boundary)
    }

    @Test fun pendingToolCallIsRefusedInsteadOfBeingTruncated() {
        val call = toolCall("pending")
        val history = listOf(
            user("u1", "x".repeat(5_000)),
            assistant("a1"),
            user("u2"),
            assistant("a2"),
            user("u3"),
            AgentMessage.Assistant(MessageId("a3"), listOf(AssistantPart.ToolCall(call))),
        )
        val decision = AgentCompactionPlanner.plan(history, policy)
        assertEquals(AgentCompactionDecision.Refused(AgentCompactionRefusal.PENDING_TOOL_CALL), decision)
    }

    @Test fun tooFewUserTurnsIsRefusedAfterTheWindowExceedsTheBudget() {
        val history = listOf(user("u1", "x".repeat(5_000)), assistant("a1"),
            user("u2"), assistant("a2"))
        assertEquals(AgentCompactionDecision.Refused(AgentCompactionRefusal.INSUFFICIENT_USER_TURNS),
            AgentCompactionPlanner.plan(history, policy))
    }

    @Test fun safeCutRefusesWhenThereIsNothingToArchive() {
        assertNull(AgentCompactionPlanner.safeCut(listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2")), 2))
        assertNull(AgentCompactionPlanner.safeCut(emptyList(), 1))
    }

    @Test fun invalidHistoryIsNeverCompacted() {
        val history = listOf(user("u1", "x".repeat(5_000)), assistant("a1"),
            AgentMessage.System(MessageId("late-system"), "late"), user("u2"), assistant("a2"))
        assertEquals(AgentCompactionDecision.Refused(AgentCompactionRefusal.INVALID_HISTORY),
            AgentCompactionPlanner.plan(history, policy))
    }
}
