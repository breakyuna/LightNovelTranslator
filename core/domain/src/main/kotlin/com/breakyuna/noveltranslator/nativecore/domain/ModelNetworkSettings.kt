package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provenance marker: [DEFAULT] means no saved override is in effect. */
enum class ModelNetworkSettingsSource { DEFAULT, SAVED }

/**
 * Sanitized local configuration failure. It never carries a proxy host, path or
 * credential; callers must not fall back to a direct connection.
 */
class InvalidModelNetworkSettings : IllegalArgumentException("Invalid model network settings")

/** A saved network configuration could not be read. Direct connections are not a fallback. */
class ModelNetworkSettingsUnavailable : IllegalStateException("Model network settings unavailable")

/**
 * Explicit HTTP proxy. Only a host and port are representable, so userinfo,
 * schemes, paths and query strings are rejected before this value exists.
 * [toString] deliberately omits the host so routine diagnostics cannot leak
 * local network topology.
 */
data class ModelHttpProxy private constructor(val host: String, val port: Int) {
    init {
        if (host.isEmpty() || host.length > MAX_HOST_CHARS || host != host.trim() || host != host.lowercase() ||
            host.none { it.isLetterOrDigit() } ||
            host.startsWith('.') || host.endsWith('.') || host.contains("..") ||
            host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') } ||
            host.contains("://") || host.contains('@') || host.contains('/') ||
            host.contains('?') || host.contains('#') || host.contains(':') ||
            host.startsWith('[') || host.endsWith(']') ||
            port !in 1..65535) {
            throw InvalidModelNetworkSettings()
        }
    }

    override fun toString(): String = "ModelHttpProxy(configured)"

    companion object {
        fun of(host: String, port: Int): ModelHttpProxy =
            ModelHttpProxy(host.trim().lowercase(), port)

        private const val MAX_HOST_CHARS = 253
    }
}

/**
 * Opaque, non-secret identity used to look up one encrypted proxy credential.
 * It is safe to persist with the network policy, but it never is the username or
 * password. The format is shared by settings validation and the Keystore store.
 */
object ModelProxyCredentialReference {
    const val MAX_CHARS = 128

    /** Opaque printable ASCII id without spaces or credential separators; hashed by the store. */
    fun isValid(value: String): Boolean =
        value.length in 1..MAX_CHARS && value.all { it.code in 33..126 && it != ':' && it != '@' }
}

/**
 * HTTP proxy Basic credentials for one resolved request. The password exists only
 * in memory and is never serialized by this type; [toString] redacts both fields.
 */
data class ModelProxyCredential private constructor(
    val username: String,
    val password: String,
) {
    init {
        require(isValidPart(username) && ':' !in username) { "Invalid proxy username" }
        require(isValidPart(password)) { "Invalid proxy password" }
    }

    override fun toString(): String = "ModelProxyCredential(username=redacted, password=redacted)"

    companion object {
        const val MAX_PART_CHARS = 512

        fun of(username: String, password: String): ModelProxyCredential =
            ModelProxyCredential(username, password)

        private fun isValidPart(value: String): Boolean =
            value.length in 1..MAX_PART_CHARS && value == value.trim() &&
                value.all { it.code in 0x20..0x7e }
    }
}

/** Sanitized proxy-credential failure. It never carries a username, password or file path. */
class InvalidModelProxyCredential : IllegalStateException("Invalid model proxy credential")

/** Optional process-level source for an encrypted proxy credential. */
interface ModelProxyCredentialPort {
    /** Returns null only when the reference has no saved credential; corruption must throw. */
    suspend fun load(reference: String): ModelProxyCredential?
}

/**
 * Immutable request policy for every compatible-model HTTP call. [source] records
 * whether this value came from [DEFAULT] or from an explicitly saved policy; the
 * storage boundary marks edited values with [saved] before writing, and [DEFAULT]
 * is the canonical no-override value used when no saved file exists.
 *
 * Timeouts are explicit bounded seconds. Redirects stay disabled unless the user
 * opts in; enabling them applies to both same-scheme and TLS redirects. An explicit
 * proxy is used for HTTP and HTTPS (CONNECT); there is no fallback to direct access.
 * [proxyCredentialRef] is only an opaque lookup key for the encrypted credential
 * store; a proxy password is never part of this schema.
 */
data class ModelNetworkSettings(
    val connectTimeoutSeconds: Int = 10,
    val readTimeoutSeconds: Int = 90,
    val writeTimeoutSeconds: Int = 10,
    val callTimeoutSeconds: Int = 90,
    val proxy: ModelHttpProxy? = null,
    val followRedirects: Boolean = false,
    val source: ModelNetworkSettingsSource = ModelNetworkSettingsSource.DEFAULT,
    val proxyCredentialRef: String? = null,
) {
    init {
        if (connectTimeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS ||
            readTimeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS ||
            writeTimeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS ||
            callTimeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS ||
            (proxyCredentialRef != null && !ModelProxyCredentialReference.isValid(proxyCredentialRef))) {
            throw InvalidModelNetworkSettings()
        }
    }

    /** Storage/last-write identity; source is provenance, not a network policy value. */
    fun saved(): ModelNetworkSettings =
        if (source == ModelNetworkSettingsSource.SAVED) this else copy(source = ModelNetworkSettingsSource.SAVED)

    override fun toString(): String =
        "ModelNetworkSettings(connect=${connectTimeoutSeconds}s, read=${readTimeoutSeconds}s, " +
            "write=${writeTimeoutSeconds}s, call=${callTimeoutSeconds}s, " +
            "proxy=${if (proxy == null) "none" else "set"}, " +
            "proxyCredential=${if (proxyCredentialRef == null) "none" else "set"}, " +
            "redirects=$followRedirects, source=$source)"

    companion object {
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 600

        /** Matches the historical transport: connect/write 10s, read/call 90s, no proxy, no redirects. */
        val DEFAULT = ModelNetworkSettings()
    }
}

/** Optional process-level source. A failed read must fail closed, never direct-connect. */
interface ModelNetworkSettingsProvider {
    suspend fun networkSettings(): ModelNetworkSettings
}

/**
 * Versioned non-secret encoding of [ModelNetworkSettings]. Credentials, proxy
 * passwords, headers and endpoints are not part of this schema; the optional
 * [ModelNetworkSettings.proxyCredentialRef] is only an opaque lookup key. Unknown
 * fields are rejected so a future secret field cannot be silently written here.
 */
object ModelNetworkSettingsCodec {
    const val FORMAT = "lnt-model-network-settings"
    const val FORMAT_VERSION = 1
    const val MAX_CHARS = 8_192

    private val ROOT_KEYS = setOf(
        "format", "formatVersion", "connectTimeoutSeconds", "readTimeoutSeconds",
        "writeTimeoutSeconds", "callTimeoutSeconds", "proxy", "followRedirects", "source",
    )
    /** Added after v1; omitted when null so existing files keep their exact fingerprint. */
    private val OPTIONAL_ROOT_KEYS = setOf("proxyCredentialRef")
    private val PROXY_KEYS = setOf("host", "port")
    private val INTEGER = Regex("0|[1-9][0-9]*")

    fun encode(settings: ModelNetworkSettings): String {
        val proxy = settings.proxy
        val encoded = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("connectTimeoutSeconds", settings.connectTimeoutSeconds)
            put("readTimeoutSeconds", settings.readTimeoutSeconds)
            put("writeTimeoutSeconds", settings.writeTimeoutSeconds)
            put("callTimeoutSeconds", settings.callTimeoutSeconds)
            put("proxy", if (proxy == null) JsonNull else buildJsonObject {
                put("host", proxy.host)
                put("port", proxy.port)
            })
            put("followRedirects", settings.followRedirects)
            put("source", settings.source.name)
            settings.proxyCredentialRef?.let { put("proxyCredentialRef", it) }
        }.toString()
        if (encoded.length > MAX_CHARS) throw InvalidModelNetworkSettings()
        return encoded
    }

    /** SHA-256 of [encode]; lower-case hexadecimal. */
    fun fingerprint(settings: ModelNetworkSettings): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(settings)))

    /** Strict inverse of [encode]. Any unknown, missing or ill-typed field is rejected. */
    fun decode(text: String): ModelNetworkSettings {
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
            require(root.keys == ROOT_KEYS || root.keys == ROOT_KEYS + OPTIONAL_ROOT_KEYS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "formatVersion") == FORMAT_VERSION.toLong())
            val proxyElement = root.getValue("proxy")
            val proxy = when (proxyElement) {
                JsonNull -> null
                is JsonObject -> {
                    require(proxyElement.keys == PROXY_KEYS)
                    ModelHttpProxy.of(string(proxyElement, "host"), integer(proxyElement, "port", 1L, 65_535L).toInt())
                }
                else -> throw InvalidModelNetworkSettings()
            }
            val redirects = (root.getValue("followRedirects") as? JsonPrimitive)
                ?.takeUnless { it.isString }?.booleanOrNull ?: throw InvalidModelNetworkSettings()
            val source = when (string(root, "source")) {
                ModelNetworkSettingsSource.DEFAULT.name -> ModelNetworkSettingsSource.DEFAULT
                ModelNetworkSettingsSource.SAVED.name -> ModelNetworkSettingsSource.SAVED
                else -> throw InvalidModelNetworkSettings()
            }
            val proxyCredentialRef = when (val reference = root["proxyCredentialRef"]) {
                null, JsonNull -> null
                is JsonPrimitive -> {
                    require(reference.isString)
                    reference.content
                }
                else -> throw InvalidModelNetworkSettings()
            }
            return ModelNetworkSettings(
                connectTimeoutSeconds = timeout(root, "connectTimeoutSeconds"),
                readTimeoutSeconds = timeout(root, "readTimeoutSeconds"),
                writeTimeoutSeconds = timeout(root, "writeTimeoutSeconds"),
                callTimeoutSeconds = timeout(root, "callTimeoutSeconds"),
                proxy = proxy,
                followRedirects = redirects,
                source = source,
                proxyCredentialRef = proxyCredentialRef,
            )
        } catch (invalid: InvalidModelNetworkSettings) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelNetworkSettings()
        }
    }

    private fun timeout(value: JsonObject, key: String): Int =
        integer(value, key, ModelNetworkSettings.MIN_TIMEOUT_SECONDS.toLong(),
            ModelNetworkSettings.MAX_TIMEOUT_SECONDS.toLong()).toInt()

    private fun integer(value: JsonObject, key: String, minimum: Long = 0, maximum: Long = Long.MAX_VALUE): Long {
        val primitive = value.getValue(key) as? JsonPrimitive ?: throw InvalidModelNetworkSettings()
        require(!primitive.isString && INTEGER.matches(primitive.content))
        val parsed = primitive.content.toLongOrNull() ?: throw InvalidModelNetworkSettings()
        if (parsed !in minimum..maximum) throw InvalidModelNetworkSettings()
        return parsed
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value.getValue(key) as? JsonPrimitive ?: throw InvalidModelNetworkSettings()
        require(primitive.isString) { "String field must be a string" }
        return primitive.content
    }
}
