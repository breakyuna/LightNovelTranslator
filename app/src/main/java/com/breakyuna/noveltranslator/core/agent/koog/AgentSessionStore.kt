package com.breakyuna.noveltranslator.core.agent.koog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 会话与消息的持久化边界。Room 实现见 [RoomAgentSessionStore]（由集成层接线）；
 * 未注入存储时使用 [InMemoryAgentSessionStore]，行为一致只是不落库。
 */
interface AgentSessionStore {

    suspend fun createSession(title: String, providerId: Long?, modelName: String?): Long

    /** 更新会话的预览与更新时间，供会话列表排序展示。 */
    suspend fun touchSession(sessionId: Long, preview: String, updatedAt: Long)

    suspend fun appendMessage(sessionId: Long, message: AgentMessage): Long

    suspend fun updateMessageContent(messageId: Long, content: String)

    /**
     * 返回该会话的历史快照副本：每次调用都产出新列表，
     * 调用方可以自由改动返回值，不会影响存储内的消息。
     */
    suspend fun loadMessages(sessionId: Long): List<AgentMessage>

    fun observeMessages(sessionId: Long): Flow<List<AgentMessage>>
}

/** 进程内存储：供测试与未接数据库时使用，重启后丢失。 */
class InMemoryAgentSessionStore : AgentSessionStore {

    private val lock = Any()
    private var nextSessionId = 1L
    private var nextMessageId = 1L
    private val messagesBySession = mutableMapOf<Long, MutableList<AgentMessage>>()
    private val flows = mutableMapOf<Long, MutableStateFlow<List<AgentMessage>>>()

    override suspend fun createSession(title: String, providerId: Long?, modelName: String?): Long =
        synchronized(lock) {
            val id = nextSessionId++
            messagesBySession[id] = mutableListOf()
            flows[id] = MutableStateFlow(emptyList())
            id
        }

    override suspend fun touchSession(sessionId: Long, preview: String, updatedAt: Long) {
        // 进程内存储没有会话行，无需处理。
    }

    override suspend fun appendMessage(sessionId: Long, message: AgentMessage): Long = synchronized(lock) {
        val stored = message.copy(id = nextMessageId++)
        messagesBySession.getOrPut(sessionId) { mutableListOf() } += stored
        flows[sessionId]?.value = snapshotOf(sessionId)
        stored.id
    }

    override suspend fun updateMessageContent(messageId: Long, content: String) = synchronized(lock) {
        messagesBySession.values.forEach { messages ->
            val index = messages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                messages[index] = messages[index].copy(content = content)
            }
        }
        flows.forEach { (sessionId, flow) ->
            flow.value = snapshotOf(sessionId)
        }
        Unit
    }

    override suspend fun loadMessages(sessionId: Long): List<AgentMessage> = synchronized(lock) {
        snapshotOf(sessionId)
    }

    override fun observeMessages(sessionId: Long): Flow<List<AgentMessage>> =
        flows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    /**
     * 产出会话当前消息的快照副本。必须用 `toMutableList()` 而不是 `toList()`：
     * `toList()` 对 0/1 条消息会返回运行时不可变的列表（emptyList / singletonList），
     * 调用方一改动就抛 UnsupportedOperationException，而 ≥2 条时又变成可改动的
     * ArrayList——可变性随消息数漂移。快照契约（见 [AgentSessionStore.loadMessages]）
     * 与 Room 实现（map 产出 ArrayList）都要求每次返回可自由改动的新列表。
     */
    private fun snapshotOf(sessionId: Long): List<AgentMessage> =
        messagesBySession[sessionId]?.toMutableList() ?: emptyList()
}
