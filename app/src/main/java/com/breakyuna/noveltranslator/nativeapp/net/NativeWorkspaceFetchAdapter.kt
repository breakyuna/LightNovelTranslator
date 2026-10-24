package com.breakyuna.noveltranslator.nativeapp.net

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchRequest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchedDocument
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkError
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Public-address policy for workspace and Agent web access. It is deliberately stricter than
 * URL syntax validation: literal private ranges, loopback, link-local, multicast, CGNAT and
 * IPv6 unique-local addresses are refused before a request is built, and every redirect is
 * checked again. DNS answers are filtered by the same policy through [ValidatingDns].
 */
internal object NativePublicNetworkPolicy {
    private val blockedSuffixes = listOf(".localhost", ".local", ".internal", ".home.arpa")

    fun requirePublicUrl(value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("Invalid URL", it)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "Only HTTP(S) is allowed" }
        require(uri.userInfo == null) { "URL credentials are not allowed" }
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank()) { "URL requires a host" }
        require(host != "localhost" && blockedSuffixes.none { host == it.removePrefix(".") || host.endsWith(it) }) {
            "Local host names are not allowed"
        }
        val looksIpv4 = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
        if (looksIpv4 || host.contains(':')) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid address")
            require(isPublic(literal)) { "Private addresses are not allowed" }
        }
        return uri
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
            if (first == 100 && second in 64..127) return false
            if (first == 192 && second == 0) return false
            if (first >= 224) return false
        } else if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false
            if (first == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) return false
        }
        return true
    }

    /** Validated resolved addresses; a name with any private answer is rejected wholesale. */
    class ValidatingDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals("localhost", true) || blockedSuffixes.any { hostname.endsWith(it) }) {
                throw UnknownHostException("Local host is not allowed")
            }
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
                throw UnknownHostException("Host resolves to a non-public address")
            }
            return addresses
        }
    }
}

/**
 * Production fetch adapter for `WorkspaceFetchPort`. Redirects are followed manually so every
 * hop is revalidated, credentials are never attached, and a response is rejected rather than
 * silently truncated when it exceeds the request budget. It is independent of any provider
 * credential and does not proxy around a failed network policy.
 */
internal class NativeWorkspaceFetchAdapter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .dns(NativePublicNetworkPolicy.ValidatingDns())
        .build(),
) : WorkspaceFetchPort {
    override suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome {
        request.cancellation.throwIfCancelled()
        if (System.currentTimeMillis() >= request.deadlineAtMillis) {
            return WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT))
        }
        var current = request.url
        repeat(MAX_REDIRECTS + 1) { hop ->
            val uri = try {
                NativePublicNetworkPolicy.requirePublicUrl(current)
            } catch (_: IllegalArgumentException) {
                return WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.POLICY_REJECTED))
            }
            request.cancellation.throwIfCancelled()
            if (request.deadlineAtMillis <= System.currentTimeMillis()) {
                return WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT))
            }
            val outcome = execute(request, uri)
            when (outcome) {
                is HopResult.Redirect -> {
                    if (hop >= MAX_REDIRECTS) {
                        return WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.HTTP_STATUS))
                    }
                    current = outcome.location
                }
                is HopResult.Done -> return outcome.outcome
            }
        }
        return WorkspaceFetchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.HTTP_STATUS))
    }

    private suspend fun execute(
        request: WorkspaceFetchRequest,
        uri: URI,
    ): HopResult {
        val scoped = client.newCall(
            Request.Builder()
                .url(uri.toString())
                .header("Accept", request.allowedMediaTypes.joinToString(", "))
                .header("User-Agent", "LNT-Native/1")
                .get()
                .build(),
        )
        return withContext(Dispatchers.IO) {
            val cancellation = coroutineContext.job.invokeOnCompletion { scoped.cancel() }
            try {
                scoped.execute().use outer@ { response ->
                    request.cancellation.throwIfCancelled()
                    if (response.code in 300..399) {
                        val location = response.header("Location")
                            ?: return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                                WorkspaceNetworkError(WorkspaceNetworkFailure.HTTP_STATUS, response.code),
                            ))
                        val resolved = runCatching { URI(uri.resolve(location)).toString() }.getOrNull()
                            ?: return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                                WorkspaceNetworkError(WorkspaceNetworkFailure.POLICY_REJECTED),
                            ))
                        return@outer HopResult.Redirect(resolved)
                    }
                    if (response.code !in 200..299) {
                        return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                            WorkspaceNetworkError(
                                WorkspaceNetworkFailure.HTTP_STATUS,
                                response.code,
                                retryAfterMillis(response),
                            ),
                        ))
                    }
                    val body = response.body ?: return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                        WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT),
                    ))
                    val mediaType = body.contentType()?.toString()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                        ?: return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                            WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT),
                        ))
                    if (mediaType !in request.allowedMediaTypes.map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }.toSet()) {
                        return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                            WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT),
                        ))
                    }
                    val declared = body.contentLength()
                    if (declared > request.maxBytes) {
                        return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                            WorkspaceNetworkError(WorkspaceNetworkFailure.CONTENT_TOO_LARGE),
                        ))
                    }
                    val output = ByteArrayOutputStream(declared.coerceIn(0, request.maxBytes).toInt().coerceAtLeast(64))
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        var total = 0L
                        while (true) {
                            request.cancellation.throwIfCancelled()
                            if (System.currentTimeMillis() >= request.deadlineAtMillis) {
                                return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                                    WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT),
                                ))
                            }
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > request.maxBytes) {
                                return@outer HopResult.Done(WorkspaceFetchOutcome.Failed(
                                    WorkspaceNetworkError(WorkspaceNetworkFailure.CONTENT_TOO_LARGE),
                                ))
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    val finalUrl = response.request.url.toString()
                    NativePublicNetworkPolicy.requirePublicUrl(finalUrl)
                    HopResult.Done(WorkspaceFetchOutcome.Fetched(
                        WorkspaceFetchedDocument(
                            finalUrl = finalUrl,
                            mediaType = mediaType,
                            bytes = output.toByteArray(),
                            truncated = false,
                        ),
                    ))
                }
            } catch (failure: IOException) {
                HopResult.Done(if (request.cancellation.isCancelled) WorkspaceFetchOutcome.Cancelled
                else WorkspaceFetchOutcome.Failed(classify(failure)))
            } finally {
                cancellation.dispose()
            }
        }
    }

    private fun classify(failure: IOException): WorkspaceNetworkError = when (failure) {
        is java.net.SocketTimeoutException -> WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT)
        is UnknownHostException -> WorkspaceNetworkError(WorkspaceNetworkFailure.DNS)
        is SSLException -> WorkspaceNetworkError(WorkspaceNetworkFailure.TLS)
        else -> WorkspaceNetworkError(WorkspaceNetworkFailure.CONNECT)
    }

    private fun retryAfterMillis(response: Response): Long? {
        val value = response.header("Retry-After")?.trim() ?: return null
        value.toLongOrNull()?.let { seconds -> return (seconds * 1_000L).coerceAtLeast(0) }
        return null
    }

    private sealed interface HopResult {
        class Done(val outcome: WorkspaceFetchOutcome) : HopResult
        class Redirect(val location: String) : HopResult
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val DEFAULT_BUFFER = 16 * 1024
    }
}
