package com.breakyuna.noveltranslator.data.repository

import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import com.breakyuna.noveltranslator.data.db.ApiProviderDao
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persists provider settings while keeping secrets encrypted at rest. */
class ApiProviderRepository(
    private val apiProviderDao: ApiProviderDao,
    private val apiKeyCipher: ApiKeyCipher
) {
    val allProviders: Flow<List<ApiProviderEntity>> = apiProviderDao.getAllProviders()
        .map { providers -> providers.map(::decryptProvider) }

    suspend fun getDefaultProvider(): ApiProviderEntity? =
        apiProviderDao.getDefaultProvider()?.let(::decryptProvider)

    suspend fun getProviderById(id: Long): ApiProviderEntity? =
        apiProviderDao.getProviderById(id)?.let(::decryptProvider)

    suspend fun insertProvider(provider: ApiProviderEntity): Long =
        apiProviderDao.insertProvider(encryptProvider(provider))

    suspend fun updateProvider(provider: ApiProviderEntity) =
        apiProviderDao.updateProvider(encryptProvider(provider))

    suspend fun setDefaultProvider(id: Long) {
        apiProviderDao.clearDefaultFlags()
        apiProviderDao.setDefaultProvider(id)
    }

    suspend fun deleteProviderById(id: Long) = apiProviderDao.deleteProviderById(id)

    /** Removes obsolete starter presets and normalizes the built-in DeepSeek model. */
    suspend fun normalizeBuiltInPresets() {
        val providers = apiProviderDao.getAllProviders().first()
        providers.filter {
            it.providerType == ProviderType.DEEPSEEK && it.selectedModel == "deepseek-chat" &&
                it.name in setOf("DeepSeek", "DeepSeek (Official)", "DeepSeek API")
        }.forEach { apiProviderDao.updateProvider(it.copy(selectedModel = "deepseek-v4-flash")) }
        providers.filter { provider ->
            provider.apiKey.isBlank() && (
                provider.name == "Google Gemini" && provider.providerType == ProviderType.GEMINI_DIRECT ||
                    provider.name == "Anthropic Claude" && provider.providerType == ProviderType.ANTHROPIC_CLAUDE
                )
        }.forEach { apiProviderDao.deleteProviderById(it.id) }
        val remaining = apiProviderDao.getAllProviders().first()
        if (remaining.none { it.isDefault }) {
            remaining.firstOrNull { it.providerType == ProviderType.OPENAI_COMPATIBLE }
                ?.let { apiProviderDao.setDefaultProvider(it.id) }
        }
    }

    /** Encrypts secrets written by older app versions exactly once. */
    suspend fun encryptUnprotectedSecrets() {
        apiProviderDao.getAllProviders().first().forEach { provider ->
            val hasPlainApiKey = provider.apiKey.isNotBlank() && !apiKeyCipher.isEncrypted(provider.apiKey)
            val headers = provider.customHeadersJson.trim()
            val hasPlainCustomHeaders = headers.isNotBlank() && headers != "{}" && !apiKeyCipher.isEncrypted(headers)
            if (hasPlainApiKey || hasPlainCustomHeaders) {
                apiProviderDao.updateProvider(encryptProvider(provider))
            }
        }
    }

    private fun encryptProvider(provider: ApiProviderEntity): ApiProviderEntity {
        val headers = provider.customHeadersJson.trim().ifBlank { "{}" }
        return provider.copy(
            apiKey = apiKeyCipher.encrypt(provider.apiKey.trim()),
            customHeadersJson = if (headers == "{}") headers else apiKeyCipher.encrypt(headers)
        )
    }

    private fun decryptProvider(provider: ApiProviderEntity): ApiProviderEntity = provider.copy(
        apiKey = apiKeyCipher.decrypt(provider.apiKey),
        customHeadersJson = apiKeyCipher.decrypt(provider.customHeadersJson).ifBlank { "{}" }
    )
}
