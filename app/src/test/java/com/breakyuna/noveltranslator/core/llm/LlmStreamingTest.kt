package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 流式（SSE）链路的测试：三家 provider 的帧累积、[LlmGateway] 默认退化实现，
 * 以及 [RetryingLlmGateway] 的流式重试边界。
 *
 * 真实的 HTTP/SSE 收发由 LlmClient 负责，这里不触达网络；[LlmSse] 的逐行读帧需要
 * OkHttp Response，交由集成验证。累积状态用 android 的 org.json 解析帧，
 * 因此与仓库既有 agent 测试一致跑 Robolectric（纯 JVM 下 android.jar 是未模拟桩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmStreamingTest {

    // ---------- 工具 ----------

    private fun provider() = ApiProviderEntity(
        name = "fake",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "not-a-real-key",
        selectedModel = "fake-model"
    )

    private fun failure(
        category: LlmErrorCategory,
        retryable: Boolean = true
    ) = LlmResult(
        text = "",
        promptTokens = 2,
        completionTokens = 0,
        isSuccess = false,
        errorCategory = category,
        retryable = retryable,
        errorMessage = category.name
    )

    private fun success(text: String, promptTokens: Long = 11L, completionTokens: Long = 5L) =
        LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = "stop"
        )

    private class NoWaitDelayProvider : DelayProvider {
        override suspend fun delayFor(milliseconds: Long) = Unit
    }

    /** 只实现 SSE 的假 gateway：按调用次序吐出脚本化的帧序列。 */
    private class StreamingGateway(
        private val scripts: List<List<LlmStreamChunk>>
    ) : LlmGateway {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun executeCompletion(request: LlmRequest): LlmResult =
            error("流式路径不应回退非流式")

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(emptyList())

        override fun executeStreaming(request: LlmRequest): Flow<LlmStreamChunk> = flow {
            requests += request
            scripts.getOrElse(requests.size - 1) { scripts.last() }.forEach { emit(it) }
        }
    }

    // ---------- optText：JSON null 不能当成字面量 "null" ----------

    @Test
    fun optTextTreatsJsonNullAndMissingKeysAsBlank() {
        val json = JSONObject("""{"text":"你好","content":null}""")
        assertEquals("你好", json.optText("text"))
        assertEquals("", json.optText("content"))
        assertEquals("", json.optText("missing"))
    }

    // ---------- OpenAI 兼容 ----------

    @Test
    fun openAiAccumulatorMergesTextToolCallsAndUsage() {
        val accumulator = OpenAiStreamAccumulator()
        accumulator.consume(JSONObject("""{"choices":[{"delta":{"content":"书架"}}]}"""))
        accumulator.consume(JSONObject("""{"choices":[{"delta":{"content":"上有 2 本书。"}}]}"""))
        accumulator.consume(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_books","arguments":"{\"a\""}}]}}]}"""
            )
        )
        accumulator.consume(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]}}]}"""
            )
        )
        accumulator.consume(JSONObject("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""))
        accumulator.consume(JSONObject("""{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":5}}"""))

        assertEquals("书架上有 2 本书。", accumulator.text())
        assertEquals("tool_calls", accumulator.finishReason)
        assertEquals(11L, accumulator.promptTokens)
        assertEquals(5L, accumulator.completionTokens)

        val calls = accumulator.toolCalls()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("list_books", calls[0].name)
        assertEquals("""{"a":1}""", calls[0].argumentsJson)
    }

    @Test
    fun openAiAccumulatorIgnoresNullContentAndBlankToolNames() {
        val accumulator = OpenAiStreamAccumulator()
        val delta = accumulator.consume(
            JSONObject(
                """{"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_books","arguments":"{}"}}]}}]}"""
            )
        )

        // content 为 null 的帧是工具调用轮的常见形状，不能把 "null" 追加进正文。
        assertEquals("", delta)
        assertEquals("", accumulator.text())
        assertEquals(1, accumulator.toolCalls().size)
    }

    // ---------- Anthropic ----------

    @Test
    fun anthropicAccumulatorCollectsTextAndSplitUsage() {
        val accumulator = AnthropicStreamAccumulator()
        accumulator.consume(
            JSONObject(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":4}}}"""
            )
        )
        accumulator.consume(
            JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"书架"}}""")
        )
        accumulator.consume(
            JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"上有 2 本书。"}}""")
        )
        // 思考增量不属于正文。
        accumulator.consume(
            JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"内部"}}""")
        )
        accumulator.consume(
            JSONObject("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""")
        )

        assertEquals("书架上有 2 本书。", accumulator.text())
        assertEquals(10L, accumulator.inputTokens)
        assertEquals(7L, accumulator.outputTokens)
        assertEquals(4L, accumulator.cachedTokens)
        assertEquals("end_turn", accumulator.stopReason)
    }

    // ---------- Gemini ----------

    @Test
    fun geminiAccumulatorConcatenatesParts() {
        val accumulator = GeminiStreamAccumulator()
        accumulator.consume(
            JSONObject(
                """{"candidates":[{"content":{"parts":[{"text":"书架"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}"""
            )
        )
        accumulator.consume(
            JSONObject("""{"candidates":[{"content":{"parts":[{"text":"上有 2 本书。"}]}}]}""")
        )

        assertEquals("书架上有 2 本书。", accumulator.text())
        assertEquals(3L, accumulator.promptTokens)
        assertEquals(2L, accumulator.completionTokens)
        assertEquals("STOP", accumulator.finishReason)
    }

    // ---------- LlmGateway 默认实现：未实现 SSE 的 gateway 行为不变 ----------

    @Test
    fun defaultStreamingMirrorsExecuteCompletion() {
        val gateway = object : LlmGateway {
            var completionCalls = 0
                private set

            override suspend fun executeCompletion(request: LlmRequest): LlmResult {
                completionCalls++
                return success("完整答复")
            }

            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(emptyList())
        }

        val chunks = runBlocking {
            gateway.executeStreaming(
                LlmRequest(provider(), "system", "user")
            ).toList()
        }

        assertEquals(1, gateway.completionCalls)
        assertEquals(LlmStreamChunk.TextDelta("完整答复"), chunks.first())
        val completed = chunks.last() as LlmStreamChunk.Completed
        assertEquals("完整答复", completed.result.text)
    }

    @Test
    fun defaultStreamingReportsEmptyResponseAsFailure() {
        val gateway = object : LlmGateway {
            override suspend fun executeCompletion(request: LlmRequest): LlmResult =
                success("")

            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(emptyList())
        }

        val chunks = runBlocking {
            gateway.executeStreaming(
                LlmRequest(provider(), "system", "user")
            ).toList()
        }

        val failed = chunks.single() as LlmStreamChunk.Failed
        assertEquals(LlmErrorCategory.EMPTY_RESPONSE, failed.result.errorCategory)
    }

    @Test
    fun defaultStreamingForwardsNativeToolCalls() {
        val gateway = object : LlmGateway {
            override suspend fun executeCompletion(request: LlmRequest): LlmResult =
                LlmResult(
                    text = "我来查一下",
                    promptTokens = 11,
                    completionTokens = 5,
                    isSuccess = true,
                    toolCalls = listOf(
                        LlmToolCall(id = "call_1", name = "list_books", argumentsJson = "{}")
                    )
                )

            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(emptyList())
        }

        val chunks = runBlocking {
            gateway.executeStreaming(
                LlmRequest(provider(), "system", "user")
            ).toList()
        }

        assertEquals(LlmStreamChunk.TextDelta("我来查一下"), chunks[0])
        val toolCalls = chunks[1] as LlmStreamChunk.ToolCalls
        assertEquals("list_books", toolCalls.calls.single().name)
        assertTrue(chunks.last() is LlmStreamChunk.Completed)
    }

    // ---------- 非流式回退的逐字回放 ----------

    @Test
    fun replayChunksShortTextWithoutTouchingTheTail() = runBlocking {
        val text = "abcdefghij"

        val deltas = replayTextAsDeltas(
            text = text,
            chunkChars = 2,
            // delayMs 必须为正：实现约定"<=0 即调用方不要求润色，一次给完"。
            delayMs = 1L,
            budgetMs = 10_000L
        ).toList().filterIsInstance<LlmStreamChunk.TextDelta>().map { it.text }

        // 唯一不可妥协的不变式：按顺序拼接必须等于原文。
        assertEquals(text, deltas.joinToString(""))
        assertEquals(listOf("ab", "cd", "ef", "gh", "ij"), deltas)
    }

    @Test
    fun replayStopsChunkingOnceBudgetRunsOut() = runBlocking {
        val text = "一二三四五六七八九十"

        val deltas = replayTextAsDeltas(
            text = text,
            chunkChars = 1,
            delayMs = 1L,
            // 只够 3 片：第 4 片起剩余部分必须一次给完。
            budgetMs = 3L
        ).toList().filterIsInstance<LlmStreamChunk.TextDelta>().map { it.text }

        assertEquals(text, deltas.joinToString(""))
        assertEquals(listOf("一", "二", "三", "四五六七八九十"), deltas)
    }

    @Test
    fun replayWithoutBudgetEmitsTheWholeTextAtOnce() = runBlocking {
        val deltas = replayTextAsDeltas(
            text = "一次给完",
            chunkChars = 2,
            delayMs = 0L,
            budgetMs = 0L
        ).toList().filterIsInstance<LlmStreamChunk.TextDelta>().map { it.text }

        assertEquals(listOf("一次给完"), deltas)
    }

    @Test
    fun replayOfBlankTextEmitsNothing() = runBlocking {
        val deltas = replayTextAsDeltas(
            text = "",
            chunkChars = 2,
            delayMs = 1L,
            budgetMs = 100L
        ).toList()

        assertTrue(deltas.isEmpty())
    }

    // ---------- RetryingLlmGateway 的流式重试边界 ----------

    @Test
    fun retryingGatewayRetriesStreamUntilTextArrives() = runBlocking {
        val gateway = StreamingGateway(
            listOf(
                listOf(LlmStreamChunk.Failed(failure(LlmErrorCategory.NETWORK_UNAVAILABLE))),
                listOf(
                    LlmStreamChunk.TextDelta("书"),
                    LlmStreamChunk.TextDelta("架"),
                    LlmStreamChunk.Completed(success("书架"))
                )
            )
        )

        val chunks = RetryingLlmGateway(gateway, NoWaitDelayProvider())
            .executeStreaming(LlmRequest(provider(), "system", "user"))
            .toList()

        assertEquals(2, gateway.requests.size)
        assertEquals(listOf("书", "架"), chunks.filterIsInstance<LlmStreamChunk.TextDelta>().map { it.text })
        val completed = chunks.last() as LlmStreamChunk.Completed
        assertEquals("书架", completed.result.text)
        // 失败尝试同样计入用量与尝试次数。
        assertEquals(2, completed.result.attempts.size)
    }

    @Test
    fun retryingGatewayDoesNotRetryAfterTextWasForwarded() = runBlocking {
        val gateway = StreamingGateway(
            listOf(
                listOf(
                    LlmStreamChunk.TextDelta("半段答复"),
                    LlmStreamChunk.Failed(failure(LlmErrorCategory.NETWORK_UNAVAILABLE))
                ),
                // 第二次脚本不该被用到：重试会造成重复计费与重复内容。
                listOf(LlmStreamChunk.TextDelta("不该出现"))
            )
        )

        val chunks = RetryingLlmGateway(gateway, NoWaitDelayProvider())
            .executeStreaming(LlmRequest(provider(), "system", "user"))
            .toList()

        assertEquals(1, gateway.requests.size)
        assertEquals(
            listOf("半段答复"),
            chunks.filterIsInstance<LlmStreamChunk.TextDelta>().map { it.text }
        )
        assertTrue(chunks.last() is LlmStreamChunk.Failed)
    }

    @Test
    fun retryingGatewayStopsOnNonRetryableStreamFailure() = runBlocking {
        val gateway = StreamingGateway(
            listOf(
                listOf(LlmStreamChunk.Failed(failure(LlmErrorCategory.AUTHENTICATION, retryable = false))),
                listOf(LlmStreamChunk.TextDelta("不该出现"))
            )
        )

        val chunks = RetryingLlmGateway(gateway, NoWaitDelayProvider())
            .executeStreaming(LlmRequest(provider(), "system", "user"))
            .toList()

        assertEquals(1, gateway.requests.size)
        assertTrue(chunks.single() is LlmStreamChunk.Failed)
    }
}
