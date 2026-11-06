package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import com.breakyuna.noveltranslator.nativecore.engine.OAuthHttpExecutor
import com.breakyuna.noveltranslator.nativecore.engine.OAuthHttpResult
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOAuthTokenClientTest {
    private val config = NativeOAuthConfig.of(
        profileId = "profile-1",
        authorizationEndpoint = "https://auth.example.test/authorize",
        tokenEndpoint = "https://auth.example.test/token",
        clientId = "client-id+1",
        scopes = listOf("read", "write"),
    )

    private class FakeExecutor(
        private val response: OAuthHttpResult? = null,
        private val failure: Exception? = null,
    ) : OAuthHttpExecutor {
        var url: String? = null
        var form: String? = null
        override suspend fun postForm(url: String, form: String): OAuthHttpResult {
            this.url = url
            this.form = form
            failure?.let { throw it }
            return requireNotNull(response)
        }
    }

    private suspend fun requestFailure(block: suspend () -> Unit): OAuthFailure {
        try {
            block()
        } catch (failure: OAuthFailure) {
            return failure
        }
        throw AssertionError("Expected OAuthFailure")
    }

    private fun parseFailure(block: () -> Unit): OAuthFailure {
        try {
            block()
        } catch (failure: OAuthFailure) {
            return failure
        }
        throw AssertionError("Expected OAuthFailure")
    }

    @Test fun authorizationCodeExchangeUsesFormPkceAndNoClientSecret() = runBlocking {
        val executor = FakeExecutor(OAuthHttpResult(200, """
            {"access_token":"access-1","refresh_token":"refresh-1",
             "expires_in":3600,"scope":"read write","token_type":"Bearer"}
        """.trimIndent()))
        val client = NativeOAuthTokenClient(executor, clock = { 1_000L })
        val credential = client.exchange(config, "code 1", "verifier", config.scopes)
        assertEquals("access-1", credential.accessToken)
        assertEquals("refresh-1", credential.refreshToken)
        assertEquals(3_601_000L, credential.expiresAtMillis ?: -1L)
        assertEquals(listOf("read", "write"), credential.scopes)
        assertEquals("https://auth.example.test/token", executor.url)
        val form = requireNotNull(executor.form)
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=code+1"))
        assertTrue(form.contains("redirect_uri=lnt-oauth%3A%2F%2Fcallback"))
        assertTrue(form.contains("client_id=client-id%2B1"))
        assertTrue(form.contains("code_verifier=verifier"))
        assertFalse(form.contains("client_secret"))
        assertFalse(credential.toString().contains("access-1"))
        assertFalse(credential.toString().contains("refresh-1"))
    }

    @Test fun omittedScopeAndExpiryKeepFallbackScopesAndNullExpiry() {
        val credential = parseNativeOAuthTokenResponse(
            """{"access_token":"access-1","token_type":"bearer"}""",
            nowMillis = 5_000L,
            fallbackScopes = listOf("saved"),
        )
        assertNull(credential.expiresAtMillis)
        assertEquals(listOf("saved"), credential.scopes)
        assertEquals("bearer", credential.tokenType)
    }

    @Test fun providerScopeArrayIsAcceptedAndNumericExpiryIsBounded() {
        val credential = parseNativeOAuthTokenResponse(
            """{"access_token":"access-1","expires_in":"60","scope":["a","b"],"token_type":"Bearer"}""",
            nowMillis = 5_000L,
            fallbackScopes = emptyList(),
        )
        assertEquals(65_000L, credential.expiresAtMillis ?: -1L)
        assertEquals(listOf("a", "b"), credential.scopes)
        assertEquals(OAuthFailureKind.MALFORMED_RESPONSE, parseFailure {
            parseNativeOAuthTokenResponse(
                """{"access_token":"access-1","expires_in":99999999999}""",
                0L, emptyList())
        }.kind)
    }

    @Test fun httpErrorsMapToSanitizedOAuthFailureKinds() = runBlocking {
        assertEquals(OAuthFailureKind.REFRESH_REJECTED,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(400, "")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.REFRESH_REJECTED,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(401, "")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.TIMEOUT,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(408, "")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.REFRESH_UNAVAILABLE,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(503, "")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.REFRESH_UNAVAILABLE,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(429, "")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
    }

    @Test fun ioTimeoutAndMalformedResponsesAreClassifiedWithoutBodyMaterial() = runBlocking {
        assertEquals(OAuthFailureKind.TIMEOUT,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(failure = SocketTimeoutException("timeout")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.NETWORK,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(failure = java.io.IOException("offline")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.MALFORMED_RESPONSE,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(200, "not json")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        assertEquals(OAuthFailureKind.MALFORMED_RESPONSE,
            requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(200,
                """{"access_token":"${"a".repeat(70 * 1024)}"}""")))
                .exchange(config, "code", "verifier", config.scopes) }.kind)
        val rejected = requestFailure { NativeOAuthTokenClient(FakeExecutor(OAuthHttpResult(200,
            """{"error":"invalid_grant"}""")))
            .exchange(config, "code", "verifier", config.scopes) }
        assertEquals(OAuthFailureKind.REFRESH_REJECTED, rejected.kind)
        assertFalse(rejected.toString().contains("invalid_grant"))
    }

    @Test fun refreshOmitsTheAuthorizationCodeAndRequiresARefreshToken() = runBlocking {
        val executor = FakeExecutor(OAuthHttpResult(200,
            """{"access_token":"access-2","expires_in":60}"""))
        val client = NativeOAuthTokenClient(executor, clock = { 0L })
        val refreshed = client.refresh(config,
            com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential.of(
                "access-1", "refresh-1", null, listOf("read")))
        val form = requireNotNull(executor.form)
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertFalse(form.contains("authorization_code"))
        assertFalse(form.contains("code_verifier"))
        assertEquals(listOf("read"), refreshed.scopes)
        val missing = requestFailure {
            client.refresh(config,
                com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential.of("access-1"))
        }
        assertEquals(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH, missing.kind)
    }

    @Test fun httpStatusClassificationIsPureAndCoversRedirects() {
        assertEquals(OAuthFailureKind.TIMEOUT, nativeOAuthHttpFailureKind(408))
        assertEquals(OAuthFailureKind.TIMEOUT, nativeOAuthHttpFailureKind(504))
        assertEquals(OAuthFailureKind.REFRESH_REJECTED, nativeOAuthHttpFailureKind(403))
        assertEquals(OAuthFailureKind.REFRESH_UNAVAILABLE, nativeOAuthHttpFailureKind(500))
        assertEquals(OAuthFailureKind.MALFORMED_RESPONSE, nativeOAuthHttpFailureKind(302))
    }
}
