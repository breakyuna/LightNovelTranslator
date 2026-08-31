package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailurePolicyTest {
    @Test
    fun exhaustedProviderAndConfigurationFailuresAbortTheRun() {
        listOf(
            LlmErrorCategory.NETWORK_UNAVAILABLE,
            LlmErrorCategory.TIMEOUT,
            LlmErrorCategory.RATE_LIMIT,
            LlmErrorCategory.SERVER_ERROR,
            LlmErrorCategory.AUTHENTICATION,
            LlmErrorCategory.INVALID_REQUEST,
            LlmErrorCategory.LOCAL_STORAGE,
            LlmErrorCategory.UNKNOWN
        ).forEach { category ->
            assertTrue(category.name, TranslationFailurePolicy.shouldAbortRun(category))
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
            assertFalse(category.name, TranslationFailurePolicy.shouldAbortRun(category))
        }
    }
}
