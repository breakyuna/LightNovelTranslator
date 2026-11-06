package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McpCredentialTest {
    @Test
    fun credentialNeverRendersItsValue() {
        val credential = McpCredential.bearer("top-secret-token")
        assertEquals("Authorization", credential.headerName)
        assertFalse(credential.toString().contains("top-secret-token"))
        assertFalse(credential.toString().contains("Bearer"))
        assertEquals("Bearer top-secret-token", credential.valueForTransport())
    }

    @Test
    fun forbidsReservedAndMalformedHeaders() {
        assertThrows(IllegalArgumentException::class.java) {
            McpCredential.of("Host", "example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpCredential.of("Authorization", "Bearer a\r\nInjected: yes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpCredential.bearer("with space")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpCredential.of("X-Test", "")
        }
    }

    @Test
    fun noCredentialModeIsExplicit() = runBlocking {
        assertNull(McpNoCredentials.credentialFor("server"))
        assertEquals("McpNoCredentials", McpNoCredentials.toString())
    }

    @Test
    fun transportRequestDoesNotRenderBodyOrCredential() {
        val request = McpTransportRequest(
            body = """{"jsonrpc":"2.0","id":"lnt-1","method":"tools/call","params":{"token":"leak"}}""",
            sessionId = "session",
            protocolVersion = "2025-06-18",
            deadlineAtMillis = System.currentTimeMillis() + 10_000,
            cancellation = WorkspaceCancellation(),
            expectsResponse = true,
        )
        assertFalse(request.toString().contains("leak"))
        assertFalse(request.toString().contains("tools/call"))
        assertFalse(request.toString().contains("jsonrpc"))
    }
}
