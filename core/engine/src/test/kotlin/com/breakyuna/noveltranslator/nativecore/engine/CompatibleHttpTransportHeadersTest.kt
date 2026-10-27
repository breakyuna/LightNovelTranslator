package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompatibleHttpTransportHeadersTest {
    private fun request() = okhttp3.Request.Builder().url("http://localhost:1234/v1/models").get().build()

    private fun client(status: Int = 200, payload: String = "{\"ok\":true}",
        configure: Response.Builder.() -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().apply {
                request(chain.request())
                protocol(Protocol.HTTP_1_1)
                code(status)
                message("Fixture")
                body(payload.toResponseBody("application/json".toMediaType()))
                configure()
            }.build()
        }.build()

    @Test fun onlyRetryAfterAndRateLimitHeadersAreExposed() = runBlocking {
        val transport = CompatibleHttpTransport(client {
            header("Retry-After", "12")
                .header("X-RateLimit-Limit", "60")
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "1700000000")
                .header("Set-Cookie", "session=private")
                .header("Authorization", "Bearer private")
                .header("X-Request-Id", "private")
                .header("X-RateLimit-Big", "a".repeat(300))
        })
        val response = transport.executeWithHeaders(request())
        assertEquals("{\"ok\":true}", response.body)
        assertEquals(
            setOf("retry-after", "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset"),
            response.headers.values.keys,
        )
        assertEquals("12", response.headers.value("Retry-After"))
        assertEquals(12_000L, response.headers.retryAfterMillis())
        assertEquals(mapOf("x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "0",
            "x-ratelimit-reset" to "1700000000"), response.headers.rateLimit)
        assertNull(response.headers.value("set-cookie"))
        assertNull(response.headers.value("authorization"))
        assertNull(response.headers.value("x-request-id"))
        assertNull(response.headers.value("x-ratelimit-big"))
        assertEquals("ModelHttpResponse(body=redacted, headers=ModelResponseHeaders(names=" +
            "[retry-after, x-ratelimit-limit, x-ratelimit-remaining, x-ratelimit-reset]))",
            response.toString())
    }

    @Test fun invalidRetryAfterIsNotGuessed() = runBlocking {
        val response = CompatibleHttpTransport(client { header("Retry-After", "later") })
            .executeWithHeaders(request())
        assertNull(response.headers.retryAfterMillis())
    }

    @Test fun headerChannelStillEnforcesTheTwoMebibyteBodyBound() = runBlocking {
        val transport = CompatibleHttpTransport(client(payload = "a".repeat(2 * 1024 * 1024 + 1)))
        try {
            transport.executeWithHeaders(request())
            throw AssertionError("Accepted an oversized body")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT, failure.kind)
        }
    }

    @Test fun failuresKeepTheirClassifiedRetryAfterWithoutExposingBodies(): Unit = runBlocking {
        val transport = CompatibleHttpTransport(client(429, "private-body") { header("Retry-After", "30") })
        assertThrows(AgentModelFailure::class.java) {
            runBlocking { transport.executeWithHeaders(request()) }
        }
        try {
            transport.executeWithHeaders(request())
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.RATE_LIMIT, failure.kind)
            assertEquals(30_000L, failure.retryAfterMillis)
            assertEquals(false, failure.toString().contains("private-body"))
        }
    }
}
