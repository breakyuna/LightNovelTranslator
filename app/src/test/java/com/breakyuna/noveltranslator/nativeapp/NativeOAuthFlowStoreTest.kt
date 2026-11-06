package com.breakyuna.noveltranslator.nativeapp

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeOAuthFlowStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var clock = 1_000L
    private val fingerprint = "a".repeat(64)
    private val state = "s".repeat(32)
    private val verifier = "v".repeat(43)

    private fun store() = NativeOAuthFlowStore(temporary.root, clock = { clock })

    private fun pending(createdAt: Long = 1_000L) = NativeOAuthPendingFlow(
        profileId = "profile-1",
        revision = 2L,
        configFingerprint = fingerprint,
        state = state,
        codeVerifier = verifier,
        createdAtMillis = createdAt,
    )

    @Test fun pendingFlowIsOneTimeAndMismatchedStateDoesNotConsumeIt() = runBlocking {
        val store = store()
        store.savePending(pending())
        assertEquals(state, store.pending()?.state)
        assertNull(store.consumePending("x".repeat(32)))
        assertNotNull(store.pending())
        assertEquals(state, store.consumePending(state)?.state)
        assertNull(store.consumePending(state))
        assertNull(store.pending())
    }

    @Test fun pendingFlowExpiresAfterTenMinutesAndIsDeleted() = runBlocking {
        val store = store()
        store.savePending(pending(createdAt = 1_000L))
        clock = 1_000L + 10 * 60_000L - 1L
        assertNotNull(store.pending())
        clock += 1L
        assertNull(store.pending())
        assertNull(store.pending())
    }

    @Test fun expiredOrFuturePendingFlowIsRejected() = runBlocking {
        val store = store()
        clock = 500L
        store.savePending(pending(createdAt = 1_000L))
        assertNull(store.pending())
        clock = 1_000L
        store.savePending(pending(createdAt = 1_000L))
        assertNotNull(store.consumePending(state))
    }

    @Test fun callbackResultIsConsumedExactlyOnce() = runBlocking {
        val store = store()
        val first = NativeOAuthCallbackResult.Success(
            "profile-1", 2L, fingerprint, 1_000L, "code-1", verifier)
        assertFalse(first.toString().contains("code-1"))
        store.saveCallback(first)
        val consumed = store.consumeCallback()
        assertTrue(consumed is NativeOAuthCallbackResult.Success)
        assertEquals("code-1", (consumed as NativeOAuthCallbackResult.Success).code)
        assertNull(store.consumeCallback())
    }

    @Test fun newPendingFlowDiscardsAStaleCallback() = runBlocking {
        val store = store()
        store.saveCallback(NativeOAuthCallbackResult.Failure(
            "profile-1", 2L, fingerprint, 1_000L, NativeOAuthCallbackError.ACCESS_DENIED))
        store.savePending(pending())
        assertNull(store.consumeCallback())
        assertNotNull(store.pending())
    }

    @Test fun expiredCallbackResultIsDeleted() = runBlocking {
        val store = store()
        store.saveCallback(NativeOAuthCallbackResult.Failure(
            "profile-1", 2L, fingerprint, 1_000L, NativeOAuthCallbackError.SERVER_ERROR))
        clock = 1_000L + 5 * 60_000L
        assertNull(store.consumeCallback())
        assertNull(store.consumeCallback())
    }

    @Test fun callbackAuthorityRejectsForeignPortsUserInfoFragmentsAndPaths() {
        assertTrue(isNativeOAuthCallback(Uri.parse("lnt-oauth://callback?state=$state&code=code-1")))
        assertTrue(isNativeOAuthCallback(Uri.parse("lnt-oauth://callback/?state=$state")))
        assertFalse(isNativeOAuthCallback(Uri.parse("https://callback?state=$state")))
        assertFalse(isNativeOAuthCallback(Uri.parse("lnt-oauth://callback:1234?state=$state")))
        assertFalse(isNativeOAuthCallback(Uri.parse("lnt-oauth://user@callback?state=$state")))
        assertFalse(isNativeOAuthCallback(Uri.parse("lnt-oauth://callback#fragment")))
        assertFalse(isNativeOAuthCallback(Uri.parse("lnt-oauth://callback/other?state=$state")))
        assertFalse(isNativeOAuthCallback(Uri.parse("lnt-oauth:callback?state=$state")))
        assertFalse(isNativeOAuthCallback(null))
    }

    @Test fun callbackParserBoundsValuesAndClassifiesProviderErrors() {
        val parameters = nativeOAuthCallbackParameters { name ->
            mapOf(
                "state" to state,
                "code" to "code-1",
                "error" to "access_denied",
                "error_description" to "must not be retained",
            )[name]
        }
        assertEquals(state, parameters.state)
        assertEquals("code-1", parameters.code)
        assertEquals("access_denied", parameters.error)
        assertEquals(NativeOAuthCallbackError.ACCESS_DENIED, nativeOAuthCallbackError(parameters.error))
        assertEquals(NativeOAuthCallbackError.INVALID_REQUEST,
            nativeOAuthCallbackError("invalid_scope"))
        assertEquals(NativeOAuthCallbackError.SERVER_ERROR,
            nativeOAuthCallbackError("server_error"))
        assertEquals(NativeOAuthCallbackError.TEMPORARILY_UNAVAILABLE,
            nativeOAuthCallbackError("temporarily_unavailable"))
        assertEquals(NativeOAuthCallbackError.UNKNOWN, nativeOAuthCallbackError("other"))
        val invalid = nativeOAuthCallbackParameters { name ->
            if (name == "state") "bad state" else null
        }
        assertNull(invalid.state)
    }
}
