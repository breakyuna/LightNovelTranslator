package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelAuthMode
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class OAuthCredentialResolverTest {
    private val reference = ModelConfigurationRef("oauth-profile", 3)
    private val other = ModelConfigurationRef("api-profile", 1)
    private var keyReads = 0

    private val settings = object : NativeModelSettingsPort {
        override suspend fun current(): NativeModelSettings? = null
        override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings =
            NativeModelSettings(reference, "https://example.com/v1", "fixture")
        override suspend fun credential(reference: ModelConfigurationRef): String {
            keyReads++
            return "key-1"
        }
        override suspend fun credentials(reference: ModelConfigurationRef): List<String> {
            keyReads++
            return listOf("key-1", "key-2")
        }
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings =
            error("Not used")
    }

    private fun oauthStore(credential: OAuthCredential?): OAuthCredentialStore =
        object : OAuthCredentialStore {
            private var current = credential
            override suspend fun load(reference: ModelConfigurationRef) = current
            override suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential) {
                current = credential
            }
            override suspend fun clear(reference: ModelConfigurationRef) { current = null }
        }

    @Test fun apiKeyModeKeepsSelectionRotationAndFailureClassification() = runBlocking {
        val resolver = ApiKeyModelCredentialResolver(ModelKeyPool(settings))
        assertEquals(ModelAuthMode.API_KEY, resolver.mode(reference))
        val first = resolver.resolve(reference)
        assertEquals(ModelAuthMode.API_KEY, first.mode)
        assertEquals("key-1", first.token)
        resolver.failure(reference, first, ModelFailureKind.AUTHENTICATION)
        val second = resolver.resolve(reference)
        assertEquals("key-2", second.token)
        resolver.success(reference, second)
        assertEquals("key-2", resolver.resolve(reference).token)
        assertFalse(first.toString().contains("key-1"))
        assertEquals(1, keyReads)
    }

    @Test fun oauthModeUsesProviderAndNeverTouchesTheKeyPool() = runBlocking {
        val provider = OAuthTokenProvider(oauthStore(OAuthCredential.of("oauth-access", "refresh",
            Long.MAX_VALUE)), object : OAuthRefresher {
            override suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential) =
                error("Fresh OAuth credential must not refresh")
        })
        val resolver = OAuthCredentialResolver(provider, reference,
            fallback = ApiKeyModelCredentialResolver(ModelKeyPool(settings)))
        assertEquals(ModelAuthMode.OAUTH, resolver.mode(reference))
        val credential = resolver.resolve(reference)
        assertEquals(ModelAuthMode.OAUTH, credential.mode)
        assertEquals("oauth-access", credential.token)
        assertFalse(credential.toString().contains("oauth-access"))
        assertEquals(0, keyReads)
        resolver.success(reference, credential)
        resolver.failure(reference, credential, ModelFailureKind.AUTHENTICATION)
        assertEquals(ModelAuthMode.API_KEY, resolver.mode(other))
        assertEquals("key-1", resolver.resolve(other).token)
    }

    @Test fun oauthFailureIsAuthenticationAndDoesNotFallBackToApiKeys() = runBlocking {
        val provider = OAuthTokenProvider(oauthStore(null), object : OAuthRefresher {
            override suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential) =
                error("No credential")
        })
        val resolver = OAuthCredentialResolver(provider, reference,
            fallback = ApiKeyModelCredentialResolver(ModelKeyPool(settings)))
        try {
            resolver.resolve(reference)
            fail("Fell back from missing OAuth")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind)
        }
        assertEquals(0, keyReads)
    }

    @Test fun oauthFailureWithoutAResolvedCredentialNeverReachesTheApiKeyFallback() = runBlocking {
        val provider = OAuthTokenProvider(oauthStore(null), object : OAuthRefresher {
            override suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential) =
                error("No credential")
        })
        val fallback = object : ModelCredentialResolver {
            var failures = 0
            override suspend fun resolve(reference: ModelConfigurationRef): ModelCredential =
                ModelCredential.none()
            override suspend fun failure(reference: ModelConfigurationRef, credential: ModelCredential?,
                kind: ModelFailureKind, retryAfterMillis: Long?) {
                failures++
            }
        }
        val resolver = OAuthCredentialResolver(provider, reference, fallback)
        resolver.failure(reference, null, ModelFailureKind.AUTHENTICATION)
        assertEquals(0, fallback.failures)
        assertEquals(0, keyReads)
    }

    @Test fun unmatchedReferenceWithoutFallbackIsExplicitNoAuth() = runBlocking {
        val resolver = OAuthCredentialResolver(OAuthTokenProvider(oauthStore(null),
            object : OAuthRefresher {
                override suspend fun refresh(reference: ModelConfigurationRef, credential: OAuthCredential) =
                    error("Must not refresh")
            }), reference)
        assertEquals(ModelAuthMode.NONE, resolver.mode(other))
        assertEquals(ModelAuthMode.NONE, resolver.resolve(other).mode)
        assertEquals("", resolver.resolve(other).token)
    }
}
