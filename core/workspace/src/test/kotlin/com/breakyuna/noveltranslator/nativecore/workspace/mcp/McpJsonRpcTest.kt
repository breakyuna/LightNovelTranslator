package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpJsonRpcTest {
    private val id = "lnt-1"

    private fun response(result: String = "{}"): String =
        """{"jsonrpc":"2.0","id":"$id","result":$result}"""

    private fun error(code: Int, message: String = "server error"): String =
        """{"jsonrpc":"2.0","id":"$id","error":{"code":$code,"message":"$message"}}"""

    @Test
    fun parsesResultAndChecksId() {
        val result = McpJsonRpc.parseResponse(response("""{"ok":true}"""), id)
        assertEquals("true", result["ok"].toString())
        val mismatch = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(response(), "lnt-2")
        }
        assertEquals(McpFailureKind.PROTOCOL, mismatch.kind)
    }

    @Test
    fun parsesErrorWithoutRetainingServerText() {
        val failure = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(error(-32601, "secret provider text"), id)
        }
        assertEquals(McpFailureKind.RPC_ERROR, failure.kind)
        assertEquals(-32601, failure.rpcCode)
        assertEquals("McpException(kind=RPC_ERROR, httpStatus=null, rpcCode=-32601)", failure.toString())
        assertEquals(false, failure.toString().contains("secret provider text"))
    }

    @Test
    fun rejectsBatchesUnknownMembersAndDuplicates() {
        val batch = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse("""[{"jsonrpc":"2.0","id":"$id","result":{}}]""", id)
        }
        assertEquals(McpFailureKind.BATCH_NOT_SUPPORTED, batch.kind)

        val unknown = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(
                """{"jsonrpc":"2.0","id":"$id","result":{},"extra":1}""",
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, unknown.kind)

        val duplicate = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(
                """{"jsonrpc":"2.0","id":"$id","id":"$id","result":{}}""",
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, duplicate.kind)
    }

    @Test
    fun rejectsServerRequestsAndNotificationsInJsonBody() {
        val request = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(
                """{"jsonrpc":"2.0","id":"$id","method":"sampling/createMessage"}""",
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, request.kind)

        val notification = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(
                """{"jsonrpc":"2.0","method":"notifications/message","params":{}}""",
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, notification.kind)
    }

    @Test
    fun rejectsOversizedBodiesBeforeParsing() {
        val oversized = "x".repeat(McpLimits.MAX_RESPONSE_CHARS + 1)
        val failure = assertThrows(McpException::class.java) {
            McpJsonRpc.parseResponse(oversized, id)
        }
        assertEquals(McpFailureKind.CONTENT_TOO_LARGE, failure.kind)
    }

    @Test
    fun requestWriterProducesUniqueValidIds() {
        val first = Json.parseToJsonElement(
            McpJsonRpc.request("lnt-1", "tools/list", kotlinx.serialization.json.buildJsonObject { }),
        ).jsonObject
        assertEquals("lnt-1", first["id"]?.jsonPrimitive?.content)
        assertEquals("2.0", first["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("tools/list", first["method"]?.jsonPrimitive?.content)
        assertThrows(IllegalArgumentException::class.java) {
            McpJsonRpc.request("bad id", "tools/list", kotlinx.serialization.json.buildJsonObject { })
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpJsonRpc.request("lnt-1", "bad method", kotlinx.serialization.json.buildJsonObject { })
        }
    }

    @Test
    fun parsesSseDataLinesAndIgnoresNotifications() {
        val stream = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "event: ping\ndata: ignored\n\n" +
            "data: " + response("""{"ok":true}""") + "\n\n"
        val parsed = McpJsonRpc.parse(
            McpTransportResponse(stream, "text/event-stream; charset=utf-8", null),
            id,
        )
        assertEquals("true", parsed["ok"].toString())
    }

    @Test
    fun parsesMultilineSseDataAndFinalEventWithoutBlankLine() {
        val stream = "data: {\"jsonrpc\":\"2.0\",\r\ndata: \"id\":\"$id\",\r\ndata: \"result\":{}}\r\n"
        val parsed = McpJsonRpc.parse(McpTransportResponse(stream, "text/event-stream", null), id)
        assertEquals(0, parsed.size)
    }

    @Test
    fun rejectsUnknownSseEventAndMissingResponse() {
        val unknown = assertThrows(McpException::class.java) {
            McpJsonRpc.parse(
                McpTransportResponse("event: custom\ndata: " + response() + "\n\n", "text/event-stream", null),
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, unknown.kind)

        val missing = assertThrows(McpException::class.java) {
            McpJsonRpc.parse(
                McpTransportResponse("event: ping\ndata: ignored\n\n", "text/event-stream", null),
                id,
            )
        }
        assertEquals(McpFailureKind.PROTOCOL, missing.kind)
    }

    @Test
    fun rejectsUnsupportedContentType() {
        val failure = assertThrows(McpException::class.java) {
            McpJsonRpc.parse(McpTransportResponse(response(), "text/plain", null), id)
        }
        assertEquals(McpFailureKind.UNSUPPORTED_CONTENT, failure.kind)
    }
}
