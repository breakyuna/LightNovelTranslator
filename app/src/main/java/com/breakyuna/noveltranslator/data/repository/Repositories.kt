package com.breakyuna.noveltranslator.data.repository

import com.breakyuna.noveltranslator.core.security.ApiKeyCipher
import com.breakyuna.noveltranslator.data.db.ApiProviderDao
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.Locale

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
        apiProviderDao.insertProviderAndRepairDefault(encryptProvider(normalizeProvider(provider)))

    suspend fun updateProvider(provider: ApiProviderEntity) {
        check(apiProviderDao.updateProviderAndRepairDefault(encryptProvider(normalizeProvider(provider)))) {
            "API provider not found"
        }
    }

    suspend fun setDefaultProvider(id: Long) {
        check(apiProviderDao.replaceDefaultProvider(id)) { "API provider not found" }
    }

    suspend fun deleteProviderById(id: Long) {
        check(apiProviderDao.deleteProviderAndRepairDefault(id)) { "API provider not found" }
    }

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
                ?.let { apiProviderDao.replaceDefaultProvider(it.id) }
        }
        apiProviderDao.repairDefaultProvider()
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

    private fun normalizeProvider(provider: ApiProviderEntity): ApiProviderEntity {
        val name = provider.name.trim().take(120)
        val baseUrl = provider.baseUrl.trim().take(2_048)
        val selectedModel = provider.selectedModel.trim().take(240)
        val apiKey = provider.apiKey.trim()
        val currency = provider.currency.trim().uppercase(Locale.ROOT).ifBlank { "USD" }
        val headers = provider.customHeadersJson.trim().ifBlank { "{}" }
        require(name.isNotBlank()) { "Provider name must not be blank" }
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(name.isSafeProviderText() && baseUrl.isSafeProviderText() &&
            selectedModel.isSafeProviderText() && apiKey.isSafeProviderText() &&
            headers.none { it.code == 0 || it.code == 127 }
        ) {
            "Provider configuration contains unsupported control characters"
        }
        require(apiKey.length <= 8_192) { "API key is too long" }
        require(currency.length in 3..8 && currency.all(Char::isLetter)) { "Invalid billing currency" }
        require(provider.inputPricePerMillion.isFinite() && provider.inputPricePerMillion >= 0.0) {
            "Input price must be a non-negative number"
        }
        require(provider.outputPricePerMillion.isFinite() && provider.outputPricePerMillion >= 0.0) {
            "Output price must be a non-negative number"
        }
        require(provider.temperature.isFinite() && provider.temperature in 0f..2f) {
            "Temperature must be between 0 and 2"
        }
        require(provider.maxContextTokens in 1_024..2_000_000) { "Invalid context-token limit" }
        val headerObject = runCatching { JSONObject(headers) }
            .getOrElse { throw IllegalArgumentException("Custom headers must be valid JSON") }
        require(headerObject.keys().asSequence().all { key ->
            key.isNotBlank() && key.length <= 128 && key.none { it == '\r' || it == '\n' }
        }) { "Invalid custom header name" }
        headerObject.keys().forEach { key ->
            val value = headerObject.opt(key)
            require(value is String && value.length <= 4_096 && value.none { it == '\r' || it == '\n' }) {
                "Custom header values must be single-line strings"
            }
        }
        return provider.copy(
            name = name,
            baseUrl = baseUrl,
            selectedModel = selectedModel,
            apiKey = apiKey,
            currency = currency,
            customHeadersJson = headers
        )
    }

    private fun String.isSafeProviderText(): Boolean = none { it.code < 32 || it.code == 127 }

    private fun decryptProvider(provider: ApiProviderEntity): ApiProviderEntity = provider.copy(
        apiKey = apiKeyCipher.decrypt(provider.apiKey),
        customHeadersJson = apiKeyCipher.decrypt(provider.customHeadersJson).ifBlank { "{}" }
    )
}
