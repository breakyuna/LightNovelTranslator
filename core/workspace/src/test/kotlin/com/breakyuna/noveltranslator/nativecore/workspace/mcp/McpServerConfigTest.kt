package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class McpServerConfigTest {
    private fun config(
        url: String = "https://mcp.example.com/mcp",
        allowPrivateNetwork: Boolean = false,
        protocolVersion: String? = null,
    ) = McpServerConfig(
        id = "server",
        name = "Server",
        url = url,
        allowPrivateNetwork = allowPrivateNetwork,
        allowedTools = setOf("search", "fetch"),
        timeoutMillis = 15_000,
        maxTools = 8,
        protocolVersion = protocolVersion,
    )

    @Test
    fun acceptsHttpAndHttpsWithoutCredentials() {
        assertEquals("https://mcp.example.com/mcp", config().url)
        assertEquals("http://mcp.example.com:8080/mcp", config("http://mcp.example.com:8080/mcp").url)
        assertEquals("2025-06-18", config(protocolVersion = "2025-06-18").protocolVersion)
    }

    @Test
    fun rejectsEmbeddedCredentialsQueryAndFragment() {
        for (url in listOf(
            "https://user:secret@mcp.example.com/mcp",
            "https://mcp.example.com/mcp?key=secret",
            "https://mcp.example.com/mcp#fragment",
            "ftp://mcp.example.com/mcp",
            "file:///tmp/mcp",
            "https:///missing-host",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { config(url) }
        }
    }

    @Test
    fun privateAndLoopbackEndpointsRequireExplicitOptIn() {
        for (url in listOf(
            "http://127.0.0.1:8080/mcp",
            "http://localhost:8080/mcp",
            "http://192.168.1.10/mcp",
            "http://[::1]:8080/mcp",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { config(url) }
            assertEquals(url, config(url, allowPrivateNetwork = true).url)
        }
    }

    @Test
    fun boundsAndAllowlistAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServerConfig(
                id = "s",
                name = "S",
                url = "https://example.com/mcp",
                protocolVersion = "1900-01-01",
            )
        }
    }

    @Test
    fun codecRoundTripsWithoutSecrets() {
        val original = config(protocolVersion = "2025-03-26")
        val encoded = McpServerConfigCodec.encode(original)
        assertEquals(original, McpServerConfigCodec.decode(encoded))
        assertFalse(encoded.contains("token", ignoreCase = true))
        assertFalse(encoded.contains("authorization", ignoreCase = true))
    }

    @Test
    fun codecRejectsUnknownAndSecretShapedFields() {
        val valid = McpServerConfigCodec.encode(config())
        assertThrows(InvalidMcpServerConfig::class.java) {
            McpServerConfigCodec.decode(valid.dropLast(1) + ",\"mystery\":1}")
        }
        for (key in listOf("token", "apiKey", "authorization", "headers", "client_secret", "password")) {
            assertThrows(key, InvalidMcpServerConfig::class.java) {
                McpServerConfigCodec.decode(valid.dropLast(1) + ",\"$key\":\"leak\"}")
            }
        }
    }

    @Test
    fun codecRejectsNonObjectAndMalformedInput() {
        assertThrows(InvalidMcpServerConfig::class.java) { McpServerConfigCodec.decode("[]") }
        assertThrows(InvalidMcpServerConfig::class.java) { McpServerConfigCodec.decode("not json") }
        assertThrows(InvalidMcpServerConfig::class.java) {
            McpServerConfigCodec.decode(
                "{\"format\":\"lnt-mcp-server-config\",\"formatVersion\":1,\"format\":\"lnt-mcp-server-config\"}",
            )
        }
    }
}
