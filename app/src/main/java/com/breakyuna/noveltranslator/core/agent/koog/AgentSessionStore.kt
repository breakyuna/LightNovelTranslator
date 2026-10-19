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
        flows[sessionId]?.value = messagesBySession[sessionId]?.toList() ?: emptyList()
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
            flow.value = messagesBySession[sessionId]?.toList() ?: emptyList()
        }
        Unit
    }

    override suspend fun loadMessages(sessionId: Long): List<AgentMessage> = synchronized(lock) {
        messagesBySession[sessionId]?.toList() ?: emptyList()
    }

    override fun observeMessages(sessionId: Long): Flow<List<AgentMessage>> =
        flows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
}
