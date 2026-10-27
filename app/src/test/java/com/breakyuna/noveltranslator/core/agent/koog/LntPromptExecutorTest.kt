package com.breakyuna.noveltranslator.core.agent.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LntPromptExecutor（Koog PromptExecutor → LlmGateway 桥接）与
 * LntPromptMapper（Prompt 展平）的纯 JVM 测试。
 *
 * Koog 的 prompt 模型与 coroutines 均为纯 JVM 组件；执行失败路径上的
 * SystemLogger 也只写内存，不触达 Android API。
 */
class LntPromptExecutorTest {

    private val provider = ApiProviderEntity(
        id = 7L,
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
        maxTokens = 1024,
        reasoningEffort = "low"
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
            Result.success(listOf("gpt-4o-mini"))
    }

    private val meta = RequestMetaInfo(Instant.fromEpochMilliseconds(0L))

    private fun promptOf(vararg messages: Message): Prompt = Prompt(messages.toList(), id = "test-prompt")

    private fun success(text: String, promptTokens: Long = 11L, completionTokens: Long = 5L) =
        LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = "stop"
        )

    // ---------- execute: LlmResult → Message.Assistant ----------

    @Test
    fun executeMapsSuccessToAssistantMessage() {
        val gateway = RecordingGateway { success("这是答复", 11, 5) }
        val signal = TranslationControlSignal()
        val executor = LntPromptExecutor(gateway, config) { signal }

        val response = runBlocking {
            executor.execute(
                promptOf(
                    Message.System("系统指令", meta),
                    Message.User("你好", meta)
                ),
                model,
                emptyList()
            )
        }

        assertEquals("这是答复", response.textContent())
        val request = gateway.requests.single()
        assertTrue(request.systemPrompt.contains("系统指令"))
        assertTrue(request.userPrompt.contains("用户：你好"))
        assertEquals("AGENT_CHAT", request.operation)
        assertEquals(0.2f, request.temperature!!, 0.0001f)
        assertEquals(1024, request.maxTokens)
        assertEquals("low", request.reasoningEffort)
        assertSame(signal, request.controlSignal)

        val responseMeta = response.metaInfo
        assertEquals(11, responseMeta.inputTokensCount!!.toInt())
        assertEquals(5, responseMeta.outputTokensCount!!.toInt())
        assertEquals(16, responseMeta.totalTokensCount!!.toInt())
        assertEquals("gpt-4o-mini", responseMeta.modelId)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun executeUsesDefaultSignalWhenNoneProvided() {
        val gateway = RecordingGateway { success("ok") }
        val executor = LntPromptExecutor(gateway, config)
        runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }
        assertEquals(null, gateway.requests.single().controlSignal)
    }

    @Test
    fun blankTextBecomesEmptyResponseFailure() {
        val gateway = RecordingGateway { success("   \n  ") }
        val executor = LntPromptExecutor(gateway, config)
        val failure = try {
            runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }
            throw AssertionError("空白回复应转换为 AgentLlmFailureException")
        } catch (expected: AgentLlmFailureException) {
            expected
        }
        assertEquals(LlmErrorCategory.EMPTY_RESPONSE, failure.result.errorCategory)
        assertEquals("模型返回了空内容", failure.message)
    }

    @Test
    fun failedResultPropagatesCategoryAndMessage() {
        val gateway = RecordingGateway {
            LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorCategory = LlmErrorCategory.AUTHENTICATION,
                errorMessage = "401 未授权"
            )
        }
        val executor = LntPromptExecutor(gateway, config)
        val failure = try {
            runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }
            throw AssertionError("失败结果应抛 AgentLlmFailureException")
        } catch (expected: AgentLlmFailureException) {
            expected
        }
        assertEquals(LlmErrorCategory.AUTHENTICATION, failure.result.errorCategory)
        assertEquals("401 未授权", failure.message)
    }

    @Test
    fun failureMessageDoesNotLeakPromptText() {
        val gateway = RecordingGateway {
            LlmResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                isSuccess = false,
                errorCategory = LlmErrorCategory.SERVER_ERROR,
                errorMessage = "500 上游错误"
            )
        }
        val executor = LntPromptExecutor(gateway, config)
        try {
            runBlocking {
                executor.execute(promptOf(Message.User("含有正文的提问", meta)), model, emptyList())
            }
            throw AssertionError("应抛异常")
        } catch (expected: AgentLlmFailureException) {
            assertFalse(expected.message!!.contains("含有正文的提问"))
        }
    }

    @Test
    fun missingProviderThrows() {
        val gateway = RecordingGateway { error("不应被调用") }
        val executor = LntPromptExecutor(gateway, config.copy(provider = null))
        try {
            runBlocking { executor.execute(promptOf(Message.User("问", meta)), model, emptyList()) }
            throw AssertionError("缺少提供方应抛 IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("缺少模型提供方"))
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun hugeTokenCountsAreCoercedToIntMax() {
        val gateway = RecordingGateway { success("ok", promptTokens = 4_000_000_000, completionTokens = 4_000_000_000) }
        val executor = LntPromptExecutor(gateway, config)
        val response = runBlocking {
            executor.execute(promptOf(Message.User("问", meta)), model, emptyList())
        }
        assertEquals(Int.MAX_VALUE, response.metaInfo.inputTokensCount!!.toInt())
        assertEquals(Int.MAX_VALUE, response.metaInfo.outputTokensCount!!.toInt())
        assertEquals(Int.MAX_VALUE, response.metaInfo.totalTokensCount!!.toInt())
    }

    @Test
    fun modelsReturnsEmptyList() {
        val gateway = RecordingGateway { error("unused") }
        val executor = LntPromptExecutor(gateway, config)
        assertTrue(runBlocking { executor.models() }.isEmpty())
    }

    // ---------- LntPromptMapper.flatten ----------

    @Test
    fun flattenJoinsSystemMessagesAndLabelsTranscript() {
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(
                Message.System("系统一", meta),
                Message.System("系统二", meta),
                Message.User("问题", meta),
                Message.Assistant("回答", ResponseMetaInfo(Instant.fromEpochMilliseconds(0L)))
            ),
            maxTranscriptChars = 60_000,
            maxContextTokens = 8192
        )
        assertTrue(flattened.system.contains("系统一"))
        assertTrue(flattened.system.contains("系统二"))
        assertTrue(flattened.system.contains("\n\n"))
        assertTrue(flattened.user.contains("用户：问题"))
        assertTrue(flattened.user.contains("助手：回答"))
    }

    @Test
    fun blankSystemFallsBackToDefault() {
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(Message.System("   ", meta), Message.User("问题", meta)),
            maxTranscriptChars = 60_000,
            maxContextTokens = 8192
        )
        assertEquals("你是 LightNovelTranslator 的内置助手。", flattened.system)
    }

    @Test
    fun flattenRendersToolResultAndCallParts() {
        val userWithToolResult = Message.User(
            parts = listOf(
                MessagePart.Text("查一下书架"),
                MessagePart.Tool.Result(tool = "list_books", output = "共 2 本书")
            ),
            metaInfo = meta
        )
        val assistantWithToolCall = Message.Assistant(
            parts = listOf(
                MessagePart.Text("我来查"),
                MessagePart.Tool.Call(tool = "get_book_status", args = """{"book_id":1}""")
            ),
            metaInfo = ResponseMetaInfo(Instant.fromEpochMilliseconds(0L))
        )
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(Message.System("系统", meta), userWithToolResult, assistantWithToolCall),
            maxTranscriptChars = 60_000,
            maxContextTokens = 8192
        )
        assertTrue(flattened.user.contains("查一下书架\nlist_books 结果：共 2 本书"))
        assertTrue(flattened.user.contains("助手：我来查\nget_book_status 调用参数：{\"book_id\":1}"))
    }

    @Test
    fun flattenSkipsBlankParts() {
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(
                Message.System("系统", meta),
                Message.User("   ", meta),
                Message.Assistant("", ResponseMetaInfo(Instant.fromEpochMilliseconds(0L)))
            ),
            maxTranscriptChars = 60_000,
            maxContextTokens = 8192
        )
        assertFalse(flattened.user.contains("用户："))
        assertFalse(flattened.user.contains("助手："))
    }

    @Test
    fun flattenCapsTranscriptByCharacters() {
        val longText = "A".repeat(400)
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(Message.System("系统", meta), Message.User(longText, meta)),
            maxTranscriptChars = 100,
            maxContextTokens = 8192
        )
        assertTrue(flattened.user.contains("中间历史已截断"))
        assertTrue(flattened.user.startsWith("用户："))
        assertTrue(flattened.user.length < longText.length)
    }

    @Test
    fun flattenCapsTranscriptByTokenBudget() {
        val longText = "A".repeat(4000)
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(Message.System("系统指令", meta), Message.User(longText, meta)),
            maxTranscriptChars = 60_000,
            maxContextTokens = 4096
        )
        // 4096 上下文扣掉输出预留与固定开销后，远小于 4000 字符的估算 token，
        // 必须触发按 token 预算的头尾截断。
        assertTrue(flattened.user.contains("中间历史已截断"))
        assertTrue(flattened.user.length < longText.length)
        val budget = TokenCalculator.calculateChunkBudget(
            maxContextTokens = 4096,
            overheadEstimate = 1_200L + TokenCalculator.estimateTokens("系统指令")
        )
        assertTrue(budget < TokenCalculator.estimateTokens(longText))
    }

    @Test
    fun flattenKeepsShortTranscriptVerbatim() {
        val flattened = LntPromptMapper.flatten(
            prompt = promptOf(Message.System("系统", meta), Message.User("短问题", meta)),
            maxTranscriptChars = 60_000,
            maxContextTokens = 8192
        )
        assertEquals("用户：短问题", flattened.user)
        assertFalse(flattened.user.contains("截断"))
    }
}
