package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory

/**
 * Decides whether a failed request is chapter-local or makes later requests predictably fail.
 *
 * Transient provider failures (network, timeout, rate limit, 5xx) are no longer run-fatal: the
 * gateway has already exhausted its own bounded retries, so the engine re-queues the affected
 * chapter with a capped backoff instead of abandoning chapters that were never attempted.
 * Only errors that every later request would reproduce are fatal.
 */
object TranslationFailurePolicy {
    private val FATAL_CATEGORIES = setOf(
        LlmErrorCategory.AUTHENTICATION,
        LlmErrorCategory.INVALID_REQUEST,
        LlmErrorCategory.LOCAL_STORAGE,
        LlmErrorCategory.UNKNOWN
    )

    /** True when retrying the same request later cannot plausibly succeed. */
    fun isFatal(category: LlmErrorCategory?): Boolean = category in FATAL_CATEGORIES

    /** True when the failure is transient and the chapter can be re-queued with a backoff. */
    fun isRecoverable(category: LlmErrorCategory?): Boolean = !isFatal(category)
}
