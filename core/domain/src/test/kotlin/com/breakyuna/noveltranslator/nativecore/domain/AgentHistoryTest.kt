package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentHistoryTest {
    private val empty = JsonObjectValue.parse("{}", JsonInputLimits(128, 4))

    @Test
    fun preservesParallelCallPairingWithoutRewritingRoles() {
        AgentHistory.validate(listOf(user("u"), calls("a", "one", "two"), result("r2", "two"), result("r1", "one")))
    }

    @Test
    fun requestLocalProviderIdsDoNotReplaceStableInternalCallIdentity() {
        fun assistant(message: String, internal: String) = AgentMessage.Assistant(MessageId(message), listOf(
            AssistantPart.ToolCall(AgentToolCall(ToolCallId(internal), "lookup", empty, providerCallId = "call_0")),
        ))
        AgentHistory.validate(listOf(user("u"), assistant("a1", "internal-1"), result("r1", "internal-1"),
            assistant("a2", "internal-2"), result("r2", "internal-2")))
    }

    @Test
    fun requestsCannotSkipOrLosePendingResults() {
        val history = listOf(user("u"), calls("a", "one"))
        assertReason(InvalidAgentHistory.Reason.MISSING_RESULT) { AgentHistory.validate(history) }
        assertReason(InvalidAgentHistory.Reason.MISSING_RESULT) { AgentHistory.validate(history + user("late")) }
        AgentHistory.validate(history, allowPendingAtTail = true)
    }

    @Test
    fun rejectsUnmatchedWrongNamedAndDuplicateResults() {
        assertReason(InvalidAgentHistory.Reason.UNMATCHED_RESULT) { AgentHistory.validate(listOf(result("r", "one"))) }
        assertReason(InvalidAgentHistory.Reason.UNMATCHED_RESULT) {
            AgentHistory.validate(listOf(calls("a", "one"), result("r", "one", "different")))
        }
        assertReason(InvalidAgentHistory.Reason.UNMATCHED_RESULT) {
            AgentHistory.validate(listOf(calls("a", "one"), result("r1", "one"), result("r2", "one")))
        }
    }

    @Test
    fun duplicateMessageAndCallIdentitiesAreRejected() {
        assertReason(InvalidAgentHistory.Reason.DUPLICATE_MESSAGE) { AgentHistory.validate(listOf(user("same"), user("same"))) }
        assertReason(InvalidAgentHistory.Reason.DUPLICATE_CALL) {
            AgentHistory.validate(listOf(calls("a", "one", "one")), allowPendingAtTail = true)
        }
    }

    @Test
    fun systemInstructionsCannotBeInsertedAsLaterConversationMessages() {
        assertReason(InvalidAgentHistory.Reason.SYSTEM_POSITION) {
            AgentHistory.validate(listOf(user("u"), AgentMessage.System(MessageId("s"), "instruction")))
        }
    }

    @Test
    fun constructorFreezesCallerOwnedPartLists() {
        val parts = mutableListOf<UserPart>(UserPart.Text("original"))
        val message = AgentMessage.User(MessageId("u"), parts)
        parts.clear()
        assertEquals(1, message.parts.size)
        assertEquals("original", (message.parts.single() as UserPart.Text).text)
    }

    private fun user(id: String) = AgentMessage.User(MessageId(id), listOf(UserPart.Text("synthetic")))
    private fun calls(id: String, vararg calls: String) = AgentMessage.Assistant(MessageId(id), calls.map {
        AssistantPart.ToolCall(AgentToolCall(ToolCallId(it), "lookup", empty))
    })
    private fun result(id: String, call: String, name: String = "lookup") =
        AgentMessage.ToolResult(MessageId(id), ToolCallId(call), name, empty, false)
    private fun assertReason(reason: InvalidAgentHistory.Reason, body: () -> Unit) {
        assertEquals(reason, assertThrows(InvalidAgentHistory::class.java) { body() }.reason)
    }
}
