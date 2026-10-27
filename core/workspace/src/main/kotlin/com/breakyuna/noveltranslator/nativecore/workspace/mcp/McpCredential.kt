package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import java.util.Locale

/**
 * One resolved MCP credential. The value is never part of configuration, logs, exception
 * messages or [toString]; only the HTTP transport reads it through an internal accessor.
 * This class does not persist anything and does not implement Keystore storage.
 */
class McpCredential private constructor(
    val headerName: String,
    private val headerValue: String,
) {
    internal fun valueForTransport(): String = headerValue

    override fun toString(): String = "McpCredential(header=$headerName, value=redacted)"

    companion object {
        private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        private val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "content-type",
            "transfer-encoding",
            "connection",
            "accept",
            "mcp-session-id",
            "mcp-protocol-version",
            "proxy-authorization",
        )

        /** A header value is printable ASCII and has no surrounding whitespace. */
        fun of(headerName: String, headerValue: String): McpCredential {
            require(HEADER_NAME.matches(headerName)) { "Invalid credential header name" }
            require(headerName.lowercase(Locale.ROOT) !in FORBIDDEN_HEADERS) {
                "Credential header is reserved"
            }
            require(headerValue.length in 1..8_192) { "Invalid credential header value" }
            require(headerValue == headerValue.trim()) { "Invalid credential header value" }
            require(headerValue.all { it.code in 0x20..0x7e }) { "Invalid credential header value" }
            return McpCredential(headerName, headerValue)
        }

        /** Common bearer-token form; the token itself must be visible ASCII without spaces. */
        fun bearer(token: String): McpCredential {
            require(token.length in 1..8_000 && token.all { it.code in 0x21..0x7e }) {
                "Invalid bearer token"
            }
            return of("Authorization", "Bearer $token")
        }
    }
}

/**
 * Reads a server credential when a request is made. Implementations own secure storage and
 * must return null for an explicitly unauthenticated server; this core module never stores
 * or serializes the returned value.
 */
interface McpCredentialPort {
    suspend fun credentialFor(serverId: String): McpCredential?
}

/** Explicit no-authentication mode. It never sends an empty Authorization header. */
object McpNoCredentials : McpCredentialPort {
    override suspend fun credentialFor(serverId: String): McpCredential? = null

    override fun toString(): String = "McpNoCredentials"
}
