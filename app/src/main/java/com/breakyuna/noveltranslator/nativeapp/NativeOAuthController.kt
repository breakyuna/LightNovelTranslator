package com.breakyuna.noveltranslator.nativeapp

import android.util.Base64
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelAuthMode
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfilePort
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import com.breakyuna.noveltranslator.nativecore.engine.ModelCredential
import com.breakyuna.noveltranslator.nativecore.engine.ModelCredentialResolver
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NativeOAuthMode { API_KEY, OAUTH }

enum class NativeOAuthStatus {
    NOT_CONFIGURED,
    SIGNED_OUT,
    CONNECTED,
    EXPIRING_SOON,
    EXPIRED,
}

enum class NativeOAuthUiFailure {
    BUSY,
    PROFILE_MISSING,
    NOT_CONFIGURED,
    INVALID_CONFIGURATION,
    CONFIG_CONFLICT,
    CONFIG_CHANGED,
    CALLBACK_INVALID,
    ACCESS_DENIED,
    REJECTED,
    UNAVAILABLE,
    NETWORK,
    TIMEOUT,
    MALFORMED,
    STORE,
    NO_BROWSER,
    UNKNOWN,
}

enum class NativeOAuthUiNotice {
    CONFIG_SAVED,
    LOGIN_STARTED,
    LOGIN_CANCELLED,
    CONNECTED,
    SIGNED_OUT,
}

/**
 * The complete UI-visible OAuth state. It intentionally contains no access token,
 * refresh token or client secret; only presence, expiry and scopes are exposed.
 */
data class NativeOAuthUiState(
    val profileId: String? = null,
    val revision: Long = -1,
    val configured: Boolean = false,
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val clientId: String = "",
    val scopes: List<String> = emptyList(),
    val fingerprint: String? = null,
    val status: NativeOAuthStatus = NativeOAuthStatus.NOT_CONFIGURED,
    val expiresAtMillis: Long? = null,
    val refreshable: Boolean = false,
    val pendingLogin: Boolean = false,
    val busy: Boolean = false,
    val failure: NativeOAuthUiFailure? = null,
    val notice: NativeOAuthUiNotice? = null,
) {
    val hasToken: Boolean get() = status == NativeOAuthStatus.CONNECTED ||
        status == NativeOAuthStatus.EXPIRING_SOON || status == NativeOAuthStatus.EXPIRED

    override fun toString(): String =
        "NativeOAuthUiState(profileId=$profileId, revision=$revision, status=$status, " +
            "pendingLogin=$pendingLogin, busy=$busy, failure=$failure)"
}

class NativeOAuthConfigDraft(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: String,
) {
    fun toConfig(profileId: String, allowLoopbackHttp: Boolean): NativeOAuthConfig =
        NativeOAuthConfig.of(
            profileId = profileId,
            authorizationEndpoint = authorizationEndpoint.trim(),
            tokenEndpoint = tokenEndpoint.trim(),
            clientId = clientId.trim(),
            scopes = nativeOAuthScopes(scopes),
            redirectUri = NativeOAuthConfig.REDIRECT_URI,
            allowLoopbackHttp = allowLoopbackHttp,
        )
}

/** Commands exposed through the model page state, following the catalog-action pattern. */
class NativeOAuthActions(
    val load: (String) -> Unit = {},
    val saveConfiguration: (String, NativeOAuthConfigDraft) -> Unit = { _, _ -> },
    val removeConfiguration: (String) -> Unit = {},
    val login: (String) -> Unit = {},
    val cancel: (String) -> Unit = {},
    val refreshStatus: (String) -> Unit = {},
    val logout: (String) -> Unit = {},
)

sealed interface NativeOAuthLoginStart {
    data class Open(val url: String) : NativeOAuthLoginStart
    data class Rejected(val failure: NativeOAuthUiFailure) : NativeOAuthLoginStart
}

sealed interface NativeOAuthCallbackOutcome {
    data class Connected(val profileId: String) : NativeOAuthCallbackOutcome
    data class Failed(val profileId: String?, val failure: NativeOAuthUiFailure) : NativeOAuthCallbackOutcome
    data object Ignored : NativeOAuthCallbackOutcome
}

internal fun nativeOAuthFailure(kind: OAuthFailureKind): NativeOAuthUiFailure = when (kind) {
    OAuthFailureKind.MISSING_CREDENTIAL,
    OAuthFailureKind.EXPIRED_WITHOUT_REFRESH,
    OAuthFailureKind.REFRESH_REJECTED -> NativeOAuthUiFailure.REJECTED
    OAuthFailureKind.REFRESH_UNAVAILABLE -> NativeOAuthUiFailure.UNAVAILABLE
    OAuthFailureKind.NETWORK -> NativeOAuthUiFailure.NETWORK
    OAuthFailureKind.TIMEOUT -> NativeOAuthUiFailure.TIMEOUT
    OAuthFailureKind.MALFORMED_RESPONSE -> NativeOAuthUiFailure.MALFORMED
    OAuthFailureKind.STORE_CORRUPT,
    OAuthFailureKind.STORE_UNAVAILABLE -> NativeOAuthUiFailure.STORE
    OAuthFailureKind.UNKNOWN -> NativeOAuthUiFailure.UNKNOWN
}

internal fun nativeOAuthFailure(error: NativeOAuthCallbackError): NativeOAuthUiFailure = when (error) {
    NativeOAuthCallbackError.ACCESS_DENIED -> NativeOAuthUiFailure.ACCESS_DENIED
    NativeOAuthCallbackError.INVALID_REQUEST -> NativeOAuthUiFailure.REJECTED
    NativeOAuthCallbackError.SERVER_ERROR,
    NativeOAuthCallbackError.TEMPORARILY_UNAVAILABLE -> NativeOAuthUiFailure.UNAVAILABLE
    NativeOAuthCallbackError.INVALID_CALLBACK -> NativeOAuthUiFailure.CALLBACK_INVALID
    NativeOAuthCallbackError.UNKNOWN -> NativeOAuthUiFailure.UNKNOWN
}

internal fun nativeOAuthScopes(value: String): List<String> {
    if (value.length > 8192) throw NativeOAuthConfigurationException(
        NativeOAuthConfigurationException.Reason.INVALID)
    val scopes = value.split(OAUTH_SCOPE_SEPARATOR).filter { it.isNotEmpty() }.distinct()
    if (scopes.size > NativeOAuthConfig.MAX_SCOPES) throw NativeOAuthConfigurationException(
        NativeOAuthConfigurationException.Reason.INVALID)
    scopes.forEach { scope ->
        if (!OAUTH_SCOPE_TOKEN.matches(scope)) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
    }
    return Collections.unmodifiableList(ArrayList(scopes))
}

/**
 * Pure UI projection used by tests and the controller. A token is never part of the
 * result; only its published expiry and whether a refresh token exists are exposed.
 */
internal fun nativeOAuthUiState(
    profile: NativeModelProfile,
    config: NativeOAuthConfigSnapshot?,
    credential: OAuthCredential?,
    pending: NativeOAuthPendingFlow?,
    nowMillis: Long,
    busy: Boolean = false,
    failure: NativeOAuthUiFailure? = null,
    notice: NativeOAuthUiNotice? = null,
): NativeOAuthUiState {
    val now = nowMillis.coerceAtLeast(0)
    val status = when {
        config == null -> NativeOAuthStatus.NOT_CONFIGURED
        credential == null -> NativeOAuthStatus.SIGNED_OUT
        credential.isExpired(now) -> NativeOAuthStatus.EXPIRED
        credential.needsRefresh(now) -> NativeOAuthStatus.EXPIRING_SOON
        else -> NativeOAuthStatus.CONNECTED
    }
    return NativeOAuthUiState(
        profileId = profile.id,
        revision = profile.reference.revision,
        configured = config != null,
        authorizationEndpoint = config?.config?.authorizationEndpoint.orEmpty(),
        tokenEndpoint = config?.config?.tokenEndpoint.orEmpty(),
        clientId = config?.config?.clientId.orEmpty(),
        scopes = config?.config?.scopes.orEmpty(),
        fingerprint = config?.fingerprint,
        status = status,
        expiresAtMillis = credential?.expiresAtMillis,
        refreshable = credential?.refreshToken != null,
        pendingLogin = pending != null && pending.profileId == profile.id,
        busy = busy,
        failure = failure,
        notice = notice,
    )
}

/** PKCE S256 with Android Base64 URL-safe, unpadded encoding (API 24 compatible). */
internal object NativeOAuthPkce {
    fun state(random: SecureRandom = SecureRandom()): String = randomToken(random)
    fun verifier(random: SecureRandom = SecureRandom()): String = randomToken(random)

    fun challenge(verifier: String): String {
        if (!VERIFIER.matches(verifier)) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
        return base64Url(MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    private fun randomToken(random: SecureRandom): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private val VERIFIER = Regex("[A-Za-z0-9\\-._~]{43,128}")
}

internal fun nativeOAuthAuthorizationUrl(
    config: NativeOAuthConfig,
    state: String,
    challenge: String,
): String {
    val parameters = LinkedHashMap<String, String>()
    parameters["response_type"] = "code"
    parameters["client_id"] = config.clientId
    parameters["redirect_uri"] = config.redirectUri
    if (config.scopes.isNotEmpty()) parameters["scope"] = config.scopes.joinToString(" ")
    parameters["state"] = state
    parameters["code_challenge"] = challenge
    parameters["code_challenge_method"] = "S256"
    val endpoint = config.authorizationEndpoint.trimEnd('?', '&')
    val separator = if (endpoint.contains('?')) "&" else "?"
    return endpoint + separator + parameters.entries.joinToString("&") { (name, value) ->
        nativeOAuthFormEncode(name) + "=" + nativeOAuthFormEncode(value)
    }
}

/**
 * Per-reference auth selection. A stored OAuth credential makes the reference OAuth;
 * otherwise the unchanged API-key resolver is used. A corrupt OAuth store never
 * silently falls back to a key.
 */
class NativeOAuthFallbackCredentialResolver(
    private val oauth: ModelCredentialResolver,
    private val fallback: ModelCredentialResolver,
    private val hasOAuthCredential: suspend (ModelConfigurationRef) -> Boolean,
) : ModelCredentialResolver {
    override suspend fun mode(reference: ModelConfigurationRef): ModelAuthMode =
        if (hasOAuth(reference)) ModelAuthMode.OAUTH else fallback.mode(reference)

    override suspend fun resolve(reference: ModelConfigurationRef): ModelCredential =
        if (hasOAuth(reference)) oauth.resolve(reference) else fallback.resolve(reference)

    override suspend fun success(reference: ModelConfigurationRef, credential: ModelCredential?) {
        if (credential?.mode == ModelAuthMode.OAUTH) return
        fallback.success(reference, credential)
    }

    override suspend fun failure(
        reference: ModelConfigurationRef,
        credential: ModelCredential?,
        kind: ModelFailureKind,
        retryAfterMillis: Long?,
    ) {
        if (credential?.mode == ModelAuthMode.OAUTH) return
        if (credential == null && oauthInUseAfterResolutionFailure(reference)) return
        fallback.failure(reference, credential, kind, retryAfterMillis)
    }

    private suspend fun hasOAuth(reference: ModelConfigurationRef): Boolean = try {
        hasOAuthCredential(reference)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OAuthFailure) {
        throw authenticationFailure(failure)
    } catch (_: Exception) {
        throw authenticationFailure(null)
    }

    private suspend fun oauthInUseAfterResolutionFailure(reference: ModelConfigurationRef): Boolean = try {
        hasOAuthCredential(reference)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    private fun authenticationFailure(cause: OAuthFailure?): AgentModelFailure =
        AgentModelFailure(ModelFailureKind.AUTHENTICATION).also { failure ->
            if (cause != null) failure.initCause(cause)
        }
}

/**
 * Orchestrates configuration, PKCE login, callback handoff, token persistence,
 * refresh status and logout. Network exchange happens only after MainActivity has
 * consumed a state-validated callback, never inside [NativeOAuthCallbackActivity].
 */
class NativeOAuthController(
    private val configs: NativeOAuthConfigStore,
    private val flows: NativeOAuthFlowStore,
    private val client: NativeOAuthTokenClient,
    private val credentials: OAuthCredentialStore,
    private val profiles: NativeModelProfilePort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(NativeOAuthUiState())
    val state: StateFlow<NativeOAuthUiState> = _state.asStateFlow()

    suspend fun load(profileId: String): NativeOAuthUiState = mutex.withLock {
        loadLocked(profileId)
    }

    suspend fun saveConfiguration(
        profile: NativeModelProfile,
        draft: NativeOAuthConfigDraft,
    ): NativeOAuthUiState = mutex.withLock {
        try {
            val config = draft.toConfig(profile.id, configs.allowsLoopbackHttp)
            val previous = configs.load(profile.id)
            val fingerprint = configs.fingerprint(config)
            val changed = previous == null || previous.fingerprint != fingerprint
            if (changed) {
                // A token can only belong to the exact configuration that issued it. Clear
                // before the new configuration becomes active, including a token orphaned
                // by a missing configuration file.
                credentials.clear(profile.reference)
            }
            configs.save(config, expectedFingerprint(profile.id))
            if (changed) clearPendingIfProfile(profile.id)
            loadLocked(profile.id, notice = NativeOAuthUiNotice.CONFIG_SAVED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeOAuthConfigurationException) {
            loadLocked(profile.id, failure = configurationFailure(failure))
        } catch (_: Exception) {
            loadLocked(profile.id, failure = NativeOAuthUiFailure.STORE)
        }
    }

    suspend fun removeConfiguration(profile: NativeModelProfile): NativeOAuthUiState = mutex.withLock {
        try {
            // Invalidate any pending/callback handoff first, so a failed configuration
            // removal cannot be followed by a late callback reconnecting the profile.
            flows.clear()
            credentials.clear(profile.reference)
            try {
                configs.clear(profile.id, removalFingerprint(profile.id))
            } catch (failure: NativeOAuthConfigurationException) {
                // A corrupt document cannot prove its fingerprint. The explicit user action
                // may still force-remove it so the profile can be reconfigured.
                if (failure.reason != NativeOAuthConfigurationException.Reason.CORRUPT) throw failure
                configs.clear(profile.id, null)
            }
            loadLocked(profile.id, notice = NativeOAuthUiNotice.SIGNED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeOAuthConfigurationException) {
            loadLocked(profile.id, failure = configurationFailure(failure))
        } catch (failure: OAuthFailure) {
            loadLocked(profile.id, failure = nativeOAuthFailure(failure.kind))
        } catch (_: Exception) {
            loadLocked(profile.id, failure = NativeOAuthUiFailure.STORE)
        }
    }

    suspend fun beginLogin(profile: NativeModelProfile): NativeOAuthLoginStart = mutex.withLock {
        try {
            val snapshot = configs.load(profile.id) ?: run {
                loadLocked(profile.id, failure = NativeOAuthUiFailure.NOT_CONFIGURED)
                return@withLock NativeOAuthLoginStart.Rejected(NativeOAuthUiFailure.NOT_CONFIGURED)
            }
            val state = NativeOAuthPkce.state()
            val verifier = NativeOAuthPkce.verifier()
            flows.clear()
            flows.savePending(NativeOAuthPendingFlow(
                profileId = profile.id,
                revision = profile.reference.revision,
                configFingerprint = snapshot.fingerprint,
                state = state,
                codeVerifier = verifier,
                createdAtMillis = clock().coerceAtLeast(0),
            ))
            val url = nativeOAuthAuthorizationUrl(
                snapshot.config, state, NativeOAuthPkce.challenge(verifier))
            loadLocked(profile.id, notice = NativeOAuthUiNotice.LOGIN_STARTED)
            NativeOAuthLoginStart.Open(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeOAuthConfigurationException) {
            loadLocked(profile.id, failure = configurationFailure(failure))
            NativeOAuthLoginStart.Rejected(configurationFailure(failure))
        } catch (failure: OAuthFailure) {
            loadLocked(profile.id, failure = nativeOAuthFailure(failure.kind))
            NativeOAuthLoginStart.Rejected(nativeOAuthFailure(failure.kind))
        } catch (_: Exception) {
            loadLocked(profile.id, failure = NativeOAuthUiFailure.STORE)
            NativeOAuthLoginStart.Rejected(NativeOAuthUiFailure.STORE)
        }
    }

    suspend fun cancelLogin(profileId: String): NativeOAuthUiState = mutex.withLock {
        try {
            clearPendingIfProfile(profileId)
            loadLocked(profileId, notice = NativeOAuthUiNotice.LOGIN_CANCELLED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadLocked(profileId, failure = NativeOAuthUiFailure.STORE)
        }
    }

    suspend fun logout(profile: NativeModelProfile): NativeOAuthUiState = mutex.withLock {
        try {
            // Invalidate any pending/callback handoff before deleting the token. If this
            // fails, logout reports a failure instead of appearing signed out while a
            // previously accepted callback could still reconnect the profile.
            flows.clear()
            credentials.clear(profile.reference)
            loadLocked(profile.id, notice = NativeOAuthUiNotice.SIGNED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OAuthFailure) {
            loadLocked(profile.id, failure = nativeOAuthFailure(failure.kind))
        } catch (_: Exception) {
            loadLocked(profile.id, failure = NativeOAuthUiFailure.STORE)
        }
    }

    /**
     * Called by the exported callback Activity only after it checked the custom scheme
     * and host. It consumes the pending PKCE state exactly once, stores a bounded
     * one-time callback result and performs no network request.
     */
    suspend fun acceptCallback(
        scheme: String?,
        host: String?,
        parameters: NativeOAuthCallbackParameters,
    ): Boolean = mutex.withLock {
        acceptCallbackLocked(scheme, host, parameters)
    }

    private suspend fun acceptCallbackLocked(
        scheme: String?,
        host: String?,
        parameters: NativeOAuthCallbackParameters,
    ): Boolean {
        if (!NativeOAuthConfig.REDIRECT_SCHEME.equals(scheme, ignoreCase = true) ||
            !NativeOAuthConfig.REDIRECT_HOST.equals(host, ignoreCase = true)) return false
        val state = parameters.state ?: return false
        val pending = try {
            flows.consumePending(state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        val now = clock().coerceAtLeast(0)
        val code = parameters.code
        val result = when {
            parameters.error != null -> NativeOAuthCallbackResult.Failure(
                pending.profileId, pending.revision, pending.configFingerprint, now,
                nativeOAuthCallbackError(parameters.error),
            )
            code == null -> NativeOAuthCallbackResult.Failure(
                pending.profileId, pending.revision, pending.configFingerprint, now,
                NativeOAuthCallbackError.INVALID_CALLBACK,
            )
            else -> NativeOAuthCallbackResult.Success(
                pending.profileId, pending.revision, pending.configFingerprint, now,
                code, pending.codeVerifier,
            )
        }
        return try {
            flows.saveCallback(result)
            if (_state.value.profileId == pending.profileId) {
                _state.value = _state.value.copy(pendingLogin = false, busy = false)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Consumes one callback result. [allowNetwork] is false while a run owns the
     * runtime slot; the code is then discarded instead of being exchanged later.
     */
    suspend fun consumeCallback(allowNetwork: Boolean): NativeOAuthCallbackOutcome = mutex.withLock {
        val result = try {
            flows.consumeCallback()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                busy = false, failure = NativeOAuthUiFailure.STORE, notice = null)
            return@withLock NativeOAuthCallbackOutcome.Failed(null, NativeOAuthUiFailure.STORE)
        } ?: return@withLock NativeOAuthCallbackOutcome.Ignored

        val profile = try {
            profiles.find(result.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: run {
            updateIfCurrent(result.profileId, NativeOAuthUiFailure.PROFILE_MISSING)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.PROFILE_MISSING)
        }
        if (!allowNetwork) {
            updateIfCurrent(result.profileId, NativeOAuthUiFailure.BUSY)
            return@withLock NativeOAuthCallbackOutcome.Failed(result.profileId, NativeOAuthUiFailure.BUSY)
        }
        if (result is NativeOAuthCallbackResult.Failure) {
            val failure = nativeOAuthFailure(result.error)
            updateIfCurrent(result.profileId, failure)
            return@withLock NativeOAuthCallbackOutcome.Failed(result.profileId, failure)
        }
        result as NativeOAuthCallbackResult.Success
        if (profile.reference.revision != result.revision) {
            loadForCallback(result.profileId, failure = NativeOAuthUiFailure.CONFIG_CHANGED)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.CONFIG_CHANGED)
        }
        val snapshot = try {
            configs.load(result.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (snapshot == null || snapshot.fingerprint != result.configFingerprint) {
            loadForCallback(result.profileId, failure = NativeOAuthUiFailure.CONFIG_CHANGED)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.CONFIG_CHANGED)
        }
        if (_state.value.profileId == null || _state.value.profileId == result.profileId) {
            _state.value = _state.value.copy(
                profileId = result.profileId, busy = true, failure = null, notice = null)
        }
        val credential = try {
            client.exchange(snapshot.config, result.code, result.codeVerifier, snapshot.config.scopes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OAuthFailure) {
            loadForCallback(result.profileId, failure = nativeOAuthFailure(failure.kind))
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, nativeOAuthFailure(failure.kind))
        } catch (_: Exception) {
            loadForCallback(result.profileId, failure = NativeOAuthUiFailure.UNKNOWN)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.UNKNOWN)
        }
        // The configuration or model revision may have changed while the token request
        // was in flight. Never persist a token for a configuration that is no longer current.
        val currentProfile = try {
            profiles.find(result.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val currentConfig = try {
            configs.load(result.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (currentProfile == null || currentProfile.reference.revision != result.revision ||
            currentConfig == null || currentConfig.fingerprint != result.configFingerprint) {
            loadForCallback(result.profileId, failure = NativeOAuthUiFailure.CONFIG_CHANGED)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.CONFIG_CHANGED)
        }
        try {
            credentials.save(currentProfile.reference, credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OAuthFailure) {
            loadForCallback(result.profileId, failure = nativeOAuthFailure(failure.kind))
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, nativeOAuthFailure(failure.kind))
        } catch (_: Exception) {
            loadForCallback(result.profileId, failure = NativeOAuthUiFailure.STORE)
            return@withLock NativeOAuthCallbackOutcome.Failed(
                result.profileId, NativeOAuthUiFailure.STORE)
        }
        loadForCallback(result.profileId, notice = NativeOAuthUiNotice.CONNECTED)
        NativeOAuthCallbackOutcome.Connected(result.profileId)
    }

    suspend fun reportFailure(profileId: String?, failure: NativeOAuthUiFailure) {
        mutex.withLock {
            if (profileId == null) {
                _state.value = _state.value.copy(busy = false, failure = failure, notice = null)
            } else if (_state.value.profileId == null || _state.value.profileId == profileId) {
                loadLocked(profileId, failure = failure)
            }
        }
    }

    /** Deletes one configuration revision's token after the model configuration changes. */
    suspend fun clearCredential(reference: ModelConfigurationRef) {
        mutex.withLock { credentials.clear(reference) }
    }

    /** Deletes OAuth configuration and the token for one profile; called when a profile is deleted. */
    suspend fun clearProfile(profile: NativeModelProfile) {
        mutex.withLock {
            credentials.clear(profile.reference)
            configs.clear(profile.id)
            flows.clear()
            if (_state.value.profileId == profile.id) _state.value = NativeOAuthUiState()
        }
    }

    private suspend fun loadLocked(
        profileId: String,
        busy: Boolean = false,
        failure: NativeOAuthUiFailure? = null,
        notice: NativeOAuthUiNotice? = null,
    ): NativeOAuthUiState {
        val profile = try {
            profiles.find(profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            val state = NativeOAuthUiState(
                profileId = profileId, busy = busy,
                failure = failure ?: NativeOAuthUiFailure.PROFILE_MISSING, notice = notice)
            _state.value = state
            return state
        }
        var effectiveFailure = failure
        val config = try {
            configs.load(profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            effectiveFailure = effectiveFailure ?: NativeOAuthUiFailure.STORE
            null
        }
        val credential = try {
            credentials.load(profile.reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OAuthFailure) {
            effectiveFailure = effectiveFailure ?: nativeOAuthFailure(failure.kind)
            null
        } catch (_: Exception) {
            effectiveFailure = effectiveFailure ?: NativeOAuthUiFailure.STORE
            null
        }
        val pending = try {
            flows.pending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            effectiveFailure = effectiveFailure ?: NativeOAuthUiFailure.STORE
            null
        }
        val state = nativeOAuthUiState(
            profile = profile,
            config = config,
            credential = credential,
            pending = pending,
            nowMillis = clock().coerceAtLeast(0),
            busy = busy,
            failure = effectiveFailure,
            notice = notice,
        )
        _state.value = state
        return state
    }

    /** A callback for a profile that is no longer selected must not replace the open editor. */
    private suspend fun loadForCallback(
        profileId: String,
        failure: NativeOAuthUiFailure? = null,
        notice: NativeOAuthUiNotice? = null,
    ) {
        val current = _state.value.profileId
        if (current == null || current == profileId) {
            loadLocked(profileId, failure = failure, notice = notice)
        }
    }

    private fun updateIfCurrent(profileId: String, failure: NativeOAuthUiFailure) {
        if (_state.value.profileId == null || _state.value.profileId == profileId) {
            _state.value = _state.value.copy(
                profileId = profileId, busy = false, failure = failure, notice = null)
        }
    }

    /** CAS baseline from the loaded editor state, never a just-read value that hides a conflict. */
    private suspend fun expectedFingerprint(profileId: String): String? =
        if (_state.value.profileId == profileId) _state.value.fingerprint
        else configs.load(profileId)?.fingerprint

    /**
     * Removal may proceed from the visible editor baseline, or re-read a missing one after a
     * failed load. A corrupt document yields null so the caller can force-remove it; any other
     * read failure must not delete a configuration that may still be valid.
     */
    private suspend fun removalFingerprint(profileId: String): String? {
        val visible = _state.value
        if (visible.profileId == profileId && visible.fingerprint != null) return visible.fingerprint
        return try {
            configs.load(profileId)?.fingerprint
        } catch (failure: NativeOAuthConfigurationException) {
            if (failure.reason == NativeOAuthConfigurationException.Reason.CORRUPT) null
            else throw failure
        }
    }

    private suspend fun clearPendingIfProfile(profileId: String) {
        val pending = flows.pending()
        if (pending?.profileId == profileId) flows.clearPending()
    }

    private fun configurationFailure(
        failure: NativeOAuthConfigurationException,
    ): NativeOAuthUiFailure = when (failure.reason) {
        NativeOAuthConfigurationException.Reason.INVALID -> NativeOAuthUiFailure.INVALID_CONFIGURATION
        NativeOAuthConfigurationException.Reason.CONFLICT -> NativeOAuthUiFailure.CONFIG_CONFLICT
        NativeOAuthConfigurationException.Reason.CORRUPT,
        NativeOAuthConfigurationException.Reason.UNAVAILABLE -> NativeOAuthUiFailure.STORE
    }

}

private val OAUTH_SCOPE_SEPARATOR = Regex("[\\s,]+")
private val OAUTH_SCOPE_TOKEN = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,512}")
