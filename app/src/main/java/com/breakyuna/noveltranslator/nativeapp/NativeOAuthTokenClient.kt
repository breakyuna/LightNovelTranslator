package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import com.breakyuna.noveltranslator.nativecore.engine.OAuthRefresher
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class NativeOAuthHttpResult(val status: Int, val body: String) {
    override fun toString(): String = "NativeOAuthHttpResult(status=$status, body=redacted)"
}

/** Test seam only: production uses [OkHttpNativeOAuthHttpExecutor]. */
internal fun interface NativeOAuthHttpExecutor {
    suspend fun postForm(url: String, form: String): NativeOAuthHttpResult
}

/**
 * Authorization-code and refresh-token client for public OAuth clients. Requests are
 * form encoded, redirects are not followed, responses are bounded and cancellation
 * closes the call. No request body, response body, token or header is logged.
 */
class NativeOAuthTokenClient internal constructor(
    private val executor: NativeOAuthHttpExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor() : this(OkHttpNativeOAuthHttpExecutor())

    suspend fun exchange(
        config: NativeOAuthConfig,
        code: String,
        codeVerifier: String,
        fallbackScopes: List<String>,
    ): OAuthCredential = execute(
        config = config,
        fields = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "client_id" to config.clientId,
            "code_verifier" to codeVerifier,
        ),
        fallbackScopes = fallbackScopes,
    )

    suspend fun refresh(config: NativeOAuthConfig, current: OAuthCredential): OAuthCredential {
        val refreshToken = current.refreshToken?.takeIf { it.isNotEmpty() }
            ?: throw OAuthFailure(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH)
        return execute(
            config = config,
            fields = listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to config.clientId,
            ),
            fallbackScopes = current.scopes,
        )
    }

    private suspend fun execute(
        config: NativeOAuthConfig,
        fields: List<Pair<String, String>>,
        fallbackScopes: List<String>,
    ): OAuthCredential {
        val form = nativeOAuthForm(fields)
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                executor.postForm(config.tokenEndpoint, form)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw OAuthFailure(OAuthFailureKind.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: java.net.SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw OAuthFailure(OAuthFailureKind.TIMEOUT)
        } catch (_: java.io.InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw OAuthFailure(OAuthFailureKind.TIMEOUT)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw OAuthFailure(OAuthFailureKind.NETWORK)
        } catch (failure: OAuthFailure) {
            throw failure
        } catch (_: Exception) {
            throw OAuthFailure(OAuthFailureKind.UNKNOWN)
        }
        if (response.status !in 200..299) {
            throw OAuthFailure(nativeOAuthHttpFailureKind(response.status))
        }
        return parseNativeOAuthTokenResponse(
            body = response.body,
            nowMillis = clock().coerceAtLeast(0),
            fallbackScopes = fallbackScopes,
        )
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 45_000L
    }
}

/** 400/401/403 and other client errors are rejected token requests; 408 and 5xx are transient. */
internal fun nativeOAuthHttpFailureKind(status: Int): OAuthFailureKind = when {
    status == 408 || status == 504 -> OAuthFailureKind.TIMEOUT
    status == 429 -> OAuthFailureKind.REFRESH_UNAVAILABLE
    status in 400..499 -> OAuthFailureKind.REFRESH_REJECTED
    status in 500..599 -> OAuthFailureKind.REFRESH_UNAVAILABLE
    else -> OAuthFailureKind.MALFORMED_RESPONSE
}

internal fun nativeOAuthForm(fields: List<Pair<String, String>>): String =
    fields.joinToString("&") { (name, value) ->
        nativeOAuthFormEncode(name) + "=" + nativeOAuthFormEncode(value)
    }

internal fun nativeOAuthFormEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

/**
 * Parses a token response. The provider's `scope` may be a space-delimited string or
 * an array; an omitted scope keeps the caller's existing scopes. Only a sanitized
 * [OAuthFailure] leaves this function on malformed or rejected content.
 */
internal fun parseNativeOAuthTokenResponse(
    body: String,
    nowMillis: Long,
    fallbackScopes: List<String>,
): OAuthCredential {
    try {
        if (body.length > MAX_RESPONSE_CHARS) throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        val root = JsonObjectValue.parse(body, JsonInputLimits(MAX_RESPONSE_CHARS, 8)).json
        val error = (root["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!error.isNullOrEmpty()) throw OAuthFailure(OAuthFailureKind.REFRESH_REJECTED)
        val accessToken = string(root, "access_token")
        if (accessToken.isEmpty()) throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        val refreshToken = when (val element = root["refresh_token"]) {
            null, JsonNull -> null
            is JsonPrimitive -> element.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
            else -> throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
        val expiresAtMillis = when (val element = root["expires_in"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val text = element.content
                if (!EXPIRES_IN.matches(text)) throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                val seconds = text.toLongOrNull() ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                if (seconds !in 0..MAX_EXPIRES_SECONDS) throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                try {
                    Math.addExact(nowMillis, Math.multiplyExact(seconds, 1_000L))
                } catch (_: ArithmeticException) {
                    throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                }
            }
            else -> throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
        val scopes = when (val element = root["scope"]) {
            null, JsonNull -> fallbackScopes
            is JsonPrimitive -> {
                if (!element.isString) throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                element.content.split(' ').filter { it.isNotEmpty() }
            }
            is JsonArray -> element.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
            }
            else -> throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
        val tokenType = when (val element = root["token_type"]) {
            null, JsonNull -> "Bearer"
            is JsonPrimitive -> element.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
            else -> throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
        return OAuthCredential.of(accessToken, refreshToken, expiresAtMillis, scopes, tokenType)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OAuthFailure) {
        throw failure
    } catch (_: Exception) {
        throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
    }
}

private fun string(value: JsonObject, key: String): String =
    (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)

private val EXPIRES_IN = Regex("[0-9]{1,10}")
private const val MAX_EXPIRES_SECONDS = 315_360_000L
private const val MAX_RESPONSE_CHARS = 64 * 1024

/** Default token transport. Redirects stay disabled so a code or token cannot be replayed elsewhere. */
internal class OkHttpNativeOAuthHttpExecutor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) : NativeOAuthHttpExecutor {
    override suspend fun postForm(url: String, form: String): NativeOAuthHttpResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(form.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                .build()
            val call = client.newCall(request)
            coroutineScope {
                val canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        val body = response.body ?: throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
                        NativeOAuthHttpResult(response.code, readBounded(body.byteStream()))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: OAuthFailure) {
                    throw failure
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw if (error is java.net.SocketTimeoutException ||
                        error is java.io.InterruptedIOException) {
                        OAuthFailure(OAuthFailureKind.TIMEOUT)
                    } else {
                        OAuthFailure(OAuthFailureKind.NETWORK)
                    }
                } finally {
                    canceller.cancel()
                    withContext(NonCancellable) { canceller.join() }
                }
            }
        }

    private suspend fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_RESPONSE_CHARS) {
                throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
            }
            output.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: Exception) {
            throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
    }
}

/** Uses the saved endpoint configuration for a refresh; the token store is owned by OAuthTokenProvider. */
class NativeOAuthRefresher(
    private val configs: NativeOAuthConfigStore,
    private val client: NativeOAuthTokenClient,
) : OAuthRefresher {
    override suspend fun refresh(
        reference: ModelConfigurationRef,
        credential: OAuthCredential,
    ): OAuthCredential {
        val config = try {
            configs.load(reference.id)?.config
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw OAuthFailure(OAuthFailureKind.REFRESH_UNAVAILABLE)
        } ?: throw OAuthFailure(OAuthFailureKind.REFRESH_UNAVAILABLE)
        if (credential.refreshToken.isNullOrEmpty()) {
            throw OAuthFailure(OAuthFailureKind.EXPIRED_WITHOUT_REFRESH)
        }
        return client.refresh(config, credential)
    }
}
