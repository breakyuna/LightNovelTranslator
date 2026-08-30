package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory

/** Decides whether a failed request is chapter-local or makes later requests predictably fail. */
object TranslationFailurePolicy {
    fun shouldAbortRun(category: LlmErrorCategory?): Boolean = category in setOf(
        // The retrying gateway has already exhausted its bounded retry policy before these reach
        // the engine. Repeating the same provider failure for every later chapter is not useful.
        LlmErrorCategory.NETWORK_UNAVAILABLE,
        LlmErrorCategory.TIMEOUT,
        LlmErrorCategory.RATE_LIMIT,
        LlmErrorCategory.SERVER_ERROR,
        LlmErrorCategory.AUTHENTICATION,
        LlmErrorCategory.INVALID_REQUEST,
        LlmErrorCategory.LOCAL_STORAGE,
        LlmErrorCategory.UNKNOWN
    )
}
