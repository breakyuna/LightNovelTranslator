package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections

/** How one immutable model configuration obtains its request credential. */
enum class ModelAuthMode {
    API_KEY,
    OAUTH,
    NONE,
}

/**
 * OAuth material for one immutable configuration revision. Values are never
 * included in [toString]; callers must opt in through the properties and must not
 * log, serialize into diagnostics or place them in prompts/history.
 *
 * [expiresAtMillis] is an absolute epoch-millisecond instant. A null expiry means
 * the provider did not publish one, so the token is used until the server rejects
 * it; that rejection must not be retried automatically within the same request.
 */
class OAuthCredential private constructor(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
    scopes: List<String>,
    val tokenType: String,
) {
    val scopes: List<String> = Collections.unmodifiableList(ArrayList(scopes))

    init {
        require(validSecret(accessToken)) { "Invalid OAuth access token" }
        require(refreshToken == null || validSecret(refreshToken)) { "Invalid OAuth refresh token" }
        require(expiresAtMillis == null || expiresAtMillis >= 0) { "Invalid OAuth expiry" }
        require(tokenType.isNotBlank() && tokenType.length <= MAX_TOKEN_TYPE_CHARS &&
            tokenType.all { it.code in PRINTABLE_ASCII }) { "Invalid OAuth token type" }
        require(this.scopes.size <= MAX_SCOPES) { "Too many OAuth scopes" }
        this.scopes.forEach { scope ->
            require(scope.isNotEmpty() && scope.length <= MAX_SCOPE_CHARS &&
                scope.all { it.code in PRINTABLE_ASCII }) { "Invalid OAuth scope" }
        }
    }

    /** True when the provider-published expiry has been reached. */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiresAtMillis ?: return false
        return expiry <= nowMillis.coerceAtLeast(0)
    }

    /**
     * True when a refresh should happen before the next request. A null expiry is
     * never refreshed proactively: doing so would turn every request into a token
     * request for providers that omit `expires_in`.
     */
    fun needsRefresh(nowMillis: Long = System.currentTimeMillis(),
        windowMillis: Long = DEFAULT_REFRESH_WINDOW_MILLIS): Boolean {
        require(windowMillis >= 0) { "Invalid OAuth refresh window" }
        val expiry = expiresAtMillis ?: return false
        val now = nowMillis.coerceAtLeast(0)
        if (now > Long.MAX_VALUE - windowMillis) return true
        return expiry <= now + windowMillis
    }

    override fun toString(): String =
        "OAuthCredential(accessToken=redacted, refreshToken=${if (refreshToken == null) "none" else "present"}, " +
            "expiresAtMillis=$expiresAtMillis, scopes=${scopes.size}, tokenType=$tokenType)"

    companion object {
        /** Refresh five minutes before a provider-published expiry. */
        const val DEFAULT_REFRESH_WINDOW_MILLIS = 300_000L
        const val MAX_TOKEN_CHARS = 32_768
        const val MAX_SCOPES = 32
        const val MAX_SCOPE_CHARS = 512
        const val MAX_TOKEN_TYPE_CHARS = 64

        fun of(accessToken: String, refreshToken: String? = null, expiresAtMillis: Long? = null,
            scopes: List<String> = emptyList(), tokenType: String = "Bearer"): OAuthCredential =
            OAuthCredential(accessToken, refreshToken?.takeIf { it.isNotEmpty() }, expiresAtMillis,
                scopes.distinct(), tokenType)

        internal fun validSecret(value: String): Boolean =
            value.length in 1..MAX_TOKEN_CHARS && value.all { it.code in PRINTABLE_ASCII }

        private val PRINTABLE_ASCII = 33..126
    }
}

/** Sanitized OAuth failure categories. No provider response, token or header is retained. */
enum class OAuthFailureKind {
    MISSING_CREDENTIAL,
    EXPIRED_WITHOUT_REFRESH,
    REFRESH_REJECTED,
    REFRESH_UNAVAILABLE,
    NETWORK,
    TIMEOUT,
    MALFORMED_RESPONSE,
    STORE_CORRUPT,
    STORE_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Credential-layer failure. The provider maps every kind to the model
 * AUTHENTICATION failure; this type is retained only to classify the cause
 * without carrying any credential material.
 */
class OAuthFailure(val kind: OAuthFailureKind) : Exception("OAuth credential failure: $kind") {
    override fun toString(): String = "OAuthFailure(kind=$kind)"
}

/**
 * Atomic, encrypted credential persistence. Implementations must reject corruption
 * and identity/AAD mismatches instead of returning no-auth, and must never write
 * credentials to diagnostics, usage, prompts or backups.
 */
interface OAuthCredentialStore {
    suspend fun load(reference: ModelConfigurationRef): OAuthCredential?
    suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential)
    suspend fun clear(reference: ModelConfigurationRef)
}
