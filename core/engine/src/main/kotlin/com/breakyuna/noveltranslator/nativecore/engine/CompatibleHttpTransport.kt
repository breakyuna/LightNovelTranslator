package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*

/** Shared HTTP ownership. Cancellation closes the call while blocked IO unwinds before returning. */
class CompatibleHttpTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()) {
    fun request(url: String, secret: String): Request.Builder {
        NativeModelSettingsValidation.validateKey(secret)
        return Request.Builder().url(url).apply {
            if (secret.isNotEmpty()) header("Authorization", "Bearer $secret")
        }
    }

    /** The consumer reads on IO; neither the Response nor its stream escapes this lifetime. */
    suspend fun <T> read(request: Request, streaming: Boolean = false, consume: suspend (InputStream, String?) -> T): T = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        if (streaming) call.timeout().timeout(10, TimeUnit.MINUTES)
        coroutineScope {
            // Unconfined cleanup runs promptly on parent cancellation even while this IO thread is blocked.
            val canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) throw AgentModelFailure(when (response.code) {
                        401, 403 -> ModelFailureKind.AUTHENTICATION
                        408, 504 -> ModelFailureKind.TIMEOUT
                        429 -> ModelFailureKind.RATE_LIMIT
                        in 500..599 -> ModelFailureKind.SERVER
                        else -> ModelFailureKind.CONTENT
                    })
                    val body = response.body ?: throw AgentModelFailure(ModelFailureKind.EMPTY_RESPONSE)
                    consume(body.byteStream(), body.contentType()?.let { it.type + "/" + it.subtype })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (classified: AgentModelFailure) { throw classified }
            catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw AgentModelFailure(if (error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException)
                    ModelFailureKind.TIMEOUT else ModelFailureKind.NETWORK)
            } finally {
                canceller.cancel()
                withContext(NonCancellable) { canceller.join() }
            }
        }
    }

    suspend fun execute(request: Request): String = read(request) { stream, _ -> readUtf8(stream) }

    internal suspend fun readUtf8(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > 2L * 1024 * 1024) throw AgentModelFailure(ModelFailureKind.CONTENT)
            output.write(buffer, 0, read)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }
}
