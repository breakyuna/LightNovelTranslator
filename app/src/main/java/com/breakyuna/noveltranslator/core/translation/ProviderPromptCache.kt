package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType

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

/**
 * Anthropic explicit prompt caching. The client turns the presence of a prompt-cache hint into
 * `cache_control: {"type":"ephemeral"}` breakpoints on the system block and on the last user
 * content block, so this capability only has to describe the cache it expects.
 */
object AnthropicPromptCacheCapability : ProviderPromptCacheCapability {
    /** Anthropic keeps an ephemeral cache entry alive for five minutes. */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    override fun supports(provider: ApiProviderEntity): Boolean =
        provider.providerType == ProviderType.ANTHROPIC_CLAUDE

    override suspend fun prepare(
        provider: ApiProviderEntity,
        fingerprint: String,
        stablePrefix: String,
        expectedReuseCount: Int
    ): PromptCacheHandle {
        SystemLogger.info(
            "PROMPT_CACHE",
            "Anthropic 显式提示词缓存已启用 (模型: ${provider.selectedModel}, 稳定前缀 ${stablePrefix.length} 字符)"
        )
        return PromptCacheHandle(
            fingerprint = fingerprint,
            // Anthropic has no create-cache endpoint: the breakpoint itself is the cache.
            remoteCacheId = null,
            cachedTokenCount = stablePrefix.length / 4L,
            expiresAt = System.currentTimeMillis() + CACHE_TTL_MS
        )
    }
}

/**
 * Providers with implicit/automatic prompt caching (OpenAI compatible, DeepSeek, Gemini and the
 * other OpenAI-shaped gateways). Nothing changes on the wire: identical prompt prefixes are
 * discounted server-side, so the capability only records that caching applies.
 */
object AutomaticPromptCacheCapability : ProviderPromptCacheCapability {
    override fun supports(provider: ApiProviderEntity): Boolean = when (provider.providerType) {
        ProviderType.ANTHROPIC_CLAUDE -> false
        ProviderType.OLLAMA_LOCAL -> false
        else -> true
    }

    override suspend fun prepare(
        provider: ApiProviderEntity,
        fingerprint: String,
        stablePrefix: String,
        expectedReuseCount: Int
    ): PromptCacheHandle = PromptCacheHandle(
        fingerprint = fingerprint,
        remoteCacheId = null,
        cachedTokenCount = stablePrefix.length / 4L,
        expiresAt = null
    )
}

/**
 * Dispatches to the capability that matches the resolved provider. The engine is constructed once
 * while the provider is only known per project, so provider selection lives here instead of in
 * the engine constructor.
 */
class CompositePromptCacheCapability(
    private val delegates: List<ProviderPromptCacheCapability>
) : ProviderPromptCacheCapability {
    constructor(vararg delegates: ProviderPromptCacheCapability) : this(delegates.toList())

    override fun supports(provider: ApiProviderEntity): Boolean = delegates.any { it.supports(provider) }

    override suspend fun prepare(
        provider: ApiProviderEntity,
        fingerprint: String,
        stablePrefix: String,
        expectedReuseCount: Int
    ): PromptCacheHandle? = delegates.firstOrNull { it.supports(provider) }
        ?.prepare(provider, fingerprint, stablePrefix, expectedReuseCount)
}

object PromptCachePolicy {
    /** Explicit remote caches are only worthwhile when the same stable prefix will be reused. */
    fun shouldCreate(stablePrefixTokens: Long, expectedReuseCount: Int): Boolean =
        stablePrefixTokens >= 1_024 && expectedReuseCount >= 2
}
