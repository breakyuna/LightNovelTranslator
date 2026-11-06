package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.LlmStreamChunk
import com.breakyuna.noveltranslator.core.llm.LlmToolCall
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器流式路径的行为约束：增量必须送到观察者，但 [Message.Assistant] 的内容、
 * 工具协议块、计费口径与失败分类都必须和非流式完全一致。
 *
 * 流式只改变"用户何时看到字"，不改变"回合何时结束、回合里发生了什么"。
 */
class LntPromptExecutorStreamingTest {

    private val provider = ApiProviderEntity(
        id = 9L,
        name = "TestProvider",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "dummy-key-not-a-real-secret",
        selectedModel = "gpt-4o-mini",
        temperature = 0.7f,
        maxContextTokens = 8192
    )

    private val model = LLModel(
        provider = LLMProvider("openai_compatible", "TestProvider"),
        id = "gpt-4o-mini",
        capabilities = listOf(LLMCapability.Temperature)
    )

    private val config = AgentSessionConfig(
        provider = provider,
        modelName = "gpt-4o-mini",
        temperature = 0.2f,
        maxTokens = 1024
    )

    private val meta = RequestMetaInfo(Instant.fromEpochMilliseconds(0L))

    private fun promptOf(vararg messages: Message): Prompt =
        Prompt(messages.toList(), id = "test-prompt")

    private fun success(text: String, promptTokens: Long = 11L, completionTokens: Long = 5L) =
        LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = "stop"
        )

    /** 只实现 SSE 的 gateway；[executeCompletion] 被调用即说明流式路径意外回退了。 */
    private class StreamingOnlyGateway(
        private val frames: List<LlmStreamChunk>
    ) : LlmGateway {
        override suspend fun executeCompletion(request: LlmRequest): LlmResult =
            error("流式路径不应回退非流式")

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(emptyList())

        override fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> = flow {
            frames.forEach { emit(it) }
        }
    }

    @Test
    fun deltasReachObserverAndAssistantMessageKeepsFullText() = runBlocking {
        val gateway = StreamingOnlyGateway(
            listOf(
                LlmStreamChunk.TextDelta("书架"),
                LlmStreamChunk.TextDelta("上有 2 本书。"),
                LlmStreamChunk.Completed(success("书架上有 2 本书。"))
            )
        )
        val seen = StringBuilder()
        val executor = LntPromptExecutor(gateway, config, streamObserver = { seen.append(it) })

        val response = executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())

        // 观察者拿到的是逐段增量，Koog 拿到的是完整答复。
        assertEquals("书架上有 2 本书。", seen.toString())
        assertEquals("书架上有 2 本书。", response.textContent())
    }

    @Test
    fun nativeToolCallsSurviveStreaming() = runBlocking {
        val gateway = StreamingOnlyGateway(
            listOf(
                LlmStreamChunk.TextDelta("我来查一下"),
                LlmStreamChunk.ToolCalls(
                    listOf(LlmToolCall(id = "call_1", name = "list_books", argumentsJson = "{}"))
                ),
                LlmStreamChunk.Completed(
                    LlmResult(
                        text = "",
                        promptTokens = 11,
                        completionTokens = 5,
                        isSuccess = true,
                        finishReason = "tool_calls"
                    )
                )
            )
        )
        val executor = LntPromptExecutor(gateway, config, streamObserver = {})

        val response = executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())

        val text = response.textContent()
        assertTrue(text.startsWith("我来查一下"))
        assertTrue(text.contains(AgentToolProtocol.TOOL_CALL_OPEN))
        assertTrue(text.contains("list_books"))
        assertTrue(text.contains(AgentToolProtocol.TOOL_CALL_CLOSE))
    }

    @Test
    fun streamFailurePropagatesAsAgentLlmFailure() = runBlocking {
        val gateway = StreamingOnlyGateway(
            listOf(
                LlmStreamChunk.TextDelta("半段"),
                LlmStreamChunk.Failed(
                    LlmResult(
                        text = "",
                        promptTokens = 0,
                        completionTokens = 0,
                        isSuccess = false,
                        errorCategory = LlmErrorCategory.NETWORK_UNAVAILABLE,
                        retryable = true,
                        errorMessage = "连接中断"
                    )
                )
            )
        )
        val executor = LntPromptExecutor(gateway, config, streamObserver = {})

        val error = runCatching {
            executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())
        }.exceptionOrNull()

        assertTrue(error is AgentLlmFailureException)
        assertEquals(
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            (error as AgentLlmFailureException).result.errorCategory
        )
    }

    @Test
    fun streamWithoutAnyDeltaFallsBackToNonStreaming() = runBlocking {
        var completionCalls = 0
        val gateway = object : LlmGateway {
            override suspend fun executeCompletion(request: LlmRequest): LlmResult {
                completionCalls++
                return success("非流式答复")
            }

            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(emptyList())

            // 一个字都没吐出来的流：必须整段回退，不能把空响应当成失败。
            override fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> = flow { }
        }
        val executor = LntPromptExecutor(gateway, config, streamObserver = { })

        val response = executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())

        assertEquals(1, completionCalls)
        assertEquals("非流式答复", response.textContent())
    }

    @Test
    fun streamingDisabledKeepsTheNonStreamingPath() = runBlocking {
        var completionCalls = 0
        val gateway = object : LlmGateway {
            override suspend fun executeCompletion(request: LlmRequest): LlmResult {
                completionCalls++
                return success("非流式答复")
            }

            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(emptyList())

            override fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> =
                error("关闭流式时不应走到 SSE")
        }
        val executor = LntPromptExecutor(
            gateway,
            config.copy(streamingEnabled = false),
            streamObserver = { error("关闭流式时不应收到增量") }
        )

        val response = executor.execute(promptOf(Message.User("书架", meta)), model, emptyList())

        assertEquals(1, completionCalls)
        assertEquals("非流式答复", response.textContent())
    }
}
