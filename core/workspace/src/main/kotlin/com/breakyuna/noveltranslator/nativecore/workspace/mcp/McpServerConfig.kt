package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceHardLimits
import com.breakyuna.noveltranslator.nativecore.workspace.requireWellFormedUnicode
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A configuration failure never contains the URL, credential or any secret value. */
class InvalidMcpServerConfig : IllegalArgumentException("Invalid MCP server configuration")

/**
 * One external MCP server endpoint. Secrets are deliberately not representable: tokens are
 * read from [McpCredentialPort] at request time. [allowedTools] contains external MCP names;
 * an empty set denies every tool call. Private/loopback endpoints require an explicit opt-in
 * and are still rejected when a literal address is known to be private.
 */
class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val allowPrivateNetwork: Boolean = false,
    allowedTools: Collection<String> = emptySet(),
    val timeoutMillis: Long = McpLimits.DEFAULT_TIMEOUT_MILLIS,
    val maxTools: Int = McpLimits.DEFAULT_MAX_TOOLS,
    val protocolVersion: String? = null,
) {
    val allowedTools: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(allowedTools))

    init {
        require(SERVER_ID.matches(id)) { "Invalid MCP server id" }
        require(name.isNotEmpty() && name.length <= McpLimits.MAX_SERVER_NAME_CHARS) {
            "Invalid MCP server name"
        }
        require(name == name.trim() && name.none { it.isISOControl() }) { "Invalid MCP server name" }
        requireWellFormedUnicode(name)
        validateUrl(url, allowPrivateNetwork)
        require(allowedTools.size <= McpLimits.MAX_TOOLS) { "Too many allowed MCP tools" }
        require(allowedTools.all { validToolName(it) }) { "Invalid MCP tool allowlist" }
        require(timeoutMillis in McpLimits.MIN_TIMEOUT_MILLIS..WorkspaceHardLimits.MAX_TIMEOUT_MILLIS) {
            "Invalid MCP timeout"
        }
        require(maxTools in 1..McpLimits.MAX_TOOLS) { "Invalid MCP tool limit" }
        if (protocolVersion != null) {
            require(protocolVersion.length in 1..McpLimits.MAX_PROTOCOL_VERSION_CHARS) {
                "Invalid MCP protocol version"
            }
            require(protocolVersion.none { it.isISOControl() }) { "Invalid MCP protocol version" }
            require(McpProtocolVersions.isSupported(protocolVersion)) { "Unsupported MCP protocol version" }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is McpServerConfig &&
            id == other.id &&
            name == other.name &&
            url == other.url &&
            enabled == other.enabled &&
            allowPrivateNetwork == other.allowPrivateNetwork &&
            allowedTools == other.allowedTools &&
            timeoutMillis == other.timeoutMillis &&
            maxTools == other.maxTools &&
            protocolVersion == other.protocolVersion

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + allowPrivateNetwork.hashCode()
        result = 31 * result + allowedTools.hashCode()
        result = 31 * result + timeoutMillis.hashCode()
        result = 31 * result + maxTools
        result = 31 * result + (protocolVersion?.hashCode() ?: 0)
        return result
    }

    /** Deliberately omits the endpoint and every possible secret value. */
    override fun toString(): String =
        "McpServerConfig(id=$id, enabled=$enabled, privateNetwork=$allowPrivateNetwork, " +
            "tools=${allowedTools.size}, maxTools=$maxTools)"

    private companion object {
        val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val IPV4_LITERAL = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")

        fun validToolName(value: String): Boolean =
            value.length in 1..McpLimits.MAX_TOOL_NAME_CHARS &&
                value.none { it.isISOControl() || it.isWhitespace() } &&
                runCatching { requireWellFormedUnicode(value) }.isSuccess

        fun validateUrl(value: String, allowPrivateNetwork: Boolean) {
            require(value.length in 1..McpLimits.MAX_URL_CHARS) { "Invalid MCP URL" }
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
            require(McpNetworkPolicy.isAllowed(address, false)) {
                "Private MCP endpoint requires explicit opt-in"
            }
        }
    }
}

/**
 * Strict versioned JSON encoding of [McpServerConfig]. Unknown fields, duplicate keys and
 * any secret-shaped field (token/password/header/credential/...) are rejected, so a token
 * cannot be smuggled into the configuration document. The URL is present because it is the
 * endpoint identity; credentials never are.
 */
object McpServerConfigCodec {
    const val FORMAT = "lnt-mcp-server-config"
    const val FORMAT_VERSION = 1

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
        "protocolVersion",
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

    fun encode(config: McpServerConfig): String {
        val encoded = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("id", config.id)
            put("name", config.name)
            put("url", config.url)
            put("enabled", config.enabled)
            put("allowPrivateNetwork", config.allowPrivateNetwork)
            put("allowedTools", buildJsonArray {
                config.allowedTools.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("timeoutMillis", config.timeoutMillis)
            put("maxTools", config.maxTools)
            put(
                "protocolVersion",
                config.protocolVersion?.let { JsonPrimitive(it) } ?: JsonNull,
            )
        }.toString()
        if (encoded.length > McpLimits.MAX_CONFIG_CHARS) throw InvalidMcpServerConfig()
        return encoded
    }

    /** Strict inverse of [encode]. Any unknown or secret-shaped field is rejected. */
    fun decode(text: String): McpServerConfig {
        try {
            val root = JsonObjectValue.parse(
                text,
                JsonInputLimits(McpLimits.MAX_CONFIG_CHARS, 8),
            ).json
            for (key in root.keys) {
                if (isSecretField(key)) throw InvalidMcpServerConfig()
            }
            require(root.keys == ROOT_KEYS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "formatVersion") == FORMAT_VERSION.toLong())
            val toolsElement = root.getValue("allowedTools") as? JsonArray
                ?: throw InvalidMcpServerConfig()
            require(toolsElement.size <= McpLimits.MAX_TOOLS)
            val tools = toolsElement.map { element ->
                val primitive = element as? JsonPrimitive ?: throw InvalidMcpServerConfig()
                require(primitive.isString)
                primitive.content
            }.toSet()
            val protocolElement = root.getValue("protocolVersion")
            val protocol = when (protocolElement) {
                JsonNull -> null
                is JsonPrimitive -> {
                    require(protocolElement.isString)
                    protocolElement.content
                }
                else -> throw InvalidMcpServerConfig()
            }
            return McpServerConfig(
                id = string(root, "id"),
                name = string(root, "name"),
                url = string(root, "url"),
                enabled = boolean(root, "enabled"),
                allowPrivateNetwork = boolean(root, "allowPrivateNetwork"),
                allowedTools = tools,
                timeoutMillis = integer(
                    root,
                    "timeoutMillis",
                    McpLimits.MIN_TIMEOUT_MILLIS,
                    WorkspaceHardLimits.MAX_TIMEOUT_MILLIS,
                ),
                maxTools = integer(root, "maxTools", 1L, McpLimits.MAX_TOOLS.toLong()).toInt(),
                protocolVersion = protocol,
            )
        } catch (invalid: InvalidMcpServerConfig) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidMcpServerConfig()
        }
    }

    private fun isSecretField(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
        return SECRET_WORDS.any { normalized.contains(it) } ||
            normalized == "auth" ||
            normalized == "headers" ||
            normalized.endsWith("header")
    }

    private fun integer(
        value: JsonObject,
        key: String,
        minimum: Long = 0,
        maximum: Long = Long.MAX_VALUE,
    ): Long {
        val primitive = value.getValue(key) as? JsonPrimitive ?: throw InvalidMcpServerConfig()
        require(!primitive.isString && INTEGER.matches(primitive.content))
        val parsed = primitive.content.toLongOrNull() ?: throw InvalidMcpServerConfig()
        if (parsed !in minimum..maximum) throw InvalidMcpServerConfig()
        return parsed
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value.getValue(key) as? JsonPrimitive ?: throw InvalidMcpServerConfig()
        require(primitive.isString)
        return primitive.content
    }

    private fun boolean(value: JsonObject, key: String): Boolean {
        val primitive = value.getValue(key) as? JsonPrimitive ?: throw InvalidMcpServerConfig()
        require(!primitive.isString)
        return primitive.booleanOrNull ?: throw InvalidMcpServerConfig()
    }
}
