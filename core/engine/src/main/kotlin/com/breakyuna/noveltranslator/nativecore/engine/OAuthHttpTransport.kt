package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A bounded token-endpoint response; never logs or exposes headers. */
data class OAuthHttpResult(val status: Int, val body: String) {
    override fun toString(): String = "OAuthHttpResult(status=$status, body=redacted)"
}

/**
 * Port for one form-encoded token request. Implementations must not follow redirects,
 * must bound the response and must not log request or response bodies.
 */
fun interface OAuthHttpExecutor {
    suspend fun postForm(url: String, form: String): OAuthHttpResult
}

/** Default token transport. Redirects stay disabled so a code or token cannot be replayed elsewhere. */
class OkHttpOAuthHttpExecutor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) : OAuthHttpExecutor {
    override suspend fun postForm(url: String, form: String): OAuthHttpResult =
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
                        OAuthHttpResult(response.code, readBounded(body.byteStream()))
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

    private suspend fun readBounded(input: InputStream): String {
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
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: Exception) {
            throw OAuthFailure(OAuthFailureKind.MALFORMED_RESPONSE)
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}
