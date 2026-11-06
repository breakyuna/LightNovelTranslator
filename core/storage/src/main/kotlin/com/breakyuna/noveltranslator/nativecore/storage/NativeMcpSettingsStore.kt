package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One stored MCP endpoint. Secrets are not representable in this class. */
class NativeMcpServerRecord(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val allowPrivateNetwork: Boolean = false,
    allowedTools: Collection<String> = emptySet(),
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxTools: Int = DEFAULT_MAX_TOOLS,
) {
    val allowedTools: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(allowedTools))

    init {
        require(SERVER_ID.matches(id)) { "Invalid MCP server id" }
        require(name.isNotEmpty() && name.length <= MAX_SERVER_NAME_CHARS) {
            "Invalid MCP server name"
        }
        require(name == name.trim() && name.none { it.isISOControl() }) { "Invalid MCP server name" }
        require(wellFormed(name)) { "Invalid MCP server name" }
        require(validToolList(this.allowedTools)) { "Invalid MCP tool allowlist" }
        require(timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) { "Invalid MCP timeout" }
        require(maxTools in 1..MAX_TOOLS) { "Invalid MCP tool limit" }
        validateUrl(url, allowPrivateNetwork)
    }

    fun withEnabled(value: Boolean): NativeMcpServerRecord = NativeMcpServerRecord(
        id = id,
        name = name,
        url = url,
        enabled = value,
        allowPrivateNetwork = allowPrivateNetwork,
        allowedTools = allowedTools,
        timeoutMillis = timeoutMillis,
        maxTools = maxTools,
    )

    override fun equals(other: Any?): Boolean =
        other is NativeMcpServerRecord &&
            id == other.id &&
            name == other.name &&
            url == other.url &&
            enabled == other.enabled &&
            allowPrivateNetwork == other.allowPrivateNetwork &&
            allowedTools == other.allowedTools &&
            timeoutMillis == other.timeoutMillis &&
            maxTools == other.maxTools

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + allowPrivateNetwork.hashCode()
        result = 31 * result + allowedTools.hashCode()
        result = 31 * result + timeoutMillis.hashCode()
        result = 31 * result + maxTools
        return result
    }

    /** Never includes the endpoint or a possible secret value. */
    override fun toString(): String =
        "NativeMcpServerRecord(id=$id, enabled=$enabled, privateNetwork=$allowPrivateNetwork, " +
            "tools=${allowedTools.size}, maxTools=$maxTools)"

    private companion object {
        val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val IPV4_LITERAL = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")

        fun validToolList(tools: Set<String>): Boolean =
            tools.size <= MAX_TOOLS && tools.all { value ->
                value.length in 1..MAX_TOOL_NAME_CHARS &&
                    value.none { it.isISOControl() || it.isWhitespace() } &&
                    wellFormed(value)
            }

        fun wellFormed(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character.isHighSurrogate() -> {
                        if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                            return false
                        }
                        index += 2
                    }
                    character.isLowSurrogate() -> return false
                    else -> index += 1
                }
            }
            return true
        }

        fun validateUrl(value: String, allowPrivateNetwork: Boolean) {
            require(value.length in 1..MAX_URL_CHARS) { "Invalid MCP URL" }
            require(value == value.trim()) { "Invalid MCP URL" }
            require(value.none { it.isISOControl() || it.isWhitespace() }) { "Invalid MCP URL" }
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("Invalid MCP URL")
            }
            require(uri.isAbsolute) { "Invalid MCP URL" }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "Invalid MCP URL scheme" }
            val host = uri.host
            require(!host.isNullOrBlank()) { "Invalid MCP URL host" }
            require(uri.userInfo == null) { "MCP URL credentials are not allowed" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "MCP URL query and fragment are not allowed"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid MCP URL port" }
            rejectPrivateLiteral(host, allowPrivateNetwork)
        }

        fun rejectPrivateLiteral(hostValue: String, allowPrivateNetwork: Boolean) {
            if (allowPrivateNetwork) return
            val host = hostValue.removePrefix("[").removeSuffix("]").substringBefore('%')
            if (host.equals("localhost", ignoreCase = true)) {
                throw IllegalArgumentException("Private MCP endpoint requires explicit opt-in")
            }
            val literal = IPV4_LITERAL.matches(host) || host.contains(':')
            if (!literal) return
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid MCP URL host")
            }
            require(isPublicAddress(address)) {
                "Private MCP endpoint requires explicit opt-in"
            }
        }

        fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return false
            }
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                if (first == 0 || first >= 224) return false
                if (first == 100 && second in 64..127) return false
                if (first == 198 && second in 18..19) return false
            }
            return true
        }
    }
}

/** One record plus the monotonic revision used to bind credentials. */
data class NativeMcpServerEntry(
    val config: NativeMcpServerRecord,
    val revision: Long,
    val fingerprint: String,
) {
    init {
        require(revision >= 1) { "Invalid MCP server revision" }
        require(fingerprint.matches(HEX_SHA256)) { "Invalid MCP server fingerprint" }
    }

    private companion object {
        val HEX_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Immutable configuration view. [fingerprint] is a CAS token over every record and revision;
 * [servers] keeps the user-visible order.
 */
data class NativeMcpSettingsSnapshot(
    val servers: List<NativeMcpServerEntry>,
    val revision: Long,
    val fingerprint: String,
) {
    fun server(id: String): NativeMcpServerEntry? = servers.firstOrNull { it.config.id == id }

    companion object {
        val EMPTY = NativeMcpSettingsSnapshot(
            servers = emptyList(),
            revision = 0,
            fingerprint = fingerprintOf(emptyList()),
        )

        internal fun fingerprintOf(entries: List<NativeMcpServerEntry>): String = sha256(
            buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("revision", entry.revision)
                        put("config", JsonObjectValue.parse(
                            NativeMcpServerRecordCodec.encode(entry.config),
                            JsonInputLimits(NativeMcpServerRecordCodec.MAX_CONFIG_CHARS, 8),
                        ).json)
                    })
                }
            }.toString(),
        )

        internal fun recordFingerprint(record: NativeMcpServerRecord): String =
            sha256(NativeMcpServerRecordCodec.encode(record))

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
}

class InvalidMcpSettings : IllegalArgumentException("Invalid MCP settings")

class McpSettingsConflict : IllegalStateException("MCP settings changed")

/**
 * Strict versioned JSON list for non-secret MCP endpoints. Every document is written through
 * [AtomicFile]. Unknown keys, duplicate ids, secret-shaped fields, malformed records and a
 * fingerprint mismatch fail closed. There is no logger and no token field in this store.
 */
class NativeMcpSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = Mutex()

    suspend fun load(): NativeMcpSettingsSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { read()?.snapshot ?: NativeMcpSettingsSnapshot.EMPTY }
    }

    suspend fun upsert(
        record: NativeMcpServerRecord,
        expectedFingerprint: String,
    ): NativeMcpSettingsSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read()
            val current = previous?.snapshot ?: NativeMcpSettingsSnapshot.EMPTY
            if (current.fingerprint != expectedFingerprint) throw McpSettingsConflict()
            currentCoroutineContext().ensureActive()
            val index = current.servers.indexOfFirst { it.config.id == record.id }
            if (index >= 0 && current.servers[index].config == record) return@withLock current
            val entry = NativeMcpServerEntry(
                config = record,
                revision = if (index >= 0) Math.addExact(current.servers[index].revision, 1L) else 1L,
                fingerprint = NativeMcpSettingsSnapshot.recordFingerprint(record),
            )
            val next = ArrayList(current.servers)
            if (index >= 0) next[index] = entry else next += entry
            write(previous, next)
        }
    }

    suspend fun setEnabled(
        serverId: String,
        enabled: Boolean,
        expectedFingerprint: String,
    ): NativeMcpSettingsSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read()
            val current = previous?.snapshot ?: NativeMcpSettingsSnapshot.EMPTY
            if (current.fingerprint != expectedFingerprint) throw McpSettingsConflict()
            val index = current.servers.indexOfFirst { it.config.id == serverId }
            if (index < 0) throw McpSettingsConflict()
            val record = current.servers[index].config
            if (record.enabled == enabled) return@withLock current
            currentCoroutineContext().ensureActive()
            val changed = record.withEnabled(enabled)
            val entry = NativeMcpServerEntry(
                config = changed,
                revision = Math.addExact(current.servers[index].revision, 1L),
                fingerprint = NativeMcpSettingsSnapshot.recordFingerprint(changed),
            )
            val next = ArrayList(current.servers)
            next[index] = entry
            write(previous, next)
        }
    }

    suspend fun remove(
        serverId: String,
        expectedFingerprint: String,
    ): NativeMcpSettingsSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read()
            val current = previous?.snapshot ?: NativeMcpSettingsSnapshot.EMPTY
            if (current.fingerprint != expectedFingerprint) throw McpSettingsConflict()
            if (current.servers.none { it.config.id == serverId }) return@withLock current
            currentCoroutineContext().ensureActive()
            write(previous, current.servers.filterNot { it.config.id == serverId })
        }
    }

    private suspend fun read(): Stored? {
        val input = try {
            file.openRead()
        } catch (_: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + BACKUP_SUFFIX).exists()) {
                throw InvalidMcpSettings()
            }
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw InvalidMcpSettings()
        }
        try {
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_FILE_BYTES) throw InvalidMcpSettings()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return decode(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidMcpSettings) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidMcpSettings()
        }
    }

    private fun decode(bytes: ByteArray): Stored {
        if (bytes.size.toLong() > MAX_FILE_BYTES) throw InvalidMcpSettings()
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw InvalidMcpSettings()
        }
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(MAX_FILE_CHARS, 8)).json
        } catch (_: InvalidJsonPayload) {
            throw InvalidMcpSettings()
        } catch (_: Exception) {
            throw InvalidMcpSettings()
        }
        if (root.keys.any { NativeMcpServerRecordCodec.isSecretField(it) }) throw InvalidMcpSettings()
        if (root.keys != ENVELOPE_KEYS) throw InvalidMcpSettings()
        if (string(root, "format") != ENVELOPE_FORMAT) throw InvalidMcpSettings()
        if (integer(root, "formatVersion") != ENVELOPE_VERSION) throw InvalidMcpSettings()
        val revision = integer(root, "revision")
        val expected = string(root, "fingerprint")
        val serversElement = root["servers"] as? JsonArray ?: throw InvalidMcpSettings()
        if (serversElement.size > MAX_SERVERS) throw InvalidMcpSettings()
        val entries = ArrayList<NativeMcpServerEntry>(serversElement.size)
        for (element in serversElement) {
            val entry = element as? JsonObject ?: throw InvalidMcpSettings()
            if (entry.keys != ENTRY_KEYS) throw InvalidMcpSettings()
            val serverRevision = integer(entry, "revision")
            if (serverRevision < 1) throw InvalidMcpSettings()
            val configObject = entry["config"] as? JsonObject ?: throw InvalidMcpSettings()
            val config = try {
                NativeMcpServerRecordCodec.decode(configObject.toString())
            } catch (_: Exception) {
                throw InvalidMcpSettings()
            }
            entries += NativeMcpServerEntry(
                config = config,
                revision = serverRevision,
                fingerprint = NativeMcpSettingsSnapshot.recordFingerprint(config),
            )
        }
        if (entries.map { it.config.id }.toSet().size != entries.size) throw InvalidMcpSettings()
        val snapshot = NativeMcpSettingsSnapshot(
            servers = Collections.unmodifiableList(entries),
            revision = revision,
            fingerprint = NativeMcpSettingsSnapshot.fingerprintOf(entries),
        )
        if (snapshot.fingerprint != expected) throw InvalidMcpSettings()
        return Stored(snapshot)
    }

    private fun write(previous: Stored?, entries: List<NativeMcpServerEntry>): NativeMcpSettingsSnapshot {
        val revision = Math.addExact(previous?.snapshot?.revision ?: 0L, 1L)
        val snapshot = NativeMcpSettingsSnapshot(
            servers = Collections.unmodifiableList(ArrayList(entries)),
            revision = revision,
            fingerprint = NativeMcpSettingsSnapshot.fingerprintOf(entries),
        )
        val payload = buildJsonObject {
            put("format", ENVELOPE_FORMAT)
            put("formatVersion", ENVELOPE_VERSION)
            put("revision", revision)
            put("fingerprint", snapshot.fingerprint)
            put("servers", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("revision", entry.revision)
                        put("config", JsonObjectValue.parse(
                            NativeMcpServerRecordCodec.encode(entry.config),
                            JsonInputLimits(NativeMcpServerRecordCodec.MAX_CONFIG_CHARS, 8),
                        ).json)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        if (payload.size.toLong() > MAX_FILE_BYTES) throw InvalidMcpSettings()
        val output = file.startWrite()
        try {
            output.write(payload)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        return snapshot
    }

    private fun string(root: JsonObject, key: String): String {
        val primitive = root[key] as? JsonPrimitive ?: throw InvalidMcpSettings()
        if (!primitive.isString) throw InvalidMcpSettings()
        return primitive.content
    }

    private fun integer(root: JsonObject, key: String): Long {
        val primitive = root[key] as? JsonPrimitive ?: throw InvalidMcpSettings()
        if (primitive.isString || !INTEGER.matches(primitive.content)) throw InvalidMcpSettings()
        return primitive.content.toLongOrNull() ?: throw InvalidMcpSettings()
    }

    private class Stored(val snapshot: NativeMcpSettingsSnapshot)

    private companion object {
        const val FILE_NAME = "native-mcp-settings.json"
        const val BACKUP_SUFFIX = ".bak"
        const val ENVELOPE_FORMAT = "lnt-mcp-settings"
        const val ENVELOPE_VERSION = 1L
        const val MAX_FILE_BYTES = 1024L * 1024
        const val MAX_FILE_CHARS = 1024 * 1024
        const val MAX_SERVERS = 32
        val ENVELOPE_KEYS = setOf("format", "formatVersion", "revision", "fingerprint", "servers")
        val ENTRY_KEYS = setOf("revision", "config")
        val INTEGER = Regex("0|[1-9][0-9]*")
    }
}

/**
 * Strict codec for one non-secret MCP endpoint. Any token/password/header/credential-shaped
 * key is rejected before a record is constructed.
 */
internal object NativeMcpServerRecordCodec {
    const val FORMAT = "lnt-mcp-server-record"
    const val FORMAT_VERSION = 1L
    const val MAX_CONFIG_CHARS = 128 * 1024

    private val ROOT_KEYS = setOf(
        "format",
        "formatVersion",
        "id",
        "name",
        "url",
        "enabled",
        "allowPrivateNetwork",
        "allowedTools",
        "timeoutMillis",
        "maxTools",
    )
    private val INTEGER = Regex("0|[1-9][0-9]*")
    private val SECRET_WORDS = setOf(
        "token",
        "secret",
        "password",
        "passwd",
        "credential",
        "authorization",
        "apikey",
        "cookie",
        "privatekey",
    )

    fun encode(record: NativeMcpServerRecord): String {
        val encoded = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("id", record.id)
            put("name", record.name)
            put("url", record.url)
            put("enabled", record.enabled)
            put("allowPrivateNetwork", record.allowPrivateNetwork)
            put("allowedTools", buildJsonArray {
                record.allowedTools.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("timeoutMillis", record.timeoutMillis)
            put("maxTools", record.maxTools)
        }.toString()
        if (encoded.length > MAX_CONFIG_CHARS) throw InvalidMcpSettings()
        return encoded
    }

    fun decode(text: String): NativeMcpServerRecord {
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CONFIG_CHARS, 8)).json
            if (root.keys.any { isSecretField(it) }) throw InvalidMcpSettings()
            require(root.keys == ROOT_KEYS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "formatVersion") == FORMAT_VERSION)
            val toolsElement = root["allowedTools"] as? JsonArray ?: throw InvalidMcpSettings()
            require(toolsElement.size <= 256)
            val tools = toolsElement.map { element ->
                val primitive = element as? JsonPrimitive ?: throw InvalidMcpSettings()
                require(primitive.isString)
                primitive.content
            }
            return NativeMcpServerRecord(
                id = string(root, "id"),
                name = string(root, "name"),
                url = string(root, "url"),
                enabled = boolean(root, "enabled"),
                allowPrivateNetwork = boolean(root, "allowPrivateNetwork"),
                allowedTools = tools,
                timeoutMillis = integer(root, "timeoutMillis", 1_000L, 30L * 60 * 1000),
                maxTools = integer(root, "maxTools", 1L, 256L).toInt(),
            )
        } catch (invalid: InvalidMcpSettings) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidMcpSettings()
        }
    }

    fun isSecretField(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        return SECRET_WORDS.any { normalized.contains(it) } ||
            normalized == "auth" ||
            normalized == "headers" ||
            normalized.endsWith("header")
    }

    private fun string(root: JsonObject, key: String): String {
        val primitive = root[key] as? JsonPrimitive ?: throw InvalidMcpSettings()
        require(primitive.isString)
        return primitive.content
    }

    private fun integer(root: JsonObject, key: String, minimum: Long = 0, maximum: Long = Long.MAX_VALUE): Long {
        val primitive = root[key] as? JsonPrimitive ?: throw InvalidMcpSettings()
        require(!primitive.isString && INTEGER.matches(primitive.content))
        val parsed = primitive.content.toLongOrNull() ?: throw InvalidMcpSettings()
        if (parsed !in minimum..maximum) throw InvalidMcpSettings()
        return parsed
    }

    private fun boolean(root: JsonObject, key: String): Boolean {
        val primitive = root[key] as? JsonPrimitive ?: throw InvalidMcpSettings()
        require(!primitive.isString)
        return primitive.booleanOrNull ?: throw InvalidMcpSettings()
    }
}

internal const val MCP_MAX_SERVER_NAME_CHARS = 128
internal const val MCP_MAX_TOOL_NAME_CHARS = 128
internal const val MCP_MAX_URL_CHARS = 2_048
internal const val MCP_MAX_TOOLS = 256
internal const val MCP_DEFAULT_MAX_TOOLS = 64
internal const val MCP_MIN_TIMEOUT_MILLIS = 1_000L
internal const val MCP_DEFAULT_TIMEOUT_MILLIS = 30_000L
internal const val MCP_MAX_TIMEOUT_MILLIS = 30L * 60 * 1000

private const val MAX_SERVER_NAME_CHARS = MCP_MAX_SERVER_NAME_CHARS
private const val MAX_TOOL_NAME_CHARS = MCP_MAX_TOOL_NAME_CHARS
private const val MAX_URL_CHARS = MCP_MAX_URL_CHARS
private const val MAX_TOOLS = MCP_MAX_TOOLS
private const val DEFAULT_MAX_TOOLS = MCP_DEFAULT_MAX_TOOLS
private const val MIN_TIMEOUT_MILLIS = MCP_MIN_TIMEOUT_MILLIS
private const val DEFAULT_TIMEOUT_MILLIS = MCP_DEFAULT_TIMEOUT_MILLIS
private const val MAX_TIMEOUT_MILLIS = MCP_MAX_TIMEOUT_MILLIS
