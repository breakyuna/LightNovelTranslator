package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.model.ApiProviderEntity

data class PromptCacheHandle(
    val fingerprint: String,
    val remoteCacheId: String?,
    val cachedTokenCount: Long,
    val expiresAt: Long?
)

/** Provider-specific cache behavior stays outside TranslationEngine and can be added without provider branches there. */
interface ProviderPromptCacheCapability {
    fun supports(provider: ApiProviderEntity): Boolean

    suspend fun prepare(
        provider: ApiProviderEntity,
        fingerprint: String,
        stablePrefix: String,
        expectedReuseCount: Int
    ): PromptCacheHandle?
}

object NoPromptCacheCapability : ProviderPromptCacheCapability {
    override fun supports(provider: ApiProviderEntity) = false
    override suspend fun prepare(provider: ApiProviderEntity, fingerprint: String, stablePrefix: String, expectedReuseCount: Int): PromptCacheHandle? = null
}

object PromptCachePolicy {
    /** Explicit remote caches are only worthwhile when the same stable prefix will be reused. */
    fun shouldCreate(stablePrefixTokens: Long, expectedReuseCount: Int): Boolean =
        stablePrefixTokens >= 1_024 && expectedReuseCount >= 2
}
