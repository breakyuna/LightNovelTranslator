package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*

/** Shared HTTP ownership. Cancellation closes the call while blocked IO unwinds before returning. */
class CompatibleHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build(),
    /** Optional saved network policy. Unset keeps every historical client default. */
    private val networkSettings: ModelNetworkSettingsProvider? = null,
) {
    @Volatile private var cached: CachedClient? = null

    private class CachedClient(val settings: ModelNetworkSettings, val client: OkHttpClient)

    fun request(url: String, secret: String): Request.Builder {
        NativeModelSettingsValidation.validateKey(secret)
        return Request.Builder().url(url).apply {
            if (secret.isNotEmpty()) header("Authorization", "Bearer $secret")
        }
    }

    /** The consumer reads on IO; neither the Response nor its stream escapes this lifetime. */
    suspend fun <T> read(request: Request, streaming: Boolean = false, consume: suspend (InputStream, String?) -> T): T = withContext(Dispatchers.IO) {
        val activeClient = clientFor(resolveSettings())
        val call = activeClient.newCall(request)
        // Streaming keeps the historical ten-minute call ceiling; the saved call timeout
        // still bounds every non-streaming call through [clientFor].
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
                    }, RetryAfterHeader.parseMillis(response.header("Retry-After")))
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

    /**
     * Resolves the optional provider once per call. Local settings failures are
     * typed and never fall back to an unproxied or differently-timed request.
     */
    private suspend fun resolveSettings(): ModelNetworkSettings {
        val provider = networkSettings ?: return ModelNetworkSettings.DEFAULT
        return try { provider.networkSettings() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: InvalidModelNetworkSettings) { throw invalid }
        catch (unavailable: ModelNetworkSettingsUnavailable) { throw unavailable }
        catch (_: Exception) { throw ModelNetworkSettingsUnavailable() }
    }

    /**
     * Applies saved timeouts, explicit HTTP proxy and opt-in redirects to a derived
     * client. [ModelNetworkSettings.DEFAULT] returns the injected client unchanged,
     * preserving the historical two-minute read/call and no-redirect behaviour.
     */
    internal fun clientFor(settings: ModelNetworkSettings): OkHttpClient {
        if (settings == ModelNetworkSettings.DEFAULT) return client
        cached?.let { if (it.settings == settings) return it.client }
        synchronized(this) {
            val existing = cached
            if (existing != null && existing.settings == settings) return existing.client
            val builder = client.newBuilder()
                .connectTimeout(settings.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(settings.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(settings.writeTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout(settings.callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .followRedirects(settings.followRedirects)
                .followSslRedirects(settings.followRedirects)
            val proxy = settings.proxy
            builder.proxy(if (proxy == null) null else Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
            val created = builder.build()
            cached = CachedClient(settings, created)
            return created
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
