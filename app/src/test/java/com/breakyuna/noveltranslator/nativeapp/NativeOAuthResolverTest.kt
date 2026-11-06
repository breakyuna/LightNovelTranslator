package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelAuthMode
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfilePurpose
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import com.breakyuna.noveltranslator.nativecore.engine.ModelCredential
import com.breakyuna.noveltranslator.nativecore.engine.ModelCredentialResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOAuthResolverTest {
    private val reference = ModelConfigurationRef("profile-1", 2L)

    private class Delegate(
        private val credential: ModelCredential,
        private val defaultMode: ModelAuthMode = ModelAuthMode.API_KEY,
    ) : ModelCredentialResolver {
        var modes = 0
        var resolves = 0
        var successes = 0
        var failures = 0
        override suspend fun mode(reference: ModelConfigurationRef): ModelAuthMode {
            modes++
            return defaultMode
        }
        override suspend fun resolve(reference: ModelConfigurationRef): ModelCredential {
            resolves++
            return credential
        }
        override suspend fun success(reference: ModelConfigurationRef, credential: ModelCredential?) {
            successes++
        }
        override suspend fun failure(
            reference: ModelConfigurationRef,
            credential: ModelCredential?,
            kind: ModelFailureKind,
            retryAfterMillis: Long?,
        ) {
            failures++
        }
    }

    @Test fun oauthCredentialPresenceSelectsOAuthAndNeverTouchesTheKeyFallback() = runBlocking {
        val oauth = Delegate(ModelCredential.oauth(OAuthCredential.of("oauth-access")),
            ModelAuthMode.OAUTH)
        val fallback = Delegate(ModelCredential.none())
        var present = true
        val resolver = NativeOAuthFallbackCredentialResolver(oauth, fallback) { present }
        assertEquals(ModelAuthMode.OAUTH, resolver.mode(reference))
        val credential = resolver.resolve(reference)
        assertEquals(ModelAuthMode.OAUTH, credential.mode)
        assertEquals("oauth-access", credential.token)
        assertEquals(0, fallback.resolves)
        resolver.success(reference, credential)
        resolver.failure(reference, credential, ModelFailureKind.AUTHENTICATION)
        assertEquals(0, fallback.successes)
        assertEquals(0, fallback.failures)
        assertEquals(0, oauth.successes)
        assertEquals(0, oauth.failures)
    }

    @Test fun absentOAuthCredentialUsesTheApiKeyFallback() = runBlocking {
        val oauth = Delegate(ModelCredential.none(), ModelAuthMode.OAUTH)
        val fallback = Delegate(ModelCredential.none())
        var present = false
        val resolver = NativeOAuthFallbackCredentialResolver(oauth, fallback) { present }
        assertEquals(ModelAuthMode.API_KEY, resolver.mode(reference))
        assertEquals(ModelAuthMode.NONE, resolver.resolve(reference).mode)
        assertEquals(1, fallback.modes)
        assertEquals(1, fallback.resolves)
        resolver.success(reference, null)
        resolver.failure(reference, null, ModelFailureKind.NETWORK)
        assertEquals(1, fallback.successes)
        assertEquals(1, fallback.failures)
        present = true
        resolver.failure(reference, null, ModelFailureKind.AUTHENTICATION)
        assertEquals(1, fallback.failures)
    }

    @Test fun corruptOAuthStoreFailsAsAuthenticationInsteadOfFallingBack() = runBlocking {
        val oauth = Delegate(ModelCredential.none(), ModelAuthMode.OAUTH)
        val fallback = Delegate(ModelCredential.none())
        val resolver = NativeOAuthFallbackCredentialResolver(oauth, fallback) {
            throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
        }
        val failure = try {
            resolver.resolve(reference)
            error("Expected authentication failure")
        } catch (failure: AgentModelFailure) {
            failure
        }
        assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind)
        assertEquals(OAuthFailureKind.STORE_CORRUPT,
            (failure.cause as OAuthFailure).kind)
        assertEquals(0, fallback.resolves)
    }

    @Test fun uiStateNeverCarriesTokenMaterialAndReportsExpiryStatus() {
        val config = NativeOAuthConfigSnapshot(
            NativeOAuthConfig.of(
                "profile-1", "https://auth.example.test/authorize",
                "https://auth.example.test/token", "client-1", listOf("read")),
            "a".repeat(64),
        )
        val profile = profile()
        val connected = nativeOAuthUiState(profile, config,
            OAuthCredential.of("access-secret", "refresh-secret", 1_000_000L),
            pending = null, nowMillis = 1_000L)
        assertEquals(NativeOAuthStatus.CONNECTED, connected.status)
        assertTrue(connected.refreshable)
        assertTrue(connected.hasToken)
        assertFalse(connected.toString().contains("access-secret"))
        assertEquals(NativeOAuthStatus.EXPIRING_SOON, nativeOAuthUiState(profile, config,
            OAuthCredential.of("access", "refresh", 301_000L), null, 1_000L).status)
        assertEquals(NativeOAuthStatus.EXPIRED, nativeOAuthUiState(profile, config,
            OAuthCredential.of("access", null, 999L), null, 1_000L).status)
        assertEquals(NativeOAuthStatus.SIGNED_OUT, nativeOAuthUiState(profile, config,
            null, null, 1_000L).status)
        assertEquals(NativeOAuthStatus.NOT_CONFIGURED, nativeOAuthUiState(profile, null,
            null, null, 1_000L).status)
    }

    @Test fun pendingLoginIsScopedToTheProfileAndFailureMappingIsSanitized() {
        val config = NativeOAuthConfigSnapshot(
            NativeOAuthConfig.of(
                "profile-1", "https://auth.example.test/authorize",
                "https://auth.example.test/token", "client-1", listOf("read")),
            "a".repeat(64),
        )
        val pending = NativeOAuthPendingFlow(
            "profile-1", 2L, "a".repeat(64), "s".repeat(32), "v".repeat(43), 1_000L)
        assertTrue(nativeOAuthUiState(profile(), config, null, pending, 1_000L).pendingLogin)
        val other = NativeOAuthPendingFlow(
            "profile-2", 2L, "a".repeat(64), "s".repeat(32), "v".repeat(43), 1_000L)
        assertFalse(nativeOAuthUiState(profile(), config, null, other, 1_000L).pendingLogin)
        assertEquals(NativeOAuthUiFailure.TIMEOUT,
            nativeOAuthFailure(OAuthFailureKind.TIMEOUT))
        assertEquals(NativeOAuthUiFailure.ACCESS_DENIED,
            nativeOAuthFailure(NativeOAuthCallbackError.ACCESS_DENIED))
        assertEquals(NativeOAuthUiFailure.CONFIG_CONFLICT,
            NativeOAuthUiFailure.valueOf("CONFIG_CONFLICT"))
    }

    private fun profile(): NativeModelProfile {
        val settings = NativeModelSettings(
            ModelConfigurationRef("profile-1", 2L),
            "https://model.example.test/v1",
            "model-1",
        )
        return NativeModelProfile(
            id = "profile-1",
            name = "Profile",
            kind = ModelProfileKind.CUSTOM,
            purpose = ModelProfilePurpose.AGENT,
            group = "",
            order = 0,
            enabled = true,
            settings = settings,
        )
    }
}
