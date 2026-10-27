package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OAuthTokenProviderTest {
    private val reference = ModelConfigurationRef("profile", 7)
    private val now = 1_700_000_000_000L

    private class Store(var current: OAuthCredential?) : OAuthCredentialStore {
        var saves = 0
        override suspend fun load(reference: ModelConfigurationRef): OAuthCredential? = current
        override suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential) {
            saves++
            current = credential
        }
        override suspend fun clear(reference: ModelConfigurationRef) { current = null }
    }

    private class Refresher(private val block: suspend (OAuthCredential) -> OAuthCredential) : OAuthRefresher {
        var calls = 0
        override suspend fun refresh(reference: ModelConfigurationRef,
            credential: OAuthCredential): OAuthCredential {
            calls++
            return block(credential)
        }
    }

    @Test fun freshCredentialIsUsedWithoutRefresh() = runBlocking {
        val store = Store(OAuthCredential.of("fresh-access", "refresh", now + 600_000L))
        val refresher = Refresher { error("Must not refresh") }
        val provider = OAuthTokenProvider(store, refresher, clock = { now })
        assertEquals("fresh-access", provider.accessToken(reference))
        assertEquals(0, refresher.calls)
        assertEquals(0, store.saves)
    }

    @Test fun validCredentialInsideTheWindowWithoutRefreshTokenIsStillUsable() = runBlocking {
        val store = Store(OAuthCredential.of("short-access", null, now + 60_000L))
        val refresher = Refresher { error("Must not refresh without a refresh token") }
        val provider = OAuthTokenProvider(store, refresher, clock = { now })
        assertEquals("short-access", provider.credential(reference).accessToken)
        assertEquals(0, refresher.calls)
        assertEquals(0, store.saves)
    }

    @Test fun credentialInsideSafetyWindowRefreshesExactlyOnceForConcurrentCallers() = runBlocking {
        val store = Store(OAuthCredential.of("old-access", "old-refresh", now + 1_000L))
        val refresher = Refresher {
            delay(25)
            OAuthCredential.of("new-access", "new-refresh", now + 3_600_000L)
        }
        val provider = OAuthTokenProvider(store, refresher, refreshWindowMillis = 300_000L, clock = { now })
        val results = coroutineScope {
            listOf(async { provider.credential(reference) }, async { provider.credential(reference) }).awaitAll()
        }
        assertEquals(listOf("new-access", "new-access"), results.map { it.accessToken })
        assertEquals(1, refresher.calls)
        assertEquals(1, store.saves)
        assertEquals("new-refresh", store.current?.refreshToken)
        assertFalse(results.first().toString().contains("new-access"))
    }

    @Test fun omittedExpiryOnRefreshDoesNotLoopAndOldRefreshTokenIsPreserved() = runBlocking {
        val store = Store(OAuthCredential.of("old-access", "old-refresh", now - 1L))
        val refresher = Refresher { OAuthCredential.of("new-access", null, null) }
        val provider = OAuthTokenProvider(store, refresher, clock = { now })
        assertEquals("old-refresh", provider.credential(reference).refreshToken)
        assertEquals(1, refresher.calls)
    }

    @Test fun missingExpiredAndRejectedCredentialsAreAuthenticationFailures() = runBlocking {
        val missing = Store(null)
        val missingFailure = failure { OAuthTokenProvider(missing,
            Refresher { error("Must not refresh") }, clock = { now }).credential(reference) }
        assertEquals(ModelFailureKind.AUTHENTICATION, missingFailure.kind)
        assertEquals(OAuthFailureKind.MISSING_CREDENTIAL, (missingFailure.cause as OAuthFailure).kind)

        val expired = Store(OAuthCredential.of("access", null, now - 1L))
        val expiredFailure = failure { OAuthTokenProvider(expired,
            Refresher { error("Must not refresh") }, clock = { now }).credential(reference) }
        assertEquals(ModelFailureKind.AUTHENTICATION, expiredFailure.kind)
        assertEquals(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH, (expiredFailure.cause as OAuthFailure).kind)

        val rejected = Store(OAuthCredential.of("access", "refresh", now - 1L))
        val calls = Refresher { throw OAuthFailure(OAuthFailureKind.REFRESH_REJECTED) }
        val rejectedFailure = failure {
            OAuthTokenProvider(rejected, calls, clock = { now }).credential(reference)
        }
        assertEquals(ModelFailureKind.AUTHENTICATION, rejectedFailure.kind)
        assertEquals(OAuthFailureKind.REFRESH_REJECTED, (rejectedFailure.cause as OAuthFailure).kind)
        assertEquals(1, calls.calls)
        assertEquals(0, rejected.saves)
        assertFalse(rejectedFailure.toString().contains("refresh"))
    }

    @Test fun malformedRefreshResponseAndStoreFailureAreAuthenticationFailures() = runBlocking {
        val malformed = Store(OAuthCredential.of("access", "refresh", now - 1L))
        val malformedFailure = failure {
            OAuthTokenProvider(malformed, Refresher {
                OAuthCredential.of("access", "refresh", now - 10L)
            }, clock = { now }).credential(reference)
        }
        assertEquals(OAuthFailureKind.MALFORMED_RESPONSE,
            (malformedFailure.cause as OAuthFailure).kind)

        val corrupt = object : OAuthCredentialStore {
            override suspend fun load(reference: ModelConfigurationRef): OAuthCredential? =
                throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
            override suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential) = Unit
            override suspend fun clear(reference: ModelConfigurationRef) = Unit
        }
        val corruptFailure = failure {
            OAuthTokenProvider(corrupt, Refresher { error("Must not refresh") }, clock = { now })
                .credential(reference)
        }
        assertEquals(OAuthFailureKind.STORE_CORRUPT, (corruptFailure.cause as OAuthFailure).kind)
        assertNull(corruptFailure.cause?.cause)
    }

    @Test fun cancellationDuringRefreshPropagatesWithoutPersisting() = runBlocking {
        val store = Store(OAuthCredential.of("access", "refresh", now - 1L))
        val started = CompletableDeferred<Unit>()
        val appending = object : OAuthRefresher {
            override suspend fun refresh(reference: ModelConfigurationRef,
                credential: OAuthCredential): OAuthCredential {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val provider = OAuthTokenProvider(store, appending, clock = { now })
        val job = launch { provider.credential(reference) }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, store.saves)
    }

    private suspend fun failure(block: suspend () -> Unit): AgentModelFailure {
        try {
            block()
            fail("Expected an authentication failure")
            throw IllegalStateException("unreachable")
        } catch (failure: AgentModelFailure) {
            return failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
}
