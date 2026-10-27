package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
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
    /** Optional encrypted proxy-credential source. Unset keeps the historical direct auth. */
    private val proxyCredentialPort: ModelProxyCredentialPort? = null,
) {
    @Volatile private var cached: CachedClient? = null

    private class CachedClient(
        val settings: ModelNetworkSettings,
        val proxyCredential: ModelProxyCredential?,
        val client: OkHttpClient,
    )

    fun request(url: String, secret: String, authMode: ModelAuthMode = ModelAuthMode.API_KEY): Request.Builder {
        NativeModelSettingsValidation.validateKey(secret)
        return Request.Builder().url(url).apply {
            when (authMode) {
                ModelAuthMode.API_KEY, ModelAuthMode.OAUTH ->
                    if (secret.isNotEmpty()) header("Authorization", "Bearer $secret")
                ModelAuthMode.NONE -> Unit
            }
        }
    }

    /** The consumer reads on IO; neither the Response nor its stream escapes this lifetime. */
    suspend fun <T> read(request: Request, streaming: Boolean = false, consume: suspend (InputStream, String?) -> T): T =
        readWithHeaders(request, streaming) { stream, contentType, _ -> consume(stream, contentType) }

    /**
     * Same ownership and bounds as [read], with an explicit allowlisted header view for
     * successful responses. Classified failures keep Retry-After on [AgentModelFailure]
     * and never pass a header object. Unknown headers (cookies, authorization, arbitrary
     * provider fields) are dropped, never copied. The response body still uses the
     * caller's bounded reader.
     */
    suspend fun <T> readWithHeaders(request: Request, streaming: Boolean = false,
        consume: suspend (InputStream, String?, ModelResponseHeaders) -> T): T = withContext(Dispatchers.IO) {
        val settings = resolveSettings()
        val activeClient = clientFor(settings, resolveProxyCredential(settings))
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
                    consume(body.byteStream(), body.contentType()?.let { it.type + "/" + it.subtype },
                        ModelResponseHeaders.from(response.headers))
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

    /** Bounded UTF-8 body plus the same allowlisted headers; still at most 2 MiB. */
    suspend fun executeWithHeaders(request: Request): ModelHttpResponse =
        readWithHeaders(request) { stream, _, headers -> ModelHttpResponse(readUtf8(stream), headers) }

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
     * Loads proxy auth only when a proxy and a reference are both configured. A
     * corrupt or unavailable store fails closed instead of silently connecting
     * without credentials. Absence returns null.
     */
    private suspend fun resolveProxyCredential(settings: ModelNetworkSettings): ModelProxyCredential? {
        if (settings.proxy == null) return null
        val reference = settings.proxyCredentialRef ?: return null
        val provider = proxyCredentialPort ?: return null
        return try {
            provider.load(reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidModelProxyCredential) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelProxyCredential()
        }
    }

    /**
     * Applies saved timeouts, explicit HTTP proxy and opt-in redirects to a derived
     * client. [ModelNetworkSettings.DEFAULT] with no credential returns the injected
     * client unchanged, preserving the historical read/call and no-redirect behaviour.
     * Proxy Basic auth is installed only when a resolved credential exists; it stays
     * in memory and uses a bounded single retry (a second 407 never authenticates again).
     */
    internal fun clientFor(settings: ModelNetworkSettings,
        proxyCredential: ModelProxyCredential? = null): OkHttpClient {
        if (settings == ModelNetworkSettings.DEFAULT && proxyCredential == null) return client
        cached?.let {
            if (it.settings == settings && it.proxyCredential == proxyCredential) return it.client
        }
        synchronized(this) {
            val existing = cached
            if (existing != null && existing.settings == settings &&
                existing.proxyCredential == proxyCredential) return existing.client
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
            if (proxy != null && proxyCredential != null) {
                builder.proxyAuthenticator(proxyAuthenticator(proxyCredential))
            }
            val created = builder.build()
            cached = CachedClient(settings, proxyCredential, created)
            return created
        }
    }

    /**
     * Basic proxy auth for exactly one challenge. The first response has no
     * Proxy-Authorization header and gets one retry; any subsequent 407 (or a
     * non-proxy status) returns null so OkHttp stops retrying instead of looping.
     */
    private fun proxyAuthenticator(credential: ModelProxyCredential): Authenticator =
        Authenticator { _, response ->
            if (response.code != 407 || response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization",
                        Credentials.basic(credential.username, credential.password))
                    .build()
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

/** Bounded response body; its [toString] never includes provider content. */
class ModelHttpResponse internal constructor(val body: String, val headers: ModelResponseHeaders) {
    override fun toString(): String = "ModelHttpResponse(body=redacted, headers=$headers)"
}

/**
 * Sanitized response headers from a fixed allowlist: Retry-After and X-RateLimit-*.
 * Names are normalized to lower case, values are bounded printable ASCII, and other
 * headers (cookies, authorization, arbitrary provider data) are never retained.
 */
class ModelResponseHeaders private constructor(values: Map<String, String>) {
    val values: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(values))

    val rateLimit: Map<String, String> = Collections.unmodifiableMap(values
        .filterKeys { it.startsWith(RATE_LIMIT_PREFIX) }
        .toSortedMap())

    fun value(name: String): String? = values[name.lowercase()]

    /** Parsed Retry-After when present and valid; null for an absent, invalid or past value. */
    fun retryAfterMillis(nowMillis: Long = System.currentTimeMillis()): Long? =
        RetryAfterHeader.parseMillis(values["retry-after"], nowMillis)

    override fun toString(): String = "ModelResponseHeaders(names=${values.keys.sorted()})"

    companion object {
        private const val RATE_LIMIT_PREFIX = "x-ratelimit-"
        private const val MAX_HEADERS = 32
        private const val MAX_NAME_CHARS = 64
        private const val MAX_VALUE_CHARS = 256

        internal fun from(headers: Headers): ModelResponseHeaders {
            val selected = LinkedHashMap<String, String>()
            for (name in headers.names()) {
                if (selected.size >= MAX_HEADERS) break
                val normalized = name.lowercase()
                if (!allowed(normalized)) continue
                val value = headers[name] ?: continue
                if (value.length > MAX_VALUE_CHARS || value.any { it.code !in 32..126 }) continue
                selected[normalized] = value
            }
            return ModelResponseHeaders(selected)
        }

        private fun allowed(name: String): Boolean {
            if (name.length > MAX_NAME_CHARS) return false
            if (name == "retry-after") return true
            if (!name.startsWith(RATE_LIMIT_PREFIX)) return false
            val suffix = name.removePrefix(RATE_LIMIT_PREFIX)
            return suffix.isNotEmpty() && suffix.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
