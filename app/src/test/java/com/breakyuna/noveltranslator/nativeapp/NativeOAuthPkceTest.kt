package com.breakyuna.noveltranslator.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeOAuthPkceTest {
    @Test fun rfc7636ChallengeVectorMatches() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            NativeOAuthPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun generatedVerifierAndStateUseUrlSafeUnpaddedBase64() {
        repeat(8) {
            val verifier = NativeOAuthPkce.verifier()
            assertTrue(verifier.length in 43..128)
            assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
            val state = NativeOAuthPkce.state()
            assertTrue(state.length in 16..256)
            assertTrue(state.all { it.isLetterOrDigit() || it in "-._~" })
            assertFalse(verifier.contains('='))
            assertFalse(state.contains('='))
        }
    }

    @Test fun authorizationUrlEncodesEveryParameterAndKeepsExistingQuery() {
        val config = NativeOAuthConfig.of(
            profileId = "profile-1",
            authorizationEndpoint = "https://auth.example.test/authorize?prompt=login",
            tokenEndpoint = "https://auth.example.test/token",
            clientId = "client+id",
            scopes = listOf("read", "write"),
        )
        val url = nativeOAuthAuthorizationUrl(
            config, "s".repeat(43), "c".repeat(43))
        assertTrue(url.startsWith("https://auth.example.test/authorize?prompt=login&"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client%2Bid"))
        assertTrue(url.contains("redirect_uri=lnt-oauth%3A%2F%2Fcallback"))
        assertTrue(url.contains("scope=read+write"))
        assertTrue(url.contains("state=${"s".repeat(43)}"))
        assertTrue(url.contains("code_challenge=${"c".repeat(43)}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalse(url.contains("client_secret"))
    }

    @Test fun formEncodingUsesUtf8AndUrlEncoderRules() {
        assertEquals("a+b", nativeOAuthFormEncode("a b"))
        assertEquals("a%2Bb", nativeOAuthFormEncode("a+b"))
        assertEquals("%E4%BD%A0%E5%A5%BD", nativeOAuthFormEncode("你好"))
        assertEquals(
            "grant_type=authorization_code&code=a%26b",
            nativeOAuthForm(listOf("grant_type" to "authorization_code", "code" to "a&b")),
        )
    }

    @Test fun scopesAreSplitNormalizedAndBounded() {
        assertEquals(listOf("read", "write"), nativeOAuthScopes("read, write\nread"))
        assertEquals(emptyList<String>(), nativeOAuthScopes("  \n "))
        assertTrue(runCatching {
            nativeOAuthScopes((1..33).joinToString(" ") { "s$it" })
        }.isFailure)
        assertTrue(runCatching { nativeOAuthScopes("bad\"scope") }.isFailure)
    }
}
