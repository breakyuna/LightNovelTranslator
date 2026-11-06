package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailurePolicyTest {
    @Test
    fun exhaustedProviderAndConfigurationFailuresAbortTheRun() {
        // 引擎重构后：仅“重试也无济于事”的类别终止整个 run。
        listOf(
            LlmErrorCategory.AUTHENTICATION,
            LlmErrorCategory.INVALID_REQUEST,
            LlmErrorCategory.LOCAL_STORAGE,
            LlmErrorCategory.UNKNOWN
        ).forEach { category ->
            assertTrue(category.name, TranslationFailurePolicy.isFatal(category))
        }
    }

    @Test
    fun transientProviderFailuresAreRecoverableNotFatal() {
        // 网络/超时/限流/服务端错误重试耗尽后不再杀死整本书：退避重排队。
        listOf(
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            LlmErrorCategory.TIMEOUT,
            LlmErrorCategory.RATE_LIMIT,
            LlmErrorCategory.SERVER_ERROR
        ).forEach { category ->
            assertFalse(category.name, TranslationFailurePolicy.isFatal(category))
            assertTrue(category.name, TranslationFailurePolicy.isRecoverable(category))
        }
    }

    @Test
    fun chapterLocalOutputFailuresAllowLaterChaptersToContinue() {
        listOf(
            LlmErrorCategory.CONTENT_FILTER,
            LlmErrorCategory.EMPTY_RESPONSE,
            LlmErrorCategory.TRUNCATED_OUTPUT,
            LlmErrorCategory.PARSE_ERROR,
            LlmErrorCategory.CONTEXT_OVERFLOW,
            LlmErrorCategory.QUALITY_REJECTED
        ).forEach { category ->
            assertFalse(category.name, TranslationFailurePolicy.isFatal(category))
        }
    }
}
