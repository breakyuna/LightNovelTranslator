package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAuthTest {
    @Test fun credentialToStringNeverContainsAccessOrRefreshToken() {
        val credential = OAuthCredential.of("access-secret-value", "refresh-secret-value",
            1_700_000_000_000L, listOf("scope-a", "scope-b"), "Bearer")
        val text = credential.toString()
        assertFalse(text.contains("access-secret-value"))
        assertFalse(text.contains("refresh-secret-value"))
        assertTrue(text.contains("accessToken=redacted"))
        assertTrue(text.contains("refreshToken=present"))
        assertEquals(listOf("scope-a", "scope-b"), credential.scopes)
    }

    @Test fun refreshWindowIsAppliedOnlyWhenTheProviderPublishedAnExpiry() {
        val now = 1_000_000L
        val expiring = OAuthCredential.of("token", "refresh", now + 10_001L)
        assertFalse(expiring.needsRefresh(now, 10_000L))
        assertTrue(expiring.needsRefresh(now + 1L, 10_000L))
        assertFalse(expiring.isExpired(now + 10_000L))
        assertTrue(expiring.isExpired(now + 10_001L))
        val withoutExpiry = OAuthCredential.of("token", "refresh", null)
        assertFalse(withoutExpiry.needsRefresh(now, 10_000L))
        assertFalse(withoutExpiry.isExpired(now))
    }

    @Test fun emptyRefreshTokenIsNormalizedToNullAndSecretsRejectControls() {
        assertNull(OAuthCredential.of("token", "", null).refreshToken)
        for (value in listOf("", "contains space", "line\nbreak", "non-ascii-é")) {
            try {
                OAuthCredential.of(value)
                throw AssertionError("Accepted invalid token")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun failureCategoriesAreSanitized() {
        val failure = OAuthFailure(OAuthFailureKind.REFRESH_REJECTED)
        assertEquals(OAuthFailureKind.REFRESH_REJECTED, failure.kind)
        assertFalse(failure.toString().contains("token"))
        assertFalse(failure.message.orEmpty().contains("token"))
    }
}
