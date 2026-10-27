package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelAuthMode
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential

/**
 * One credential resolved for a single dispatch. API-key selections keep their
 * pool fact so success/failure can update rotation exactly as before. OAuth tokens
 * are never handed to [ModelKeyPool] and are redacted from [toString].
 */
class ModelCredential private constructor(
    val mode: ModelAuthMode,
    val token: String,
    internal val keySelection: KeySelection?,
) {
    init {
        when (mode) {
            ModelAuthMode.API_KEY -> require(token.isNotEmpty() && keySelection != null &&
                token == keySelection.credential)
            ModelAuthMode.OAUTH -> require(token.isNotEmpty() && keySelection == null)
            ModelAuthMode.NONE -> require(token.isEmpty() && keySelection == null)
        }
    }

    override fun toString(): String = "ModelCredential(mode=$mode, token=redacted)"

    companion object {
        internal fun apiKey(selection: KeySelection?): ModelCredential =
            if (selection == null) none() else ModelCredential(ModelAuthMode.API_KEY, selection.credential, selection)

        fun oauth(credential: OAuthCredential): ModelCredential =
            ModelCredential(ModelAuthMode.OAUTH, credential.accessToken, null)

        fun none(): ModelCredential = ModelCredential(ModelAuthMode.NONE, "", null)
    }
}

/**
 * Injectable authentication boundary for Agent, Translation and model discovery.
 * Default implementations preserve the existing API-key pool path. An OAuth
 * implementation must answer [mode] without performing a model request; discovery
 * uses that answer to avoid advancing the key-pool cursor on metadata calls.
 */
interface ModelCredentialResolver {
    /** Non-OAuth resolvers keep the historical API-key path. */
    suspend fun mode(reference: ModelConfigurationRef): ModelAuthMode = ModelAuthMode.API_KEY

    /** Resolve immediately before dispatch. It may refresh an OAuth access token. */
    suspend fun resolve(reference: ModelConfigurationRef): ModelCredential

    /** Called only after a completed provider response; OAuth is a no-op by default. */
    suspend fun success(reference: ModelConfigurationRef, credential: ModelCredential?) = Unit

    /** Called only after a classified provider failure; no retry is started here. */
    suspend fun failure(reference: ModelConfigurationRef, credential: ModelCredential?,
        kind: ModelFailureKind, retryAfterMillis: Long? = null) = Unit
}

/** The unchanged default: select, rotate and cool down API keys through [ModelKeyPool]. */
class ApiKeyModelCredentialResolver(private val keys: ModelKeyPool) : ModelCredentialResolver {
    override suspend fun resolve(reference: ModelConfigurationRef): ModelCredential =
        ModelCredential.apiKey(keys.select(reference))

    override suspend fun success(reference: ModelConfigurationRef, credential: ModelCredential?) {
        keys.success(reference, credential?.keySelection)
    }

    override suspend fun failure(reference: ModelConfigurationRef, credential: ModelCredential?,
        kind: ModelFailureKind, retryAfterMillis: Long?) {
        keys.failure(reference, credential?.keySelection, kind, retryAfterMillis)
    }
}

/**
 * OAuth resolver for one profile revision, or for every revision when [reference]
 * is null. [fallback] supports mixed profile sets; without it, other revisions are
 * explicit no-auth and never silently fall back to the key pool.
 */
class OAuthCredentialResolver(
    private val provider: OAuthTokenProvider,
    private val reference: ModelConfigurationRef? = null,
    private val fallback: ModelCredentialResolver? = null,
) : ModelCredentialResolver {
    override suspend fun mode(reference: ModelConfigurationRef): ModelAuthMode =
        if (usesOAuth(reference)) ModelAuthMode.OAUTH
        else fallback?.mode(reference) ?: ModelAuthMode.NONE

    override suspend fun resolve(reference: ModelConfigurationRef): ModelCredential =
        if (usesOAuth(reference)) ModelCredential.oauth(provider.credential(reference))
        else fallback?.resolve(reference) ?: ModelCredential.none()

    override suspend fun success(reference: ModelConfigurationRef, credential: ModelCredential?) {
        if (usesOAuth(reference)) return
        fallback?.success(reference, credential)
    }

    override suspend fun failure(reference: ModelConfigurationRef, credential: ModelCredential?,
        kind: ModelFailureKind, retryAfterMillis: Long?) {
        if (usesOAuth(reference)) return
        fallback?.failure(reference, credential, kind, retryAfterMillis)
    }

    private fun usesOAuth(reference: ModelConfigurationRef): Boolean =
        this.reference == null || this.reference == reference
}
