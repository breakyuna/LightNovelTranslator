package com.breakyuna.noveltranslator.nativeapp

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeOAuthConfigStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private suspend fun expectConfigFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: NativeOAuthConfigurationException) {
            return
        }
        throw AssertionError("Expected NativeOAuthConfigurationException")
    }

    private fun config(
        profileId: String = "profile-1",
        authorizationEndpoint: String = "https://auth.example.test/authorize",
        tokenEndpoint: String = "https://auth.example.test/token",
        clientId: String = "client-1",
        allowLoopbackHttp: Boolean = false,
    ) = NativeOAuthConfig.of(
        profileId = profileId,
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        clientId = clientId,
        scopes = listOf("read", "write"),
        allowLoopbackHttp = allowLoopbackHttp,
    )

    @Test fun saveLoadAndFingerprintCasRejectStaleWriters() = runBlocking {
        val store = NativeOAuthConfigStore(temporary.root, allowLoopbackHttp = false)
        assertNull(store.load("profile-1"))
        val first = store.save(config(), expectedFingerprint = null)
        assertEquals("client-1", store.load("profile-1")?.config?.clientId)
        assertEquals(first.fingerprint, store.load("profile-1")?.fingerprint)
        expectConfigFailure {
            store.save(config(clientId = "client-duplicate"), expectedFingerprint = null)
        }
        val second = store.save(config(clientId = "client-2"), first.fingerprint)
        assertEquals("client-2", store.load("profile-1")?.config?.clientId)
        expectConfigFailure {
            store.save(config(clientId = "client-stale"), first.fingerprint)
        }
        store.clear("profile-1", second.fingerprint)
        assertNull(store.load("profile-1"))
    }

    @Test fun insecureAndNonHttpsEndpointsAreRejectedByDefault() {
        assertThrows(NativeOAuthConfigurationException::class.java) {
            config(authorizationEndpoint = "http://example.test/authorize")
        }
        assertThrows(NativeOAuthConfigurationException::class.java) {
            config(tokenEndpoint = "http://127.0.0.1:8080/token")
        }
    }

    @Test fun loopbackHttpRequiresTheStoreToOptIn() = runBlocking {
        val loopback = config(
            authorizationEndpoint = "http://127.0.0.1:8080/authorize",
            tokenEndpoint = "http://localhost:8080/token",
            allowLoopbackHttp = true,
        )
        expectConfigFailure {
            NativeOAuthConfigStore(temporary.root, allowLoopbackHttp = false)
                .save(loopback, expectedFingerprint = null)
        }
        val store = NativeOAuthConfigStore(temporary.newFolder("debug"), allowLoopbackHttp = true)
        store.save(loopback, expectedFingerprint = null)
        assertEquals("http://localhost:8080/token", store.load("profile-1")?.config?.tokenEndpoint)
    }

    @Test fun corruptedConfigurationCanBeForceClearedOnlyWithoutACasBaseline() = runBlocking {
        val store = NativeOAuthConfigStore(temporary.root, allowLoopbackHttp = false)
        store.save(config(), expectedFingerprint = null)
        val file = temporary.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.writeText("{not-json")
        expectConfigFailure { store.clear("profile-1", "a".repeat(64)) }
        store.clear("profile-1", expectedFingerprint = null)
        assertNull(store.load("profile-1"))
    }

    @Test fun persistedConfigurationContainsNoSecretOrTokenField() = runBlocking {
        val store = NativeOAuthConfigStore(temporary.root, allowLoopbackHttp = false)
        store.save(config(), expectedFingerprint = null)
        val text = temporary.root.walkTopDown().filter(File::isFile).joinToString("\n") { it.readText() }
        assertTrue(text.contains("client-1"))
        listOf("client_secret", "clientSecret", "access_token", "refresh_token", "password")
            .forEach { forbidden -> assertFalse(text.contains(forbidden)) }
    }
}
