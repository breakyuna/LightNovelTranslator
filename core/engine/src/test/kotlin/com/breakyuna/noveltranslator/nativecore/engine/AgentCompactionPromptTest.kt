package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompactionPromptTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatId("chat")

    private fun json() = JsonObjectValue.parse("{}", JsonInputLimits(1024, 4))
    private fun user(id: String, text: String = "request") =
        AgentMessage.User(MessageId(id), listOf(UserPart.Text(text)))
    private fun assistant(id: String, text: String = "reply") =
        AgentMessage.Assistant(MessageId(id), listOf(AssistantPart.Text(text)))
    private fun request(messages: List<AgentMessage>, prior: String = "",
        maxSummaryChars: Int = 500) = AgentSummaryRequest(ref, chat, prior, messages, maxSummaryChars)

    @Test fun promptStatesTheRequiredPreservationAndSafetyRules() {
        val text = AgentCompactionPrompt.build(request(listOf(user("u1"), assistant("a1")))).lowercase()
        for (required in listOf("user goals", "constraints", "decisions", "tool and project facts",
            "open tasks", "uncertaint", "credentials", "new action")) {
            assertTrue("missing rule: $required", text.contains(required))
        }
    }

    @Test fun promptContainsThePriorSummaryAndAContiguousArchivedPrefix() {
        val text = AgentCompactionPrompt.build(request(listOf(
            user("u1", "archive-marker"),
            assistant("a1", "reply-marker"),
        ), prior = "prior-marker"))
        assertTrue(text.contains("prior-marker"))
        assertTrue(text.contains("archive-marker"))
        assertTrue(text.contains("reply-marker"))
        assertTrue(text.contains("Maximum summary characters: 500"))
    }

    @Test fun promptRendersToolCallFactsWithoutProviderContinuation() {
        val call = AgentToolCall(ToolCallId("call"), "project_summary", json())
        val text = AgentCompactionPrompt.build(request(listOf(
            user("u1"),
            AgentMessage.Assistant(MessageId("a1"),
                listOf(AssistantPart.ToolCall(call), AssistantPart.Thinking("private-reasoning", null)), continuation = null),
            AgentMessage.ToolResult(MessageId("r1"), call.id, call.name, json(), false),
        )))
        assertTrue(text.contains("project_summary"))
        assertTrue(!text.contains("private-reasoning"))
    }

    @Test fun largeArchiveIsBoundedAndSaysWhatWasOmitted() {
        val archived = (0 until 20).map { index -> user("u$index", "x".repeat(10_000)) }
        val text = AgentCompactionPrompt.build(request(archived, prior = "y".repeat(12_000)))
        assertTrue(text.length <= AgentCompactionPrompt.MAX_PROMPT_CHARS)
        assertTrue(text.contains("omitted"))
    }
}
