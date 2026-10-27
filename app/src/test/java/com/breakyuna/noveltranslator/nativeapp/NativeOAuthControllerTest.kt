package com.breakyuna.noveltranslator.nativeapp

import android.net.Uri
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfilePurpose
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfilePort
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.engine.OAuthHttpExecutor
import com.breakyuna.noveltranslator.nativecore.engine.OAuthHttpResult
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
class NativeOAuthControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private var now = 1_000L
    private val reference = ModelConfigurationRef("profile-1", 2L)

    private class Credentials : OAuthCredentialStore {
        var current: OAuthCredential? = null
        var saves = 0
        var clears = 0
        override suspend fun load(reference: ModelConfigurationRef): OAuthCredential? = current
        override suspend fun save(reference: ModelConfigurationRef, credential: OAuthCredential) {
            saves++
            current = credential
        }
        override suspend fun clear(reference: ModelConfigurationRef) {
            clears++
            current = null
        }
    }

    private class Profiles(private val profile: NativeModelProfile) : NativeModelProfilePort {
        override suspend fun list(): List<NativeModelProfile> = listOf(profile)
        override suspend fun find(id: String): NativeModelProfile? =
            profile.takeIf { it.id == id }
    }

    private fun profile(): NativeModelProfile = NativeModelProfile(
        id = "profile-1",
        name = "Profile",
        kind = ModelProfileKind.CUSTOM,
        purpose = ModelProfilePurpose.AGENT,
        group = "",
        order = 0,
        enabled = true,
        settings = NativeModelSettings(
            reference, "https://model.example.test/v1", "model-1"),
    )

    private fun fixture(
        responses: MutableList<String> = mutableListOf(
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""),
    ): Fixture {
        val credentials = Credentials()
        val executor = object : OAuthHttpExecutor {
            override suspend fun postForm(url: String, form: String): OAuthHttpResult =
                OAuthHttpResult(200, responses.removeAt(0))
        }
        val configDirectory = temporary.newFolder()
        val controller = NativeOAuthController(
            configs = NativeOAuthConfigStore(configDirectory, allowLoopbackHttp = false),
            flows = NativeOAuthFlowStore(temporary.newFolder(), clock = { now }),
            client = NativeOAuthTokenClient(executor, clock = { now }),
            credentials = credentials,
            profiles = Profiles(profile()),
            clock = { now },
        )
        return Fixture(controller, credentials, configDirectory)
    }

    private class Fixture(
        val controller: NativeOAuthController,
        val credentials: Credentials,
        val configDirectory: java.io.File,
    )

    private suspend fun configure(fixture: Fixture) {
        fixture.controller.saveConfiguration(profile(), NativeOAuthConfigDraft(
            authorizationEndpoint = "https://auth.example.test/authorize",
            tokenEndpoint = "https://auth.example.test/token",
            clientId = "client-1",
            scopes = "read write",
        ))
    }

    @Test fun loginAndCallbackAreOneTimeAndPersistTheTokenOnce() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        assertTrue(fixture.controller.state.value.configured)
        val start = fixture.controller.beginLogin(profile())
        assertTrue(start is NativeOAuthLoginStart.Open)
        val state = Uri.parse((start as NativeOAuthLoginStart.Open).url)
            .getQueryParameter("state")
        assertNotNull(state)
        assertTrue(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, "code-1", null),
        ))
        // The same callback cannot be accepted or exchanged twice.
        assertFalse(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, "code-1", null),
        ))
        val first = fixture.controller.consumeCallback(allowNetwork = true)
        assertTrue(first is NativeOAuthCallbackOutcome.Connected)
        assertEquals("access-1", fixture.credentials.current?.accessToken)
        assertEquals(1, fixture.credentials.saves)
        assertEquals(NativeOAuthStatus.CONNECTED, fixture.controller.state.value.status)
        assertEquals(NativeOAuthCallbackOutcome.Ignored,
            fixture.controller.consumeCallback(allowNetwork = true))
        assertEquals(1, fixture.credentials.saves)
    }

    @Test fun mismatchedStateDoesNotConsumeTheLegitimateFlow() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        val start = fixture.controller.beginLogin(profile()) as NativeOAuthLoginStart.Open
        val state = Uri.parse(start.url).getQueryParameter("state")
        assertFalse(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters("x".repeat(32), "code-1", null),
        ))
        assertTrue(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, "code-1", null),
        ))
        assertTrue(fixture.controller.consumeCallback(allowNetwork = true)
            is NativeOAuthCallbackOutcome.Connected)
    }

    @Test fun noNetworkWhileARunIsActiveDiscardsTheCodeAndDoesNotSave() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        val start = fixture.controller.beginLogin(profile()) as NativeOAuthLoginStart.Open
        val state = Uri.parse(start.url).getQueryParameter("state")
        assertTrue(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, "code-1", null),
        ))
        val outcome = fixture.controller.consumeCallback(allowNetwork = false)
        assertTrue(outcome is NativeOAuthCallbackOutcome.Failed)
        assertEquals(NativeOAuthUiFailure.BUSY,
            (outcome as NativeOAuthCallbackOutcome.Failed).failure)
        assertNull(fixture.credentials.current)
        assertEquals(0, fixture.credentials.saves)
        assertEquals(NativeOAuthCallbackOutcome.Ignored,
            fixture.controller.consumeCallback(allowNetwork = true))
    }

    @Test fun logoutClearsTheTokenAndPendingCallback() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        val start = fixture.controller.beginLogin(profile()) as NativeOAuthLoginStart.Open
        val state = Uri.parse(start.url).getQueryParameter("state")
        assertTrue(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, "code-1", null),
        ))
        fixture.controller.logout(profile())
        assertEquals(2, fixture.credentials.clears)
        assertEquals(NativeOAuthStatus.SIGNED_OUT, fixture.controller.state.value.status)
        assertEquals(NativeOAuthCallbackOutcome.Ignored,
            fixture.controller.consumeCallback(allowNetwork = true))
    }

    @Test fun providerErrorCallbackIsReportedOnceWithoutARequest() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        val start = fixture.controller.beginLogin(profile()) as NativeOAuthLoginStart.Open
        val state = Uri.parse(start.url).getQueryParameter("state")
        assertTrue(fixture.controller.acceptCallback(
            "lnt-oauth", "callback",
            NativeOAuthCallbackParameters(state, null, "access_denied"),
        ))
        val outcome = fixture.controller.consumeCallback(allowNetwork = true)
        assertTrue(outcome is NativeOAuthCallbackOutcome.Failed)
        assertEquals(NativeOAuthUiFailure.ACCESS_DENIED,
            (outcome as NativeOAuthCallbackOutcome.Failed).failure)
        assertEquals(0, fixture.credentials.saves)
        assertEquals(NativeOAuthCallbackOutcome.Ignored,
            fixture.controller.consumeCallback(allowNetwork = true))
    }

    @Test fun savingAConfigurationClearsAnyOrphanedToken() = runBlocking {
        val fixture = fixture()
        fixture.credentials.current = OAuthCredential.of("orphan-access")
        configure(fixture)
        assertNull(fixture.credentials.current)
        assertEquals(1, fixture.credentials.clears)
    }

    @Test fun changingAConfigurationDropsTheTokenIssuedForTheOldOne() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        fixture.credentials.current = OAuthCredential.of("old-access")
        fixture.controller.saveConfiguration(profile(), NativeOAuthConfigDraft(
            authorizationEndpoint = "https://auth.example.test/authorize",
            tokenEndpoint = "https://auth.example.test/token",
            clientId = "client-2",
            scopes = "read write",
        ))
        assertNull(fixture.credentials.current)
        assertEquals("client-2", fixture.controller.state.value.clientId)
    }

    @Test fun corruptConfigurationIsVisibleAndCanBeRemoved() = runBlocking {
        val fixture = fixture()
        configure(fixture)
        val file = fixture.configDirectory.walkTopDown().first { it.isFile && it.extension == "json" }
        file.writeText("{not-json")
        val loaded = fixture.controller.load(profile().id)
        assertEquals(NativeOAuthUiFailure.STORE, loaded.failure)
        assertFalse(loaded.configured)
        val removed = fixture.controller.removeConfiguration(profile())
        assertEquals(NativeOAuthStatus.NOT_CONFIGURED, removed.status)
        assertNull(removed.failure)
        assertNull(fixture.controller.load(profile().id).fingerprint)
    }

}
