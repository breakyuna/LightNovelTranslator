package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class AgentContextCutTest {
    @Test fun toolCallAndResultStayTogetherBeforeTheCut() {
        val call = AgentToolCall(ToolCallId("call"), "query", JsonObjectValue.parse("{}", JsonInputLimits(1024, 4)))
        val messages = listOf(
            AgentMessage.User(MessageId("u1"), listOf(UserPart.Text("first"))),
            AgentMessage.Assistant(MessageId("a1"), listOf(AssistantPart.ToolCall(call))),
            AgentMessage.ToolResult(MessageId("r1"), call.id, call.name, JsonObjectValue.parse("{}", JsonInputLimits(1024, 4)), false),
            AgentMessage.User(MessageId("u2"), listOf(UserPart.Text("second"))),
            AgentMessage.Assistant(MessageId("a2"), listOf(AssistantPart.Text("answer"))),
            AgentMessage.User(MessageId("u3"), listOf(UserPart.Text("third"))),
            AgentMessage.Assistant(MessageId("a3"), listOf(AssistantPart.Text("answer"))),
        )
        assertEquals(3, AgentContextCut.index(messages))
        try { AgentContextCut.index(messages.filterNot { it is AgentMessage.ToolResult }); fail("Cut unmatched tools") }
        catch (_: InvalidAgentHistory) { }
    }
}
