package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelHttpProxy
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredentialPort
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Proxy Basic auth is installed only when a reference resolves to a credential. */
class CompatibleHttpTransportProxyCredentialTest {
    private val credential = ModelProxyCredential.of("proxy-user", "proxy-password")
    private val settings = ModelNetworkSettings(
        proxy = ModelHttpProxy.of("127.0.0.1", 3128),
        proxyCredentialRef = "proxy-ref",
        source = ModelNetworkSettingsSource.SAVED,
    )

    private class Port(private val value: ModelProxyCredential?) : ModelProxyCredentialPort {
        var loads = 0
        override suspend fun load(reference: String): ModelProxyCredential? {
            loads++
            return value
        }
    }

    private fun baseClient() = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body("{\"ok\":true}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun request() = Request.Builder().url("http://localhost:1234/v1/models").get().build()

    private fun challenge(request: Request, code: Int = 407): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("proxy")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

    @Test fun proxyAuthenticatorIsAbsentWithoutReferenceOrCredential() = runBlocking {
        val base = baseClient()
        val transport = CompatibleHttpTransport(base)
        assertSame(base.proxyAuthenticator,
            transport.clientFor(settings.copy(proxyCredentialRef = null), null).proxyAuthenticator)
        assertSame(base.proxyAuthenticator,
            transport.clientFor(settings, null).proxyAuthenticator)
        assertSame(base.proxyAuthenticator,
            transport.clientFor(settings.copy(proxy = null), credential).proxyAuthenticator)

        val emptyPort = Port(null)
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings = settings
        }
        CompatibleHttpTransport(base, provider, emptyPort).execute(request())
        assertEquals(1, emptyPort.loads)
        assertSame(base.proxyAuthenticator, transport.clientFor(settings, null).proxyAuthenticator)
    }

    @Test fun installedAuthenticatorAddsBasicOnceAndStopsOnASecondChallenge() {
        val client = CompatibleHttpTransport(baseClient()).clientFor(settings, credential)
        val request = request()
        val first = requireNotNull(client.proxyAuthenticator.authenticate(null, challenge(request)))
        assertEquals(Credentials.basic("proxy-user", "proxy-password"),
            first.header("Proxy-Authorization"))
        assertNull(client.proxyAuthenticator.authenticate(null, challenge(first)))
        assertNull(client.proxyAuthenticator.authenticate(null, challenge(request, 401)))
    }

    @Test fun configuredPortIsLoadedOnlyWhenAProxyCredentialIsConfigured() = runBlocking {
        val port = Port(credential)
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings = settings
        }
        assertEquals("{\"ok\":true}", CompatibleHttpTransport(baseClient(), provider, port)
            .execute(request()))
        assertEquals(1, port.loads)

        val noProxy = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings =
                settings.copy(proxy = null, proxyCredentialRef = null)
        }
        val directPort = Port(credential)
        CompatibleHttpTransport(baseClient(), noProxy, directPort).execute(request())
        assertEquals(0, directPort.loads)
    }

    @Test fun corruptProxyCredentialFailsBeforeDispatch() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings = settings
        }
        val port = object : ModelProxyCredentialPort {
            override suspend fun load(reference: String): ModelProxyCredential? {
                throw InvalidModelProxyCredential()
            }
        }
        val transport = CompatibleHttpTransport(client, provider, port)
        assertThrows(InvalidModelProxyCredential::class.java) {
            runBlocking { transport.execute(request()) }
        }
        assertEquals(0, requests)
    }
}
