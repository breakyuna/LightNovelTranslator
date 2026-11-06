package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exchanges a refresh token for new OAuth material. Implementations own their own
 * HTTP client/provider policy, never receive API keys and never log token values.
 * They are not model requests and must not enter [ModelRequestScheduler].
 */
interface OAuthRefresher {
    suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential): OAuthCredential
}

/**
 * Resolves an access token immediately before one model request and refreshes it
 * only when the provider-published expiry is inside the safety window.
 *
 * Refreshes are single-flight per configuration revision: the first caller holds
 * the revision mutex while refreshing, later callers re-read the store and reuse
 * the persisted result, so at most one refresh is dispatched. Mutex acquisition,
 * loading and refreshing are cancellation-cooperative and cancellation is never
 * converted to an authentication failure.
 *
 * A refresh failure of any kind becomes [ModelFailureKind.AUTHENTICATION] for the
 * current request. Tokens are persisted only in [OAuthCredentialStore]; this class
 * does not write usage, diagnostics or backups, and never exposes a token to
 * [ModelKeyPool].
 */
class OAuthTokenProvider(
    private val store: OAuthCredentialStore,
    private val refresher: OAuthRefresher,
    private val refreshWindowMillis: Long = OAuthCredential.DEFAULT_REFRESH_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lockRegistry = Mutex()
    private val refreshLocks = HashMap<ModelConfigurationRef, Mutex>()

    init {
        require(refreshWindowMillis >= 0) { "Invalid OAuth refresh window" }
    }

    /** Returns a usable credential, refreshing it first only when necessary. */
    suspend fun credential(reference: ModelConfigurationRef): OAuthCredential {
        currentCoroutineContext().ensureActive()
        val snapshot = load(reference) ?: throw authenticationFailure(OAuthFailureKind.MISSING_CREDENTIAL)
        val initialNow = now()
        if (!snapshot.needsRefresh(initialNow, refreshWindowMillis)) return snapshot
        if (snapshot.refreshToken.isNullOrEmpty()) {
            // A token inside the safety window is still valid. Without a refresh token
            // it must remain usable until its actual expiry; only then is it a failure.
            if (!snapshot.isExpired(initialNow)) return snapshot
            throw authenticationFailure(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH)
        }
        return mutexFor(reference).withLock {
            currentCoroutineContext().ensureActive()
            val current = load(reference) ?: throw authenticationFailure(OAuthFailureKind.MISSING_CREDENTIAL)
            val lockedNow = now()
            if (!current.needsRefresh(lockedNow, refreshWindowMillis)) return@withLock current
            if (current.refreshToken.isNullOrEmpty()) {
                if (!current.isExpired(lockedNow)) return@withLock current
                throw authenticationFailure(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH)
            }
            val refreshed = try {
                refresher.refresh(reference, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OAuthFailure) {
                throw authenticationFailure(failure.kind)
            } catch (_: Exception) {
                throw authenticationFailure(OAuthFailureKind.UNKNOWN)
            }
            // Providers commonly omit expires_in on refresh responses. Never turn
            // that into an immediately-expired credential or a refresh loop.
            val completedNow = now()
            val expiresAtMillis = refreshed.expiresAtMillis
            if (expiresAtMillis != null && expiresAtMillis <= completedNow) {
                throw authenticationFailure(OAuthFailureKind.MALFORMED_RESPONSE)
            }
            val next = if (refreshed.refreshToken == null) OAuthCredential.of(
                refreshed.accessToken, current.refreshToken, expiresAtMillis,
                refreshed.scopes, refreshed.tokenType) else refreshed
            try {
                store.save(reference, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OAuthFailure) {
                throw authenticationFailure(failure.kind)
            } catch (_: Exception) {
                throw authenticationFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
            next
        }
    }

    /** Convenience for the transport boundary; do not log or persist the result. */
    suspend fun accessToken(reference: ModelConfigurationRef): String = credential(reference).accessToken

    private suspend fun load(reference: ModelConfigurationRef): OAuthCredential? = try {
        store.load(reference)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OAuthFailure) {
        throw authenticationFailure(failure.kind)
    } catch (_: Exception) {
        throw authenticationFailure(OAuthFailureKind.STORE_UNAVAILABLE)
    }

    private suspend fun mutexFor(reference: ModelConfigurationRef): Mutex =
        lockRegistry.withLock { refreshLocks.getOrPut(reference) { Mutex() } }

    private fun now(): Long = clock().coerceAtLeast(0)

    /** The cause keeps the sanitized classification without exposing provider details. */
    private fun authenticationFailure(kind: OAuthFailureKind): AgentModelFailure =
        AgentModelFailure(ModelFailureKind.AUTHENTICATION).also { it.initCause(OAuthFailure(kind)) }
}
