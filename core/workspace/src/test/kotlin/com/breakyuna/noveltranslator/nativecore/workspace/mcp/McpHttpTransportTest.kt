package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpTransportTest {
    private val config = McpServerConfig(
        id = "server",
        name = "Server",
        url = "https://mcp.example.com/mcp",
        allowedTools = setOf("search"),
    )

    private fun request(
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
        deadlineAtMillis: Long = System.currentTimeMillis() + 30_000,
        expectsResponse: Boolean = true,
    ) = McpTransportRequest(
        body = """{"jsonrpc":"2.0","id":"lnt-1","method":"tools/list","params":{}}""",
        sessionId = null,
        protocolVersion = null,
        deadlineAtMillis = deadlineAtMillis,
        cancellation = cancellation,
        expectsResponse = expectsResponse,
    )

    private fun settings(): ModelNetworkSettingsProvider = object : ModelNetworkSettingsProvider {
        override suspend fun networkSettings(): ModelNetworkSettings = ModelNetworkSettings.DEFAULT
    }

    @Test
    fun privatePolicyRequiresOptInButAlwaysRejectsMetadata() {
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("127.0.0.1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("127.0.0.1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("10.0.0.1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("10.0.0.1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("192.168.1.1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("192.168.1.1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("169.254.169.254"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("0.0.0.0"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("224.0.0.1"), true))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("8.8.8.8"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("2606:4700:4700::1111"), false))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("::1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("::1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("fc00::1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("fc00::1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("fe80::1"), true))
        assertFalse(McpNetworkPolicy.isAllowed(InetAddress.getByName("::ffff:127.0.0.1"), false))
        assertTrue(McpNetworkPolicy.isAllowed(InetAddress.getByName("::ffff:127.0.0.1"), true))
    }

    @Test
    fun cancelledRequestDoesNotReadSettingsOrOpenNetwork() = runBlocking {
        var reads = 0
        val network = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings {
                reads += 1
                error("must not read settings")
            }
        }
        val cancellation = WorkspaceCancellation().also { it.cancel() }
        val failure = expectFailure {
            McpHttpTransport(config, network).send(request(cancellation))
        }
        assertFailure(McpFailureKind.CANCELLED, failure)
        org.junit.Assert.assertEquals(0, reads)
    }

    @Test
    fun expiredDeadlineAndDisabledConfigFailBeforeNetwork() = runBlocking {
        val transport = McpHttpTransport(config, settings())
        assertFailure(
            McpFailureKind.TIMEOUT,
            expectFailure { transport.send(request(deadlineAtMillis = System.currentTimeMillis() - 1)) },
        )
        val disabled = McpHttpTransport(
            McpServerConfig(
                id = "off",
                name = "Off",
                url = "https://mcp.example.com/mcp",
                enabled = false,
            ),
            settings(),
        )
        assertFailure(McpFailureKind.DISABLED, expectFailure { disabled.send(request()) })
    }

    @Test
    fun settingsFailureDoesNotFallBackToDirectConnection() = runBlocking {
        val network = object : ModelNetworkSettingsProvider {
            override suspend fun networkSettings(): ModelNetworkSettings = error("unavailable")
        }
        val failure = expectFailure { McpHttpTransport(config, network).send(request()) }
        assertFailure(McpFailureKind.INTERNAL, failure)
    }

    @Test
    fun credentialFailureDoesNotReachDns() = runBlocking {
        val credentials = object : McpCredentialPort {
            override suspend fun credentialFor(serverId: String): McpCredential = error("unavailable")
        }
        val failure = expectFailure {
            McpHttpTransport(config, settings(), credentials).send(request())
        }
        assertFailure(McpFailureKind.CREDENTIAL_UNAVAILABLE, failure)
    }

    @Test
    fun boundedReaderStopsAtTwoMiBAndCancellation() = runBlocking {
        val exactlyLimit = ByteArray(McpLimits.MAX_RESPONSE_BYTES.toInt()) { 1.toByte() }
        val accepted = readBoundedBytes(
            ByteArrayInputStream(exactlyLimit),
            WorkspaceCancellation(),
            McpLimits.MAX_RESPONSE_BYTES,
        )
        org.junit.Assert.assertEquals(exactlyLimit.size, accepted.size)

        val tooLarge = ByteArrayInputStream(ByteArray(McpLimits.MAX_RESPONSE_BYTES.toInt() + 1))
        assertFailure(
            McpFailureKind.CONTENT_TOO_LARGE,
            expectFailure {
                readBoundedBytes(tooLarge, WorkspaceCancellation(), McpLimits.MAX_RESPONSE_BYTES)
            },
        )

        val cancelled = WorkspaceCancellation().also { it.cancel() }
        assertFailure(
            McpFailureKind.CANCELLED,
            expectFailure {
                readBoundedBytes(ByteArrayInputStream(ByteArray(1)), cancelled)
            },
        )
    }

    @Test
    fun malformedUtf8IsRejected() {
        assertThrows(McpException::class.java) { decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    private fun assertFailure(expected: McpFailureKind, failure: McpException) {
        org.junit.Assert.assertEquals(expected, failure.kind)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): McpException {
        try {
            block()
        } catch (failure: McpException) {
            return failure
        }
        throw AssertionError("Expected McpException")
    }
}
