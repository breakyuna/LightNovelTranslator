package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLlmGateway(
    private val script: ArrayDeque<LlmResult>
) : LlmGateway {
    var calls: Int = 0
        private set

    override suspend fun executeCompletion(request: LlmRequest): LlmResult {
        calls++
        return script.removeFirst()
    }

    override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
        Result.success(emptyList())
}

private class NoWaitDelayProvider : DelayProvider {
    val waits = mutableListOf<Long>()
    override suspend fun delayFor(milliseconds: Long) {
        waits += milliseconds
    }
}

private fun provider() = ApiProviderEntity(
    name = "fake",
    providerType = ProviderType.OPENAI_COMPATIBLE,
    baseUrl = "https://example.invalid/v1",
    apiKey = "not-real",
    selectedModel = "fake-model"
)

private fun failure(category: LlmErrorCategory, retryable: Boolean = true, retryAfterMs: Long? = null) =
    LlmResult(
        text = "",
        promptTokens = 2,
        completionTokens = 0,
        isSuccess = false,
        errorCategory = category,
        retryable = retryable,
        retryAfterMs = retryAfterMs,
        errorMessage = category.name
    )

class RetryPolicyTest {
    @Test
    fun networkFailuresAreRetriedAndEachAttemptIsReturned() = runTest {
        val fake = FakeLlmGateway(
            ArrayDeque(
                listOf(
                    failure(LlmErrorCategory.NETWORK_UNAVAILABLE),
                    failure(LlmErrorCategory.NETWORK_UNAVAILABLE),
                    LlmResult("ok", 4, 3, true)
                )
            )
        )
        val waits = NoWaitDelayProvider()
        val result = RetryingLlmGateway(fake, waits).executeCompletion(
            LlmRequest(provider(), "system", "user")
        )

        assertEquals(3, fake.calls)
        assertEquals(3, result.attempts.size)
        assertEquals(8, result.promptTokens)
        assertTrue(result.isSuccess)
        assertEquals(2, waits.waits.size)
    }

    @Test
    fun rateLimitUsesServerRetryAfterWithoutRealWaiting() = runTest {
        val fake = FakeLlmGateway(ArrayDeque(listOf(
            failure(LlmErrorCategory.RATE_LIMIT, retryAfterMs = 75_000),
            LlmResult("ok", 1, 1, true)
        )))
        val waits = NoWaitDelayProvider()
        RetryingLlmGateway(fake, waits).executeCompletion(LlmRequest(provider(), "", ""))
        assertEquals(listOf(75_000L), waits.waits)
    }

    @Test
    fun authenticationFailureIsNotRetried() = runTest {
        val fake = FakeLlmGateway(ArrayDeque(listOf(failure(LlmErrorCategory.AUTHENTICATION, retryable = false))))
        val result = RetryingLlmGateway(fake, NoWaitDelayProvider())
            .executeCompletion(LlmRequest(provider(), "", ""))
        assertEquals(1, fake.calls)
        assertFalse(result.isSuccess)
        assertEquals(LlmErrorCategory.AUTHENTICATION, result.errorCategory)
    }

    @Test
    fun serverErrorsStopAfterFourAttempts() = runTest {
        val fake = FakeLlmGateway(ArrayDeque(List(4) { failure(LlmErrorCategory.SERVER_ERROR) }))
        val result = RetryingLlmGateway(fake, NoWaitDelayProvider())
            .executeCompletion(LlmRequest(provider(), "", ""))
        assertEquals(4, fake.calls)
        assertEquals(4, result.attempts.size)
    }

    @Test
    fun timeoutAndParseFailuresUseTheirBoundedBudgets() {
        assertEquals(5, RetryPolicy.decide(failure(LlmErrorCategory.TIMEOUT), 1).maxAttempts)
        assertEquals(3, RetryPolicy.decide(failure(LlmErrorCategory.PARSE_ERROR), 1).maxAttempts)
        assertEquals(1, RetryPolicy.decide(failure(LlmErrorCategory.CONTEXT_OVERFLOW), 1).maxAttempts)
        assertEquals(1, RetryPolicy.decide(failure(LlmErrorCategory.INVALID_REQUEST), 1).maxAttempts)
    }

    @Test
    fun retryAfterIsNotCappedByNormalDelayLimit() {
        val decision = RetryPolicy.decide(
            failure(LlmErrorCategory.RATE_LIMIT, retryAfterMs = 180_000),
            attemptNumber = 1
        )
        assertTrue(decision.retry)
        assertEquals(180_000, decision.delayMs)
    }

    @Test
    fun pauseDuringBackoffPreventsAnotherPhysicalRequest() = runTest {
        val signal = TranslationControlSignal()
        val fake = FakeLlmGateway(ArrayDeque(listOf(
            failure(LlmErrorCategory.NETWORK_UNAVAILABLE),
            LlmResult("must not run", 1, 1, true)
        )))
        val delay = object : DelayProvider {
            override suspend fun delayFor(milliseconds: Long) {
                signal.requestPause()
            }
        }
        val result = RetryingLlmGateway(fake, delay, signal)
            .executeCompletion(LlmRequest(provider(), "", ""))

        assertEquals(1, fake.calls)
        assertEquals(1, result.attempts.size)
        assertFalse(result.isSuccess)
    }

    @Test
    fun perRequestCancellationStopsBeforeTheFirstPhysicalRequest() = runTest {
        val signal = TranslationControlSignal().also { it.cancel() }
        val fake = FakeLlmGateway(ArrayDeque(listOf(LlmResult("must not run", 1, 1, true))))
        val error = runCatching {
            RetryingLlmGateway(fake).executeCompletion(
                LlmRequest(provider(), "", "", controlSignal = signal)
            )
        }.exceptionOrNull()

        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(0, fake.calls)
    }

    @Test
    fun truncatedSuccessIsNormalizedToARejectedResponse() = runTest {
        val fake = FakeLlmGateway(ArrayDeque(listOf(
            LlmResult(
                text = "partial",
                promptTokens = 1,
                completionTokens = 2,
                isSuccess = true,
                isTruncated = true,
                finishReason = "length"
            )
        )))
        val result = RetryingLlmGateway(fake).executeCompletion(LlmRequest(provider(), "", ""))

        assertFalse(result.isSuccess)
        assertEquals(LlmErrorCategory.TRUNCATED_OUTPUT, result.errorCategory)
        assertEquals(1, fake.calls)
    }
}
