package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * One bounded JSON-RPC HTTP exchange. [body] is request data and is deliberately omitted from
 * [toString]; transports must never log it or an attached credential.
 */
class McpTransportRequest(
    val body: String,
    val sessionId: String?,
    val protocolVersion: String?,
    val deadlineAtMillis: Long,
    val cancellation: WorkspaceCancellation,
    val expectsResponse: Boolean,
) {
    init {
        require(body.isNotEmpty() && body.length <= McpLimits.MAX_REQUEST_BYTES) { "Invalid MCP request body" }
        require(deadlineAtMillis > 0) { "Invalid MCP deadline" }
        require(sessionId == null || validSessionId(sessionId)) { "Invalid MCP session id" }
        require(protocolVersion == null || validProtocolVersion(protocolVersion)) {
            "Invalid MCP protocol version"
        }
    }

    override fun toString(): String =
        "McpTransportRequest(session=${sessionId != null}, protocol=$protocolVersion, " +
            "expectsResponse=$expectsResponse)"
}

/** Raw response. [body] is external, untrusted data and is omitted from [toString]. */
class McpTransportResponse(
    val body: String,
    val contentType: String?,
    val sessionId: String?,
) {
    init {
        require(sessionId == null || validSessionId(sessionId)) { "Invalid MCP session id" }
    }

    override fun toString(): String =
        "McpTransportResponse(chars=${body.length}, session=${sessionId != null})"
}

/** HTTP or fake transport boundary. Implementations must not retry or follow redirects. */
interface McpTransport {
    suspend fun send(request: McpTransportRequest): McpTransportResponse
}

/**
 * Streamable-HTTP MCP transport. It POSTs JSON-RPC and accepts JSON or `text/event-stream`.
 * Proxy and timeout defaults come from [ModelNetworkSettingsProvider]; redirects are always
 * disabled and there is no direct-connection fallback when settings fail. Private/loopback
 * addresses are allowed only by [McpServerConfig.allowPrivateNetwork]; link-local, metadata,
 * multicast and unspecified addresses stay rejected even then. Response bodies are capped at
 * two MiB and no credentials or request bodies are logged.
 */
class McpHttpTransport(
    private val config: McpServerConfig,
    private val network: ModelNetworkSettingsProvider,
    private val credentials: McpCredentialPort = McpNoCredentials,
) : McpTransport {
    override suspend fun send(request: McpTransportRequest): McpTransportResponse = withContext(Dispatchers.IO) {
        if (!config.enabled) throw McpException(McpFailureKind.DISABLED)
        if (request.cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        currentCoroutineContext().ensureActive()
        if (request.deadlineAtMillis <= System.currentTimeMillis()) throw McpException(McpFailureKind.TIMEOUT)

        val settings = try {
            network.networkSettings()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A settings failure is never replaced by an unproxied request.
            throw McpException(McpFailureKind.INTERNAL)
        }
        val credential = try {
            credentials.credentialFor(config.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw McpException(McpFailureKind.CREDENTIAL_UNAVAILABLE)
        }

        val url: HttpUrl = try {
            config.url.toHttpUrl()
        } catch (_: Exception) {
            throw McpException(McpFailureKind.INVALID_CONFIG)
        }
        val host = url.host
        val addresses = try {
            Dns.SYSTEM.lookup(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnknownHostException) {
            throw McpException(McpFailureKind.DNS)
        } catch (_: IOException) {
            throw McpException(McpFailureKind.DNS)
        }
        if (addresses.isEmpty()) throw McpException(McpFailureKind.DNS)
        if (addresses.any { !McpNetworkPolicy.isAllowed(it, config.allowPrivateNetwork) }) {
            throw McpException(McpFailureKind.POLICY_REJECTED)
        }

        val remaining = request.deadlineAtMillis - System.currentTimeMillis()
        if (remaining <= 0) throw McpException(McpFailureKind.TIMEOUT)
        val callMillis = minOf(remaining, settings.callTimeoutSeconds * 1000L)
        val connectMillis = minOf(remaining, settings.connectTimeoutSeconds * 1000L)
        val readMillis = minOf(remaining, settings.readTimeoutSeconds * 1000L)
        val writeMillis = minOf(remaining, settings.writeTimeoutSeconds * 1000L)
        if (callMillis <= 0 || connectMillis <= 0 || readMillis <= 0 || writeMillis <= 0) {
            throw McpException(McpFailureKind.TIMEOUT)
        }

        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == host) addresses else Dns.SYSTEM.lookup(hostname)
            })
            .proxy(
                settings.proxy?.let {
                    Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(it.host, it.port))
                } ?: Proxy.NO_PROXY,
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(connectMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(writeMillis, TimeUnit.MILLISECONDS)
            .callTimeout(callMillis, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "LightNovelTranslator/MCP")
            .post(request.body.toRequestBody(JSON_MEDIA))
        request.sessionId?.let { builder.header("Mcp-Session-Id", it) }
        request.protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        credential?.let { builder.header(it.headerName, it.valueForTransport()) }

        try {
            coroutineScope {
                val call = client.newCall(builder.build())
                val canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        while (isActive && !request.cancellation.isCancelled) delay(50)
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response -> handle(response, request) }
                } finally {
                    canceller.cancel()
                    withContext(NonCancellable) { canceller.join() }
                    client.connectionPool.evictAll()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: McpException) {
            throw failure
        } catch (_: SocketTimeoutException) {
            if (request.cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
            throw McpException(McpFailureKind.TIMEOUT)
        } catch (_: InterruptedIOException) {
            if (request.cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
            throw McpException(McpFailureKind.TIMEOUT)
        } catch (_: SSLException) {
            throw McpException(McpFailureKind.TLS)
        } catch (_: UnknownHostException) {
            throw McpException(McpFailureKind.DNS)
        } catch (_: IOException) {
            if (request.cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
            throw McpException(McpFailureKind.CONNECT)
        } catch (_: Exception) {
            throw McpException(McpFailureKind.INTERNAL)
        }
    }

    private suspend fun handle(
        response: Response,
        request: McpTransportRequest,
    ): McpTransportResponse {
        if (request.cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        val sessionId = response.header("Mcp-Session-Id")?.let {
            if (!validSessionId(it)) throw McpException(McpFailureKind.PROTOCOL)
            it
        }
        if (request.sessionId != null && sessionId != null && sessionId != request.sessionId) {
            throw McpException(McpFailureKind.PROTOCOL)
        }
        val code = response.code
        if (code !in 200..299) {
            throw McpException(
                kind = if (code == 408 || code == 504) McpFailureKind.TIMEOUT else McpFailureKind.HTTP_STATUS,
                httpStatus = code,
                retryAfterMillis = retryAfterMillis(response),
            )
        }
        val body = response.body
        if (!request.expectsResponse) {
            if (body != null) {
                readBoundedBytes(body.byteStream(), request.cancellation, McpLimits.MAX_RESPONSE_BYTES)
            }
            return McpTransportResponse("", null, sessionId)
        }
        if (code == 202 || code == 204) throw McpException(McpFailureKind.PROTOCOL)
        val responseBody = body ?: throw McpException(McpFailureKind.UNSUPPORTED_CONTENT)
        val mediaType = responseBody.contentType()?.toString()
        val bytes = readBoundedBytes(responseBody.byteStream(), request.cancellation, McpLimits.MAX_RESPONSE_BYTES)
        return McpTransportResponse(decodeUtf8(bytes), mediaType, sessionId)
    }

    private fun retryAfterMillis(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        if (raw.isEmpty() || !raw.all { it in '0'..'9' }) return null
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds !in 0L..86_400L) return null
        return seconds * 1000L
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Bounded shared body reader used for both JSON and SSE responses. */
internal suspend fun readBoundedBytes(
    stream: InputStream,
    cancellation: WorkspaceCancellation,
    limit: Long = McpLimits.MAX_RESPONSE_BYTES,
): ByteArray {
    require(limit in 1L..McpLimits.MAX_RESPONSE_BYTES) { "Invalid MCP byte limit" }
    if (cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        if (cancellation.isCancelled) throw McpException(McpFailureKind.CANCELLED)
        val count = stream.read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > limit) {
            throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Strict UTF-8 decoding; malformed bytes are not repaired into plausible JSON. */
internal fun decodeUtf8(bytes: ByteArray): String {
    if (bytes.size.toLong() > McpLimits.MAX_RESPONSE_BYTES) {
        throw McpException(McpFailureKind.CONTENT_TOO_LARGE)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw McpException(McpFailureKind.UNSUPPORTED_CONTENT)
    }
}

internal fun validSessionId(value: String): Boolean =
    value.length in 1..McpLimits.MAX_SESSION_ID_CHARS &&
        value.all { it.code in 0x21..0x7e }

internal fun validProtocolVersion(value: String): Boolean =
    value.length in 1..McpLimits.MAX_PROTOCOL_VERSION_CHARS &&
        value.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

/**
 * Address policy for one resolved endpoint. Public addresses are allowed by default.
 * With opt-in private networking, loopback/site-local/unique-local/CGNAT addresses are
 * allowed, but link-local, metadata, multicast, unspecified, broadcast and Teredo remain
 * rejected so a private MCP server cannot become a metadata-service proxy.
 */
internal object McpNetworkPolicy {
    fun isAllowed(address: InetAddress, allowPrivateNetwork: Boolean): Boolean =
        when (address.address.size) {
            4 -> ipv4Allowed(address.address, allowPrivateNetwork)
            16 -> ipv6Allowed(address.address, allowPrivateNetwork)
            else -> false
        }

    private fun ipv4Allowed(bytes: ByteArray, allowPrivateNetwork: Boolean): Boolean {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first == 0 || first == 255) return false
        if (first == 127) return allowPrivateNetwork
        if (first == 10) return allowPrivateNetwork
        if (first == 172 && second in 16..31) return allowPrivateNetwork
        if (first == 192 && second == 168) return allowPrivateNetwork
        if (first == 169 && second == 254) return false
        if (first == 100 && second in 64..127) return allowPrivateNetwork
        if (first == 198 && second in 18..19) return allowPrivateNetwork
        if (first >= 224) return false
        return true
    }

    private fun ipv6Allowed(bytes: ByteArray, allowPrivateNetwork: Boolean): Boolean {
        val mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (mapped) return ipv4Allowed(bytes.copyOfRange(12, 16), allowPrivateNetwork)
        val loopback = (0 until 15).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()
        if (loopback) return allowPrivateNetwork
        val compatible = (0 until 12).all { bytes[it] == 0.toByte() }
        if (compatible) return ipv4Allowed(bytes.copyOfRange(12, 16), allowPrivateNetwork)

        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first == 0xFF) return false // Multicast.
        if (first == 0xFE && (second and 0xC0) == 0x80) return false // Link-local.
        if (first == 0xFE && (second and 0xC0) == 0xC0) return allowPrivateNetwork // Deprecated site-local.
        if ((first and 0xFE) == 0xFC) return allowPrivateNetwork // Unique local.
        if (first == 0x20 && second == 0x01 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) {
            return false // Teredo can embed an arbitrary IPv4 destination.
        }
        if (first == 0x20 && second == 0x02) {
            return ipv4Allowed(bytes.copyOfRange(2, 6), allowPrivateNetwork) // 6to4 embedded IPv4.
        }
        return true
    }
}
