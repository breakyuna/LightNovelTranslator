package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.data.db.AgentMessageDao
import com.breakyuna.noveltranslator.data.db.AgentSessionDao
import com.breakyuna.noveltranslator.data.model.AgentMessageEntity
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 基于 Room 的会话存储，直接复用 agent 集成侧已建好的 agent_sessions / agent_messages 表。
 *
 * 表由引擎重构侧维护，本类只做模型映射，不修改表结构：
 * - 工具调用与工具结果各占一行，content 保存送入模型的协议原文，保证可以精确重建历史；
 * - promptTokens / completionTokens 记录在该次 LLM 请求产生的消息行上。
 */
class RoomAgentSessionStore(
    private val sessionDao: AgentSessionDao,
    private val messageDao: AgentMessageDao
) : AgentSessionStore {

    override suspend fun createSession(title: String, providerId: Long?, modelName: String?): Long {
        val now = System.currentTimeMillis()
        return sessionDao.insert(
            AgentSessionEntity(
                title = title,
                providerId = providerId,
                modelName = modelName,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun touchSession(sessionId: Long, preview: String, updatedAt: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(
                lastMessagePreview = preview.take(PREVIEW_CHARS),
                updatedAt = updatedAt
            )
        )
    }

    override suspend fun appendMessage(sessionId: Long, message: AgentMessage): Long =
        messageDao.insert(message.toEntity(sessionId))

    override suspend fun updateMessageContent(messageId: Long, content: String) =
        messageDao.updateContent(messageId, content)

    override suspend fun loadMessages(sessionId: Long): List<AgentMessage> =
        messageDao.getBySession(sessionId).map { it.toModel() }

    override fun observeMessages(sessionId: Long): Flow<List<AgentMessage>> =
        messageDao.observeBySession(sessionId).map { rows -> rows.map { it.toModel() } }

    private fun AgentMessage.toEntity(sessionId: Long): AgentMessageEntity = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.storageKey,
        content = content,
        toolName = toolName,
        toolCallId = null,
        toolArgsJson = toolArgsJson,
        toolResultJson = toolResultJson,
        isError = isError,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        createdAt = createdAt
    )

    private fun AgentMessageEntity.toModel(): AgentMessage = AgentMessage(
        id = id,
        role = AgentRole.fromStorageKey(role),
        content = content,
        toolName = toolName,
        toolArgsJson = toolArgsJson,
        toolResultJson = toolResultJson,
        isError = isError,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        createdAt = createdAt
    )

    private companion object {
        const val PREVIEW_CHARS = 80
    }
}
