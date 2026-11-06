package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Public HTTP reads only. No model credentials, cookies, automatic retries or direct fallback. */
class WorkspaceHttpFetch(private val network: ModelNetworkSettingsProvider) : WorkspaceFetchPort {
    override suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (request.cancellation.isCancelled) return@withContext WorkspaceFetchOutcome.Cancelled
            val settings = network.networkSettings()
            var url = request.url.toHttpUrl()
            repeat(4) { redirect ->
                currentCoroutineContext().ensureActive()
                if (request.cancellation.isCancelled) return@withContext WorkspaceFetchOutcome.Cancelled
                val remaining = request.deadlineAtMillis - System.currentTimeMillis()
                if (remaining <= 0) return@withContext failure(WorkspaceNetworkFailure.TIMEOUT)
                val host = url.host
                val addresses = Dns.SYSTEM.lookup(host)
                if (addresses.isEmpty() || addresses.any { !publicAddress(it) })
                    return@withContext failure(WorkspaceNetworkFailure.POLICY_REJECTED)
                val afterDns = request.deadlineAtMillis - System.currentTimeMillis()
                if (afterDns <= 0) return@withContext failure(WorkspaceNetworkFailure.TIMEOUT)
                if (request.cancellation.isCancelled) return@withContext WorkspaceFetchOutcome.Cancelled
                currentCoroutineContext().ensureActive()
                val client = OkHttpClient.Builder()
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            if (hostname == host) addresses else Dns.SYSTEM.lookup(hostname)
                    })
                    .proxy(settings.proxy?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(it.host, it.port)) } ?: Proxy.NO_PROXY)
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .connectTimeout(settings.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(settings.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .callTimeout(minOf(afterDns, settings.callTimeoutSeconds * 1000L), TimeUnit.MILLISECONDS)
                    .build()
                val call = client.newCall(Request.Builder().url(url).get()
                    .header("Accept", request.allowedMediaTypes.joinToString(", "))
                    .header("User-Agent", "LightNovelTranslator/NativeWorkspace").build())
                val outcome = coroutineScope {
                    val canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            while (isActive && !request.cancellation.isCancelled) delay(50)
                        } finally { call.cancel() }
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        call.execute().use { response ->
                            if (request.cancellation.isCancelled) return@use WorkspaceFetchOutcome.Cancelled
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                if (!settings.followRedirects || redirect == 3)
                                    return@use failure(WorkspaceNetworkFailure.POLICY_REJECTED)
                                val next = response.header("Location")?.let(url::resolve)
                                    ?: return@use failure(WorkspaceNetworkFailure.INVALID_REQUEST)
                                // Revalidate each destination and never downgrade TLS.
                                WorkspaceFetchRequest.create(next.toString(), request.deadlineAtMillis,
                                    request.cancellation, request.maxBytes, request.allowedMediaTypes)
                                if (url.isHttps && !next.isHttps) return@use failure(WorkspaceNetworkFailure.POLICY_REJECTED)
                                url = next
                                return@use null
                            }
                            if (!response.isSuccessful) return@use WorkspaceFetchOutcome.Failed(
                                WorkspaceNetworkError(if (response.code == 429) WorkspaceNetworkFailure.RATE_LIMITED
                                    else WorkspaceNetworkFailure.HTTP_STATUS, response.code))
                            val body = response.body ?: return@use failure(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT)
                            val media = body.contentType()?.let { it.type + "/" + it.subtype }
                                ?: return@use failure(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT)
                            if (media !in request.allowedMediaTypes) return@use failure(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT)
                            if (body.contentLength() > request.maxBytes) return@use failure(WorkspaceNetworkFailure.CONTENT_TOO_LARGE)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use streamRead@ { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (request.cancellation.isCancelled) return@streamRead
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (output.size().toLong() + count > request.maxBytes)
                                        return@coroutineScope failure(WorkspaceNetworkFailure.CONTENT_TOO_LARGE)
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (request.cancellation.isCancelled) WorkspaceFetchOutcome.Cancelled
                            else WorkspaceFetchOutcome.Fetched(WorkspaceFetchedDocument(url.toString(), media, output.toByteArray(), false))
                        }
                    } finally {
                        canceller.cancel()
                        withContext(NonCancellable) { canceller.join() }
                        client.connectionPool.evictAll()
                    }
                }
                if (outcome != null) return@withContext outcome
            }
            failure(WorkspaceNetworkFailure.POLICY_REJECTED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.net.SocketTimeoutException) { failure(WorkspaceNetworkFailure.TIMEOUT) }
        catch (_: java.net.UnknownHostException) { failure(WorkspaceNetworkFailure.DNS) }
        catch (_: javax.net.ssl.SSLException) { failure(WorkspaceNetworkFailure.TLS) }
        catch (_: IOException) {
            if (request.cancellation.isCancelled) WorkspaceFetchOutcome.Cancelled else failure(WorkspaceNetworkFailure.CONNECT)
        }
        catch (_: Exception) { failure(WorkspaceNetworkFailure.INVALID_REQUEST) }
    }

    private fun failure(reason: WorkspaceNetworkFailure) = WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(reason))

    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        if (bytes.size == 16) return first and 0xfe != 0xfc // IPv6 unique local.
        val second = bytes[1].toInt() and 255
        return first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
            !(first == 169 && second == 254) && !(first == 198 && second in 18..19)
    }
}
