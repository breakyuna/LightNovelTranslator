package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class RpcCall(val id: String?, val method: String, val params: JsonObject)

private class FixtureTransport(
    private val handler: (RpcCall) -> McpTransportResponse,
) : McpTransport {
    val calls = ArrayList<Pair<RpcCall, McpTransportRequest>>()

    override suspend fun send(request: McpTransportRequest): McpTransportResponse {
        val call = parseRpcCall(request)
        calls += call to request
        return handler(call)
    }

    private fun parseRpcCall(request: McpTransportRequest): RpcCall {
        val root = Json.parseToJsonElement(request.body).jsonObject
        val id = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw AssertionError("Missing method")
        val params = root["params"] as? JsonObject ?: buildJsonObject { }
        return RpcCall(id, method, params)
    }
}

private fun jsonResponse(id: String, result: JsonObject): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}.toString()

private fun initializeResult(
    version: String = "2025-06-18",
    tools: Boolean = true,
): JsonObject = buildJsonObject {
    put("protocolVersion", version)
    put("capabilities", buildJsonObject {
        if (tools) put("tools", buildJsonObject { put("listChanged", false) })
    })
    put("serverInfo", buildJsonObject {
        put("name", "fixture")
        put("version", "1.0")
    })
}

private fun tool(
    name: String,
    description: String = "fixture tool",
    readOnly: Boolean = false,
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put("inputSchema", buildJsonObject { put("type", "object") })
    if (readOnly) {
        put("annotations", buildJsonObject { put("readOnlyHint", true) })
    }
}

private fun toolsPage(vararg tools: JsonObject, cursor: String? = null): JsonObject = buildJsonObject {
    put("tools", buildJsonArray { tools.forEach { add(it) } })
    if (cursor != null) put("nextCursor", cursor)
}

private fun callResult(text: String): JsonObject = buildJsonObject {
    put("content", buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    })
}

private fun fixture(
    initialize: JsonObject = initializeResult(),
    initializeContentType: String = "application/json",
    onList: (JsonObject) -> JsonObject = { toolsPage() },
    onCall: (JsonObject) -> JsonObject = { callResult("ok") },
): FixtureTransport = FixtureTransport { call ->
    when (call.method) {
        "initialize" -> McpTransportResponse(
            jsonResponse(requireNotNull(call.id), initialize),
            initializeContentType,
            "session-1",
        )
        "notifications/initialized" -> McpTransportResponse("", null, null)
        "tools/list" -> McpTransportResponse(
            jsonResponse(requireNotNull(call.id), onList(call.params)),
            "application/json",
            null,
        )
        "tools/call" -> McpTransportResponse(
            jsonResponse(requireNotNull(call.id), onCall(call.params)),
            "application/json",
            null,
        )
        else -> throw AssertionError("Unexpected MCP method: ${call.method}")
    }
}

private fun testConfig(
    allowedTools: Set<String> = setOf("search"),
    maxTools: Int = 8,
    protocolVersion: String? = null,
    enabled: Boolean = true,
) = McpServerConfig(
    id = "server",
    name = "Server",
    url = "https://mcp.example.com/mcp",
    enabled = enabled,
    allowedTools = allowedTools,
    maxTools = maxTools,
    protocolVersion = protocolVersion,
)

private fun futureDeadline(): Long = System.currentTimeMillis() + 30_000

private fun arguments(text: String = """{"query":"x"}"""): JsonObjectValue =
    JsonObjectValue.parse(text, JsonInputLimits(4_096, 8))

private suspend fun expectFailure(block: suspend () -> Unit): McpException {
    try {
        block()
    } catch (failure: McpException) {
        return failure
    }
    throw AssertionError("Expected McpException")
}

class McpClientTest {
    @Test
    fun initializeNegotiatesAndSendsInitializedNotification() = runBlocking {
        val transport = fixture(initialize = initializeResult(version = "2025-03-26"))
        val client = McpClient(testConfig(), transport)
        val info = client.initialize(WorkspaceCancellation(), futureDeadline())

        assertEquals("2025-03-26", info.protocolVersion)
        assertEquals("fixture", info.serverName)
        assertEquals("1.0", info.serverVersion)
        assertEquals("session-1", info.sessionId)
        assertTrue(info.hasToolsCapability)
        assertEquals(2, transport.calls.size)
        assertEquals("initialize", transport.calls[0].first.method)
        assertNull(transport.calls[0].second.sessionId)
        assertNull(transport.calls[0].second.protocolVersion)
        assertEquals("notifications/initialized", transport.calls[1].first.method)
        assertNull(transport.calls[1].first.id)
        assertEquals("session-1", transport.calls[1].second.sessionId)
        assertEquals("2025-03-26", transport.calls[1].second.protocolVersion)
    }

    @Test
    fun pinnedVersionMismatchFailsBeforeNotification() = runBlocking {
        val transport = fixture(initialize = initializeResult(version = "2025-03-26"))
        val client = McpClient(testConfig(protocolVersion = "2025-06-18"), transport)
        val failure = expectFailure { client.initialize(WorkspaceCancellation(), futureDeadline()) }
        assertEquals(McpFailureKind.UNSUPPORTED_PROTOCOL, failure.kind)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun unsupportedServerVersionFailsClosed() = runBlocking {
        val transport = fixture(initialize = initializeResult(version = "1999-01-01"))
        val client = McpClient(testConfig(), transport)
        val failure = expectFailure { client.initialize(WorkspaceCancellation(), futureDeadline()) }
        assertEquals(McpFailureKind.UNSUPPORTED_PROTOCOL, failure.kind)
    }

    @Test
    fun missingToolsCapabilityFailsListAndCall() = runBlocking {
        val transport = fixture(initialize = initializeResult(tools = false))
        val client = McpClient(testConfig(), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        assertEquals(
            McpFailureKind.CAPABILITY_MISSING,
            expectFailure { client.listTools(WorkspaceCancellation(), futureDeadline()) }.kind,
        )
        assertEquals(
            McpFailureKind.CAPABILITY_MISSING,
            expectFailure {
                client.callTool("search", arguments(), WorkspaceCancellation(), futureDeadline())
            }.kind,
        )
        assertEquals(0, transport.calls.count { it.first.method == "tools/list" })
        assertEquals(0, transport.calls.count { it.first.method == "tools/call" })
    }

    @Test
    fun listToolsFollowsCursorAndStopsAtMaxTools() = runBlocking {
        val config = testConfig(setOf("alpha", "beta", "gamma", "delta"), maxTools = 2)
        val transport = fixture(onList = { params ->
            if (params["cursor"] == null) {
                toolsPage(tool("alpha"), cursor = "next")
            } else {
                toolsPage(tool("beta"), tool("gamma"), tool("delta"))
            }
        })
        val client = McpClient(config, transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val catalog = client.listTools(WorkspaceCancellation(), futureDeadline())

        assertEquals(2, catalog.size)
        assertTrue(catalog.truncated)
        assertEquals(listOf("mcp_server_alpha", "mcp_server_beta"), catalog.tools.map { it.modelName })
        assertEquals(2, transport.calls.count { it.first.method == "tools/list" })
    }

    @Test
    fun singlePageOverflowIsTruncatedWithoutAnotherRequest() = runBlocking {
        val transport = fixture(onList = { toolsPage(tool("alpha"), tool("beta")) })
        val client = McpClient(testConfig(setOf("alpha", "beta"), maxTools = 1), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val catalog = client.listTools(WorkspaceCancellation(), futureDeadline())
        assertEquals(1, catalog.size)
        assertTrue(catalog.truncated)
        assertEquals(1, transport.calls.count { it.first.method == "tools/list" })
    }

    @Test
    fun disallowedToolsAreNotExposedOrCallable() = runBlocking {
        val transport = fixture(onList = { toolsPage(tool("alpha"), tool("beta", readOnly = true)) })
        val client = McpClient(testConfig(setOf("alpha")), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val catalog = client.listTools(WorkspaceCancellation(), futureDeadline())
        assertEquals(listOf("mcp_server_alpha"), catalog.tools.map { it.modelName })
        assertFalse(catalog.tools.single().readOnly)

        val failure = expectFailure {
            client.callTool("beta", arguments(), WorkspaceCancellation(), futureDeadline())
        }
        assertEquals(McpFailureKind.TOOL_NOT_ALLOWED, failure.kind)
        assertEquals(0, transport.calls.count { it.first.method == "tools/call" })
    }

    @Test
    fun readOnlyAnnotationIsParsed() = runBlocking {
        val transport = fixture(onList = { toolsPage(tool("alpha", readOnly = true)) })
        val client = McpClient(testConfig(setOf("alpha")), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        assertTrue(client.listTools(WorkspaceCancellation(), futureDeadline()).tools.single().readOnly)
    }

    @Test
    fun modelNameCollisionIsRejected() = runBlocking {
        val transport = fixture(onList = { toolsPage(tool("a.b"), tool("a_b")) })
        val client = McpClient(testConfig(setOf("a.b", "a_b")), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val failure = expectFailure { client.listTools(WorkspaceCancellation(), futureDeadline()) }
        assertEquals(McpFailureKind.NAME_CONFLICT, failure.kind)
    }

    @Test
    fun longExternalNamesMapToTheModelCharsetAndLength() {
        val mapped = McpToolNames.modelName("server", "search." + "x".repeat(200))
        assertTrue(McpToolNames.isValidModelName(mapped))
        assertEquals(McpLimits.MAX_MODEL_TOOL_NAME_CHARS, mapped.length)
    }

    @Test
    fun callToolSendsExternalNameAndParsesTextContent() = runBlocking {
        val transport = fixture(onCall = { params ->
            assertEquals("search", params["name"]?.jsonPrimitive?.content)
            assertEquals("x", params["arguments"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
            callResult("bounded result")
        })
        val client = McpClient(testConfig(), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val result = client.callTool("search", arguments(), WorkspaceCancellation(), futureDeadline())

        assertFalse(result.isError)
        assertEquals("search", result.toolName)
        assertEquals("bounded result", (result.content.single() as McpContent.Text).text)
        assertEquals(1, transport.calls.count { it.first.method == "tools/call" })
    }

    @Test
    fun toolsCallFailureIsNotRetried() = runBlocking {
        var calls = 0
        val transport = FixtureTransport { call ->
            when (call.method) {
                "initialize" -> McpTransportResponse(
                    jsonResponse(requireNotNull(call.id), initializeResult()),
                    "application/json",
                    "session-1",
                )
                "notifications/initialized" -> McpTransportResponse("", null, null)
                "tools/call" -> {
                    calls += 1
                    throw McpException(McpFailureKind.HTTP_STATUS, httpStatus = 500)
                }
                else -> throw AssertionError("Unexpected method")
            }
        }
        val client = McpClient(testConfig(), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        val failure = expectFailure {
            client.callTool("search", arguments(), WorkspaceCancellation(), futureDeadline())
        }
        assertEquals(McpFailureKind.HTTP_STATUS, failure.kind)
        assertEquals(500, failure.httpStatus ?: -1)
        assertEquals(1, calls)
    }

    @Test
    fun mismatchedResponseIdAndBatchAreRejected() = runBlocking {
        val mismatch = FixtureTransport { call ->
            McpTransportResponse(
                jsonResponse("wrong-id", initializeResult()),
                "application/json",
                null,
            )
        }
        val mismatchFailure = expectFailure {
            McpClient(testConfig(), mismatch).initialize(WorkspaceCancellation(), futureDeadline())
        }
        assertEquals(McpFailureKind.PROTOCOL, mismatchFailure.kind)

        val batch = FixtureTransport {
            McpTransportResponse(
                "[{\"jsonrpc\":\"2.0\",\"id\":\"lnt-1\",\"result\":{}}]",
                "application/json",
                null,
            )
        }
        val batchFailure = expectFailure {
            McpClient(testConfig(), batch).initialize(WorkspaceCancellation(), futureDeadline())
        }
        assertEquals(McpFailureKind.BATCH_NOT_SUPPORTED, batchFailure.kind)
    }

    @Test
    fun oversizedResponseIsRejectedByTheClient() = runBlocking {
        val body = "x".repeat(McpLimits.MAX_RESPONSE_CHARS + 1)
        val transport = FixtureTransport {
            McpTransportResponse(body, "application/json", null)
        }
        val failure = expectFailure {
            McpClient(testConfig(), transport).initialize(WorkspaceCancellation(), futureDeadline())
        }
        assertEquals(McpFailureKind.CONTENT_TOO_LARGE, failure.kind)
    }

    @Test
    fun cancellationAndExpiredDeadlineDoNotTouchTransport() = runBlocking {
        val transport = fixture()
        val client = McpClient(testConfig(), transport)
        val cancelled = WorkspaceCancellation().also { it.cancel() }
        assertEquals(
            McpFailureKind.CANCELLED,
            expectFailure { client.initialize(cancelled, futureDeadline()) }.kind,
        )
        assertEquals(
            McpFailureKind.TIMEOUT,
            expectFailure {
                client.initialize(WorkspaceCancellation(), System.currentTimeMillis() - 1)
            }.kind,
        )
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun sseInitializeResponseIsAccepted() = runBlocking {
        val transport = FixtureTransport { call ->
            when (call.method) {
                "initialize" -> McpTransportResponse(
                    "event: message\ndata: " +
                        jsonResponse(requireNotNull(call.id), initializeResult()) + "\n\n",
                    "text/event-stream",
                    "session-1",
                )
                "notifications/initialized" -> McpTransportResponse("", null, null)
                else -> throw AssertionError("Unexpected method")
            }
        }
        val client = McpClient(testConfig(), transport)
        val info = client.initialize(WorkspaceCancellation(), futureDeadline())
        assertEquals("2025-06-18", info.protocolVersion)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun initializeIsCachedForTheClientLifetime() = runBlocking {
        val transport = fixture()
        val client = McpClient(testConfig(), transport)
        val first = client.initialize(WorkspaceCancellation(), futureDeadline())
        val second = client.initialize(WorkspaceCancellation(), futureDeadline())
        assertEquals(first.protocolVersion, second.protocolVersion)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun callBeforeInitializeAndDisabledClientFailWithoutNetwork() = runBlocking {
        val transport = fixture()
        val client = McpClient(testConfig(), transport)
        assertEquals(
            McpFailureKind.NOT_INITIALIZED,
            expectFailure {
                client.callTool("search", arguments(), WorkspaceCancellation(), futureDeadline())
            }.kind,
        )
        assertEquals(0, transport.calls.size)

        val disabledTransport = fixture()
        val disabled = McpClient(testConfig(enabled = false), disabledTransport)
        assertEquals(
            McpFailureKind.DISABLED,
            expectFailure { disabled.initialize(WorkspaceCancellation(), futureDeadline()) }.kind,
        )
        assertEquals(0, disabledTransport.calls.size)
    }

    @Test
    fun requestIdsAreUniqueAcrossHandshakeAndCalls() = runBlocking {
        val transport = fixture(
            onList = { toolsPage(tool("search")) },
            onCall = { callResult("ok") },
        )
        val client = McpClient(testConfig(), transport)
        client.initialize(WorkspaceCancellation(), futureDeadline())
        client.listTools(WorkspaceCancellation(), futureDeadline())
        client.callTool("search", arguments(), WorkspaceCancellation(), futureDeadline())
        val ids = transport.calls.mapNotNull { it.first.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("lnt-") })
        assertNotNull(transport.calls.first().first.id)
    }
}
