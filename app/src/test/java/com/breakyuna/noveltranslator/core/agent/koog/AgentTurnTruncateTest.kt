package com.breakyuna.noveltranslator.core.agent.koog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * truncateToTurns 历史截断策略的纯 JVM 测试。
 *
 * 策略：只保留最近 maxTurns 个“用户回合”（user 消息及其后的全部消息，
 * 含同一回合内的工具调用/工具结果/system 消息），更早的内容整体丢弃。
 */
class AgentTurnTruncateTest {

    private fun user(content: String) = AgentMessage(role = AgentRole.USER, content = content)
    private fun assistant(content: String) = AgentMessage(role = AgentRole.ASSISTANT, content = content)
    private fun toolCall(name: String) =
        AgentMessage(role = AgentRole.TOOL_CALL, content = "调用 $name", toolName = name)
    private fun toolResult(name: String, error: Boolean = false) =
        AgentMessage(role = AgentRole.TOOL_RESULT, content = "$name 返回", toolName = name, isError = error)
    private fun system(content: String) = AgentMessage(role = AgentRole.SYSTEM, content = content)

    @Test
    fun nonPositiveMaxTurnsReturnsEmpty() {
        val messages = listOf(user("u1"), assistant("a1"))
        assertTrue(truncateToTurns(messages, maxTurns = 0).isEmpty())
        assertTrue(truncateToTurns(messages, maxTurns = -1).isEmpty())
    }

    @Test
    fun emptyHistoryReturnsEmpty() {
        assertTrue(truncateToTurns(emptyList(), maxTurns = 5).isEmpty())
    }

    @Test
    fun fewerTurnsThanMaxReturnsUnchanged() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(messages, truncateToTurns(messages, maxTurns = 5))
    }

    @Test
    fun exactlyMaxTurnsReturnsUnchanged() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(messages, truncateToTurns(messages, maxTurns = 2))
    }

    @Test
    fun moreTurnsKeepsLastNWithTheirTrailingMessages() {
        val messages = listOf(
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2"),
            user("u3"), assistant("a3"),
            user("u4"), assistant("a4")
        )
        val truncated = truncateToTurns(messages, maxTurns = 2)
        assertEquals(
            listOf("u3", "a3", "u4", "a4"),
            truncated.map { it.content }
        )
    }

    @Test
    fun dropsLeadingMessagesBeforeOldestKeptTurn() {
        val messages = listOf(
            system("系统提示"),
            user("u1"), assistant("a1"),
            user("u2"), assistant("a2")
        )
        val truncated = truncateToTurns(messages, maxTurns = 1)
        assertEquals(listOf("u2", "a2"), truncated.map { it.content })
    }

    @Test
    fun keepsToolMessagesInsideKeptTurns() {
        val messages = listOf(
            user("u1"), toolCall("list_books"), toolResult("list_books"), assistant("a1"),
            user("u2"), toolCall("get_recent_log"), toolResult("get_recent_log", error = true), assistant("a2")
        )
        val truncated = truncateToTurns(messages, maxTurns = 1)
        assertEquals(
            listOf(AgentRole.USER, AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT, AgentRole.ASSISTANT),
            truncated.map { it.role }
        )
        // content 由本文件的助手函数生成：toolCall 为“调用 X”、toolResult 为“X 返回”
        assertEquals(
            listOf("u2", "调用 get_recent_log", "get_recent_log 返回", "a2"),
            truncated.map { it.content }
        )
        assertTrue(truncated[2].isError)
    }

    @Test
    fun historyWithoutUserMessagesIsReturnedAsIs() {
        val messages = listOf(toolCall("list_books"), toolResult("list_books"), system("提示"))
        assertEquals(messages, truncateToTurns(messages, maxTurns = 3))
    }

    @Test
    fun singleTurnKeepsEverythingAfterTheOnlyUserMessage() {
        val messages = listOf(
            user("u1"), assistant("a1"), system("回合内系统消息")
        )
        assertEquals(messages, truncateToTurns(messages, maxTurns = 1))
    }
}
