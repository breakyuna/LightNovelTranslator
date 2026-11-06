package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelHttpProxy
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsUnavailable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleHttpTransportNetworkSettingsTest {
    private fun unsetClient() = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun fixtureClient(
        code: Int = 200,
        body: String = "{\"ok\":true}",
        retryAfter: String? = null,
        observed: () -> Unit = {},
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        observed()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Fixture")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun request() = okhttp3.Request.Builder().url("http://localhost:1234/v1/models").get().build()

    @Test fun unsetSettingsReturnTheInjectedClientUnchanged() {
        val base = unsetClient()
        val transport = CompatibleHttpTransport(base)
        assertSame(base, transport.clientFor(ModelNetworkSettings.DEFAULT))
        assertTrue(transport.clientFor(ModelNetworkSettings.DEFAULT).followRedirects)
        assertFalse(ModelNetworkSettings.DEFAULT.followRedirects)
    }

    @Test fun savedSettingsApplyEveryTimeoutProxyAndRedirectPolicy() {
        val settings = ModelNetworkSettings(
            connectTimeoutSeconds = 5,
            readTimeoutSeconds = 30,
            writeTimeoutSeconds = 7,
            callTimeoutSeconds = 45,
            proxy = ModelHttpProxy.of("127.0.0.1", 3128),
            followRedirects = true,
            source = ModelNetworkSettingsSource.SAVED,
        )
        val transport = CompatibleHttpTransport(unsetClient())
        val client = transport.clientFor(settings)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(7_000, client.writeTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        val proxy = requireNotNull(client.proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(3128, address.port)
        assertTrue(address.isUnresolved)
        assertSame(client, transport.clientFor(settings))
    }

    @Test fun savedSettingsWithoutProxyAndRedirectsClearBothExplicitly() {
        val settings = ModelNetworkSettings(readTimeoutSeconds = 12, source = ModelNetworkSettingsSource.SAVED)
        val client = CompatibleHttpTransport(unsetClient()).clientFor(settings)
        assertEquals(12_000, client.readTimeoutMillis)
        assertNull(client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun providerIsCalledForEachRequestAndItsPolicyIsUsed() = runBlocking {
        var calls = 0
        var requests = 0
        val settings = ModelNetworkSettings(readTimeoutSeconds = 30, source = ModelNetworkSettingsSource.SAVED)
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings {
                calls++
                return settings
            }
        }
        val transport = CompatibleHttpTransport(fixtureClient(observed = { requests++ }), provider)
        assertEquals("{\"ok\":true}", transport.execute(request()))
        assertEquals(1, calls)
        assertEquals(1, requests)
    }

    @Test fun providerFailureFailsClosedBeforeAnyCall() = runBlocking {
        var requests = 0
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings {
                throw IOException("missing saved policy")
            }
        }
        val transport = CompatibleHttpTransport(fixtureClient(observed = { requests++ }), provider)
        assertThrows(ModelNetworkSettingsUnavailable::class.java) { runBlocking { transport.execute(request()) } }
        assertEquals(0, requests)
    }

    @Test fun invalidSettingsProviderFailureKeepsItsTypedError() = runBlocking {
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings = throw InvalidModelNetworkSettings()
        }
        val transport = CompatibleHttpTransport(fixtureClient(), provider)
        assertThrows(InvalidModelNetworkSettings::class.java) { runBlocking { transport.execute(request()) } }
        Unit
    }

    @Test fun retryAfterIsCarriedOnTheClassifiedFailure() = runBlocking {
        val limited = CompatibleHttpTransport(fixtureClient(429, "private-body", "30"))
        try {
            limited.execute(request())
            throw AssertionError("Accepted rate limit as success")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.RATE_LIMIT, failure.kind)
            assertEquals(30_000L, failure.retryAfterMillis)
            assertFalse(failure.toString().contains("private-body"))
        }

        val unavailable = CompatibleHttpTransport(fixtureClient(503, "private-body", "later"))
        try {
            unavailable.execute(request())
            throw AssertionError("Accepted server failure as success")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.SERVER, failure.kind)
            assertNull(failure.retryAfterMillis)
        }
    }

    @Test fun errorWithoutRetryAfterCarriesNoDelay() = runBlocking {
        val transport = CompatibleHttpTransport(fixtureClient(401, "private-body"))
        try {
            transport.execute(request())
            throw AssertionError("Accepted authentication failure as success")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind)
            assertNull(failure.retryAfterMillis)
        }
    }
}
