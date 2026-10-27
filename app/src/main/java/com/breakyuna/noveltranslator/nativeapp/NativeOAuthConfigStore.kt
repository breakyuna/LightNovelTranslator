package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One non-secret OAuth 2.0 public-client configuration. The fixed redirect URI uses
 * an app-private custom scheme; no client secret exists anywhere in this type or on
 * disk. Endpoints are HTTPS only unless the application explicitly enables loopback
 * HTTP for a debug build.
 */
class NativeOAuthConfig internal constructor(
    val profileId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    scopes: List<String>,
    val redirectUri: String,
    internal val allowLoopbackHttp: Boolean,
) {
    val scopes: List<String> = Collections.unmodifiableList(ArrayList(scopes))

    init {
        if (!PROFILE_ID.matches(profileId)) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
        nativeOAuthEndpoint(authorizationEndpoint, allowLoopbackHttp, tokenEndpoint = false)
        nativeOAuthEndpoint(tokenEndpoint, allowLoopbackHttp, tokenEndpoint = true)
        if (redirectUri != REDIRECT_URI) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
        if (clientId != clientId.trim() || clientId.length !in 1..MAX_CLIENT_ID_CHARS ||
            clientId.any { it.code !in PRINTABLE_ASCII }) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
        if (this.scopes.size > MAX_SCOPES) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.INVALID)
        this.scopes.forEach { scope ->
            if (!SCOPE_TOKEN.matches(scope)) throw NativeOAuthConfigurationException(
                NativeOAuthConfigurationException.Reason.INVALID)
        }
    }

    override fun toString(): String =
        "NativeOAuthConfig(profileId=$profileId, clientId=present, scopes=${scopes.size}, " +
            "redirectUri=$redirectUri)"

    companion object {
        const val REDIRECT_URI = "lnt-oauth://callback"
        const val REDIRECT_SCHEME = "lnt-oauth"
        const val REDIRECT_HOST = "callback"
        const val MAX_SCOPES = 32
        const val MAX_CLIENT_ID_CHARS = 512

        private val PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SCOPE_TOKEN = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,512}")
        private val PRINTABLE_ASCII = 0x21..0x7e

        fun of(
            profileId: String,
            authorizationEndpoint: String,
            tokenEndpoint: String,
            clientId: String,
            scopes: List<String>,
            redirectUri: String = REDIRECT_URI,
            allowLoopbackHttp: Boolean = false,
        ): NativeOAuthConfig = NativeOAuthConfig(
            profileId = profileId.trim(),
            authorizationEndpoint = authorizationEndpoint.trim(),
            tokenEndpoint = tokenEndpoint.trim(),
            clientId = clientId.trim(),
            scopes = scopes.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            redirectUri = redirectUri,
            allowLoopbackHttp = allowLoopbackHttp,
        )
    }
}

class NativeOAuthConfigurationException(
    val reason: Reason,
) : Exception("OAuth configuration error: $reason") {
    enum class Reason { INVALID, CONFLICT, CORRUPT, UNAVAILABLE }

    override fun toString(): String = "NativeOAuthConfigurationException(reason=$reason)"
}

class NativeOAuthConfigSnapshot(
    val config: NativeOAuthConfig,
    val fingerprint: String,
) {
    override fun toString(): String =
        "NativeOAuthConfigSnapshot(profileId=${config.profileId}, fingerprintPresent=true)"
}

/**
 * Atomic, non-secret OAuth endpoint configuration keyed by profile id. A write is a
 * compare-and-set on the SHA-256 fingerprint of the complete configuration, so two
 * editors cannot silently overwrite each other. The file contains no token, client
 * secret or other credential material.
 */
class NativeOAuthConfigStore internal constructor(
    private val directory: File,
    private val allowLoopbackHttp: Boolean = false,
) {
    constructor(context: Context, allowLoopbackHttp: Boolean = false) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
        allowLoopbackHttp,
    )

    internal val allowsLoopbackHttp: Boolean get() = allowLoopbackHttp
    private val mutex = Mutex()

    suspend fun load(profileId: String): NativeOAuthConfigSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            read(profileId)
        }
    }

    suspend fun save(config: NativeOAuthConfig, expectedFingerprint: String?): NativeOAuthConfigSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                // Revalidate against this store's transport policy. A config built in a
                // debug process cannot introduce loopback HTTP into a release store.
                nativeOAuthEndpoint(config.authorizationEndpoint, allowLoopbackHttp, tokenEndpoint = false)
                nativeOAuthEndpoint(config.tokenEndpoint, allowLoopbackHttp, tokenEndpoint = true)
                val current = read(config.profileId)
                when {
                    expectedFingerprint == null && current != null ->
                        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CONFLICT)
                    expectedFingerprint != null && current == null ->
                        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CONFLICT)
                    expectedFingerprint != null && current?.fingerprint != expectedFingerprint ->
                        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CONFLICT)
                }
                val fingerprint = fingerprint(config)
                write(config, fingerprint)
                NativeOAuthConfigSnapshot(config, fingerprint)
            }
        }

    suspend fun clear(profileId: String, expectedFingerprint: String? = null): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            // A null baseline is an explicit forced removal (profile deletion or recovery
            // from a corrupt file), so it must not depend on decoding the current document.
            if (expectedFingerprint == null) {
                delete(file(profileId))
                return@withLock
            }
            val current = read(profileId) ?: return@withLock
            if (current.fingerprint != expectedFingerprint) {
                throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CONFLICT)
            }
            delete(file(profileId))
        }
    }

    internal fun fingerprint(config: NativeOAuthConfig): String =
        sha256(canonical(config))

    private fun canonical(config: NativeOAuthConfig): String = buildJsonObject {
        put("profileId", config.profileId)
        put("authorizationEndpoint", config.authorizationEndpoint)
        put("tokenEndpoint", config.tokenEndpoint)
        put("clientId", config.clientId)
        put("scopes", buildJsonArray { config.scopes.forEach { add(JsonPrimitive(it)) } })
        put("redirectUri", config.redirectUri)
    }.toString()

    private suspend fun read(profileId: String): NativeOAuthConfigSnapshot? {
        val source = file(profileId)
        if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return null
        val bytes = try {
            AtomicFile(source).openRead().use { readBounded(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CORRUPT)
        }
        return decode(profileId, bytes)
    }

    private fun decode(profileId: String, bytes: ByteArray): NativeOAuthConfigSnapshot {
        try {
            if (bytes.size > MAX_FILE_BYTES) throw NativeOAuthConfigurationException(
                NativeOAuthConfigurationException.Reason.CORRUPT)
            val root = JsonObjectValue.parse(strictUtf8(bytes), JsonInputLimits(MAX_FILE_BYTES, 8)).json
            require(root.keys == ENVELOPE_FIELDS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "profileId") == profileId)
            val storedFingerprint = string(root, "fingerprint")
            require(FINGERPRINT.matches(storedFingerprint))
            val scopesElement = root["scopes"] as? JsonArray ?: error("Invalid OAuth scopes")
            require(scopesElement.size <= NativeOAuthConfig.MAX_SCOPES)
            val scopes = scopesElement.map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Invalid OAuth scope")
            }
            val config = NativeOAuthConfig.of(
                profileId = profileId,
                authorizationEndpoint = string(root, "authorizationEndpoint"),
                tokenEndpoint = string(root, "tokenEndpoint"),
                clientId = string(root, "clientId"),
                scopes = scopes,
                redirectUri = string(root, "redirectUri"),
                allowLoopbackHttp = allowLoopbackHttp,
            )
            require(fingerprint(config) == storedFingerprint)
            return NativeOAuthConfigSnapshot(config, storedFingerprint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CORRUPT)
        }
    }

    private suspend fun write(config: NativeOAuthConfig, fingerprint: String) {
        val bytes = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("profileId", config.profileId)
            put("fingerprint", fingerprint)
            put("authorizationEndpoint", config.authorizationEndpoint)
            put("tokenEndpoint", config.tokenEndpoint)
            put("clientId", config.clientId)
            put("scopes", buildJsonArray { config.scopes.forEach { add(JsonPrimitive(it)) } })
            put("redirectUri", config.redirectUri)
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        if (!directory.isDirectory && !directory.mkdirs()) throw NativeOAuthConfigurationException(
            NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        val atomic = AtomicFile(file(config.profileId))
        val output = try {
            atomic.startWrite()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        }
        try {
            output.write(bytes)
            output.fd.sync()
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(output)
        } catch (cancelled: CancellationException) {
            atomic.failWrite(output)
            throw cancelled
        } catch (_: Exception) {
            atomic.failWrite(output)
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        }
    }

    private suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) throw NativeOAuthConfigurationException(
                NativeOAuthConfigurationException.Reason.CORRUPT)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.CORRUPT)
    }

    private fun delete(source: File) {
        try {
            AtomicFile(source).delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        }
        if (source.exists() || File(source.path + BACKUP_SUFFIX).exists()) {
            throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.UNAVAILABLE)
        }
    }

    private fun file(profileId: String): File {
        val name = sha256("$FORMAT\u0000$profileId")
        return File(directory, "$name.json")
    }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid OAuth configuration field")

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: error("Invalid OAuth configuration field")
        require(!primitive.isString && INTEGER.matches(primitive.content)) {
            "Invalid OAuth configuration field"
        }
        return primitive.content.toLongOrNull() ?: error("Invalid OAuth configuration field")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }

    private companion object {
        const val DIRECTORY_NAME = "native_oauth_config"
        const val FORMAT = "lnt-native-oauth-config"
        const val VERSION = 1L
        const val BACKUP_SUFFIX = ".bak"
        const val MAX_FILE_BYTES = 64 * 1024
        val ENVELOPE_FIELDS = setOf(
            "format", "version", "profileId", "fingerprint", "authorizationEndpoint",
            "tokenEndpoint", "clientId", "scopes", "redirectUri",
        )
        val INTEGER = Regex("0|[1-9][0-9]*")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}

/**
 * Strict absolute endpoint validation. HTTPS is required; HTTP is accepted only for
 * loopback hosts and only when the caller explicitly enables it (debug builds).
 * Fragments, user info and token-endpoint query strings are rejected.
 */
internal fun nativeOAuthEndpoint(value: String, allowLoopbackHttp: Boolean, tokenEndpoint: Boolean): String {
    if (value.length !in 1..2048 || value != value.trim() || value.any(Char::isISOControl)) {
        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.INVALID)
    }
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.INVALID)
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: invalidEndpoint()
    if (uri.isOpaque || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
        invalidEndpoint()
    }
    if (uri.port == 0 || uri.port < -1 || uri.port > 65535) invalidEndpoint()
    if (uri.rawAuthority?.endsWith(":") == true) invalidEndpoint()
    if (tokenEndpoint && uri.rawQuery != null) invalidEndpoint()
    if (uri.rawPath.orEmpty().split('/').any { it == "." || it == ".." }) invalidEndpoint()
    val loopback = uri.host!!.removeSurrounding("[", "]").lowercase(Locale.ROOT) in LOOPBACK_HOSTS
    if (scheme != "https" && !(allowLoopbackHttp && scheme == "http" && loopback)) invalidEndpoint()
    return value
}

private fun invalidEndpoint(): Nothing =
    throw NativeOAuthConfigurationException(NativeOAuthConfigurationException.Reason.INVALID)

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
