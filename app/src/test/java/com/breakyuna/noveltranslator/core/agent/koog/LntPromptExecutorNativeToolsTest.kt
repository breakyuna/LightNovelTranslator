package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import com.breakyuna.noveltranslator.core.agent.AgentToolSpec
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.LlmToolCall
import com.breakyuna.noveltranslator.core.llm.LlmToolSpec
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生 function calling 桥接测试（纯 JVM）：
 * tools 数组只在 OpenAI 兼容形状 + nativeToolsEnabled 时携带；
 * 响应里的 tool_calls 被转换回文本协议调用块，回合循环无需区分两条路。
 */
class LntPromptExecutorNativeToolsTest {

    private val provider = ApiProviderEntity(
        id = 7L,
        name = "TestProvider",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "dummy-key-not-a-real-secret",
        selectedModel = "deepseek-chat",
        temperature = 0.7f,
        maxContextTokens = 8192
    )

    private val model = LLModel(
        provider = LLMProvider("openai_compatible", "TestProvider"),
        id = "deepseek-chat",
        capabilities = listOf(LLMCapability.Temperature)
    )

    private val toolSpecs = listOf(
        AgentToolSpec(
            name = "list_books",
            description = "列出书架上的全部书籍",
            parametersSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}"""
        )
    )

    private class RecordingGateway(
        private val handler: suspend (LlmRequest) -> LlmResult
    ) : LlmGateway {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun executeCompletion(request: LlmRequest): LlmResult {
            requests += request
            return handler(request)
        }

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(listOf("deepseek-chat"))
    }

    private val meta = RequestMetaInfo(Instant.fromEpochMilliseconds(0L))

    private fun promptOf(vararg messages: Message): Prompt = Prompt(messages.toList(), id = "test-prompt")

    private fun success(text: String, toolCalls: List<LlmToolCall> = emptyList()) =
        LlmResult(
            text = text,
            promptTokens = 11L,
            completionTokens = 5L,
            isSuccess = true,
            finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls",
            toolCalls = toolCalls
        )

    @Test
    fun nativeToolCallsAreConvertedToTextProtocolBlocks() {
        val gateway = RecordingGateway { request ->
            assertTrue("请求应携带 tools", request.tools?.any { it.name == "list_books" } == true)
            success(
                "",
                listOf(LlmToolCall(id = "call_1", name = "list_books", argumentsJson = "{}"))
            )
        }
        val executor = LntPromptExecutor(
            gateway,
            AgentSessionConfig(provider = provider, modelName = "deepseek-chat", nativeToolsEnabled = true),
            toolSpecs
        )

        val response = runBlocking {
            executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())
        }

        val text = response.textContent()
        assertTrue(text.contains(AgentToolProtocol.TOOL_CALL_OPEN))
        assertTrue(text.contains("list_books"))
        assertTrue(text.contains(AgentToolProtocol.TOOL_CALL_CLOSE))
    }

    @Test
    fun textAlongsideToolCallsKeepsBoth() {
        val gateway = RecordingGateway { _ ->
            success(
                "我来查一下",
                listOf(LlmToolCall(id = "call_1", name = "list_books", argumentsJson = "{}"))
            )
        }
        val executor = LntPromptExecutor(
            gateway,
            AgentSessionConfig(provider = provider, nativeToolsEnabled = true),
            toolSpecs
        )

        val response = runBlocking {
            executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())
        }

        val text = response.textContent()
        assertTrue(text.startsWith("我来查一下"))
        assertTrue(text.contains("list_books"))
    }

    @Test
    fun nativeToolsDisabledOmitsToolsFromRequest() {
        val gateway = RecordingGateway { _ -> success("书架上有 1 本书。") }
        val executor = LntPromptExecutor(
            gateway,
            AgentSessionConfig(provider = provider, nativeToolsEnabled = false),
            toolSpecs
        )

        val response = runBlocking {
            executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())
        }

        assertNull(gateway.requests.single().tools)
        assertEquals("书架上有 1 本书。", response.textContent())
    }

    @Test
    fun anthropicProviderSkipsNativeToolsEvenWhenEnabled() {
        val gateway = RecordingGateway { _ -> success("答复") }
        val executor = LntPromptExecutor(
            gateway,
            AgentSessionConfig(
                provider = provider.copy(providerType = ProviderType.ANTHROPIC_CLAUDE),
                nativeToolsEnabled = true
            ),
            toolSpecs
        )

        runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }

        assertNull(gateway.requests.single().tools)
    }

    @Test
    fun emptyCatalogOmitsToolsFromRequest() {
        val gateway = RecordingGateway { _ -> success("答复") }
        val executor = LntPromptExecutor(
            gateway,
            AgentSessionConfig(provider = provider, nativeToolsEnabled = true),
            emptyList()
        )

        runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }

        assertNull(gateway.requests.single().tools)
    }
}
