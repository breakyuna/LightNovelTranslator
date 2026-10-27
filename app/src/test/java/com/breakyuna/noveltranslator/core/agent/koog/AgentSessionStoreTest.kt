package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程内会话存储与轻量模型的纯 JVM 测试（不涉及 Android API）。
 */
class AgentSessionStoreTest {

    private fun message(role: AgentRole, content: String) =
        AgentMessage(role = role, content = content, createdAt = 1L)

    @Test
    fun createSessionIncrementsIds() = runTest {
        val store = InMemoryAgentSessionStore()
        assertEquals(1L, store.createSession("a", null, null))
        assertEquals(2L, store.createSession("b", 3L, "gpt-4o"))
    }

    @Test
    fun appendMessageAssignsIncrementingIdsAndKeepsOrder() = runTest {
        val store = InMemoryAgentSessionStore()
        val session = store.createSession("s", null, null)

        val first = store.appendMessage(session, message(AgentRole.USER, "问题"))
        val second = store.appendMessage(session, message(AgentRole.ASSISTANT, "回答"))

        assertNotEquals(first, second)
        val loaded = store.loadMessages(session)
        assertEquals(listOf("问题", "回答"), loaded.map { it.content })
        assertEquals(listOf(AgentRole.USER, AgentRole.ASSISTANT), loaded.map { it.role })
        assertTrue(loaded.all { it.id > 0L })
    }

    @Test
    fun observeMessagesEmitsOnAppendAndUpdate() = runTest {
        val store = InMemoryAgentSessionStore()
        val session = store.createSession("s", null, null)
        val flow = store.observeMessages(session)
        assertTrue(flow.first().isEmpty())

        val id = store.appendMessage(session, message(AgentRole.USER, "问题"))
        assertEquals(1, flow.first().size)
        assertEquals("问题", flow.first().single().content)

        store.updateMessageContent(id, "改写后")
        assertEquals("改写后", flow.first().single().content)
    }

    @Test
    fun updateMessageContentAffectsOnlyTargetMessage() = runTest {
        val store = InMemoryAgentSessionStore()
        val sessionA = store.createSession("a", null, null)
        val sessionB = store.createSession("b", null, null)
        val idA = store.appendMessage(sessionA, message(AgentRole.USER, "A1"))
        store.appendMessage(sessionA, message(AgentRole.ASSISTANT, "A2"))
        val idB = store.appendMessage(sessionB, message(AgentRole.USER, "B1"))

        store.updateMessageContent(idA, "A1 改写")
        store.updateMessageContent(999L, "不存在的 id")

        assertEquals(listOf("A1 改写", "A2"), store.loadMessages(sessionA).map { it.content })
        assertEquals(listOf("B1"), store.loadMessages(sessionB).map { it.content })
        assertEquals("B1", store.loadMessages(sessionB).single { it.id == idB }.content)
    }

    @Test
    fun touchSessionIsSafeForMemoryStore() = runTest {
        val store = InMemoryAgentSessionStore()
        val session = store.createSession("s", null, null)
        store.touchSession(session, "预览", 123L)
        store.appendMessage(session, message(AgentRole.USER, "问题"))
        assertEquals(1, store.loadMessages(session).size)
    }

    @Test
    fun deleteSessionDropsMessagesAndEmptiesObservation() = runTest {
        val store = InMemoryAgentSessionStore()
        val session = store.createSession("s", null, null)
        val flow = store.observeMessages(session)
        store.appendMessage(session, message(AgentRole.USER, "问题"))
        store.appendMessage(session, message(AgentRole.ASSISTANT, "回答"))
        assertEquals(2, flow.first().size)

        store.deleteSession(session)

        // 会话删除后不能留下孤儿消息：查询该会话必须为空，而不是继续返回已删会话的内容。
        assertTrue(store.loadMessages(session).isEmpty())
        assertTrue(flow.first().isEmpty())
    }

    @Test
    fun agentRoleStorageKeysRoundTrip() {
        assertEquals("USER", AgentRole.USER.storageKey)
        assertEquals("ASSISTANT", AgentRole.ASSISTANT.storageKey)
        assertEquals("TOOL_CALL", AgentRole.TOOL_CALL.storageKey)
        assertEquals("TOOL_RESULT", AgentRole.TOOL_RESULT.storageKey)
        assertEquals("SYSTEM", AgentRole.SYSTEM.storageKey)
        AgentRole.entries.forEach { role ->
            assertEquals(role, AgentRole.fromStorageKey(role.storageKey))
        }
        // 未知角色回落 SYSTEM，历史数据里的脏值不会让会话崩溃
        assertEquals(AgentRole.SYSTEM, AgentRole.fromStorageKey("weird-role"))
    }

    @Test
    fun sessionConfigFallsBackToProviderValues() {
        val provider = ApiProviderEntity(
            id = 1L,
            name = "P",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid/v1",
            apiKey = "dummy",
            selectedModel = "provider-model",
            temperature = 0.9f,
            maxContextTokens = 8192
        )

        val blankModel = AgentSessionConfig(provider = provider, modelName = "")
        assertEquals("provider-model", blankModel.effectiveModelName)

        val explicitModel = AgentSessionConfig(provider = provider, modelName = "explicit-model")
        assertEquals("explicit-model", explicitModel.effectiveModelName)

        // temperature <= 0 视为“未设置”，回落到提供方温度
        val zeroTemperature = AgentSessionConfig(provider = provider, temperature = 0f)
        assertEquals(0.9f, zeroTemperature.effectiveTemperature, 0.0001f)

        val positiveTemperature = AgentSessionConfig(provider = provider, temperature = 0.3f)
        assertEquals(0.3f, positiveTemperature.effectiveTemperature, 0.0001f)

        // 无提供方时使用默认 0.4
        val noProvider = AgentSessionConfig(provider = null, temperature = 0f)
        assertEquals(0.4f, noProvider.effectiveTemperature, 0.0001f)
        assertEquals("", noProvider.effectiveModelName)
    }

    @Test
    fun sessionTextDefaultsAreChinese() {
        val texts = AgentSessionTexts()
        assertEquals("已取消本次请求。", texts.cancelled)
        assertEquals("尚未选择可用的模型提供方，请先在设置中配置。", texts.noProvider)
        assertTrue(texts.requestFailed.contains("{reason}"))
        assertTrue(texts.turnFailed.contains("{reason}"))
        assertEquals("（模型没有返回可用内容）", texts.emptyReply)
    }
}
